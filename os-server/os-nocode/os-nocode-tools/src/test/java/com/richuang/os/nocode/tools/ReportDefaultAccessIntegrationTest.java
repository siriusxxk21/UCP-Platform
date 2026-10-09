package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.system.api.permission.*;
import com.richuang.os.module.system.api.permission.dto.RoleRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.report.service.authorization.*;
import com.richuang.os.nocode.report.service.dataset.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** 当前开发库默认全来源只读模式；真实数据集生命周期与 SQL，身份 API 使用可控替身。 */
class ReportDefaultAccessIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext context;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService auth;
    private PermissionApi permissions;
    private final Set<String> ids = new HashSet<>();

    private record Seed(ReportDatasets.Detail dataset, String object, String name, String amount) {}

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
        context = ReportIntegrationSupport.context();
        datasets = context.getBean(ReportDatasetService.class);
        auth = context.getBean(ReportDatasetAuthorizationService.class);
        permissions = servicesContext.getBean(PermissionApi.class);
        reset(
                permissions,
                servicesContext.getBean(RoleApi.class),
                servicesContext.getBean(AdminUserApi.class));
        ReportIntegrationSupport.activeUsers(10001, 10002, 10003);
        for (long actor : List.of(10001L, 10002L, 10003L)) {
            for (String action :
                    List.of("query", "create", "update", "publish", "manage", "authorize"))
                when(permissions.hasAnyPermissions(actor, "nocode:report:" + action))
                        .thenReturn(true);
            when(permissions.hasAnyPermissions(actor, "nocode:object:query")).thenReturn(true);
        }
        when(permissions.hasAnyPermissions(10003L, "nocode:object:share")).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        context.close();
        for (String id : ids) {
            ReportIntegrationSupport.clearAuthorization(id);
            jdbc.update(
                    "DELETE FROM public.nocode_resource_dependency WHERE source_kind='DATASET' AND"
                            + " source_key LIKE ?",
                    id + ":%");
            jdbc.update(
                    "DELETE FROM public.nocode_report_operation_log WHERE resource_kind='DATASET'"
                            + " AND resource_id=?",
                    Long.parseLong(id));
            jdbc.update(
                    "DELETE FROM public.nocode_report_dataset_version WHERE dataset_id=?",
                    Long.parseLong(id));
            jdbc.update(
                    "DELETE FROM public.nocode_report_dataset WHERE id=? AND owner_id=10001",
                    Long.parseLong(id));
        }
        fixture.clean();
    }

    @Test
    void publishesPreviewsQueriesAndExportsWithoutObjectOrMemberGrants() {
        rollback(
                () -> {
                    Seed seed = seed();
                    insertRows(seed);
                    assertThat(context.getBean(ReportSourcePermissions.class).enabled()).isFalse();
                    assertThat(auth.ceilings(seed.dataset().id(), 10001)).isEmpty();
                    assertThat(auth.dataPolicy(seed.dataset().id(), 10001).members()).isEmpty();
                    assertThat(
                                    auth.<Set<String>>withDataAccess(
                                            seed.dataset().id(),
                                            null,
                                            null,
                                            true,
                                            10001,
                                            access ->
                                                    access.grants()
                                                            .get(seed.object())
                                                            .getFirst()
                                                            .actions()))
                            .containsExactlyInAnyOrder("READ", "EXPORT");
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    ApplicationReports.Result result = aggregate(release, List.of(), Map.of());
                    assertThat(result.recordCount()).isEqualTo(3);
                    assertThat(new BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo("36.00");

                    assertThat(
                                    auth.<Set<String>>withExportAccess(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum(),
                                            false,
                                            10001,
                                            access ->
                                                    access.grants()
                                                            .get(seed.object())
                                                            .getFirst()
                                                            .writeFields()))
                            .isEmpty();
                });
    }

    @Test
    void chartExportUsesAllRowsWithoutObjectGrants() {
        rollback(
                () -> {
                    Seed seed = seed();
                    insertRows(seed);
                    ReportDatasets.Detail saved =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            seed.dataset().id(),
                                            seed.dataset().revision(),
                                            seed.dataset().draft().name(),
                                            "",
                                            seed.dataset().draft().source(),
                                            new ReportDatasets.Analysis(
                                                    1,
                                                    List.of(
                                                            new ReportDatasetQueries.Metric(
                                                                    "sum", "金额", "SUM", "amount")),
                                                    null,
                                                    Map.of(),
                                                    "Asia/Shanghai")),
                                    10001);
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "export", "导出验证"),
                                    10001);
                    ReportDashboards.Chart chart =
                            new ReportDashboards.Chart(
                                    "chart",
                                    "汇总",
                                    "TABLE",
                                    new ReportDashboards.Dataset(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum()),
                                    List.of(),
                                    List.of("sum"),
                                    0,
                                    0,
                                    6,
                                    4);
                    com.richuang.os.nocode.runtime.service.report.ReportDatasetQueryService query =
                            context.getBean(
                                    com.richuang.os.nocode.runtime.service.report
                                            .ReportDatasetQueryService.class);
                    assertThat(query.chart(chart, false, 10001).canExport()).isTrue();
                    assertThat(query.chart(chart, true, 10001).recordCount()).isEqualTo(3);
                });
    }

    @Test
    void storedPartialOrRevokedGrantsDoNotRestrictDefaultReads() {
        rollback(
                () -> {
                    Seed seed = seed();
                    insertRows(seed);
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name()), false)), 10003);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    0,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    scoped(
                                                            seed,
                                                            seed.name(),
                                                            "A",
                                                            Set.of(seed.name()))))),
                            10001);
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    assertThat(aggregate(release, List.of(), Map.of()).recordCount()).isEqualTo(3);
                    auth.saveCeiling(ceiling(seed, 1, null), 10003);
                    assertThat(aggregate(release, List.of(), Map.of()).recordCount()).isEqualTo(3);
                    assertThat(run(release, 10001).get(seed.object()).getFirst().readFields())
                            .contains(seed.name(), seed.amount());
                });
    }

    @Test
    void reportPermissionDiscoversSourcesWithoutDataCenterPermission() {
        rollback(
                () -> {
                    Seed seed = seed();
                    when(permissions.hasAnyPermissions(10002L, "nocode:object:query"))
                            .thenReturn(false);
                    ReportDatasetCatalogService catalog =
                            context.getBean(ReportDatasetCatalogService.class);
                    assertThat(catalog.objects(1, 100, fixture.prefix, 10002).getList())
                            .isNotEmpty();
                    assertThat(catalog.object(seed.object(), null, 10002).reference().objectId())
                            .isEqualTo(seed.object());
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:query"))
                            .thenReturn(false);
                    assertThatThrownBy(() -> catalog.objects(1, 100, null, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void resourceUseAndEditRemainRequiredAndRevocationIsImmediate() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    assertThatThrownBy(() -> run(release, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("USE"))),
                                    "协作使用"),
                            10001);
                    assertThat(run(release, 10002)).containsKey(seed.object());
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    seed.dataset().id(),
                                                    null,
                                                    null,
                                                    true,
                                                    10002,
                                                    access -> 1))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(), 1, List.of(), "撤销协作"),
                            10001);
                    assertThatThrownBy(() -> run(release, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void fixedVersionAndLifecycleChecksCannotBeBypassedWithPreviewParameters() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    "tampered",
                                                    false,
                                                    10001,
                                                    access -> 1))
                            .hasMessageContaining("校验和");
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    null,
                                                    null,
                                                    false,
                                                    10001,
                                                    access -> 1))
                            .hasMessageContaining("指定");
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    release.checksum(),
                                                    true,
                                                    10001,
                                                    access -> 1))
                            .hasMessageContaining("草稿预览");
                    ReportDatasets.Detail head = datasets.get(release.datasetId(), 10001);
                    datasets.status(
                            new ReportDatasets.ChangeStatus(
                                    head.id(), head.revision(), "INACTIVE", "停用测试"),
                            10001);
                    assertThatThrownBy(() -> run(release, 10001)).hasMessageContaining("停用");
                    assertThat(
                                    auth.<String>withDataAccess(
                                            head.id(),
                                            null,
                                            null,
                                            true,
                                            10001,
                                            access -> access.content().name()))
                            .isEqualTo(head.draft().name());
                });
    }

    @Test
    void identityChangeBeforeDeliveryDiscardsTheResult() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    release.checksum(),
                                                    false,
                                                    10001,
                                                    access -> {
                                                        servicesContext
                                                                .getBean(AdminUserApi.class)
                                                                .getUser(10001L)
                                                                .setStatus(1);
                                                        return "不能交付";
                                                    }))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportIntegrationSupport.activeUsers(10001);
                    role(10001, 200L);
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    release.checksum(),
                                                    false,
                                                    10001,
                                                    access -> {
                                                        when(permissions.getUserRoleIds(10001L))
                                                                .thenReturn(Set.of());
                                                        return "不能交付";
                                                    }))
                            .hasMessageContaining("角色已变化");
                });
    }

    @Test
    void orderWithThreeArrivalsKeepsRootAmountAndRejectsMultiValueExpansion() {
        rollback(
                () -> {
                    Seed arrivals = seed("arrivals"), original = seed("orders");
                    DataCenter.Design current = designs.get(original.object());
                    DataCenter.Design edit =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            original.object(), current.draft().lockVersion(), null),
                                    10001);
                    ObjectDraft draft = edit.draft();
                    DataCenter.Design related =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            new SaveObjectDraft(
                                                    draft.id(),
                                                    draft.lockVersion(),
                                                    draft.objectCode(),
                                                    draft.objectName(),
                                                    draft.description(),
                                                    draft.tableName(),
                                                    draft.titleFieldId(),
                                                    draft.fields(),
                                                    List.of()),
                                            edit.settings(),
                                            edit.fieldOptions(),
                                            List.of(
                                                    new DataCenter.Relation(
                                                            null,
                                                            "arrivals",
                                                            "到货",
                                                            "MANY_TO_MANY",
                                                            arrivals.object(),
                                                            null,
                                                            null,
                                                            false,
                                                            "RESTRICT")),
                                            edit.indexes(),
                                            edit.details()),
                                    10001);
                    DataCenter.PublishPlan plan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            original.object(), related.draft().lockVersion(), null),
                                    10001);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(
                                                            plan.id(), "订单关联到货夹具"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
                    DataObjectApi.PublishedObject root =
                            objects.getVersion(original.object(), null);
                    DataObjectApi.PublishedObject target =
                            objects.getVersion(arrivals.object(), null);
                    DataCenter.Relation relation = root.definition().relations().getFirst();
                    assertThat(relation.kind()).isEqualTo("MANY_TO_MANY");
                    ReportDatasets.Source source =
                            new ReportDatasets.Source(
                                    1,
                                    new ReportDatasets.ObjectReference(
                                            root.objectId(), root.versionNo(), root.checksum()),
                                    List.of(),
                                    original.dataset().draft().source().fields());
                    ReportDatasets.Detail saved =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            original.dataset().id(),
                                            original.dataset().revision(),
                                            fixture.prefix + "订单金额",
                                            "",
                                            source,
                                            new ReportDatasets.Analysis(
                                                    1,
                                                    List.of(
                                                            new ReportDatasetQueries.Metric(
                                                                    "count", "订单数", "COUNT", null),
                                                            new ReportDatasetQueries.Metric(
                                                                    "sum", "订单金额", "SUM",
                                                                    "amount")),
                                                    null,
                                                    Map.of(),
                                                    "Asia/Shanghai")),
                                    10001);
                    Seed orders =
                            new Seed(saved, original.object(), original.name(), original.amount());
                    ReportDatasets.Release release = authorized(orders);
                    String order =
                            jdbc.queryForObject(
                                    "INSERT INTO public.\""
                                            + root.definition().tableName()
                                            + "\"(name,amount,creator,deleted) VALUES"
                                            + " ('订单100',100,'10001',0) RETURNING id::text",
                                    String.class);
                    List<String> arrivalIds = new ArrayList<>();
                    for (String name : List.of("到货1", "到货2", "到货3")) {
                        String id =
                                jdbc.queryForObject(
                                        "INSERT INTO public.\""
                                                + target.definition().tableName()
                                                + "\"(name,creator,deleted) VALUES (?,'10001',0)"
                                                + " RETURNING id::text",
                                        String.class,
                                        name);
                        arrivalIds.add(id);
                        servicesContext
                                .getBean(
                                        com.richuang.os.nocode.runtime.service.record
                                                .RecordRelations.class)
                                .attach(root.definition(), relation, order, id, 10001);
                    }
                    assertThat(
                                    servicesContext
                                            .getBean(
                                                    com.richuang.os.nocode.runtime.service.record
                                                            .RecordRelations.class)
                                            .targets(root.definition(), relation, order, 10001))
                            .containsExactlyInAnyOrderElementsOf(arrivalIds);
                    com.richuang.os.nocode.runtime.service.report.ReportDatasetQueryService query =
                            context.getBean(
                                    com.richuang.os.nocode.runtime.service.report
                                            .ReportDatasetQueryService.class);
                    ReportDatasetQueries.Query request =
                            new ReportDatasetQueries.Query(
                                    release.datasetId(),
                                    release.versionNo(),
                                    release.checksum(),
                                    false,
                                    List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                                    null,
                                    Map.of(),
                                    20,
                                    "Asia/Shanghai");
                    ApplicationReports.Result result = query.query(request, 10001);
                    assertThat(result.recordCount()).isEqualTo(1);
                    assertThat(result.groups()).hasSize(1);
                    assertThat(new BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo("100");
                    assertThat(new BigDecimal(result.totals().get("count")))
                            .isEqualByComparingTo("1");
                    ReportDashboards.Chart chart =
                            new ReportDashboards.Chart(
                                    "orders",
                                    "订单金额",
                                    "TABLE",
                                    new ReportDashboards.Dataset(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum()),
                                    request.dimensions(),
                                    List.of("count", "sum"),
                                    0,
                                    0,
                                    12,
                                    4);
                    ApplicationReports.Result exporting = query.chart(chart, true, 10001);
                    assertThat(exporting.recordCount()).isEqualTo(1);
                    assertThat(new BigDecimal(exporting.totals().get("sum")))
                            .isEqualByComparingTo("100");
                    assertThat(
                                    new com.richuang.os.nocode.web.RecordExcelService()
                                            .reportResult(exporting))
                            .isNotEmpty();

                    ReportDatasets.Detail before = datasets.get(release.datasetId(), 10001);
                    List<ReportDatasets.Field> expanded = new ArrayList<>(source.fields());
                    expanded.add(
                            new ReportDatasets.Field(
                                    "arrival_name",
                                    List.of("arrivals"),
                                    arrivals.name(),
                                    "到货名称",
                                    "DIMENSION"));
                    ReportDatasets.Source unsupported =
                            new ReportDatasets.Source(
                                    1,
                                    source.root(),
                                    List.of(
                                            new ReportDatasets.Relation(
                                                    "arrivals",
                                                    List.of(),
                                                    relation.id(),
                                                    new ReportDatasets.ObjectReference(
                                                            target.objectId(),
                                                            target.versionNo(),
                                                            target.checksum()))),
                                    expanded);
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            before.id(),
                                                            before.revision(),
                                                            before.draft().name(),
                                                            "",
                                                            unsupported),
                                                    10001))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("只支持主表发出的单值关联");
                    assertThat(datasets.get(before.id(), 10001).revision())
                            .isEqualTo(before.revision());
                    ApplicationReports.Result after = query.query(request, 10001);
                    assertThat(after.recordCount()).isEqualTo(1);
                    assertThat(new BigDecimal(after.totals().get("sum")))
                            .isEqualByComparingTo("100");
                });
    }

    private Seed seed() {
        return seed("auth");
    }

    private Seed seed(String suffix) {
        String code = fixture.prefix + suffix;
        ObjectDraft draft =
                service.create(
                        new SaveObjectDraft(
                                null,
                                null,
                                code,
                                fixture.prefix + "权限测试",
                                null,
                                "biz_" + code,
                                "name",
                                List.of(
                                        new FieldDefinition(
                                                "name", null, "name", "名称", "TEXT", 100, null, null,
                                                false, false, 0),
                                        new FieldDefinition(
                                                "amount", null, "amount", "金额", "DECIMAL", null, 20,
                                                2, false, false, 1)),
                                List.of()),
                        10001,
                        UUID.randomUUID());
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "报表权限测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        String name =
                draft.fields().stream()
                        .filter(field -> field.code().equals("name"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        String amount =
                draft.fields().stream()
                        .filter(field -> field.code().equals("amount"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), published.versionNo(), published.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), name, "名称", "DIMENSION"),
                                new ReportDatasets.Field(
                                        "amount", List.of(), amount, "金额", "MEASURE")));
        ReportDatasets.Detail dataset =
                datasets.save(new ReportDatasets.Save(null, 0, code, "", source), 10001);
        ids.add(dataset.id());
        return new Seed(dataset, draft.id(), name, amount);
    }

    private ReportDatasets.Publish publish(Seed seed) {
        return new ReportDatasets.Publish(
                seed.dataset().id(), seed.dataset().revision(), "publish", "权限测试发布");
    }

    private ReportDatasets.Release authorized(Seed seed) {
        ObjectGrant grant = grant(seed, Set.of(seed.name(), seed.amount()), true);
        auth.saveCeiling(ceiling(seed, 0, grant), 10003);
        auth.saveDataPolicy(policy(seed, 0, List.of(member("USER", "10001", grant))), 10001);
        return datasets.publish(publish(seed), 10001);
    }

    private ObjectGrant grant(Seed seed, Set<String> fields, boolean export) {
        return new ObjectGrant(
                seed.object(),
                export ? Set.of("READ", "EXPORT") : Set.of("READ"),
                "ALL",
                fields,
                Set.of(),
                Set.of(),
                Set.of());
    }

    private ObjectGrant scoped(Seed seed, String field, String value, Set<String> fields) {
        return new ObjectGrant(
                seed.object(),
                Set.of("READ"),
                "ALL",
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "READ",
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition(field, "eq", value)),
                                List.of())),
                Set.of());
    }

    private Member member(String kind, String id, ObjectGrant grant) {
        return new Member(kind, id, List.of(grant));
    }

    private ReportAuthorization.SaveCeiling ceiling(Seed seed, int revision, ObjectGrant grant) {
        return new ReportAuthorization.SaveCeiling(
                seed.dataset().id(), seed.object(), revision, grant, "测试共享上限");
    }

    private ReportAuthorization.SaveDataPolicy policy(
            Seed seed, int revision, List<Member> members) {
        return new ReportAuthorization.SaveDataPolicy(
                seed.dataset().id(), revision, members, "测试成员策略");
    }

    private Map<String, List<ObjectGrant>> run(ReportDatasets.Release release, long actor) {
        return auth.withDataAccess(
                release.datasetId(),
                release.versionNo(),
                release.checksum(),
                false,
                actor,
                ReportDatasetAuthorizationService.Context::grants);
    }

    private void role(long actor, long id) {
        RoleRespDTO role = new RoleRespDTO();
        role.setId(id);
        role.setCode("REPORT_TEST");
        role.setStatus(0);
        when(permissions.getUserRoleIds(actor)).thenReturn(Set.of(id));
        when(servicesContext.getBean(RoleApi.class).getRoleList(Set.of(id)))
                .thenReturn(List.of(role));
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private void insertRows(Seed seed) {
        String table =
                servicesContext
                        .getBean(DataObjectApi.class)
                        .getPublished(seed.object())
                        .tableName();
        jdbc.update(
                "INSERT INTO public.\""
                        + table
                        + "\"(name,amount,creator,deleted) VALUES"
                        + " ('A',10.25,'10001',0),('B',20.75,'10002',0),('B',5,'10001',0),('X',100,'10001',1)");
    }

    private ApplicationReports.Result aggregate(
            ReportDatasets.Release release,
            List<ReportDatasetQueries.Dimension> dimensions,
            Map<String, Object> equal) {
        return context.getBean(
                        com.richuang.os.nocode.runtime.service.report.ReportDatasetQueryService
                                .class)
                .query(
                        new ReportDatasetQueries.Query(
                                release.datasetId(),
                                release.versionNo(),
                                release.checksum(),
                                false,
                                dimensions,
                                List.of(
                                        new ReportDatasetQueries.Metric(
                                                "sum", "金额", "SUM", "amount")),
                                equal,
                                20,
                                "Asia/Shanghai"),
                        10001);
    }
}
