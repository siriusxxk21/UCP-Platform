package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldConversionDependencyInspector.Impact;
import com.richuang.os.nocode.application.service.resource.ApplicationRuleWriteExclusion;
import com.richuang.os.nocode.application.service.resource.ApplicationRuleWriteExclusion.Writer;
import com.richuang.os.nocode.metadata.service.object.FieldRuleValidator;
import com.richuang.os.nocode.metadata.service.object.ObjectFieldConversionDependencies;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 两条开发线合并后的守卫（纯内存，不连数据库）：
 *
 * <ul>
 *   <li>字段类型转换的依赖检查认识对象规则：被联动、引用筛选、公式默认值、挑取值用到的字段改类型时逐处点名；
 *   <li>同一字段只能由一处写入：自动更新、留存动作的目标字段与数据联动、公式默认值互斥；
 *   <li>统计配置只替换指标时，列维度、透视选项、明细可编辑原样保留。
 * </ul>
 */
class MergeRuleGuardsTest {
    private static final String ORDER = "100", CUSTOMER = "200";
    private static final FieldRules.Condition BY_NAME = current("201", "eq", "101");

    // ── 类型转换依赖检查认识对象规则 ──

    private static Definition customer(String nameType) {
        return object(
                CUSTOMER,
                "客户",
                List.of(
                        field("201", "c_mc", "客户名称", nameType),
                        field("202", "c_dj", "等级", "TEXT"),
                        field("203", "c_zt", "状态", "SELECT")),
                options(Map.entry("203", local("A", "B"))),
                List.of(),
                List.of());
    }

    private static Definition order(String memoType, Map<String, FieldOptions> overrides) {
        var options = options(Map.entry("106", local("A", "B")));
        options.putAll(overrides);
        return object(
                ORDER,
                "订单",
                List.of(
                        field("101", "c_bz", "备注", memoType),
                        field("102", "c_dj", "客户等级", "TEXT"),
                        field("103", "c_kh", "客户", "INTEGER"),
                        field("104", "c_sl", "数量", "INTEGER"),
                        field("105", "c_hj", "合计", "DECIMAL"),
                        field("106", "c_zt", "状态", "SELECT")),
                options,
                List.of(ref("r103", "103", CUSTOMER)),
                List.of());
    }

    private static List<Impact> inspect(
            Definition before, Definition after, String fieldId, Definition... others) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Definition other : others) definitions.put(other.objectId(), other);
        definitions.put(after.objectId(), after);
        return ObjectFieldConversionDependencies.inspectDefinitions(
                before, after, Set.of(fieldId), definitions);
    }

    @Test
    void conversionOfLinkageValueFieldInAnotherObjectIsListed() {
        // 订单.客户等级 联动取 客户.等级；把 客户.等级 从文本改成整数。
        var order = order("TEXT", Map.of("102", ruled(linkage(CUSTOMER, "202", "FIRST", BY_NAME))));
        var before = customer("TEXT");
        var after =
                object(
                        CUSTOMER,
                        "客户",
                        List.of(
                                field("201", "c_mc", "客户名称", "TEXT"),
                                field("202", "c_dj", "等级", "INTEGER"),
                                field("203", "c_zt", "状态", "SELECT")),
                        options(Map.entry("203", local("A", "B"))),
                        List.of(),
                        List.of());
        var impacts = inspect(before, after, "202", order);
        assertThat(impacts)
                .singleElement()
                .satisfies(
                        impact -> {
                            assertThat(impact.fieldId()).isEqualTo("202");
                            assertThat(impact.sourceId()).isEqualTo(ORDER);
                            assertThat(impact.location()).isEqualTo("主表字段 / 客户等级 / 数据联动");
                            assertThat(impact.message()).contains("数据联动仍按原类型取该字段的值");
                            assertThat(impact.blocking()).isTrue();
                        });
        // 不涉及的字段、未改类型的字段不点名。
        assertThat(inspect(before, before, "202", order)).isEmpty();
        assertThat(inspect(before, after, "202", order("TEXT", Map.of()))).isEmpty();
    }

    @Test
    void conversionOfConditionFieldsIsListedOnBothSides() {
        var order = order("TEXT", Map.of("102", ruled(linkage(CUSTOMER, "202", "FIRST", BY_NAME))));
        // 条件左侧：来源对象 客户.客户名称 改类型。
        assertThat(inspect(customer("TEXT"), customer("INTEGER"), "201", order))
                .extracting(Impact::location)
                .containsExactly("主表字段 / 客户等级 / 数据联动条件");
        // 条件右侧：本对象 订单.备注（当前字段）改类型。
        assertThat(
                        inspect(
                                order,
                                order(
                                        "INTEGER",
                                        Map.of(
                                                "102",
                                                ruled(linkage(CUSTOMER, "202", "FIRST", BY_NAME)))),
                                "101",
                                customer("TEXT")))
                .extracting(Impact::location)
                .containsExactly("主表字段 / 客户等级 / 数据联动条件");
    }

    @Test
    void conversionOfReferenceFilterLabelFormulaAndPickSourcesIsListed() {
        // 引用筛选与显示名字段：订单.客户 引用 客户，按 客户.状态 筛选、显示 客户.等级。
        var referencing =
                order("TEXT", Map.of("103", ruled(reference("202", constant("203", "eq", "A")))));
        var customerStatusText =
                object(
                        CUSTOMER,
                        "客户",
                        List.of(
                                field("201", "c_mc", "客户名称", "TEXT"),
                                field("202", "c_dj", "等级", "TEXT"),
                                field("203", "c_zt", "状态", "TEXT")),
                        options(),
                        List.of(),
                        List.of());
        assertThat(inspect(customer("TEXT"), customerStatusText, "203", referencing))
                .extracting(Impact::location)
                .containsExactly("主表字段 / 客户 / 引用筛选");
        // 公式默认值：订单.合计 = 数量 * 2；把 数量 改成文本。
        var formulaBefore = order("TEXT", Map.of("105", ruled(formula("c_sl * 2", null))));
        var formulaAfter =
                object(
                        ORDER,
                        "订单",
                        List.of(
                                field("101", "c_bz", "备注", "TEXT"),
                                field("102", "c_dj", "客户等级", "TEXT"),
                                field("103", "c_kh", "客户", "INTEGER"),
                                field("104", "c_sl", "数量", "TEXT"),
                                field("105", "c_hj", "合计", "DECIMAL"),
                                field("106", "c_zt", "状态", "SELECT")),
                        options(
                                Map.entry("106", local("A", "B")),
                                Map.entry("105", ruled(formula("c_sl * 2", null)))),
                        List.of(ref("r103", "103", CUSTOMER)),
                        List.of());
        assertThat(inspect(formulaBefore, formulaAfter, "104", customer("TEXT")))
                .extracting(Impact::location)
                .containsExactly("主表字段 / 合计 / 公式默认值");
        // 挑取值：订单.状态 取 客户.状态 的选项；客户.状态 改成文本。
        var picking = order("TEXT", Map.of("106", pick(CUSTOMER, "203")));
        // 来源字段只是改了选项集或选项来源（这里从局部选项换成公共字典）、仍是单选：挑取值照常取它生效的选项，不点名。
        var customerStatusDictionary =
                object(
                        CUSTOMER,
                        "客户",
                        List.of(
                                field("201", "c_mc", "客户名称", "TEXT"),
                                field("202", "c_dj", "等级", "TEXT"),
                                field("203", "c_zt", "状态", "SELECT")),
                        options(Map.entry("203", dictionary("customer_state"))),
                        List.of(),
                        List.of());
        assertThat(inspect(customer("TEXT"), customerStatusDictionary, "203", picking)).isEmpty();
        assertThat(inspect(customer("TEXT"), customerStatusText, "203", picking))
                .extracting(Impact::location)
                .containsExactly("主表字段 / 状态 / 挑取值");
    }

    @Test
    void usagesIgnoreTheConvertedFieldItselfAndInactiveFields() {
        // 被转换字段自身的规则随字段一起按新类型校验，不算「被别处引用」。
        var self = order("TEXT", Map.of("101", ruled(linkage(ORDER, "101", "FIRST"))));
        assertThat(FieldRuleValidator.usages(self, ORDER, "101")).isEmpty();
        var inactive =
                order(
                        "TEXT",
                        Map.of(
                                "102",
                                FieldOptions.copyOf(inactive())
                                        .rules(linkage(CUSTOMER, "202", "FIRST", BY_NAME))
                                        .build()));
        assertThat(FieldRuleValidator.usages(inactive, CUSTOMER, "202")).isEmpty();
    }

    // ── 自动更新 / 留存动作 与 数据联动 / 公式默认值 互斥 ──

    @Test
    void ruleWritersAndObjectRulesCannotShareAField() {
        var proposed =
                order(
                        "TEXT",
                        Map.of(
                                "102", ruled(linkage(CUSTOMER, "202", "FIRST", BY_NAME)),
                                "105", ruled(formula("c_sl * 2", null))));
        var writers =
                List.of(
                        new Writer("9", "自动更新", "同步等级", ORDER, "102"),
                        new Writer("9", "留存动作", "留存合计", ORDER, "105"),
                        // 写的是没有规则的字段、或别的对象：不冲突。
                        new Writer("9", "自动更新", "写备注", ORDER, "101"),
                        new Writer("9", "自动更新", "写客户", CUSTOMER, "102"));
        var conflicts = ApplicationRuleWriteExclusion.conflicts(proposed, writers, id -> "销售");
        assertThat(conflicts)
                .containsExactly(
                        "字段「客户等级」配置了数据联动，但应用“销售”的自动更新“同步等级”也在写入该字段；"
                                + "同一字段只能由一处写入，请先去掉该字段的数据联动，或在应用中调整该自动更新并发布",
                        "字段「合计」配置了公式默认值，但应用“销售”的留存动作“留存合计”也在写入该字段；"
                                + "同一字段只能由一处写入，请先去掉该字段的公式默认值，或在应用中调整该留存动作并发布");
        // 去掉规则后同样的写入不再冲突：两套能力都保留，只是不能落在同一个字段上。
        assertThat(
                        ApplicationRuleWriteExclusion.conflicts(
                                order("TEXT", Map.of()), writers, id -> "销售"))
                .isEmpty();
        assertThat(ApplicationRuleWriteExclusion.ruleLabel(proposed, "102")).isEqualTo("数据联动");
        assertThat(ApplicationRuleWriteExclusion.ruleLabel(proposed, "105")).isEqualTo("公式默认值");
        // 只有引用筛选（不写值）的字段不算被对象规则写入。
        assertThat(
                        ApplicationRuleWriteExclusion.ruleLabel(
                                order("TEXT", Map.of("103", ruled(reference("202")))), "103"))
                .isNull();
        assertThat(
                        ApplicationRuleWriteExclusion.publishMessage(
                                writers.getFirst(), proposed, "数据联动"))
                .isEqualTo(
                        "自动更新“同步等级”要写入的字段「客户等级」在数据对象“订单”上配置了数据联动；"
                                + "同一字段只能由一处写入，请改用其它目标字段，或先在数据对象上去掉该字段的数据联动");
    }

    // ── 统计配置只替换指标 ──

    @Test
    void reportConfigWithMetricsKeepsPivotSettings() {
        var row = new ApplicationReports.Dimension("101", null, "VALUE");
        var column = new ApplicationReports.Dimension("106", null, "VALUE");
        var metric = new ApplicationReports.Metric("total", "合计", "COUNT", null, null, null, null);
        var config =
                new ApplicationReports.Config(
                        ORDER,
                        List.of(row),
                        List.of(metric),
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Tokyo",
                        "PIVOT",
                        null,
                        false,
                        50,
                        "view-1",
                        null,
                        null,
                        List.of(column),
                        ApplicationReports.Pivot.defaults(),
                        Boolean.TRUE);
        var renamed = new ApplicationReports.Metric("total", "件数", "COUNT", null, null, null, null);
        var next = config.withMetrics(List.of(renamed));
        assertThat(next.metrics()).containsExactly(renamed);
        assertThat(next.columnDimensions()).containsExactly(column);
        assertThat(next.pivot()).isEqualTo(ApplicationReports.Pivot.defaults());
        assertThat(next.detailEditable()).isTrue();
        // 其余组件逐个不变：以后给 Config 加组件而漏改 withMetrics，这里会红。
        for (var component : ApplicationReports.Config.class.getRecordComponents()) {
            if (component.getName().equals("metrics")) continue;
            try {
                assertThat(component.getAccessor().invoke(next))
                        .as(component.getName())
                        .isEqualTo(component.getAccessor().invoke(config));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
