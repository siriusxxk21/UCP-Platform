package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.system.api.permission.*;
import com.lingan.ucp.module.system.api.permission.dto.RoleRespDTO;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.report.service.authorization.*;
import com.lingan.ucp.nocode.report.service.dataset.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 当前开发库三层授权与撤权屏障测试；身份 API 替身可精确模拟角色撤销，所有查询入口仍执行真实授权服务。 */
class ReportAuthorizationIntegrationTest {
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
        // 历史三层授权兼容回归；当前默认简化模式由 ReportDefaultAccessIntegrationTest 覆盖。
        org.springframework.test.util.ReflectionTestUtils.setField(
                context.getBean(ReportSourcePermissions.class), "enabled", true);
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
    void creatorDoesNotInheritDataAccessOrPermissionToGrantTheObjectCeiling() {
        rollback(
                () -> {
                    Seed seed = seed();
                    assertThat(auth.ceilings(seed.dataset().id(), 10001)).isEmpty();
                    assertThatThrownBy(() -> auth.saveCeiling(ceiling(seed, 0, null), 10003))
                            .hasMessageContaining("尚无可撤销");
                    assertThatThrownBy(() -> datasets.publish(publish(seed), 10001))
                            .hasMessageContaining("尚未授权");
                    assertThatThrownBy(
                                    () ->
                                            auth.saveCeiling(
                                                    ceiling(
                                                            seed,
                                                            0,
                                                            grant(
                                                                    seed,
                                                                    Set.of(
                                                                            seed.name(),
                                                                            seed.amount()),
                                                                    true)),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name(), seed.amount()), true)),
                            10003);
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    assertThatThrownBy(() -> run(release, 10001)).hasMessageContaining("数据查看权限");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    0,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            true)))),
                            10001);
                    assertThat(run(release, 10001).get(seed.object())).hasSize(1);
                });
    }

    @Test
    void resourceCollaborationRequiresBothAclAndSystemOperationPermission() {
        rollback(
                () -> {
                    Seed seed = seed();
                    assertThatThrownBy(() -> datasets.get(seed.dataset().id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW_META", "EDIT"))),
                                    "协作编辑"),
                            10001);
                    assertThat(datasets.get(seed.dataset().id(), 10002).id())
                            .isEqualTo(seed.dataset().id());
                    assertThat(datasets.page(1, 10, fixture.prefix, 10002).getTotal()).isEqualTo(1);
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:update"))
                            .thenReturn(false);
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            seed.dataset().id(),
                                                            1,
                                                            "越过系统权",
                                                            "",
                                                            seed.dataset().draft().source()),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:update"))
                            .thenReturn(true);
                    assertThat(
                                    datasets.save(
                                                    new ReportDatasets.Save(
                                                            seed.dataset().id(),
                                                            1,
                                                            "协作修改",
                                                            "",
                                                            seed.dataset().draft().source()),
                                                    10002)
                                            .revision())
                            .isEqualTo(2);
                    assertThatThrownBy(() -> auth.saveDataPolicy(policy(seed, 0, List.of()), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            auth.saveResource(
                                                    new ReportAuthorization.SaveResource(
                                                            seed.dataset().id(),
                                                            1,
                                                            List.of(),
                                                            "越权"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void rejectsWriteComputeAndOutOfCeilingMemberGrants() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ObjectGrant write =
                            new ObjectGrant(
                                    seed.object(),
                                    Set.of("READ", "UPDATE"),
                                    "ALL",
                                    Set.of(seed.name()),
                                    Set.of(seed.name()),
                                    Set.of(),
                                    Set.of());
                    assertThatThrownBy(() -> auth.saveCeiling(ceiling(seed, 0, write), 10003))
                            .hasMessageContaining("读和导出");
                    ObjectGrant compute =
                            new ObjectGrant(
                                    seed.object(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(seed.name()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Map.of(),
                                    Set.of(seed.name()));
                    assertThatThrownBy(() -> auth.saveCeiling(ceiling(seed, 0, compute), 10003))
                            .hasMessageContaining("计算取数");
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name()), false)), 10003);
                    assertThatThrownBy(
                                    () ->
                                            auth.saveDataPolicy(
                                                    policy(
                                                            seed,
                                                            0,
                                                            List.of(
                                                                    member(
                                                                            "USER",
                                                                            "10001",
                                                                            grant(
                                                                                    seed,
                                                                                    Set.of(
                                                                                            seed
                                                                                                    .name(),
                                                                                            seed
                                                                                                    .amount()),
                                                                                    false)))),
                                                    10001))
                            .hasMessageContaining("超出");
                    assertThatThrownBy(
                                    () ->
                                            auth.saveDataPolicy(
                                                    policy(
                                                            seed,
                                                            0,
                                                            List.of(
                                                                    member(
                                                                            "USER",
                                                                            "10001",
                                                                            grant(
                                                                                    seed,
                                                                                    Set.of(
                                                                                            seed
                                                                                                    .name()),
                                                                                    true)))),
                                                    10001))
                            .hasMessageContaining("超出");
                    assertThat(auth.dataPolicy(seed.dataset().id(), 10001).revision()).isZero();
                });
    }

    @Test
    void narrowsOldMemberPoliciesImmediatelyAndPreservesSeparateRowFieldBindings() {
        rollback(
                () -> {
                    Seed seed = seed();
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name(), seed.amount()), true)),
                            10003);
                    ObjectGrant namedRows = scoped(seed, seed.name(), "A", Set.of(seed.name()));
                    ObjectGrant amountRows = scoped(seed, seed.name(), "B", Set.of(seed.amount()));
                    role(10001, 200L);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    0,
                                    List.of(
                                            member("USER", "10001", namedRows),
                                            member("ROLE", "200", amountRows))),
                            10001);
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    List<ObjectGrant> effective = run(release, 10001).get(seed.object());
                    assertThat(effective).hasSize(2);
                    assertThat(effective.get(0).readFields()).containsExactly(seed.name());
                    assertThat(effective.get(1).readFields()).containsExactly(seed.amount());
                    assertThat(
                                    effective
                                            .get(0)
                                            .actionScopes()
                                            .get("READ")
                                            .conditions()
                                            .getFirst()
                                            .value())
                            .isEqualTo("A");
                    assertThat(
                                    effective
                                            .get(1)
                                            .actionScopes()
                                            .get("READ")
                                            .conditions()
                                            .getFirst()
                                            .value())
                            .isEqualTo("B");
                    auth.saveCeiling(
                            ceiling(seed, 1, grant(seed, Set.of(seed.name()), false)), 10003);
                    assertThat(run(release, 10001).get(seed.object()))
                            .allSatisfy(
                                    grant -> {
                                        assertThat(grant.actions()).doesNotContain("EXPORT");
                                        assertThat(grant.readFields())
                                                .doesNotContain(seed.amount());
                                    });
                });
    }

    @Test
    void revokedCeilingRemainsRevisionedAndNeverRunsTheQueryCallback() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    auth.saveCeiling(ceiling(seed, 1, null), 10003);
                    assertThat(auth.ceilings(seed.dataset().id(), 10003).getFirst().revision())
                            .isEqualTo(2);
                    assertThat(auth.ceilings(seed.dataset().id(), 10003).getFirst().permission())
                            .isNull();
                    AtomicBoolean executed = new AtomicBoolean();
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    release.checksum(),
                                                    false,
                                                    10001,
                                                    access -> {
                                                        executed.set(true);
                                                        return 1;
                                                    }))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(executed).isFalse();
                    assertThatThrownBy(
                                    () ->
                                            auth.saveCeiling(
                                                    ceiling(
                                                            seed,
                                                            1,
                                                            grant(
                                                                    seed,
                                                                    Set.of(seed.name()),
                                                                    false)),
                                                    10003))
                            .hasMessageContaining("已被修改");
                });
    }

    @Test
    void rejectsStaleResourceAndMemberPolicyUpdatesAndDoesNotChangeDraftRevision() {
        rollback(
                () -> {
                    Seed seed = seed();
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(), 0, List.of(), "初始化"),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            auth.saveResource(
                                                    new ReportAuthorization.SaveResource(
                                                            seed.dataset().id(),
                                                            0,
                                                            List.of(),
                                                            "旧请求"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    auth.saveDataPolicy(policy(seed, 0, List.of()), 10001);
                    assertThatThrownBy(() -> auth.saveDataPolicy(policy(seed, 0, List.of()), 10001))
                            .hasMessageContaining("已被修改");
                    assertThat(datasets.get(seed.dataset().id(), 10001).revision()).isEqualTo(1);
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
    void revokedOrDisabledRoleNoLongerContributesMemberDataRights() {
        rollback(
                () -> {
                    Seed seed = seed();
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name(), seed.amount()), true)),
                            10003);
                    role(10001, 200L);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    0,
                                    List.of(
                                            member(
                                                    "ROLE",
                                                    "200",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            false)))),
                            10001);
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    assertThat(run(release, 10001).get(seed.object())).hasSize(1);
                    servicesContext
                            .getBean(RoleApi.class)
                            .getRoleList(Set.of(200L))
                            .getFirst()
                            .setStatus(1);
                    assertThatThrownBy(() -> run(release, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void failedAuditRollsBackCeilingChangeAndRevision() {
        Seed seed = seed();
        ReportDatasets.Release release = authorized(seed);
        writeFailure.failAfter("INSERT INTO public.nocode_report_operation_log");
        try {
            assertThatThrownBy(() -> auth.saveCeiling(ceiling(seed, 1, null), 10003))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(auth.ceilings(seed.dataset().id(), 10003).getFirst().revision()).isEqualTo(1);
        assertThat(run(release, 10001).get(seed.object())).hasSize(1);
    }

    @Test
    void revocationWaitsForAnActiveReadThenNewReadsAreDenied() throws Exception {
        Seed seed = seed();
        ReportDatasets.Release release = authorized(seed);
        CountDownLatch reading = new CountDownLatch(1),
                releaseRead = new CountDownLatch(1),
                revoking = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> read =
                    pool.submit(
                            () ->
                                    auth.withDataAccess(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum(),
                                            false,
                                            10001,
                                            access -> {
                                                reading.countDown();
                                                try {
                                                    if (!releaseRead.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError("读取未释放");
                                                } catch (InterruptedException error) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(error);
                                                }
                                                return 1;
                                            }));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReportAuthorization.ObjectCeiling> revoke =
                    pool.submit(
                            () -> {
                                revoking.countDown();
                                return auth.saveCeiling(ceiling(seed, 1, null), 10003);
                            });
            assertThat(revoking.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> revoke.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseRead.countDown();
            }
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(revoke.get(5, TimeUnit.SECONDS).revision()).isEqualTo(2);
            assertThatThrownBy(() -> run(release, 10001)).isInstanceOf(AccessDeniedException.class);
        } finally {
            releaseRead.countDown();
        }
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
    void authorizationConditionDependenciesRemainUntilBothIndependentPoliciesAreRemoved() {
        Seed original = seed();
        ReportDatasets.Source before = original.dataset().draft().source();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1, before.root(), List.of(), List.of(before.fields().getFirst()));
        ReportDatasets.Detail saved =
                datasets.save(
                        new ReportDatasets.Save(
                                original.dataset().id(),
                                original.dataset().revision(),
                                original.dataset().draft().name(),
                                "",
                                source),
                        10001);
        Seed seed = new Seed(saved, original.object(), original.name(), original.amount());
        ObjectGrant scoped = scoped(seed, seed.amount(), "10", Set.of(seed.name()));
        auth.saveCeiling(ceiling(seed, 0, scoped), 10003);
        auth.saveDataPolicy(policy(seed, 0, List.of(member("USER", "10001", scoped))), 10001);
        datasets.publish(publish(seed), 10001);
        assertThat(
                        centerMapper.dependencies(Long.parseLong(seed.object())).stream()
                                .filter(d -> d.fieldIds().contains(seed.amount()))
                                .map(DataCenter.Dependency::sourceKey))
                .containsExactlyInAnyOrder(
                        saved.id() + ":policy", saved.id() + ":ceiling:" + seed.object());
        assertThatThrownBy(() -> removeAmount(seed)).hasMessageContaining("字段仍被引用");
        auth.saveCeiling(ceiling(seed, 1, null), 10003);
        assertThatThrownBy(() -> removeAmount(seed)).hasMessageContaining("字段仍被引用");
        auth.saveDataPolicy(policy(seed, 1, List.of()), 10001);
        assertThat(removeAmount(seed).draft().fields())
                .noneMatch(f -> f.id().equals(seed.amount()));
    }

    private DataCenter.Design removeAmount(Seed seed) {
        DataCenter.Design current = designs.get(seed.object());
        DataCenter.Design edit =
                "PUBLISHED".equals(current.draft().state())
                        ? designs.editPublished(
                                new DataCenter.Revision(
                                        seed.object(), current.draft().lockVersion(), null),
                                10001)
                        : current;
        ObjectDraft d = edit.draft();
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                d.id(),
                                d.lockVersion(),
                                d.objectCode(),
                                d.objectName(),
                                d.description(),
                                d.tableName(),
                                d.titleFieldId(),
                                d.fields().stream()
                                        .filter(f -> !f.id().equals(seed.amount()))
                                        .toList(),
                                List.of(seed.amount())),
                        null,
                        null,
                        null,
                        null,
                        null),
                10001);
    }

    @Test
    void datasetDependencyBlocksIncompatibleFieldConversionAtPreviewAndPublish() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    FieldSwitchPreview.Result preview =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.schema.service.convert
                                                    .FieldSwitchPreviewService.class)
                                    .preview(
                                            new FieldSwitchPreview.Request(
                                                    seed.object(),
                                                    null,
                                                    seed.amount(),
                                                    "TEXT",
                                                    100,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false));
                    assertThat(preview.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
                    assertThat(preview.impacts())
                            .anyMatch(
                                    impact ->
                                            impact.blocking()
                                                    && "DATASET".equals(impact.sourceKind()));
                    DataCenter.Design current = designs.get(seed.object());
                    DataCenter.Design edit =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            seed.object(), current.draft().lockVersion(), null),
                                    10001);
                    ObjectDraft d = edit.draft();
                    List<FieldDefinition> fields =
                            d.fields().stream()
                                    .map(
                                            f ->
                                                    f.id().equals(seed.amount())
                                                            ? new FieldDefinition(
                                                                    f.key(),
                                                                    f.id(),
                                                                    f.code(),
                                                                    f.name(),
                                                                    "TEXT",
                                                                    100,
                                                                    null,
                                                                    null,
                                                                    f.required(),
                                                                    f.unique(),
                                                                    f.sort())
                                                            : f)
                                    .toList();
                    DataCenter.Design changed =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            new SaveObjectDraft(
                                                    d.id(),
                                                    d.lockVersion(),
                                                    d.objectCode(),
                                                    d.objectName(),
                                                    d.description(),
                                                    d.tableName(),
                                                    d.titleFieldId(),
                                                    fields,
                                                    List.of()),
                                            edit.settings(),
                                            edit.fieldOptions(),
                                            edit.relations(),
                                            edit.indexes(),
                                            edit.details()),
                                    10001);
                    DataCenter.PublishPlan plan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            seed.object(), changed.draft().lockVersion(), null),
                                    10001);
                    assertThat(plan.checks())
                            .anyMatch(check -> check.blocking() && check.message().contains("数据集"));
                });
    }

    @Test
    void queriesActualRowsWithDecimalTotalsAndGroupLimit() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    insertRows(seed);
                    ReportDatasetQueries.Query request =
                            new ReportDatasetQueries.Query(
                                    release.datasetId(),
                                    release.versionNo(),
                                    release.checksum(),
                                    false,
                                    List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount"),
                                                    new ReportDatasetQueries.Metric(
                                                            "count", "记录数", "COUNT", null),
                                            new ReportDatasetQueries.Metric(
                                                            "avg", "平均", "AVG", "amount"),
                                                    new ReportDatasetQueries.Metric(
                                                            "distinct",
                                                            "名称数",
                                                            "COUNT_DISTINCT",
                                                            "name")),
                                    Map.of(),
                                    1,
                                    "Asia/Shanghai");
                    ApplicationReports.Result result =
                            context.getBean(
                                            com.lingan.ucp.nocode.runtime.service.report
                                                    .ReportDatasetQueryService.class)
                                    .query(request, 10001);
                    assertThat(result.recordCount()).isEqualTo(3);
                    assertThat(result.totalGroups()).isEqualTo(2);
                    assertThat(result.groups()).hasSize(1);
                    assertThat(new java.math.BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo("36.00");
                    assertThat(new java.math.BigDecimal(result.totals().get("avg")))
                            .isEqualByComparingTo("12.00");
                    assertThat(result.totals().get("count")).isEqualTo("3");
                    assertThat(result.totals().get("distinct")).isEqualTo("2");
                });
    }

    @Test
    void analysisSurvivesSavePublishRestoreAndCannotBeOverriddenAtQueryTime() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release legacy = authorized(seed);
                    insertRows(seed);
                    assertThat(legacy.definition().analysis()).isNull();
                    String oldJson =
                            jdbc.queryForObject(
                                    "SELECT definition_json::text FROM"
                                        + " public.nocode_report_dataset_version WHERE dataset_id=?"
                                        + " AND version_no=1",
                                    String.class,
                                    Long.parseLong(seed.dataset().id()));
                    assertThat(oldJson).doesNotContain("analysis");
                    ApplicationReports.Format money =
                            new ApplicationReports.Format("元", 2, false, "#1677ff", true);
                    ReportDatasets.Analysis analysis =
                            new ReportDatasets.Analysis(
                                    1,
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                    "revenue", "收入", "SUM", "amount")),
                                    new DataScope(
                                            "AND",
                                            List.of(new DataScope.Condition("amount", "gt", "10")),
                                            List.of(
                                                    new DataScope(
                                                            "OR",
                                                            List.of(
                                                                    new DataScope.Condition(
                                                                            "name", "eq", "B"),
                                                                    new DataScope.Condition(
                                                                            "name", "eq", "C")),
                                                            List.of()))),
                                    Map.of("amount", money),
                                    "Asia/Shanghai");
                    ReportDatasets.Detail saved = saveAnalysis(seed, analysis);
                    assertThat(datasets.get(saved.id(), 10001).draft().analysis())
                            .isEqualTo(analysis);
                    ReportDatasets.Detail renamed =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            saved.id(),
                                            saved.revision(),
                                            "兼容旧客户端保存",
                                            "",
                                            saved.draft().source()),
                                    10001);
                    assertThat(renamed.draft().analysis()).isEqualTo(analysis);
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), renamed.revision(), "analysis_v2", "分析定义"),
                                    10001);
                    assertThat(release.definition().analysis()).isEqualTo(analysis);
                    ApplicationReports.Result result =
                            savedQuery(release, List.of("revenue"), Map.of(), null);
                    assertThat(new java.math.BigDecimal(result.totals().get("revenue")))
                            .isEqualByComparingTo("20.75");
                    assertThat(result.metrics().getFirst().format()).isEqualTo(money);
                    assertThat(result.timeZone()).isEqualTo("Asia/Shanghai");
                    assertThat(savedQuery(release, null, Map.of("name", "A"), null).recordCount())
                            .isZero();
                    assertThatThrownBy(
                                    () -> savedQuery(release, List.of("missing"), Map.of(), null))
                            .hasMessageContaining("复用指标");
                    assertThatThrownBy(
                                    () ->
                                            savedQuery(
                                                    release,
                                                    List.of("revenue", "revenue"),
                                                    Map.of(),
                                                    null))
                            .hasMessageContaining("复用指标");
                    assertThatThrownBy(() -> savedQuery(release, null, Map.of(), "UTC"))
                            .hasMessageContaining("时区");
                    assertThatThrownBy(
                                    () ->
                                            datasetQuery(
                                                    new ReportDatasetQueries.Query(
                                                            release.datasetId(),
                                                            release.versionNo(),
                                                            release.checksum(),
                                                            false,
                                                            List.of(),
                                                            List.of(
                                                                    new ReportDatasetQueries.Metric(
                                                                            "revenue", "收入",
                                                                            "COUNT", null)),
                                                            Map.of(),
                                                            20,
                                                            null)))
                            .hasMessageContaining("重定义");
                    // 不同身份的临时指标同样受固定条件约束；旧版仍读取原范围。
                    assertThat(
                                    new java.math.BigDecimal(
                                            aggregate(release, List.of(), Map.of())
                                                    .totals()
                                                    .get("sum")))
                            .isEqualByComparingTo("20.75");
                    assertThat(
                                    new java.math.BigDecimal(
                                            aggregate(legacy, List.of(), Map.of())
                                                    .totals()
                                                    .get("sum")))
                            .isEqualByComparingTo("36");
                    ReportDatasets.Detail current = datasets.get(saved.id(), 10001);
                    ReportDatasets.Detail restored =
                            datasets.restore(
                                    new ReportDatasets.Restore(
                                            saved.id(), current.revision(), 1, "恢复旧配置"),
                                    10001);
                    assertThat(restored.draft().analysis()).isNull();
                    assertThat(savedQuery(release, null, Map.of(), null).recordCount())
                            .isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT definition_json::text FROM"
                                                    + " public.nocode_report_dataset_version WHERE"
                                                    + " dataset_id=? AND version_no=1",
                                            String.class,
                                            Long.parseLong(saved.id())))
                            .isEqualTo(oldJson);
                });
    }

    @Test
    void fixedConditionFieldsRequireReadRightsEvenWhenOnlyCountingRows() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "rows", "记录数", "COUNT", null)),
                                            new DataScope(
                                                    "AND",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    "name", "eq", "B")),
                                                    List.of()),
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "fixed_count", "固定条件测试"),
                                    10001);
                    assertThat(savedQuery(release, null, Map.of(), null).recordCount())
                            .isEqualTo(2);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(seed, Set.of(seed.amount()), false)))),
                            10001);
                    assertThatThrownBy(() -> savedQuery(release, null, Map.of(), null))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void invalidAnalysisNeverChangesDraftOrDependencies() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Detail before = datasets.get(seed.dataset().id(), 10001);
                    ReportDatasetQueries.Metric valid =
                            new ReportDatasetQueries.Metric("total", "总额", "SUM", "amount");
                    List<ReportDatasets.Analysis> invalid =
                            List.of(
                                    new ReportDatasets.Analysis(
                                            2, List.of(valid), null, Map.of(), "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1, List.of(valid), null, Map.of(), "invalid-zone"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(valid, valid),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "total", "总额", "SUM", "name")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(valid),
                                            new DataScope(
                                                    "AND",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    "unknown", "eq", "B")),
                                                    List.of()),
                                            Map.of(),
                                            "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(valid),
                                            new DataScope(
                                                    "AND",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    "amount", "eq", "bad-number")),
                                                    List.of()),
                                            Map.of(),
                                            "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(valid),
                                            new DataScope(
                                                    "AND",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    "name",
                                                                    "eq",
                                                                    null,
                                                                    "CURRENT_USER")),
                                                    List.of()),
                                            Map.of(),
                                            "Asia/Shanghai"),
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(valid),
                                            null,
                                            Map.of(
                                                    "amount",
                                                    new ApplicationReports.Format(
                                                            "元", 20, false, null)),
                                            "Asia/Shanghai"));
                    for (ReportDatasets.Analysis analysis : invalid) {
                        assertThatThrownBy(() -> saveAnalysis(seed, analysis))
                                .isInstanceOf(
                                        com.lingan.ucp.framework.common.exception.ServiceException
                                                .class);
                        assertThat(datasets.get(before.id(), 10001)).isEqualTo(before);
                    }
                });
    }

    @Test
    void catalogSeparatesStructureDiscoveryAndObjectSharingFromDatasetAccess() {
        rollback(
                () -> {
                    Seed seed = seed();
                    fixture.create("unpublished");
                    ReportDatasetCatalogService catalog =
                            context.getBean(ReportDatasetCatalogService.class);
                    assertThat(catalog.objects(1, 100, fixture.prefix, 10001).getList())
                            .extracting(ReportDatasetCatalog.ObjectItem::id)
                            .containsExactly(seed.object());
                    ReportDatasetCatalog.ObjectVersion source =
                            catalog.object(seed.object(), null, 10001);
                    assertThat(source.reference())
                            .isEqualTo(seed.dataset().draft().source().root());
                    assertThat(source.fields())
                            .anyMatch(field -> field.id().equals(seed.amount()) && field.measure());
                    when(permissions.hasAnyPermissions(10002L, "nocode:object:query"))
                            .thenReturn(false);
                    assertThatThrownBy(() -> catalog.objects(1, 10, null, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> catalog.object(seed.object(), null, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> catalog.authorizationTargets(1, 10, null, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(catalog.authorizationTargets(1, 10, fixture.prefix, 10003).getList())
                            .extracting(ReportDatasetCatalog.AuthorizationTarget::id)
                            .containsExactly(seed.dataset().id());
                    assertThatThrownBy(() -> datasets.get(seed.dataset().id(), 10003))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> catalog.objects(0, 101, null, 10001))
                            .hasMessageContaining("分页");
                });
    }

    @Test
    void pureDataAdministratorDiscoversOnlyAuthorizationStructure() {
        rollback(
                () -> {
                    Seed seed = seed();
                    when(permissions.hasAnyPermissions(10003L, "nocode:report:query"))
                            .thenReturn(false);
                    when(permissions.hasAnyPermissions(10003L, "nocode:object:query"))
                            .thenReturn(false);
                    ReportDatasetCatalogService catalog =
                            context.getBean(ReportDatasetCatalogService.class);
                    List<ReportDatasetCatalog.AuthorizationObject> objects =
                            catalog.authorizationObjects(seed.dataset().id(), 10003);
                    assertThat(objects).hasSize(1);
                    assertThat(objects.getFirst().definition().fields())
                            .extracting(ReportDatasetCatalog.Field::id)
                            .contains(seed.name(), seed.amount());
                    assertThatThrownBy(() -> catalog.object(seed.object(), null, 10003))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> datasets.get(seed.dataset().id(), 10003))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () -> catalog.authorizationObjects(seed.dataset().id(), 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> catalog.authorizationObjects("999999999999", 10003))
                            .hasMessageContaining("不存在");
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name()), false)), 10003);
                    assertThat(auth.ceilings(seed.dataset().id(), 10003)).hasSize(1);
                });
    }

    @Test
    void authorizationObjectsRetainPublishedSourcesAfterDraftRemoval() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    auth.saveCeiling(ceiling(seed, 1, null), 10003);
                    // 无有效上限时也须保留历史版本的来源候选，以便重新授权固定旧版。
                    ReportDatasets.Detail head = datasets.get(seed.dataset().id(), 10001);
                    datasets.save(
                            new ReportDatasets.Save(
                                    head.id(), head.revision(), head.draft().name(), "", null),
                            10001);
                    ReportDatasetCatalogService catalog =
                            context.getBean(ReportDatasetCatalogService.class);
                    assertThat(catalog.authorizationObjects(head.id(), 10003))
                            .extracting(ReportDatasetCatalog.AuthorizationObject::id)
                            .containsExactly(seed.object());
                    auth.saveCeiling(
                            ceiling(seed, 2, grant(seed, Set.of(seed.name(), seed.amount()), true)),
                            10003);
                    assertThat(auth.ceilings(head.id(), 10003).getFirst().permission()).isNotNull();
                });
    }

    @Test
    void unavailableFormerSourceKeepsRevocationEntryAndDeletedDatasetIsDenied() {
        rollback(
                () -> {
                    Seed seed = seed();
                    auth.saveCeiling(
                            ceiling(seed, 0, grant(seed, Set.of(seed.name()), false)), 10003);
                    ReportDatasets.Detail head = datasets.get(seed.dataset().id(), 10001);
                    datasets.save(
                            new ReportDatasets.Save(
                                    head.id(), head.revision(), head.draft().name(), "", null),
                            10001);
                    jdbc.update(
                            "UPDATE public.nocode_object SET status='INACTIVE' WHERE id=?",
                            Long.parseLong(seed.object()));
                    ReportDatasetCatalogService catalog =
                            context.getBean(ReportDatasetCatalogService.class);
                    ReportDatasetCatalog.AuthorizationObject object =
                            catalog.authorizationObjects(head.id(), 10003).getFirst();
                    assertThat(object.id()).isEqualTo(seed.object());
                    assertThat(object.definition()).isNull();
                    assertThat(object.unavailableReason()).contains("撤销");
                    auth.saveCeiling(ceiling(seed, 1, null), 10003);
                    assertThat(auth.ceilings(head.id(), 10003).getFirst().permission()).isNull();
                    ReportDatasets.Detail current = datasets.get(head.id(), 10001);
                    datasets.delete(
                            new ReportDatasets.Delete(head.id(), current.revision(), "测试删除"),
                            10001);
                    assertThatThrownBy(() -> catalog.authorizationObjects(head.id(), 10003))
                            .hasMessageContaining("不存在");
                });
    }

    @Test
    void conditionalMetricsAndFormulaTotalsUseAuthorizedRowsBeforeGroupLimit() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    DataScope high =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition("amount", "gt", "10")),
                                    List.of());
                    List<ReportDatasetQueries.Metric> metrics =
                            List.of(
                                    new ReportDatasetQueries.Metric("sum", "总额", "SUM", "amount"),
                                    new ReportDatasetQueries.Metric("rows", "记录", "COUNT", null),
                                    new ReportDatasetQueries.Metric(
                                            "high", "高额", "SUM", "amount", null, high, null),
                                    formula("average", "DIVIDE", "sum", "rows"),
                                    formula("plus", "ADD", "high", "rows"),
                                    formula("minus", "SUBTRACT", "sum", "high"),
                                    formula("product", "MULTIPLY", "minus", "rows"),
                                    formula("zero", "SUBTRACT", "rows", "rows"),
                                    formula("undefined", "DIVIDE", "sum", "zero"));
                    ReportDatasets.Analysis configuration =
                            new ReportDatasets.Analysis(
                                    1, metrics, null, Map.of(), "Asia/Shanghai");
                    ReportDatasets.Detail saved = saveAnalysis(seed, configuration);
                    assertThat(datasets.get(saved.id(), 10001).draft().analysis())
                            .isEqualTo(configuration);
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(),
                                            saved.revision(),
                                            "metric_formula",
                                            "指标条件四则"),
                                    10001);
                    ApplicationReports.Result result =
                            datasetQuery(
                                    new ReportDatasetQueries.Query(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum(),
                                            false,
                                            List.of(
                                                    new ReportDatasetQueries.Dimension(
                                                            "name", "VALUE")),
                                            null,
                                            Map.of(),
                                            1,
                                            null,
                                            List.of(
                                                    "average",
                                                    "plus",
                                                    "minus",
                                                    "product",
                                                    "undefined")));
                    assertThat(result.totalGroups()).isEqualTo(2);
                    assertThat(result.groups()).hasSize(1);
                    assertThat(result.recordCount()).isEqualTo(3);
                    assertThat(result.totals())
                            .containsOnlyKeys("average", "plus", "minus", "product", "undefined");
                    assertThat(new java.math.BigDecimal(result.totals().get("average")))
                            .isEqualByComparingTo("12");
                    assertThat(new java.math.BigDecimal(result.totals().get("plus")))
                            .isEqualByComparingTo("34");
                    assertThat(new java.math.BigDecimal(result.totals().get("minus")))
                            .isEqualByComparingTo("5");
                    assertThat(new java.math.BigDecimal(result.totals().get("product")))
                            .isEqualByComparingTo("15");
                    assertThat(result.totals().get("undefined")).isNull();
                    assertThat(result.groups().getFirst().values())
                            .containsOnlyKeys("average", "plus", "minus", "product", "undefined");
                    assertThat(
                                    new java.math.BigDecimal(
                                            result.groups().getFirst().values().get("average")))
                            .isEqualByComparingTo("10.25");
                    ApplicationReports.Result empty =
                            savedQuery(
                                    release,
                                    List.of("average", "high"),
                                    Map.of("name", "missing"),
                                    null);
                    assertThat(empty.totals().get("average")).isNull();
                    assertThat(new java.math.BigDecimal(empty.totals().get("high"))).isZero();
                    saveAnalysis(
                            seed,
                            new ReportDatasets.Analysis(
                                    1,
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                    "sum", "总额", "COUNT", null)),
                                    null,
                                    Map.of(),
                                    "Asia/Shanghai"));
                    assertThat(
                                    new java.math.BigDecimal(
                                            savedQuery(release, List.of("average"), Map.of(), null)
                                                    .totals()
                                                    .get("average")))
                            .isEqualByComparingTo("12");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(seed, Set.of(seed.name()), false)))),
                            10001);
                    assertThatThrownBy(
                                    () -> savedQuery(release, List.of("average"), Map.of(), null))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void formulaPreservesDecimalPrecisionBeyondJavascriptSafeInteger() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    String table =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getPublished(seed.object())
                                    .tableName();
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\"(name,amount,creator,deleted) VALUES"
                                    + " ('large',9007199254740993.01,'10001',0),('cent',0.02,'10001',0)");
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "总额", "SUM", "amount"),
                                                    new ReportDatasetQueries.Metric(
                                                            "rows", "记录", "COUNT", null),
                                                    formula("average", "DIVIDE", "sum", "rows")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "metric_precision", "精度"),
                                    10001);
                    ApplicationReports.Result result =
                            savedQuery(release, List.of("sum", "average"), Map.of(), null);
                    assertThat(result.totals().get("sum")).isEqualTo("9007199254740993.03");
                    assertThat(new java.math.BigDecimal(result.totals().get("average")))
                            .isEqualByComparingTo("4503599627370496.515");
                });
    }

    @Test
    void metricOnlyConditionsCannotReadUnauthorizedFields() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "count",
                                                            "计数",
                                                            "COUNT",
                                                            null,
                                                            null,
                                                            new DataScope(
                                                                    "AND",
                                                                    List.of(
                                                                            new DataScope.Condition(
                                                                                    "name", "eq",
                                                                                    "B")),
                                                                    List.of()),
                                                            null)),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(),
                                            saved.revision(),
                                            "metric_conditions",
                                            "仅指标条件"),
                                    10001);
                    assertThat(savedQuery(release, null, Map.of(), null).totals().get("count"))
                            .isEqualTo("2");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(seed, Set.of(seed.amount()), false)))),
                            10001);
                    assertThatThrownBy(() -> savedQuery(release, null, Map.of(), null))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void formulaValidationRejectsCyclesMissingReferencesAndConflictingPayloadsAtomically() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Detail before = datasets.get(seed.dataset().id(), 10001);
                    ReportDatasetQueries.Metric sum =
                            new ReportDatasetQueries.Metric("sum", "总额", "SUM", "amount");
                    for (List<ReportDatasetQueries.Metric> metrics :
                            List.of(
                                    List.of(sum, formula("bad", "DIVIDE", "sum", "missing")),
                                    List.of(sum, formula("bad", "ADD", "bad", "sum")),
                                    List.of(
                                            sum,
                                            formula("a", "ADD", "b", "sum"),
                                            formula("b", "ADD", "a", "sum")),
                                    List.of(sum, formula("bad", "SQL", "sum", "sum")),
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                    "bad",
                                                    "错误",
                                                    "FORMULA",
                                                    "amount",
                                                    null,
                                                    null,
                                                    new ApplicationReports.Formula(
                                                            "ADD", "bad", "bad"))),
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                    "bad",
                                                    "错误",
                                                    "COUNT",
                                                    null,
                                                    null,
                                                    null,
                                                    new ApplicationReports.Formula(
                                                            "ADD", "bad", "bad"))),
                                    List.of(
                                            new ReportDatasetQueries.Metric(
                                                    "bad",
                                                    "错误",
                                                    "COUNT",
                                                    null,
                                                    null,
                                                    new DataScope(
                                                            "AND",
                                                            List.of(
                                                                    new DataScope.Condition(
                                                                            "missing", "eq", 1)),
                                                            List.of()),
                                                    null)))) {
                        assertThatThrownBy(
                                        () ->
                                                saveAnalysis(
                                                        seed,
                                                        new ReportDatasets.Analysis(
                                                                1,
                                                                metrics,
                                                                null,
                                                                Map.of(),
                                                                "Asia/Shanghai")))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(datasets.get(before.id(), 10001)).isEqualTo(before);
                    }
                });
    }

    private ReportDatasetQueries.Metric formula(
            String id, String operator, String left, String right) {
        return new ReportDatasetQueries.Metric(
                id,
                id,
                "FORMULA",
                null,
                null,
                null,
                new ApplicationReports.Formula(operator, left, right));
    }

    @Test
    void candidatesUseAuthorizedFactsAndSearchBeforeStablePagination() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    insertRows(seed);
                    String table =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getPublished(seed.object())
                                    .tableName();
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\"(name,amount,creator,deleted) VALUES"
                                    + " ('',1,'10001',0),(NULL,2,'10001',0)");
                    ReportDatasetQueries.OptionPage first =
                            candidateQuery(release, "name", null, 1, 2, "");
                    assertThat(first.total()).isEqualTo(4);
                    assertThat(
                                    first.list().stream()
                                            .map(ReportDatasetQueries.Option::value)
                                            .toList())
                            .containsExactly("", "A");
                    ReportDatasetQueries.OptionPage second =
                            candidateQuery(release, "name", null, 2, 2, "");
                    assertThat(
                                    second.list().stream()
                                            .map(ReportDatasetQueries.Option::value)
                                            .toList())
                            .containsExactly("B", null);
                    assertThat(second.list().getLast().label()).isEqualTo("未填写");
                    assertThat(candidateQuery(release, "name", null, 1, 1, "b").list())
                            .containsExactly(new ReportDatasetQueries.Option("B", "B"));
                    assertThat(candidateQuery(release, "name", null, 1, 20, "%' OR 1=1 --").total())
                            .isZero();
                    DataScope filters =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition("name", "eq", "A")),
                                    List.of(
                                            new DataScope(
                                                    "OR",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    "amount", "gte", "20")),
                                                    List.of())));
                    assertThat(candidateQuery(release, "name", filters, 1, 20, "").list())
                            .containsExactly(new ReportDatasetQueries.Option("B", "B"));
                    // 候选同样保持字段权限与行范围绑定，不能因查询去重值而合并放宽。
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    scoped(
                                                            seed,
                                                            seed.name(),
                                                            "A",
                                                            Set.of(seed.name(), seed.amount()))))),
                            10001);
                    assertThat(candidateQuery(release, "name", null, 1, 20, "").list())
                            .containsExactly(new ReportDatasetQueries.Option("A", "A"));
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    2,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(seed, Set.of(seed.amount()), false)))),
                            10001);
                    assertThatThrownBy(() -> candidateQuery(release, "name", null, 1, 20, ""))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void candidateSelfFilterNeverRemovesFixedConditionsOrChangesPublishedVersion() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release old = authorized(seed);
                    insertRows(seed);
                    DataScope fixed =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition("name", "eq", "B")),
                                    List.of());
                    ReportDatasets.Detail draft =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1, List.of(), fixed, Map.of(), "Asia/Shanghai"));
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            draft.id(), draft.revision(), "options_v2", "候选固定条件"),
                                    10001);
                    DataScope self =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition("name", "eq", "A")),
                                    List.of());
                    assertThat(candidateQuery(release, "name", self, 1, 20, "").list())
                            .containsExactly(new ReportDatasetQueries.Option("B", "B"));
                    assertThat(candidateQuery(old, "name", self, 1, 20, "").total()).isEqualTo(2);
                    DataScope unfilled =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition("name", "eq", null)),
                                    List.of());
                    assertThat(candidateQuery(old, "name", unfilled, 1, 20, "").total())
                            .isEqualTo(2);
                    assertThatThrownBy(() -> candidateQuery(release, "missing", null, 1, 20, ""))
                            .hasMessageContaining("字段");
                    assertThatThrownBy(() -> candidateQuery(release, "name", null, 0, 20, ""))
                            .hasMessageContaining("参数");
                    assertThatThrownBy(() -> candidateQuery(release, "name", null, 1, 101, ""))
                            .hasMessageContaining("参数");
                });
    }

    private ReportDatasetQueries.OptionPage candidateQuery(
            ReportDatasets.Release release,
            String field,
            DataScope filters,
            int page,
            int size,
            String search) {
        return context.getBean(
                        com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService
                                .class)
                .options(
                        new ReportDatasetQueries.Options(
                                release.datasetId(),
                                release.versionNo(),
                                release.checksum(),
                                false,
                                field,
                                filters,
                                page,
                                size,
                                search),
                        10001);
    }

    private ReportDatasets.Detail saveAnalysis(Seed seed, ReportDatasets.Analysis analysis) {
        ReportDatasets.Detail current = datasets.get(seed.dataset().id(), 10001);
        return datasets.save(
                new ReportDatasets.Save(
                        current.id(),
                        current.revision(),
                        current.draft().name(),
                        "",
                        current.draft().source(),
                        analysis),
                10001);
    }

    private ApplicationReports.Result datasetQuery(ReportDatasetQueries.Query query) {
        return context.getBean(
                        com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService
                                .class)
                .query(query, 10001);
    }

    private ApplicationReports.Result savedQuery(
            ReportDatasets.Release release,
            List<String> ids,
            Map<String, Object> equal,
            String timeZone) {
        return datasetQuery(
                new ReportDatasetQueries.Query(
                        release.datasetId(),
                        release.versionNo(),
                        release.checksum(),
                        false,
                        List.of(),
                        null,
                        equal,
                        20,
                        timeZone,
                        ids));
    }

    @Test
    void aggregationKeepsFieldRightsBoundToTheirRowsAndBindsFilterValues() {
        rollback(
                () -> {
                    Seed seed = seed();
                    auth.saveCeiling(
                            ceiling(
                                    seed,
                                    0,
                                    grant(seed, Set.of(seed.name(), seed.amount()), false)),
                            10003);
                    role(10001, 200L);
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
                                                            Set.of(seed.name()))),
                                            member(
                                                    "ROLE",
                                                    "200",
                                                    scoped(
                                                            seed,
                                                            seed.name(),
                                                            "B",
                                                            Set.of(seed.amount()))))),
                            10001);
                    ReportDatasets.Release release = datasets.publish(publish(seed), 10001);
                    insertRows(seed);
                    ApplicationReports.Result allowed = aggregate(release, List.of(), Map.of());
                    assertThat(allowed.recordCount()).isEqualTo(2);
                    assertThat(new java.math.BigDecimal(allowed.totals().get("sum")))
                            .isEqualByComparingTo("25.75");
                    assertThat(
                                    aggregate(
                                                    release,
                                                    List.of(
                                                            new ReportDatasetQueries.Dimension(
                                                                    "name", "VALUE")),
                                                    Map.of())
                                            .recordCount())
                            .isZero();
                    assertThat(aggregate(release, List.of(), Map.of("name", "A")).recordCount())
                            .isZero();
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            false)))),
                            10001);
                    assertThat(
                                    aggregate(release, List.of(), Map.of("name", "' OR 1=1 --"))
                                            .recordCount())
                            .isZero();
                    assertThat(aggregate(release, List.of(), Map.of("name", "A")).recordCount())
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            aggregate(
                                                    release,
                                                    List.of(),
                                                    Map.of("not_a_dataset_field", "A")))
                            .hasMessageContaining("不属于数据集");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    2,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(seed, Set.of(seed.name()), false)))),
                            10001);
                    assertThatThrownBy(() -> aggregate(release, List.of(), Map.of()))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    private ApplicationReports.Result aggregate(
            ReportDatasets.Release release,
            List<ReportDatasetQueries.Dimension> dimensions,
            Map<String, Object> equal) {
        return context.getBean(
                        com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService
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

    /** A08：实际多值关联存在时只聚合订单根记录，首批未支持的展开在保存时明确拒绝。 */
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
                                        com.lingan.ucp.nocode.runtime.service.record.RecordRelations
                                                .class)
                                .attach(root.definition(), relation, order, id, 10001);
                    }
                    assertThat(
                                    servicesContext
                                            .getBean(
                                                    com.lingan.ucp.nocode.runtime.service.record
                                                            .RecordRelations.class)
                                            .targets(root.definition(), relation, order, 10001))
                            .containsExactlyInAnyOrderElementsOf(arrivalIds);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
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
                                    new com.lingan.ucp.nocode.web.RecordExcelService()
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

    @Test
    void relatedDimensionsFilterHiddenTargetsWithoutMergingThemIntoNullGroup() {
        rollback(
                () -> {
                    Seed target = seed();
                    insertRows(target);
                    String code = fixture.prefix + "joined";
                    DataCenter.Design design =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            new SaveObjectDraft(
                                                    null,
                                                    null,
                                                    code,
                                                    "关联聚合",
                                                    null,
                                                    "biz_" + code,
                                                    "name",
                                                    List.of(
                                                            new FieldDefinition(
                                                                    "name", null, "name", "名称",
                                                                    "TEXT", 100, null, null, false,
                                                                    false, 0),
                                                            new FieldDefinition(
                                                                    "amount", null, "amount", "金额",
                                                                    "DECIMAL", null, 20, 2, false,
                                                                    false, 1)),
                                                    List.of()),
                                            DataCenter.Settings.defaults(),
                                            Map.of(),
                                            List.of(
                                                    new DataCenter.Relation(
                                                            null,
                                                            "company",
                                                            "公司",
                                                            "REFERENCE",
                                                            target.object(),
                                                            null,
                                                            null,
                                                            false,
                                                            "RESTRICT")),
                                            List.of(),
                                            List.of()),
                                    10001);
                    DataCenter.PublishPlan plan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            design.draft().id(),
                                            design.draft().lockVersion(),
                                            null),
                                    10001);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(plan.id(), "关联查询夹具"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
                    DataObjectApi.PublishedObject
                            root = objects.getVersion(design.draft().id(), null),
                            destination = objects.getVersion(target.object(), null);
                    DataCenter.Relation relation = root.definition().relations().getFirst();
                    String amount =
                            root.definition().fields().stream()
                                    .filter(f -> f.code().equals("amount"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    ReportDatasets.Source source =
                            new ReportDatasets.Source(
                                    1,
                                    new ReportDatasets.ObjectReference(
                                            root.objectId(), root.versionNo(), root.checksum()),
                                    List.of(
                                            new ReportDatasets.Relation(
                                                    "company",
                                                    List.of(),
                                                    relation.id(),
                                                    new ReportDatasets.ObjectReference(
                                                            destination.objectId(),
                                                            destination.versionNo(),
                                                            destination.checksum()))),
                                    List.of(
                                            new ReportDatasets.Field(
                                                    "amount", List.of(), amount, "金额", "MEASURE"),
                                            new ReportDatasets.Field(
                                                    "company_key",
                                                    List.of(),
                                                    relation.fieldId(),
                                                    "公司原键",
                                                    "DIMENSION"),
                                            new ReportDatasets.Field(
                                                    "company",
                                                    List.of("company"),
                                                    target.name(),
                                                    "公司",
                                                    "DIMENSION")));
                    ReportDatasets.Detail dataset =
                            datasets.save(
                                    new ReportDatasets.Save(null, 0, "关联数据集", "", source), 10001);
                    ids.add(dataset.id());
                    ObjectGrant rootGrant =
                            new ObjectGrant(
                                    root.objectId(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(amount, relation.fieldId()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    ObjectGrant targetGrant =
                            scoped(
                                    target,
                                    target.name(),
                                    "A",
                                    Set.of(target.name(), target.amount()));
                    auth.saveCeiling(
                            new ReportAuthorization.SaveCeiling(
                                    dataset.id(), root.objectId(), 0, rootGrant, "根授权"),
                            10003);
                    auth.saveCeiling(
                            new ReportAuthorization.SaveCeiling(
                                    dataset.id(), target.object(), 0, targetGrant, "关联授权"),
                            10003);
                    auth.saveDataPolicy(
                            new ReportAuthorization.SaveDataPolicy(
                                    dataset.id(),
                                    0,
                                    List.of(
                                            new Member(
                                                    "USER",
                                                    "10001",
                                                    List.of(rootGrant, targetGrant))),
                                    "关联策略"),
                            10001);
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            dataset.id(), dataset.revision(), "join", "关联查询"),
                                    10001);
                    long visible =
                            jdbc.queryForObject(
                                    "SELECT id FROM public.\""
                                            + destination.definition().tableName()
                                            + "\" WHERE name='A'",
                                    Long.class);
                    long hidden =
                            jdbc.queryForObject(
                                    "SELECT min(id) FROM public.\""
                                            + destination.definition().tableName()
                                            + "\" WHERE name='B'",
                                    Long.class);
                    String column =
                            root.definition().fields().stream()
                                    .filter(f -> f.id().equals(relation.fieldId()))
                                    .findFirst()
                                    .orElseThrow()
                                    .code();
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + root.definition().tableName()
                                    + "\"(name,amount,\""
                                    + column
                                    + "\") VALUES ('可见',7,?),('隐藏',9,?),('未填写',4,null)",
                            visible,
                            hidden);
                    ApplicationReports.Result result =
                            aggregate(
                                    release,
                                    List.of(new ReportDatasetQueries.Dimension("company", "VALUE")),
                                    Map.of());
                    assertThat(result.recordCount()).isEqualTo(2);
                    assertThat(result.groups()).hasSize(2);
                    assertThat(new java.math.BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo("11");
                    assertThat(result.groups())
                            .anyMatch(group -> group.keys().getFirst() == null)
                            .anyMatch(group -> "A".equals(group.keys().getFirst()));
                    ReportDatasets.Detail configured = datasets.get(dataset.id(), 10001);
                    ReportDatasets.Detail filtered =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            configured.id(),
                                            configured.revision(),
                                            configured.draft().name(),
                                            "",
                                            source,
                                            new ReportDatasets.Analysis(
                                                    1,
                                                    List.of(
                                                            new ReportDatasetQueries.Metric(
                                                                    "sum", "金额", "SUM", "amount")),
                                                    new DataScope(
                                                            "AND",
                                                            List.of(),
                                                            List.of(
                                                                    new DataScope(
                                                                            "OR",
                                                                            List.of(
                                                                                    new DataScope
                                                                                            .Condition(
                                                                                            "company",
                                                                                            "eq",
                                                                                            "A"),
                                                                                    new DataScope
                                                                                            .Condition(
                                                                                            "amount",
                                                                                            "gt",
                                                                                            "8")),
                                                                            List.of()))),
                                                    Map.of(),
                                                    "Asia/Shanghai")),
                                    10001);
                    ReportDatasets.Release filteredRelease =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            filtered.id(),
                                            filtered.revision(),
                                            "related_fixed",
                                            "关联条件测试"),
                                    10001);
                    assertThat(savedQuery(filteredRelease, null, Map.of(), null).recordCount())
                            .isEqualTo(1);
                    assertThat(
                                    new java.math.BigDecimal(
                                            savedQuery(filteredRelease, null, Map.of(), null)
                                                    .totals()
                                                    .get("sum")))
                            .isEqualByComparingTo("7");
                    // 同名目标不能合并，候选先按目标标题搜索，再分页返回原键。
                    long sameTitle =
                            jdbc.queryForObject(
                                    "INSERT INTO public.\""
                                            + destination.definition().tableName()
                                            + "\"(name,amount) VALUES ('A',0) RETURNING id",
                                    Long.class);
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + root.definition().tableName()
                                    + "\"(name,amount,\""
                                    + column
                                    + "\") VALUES ('同名',11,?)",
                            sameTitle);
                    ApplicationReports.Result titled =
                            aggregate(
                                    release,
                                    List.of(
                                            new ReportDatasetQueries.Dimension(
                                                    "company_key", "VALUE")),
                                    Map.of());
                    assertThat(titled.groups()).hasSize(3);
                    assertThat(
                                    titled.groups().stream()
                                            .filter(group -> "A".equals(group.labels().getFirst()))
                                            .map(group -> group.keys().getFirst()))
                            .containsExactlyInAnyOrder(
                                    Long.toString(visible), Long.toString(sameTitle));
                    assertThat(titled.recordCount()).isEqualTo(3);
                    ReportDatasetQueries.OptionPage names =
                            candidateQuery(release, "company_key", null, 1, 1, "A");
                    assertThat(names.total()).isEqualTo(2);
                    assertThat(names.list()).hasSize(1);
                    assertThat(names.list().getFirst().label()).isEqualTo("A");
                    assertThat(
                                    candidateQuery(release, "company_key", null, 2, 1, "A")
                                            .list()
                                            .getFirst()
                                            .value())
                            .isNotEqualTo(names.list().getFirst().value());
                    assertThat(candidateQuery(release, "company_key", null, 1, 20, "B").total())
                            .isZero();
                    // 目标标题已登记为数据集字段，类型变更不能绕过原有依赖保护。
                    FieldSwitchPreview.Result titleImpact =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.schema.service.convert
                                                    .FieldSwitchPreviewService.class)
                                    .preview(
                                            new FieldSwitchPreview.Request(
                                                    target.object(),
                                                    null,
                                                    target.name(),
                                                    "DECIMAL",
                                                    null,
                                                    20,
                                                    2,
                                                    null,
                                                    null,
                                                    false));
                    assertThat(titleImpact.decision())
                            .isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
                    DataCenter.Design targetEdit =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            target.object(),
                                            designs.get(target.object()).draft().lockVersion(),
                                            null),
                                    10001);
                    ObjectDraft targetDraft = targetEdit.draft();
                    List<FieldDefinition> changedTitleFields =
                            new ArrayList<>(targetDraft.fields());
                    changedTitleFields.add(
                            new FieldDefinition(
                                    "alternate_title",
                                    null,
                                    "alternate_title",
                                    "新标题",
                                    "TEXT",
                                    100,
                                    null,
                                    null,
                                    false,
                                    false,
                                    20));
                    DataCenter.Design targetChanged =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            new SaveObjectDraft(
                                                    targetDraft.id(),
                                                    targetDraft.lockVersion(),
                                                    targetDraft.objectCode(),
                                                    targetDraft.objectName(),
                                                    targetDraft.description(),
                                                    targetDraft.tableName(),
                                                    "alternate_title",
                                                    changedTitleFields,
                                                    List.of()),
                                            targetEdit.settings(),
                                            targetEdit.fieldOptions(),
                                            targetEdit.relations(),
                                            targetEdit.indexes(),
                                            targetEdit.details()),
                                    10001);
                    DataCenter.PublishPlan targetPlan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            target.object(),
                                            targetChanged.draft().lockVersion(),
                                            null),
                                    10001);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(
                                                            targetPlan.id(), "变更最新标题配置"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    assertThat(candidateQuery(release, "company_key", null, 1, 20, "A").list())
                            .allMatch(option -> "A".equals(option.label()));
                    assertThat(
                                    datasets
                                            .releases(release.datasetId(), 1, 20, 10001)
                                            .getList()
                                            .stream()
                                            .filter(
                                                    version ->
                                                            version.versionNo()
                                                                    == release.versionNo())
                                            .findFirst()
                                            .orElseThrow()
                                            .checksum())
                            .isEqualTo(release.checksum());
                    FieldSwitchPreview.Result fixedTitleImpact =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.schema.service.convert
                                                    .FieldSwitchPreviewService.class)
                                    .preview(
                                            new FieldSwitchPreview.Request(
                                                    target.object(),
                                                    null,
                                                    target.name(),
                                                    "DECIMAL",
                                                    null,
                                                    20,
                                                    2,
                                                    null,
                                                    null,
                                                    false));
                    assertThat(fixedTitleImpact.impacts())
                            .anyMatch(
                                    impact ->
                                            impact.blocking()
                                                    && "DATASET".equals(impact.sourceKind()));
                    ObjectGrant noTitle =
                            new ObjectGrant(
                                    target.object(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(target.amount()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    auth.saveDataPolicy(
                            new ReportAuthorization.SaveDataPolicy(
                                    dataset.id(),
                                    1,
                                    List.of(
                                            new Member(
                                                    "USER", "10001", List.of(rootGrant, noTitle))),
                                    "撤销标题读取"),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            aggregate(
                                                    release,
                                                    List.of(
                                                            new ReportDatasetQueries.Dimension(
                                                                    "company_key", "VALUE")),
                                                    Map.of()))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () -> candidateQuery(release, "company_key", null, 1, 20, "A"))
                            .isInstanceOf(AccessDeniedException.class);
                    ObjectGrant noKey =
                            new ObjectGrant(
                                    root.objectId(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(amount),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    auth.saveDataPolicy(
                            new ReportAuthorization.SaveDataPolicy(
                                    dataset.id(),
                                    2,
                                    List.of(
                                            new Member(
                                                    "USER", "10001", List.of(noKey, targetGrant))),
                                    "移除连接键权限"),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            aggregate(
                                                    release,
                                                    List.of(
                                                            new ReportDatasetQueries.Dimension(
                                                                    "company", "VALUE")),
                                                    Map.of()))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void departmentTreeChangesBeforeDeliveryDiscardResults() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDatasets.Release release = authorized(seed);
                    servicesContext.getBean(AdminUserApi.class).getUser(10001L).setDeptId(10L);
                    com.lingan.ucp.module.system.api.dept.DeptApi departments =
                            servicesContext.getBean(
                                    com.lingan.ucp.module.system.api.dept.DeptApi.class);
                    com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO parent =
                            new com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO();
                    parent.setId(10L);
                    parent.setStatus(0);
                    com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO child =
                            new com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO();
                    child.setId(11L);
                    child.setStatus(0);
                    when(departments.getDept(10L)).thenReturn(parent);
                    when(departments.getChildDeptList(10L)).thenReturn(List.of(child));
                    assertThatThrownBy(
                                    () ->
                                            auth.withDataAccess(
                                                    release.datasetId(),
                                                    release.versionNo(),
                                                    release.checksum(),
                                                    false,
                                                    10001,
                                                    access -> {
                                                        assertThat(
                                                                        access.scopeContext()
                                                                                .get(
                                                                                        "CURRENT_DEPARTMENT_TREE"))
                                                                .isEqualTo(List.of("10", "11"));
                                                        when(departments.getChildDeptList(10L))
                                                                .thenReturn(List.of());
                                                        return "不能交付";
                                                    }))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void migrationBackfillsNestedScopesForExistingCeilingsAndMemberPolicies() {
        rollback(
                () -> {
                    Seed seed = seed();
                    DataScope scope =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition(seed.name(), "eq", "A")),
                                    List.of(
                                            new DataScope(
                                                    "OR",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    seed.amount(), "eq", "10")),
                                                    List.of())));
                    ObjectGrant grant =
                            new ObjectGrant(
                                    seed.object(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(seed.name(), seed.amount()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Map.of("READ", scope),
                                    Set.of());
                    auth.saveCeiling(ceiling(seed, 0, grant), 10003);
                    auth.saveDataPolicy(
                            policy(seed, 0, List.of(member("USER", "10001", grant))), 10001);
                    DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
                    objects.removeDependencies(
                            "DATASET", seed.dataset().id() + ":ceiling:" + seed.object(), 10001);
                    objects.removeDependencies("DATASET", seed.dataset().id() + ":policy", 10001);
                    try (java.io.InputStream input =
                            getClass()
                                    .getResourceAsStream(
                                            "/db/nocode/V054__report_authorization_dependencies.sql")) {
                        assertThat(input).isNotNull();
                        String migration =
                                new String(
                                        input.readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        // 只运行当前夹具的补登记分支，避免重写其他开发数据的审计时间。
                        jdbc.execute(
                                migration.replace(
                                        "d.deleted=0",
                                        "d.deleted=0 AND d.id=" + seed.dataset().id()));
                    } catch (java.io.IOException error) {
                        throw new AssertionError(error);
                    }
                    assertThat(
                                    centerMapper
                                            .dependencies(Long.parseLong(seed.object()))
                                            .stream()
                                            .filter(
                                                    dependency ->
                                                            dependency
                                                                            .sourceKey()
                                                                            .contains(":ceiling:")
                                                                    || dependency
                                                                            .sourceKey()
                                                                            .endsWith(":policy")))
                            .hasSize(2)
                            .allSatisfy(
                                    dependency ->
                                            assertThat(dependency.fieldIds())
                                                    .containsExactlyInAnyOrder(
                                                            seed.name(), seed.amount()));
                });
    }

    @Test
    void independentDashboardPersistsPublishesQueriesAndProtectsFixedDependencies() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release source =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "dashboard_data", "看板测试"),
                                    10001);
                    com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                            context.getBean(
                                    com.lingan.ucp.nocode.report.service.dashboard
                                            .ReportDashboardService.class);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDashboardQueryService.class);
                    ReportDashboards.Dataset reference =
                            new ReportDashboards.Dataset(
                                    source.datasetId(), source.versionNo(), source.checksum());
                    List<ReportDashboards.Chart> charts = new ArrayList<>();
                    for (String display : List.of("METRIC", "BAR", "LINE", "PIE", "TABLE"))
                        charts.add(
                                new ReportDashboards.Chart(
                                        display,
                                        display,
                                        display,
                                        reference,
                                        display.equals("METRIC")
                                                ? List.of()
                                                : List.of(
                                                        new ReportDatasetQueries.Dimension(
                                                                "name", "VALUE")),
                                        List.of("sum"),
                                        0,
                                        charts.size() * 6,
                                        6,
                                        6));
                    ReportDashboards.Content content =
                            new ReportDashboards.Content(1, "独立看板", "真实查询", charts);
                    ReportDashboards.Detail board =
                            boards.save(new ReportDashboards.Save(null, 0, content), 10001);
                    assertThat(boards.get(board.id(), 10001).draft()).isEqualTo(content);
                    assertThatThrownBy(() -> boards.get(board.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            boards.save(
                                                    new ReportDashboards.Save(
                                                            board.id(), 0, content),
                                                    10001))
                            .hasMessageContaining("修改");
                    ReportDashboards.Release released =
                            boards.publish(
                                    new ReportDashboards.Publish(
                                            board.id(), board.revision(), "first"),
                                    10001);
                    assertThat(
                                    boards.publish(
                                            new ReportDashboards.Publish(
                                                    board.id(), board.revision(), "first"),
                                            10001))
                            .isEqualTo(released);
                    for (ReportDashboards.Chart chart : charts) {
                        ApplicationReports.Result result =
                                query.query(
                                        new ReportDashboards.Query(
                                                board.id(),
                                                chart.id(),
                                                false,
                                                released.versionNo(),
                                                released.checksum()),
                                        10001);
                        assertThat(result.recordCount()).isEqualTo(3);
                        assertThat(new java.math.BigDecimal(result.totals().get("sum")))
                                .isEqualByComparingTo("36");
                    }
                    assertThat(datasets.deletePreview(seed.dataset().id(), 10001).canDelete())
                            .isFalse();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_report_dependency"
                                                + " WHERE source_kind='DASHBOARD' AND source_id=?"
                                                + " AND deleted=0",
                                            Long.class,
                                            Long.parseLong(board.id())))
                            .isEqualTo(2);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    boards.save(
                            new ReportDashboards.Save(
                                    current.id(),
                                    current.revision(),
                                    new ReportDashboards.Content(1, "已修改草稿", "", List.of())),
                            10001);
                    assertThat(
                                    boards.published(
                                                    board.id(),
                                                    released.versionNo(),
                                                    released.checksum(),
                                                    10001)
                                            .content())
                            .isEqualTo(content);
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    new ReportDashboards.Query(
                                                            board.id(),
                                                            "fake",
                                                            false,
                                                            released.versionNo(),
                                                            released.checksum()),
                                                    10001))
                            .hasMessageContaining("不属于");
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    new ReportDashboards.Query(
                                                            board.id(),
                                                            "METRIC",
                                                            false,
                                                            released.versionNo(),
                                                            "tampered"),
                                                    10001))
                            .hasMessageContaining("校验和");
                    auth.saveDataPolicy(policy(seed, 1, List.of()), 10001);
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    new ReportDashboards.Query(
                                                            board.id(),
                                                            "METRIC",
                                                            false,
                                                            released.versionNo(),
                                                            released.checksum()),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void independentPivotDetailsAndExportPreservePrecisionAndActionScopes() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount"),
                                                    new ReportDatasetQueries.Metric(
                                                            "avg", "均值", "AVG", "amount"),
                                                    new ReportDatasetQueries.Metric(
                                                            "unique",
                                                            "去重名称",
                                                            "COUNT_DISTINCT",
                                                            "name"),
                                                    new ReportDatasetQueries.Metric(
                                                            "count", "记录数", "COUNT", null),
                                                    new ReportDatasetQueries.Metric(
                                                            "ratio",
                                                            "平均金额",
                                                            "FORMULA",
                                                            null,
                                                            null,
                                                            null,
                                                            new ApplicationReports.Formula(
                                                                    "DIVIDE", "sum", "count"))),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release release =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "pivot_data", "透视测试"),
                                    10001);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService queries =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDatasetQueryService.class);
                    ReportDashboards.Chart chart =
                            new ReportDashboards.Chart(
                                    "pivot",
                                    "透视",
                                    "PIVOT",
                                    new ReportDashboards.Dataset(
                                            release.datasetId(),
                                            release.versionNo(),
                                            release.checksum()),
                                    List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                                    List.of("sum", "avg", "unique", "ratio"),
                                    0,
                                    0,
                                    12,
                                    6,
                                    List.of(),
                                    ApplicationReports.Pivot.defaults());
                    ApplicationReports.Result result = queries.chart(chart, false, 10001);
                    assertThat(new java.math.BigDecimal(result.totals().get("avg")))
                            .isEqualByComparingTo("12");
                    assertThat(new java.math.BigDecimal(result.totals().get("ratio")))
                            .isEqualByComparingTo("12");
                    assertThat(result.totals().get("unique")).isEqualTo("2");
                    assertThat(result.canExport()).isTrue();
                    assertThat(result.pivot().cells())
                            .anySatisfy(
                                    c -> {
                                        assertThat(c.rowKeys()).isEmpty();
                                        assertThat(c.columnKeys()).isEmpty();
                                        assertThat(new java.math.BigDecimal(c.values().get("avg")))
                                                .isEqualByComparingTo("12");
                                    });
                    ReportDashboards.DetailPage details =
                            queries.details(
                                    chart,
                                    new ReportDashboards.Details(
                                            null, List.of("B"), List.of(), "sum", 1, 1),
                                    10001);
                    assertThat(details.total()).isEqualTo(2);
                    assertThat(details.list()).hasSize(1);
                    ReportDashboards.DetailPage next =
                            queries.details(
                                    chart,
                                    new ReportDashboards.Details(
                                            null, List.of("B"), List.of(), "sum", 2, 1),
                                    10001);
                    assertThat(next.list().getFirst().id())
                            .isNotEqualTo(details.list().getFirst().id());
                    assertThatThrownBy(
                                    () ->
                                            queries.details(
                                                    chart,
                                                    new ReportDashboards.Details(
                                                            null, List.of(), List.of(), "ratio", 1,
                                                            20),
                                                    10001))
                            .hasMessageContaining("基础指标");
                    assertThatThrownBy(
                                    () ->
                                            queries.details(
                                                    chart,
                                                    new ReportDashboards.Details(
                                                            null,
                                                            List.of("B", "forged"),
                                                            List.of(),
                                                            null,
                                                            1,
                                                            20),
                                                    10001))
                            .hasMessageContaining("范围");
                    assertThat(queries.chart(chart, true, 10001).recordCount()).isEqualTo(3);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            false)))),
                            10001);
                    assertThat(queries.chart(chart, false, 10001).recordCount()).isEqualTo(3);
                    assertThat(queries.chart(chart, false, 10001).canExport()).isFalse();
                    assertThatThrownBy(() -> queries.chart(chart, true, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    ObjectGrant restricted =
                            new ObjectGrant(
                                    seed.object(),
                                    Set.of("READ", "EXPORT"),
                                    "ALL",
                                    Set.of(seed.name(), seed.amount()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Map.of(
                                            "EXPORT",
                                            new DataScope(
                                                    "AND",
                                                    List.of(
                                                            new DataScope.Condition(
                                                                    seed.name(), "eq", "A")),
                                                    List.of())),
                                    Set.of());
                    auth.saveDataPolicy(
                            policy(seed, 2, List.of(member("USER", "10001", restricted))), 10001);
                    ApplicationReports.Result exported = queries.chart(chart, true, 10001);
                    assertThat(exported.recordCount()).isEqualTo(1);
                    assertThat(new java.math.BigDecimal(exported.totals().get("sum")))
                            .isEqualByComparingTo("10.25");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    3,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10001",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            true)))),
                            10001);
                    String table =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getPublished(seed.object())
                                    .tableName();
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\"(name,amount,creator,deleted) VALUES"
                                    + " (NULL,9007199254740993.01,'10001',0)");
                    assertThat(queries.chart(chart, false, 10001).totals().get("sum"))
                            .isEqualTo("9007199254741029.01");
                    ReportDashboards.DetailPage nulls =
                            queries.details(
                                    chart,
                                    new ReportDashboards.Details(
                                            null,
                                            Collections.singletonList(null),
                                            List.of(),
                                            "sum",
                                            1,
                                            20),
                                    10001);
                    assertThat(nulls.total()).isEqualTo(1);
                    assertThat(nulls.list().getFirst().values()).contains("9007199254740993.01");
                    assertThat(queries.chart(chart, true, 10001).totals().get("sum"))
                            .isEqualTo("9007199254741029.01");
                });
    }

    @Test
    void dashboardInteractionsIntersectAndKeepNullAcrossOptionsDetailsAndExport() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    String table =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getPublished(seed.object())
                                    .tableName();
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\"(name,amount,creator,deleted) VALUES"
                                    + " (NULL,3,'10001',0),(NULL,6,'10001',0),('%_literal',2,'10001',0)");
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release source =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(), saved.revision(), "interaction", "交互测试"),
                                    10001);
                    ReportDashboards.Dataset ref =
                            new ReportDashboards.Dataset(
                                    source.datasetId(), source.versionNo(), source.checksum());
                    ReportDashboards.Link link =
                            new ReportDashboards.Link("target", "name", "name");
                    List<ReportDashboards.Chart> charts =
                            List.of(
                                    interactionChart(
                                            "source", ref, "name", "VALUE", 0, null, List.of(link)),
                                    interactionChart(
                                            "other", ref, "name", "VALUE", 6, null, List.of(link)),
                                    interactionChart(
                                            "target", ref, "name", "VALUE", 12, null, null));
                    List<ReportDashboards.Mapping> nameMap =
                            List.of(new ReportDashboards.Mapping("target", "name"));
                    List<ReportDashboards.Filter> filters =
                            List.of(
                                    new ReportDashboards.Filter(
                                            "names", "名称", "MULTISELECT", nameMap),
                                    new ReportDashboards.Filter(
                                            "amount",
                                            "金额范围",
                                            "NUMBER_RANGE",
                                            List.of(
                                                    new ReportDashboards.Mapping(
                                                            "target", "amount"))),
                                    new ReportDashboards.Filter("search", "名称搜索", "TEXT", nameMap));
                    ReportDashboards.Release board =
                            publishBoard(
                                    new ReportDashboards.Content(1, "交互看板", "", charts, filters));
                    com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDashboardQueryService.class);
                    ReportDashboards.FilterValue range =
                            new ReportDashboards.FilterValue("amount", null, "10", null);
                    ReportDashboards.Selection b =
                            new ReportDashboards.Selection("source", List.of("B"));
                    ReportDashboards.Query constrained =
                            interactionQuery(board, "target", List.of(range), List.of(b), null);
                    assertThat(query.query(constrained, 10001).recordCount()).isEqualTo(1);
                    assertThat(
                                    new java.math.BigDecimal(
                                            query.query(constrained, 10001).totals().get("sum")))
                            .isEqualByComparingTo("20.75");
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(range),
                                                            List.of(
                                                                    b,
                                                                    new ReportDashboards.Selection(
                                                                            "other", List.of("A"))),
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isZero();
                    // 移除一个来源后仍保留另一来源及公共范围。
                    assertThat(query.query(constrained, 10001).recordCount()).isEqualTo(1);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(range),
                                                            List.of(),
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(2);
                    ReportDashboards.Query nulls =
                            interactionQuery(
                                    board,
                                    "target",
                                    List.of(
                                            new ReportDashboards.FilterValue(
                                                    "amount", null, "0", "5")),
                                    List.of(
                                            new ReportDashboards.Selection(
                                                    "source", Arrays.asList((String) null))),
                                    null);
                    assertThat(query.query(nulls, 10001).recordCount()).isEqualTo(1);
                    assertThat(query.export(nulls, 10001).totals().get("sum"))
                            .isEqualTo(query.query(nulls, 10001).totals().get("sum"));
                    assertThat(
                                    query.details(
                                                    new ReportDashboards.Details(
                                                            nulls,
                                                            Arrays.asList((String) null),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10001)
                                            .total())
                            .isEqualTo(1);
                    // 候选仅移除自身筛选身份，保留同一字段上的联动。
                    ReportDashboards.Query candidates =
                            interactionQuery(
                                    board,
                                    "target",
                                    List.of(
                                            range,
                                            new ReportDashboards.FilterValue(
                                                    "names", List.of("A"), null, null)),
                                    List.of(b),
                                    null);
                    assertThat(
                                    query.options(
                                                    new ReportDashboards.Options(
                                                            candidates, "names", 1, 20, ""),
                                                    10001)
                                            .list())
                            .extracting(ReportDatasetQueries.Option::value)
                            .containsExactly("B");
                    assertThat(
                                    query.options(
                                                    new ReportDashboards.Options(
                                                            nulls, "names", 1, 20, ""),
                                                    10001)
                                            .list())
                            .extracting(ReportDatasetQueries.Option::value)
                            .containsExactly((String) null);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "search",
                                                                            List.of("b"),
                                                                            null,
                                                                            null)),
                                                            null,
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(2);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "search",
                                                                            List.of("%_"),
                                                                            null,
                                                                            null)),
                                                            null,
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(1);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "names",
                                                                            Arrays.asList(
                                                                                    "A", null),
                                                                            null,
                                                                            null)),
                                                            null,
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(3);
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "forged",
                                                                            List.of("A"),
                                                                            null,
                                                                            null)),
                                                            null,
                                                            null),
                                                    10001))
                            .hasMessageContaining("身份");
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            null,
                                                            List.of(
                                                                    new ReportDashboards.Selection(
                                                                            "source", List.of())),
                                                            null),
                                                    10001))
                            .hasMessageContaining("完整");
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    interactionQuery(
                                                            board,
                                                            "target",
                                                            null,
                                                            null,
                                                            List.of("A")),
                                                    10001))
                            .hasMessageContaining("层级");
                    assertThatThrownBy(
                                    () ->
                                            publishBoard(
                                                    new ReportDashboards.Content(
                                                            1,
                                                            "伪造",
                                                            "",
                                                            charts,
                                                            List.of(
                                                                    new ReportDashboards.Filter(
                                                                            "bad",
                                                                            "伪造",
                                                                            "SELECT",
                                                                            List.of(
                                                                                    new ReportDashboards
                                                                                            .Mapping(
                                                                                            "target",
                                                                                            "missing")))))))
                            .hasMessageContaining("固定版本");
                    assertThatThrownBy(
                                    () ->
                                            publishBoard(
                                                    new ReportDashboards.Content(
                                                            1,
                                                            "错误类型",
                                                            "",
                                                            charts,
                                                            List.of(
                                                                    new ReportDashboards.Filter(
                                                                            "bad",
                                                                            "错误类型",
                                                                            "NUMBER_RANGE",
                                                                            nameMap)))))
                            .hasMessageContaining("类型");
                    auth.saveDataPolicy(policy(seed, 1, List.of()), 10001);
                    assertThatThrownBy(() -> query.query(constrained, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> query.export(nulls, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            query.options(
                                                    new ReportDashboards.Options(
                                                            candidates, "names", 1, 20, ""),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void dashboardDateRangeAndSameFieldBucketsKeepDrillDetailsAndExportConsistent() {
        rollback(
                () -> {
                    Seed seed = interactionSeed();
                    DataObjectApi.PublishedObject object =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getVersion(seed.object(), null);
                    Set<String> fields =
                            object.definition().fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet());
                    ObjectGrant grant = grant(seed, fields, true);
                    auth.saveCeiling(ceiling(seed, 0, grant), 10003);
                    auth.saveDataPolicy(
                            policy(seed, 0, List.of(member("USER", "10001", grant))), 10001);
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + object.definition().tableName()
                                    + "\"(name,amount,happened,active,creator,deleted) VALUES"
                                    + " (NULL,3,'2026-01-31 23:59:59',false,'10001',0),"
                                    + " ('A',7,'2026-01-31 12:30:00',true,'10001',0),"
                                    + " ('A',5,'2026-02-01 00:00:00',true,'10001',0),"
                                    + " ('A',11,'2027-01-31 12:00:00',true,'10001',0)");
                    ReportDatasets.Detail saved =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release source =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            saved.id(),
                                            saved.revision(),
                                            "date_interaction",
                                            "日期交互测试"),
                                    10001);
                    ReportDashboards.Dataset ref =
                            new ReportDashboards.Dataset(
                                    source.datasetId(), source.versionNo(), source.checksum());
                    List<ReportDashboards.Chart> charts =
                            List.of(
                                    interactionChart(
                                            "dates",
                                            ref,
                                            "happened",
                                            "YEAR",
                                            0,
                                            List.of(
                                                    new ReportDatasetQueries.Dimension(
                                                            "happened", "MONTH"),
                                                    new ReportDatasetQueries.Dimension(
                                                            "happened", "DAY")),
                                            null),
                                    interactionChart(
                                            "names",
                                            ref,
                                            "name",
                                            "VALUE",
                                            6,
                                            List.of(
                                                    new ReportDatasetQueries.Dimension(
                                                            "active", "VALUE"),
                                                    new ReportDatasetQueries.Dimension(
                                                            "happened", "MONTH")),
                                            null));
                    List<ReportDashboards.Filter> filters =
                            List.of(
                                    new ReportDashboards.Filter(
                                            "date",
                                            "业务日期",
                                            "DATE_RANGE",
                                            List.of(
                                                    new ReportDashboards.Mapping(
                                                            "dates", "happened"),
                                                    new ReportDashboards.Mapping(
                                                            "names", "happened"))),
                                    new ReportDashboards.Filter(
                                            "active",
                                            "有效",
                                            "SELECT",
                                            List.of(
                                                    new ReportDashboards.Mapping(
                                                            "names", "active"))));
                    ReportDashboards.Release board =
                            publishBoard(
                                    new ReportDashboards.Content(1, "日期看板", "", charts, filters));
                    com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDashboardQueryService.class);
                    ReportDashboards.Query year =
                            interactionQuery(board, "dates", null, null, List.of("2026"));
                    assertThat(query.query(year, 10001).recordCount()).isEqualTo(3);
                    assertThat(query.query(year, 10001).groups())
                            .extracting(g -> g.keys().getFirst())
                            .containsExactly("2026-01", "2026-02");
                    ReportDashboards.Query month =
                            interactionQuery(
                                    board, "dates", null, null, List.of("2026", "2026-01"));
                    assertThat(query.query(month, 10001).recordCount()).isEqualTo(2);
                    assertThat(query.query(month, 10001).groups().getFirst().keys())
                            .containsExactly("2026-01-31");
                    assertThat(
                                    query.details(
                                                    new ReportDashboards.Details(
                                                            month,
                                                            List.of("2026-01-31"),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10001)
                                            .total())
                            .isEqualTo(2);
                    assertThat(query.export(month, 10001).recordCount()).isEqualTo(2);
                    List<ReportDashboards.FilterValue> januaryDay =
                            List.of(
                                    new ReportDashboards.FilterValue(
                                            "date", null, "2026-01-31", "2026-01-31"));
                    ReportDashboards.Query range =
                            interactionQuery(board, "dates", januaryDay, null, null);
                    assertThat(query.query(range, 10001).recordCount()).isEqualTo(2);
                    assertThat(
                                    new java.math.BigDecimal(
                                            query.export(range, 10001).totals().get("sum")))
                            .isEqualByComparingTo("10");
                    assertThat(
                                    query.details(
                                                    new ReportDashboards.Details(
                                                            range,
                                                            List.of("2026"),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10001)
                                            .total())
                            .isEqualTo(2);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board, "dates", null, null, null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(4);
                    ReportDashboards.Query nullPath =
                            interactionQuery(
                                    board, "names", null, null, Arrays.asList(null, "false"));
                    assertThat(query.query(nullPath, 10001).recordCount()).isEqualTo(1);
                    assertThat(query.query(nullPath, 10001).groups().getFirst().keys())
                            .containsExactly("2026-01");
                    assertThat(
                                    query.details(
                                                    new ReportDashboards.Details(
                                                            nullPath,
                                                            List.of("2026-01"),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10001)
                                            .total())
                            .isEqualTo(1);
                    assertThat(
                                    query.query(
                                                    interactionQuery(
                                                            board,
                                                            "names",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "active",
                                                                            List.of("false"),
                                                                            null,
                                                                            null)),
                                                            null,
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    interactionQuery(
                                                            board,
                                                            "dates",
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "date",
                                                                            null,
                                                                            "2026-02-01",
                                                                            "2026-01-31")),
                                                            null,
                                                            null),
                                                    10001))
                            .hasMessageContaining("范围");
                    assertThatThrownBy(
                                    () ->
                                            publishBoard(
                                                    new ReportDashboards.Content(
                                                            1,
                                                            "重复层级",
                                                            "",
                                                            List.of(
                                                                    interactionChart(
                                                                            "bad",
                                                                            ref,
                                                                            "happened",
                                                                            "YEAR",
                                                                            0,
                                                                            List.of(
                                                                                    new ReportDatasetQueries
                                                                                            .Dimension(
                                                                                            "happened",
                                                                                            "YEAR")),
                                                                            null)))))
                            .hasMessageContaining("重复");
                });
    }

    private ReportDashboards.Chart interactionChart(
            String id,
            ReportDashboards.Dataset reference,
            String field,
            String bucket,
            int y,
            List<ReportDatasetQueries.Dimension> drill,
            List<ReportDashboards.Link> links) {
        return new ReportDashboards.Chart(
                id,
                id,
                "TABLE",
                reference,
                List.of(new ReportDatasetQueries.Dimension(field, bucket)),
                List.of("sum"),
                0,
                y,
                12,
                6,
                null,
                null,
                drill,
                links);
    }

    private ReportDashboards.Release publishBoard(ReportDashboards.Content content) {
        com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                context.getBean(
                        com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService
                                .class);
        ReportDashboards.Detail saved =
                boards.save(new ReportDashboards.Save(null, 0, content), 10001);
        return boards.publish(
                new ReportDashboards.Publish(
                        saved.id(), saved.revision(), UUID.randomUUID().toString()),
                10001);
    }

    private ReportDashboards.Query interactionQuery(
            ReportDashboards.Release board,
            String chart,
            List<ReportDashboards.FilterValue> filters,
            List<ReportDashboards.Selection> selections,
            List<String> path) {
        return new ReportDashboards.Query(
                board.id(),
                chart,
                false,
                board.versionNo(),
                board.checksum(),
                filters,
                selections,
                path);
    }

    /** 专项夹具保留已有体验数据，所有对象、看板与授权写入均由测试外层事务回滚。 */
    private Seed interactionSeed() {
        String code = fixture.prefix + "interaction";
        ObjectDraft draft =
                service.create(
                        new SaveObjectDraft(
                                null,
                                null,
                                code,
                                "交互测试",
                                null,
                                "biz_" + code,
                                "name",
                                List.of(
                                        new FieldDefinition(
                                                "name", null, "name", "名称", "TEXT", 100, null, null,
                                                false, false, 0),
                                        new FieldDefinition(
                                                "amount", null, "amount", "金额", "DECIMAL", null, 20,
                                                2, false, false, 1),
                                        new FieldDefinition(
                                                "happened",
                                                null,
                                                "happened",
                                                "业务日期",
                                                "DATETIME",
                                                null,
                                                null,
                                                null,
                                                false,
                                                false,
                                                2),
                                        new FieldDefinition(
                                                "active", null, "active", "有效", "BOOLEAN", null,
                                                null, null, false, false, 3)),
                                List.of()),
                        10001,
                        UUID.randomUUID());
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "交互夹具"), 10001).state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject object =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        Map<String, String> idsByCode = new LinkedHashMap<>();
        draft.fields().forEach(field -> idsByCode.put(field.code(), field.id()));
        List<ReportDatasets.Field> fields =
                idsByCode.entrySet().stream()
                        .map(
                                entry ->
                                        new ReportDatasets.Field(
                                                entry.getKey(),
                                                List.of(),
                                                entry.getValue(),
                                                entry.getKey(),
                                                entry.getKey().equals("amount")
                                                        ? "MEASURE"
                                                        : "DIMENSION"))
                        .toList();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), object.versionNo(), object.checksum()),
                        List.of(),
                        fields);
        ReportDatasets.Detail saved =
                datasets.save(new ReportDatasets.Save(null, 0, code, "", source), 10001);
        ids.add(saved.id());
        return new Seed(saved, draft.id(), idsByCode.get("name"), idsByCode.get("amount"));
    }

    @Test
    void dashboardAudienceViewsWithoutDatasetUseButNeverBypassesDataOrExportRights() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail configured =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release data =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            configured.id(),
                                            configured.revision(),
                                            "audience_data",
                                            "受众数据"),
                                    10001);
                    ReportDashboards.Dataset ref =
                            new ReportDashboards.Dataset(
                                    data.datasetId(), data.versionNo(), data.checksum());
                    ReportDashboards.Chart source =
                            interactionChart(
                                    "source",
                                    ref,
                                    "name",
                                    "VALUE",
                                    0,
                                    null,
                                    List.of(new ReportDashboards.Link("target", "name", "name")));
                    ReportDashboards.Chart target =
                            interactionChart("target", ref, "name", "VALUE", 6, null, null);
                    List<ReportDashboards.Filter> filters =
                            List.of(
                                    new ReportDashboards.Filter(
                                            "names",
                                            "名称",
                                            "SELECT",
                                            List.of(
                                                    new ReportDashboards.Mapping(
                                                            "target", "name"))));
                    ReportDashboards.Release board =
                            publishBoard(
                                    new ReportDashboards.Content(
                                            1, "已发布给受众", "", List.of(source, target), filters));
                    com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                            context.getBean(
                                    com.lingan.ucp.nocode.report.service.dashboard
                                            .ReportDashboardService.class);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDashboardQueryService.class);
                    ReportDashboards.Query request =
                            interactionQuery(board, "target", null, null, null);
                    assertThatThrownBy(() -> query.query(request, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportAuthorization.ResourcePolicy acl =
                            boards.saveResource(
                                    new ReportAuthorization.SaveDashboardResource(
                                            board.id(),
                                            0,
                                            List.of(
                                                    new ReportAuthorization.ResourceMember(
                                                            "USER",
                                                            "10002",
                                                            Set.of("VIEW", "EXPORT"))),
                                            "分享受众"),
                                    10001);
                    assertThat(acl.revision()).isEqualTo(1);
                    // VIEW 并不替未登记的受众补成员数据策略。
                    assertThatThrownBy(() -> query.query(request, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ObjectGrant all = grant(seed, Set.of(seed.name(), seed.amount()), true);
                    ObjectGrant read = grant(seed, Set.of(seed.name(), seed.amount()), false);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    1,
                                    List.of(
                                            member("USER", "10001", all),
                                            member("USER", "10002", read))),
                            10001);
                    assertThat(query.query(request, 10002).recordCount()).isEqualTo(3);
                    assertThat(query.query(request, 10002).canExport()).isFalse();
                    assertThatThrownBy(() -> query.export(request, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> datasets.get(data.datasetId(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(
                                                            com.lingan.ucp.nocode.runtime.service
                                                                    .report
                                                                    .ReportDatasetQueryService
                                                                    .class)
                                                    .query(
                                                            new ReportDatasetQueries.Query(
                                                                    data.datasetId(),
                                                                    data.versionNo(),
                                                                    data.checksum(),
                                                                    false,
                                                                    List.of(
                                                                            new ReportDatasetQueries
                                                                                    .Dimension(
                                                                                    "name",
                                                                                    "VALUE")),
                                                                    null,
                                                                    Map.of(),
                                                                    100,
                                                                    null,
                                                                    List.of("sum")),
                                                            10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportDashboards.Query linked =
                            interactionQuery(
                                    board,
                                    "target",
                                    null,
                                    List.of(new ReportDashboards.Selection("source", List.of("B"))),
                                    null);
                    assertThat(query.query(linked, 10002).recordCount()).isEqualTo(2);
                    assertThat(
                                    query.details(
                                                    new ReportDashboards.Details(
                                                            linked,
                                                            List.of("B"),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10002)
                                            .total())
                            .isEqualTo(2);
                    assertThat(
                                    query.options(
                                                    new ReportDashboards.Options(
                                                            linked, "names", 1, 20, ""),
                                                    10002)
                                            .list())
                            .extracting(ReportDatasetQueries.Option::value)
                            .containsExactly("B");
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    2,
                                    List.of(
                                            member("USER", "10001", all),
                                            member("USER", "10002", all))),
                            10001);
                    assertThat(query.query(linked, 10002).canExport()).isTrue();
                    assertThat(query.export(linked, 10002).recordCount()).isEqualTo(2);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    1,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW"))),
                                    "撤销导出"),
                            10001);
                    assertThat(query.query(linked, 10002).canExport()).isFalse();
                    assertThatThrownBy(() -> query.export(linked, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    // 拥有者只自动获得资源管理动作，同样必须登记来源数据策略。
                    auth.saveDataPolicy(
                            policy(seed, 3, List.of(member("USER", "10002", all))), 10001);
                    assertThatThrownBy(() -> query.query(request, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> boards.resourcePolicy(board.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(), 2, List.of(), "撤销受众"),
                            10001);
                    assertThatThrownBy(() -> query.query(linked, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            query.details(
                                                    new ReportDashboards.Details(
                                                            linked,
                                                            List.of("B"),
                                                            null,
                                                            "sum",
                                                            1,
                                                            20),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            query.options(
                                                    new ReportDashboards.Options(
                                                            linked, "names", 1, 20, ""),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            boards.saveResource(
                                                    new ReportAuthorization.SaveDashboardResource(
                                                            board.id(), 1, List.of(), "过期授权"),
                                                    10001))
                            .hasMessageContaining("修改");
                    assertThatThrownBy(
                                    () ->
                                            boards.saveResource(
                                                    new ReportAuthorization.SaveDashboardResource(
                                                            board.id(),
                                                            3,
                                                            List.of(
                                                                    new ReportAuthorization
                                                                            .ResourceMember(
                                                                            "USER",
                                                                            "10002",
                                                                            Set.of("USE"))),
                                                            "错误动作"),
                                                    10001))
                            .hasMessageContaining("仪表板");
                    assertThatThrownBy(
                                    () ->
                                            boards.saveResource(
                                                    new ReportAuthorization.SaveDashboardResource(
                                                            board.id(),
                                                            3,
                                                            List.of(
                                                                    new ReportAuthorization
                                                                            .ResourceMember(
                                                                            "USER",
                                                                            "10002",
                                                                            Set.of("EXPORT"))),
                                                            "错误导出"),
                                                    10001))
                            .hasMessageContaining("查看");
                });
    }

    @Test
    void dashboardAvailableSummariesHideDraftsAndSeparatePublishFromEdit() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    ReportDatasets.Detail configured =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release data =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            configured.id(),
                                            configured.revision(),
                                            "summary_data",
                                            "摘要数据"),
                                    10001);
                    ReportDashboards.Dataset ref =
                            new ReportDashboards.Dataset(
                                    data.datasetId(), data.versionNo(), data.checksum());
                    ReportDashboards.Chart chart =
                            interactionChart("chart", ref, "name", "VALUE", 0, null, null);
                    ReportDashboards.Release board =
                            publishBoard(
                                    new ReportDashboards.Content(1, "公开名称", "", List.of(chart)));
                    com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                            context.getBean(
                                    com.lingan.ucp.nocode.report.service.dashboard
                                            .ReportDashboardService.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW")),
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10003", Set.of("PUBLISH"))),
                                    "查看与发布分离"),
                            10001);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Content privateDraft =
                            new ReportDashboards.Content(
                                    1,
                                    "私密草稿名称",
                                    "私密说明",
                                    List.of(
                                            chart,
                                            interactionChart(
                                                    "private", ref, "name", "VALUE", 6, null,
                                                    null)));
                    boards.save(
                            new ReportDashboards.Save(board.id(), current.revision(), privateDraft),
                            10001);
                    ReportDashboards.AvailableItem visible =
                            boards.availablePage(1, 20, "公开名称", 10002).getList().getFirst();
                    assertThat(visible.id()).isEqualTo(board.id());
                    assertThat(visible.name()).isEqualTo("公开名称");
                    assertThat(visible.chartCount()).isEqualTo(1);
                    assertThat(visible.modified()).isFalse();
                    assertThat(visible.revision()).isNull();
                    assertThat(visible.capabilities())
                            .isEqualTo(
                                    new ReportDashboards.Capabilities(
                                            true, false, false, false, false, false));
                    assertThat(boards.availablePage(1, 20, "私密草稿名称", 10002).getTotal()).isZero();
                    assertThat(
                                    boards.availablePage(1, 20, "私密草稿名称", 10001)
                                            .getList()
                                            .getFirst()
                                            .chartCount())
                            .isEqualTo(2);
                    assertThat(
                                    boards.availablePage(1, 20, "私密草稿名称", 10001)
                                            .getList()
                                            .getFirst()
                                            .modified())
                            .isTrue();
                    assertThatThrownBy(() -> boards.get(board.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            boards.resolve(
                                                    new ReportDashboards.Query(
                                                            board.id(), "chart", true, null, null),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportDashboards.Detail publisher = boards.get(board.id(), 10003);
                    assertThat(publisher.capabilities().canPublish()).isTrue();
                    assertThat(publisher.capabilities().canEdit()).isFalse();
                    assertThatThrownBy(
                                    () ->
                                            boards.save(
                                                    new ReportDashboards.Save(
                                                            board.id(),
                                                            publisher.revision(),
                                                            privateDraft),
                                                    10003))
                            .isInstanceOf(AccessDeniedException.class);
                    // 发布者读取草稿，发布时仍必须拥有数据集设计 USE。
                    assertThatThrownBy(
                                    () ->
                                            boards.publish(
                                                    new ReportDashboards.Publish(
                                                            board.id(),
                                                            publisher.revision(),
                                                            "no_dataset_use"),
                                                    10003))
                            .isInstanceOf(AccessDeniedException.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    1,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("GRANT")),
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10003", Set.of("PUBLISH"))),
                                    "仅授权管理"),
                            10001);
                    ReportDashboards.AvailableItem manager =
                            boards.availablePage(1, 20, "公开名称", 10002).getList().getFirst();
                    assertThat(manager.capabilities().canGrant()).isTrue();
                    assertThat(manager.capabilities().canView()).isFalse();
                    assertThat(manager.name()).isEqualTo("公开名称");
                    assertThat(manager.chartCount()).isEqualTo(1);
                    assertThat(manager.revision()).isNull();
                    assertThat(manager.modified()).isFalse();
                    assertThat(boards.resourcePolicy(board.id(), 10002).revision()).isEqualTo(2);
                    assertThatThrownBy(
                                    () ->
                                            boards.published(
                                                    board.id(),
                                                    board.versionNo(),
                                                    board.checksum(),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:manage"))
                            .thenReturn(false);
                    assertThat(boards.availablePage(1, 20, "公开名称", 10002).getTotal()).isZero();
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:manage"))
                            .thenReturn(true);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    data.datasetId(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10003", Set.of("USE"))),
                                    "发布者数据集使用权"),
                            10001);
                    assertThat(
                                    boards.publish(
                                                    new ReportDashboards.Publish(
                                                            board.id(),
                                                            publisher.revision(),
                                                            "with_dataset_use"),
                                                    10003)
                                            .content())
                            .isEqualTo(privateDraft);
                    ReportDashboards.Detail unpublished =
                            boards.save(
                                    new ReportDashboards.Save(
                                            null,
                                            0,
                                            new ReportDashboards.Content(
                                                    1, "未发布私密看板", "", List.of(chart))),
                                    10001);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    unpublished.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW")),
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10003", Set.of("EDIT"))),
                                    "未发布协作"),
                            10001);
                    assertThat(boards.availablePage(1, 20, "未发布私密看板", 10002).getTotal()).isZero();
                    assertThat(boards.availablePage(1, 20, "未发布私密看板", 10003).getList()).hasSize(1);
                    assertThat(boards.get(unpublished.id(), 10003).capabilities().canEdit())
                            .isTrue();
                    when(permissions.hasAnyPermissions(10003L, "nocode:report:update"))
                            .thenReturn(false);
                    assertThat(boards.get(unpublished.id(), 10003).capabilities().canEdit())
                            .isFalse();
                    assertThatThrownBy(
                                    () ->
                                            boards.save(
                                                    new ReportDashboards.Save(
                                                            unpublished.id(),
                                                            unpublished.revision(),
                                                            unpublished.draft()),
                                                    10003))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void dashboardControlledSourcesAndRoleRecheckRejectUnrelatedReferencesAndRevocation() {
        rollback(
                () -> {
                    Seed sourceSeed = seed();
                    authorized(sourceSeed);
                    insertRows(sourceSeed);
                    Seed targetSeed = seed("auth_target");
                    authorized(targetSeed);
                    insertRows(targetSeed);
                    List<ReportDashboards.Dataset> refs = new ArrayList<>();
                    for (Seed seed : List.of(sourceSeed, targetSeed)) {
                        ReportDatasets.Detail configured =
                                saveAnalysis(
                                        seed,
                                        new ReportDatasets.Analysis(
                                                1,
                                                List.of(
                                                        new ReportDatasetQueries.Metric(
                                                                "sum", "金额", "SUM", "amount")),
                                                null,
                                                Map.of(),
                                                "Asia/Shanghai"));
                        ReportDatasets.Release data =
                                datasets.publish(
                                        new ReportDatasets.Publish(
                                                configured.id(),
                                                configured.revision(),
                                                "controlled_data",
                                                "受控数据"),
                                        10001);
                        refs.add(
                                new ReportDashboards.Dataset(
                                        data.datasetId(), data.versionNo(), data.checksum()));
                    }
                    ReportDashboards.Chart source =
                            interactionChart(
                                    "source",
                                    refs.get(0),
                                    "name",
                                    "VALUE",
                                    0,
                                    null,
                                    List.of(new ReportDashboards.Link("target", "name", "name")));
                    ReportDashboards.Chart target =
                            interactionChart("target", refs.get(1), "name", "VALUE", 6, null, null);
                    ReportDashboards.Release board =
                            publishBoard(
                                    new ReportDashboards.Content(
                                            1,
                                            "受控来源",
                                            "",
                                            List.of(source, target),
                                            List.of(
                                                    new ReportDashboards.Filter(
                                                            "names",
                                                            "名称",
                                                            "SELECT",
                                                            List.of(
                                                                    new ReportDashboards.Mapping(
                                                                            "target", "name"))))));
                    com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                            context.getBean(
                                    com.lingan.ucp.nocode.report.service.dashboard
                                            .ReportDashboardService.class);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDashboardQueryService.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "ROLE", "200", Set.of("VIEW", "EXPORT"))),
                                    "角色受众"),
                            10001);
                    role(10002, 200);
                    ObjectGrant targetGrant =
                            grant(targetSeed, Set.of(targetSeed.name(), targetSeed.amount()), true);
                    auth.saveDataPolicy(
                            policy(
                                    targetSeed,
                                    1,
                                    List.of(
                                            member("USER", "10001", targetGrant),
                                            member("ROLE", "200", targetGrant))),
                            10001);
                    ReportDashboards.Query direct =
                            interactionQuery(board, "target", null, null, null);
                    assertThat(query.query(direct, 10002).recordCount()).isEqualTo(3);
                    ReportDashboards.Query linked =
                            interactionQuery(
                                    board,
                                    "target",
                                    null,
                                    List.of(new ReportDashboards.Selection("source", List.of("B"))),
                                    null);
                    // 联动来源没有数据成员权，不能借目标数据集的授权驱动它。
                    assertThatThrownBy(() -> query.query(linked, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            query.options(
                                                    new ReportDashboards.Options(
                                                            linked, "names", 1, 20, ""),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ObjectGrant sourceGrant =
                            grant(sourceSeed, Set.of(sourceSeed.name(), sourceSeed.amount()), true);
                    auth.saveDataPolicy(
                            policy(
                                    sourceSeed,
                                    1,
                                    List.of(
                                            member("USER", "10001", sourceGrant),
                                            member("ROLE", "200", sourceGrant))),
                            10001);
                    assertThat(query.query(linked, 10002).recordCount()).isEqualTo(2);
                    ReportDashboards.Resolved resolved = boards.resolve(direct, 10002);
                    ReportDashboards.Chart fake =
                            interactionChart("target", refs.get(0), "name", "VALUE", 6, null, null);
                    assertThatThrownBy(
                                    () ->
                                            auth.withDashboardDataAccess(
                                                    new ReportDashboards.Resolved(
                                                            resolved.request(),
                                                            resolved.revision(),
                                                            resolved.stamp(),
                                                            fake,
                                                            resolved.content()),
                                                    10002,
                                                    context -> "fake"))
                            .hasMessageContaining("变化");
                    assertThatThrownBy(
                                    () ->
                                            auth.withDashboardDataAccess(
                                                    resolved,
                                                    10002,
                                                    access -> {
                                                        when(permissions.getUserRoleIds(10002L))
                                                                .thenReturn(Set.of());
                                                        return "must_not_deliver";
                                                    }))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> query.query(direct, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(boards.availablePage(1, 20, "受控来源", 10002).getTotal()).isZero();
                });
    }

    @Test
    void viewOnlyReadAudienceCommitsQueriesWithoutExportProbeMarkingTransactionRollbackOnly() {
        // 不能用测试外层回滚事务掩盖内层 canExport 探测留下的 rollback-only 标记。
        Seed seed = seed();
        authorized(seed);
        insertRows(seed);
        ReportDatasets.Detail configured =
                saveAnalysis(
                        seed,
                        new ReportDatasets.Analysis(
                                1,
                                List.of(
                                        new ReportDatasetQueries.Metric(
                                                "sum", "金额", "SUM", "amount")),
                                null,
                                Map.of(),
                                "Asia/Shanghai"));
        ReportDatasets.Release data =
                datasets.publish(
                        new ReportDatasets.Publish(
                                configured.id(),
                                configured.revision(),
                                "view_read_commit",
                                "查看受众提交事务"),
                        10001);
        auth.saveDataPolicy(
                policy(
                        seed,
                        1,
                        List.of(
                                member(
                                        "USER",
                                        "10002",
                                        grant(seed, Set.of(seed.name(), seed.amount()), false)))),
                10001);
        com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService boards =
                context.getBean(
                        com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService
                                .class);
        ReportDashboards.Detail saved =
                boards.save(
                        new ReportDashboards.Save(
                                null,
                                0,
                                new ReportDashboards.Content(
                                        1,
                                        fixture.prefix + "查看受众提交",
                                        "",
                                        List.of(
                                                new ReportDashboards.Chart(
                                                        "table",
                                                        "查看数据",
                                                        "TABLE",
                                                        new ReportDashboards.Dataset(
                                                                data.datasetId(),
                                                                data.versionNo(),
                                                                data.checksum()),
                                                        List.of(
                                                                new ReportDatasetQueries.Dimension(
                                                                        "name", "VALUE")),
                                                        List.of("sum"),
                                                        0,
                                                        0,
                                                        12,
                                                        4)))),
                        10001);
        long boardId = Long.parseLong(saved.id());
        try {
            ReportDashboards.Release board =
                    boards.publish(
                            new ReportDashboards.Publish(
                                    saved.id(), saved.revision(), "view_read_commit"),
                            10001);
            boards.saveResource(
                    new ReportAuthorization.SaveDashboardResource(
                            board.id(),
                            0,
                            List.of(
                                    new ReportAuthorization.ResourceMember(
                                            "USER", "10002", Set.of("VIEW"))),
                            "只看受众"),
                    10001);
            ReportDashboards.Query request =
                    new ReportDashboards.Query(
                            board.id(), "table", false, board.versionNo(), board.checksum());
            com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService query =
                    context.getBean(
                            com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService
                                    .class);
            ApplicationReports.Result result = query.query(request, 10002);
            assertThat(result.recordCount()).isEqualTo(3);
            assertThat(result.canExport()).isFalse();
            assertThat(new java.math.BigDecimal(result.totals().get("sum")))
                    .isEqualByComparingTo("36.00");
            assertThat(
                            auth.withDashboardDataAccess(
                                    boards.resolve(request, 10002),
                                    10002,
                                    ReportDatasetAuthorizationService.Context::resourceCanExport))
                    .isFalse();
            assertThatThrownBy(() -> query.export(request, 10002))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            // 精确创建 ID 清理，保留所有已有体验看板与历史引用。
            jdbc.update(
                    "DELETE FROM public.nocode_report_dependency WHERE source_kind='DASHBOARD' AND"
                            + " source_id=?",
                    boardId);
            jdbc.update(
                    "DELETE FROM public.nocode_report_resource_acl WHERE resource_kind='DASHBOARD'"
                            + " AND resource_id=?",
                    boardId);
            jdbc.update(
                    "DELETE FROM public.nocode_report_operation_log WHERE resource_kind='DASHBOARD'"
                            + " AND resource_id=?",
                    boardId);
            jdbc.update(
                    "DELETE FROM public.nocode_report_dashboard_version WHERE dashboard_id=?",
                    boardId);
            jdbc.update(
                    "DELETE FROM public.nocode_report_dashboard WHERE id=? AND owner_id=10001",
                    boardId);
        }
    }

    @Test
    void ordinaryDatasetUseCanExportWithActualDataExportButNeitherRightCanBeBypassed() {
        rollback(
                () -> {
                    Seed seed = seed();
                    authorized(seed);
                    insertRows(seed);
                    ReportDatasets.Detail configured =
                            saveAnalysis(
                                    seed,
                                    new ReportDatasets.Analysis(
                                            1,
                                            List.of(
                                                    new ReportDatasetQueries.Metric(
                                                            "sum", "金额", "SUM", "amount")),
                                            null,
                                            Map.of(),
                                            "Asia/Shanghai"));
                    ReportDatasets.Release data =
                            datasets.publish(
                                    new ReportDatasets.Publish(
                                            configured.id(),
                                            configured.revision(),
                                            "dataset_use_export",
                                            "数据集导出测试"),
                                    10001);
                    ObjectGrant exporting = grant(seed, Set.of(seed.name(), seed.amount()), true);
                    auth.saveDataPolicy(
                            policy(seed, 1, List.of(member("USER", "10002", exporting))), 10001);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("USE"))),
                                    "普通数据集使用者"),
                            10001);
                    ReportDashboards.Chart chart =
                            new ReportDashboards.Chart(
                                    "normal",
                                    "普通数据集导出",
                                    "TABLE",
                                    new ReportDashboards.Dataset(
                                            data.datasetId(), data.versionNo(), data.checksum()),
                                    List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                                    List.of("sum"),
                                    0,
                                    0,
                                    12,
                                    4);
                    com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService query =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDatasetQueryService.class);
                    assertThat(query.chart(chart, false, 10002).canExport()).isTrue();
                    assertThat(query.chart(chart, true, 10002).recordCount()).isEqualTo(3);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(), 1, List.of(), "撤销使用"),
                            10001);
                    assertThatThrownBy(() -> query.chart(chart, true, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.dataset().id(),
                                    2,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("USE"))),
                                    "恢复使用"),
                            10001);
                    auth.saveDataPolicy(
                            policy(
                                    seed,
                                    2,
                                    List.of(
                                            member(
                                                    "USER",
                                                    "10002",
                                                    grant(
                                                            seed,
                                                            Set.of(seed.name(), seed.amount()),
                                                            false)))),
                            10001);
                    assertThat(query.chart(chart, false, 10002).recordCount()).isEqualTo(3);
                    assertThat(query.chart(chart, false, 10002).canExport()).isFalse();
                    assertThatThrownBy(() -> query.chart(chart, true, 10002))
                            .isInstanceOf(AccessDeniedException.class);
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
}
