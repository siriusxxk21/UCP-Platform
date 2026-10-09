package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.LinkageSyncFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;

import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.*;

/**
 * 单条保存与「一次保存牵动很多目标」的语句数、耗时，以及锚点查询的执行计划（几分钟内跑完）。
 *
 * <p>硬断言：不参与的对象只多一条语句、值没变不产生写语句、语句数对目标数线性。
 */
@Tag("linkage-performance")
@EnabledIfEnvironmentVariable(named = "LINKAGE_PERFORMANCE", matches = "1")
class LinkageAutoUpdatePerformanceTest extends LinkagePerformanceSupport {
    /** 不参与任何自动更新的对象：单条保存恰好多 1 条语句（参与判断的 EXISTS），不取执行锁、不读索引。 */
    @Test
    void savingAnObjectOutsideAnyRuleCostsOneExtraStatement() {
        x.benchmark();
        var plain =
                x.object(
                        "plain",
                        List.of(field("memo", "备注", "TEXT")),
                        Map.of(),
                        List.of(),
                        DataCenter.Settings.defaults());
        String app = x.f.app(plain);
        x.f.save(app, plain, values(id(plain, "name"), "预热"));
        var save =
                measure(
                        "single-save outside-any-rule",
                        () -> x.f.save(app, plain, values(id(plain, "name"), "不参与")));
        assertThat(save.of("LinkageTriggerMapper.participates")).isEqualTo(1);
        assertThat(
                        save.statements().keySet().stream()
                                .filter(key -> key.startsWith("LinkageTriggerMapper."))
                                .toList())
                .containsExactly("LinkageTriggerMapper.participates");
        assertThat(save.of("ObjectDraftMapper.lockTableName")).as("不取全局执行锁").isZero();
    }

    /** 一条规则、一个目标：值变了有固定的语句数（两次同样的操作语句数相同）；值没变不产生任何写语句。 */
    @Test
    void singleSourceSaveHasAFixedStatementCountAndWritesOnlyOnChange() {
        x.benchmark();
        var a = x.createFlow("流水甲");
        var b = x.createFlow("流水乙");
        var first = x.createVoucher("凭证一", a.id(), "ylr");
        var second = x.createVoucher("凭证二", b.id(), "ylr");
        String status = id(x.voucher, "status");
        var changed =
                measure(
                        "single-save one-target value-changed",
                        () -> x.update(x.voucher, first.id(), Map.of(status, "ysh")));
        var changedAgain =
                measure(
                        "single-save one-target value-changed (repeat)",
                        () -> x.update(x.voucher, second.id(), Map.of(status, "ysh")));
        assertThat(changedAgain.total()).as("同样的操作语句数相同").isEqualTo(changed.total());
        assertThat(changed.of("RecordMapper.update")).as("来源一次、目标一次").isEqualTo(2);
        assertThat(changed.of("ObjectDraftMapper.lockTableName")).isGreaterThanOrEqualTo(1);
        var unchanged =
                measure(
                        "single-save one-target value-unchanged",
                        () -> x.update(x.voucher, first.id(), Map.of(id(x.voucher, "kind"), "无关")));
        assertThat(unchanged.of("RecordMapper.update")).as("只有来源自己那一次写").isEqualTo(1);
        assertThat(unchanged.of("RecordHistoryMapper.append")).isEqualTo(1);
        assertThat(unchanged.total()).isLessThan(changed.total());
    }

    private DataCenter.Definition customer;
    private DataCenter.Definition order;

    private void recordKeyScenario() {
        customer =
                x.object(
                        "customer",
                        List.of(field("level", "等级", "TEXT")),
                        Map.of(),
                        List.of(),
                        DataCenter.Settings.defaults());
        order =
                x.object(
                        "order",
                        List.of(field("customer_level", "客户等级", "TEXT")),
                        Map.of(),
                        List.of(reference("customer", customer)),
                        DataCenter.Settings.defaults());
        order =
                rules(
                        order,
                        id(order, "customer_level"),
                        auto(
                                customer,
                                id(customer, "level"),
                                "FIRST",
                                "无",
                                formField("$record", "eq", relationField(order, "customer"))));
        x.app = x.f.app(customer, order);
    }

    private Row customerWithOrders(String name, int orders) {
        var c =
                x.f.save(
                        x.app,
                        customer,
                        values(id(customer, "name"), name, id(customer, "level"), "A"));
        jdbc.update(
                "INSERT INTO public.\""
                        + order.tableName()
                        + "\"(name, customer_level, \""
                        + refColumn(order, "customer")
                        + "\", creator, updater, create_time,"
                        + " update_time, deleted) SELECT ? || g, 'A', CAST(? AS bigint), '10001',"
                        + " '10001', clock_timestamp(), clock_timestamp(), 0 FROM"
                        + " generate_series(1, ?) g",
                name,
                c.id(),
                orders);
        return c;
    }

    /**
     * 一次保存牵动很多目标：语句数对目标数线性（50 / 100 / 200 三点），并记下 200 条时的总耗时。
     *
     * <p>先用一个小客户预热：第一次牵动会多出几条只发生一次的语句（实测首轮 50 条比按斜率推算的多 8 条），不预热三点就不严格共线。 断言留 1%
     * 的余量，钉的是「线性、不随目标数加速增长」，不是某个具体条数。
     */
    @Test
    void statementsGrowLinearlyWithTheNumberOfTargets() {
        recordKeyScenario();
        var warm = customerWithOrders("预热-", 5);
        x.update(customer, warm.id(), Map.of(id(customer, "level"), "B"));
        Map<Integer, Measured<Row>> runs = new LinkedHashMap<>();
        for (int size : new int[] {50, 100, 200}) {
            var c = customerWithOrders("客户" + size + "-", size);
            runs.put(
                    size,
                    measure(
                            "one-save targets=" + size,
                            () -> x.update(customer, c.id(), Map.of(id(customer, "level"), "B"))));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.\""
                                            + order.tableName()
                                            + "\" WHERE \""
                                            + refColumn(order, "customer")
                                            + "\"::text = ? AND"
                                            + " customer_level = 'B'",
                                    Long.class,
                                    c.id()))
                    .isEqualTo(size);
        }
        long low = runs.get(100).total() - runs.get(50).total();
        long high = runs.get(200).total() - runs.get(100).total();
        System.out.println(
                "LINKAGE_PERFORMANCE statements-per-target low="
                        + low / 50.0
                        + " high="
                        + high / 100.0
                        + " ms-per-target(200)="
                        + runs.get(200).millis() / 200.0);
        assertThat(Math.abs(high - 2 * low))
                .as("每多一个目标多出的语句数是常数：100→200 多出的语句数应是 50→100 的两倍（余量为总数的 1%）")
                .isLessThanOrEqualTo(runs.get(200).total() / 100);
    }

    /** 锚点查询的执行计划（引用列没有普通索引时是全表扫）：只打出来留档，不设断言。 */
    @Test
    void anchorLookupQueryPlans() {
        recordKeyScenario();
        var c = customerWithOrders("计划-", 5000);
        jdbc.execute("ANALYZE public.\"" + order.tableName() + "\"");
        for (String line :
                jdbc.queryForList(
                        "EXPLAIN SELECT id FROM public.\""
                                + order.tableName()
                                + "\" WHERE \""
                                + refColumn(order, "customer")
                                + "\" = "
                                + Long.parseLong(c.id())
                                + " AND deleted = 0 LIMIT 201",
                        String.class))
            System.out.println(
                    "LINKAGE_PERFORMANCE plan RECORD_KEY-reverse-lookup rows=5000 | " + line);
        for (String line :
                jdbc.queryForList(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename"
                                + " = ?",
                        String.class,
                        order.tableName()))
            System.out.println("LINKAGE_PERFORMANCE plan indexes | " + line);
    }
}
