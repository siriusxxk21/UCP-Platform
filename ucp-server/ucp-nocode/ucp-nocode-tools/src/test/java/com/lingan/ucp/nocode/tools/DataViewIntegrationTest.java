package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.view.DataViewService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 在隔离数据库验证真实物理子表、固定应用版本、分页计数及关联保存，不模拟 SQL 结果。 */
class DataViewIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private RecordService records;
    private DataViewService views;
    private DataObjectApi objects;
    private ObjectMapper json;

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
        apps = servicesContext.getBean(ApplicationService.class);
        records = servicesContext.getBean(RecordService.class);
        views = servicesContext.getBean(DataViewService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        json = servicesContext.getBean(ObjectMapper.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private void rollback(Runnable body) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                body.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private ObjectReference object(
            String code, List<DataCenter.Detail> details, List<DataCenter.Relation> relations) {
        var d =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest(code),
                                DataCenter.Settings.defaults(),
                                null,
                                relations,
                                List.of(),
                                details),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(d.draft().id(), d.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "隔离视图测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var v = objects.getVersion(d.draft().id(), null);
        return new ObjectReference(v.objectId(), v.versionNo(), v.checksum());
    }

    private DataCenter.Definition definition(ObjectReference r) {
        return objects.getVersion(r.objectId(), r.versionNo()).definition();
    }

    private DataCenter.Detail detail(String code) {
        return new DataCenter.Detail(
                null,
                code,
                code,
                "biz_" + fixture.prefix + "_" + code,
                "ACTIVE",
                List.of(
                        fixture.field("label", "label", "TEXT", 0),
                        fixture.field("qty", "qty", "INTEGER", 1)),
                Map.of(),
                List.of());
    }

    private Resource resource(String id, ApplicationUi.View view) {
        return new Resource(
                id,
                "VIEW",
                id,
                id,
                json.convertValue(view, new TypeReference<Map<String, Object>>() {}));
    }

    private ApplicationUi.View view(String object, String field, DataViews.Composition c) {
        return new ApplicationUi.View(
                object,
                List.of(field),
                Map.of(),
                null,
                false,
                20,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                c);
    }

    private String app(List<ObjectReference> refs, List<Resource> resources) {
        var a =
                apps.save(
                        new Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "视图测试",
                                null,
                                null,
                                new Definition(refs, resources)),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(new Revision(a.application().id(), 0, "测试"), 10001);
        return a.application().id();
    }

    private ApplicationRecords.Row line(DataCenter.Detail detail, String label, int qty) {
        return new ApplicationRecords.Row(
                null,
                null,
                Map.of(
                        field(detail.fields(), "label"),
                        label,
                        field(detail.fields(), "qty"),
                        Integer.toString(qty)));
    }

    private String field(List<FieldDefinition> fields, String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow().id();
    }

    private ApplicationRecords.Aggregate save(
            String app,
            DataCenter.Definition d,
            String name,
            Map<String, List<ApplicationRecords.Row>> details) {
        return records.save(
                new ApplicationRecords.Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(d.fields().getFirst().id(), name),
                        details),
                10001);
    }

    private ApplicationRecords.Query query(String app, String object, String view) {
        return new ApplicationRecords.Query(app, object, 1, 20, null, Map.of(), null, false, view);
    }

    private DynamicConditionDTO condition(String field, String op, Object value) {
        return json.convertValue(
                Map.of(
                        "logic",
                        "AND",
                        "items",
                        List.of(
                                Map.of(
                                        "type",
                                        "condition",
                                        "field",
                                        field,
                                        "operator",
                                        op,
                                        "value",
                                        value))),
                DynamicConditionDTO.class);
    }

    @Test
    void masterChildrenDetailGrainAndExportsKeepTheSameFilteredRowsWithoutCartesianTotals() {
        rollback(
                () -> {
                    var ref = object("root", List.of(detail("items"), detail("costs")), List.of());
                    var d = definition(ref);
                    var items = d.details().getFirst();
                    var costs = d.details().get(1);
                    String name = d.fields().getFirst().id();
                    var sections =
                            List.of(
                                    new DataViews.Section(
                                            "items",
                                            "项目",
                                            items.id(),
                                            null,
                                            null,
                                            null,
                                            items.fields().stream()
                                                    .map(FieldDefinition::id)
                                                    .toList(),
                                            null,
                                            1,
                                            true),
                                    new DataViews.Section(
                                            "costs",
                                            "费用",
                                            costs.id(),
                                            null,
                                            null,
                                            null,
                                            costs.fields().stream()
                                                    .map(FieldDefinition::id)
                                                    .toList(),
                                            null,
                                            10,
                                            true));
                    var cols =
                            List.of(
                                    new DataViews.Column(
                                            "view_count", "项目数", "items", null, "COUNT", null),
                                    new DataViews.Column(
                                            "view_qty",
                                            "数量",
                                            "items",
                                            field(items.fields(), "qty"),
                                            "SUM",
                                            null),
                                    new DataViews.Column(
                                            "view_cost",
                                            "费用",
                                            "costs",
                                            field(costs.fields(), "qty"),
                                            "SUM",
                                            null));
                    var composed = new DataViews.Composition("ROOT", null, sections, cols);
                    var detailView =
                            new DataViews.Composition(
                                    "DETAIL",
                                    items.id(),
                                    sections,
                                    List.of(
                                            new DataViews.Column(
                                                    "view_line",
                                                    "明细数量",
                                                    "items",
                                                    field(items.fields(), "qty"),
                                                    "DETAIL",
                                                    null)));
                    String app =
                            app(
                                    List.of(ref),
                                    List.of(
                                            resource(
                                                    "mainview", view(d.objectId(), name, composed)),
                                            resource(
                                                    "detailview",
                                                    view(d.objectId(), name, detailView))));
                    var a =
                            save(
                                    app,
                                    d,
                                    "A",
                                    Map.of(
                                            items.id(),
                                            List.of(line(items, "A1", 2), line(items, "A2", 3)),
                                            costs.id(),
                                            List.of(line(costs, "C1", 10), line(costs, "C2", 20))));
                    save(app, d, "B", Map.of(items.id(), List.of(line(items, "B1", 9))));
                    save(app, d, "C", Map.of());
                    var result = records.page(query(app, d.objectId(), "mainview"), 10001);
                    assertThat(result.getTotal()).isEqualTo(3);
                    var first =
                            result.getList().stream()
                                    .filter(r -> r.id().equals(a.record().id()))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(first.values())
                            .containsEntry("view_count", "2")
                            .containsEntry("view_qty", "5")
                            .containsEntry("view_cost", "30");
                    var sorted =
                            records.page(
                                    new ApplicationRecords.Query(
                                            app,
                                            d.objectId(),
                                            1,
                                            1,
                                            null,
                                            Map.of(),
                                            "view_qty",
                                            false,
                                            "mainview"),
                                    10001);
                    assertThat(sorted.getTotal()).isEqualTo(3);
                    assertThat(sorted.getList().getFirst().values()).containsEntry(name, "A");
                    var filtered =
                            records.page(
                                    new ApplicationRecords.Query(
                                            app,
                                            d.objectId(),
                                            1,
                                            20,
                                            null,
                                            Map.of(),
                                            null,
                                            false,
                                            "mainview",
                                            null,
                                            condition("view_qty", "gte", "9")),
                                    10001);
                    assertThat(filtered.getTotal()).isEqualTo(1);
                    assertThat(filtered.getList().getFirst().values()).containsEntry(name, "B");
                    var rowFilter =
                            new DataViews.ChildFilter(
                                    "items",
                                    null,
                                    Map.of(),
                                    condition(field(items.fields(), "qty"), "gte", "3"),
                                    false);
                    var displayOnly =
                            new ApplicationRecords.Query(
                                    app,
                                    d.objectId(),
                                    1,
                                    20,
                                    null,
                                    Map.of(),
                                    null,
                                    false,
                                    "mainview",
                                    null,
                                    null,
                                    List.of(rowFilter));
                    assertThat(records.page(displayOnly, 10001).getTotal()).isEqualTo(3);
                    var matching =
                            new ApplicationRecords.Query(
                                    app,
                                    d.objectId(),
                                    1,
                                    20,
                                    null,
                                    Map.of(),
                                    null,
                                    false,
                                    "mainview",
                                    null,
                                    null,
                                    List.of(
                                            new DataViews.ChildFilter(
                                                    "items",
                                                    null,
                                                    Map.of(),
                                                    rowFilter.conditions(),
                                                    true)));
                    assertThat(records.page(matching, 10001).getTotal()).isEqualTo(2);
                    assertThat(records.export(matching, 10001)).hasSize(2);
                    var child =
                            views.children(
                                    new DataViews.ChildQuery(
                                            app,
                                            d.objectId(),
                                            "mainview",
                                            "items",
                                            a.record().id(),
                                            2,
                                            1,
                                            null,
                                            Map.of(),
                                            null,
                                            false,
                                            null),
                                    10001);
                    assertThat(child.getTotal()).isEqualTo(2);
                    assertThat(child.getList()).hasSize(1);
                    var flat = records.page(query(app, d.objectId(), "detailview"), 10001);
                    assertThat(flat.getTotal()).isEqualTo(3);
                    assertThat(flat.getList())
                            .allSatisfy(r -> assertThat(r.parentId()).isNotBlank());
                    assertThat(flat.getList().stream().map(ApplicationRecords.Row::id))
                            .doesNotHaveDuplicates();
                    assertThat(
                                    servicesContext
                                            .getBean(ApplicationRuntimeService.class)
                                            .application(app, 10001)
                                            .definition()
                                            .resources()
                                            .getFirst()
                                            .config())
                            .containsKey("composition");
                });
    }

    @Test
    void relatedObjectsUsePublishedBindingsAndIncomingCreationLocksTheParentReference() {
        rollback(
                () -> {
                    var root = object("order", List.of(), List.of());
                    var d = definition(root);
                    var payment =
                            object(
                                    "payment",
                                    List.of(),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "purchase",
                                                    "订单",
                                                    "REFERENCE",
                                                    root.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT",
                                                    null)));
                    var p = definition(payment);
                    var relation = p.relations().getFirst();
                    var section =
                            new DataViews.Section(
                                    "payments",
                                    "收款",
                                    null,
                                    p.objectId(),
                                    "paymentview",
                                    new ApplicationUi.RelationBinding(relation.id(), "INCOMING"),
                                    p.fields().stream().map(FieldDefinition::id).toList(),
                                    null,
                                    20,
                                    true);
                    var c =
                            new DataViews.Composition(
                                    "ROOT",
                                    null,
                                    List.of(section),
                                    List.of(
                                            new DataViews.Column(
                                                    "view_paid",
                                                    "收款笔数",
                                                    "payments",
                                                    null,
                                                    "COUNT",
                                                    null)));
                    String app =
                            app(
                                    List.of(root, payment),
                                    List.of(
                                            resource(
                                                    "orders",
                                                    view(
                                                            d.objectId(),
                                                            d.fields().getFirst().id(),
                                                            c)),
                                            resource(
                                                    "paymentview",
                                                    view(
                                                            p.objectId(),
                                                            p.fields().getFirst().id(),
                                                            null))));
                    var a = save(app, d, "订单 A", Map.of());
                    var b = save(app, d, "订单 B", Map.of());
                    var context =
                            new ApplicationRecords.Context("orders", "payments", a.record().id());
                    var created =
                            records.save(
                                    new ApplicationRecords.Save(
                                            app,
                                            p.objectId(),
                                            null,
                                            null,
                                            Map.of(p.fields().getFirst().id(), "到账"),
                                            Map.of(),
                                            Map.of(),
                                            context),
                                    10001);
                    assertThat(created.record().values())
                            .containsEntry(relation.fieldId(), a.record().id());
                    var result = records.page(query(app, d.objectId(), "orders"), 10001);
                    assertThat(
                                    result.getList().stream()
                                            .filter(r -> r.id().equals(a.record().id()))
                                            .findFirst()
                                            .orElseThrow()
                                            .values())
                            .containsEntry("view_paid", "1");
                    assertThat(
                                    views.children(
                                                    new DataViews.ChildQuery(
                                                            app,
                                                            d.objectId(),
                                                            "orders",
                                                            "payments",
                                                            b.record().id(),
                                                            1,
                                                            20,
                                                            null,
                                                            Map.of(),
                                                            null,
                                                            false,
                                                            null),
                                                    10001)
                                            .getTotal())
                            .isZero();
                    assertThatThrownBy(
                                    () ->
                                            records.save(
                                                    new ApplicationRecords.Save(
                                                            app,
                                                            p.objectId(),
                                                            null,
                                                            null,
                                                            Map.of(
                                                                    p.fields().getFirst().id(),
                                                                    "错绑",
                                                                    relation.fieldId(),
                                                                    b.record().id()),
                                                            Map.of(),
                                                            Map.of(),
                                                            context),
                                                    10001))
                            .hasMessageContaining("关联");
                    assertThat(
                                    records.page(query(app, p.objectId(), "paymentview"), 10001)
                                            .getTotal())
                            .isEqualTo(1);
                    var sharing =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.application.service.sharing
                                            .ObjectSharingService.class);
                    var grant =
                            sharing.forApplication(app).stream()
                                    .filter(g -> g.objectId().equals(p.objectId()))
                                    .findFirst()
                                    .orElseThrow();
                    sharing.save(
                            new ObjectSharing.Save(
                                    p.objectId(),
                                    app,
                                    grant.revision(),
                                    new ApplicationAuthorization.ObjectGrant(
                                            p.objectId(),
                                            Set.of("READ"),
                                            "ALL",
                                            Set.of(p.fields().getFirst().id()),
                                            Set.of(),
                                            Set.of(),
                                            Set.of(),
                                            Set.of(),
                                            Set.of()),
                                    "撤销关联字段读取"),
                            10001);
                    assertThat(
                                    views.model(app, d.objectId(), "orders", 10001)
                                            .composition()
                                            .sections())
                            .isEmpty();
                    assertThat(records.page(query(app, d.objectId(), "orders"), 10001).getList())
                            .allSatisfy(
                                    row -> assertThat(row.values()).doesNotContainKey("view_paid"));
                    assertThatThrownBy(
                                    () ->
                                            records.page(
                                                    new ApplicationRecords.Query(
                                                            app,
                                                            d.objectId(),
                                                            1,
                                                            20,
                                                            null,
                                                            Map.of(),
                                                            "view_paid",
                                                            false,
                                                            "orders"),
                                                    10001))
                            .hasMessageContaining("排序字段");
                    assertThatThrownBy(
                                    () ->
                                            views.children(
                                                    new DataViews.ChildQuery(
                                                            app,
                                                            d.objectId(),
                                                            "orders",
                                                            "payments",
                                                            a.record().id(),
                                                            1,
                                                            20,
                                                            null,
                                                            Map.of(),
                                                            null,
                                                            false,
                                                            null),
                                                    10001))
                            .hasMessageContaining("子表");
                });
    }
}
