package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RuleFixtures.current;
import static com.lingan.ucp.nocode.tools.RuleFixtures.formula;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 对象数据维护入口不带应用（records.save(null, …)）。字段对象规则在这里按对象当前发布版解析并照常强制：公式默认值照算、只读联动按服务端结果覆盖客户端提交的值，
 * 来源对象同样取当前发布版；不能报空指针或 500。仅清理本测试前缀拥有的夹具。
 */
class ObjectMaintenanceRulesIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ObjectDataMaintenanceService maintenance;

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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        maintenance = servicesContext.getBean(ObjectDataMaintenanceService.class);
        PermissionCommonApi permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private Design published(
            String suffix, List<FieldDefinition> extra, Map<String, FieldOptions> options) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        List<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.addAll(extra);
        Design draft =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                Settings.defaults(),
                                options,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001L);
        PublishPlan plan =
                publisher.plan(
                        new Revision(draft.draft().id(), draft.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "维护入口规则验证"), 10001L).state())
                .isEqualTo("SUCCEEDED");
        return designs.get(draft.draft().id());
    }

    private static String id(Design design, String code) {
        return design.draft().fields().stream()
                .filter(field -> field.code().equals(code))
                .map(FieldDefinition::id)
                .findFirst()
                .orElseThrow();
    }

    private ApplicationRecords.Aggregate save(Design design, Map<String, Object> values) {
        ObjectDataMaintenance.Model model = maintenance.model(design.draft().id(), 10001L);
        return maintenance.save(
                new ObjectDataMaintenance.Save(
                        design.draft().id(),
                        model.versionNo(),
                        model.checksum(),
                        null,
                        null,
                        values,
                        UUID.randomUUID().toString()),
                10001L);
    }

    @Test
    void defaultFormulaIsComputedWithoutApplication() {
        Design orders =
                published(
                        "mnt_formula",
                        List.of(
                                fixture.field("price", "price", "DECIMAL", 1),
                                fixture.field("qty", "qty", "INTEGER", 2),
                                fixture.field("total", "total", "MONEY", 3)),
                        Map.of(
                                "total",
                                FieldOptions.defaults().withRules(formula("price * qty", null))));
        Map<String, Object> values = new HashMap<>();
        values.put(id(orders, "name"), "维护入口公式");
        values.put(id(orders, "price"), "10.5");
        values.put(id(orders, "qty"), 3);
        // 公式默认值一律只读：客户端伪造的值被服务端结果覆盖（31.5 按缺省「向下取整」得 31）。
        values.put(id(orders, "total"), "999");

        ApplicationRecords.Aggregate saved = save(orders, values);

        assertThat(new BigDecimal(saved.record().values().get(id(orders, "total")).toString()))
                .isEqualByComparingTo("31");
    }

    @Test
    void readOnlyLinkageResolvesItsSourceFromThePublishedVersionWithoutApplication() {
        Design customers =
                published(
                        "mnt_source",
                        List.of(fixture.field("grade", "grade", "TEXT", 1)),
                        Map.of());
        String customerName = id(customers, "name"), customerGrade = id(customers, "grade");
        // 只读联动（readOnly 为 null 按只读）：按「客户名」到客户对象取「等级」。条件里的当前字段先写临时 key，保存后改写为稳定 ID。
        FieldRules linkage =
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                customers.draft().id(),
                                List.of(current(customerName, "eq", "customer")),
                                customerGrade,
                                "FIRST",
                                null,
                                null,
                                null),
                        null,
                        null,
                        null);
        Design orders =
                published(
                        "mnt_target",
                        List.of(
                                fixture.field("customer", "customer", "TEXT", 1),
                                fixture.field("grade", "grade", "TEXT", 2)),
                        Map.of("grade", FieldOptions.defaults().withRules(linkage)));

        save(customers, Map.of(customerName, "甲公司", customerGrade, "A"));
        Map<String, Object> values = new HashMap<>();
        values.put(id(orders, "name"), "维护入口联动");
        values.put(id(orders, "customer"), "甲公司");
        values.put(id(orders, "grade"), "伪造的等级");

        ApplicationRecords.Aggregate saved = save(orders, values);

        assertThat(saved.record().values()).containsEntry(id(orders, "grade"), "A");
    }
}
