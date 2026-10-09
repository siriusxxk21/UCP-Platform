package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleService;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * 合并验收：规则经对象设计保存、发布、应用同步进入运行期，保存记录时由路 B 求值器与路 C 强制落库。
 *
 * <p>不用脚本替身，不用 SQL 写规则快照；只读业务表核对落库值。
 */
class FieldRuleEndToEndIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition flow;
    private DataCenter.Definition voucher;
    private String app;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new FieldRuleFixture();
        flow =
                f.object(
                        "flow",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("bank", "银行", "TEXT"),
                                field("amount", "金额", "MONEY", 18, 2)),
                        Map.of(),
                        List.of(),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("bank", "银行名", "TEXT"),
                                field("sum_floor", "向下取整合计", "MONEY", 18, 0),
                                field("sum_half", "四舍五入合计", "MONEY", 18, 0),
                                field("sum_down", "去掉小数合计", "MONEY", 18, 0)),
                        Map.of(),
                        List.of(),
                        List.of());
        app = f.app(flow, voucher);
        // 这一组用例走的是「对象发布 → 人工同步应用 → 重新发布」：把自动跟随单独关掉，保留人工同步这条路径的覆盖。
        NocodeIntegrationSupport.stopFollowing(app);
        flowRow("甲", "甲-住信", "100.50");
        flowRow("甲", "甲-瑞穗", "200.40");
        flowRow("甲", "甲-三菱", "300");
        flowRow("乙", "乙-三井", "-1.50");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    /** 只读联动：设计保存发布后，应用同步前仍按旧版本不强制；同步发布后伪造值被服务端重算值覆盖。 */
    @Test
    void readOnlyLinkageForcedAfterDesignPublishAndApplicationSync() {
        var enforcer = servicesContext.getBean(FieldRuleEnforcer.class);
        assertThat(ReflectionTestUtils.getField(enforcer, "evaluator"))
                .isInstanceOf(FieldRuleService.class);
        String company = id(voucher, "company");
        String bank = id(voucher, "bank");
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        changes.put(bank, options(bank).withRules(readOnlyLinkage("bank", "FIRST", null)));
        voucher = f.republish(voucher, changes);
        var saved = voucher.fieldOptions().get(bank).rules();
        assertThat(saved.linkage().readOnly()).isTrue();
        assertThat(saved.linkage().conditions().getFirst().formFieldId()).isEqualTo(company);

        var pinned = f.save(app, voucher, values(name(), "同步前", company, "甲", bank, "伪造"));
        assertThat(pinned.values()).containsEntry(bank, "伪造");
        assertThat(stored(pinned.id(), bank)).isEqualTo("伪造");

        syncApplication();
        var created = f.save(app, voucher, values(name(), "同步后", company, "甲", bank, "伪造"));
        assertThat(created.values()).containsEntry(bank, "甲-住信");
        assertThat(stored(created.id(), bank)).isEqualTo("甲-住信");
        var forged = update(created, values(bank, "再伪造"));
        assertThat(forged.values()).containsEntry(bank, "甲-住信");
        var moved = update(forged, values(company, "乙"));
        assertThat(moved.values()).containsEntry(bank, "乙-三井");
        assertThat(stored(created.id(), bank)).isEqualTo("乙-三井");
    }

    /**
     * 2026-09-29 只读口径，经真实求值器落库：readOnly 为 null 的联动按只读强制；未命中（NO_MATCH）或依赖未填（PENDING_ROW_VALUE）时字段写空，
     * 手填与库中旧值都不保留。
     */
    @Test
    void readOnlyByDefaultClearsWhenNothingMatched() {
        String company = id(voucher, "company");
        String bank = id(voucher, "bank");
        var condition = formField(id(flow, "company"), "eq", company);
        var linkage =
                new FieldRules.Linkage(
                        flow.objectId(),
                        List.of(condition),
                        id(flow, "bank"),
                        "FIRST",
                        null,
                        null,
                        null);
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        changes.put(
                bank,
                options(bank).withRules(new FieldRules(null, linkage, null, null, null, null)));
        voucher = f.republish(voucher, changes);
        assertThat(voucher.fieldOptions().get(bank).rules().linkage().readOnly()).isNull();
        syncApplication();

        var created = f.save(app, voucher, values(name(), "缺省只读", company, "甲", bank, "伪造"));
        assertThat(stored(created.id(), bank)).isEqualTo("甲-住信");
        var unmatched = update(created, values(company, "丙", bank, "手填"));
        assertThat(unmatched.values().get(bank)).isNull();
        assertThat(stored(created.id(), bank)).isNull();
        var pending = f.save(app, voucher, values(name(), "未填公司", bank, "手填"));
        assertThat(stored(pending.id(), bank)).isNull();
    }

    /** 数据联动求和到 MONEY 目标：三档取整方式各一，经真实求值落库（600.9 与 -1.5 两组）。 */
    @Test
    void moneySumRoundingPersistedForEachMode() {
        String company = id(voucher, "company");
        String floor = id(voucher, "sum_floor");
        String half = id(voucher, "sum_half");
        String down = id(voucher, "sum_down");
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        changes.put(floor, options(floor).withRules(readOnlyLinkage("amount", "SUM", "FLOOR")));
        changes.put(half, options(half).withRules(readOnlyLinkage("amount", "SUM", "HALF_UP")));
        changes.put(down, options(down).withRules(readOnlyLinkage("amount", "SUM", "DOWN")));
        voucher = f.republish(voucher, changes);
        assertThat(voucher.fieldOptions().get(half).rules().rounding()).isEqualTo("HALF_UP");
        syncApplication();

        var input = values(name(), "甲合计", company, "甲", floor, "1", half, "1", down, "1");
        var positive = f.save(app, voucher, input);
        assertThat(money(stored(positive.id(), floor))).isEqualByComparingTo("600");
        assertThat(money(stored(positive.id(), half))).isEqualByComparingTo("601");
        assertThat(money(stored(positive.id(), down))).isEqualByComparingTo("600");
        var negative = f.save(app, voucher, values(name(), "乙合计", company, "乙"));
        assertThat(money(stored(negative.id(), floor))).isEqualByComparingTo("-2");
        assertThat(money(stored(negative.id(), half))).isEqualByComparingTo("-2");
        assertThat(money(stored(negative.id(), down))).isEqualByComparingTo("-1");
    }

    private void flowRow(String company, String bank, Object amount) {
        var input =
                values(
                        id(flow, "name"),
                        bank,
                        id(flow, "company"),
                        company,
                        id(flow, "bank"),
                        bank,
                        id(flow, "amount"),
                        amount);
        f.save(app, flow, input);
    }

    private FieldRules readOnlyLinkage(String valueCode, String multiRow, String rounding) {
        var condition = formField(id(flow, "company"), "eq", id(voucher, "company"));
        var linkage =
                new FieldRules.Linkage(
                        flow.objectId(),
                        List.of(condition),
                        id(flow, valueCode),
                        multiRow,
                        true,
                        null,
                        null);
        return new FieldRules(null, linkage, null, rounding, null, null);
    }

    private String name() {
        return id(voucher, "name");
    }

    private DataCenter.FieldOptions options(String fieldId) {
        var options = voucher.fieldOptions().get(fieldId);
        assertThat(options).as("已发布快照包含字段扩展属性：" + fieldId).isNotNull();
        return options;
    }

    /** 应用同步：引用的对象改为各自最新发布版本，保存应用草稿后重新发布。 */
    private void syncApplication() {
        var api = servicesContext.getBean(DataObjectApi.class);
        var before = f.applications.get(app);
        var refs = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var r : before.draft().objects()) {
            var v = api.getVersion(r.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            v.objectId(), v.versionNo(), v.checksum()));
        }
        assertThat(refs).isNotEqualTo(before.draft().objects());
        var definition = new ApplicationCenter.Definition(refs, before.draft().resources());
        var request =
                new ApplicationCenter.Save(
                        app,
                        before.application().revision(),
                        before.application().code(),
                        before.application().name(),
                        null,
                        null,
                        definition);
        var saved = f.applications.save(request, 10001);
        grantApplicationObjects(app);
        f.publishSynced(saved.application());
    }

    private ApplicationRecords.Row update(ApplicationRecords.Row row, Map<String, Object> values) {
        var request =
                new ApplicationRecords.Save(
                        app, voucher.objectId(), row.id(), row.revision(), values, null);
        return f.runtime.save(request, 10001).record();
    }

    private Object stored(String recordId, String fieldId) {
        String column = voucher.fieldOptions().get(fieldId).columnName();
        String sql = "SELECT \"" + column + "\" FROM public.\"" + voucher.tableName() + "\"";
        return jdbc.queryForObject(sql + " WHERE id=?", Object.class, Long.valueOf(recordId));
    }

    private static BigDecimal money(Object value) {
        assertThat(value).isNotNull();
        return new BigDecimal(value.toString());
    }
}
