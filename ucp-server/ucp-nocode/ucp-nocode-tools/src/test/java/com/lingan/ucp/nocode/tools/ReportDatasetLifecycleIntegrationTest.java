package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.report.service.dataset.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

/** 在当前开发库验证数据集版本事务；多数用例回滚，并发用例只清理本次记录的精确 ID。 */
class ReportDatasetLifecycleIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext context;
    private ReportDatasetService datasets;
    private DataObjectApi objects;
    private final Set<String> ids = new HashSet<>();

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
        ReportIntegrationSupport.activeUsers(10001L, 10002L);
        datasets = context.getBean(ReportDatasetService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        for (long actor : List.of(10001L, 10002L)) {
            for (String code : List.of("query", "create", "update", "publish", "manage"))
                when(permissions.hasAnyPermissions(actor, "nocode:report:" + code))
                        .thenReturn(true);
            when(permissions.hasAnyPermissions(actor, "nocode:object:query")).thenReturn(true);
        }
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        context.close();
        for (String id : ids) {
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
            ReportIntegrationSupport.clearAuthorization(id);
            jdbc.update(
                    "DELETE FROM public.nocode_report_dataset WHERE id=? AND owner_id=10001",
                    Long.parseLong(id));
        }
        fixture.clean();
    }

    @Test
    void savesIncompleteDraftButRefusesPublishingWithoutSource() {
        rollback(
                () -> {
                    ReportDatasets.Detail draft = create(null);
                    assertThat(draft.revision()).isEqualTo(1);
                    assertThat(draft.status()).isEqualTo("INACTIVE");
                    assertThat(draft.modified()).isTrue();
                    assertThat(datasets.get(draft.id(), 10001)).isEqualTo(draft);
                    assertThatThrownBy(() -> datasets.publish(publish(draft, "first"), 10001))
                            .hasMessageContaining("来源");
                    assertThat(datasets.releases(draft.id(), 1, 10, 10001).getTotal()).isZero();
                    assertThat(datasets.get(draft.id(), 10001).revision()).isEqualTo(1);
                });
    }

    @Test
    void separatesOwnershipFromSystemPermissionAndFiltersList() {
        rollback(
                () -> {
                    ReportDatasets.Detail draft = create(null);
                    assertThatThrownBy(() -> datasets.get(draft.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> datasets.get(draft.id(), 20000))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            draft.id(), 1, "越权", "", null),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(datasets.page(1, 10, fixture.prefix, 10002).getTotal()).isZero();
                    assertThat(datasets.page(1, 10, fixture.prefix, 10001).getList())
                            .extracting(ReportDatasets.Detail::id)
                            .containsExactly(draft.id());
                    assertThat(datasets.page(1, 10, "%", 10001).getList())
                            .noneMatch(item -> item.id().equals(draft.id()));
                });
    }

    @Test
    void publishesImmutableSnapshotAndRetriesIdempotentlyEvenAfterDraftChanges() {
        rollback(
                () -> {
                    ReportDatasets.Detail draft = create(source("original"));
                    ReportDatasets.Publish request = publish(draft, "same_request");
                    ReportDatasets.Release first = datasets.publish(request, 10001);
                    assertThat(first.versionNo()).isEqualTo(1);
                    ReportDatasets.Detail published = datasets.get(draft.id(), 10001);
                    assertThat(published.modified()).isFalse();
                    assertThat(published.status()).isEqualTo("ACTIVE");
                    ReportDatasets.Detail modified =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            draft.id(),
                                            published.revision(),
                                            "修改后的名称",
                                            "新说明",
                                            draft.draft().source()),
                                    10001);
                    assertThat(modified.modified()).isTrue();
                    assertThat(datasets.publish(request, 10001)).isEqualTo(first);
                    assertThat(datasets.releases(draft.id(), 1, 10, 10001).getTotal()).isEqualTo(1);
                    assertThat(datasets.get(draft.id(), 10001).revision())
                            .isEqualTo(modified.revision());
                    assertThatThrownBy(
                                    () ->
                                            datasets.publish(
                                                    new ReportDatasets.Publish(
                                                            draft.id(),
                                                            modified.revision(),
                                                            "same_request",
                                                            "另一个请求"),
                                                    10001))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("不同请求");
                });
    }

    @Test
    void rejectsStaleRevisionsAcrossAllWrites() {
        rollback(
                () -> {
                    ReportDatasets.Detail draft = create(source("stale"));
                    datasets.publish(publish(draft, "first"), 10001);
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            draft.id(),
                                                            draft.revision(),
                                                            "失效",
                                                            "",
                                                            null),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    assertThatThrownBy(() -> datasets.publish(publish(draft, "different"), 10001))
                            .hasMessageContaining("已被修改");
                    assertThatThrownBy(
                                    () ->
                                            datasets.restore(
                                                    new ReportDatasets.Restore(
                                                            draft.id(), draft.revision(), 1, "失效"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    assertThatThrownBy(
                                    () ->
                                            datasets.status(
                                                    new ReportDatasets.ChangeStatus(
                                                            draft.id(),
                                                            draft.revision(),
                                                            "INACTIVE",
                                                            "失效"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    assertThat(datasets.get(draft.id(), 10001).revision()).isEqualTo(2);
                });
    }

    @Test
    void restoringChangesDraftOnlyAndPublicationDoesNotReenableDisabledResource() {
        rollback(
                () -> {
                    ReportDatasets.Detail initial = create(source("restore"));
                    datasets.publish(publish(initial, "v1"), 10001);
                    ReportDatasets.Detail head = datasets.get(initial.id(), 10001);
                    head =
                            datasets.status(
                                    new ReportDatasets.ChangeStatus(
                                            head.id(), head.revision(), "INACTIVE", "暂停使用"),
                                    10001);
                    head =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            head.id(),
                                            head.revision(),
                                            "第二版",
                                            "",
                                            initial.draft().source()),
                                    10001);
                    datasets.publish(publish(head, "v2"), 10001);
                    head = datasets.get(head.id(), 10001);
                    assertThat(head.status()).isEqualTo("INACTIVE");
                    ReportDatasets.Detail restored =
                            datasets.restore(
                                    new ReportDatasets.Restore(
                                            head.id(), head.revision(), 1, "恢复第一版草稿"),
                                    10001);
                    assertThat(restored.publishedVersion()).isEqualTo(2);
                    assertThat(restored.status()).isEqualTo("INACTIVE");
                    assertThat(restored.modified()).isTrue();
                    assertThat(restored.draft()).isEqualTo(initial.draft());
                    assertThat(datasets.releases(head.id(), 1, 10, 10001).getList())
                            .extracting(ReportDatasets.Release::versionNo)
                            .containsExactly(2, 1);
                    ReportDatasets.Detail enabled =
                            datasets.status(
                                    new ReportDatasets.ChangeStatus(
                                            restored.id(),
                                            restored.revision(),
                                            "ACTIVE",
                                            "重新启用发布版本"),
                                    10001);
                    assertThat(enabled.publishedVersion()).isEqualTo(2);
                    assertThat(enabled.modified()).isTrue();
                });
    }

    @Test
    void keepsPublishedDependenciesWhenDraftChangesObjectsWithoutCreatingAnyGrant() {
        rollback(
                () -> {
                    ReportDatasets.Source first = source("first");
                    ReportDatasets.Source second = source("second");
                    ReportDatasets.Detail draft = create(first);
                    datasets.publish(publish(draft, "v1"), 10001);
                    ReportDatasets.Detail head = datasets.get(draft.id(), 10001);
                    datasets.save(
                            new ReportDatasets.Save(
                                    head.id(), head.revision(), head.draft().name(), "", second),
                            10001);
                    assertThat(centerMapper.dependencies(Long.parseLong(first.root().objectId())))
                            .filteredOn(dependency -> "DATASET".equals(dependency.sourceKind()))
                            .extracting(DataCenter.Dependency::sourceKey)
                            .containsExactly(head.id() + ":v1");
                    assertThat(centerMapper.dependencies(Long.parseLong(second.root().objectId())))
                            .filteredOn(dependency -> "DATASET".equals(dependency.sourceKind()))
                            .extracting(DataCenter.Dependency::sourceKey)
                            .containsExactly(head.id() + ":draft");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM"
                                                + " public.nocode_object_application_grant WHERE"
                                                + " object_id IN (?,?)",
                                            Long.class,
                                            Long.parseLong(first.root().objectId()),
                                            Long.parseLong(second.root().objectId())))
                            .isZero();
                });
    }

    @Test
    void invalidSourceCannotPartiallyReplaceDraftOrDependencies() {
        rollback(
                () -> {
                    ReportDatasets.Source good = source("valid");
                    ReportDatasets.Detail draft = create(good);
                    ReportDatasets.Source bad =
                            new ReportDatasets.Source(
                                    1,
                                    new ReportDatasets.ObjectReference(
                                            good.root().objectId(),
                                            good.root().versionNo(),
                                            "tampered"),
                                    good.relations(),
                                    good.fields());
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            draft.id(),
                                                            draft.revision(),
                                                            "不能保存",
                                                            "",
                                                            bad),
                                                    10001))
                            .hasMessageContaining("校验和");
                    assertThat(datasets.get(draft.id(), 10001)).isEqualTo(draft);
                    assertThat(centerMapper.dependencies(Long.parseLong(good.root().objectId())))
                            .anyMatch(
                                    dependency ->
                                            dependency.sourceKey().equals(draft.id() + ":draft"));
                });
    }

    @Test
    void concurrentEditorsCannotSilentlyOverwriteOneAnother() throws Exception {
        ReportDatasets.Detail draft = create(null);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (String name : List.of("并发一", "并发二"))
                attempts.add(
                        pool.submit(
                                () -> {
                                    start.await(5, TimeUnit.SECONDS);
                                    try {
                                        datasets.save(
                                                new ReportDatasets.Save(
                                                        draft.id(),
                                                        draft.revision(),
                                                        name,
                                                        "",
                                                        null),
                                                10001);
                                        return true;
                                    } catch (ServiceException error) {
                                        assertThat(error.getCode())
                                                .isEqualTo(NocodeErrorCodes.CONFLICT);
                                        return false;
                                    }
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    attempts.get(0).get(10, TimeUnit.SECONDS),
                                    attempts.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(datasets.get(draft.id(), 10001).revision()).isEqualTo(2);
    }

    @Test
    void failedPublicationRollsBackVersionPointerDependenciesAndAuditTogether() {
        ReportDatasets.Source source = source("rollback");
        ReportDatasets.Detail draft = create(source);
        writeFailure.failAfter("INSERT INTO public.nocode_report_operation_log");
        try {
            assertThatThrownBy(() -> datasets.publish(publish(draft, "retry_after_failure"), 10001))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(datasets.get(draft.id(), 10001)).isEqualTo(draft);
        assertThat(datasets.releases(draft.id(), 1, 10, 10001).getTotal()).isZero();
        assertThat(centerMapper.dependencies(Long.parseLong(source.root().objectId())))
                .filteredOn(dependency -> "DATASET".equals(dependency.sourceKind()))
                .extracting(DataCenter.Dependency::sourceKey)
                .containsExactly(draft.id() + ":draft");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_report_operation_log WHERE"
                                        + " resource_kind='DATASET' AND resource_id=?",
                                Long.class,
                                Long.parseLong(draft.id())))
                .isEqualTo(2);
        assertThat(datasets.publish(publish(draft, "retry_after_failure"), 10001).versionNo())
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicatePublishProducesOneVersionAndSameResult() throws Exception {
        ReportDatasets.Detail draft = create(source("concurrentpublish"));
        ReportDatasets.Publish request = publish(draft, "parallel_retry");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<ReportDatasets.Release> action =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        return datasets.publish(request, 10001);
                    };
            Future<ReportDatasets.Release> first = pool.submit(action);
            Future<ReportDatasets.Release> second = pool.submit(action);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(datasets.releases(draft.id(), 1, 10, 10001).getTotal()).isEqualTo(1);
        assertThat(datasets.get(draft.id(), 10001).revision()).isEqualTo(2);
    }

    @Test
    void copyingKeepsDefinitionButDoesNotCopyPublicationOrAnyAuthorization() {
        rollback(
                () -> {
                    ReportDatasets.Detail original = create(source("copy"));
                    datasets.publish(publish(original, "copy_source"), 10001);
                    original = datasets.get(original.id(), 10001);
                    ReportDatasets.Detail copy =
                            datasets.copy(
                                    new ReportDatasets.Copy(
                                            original.id(),
                                            original.revision(),
                                            fixture.prefix + "副本",
                                            "复制验证"),
                                    10001);
                    assertThat(copy.id()).isNotEqualTo(original.id());
                    assertThat(copy.draft().source()).isEqualTo(original.draft().source());
                    assertThat(copy.publishedVersion()).isNull();
                    assertThat(copy.status()).isEqualTo("INACTIVE");
                    assertThat(copy.revision()).isEqualTo(1);
                    com.lingan.ucp.nocode.report.dal.mapper.ReportAuthorizationMapper grants =
                            context.getBean(
                                    com.lingan.ucp.nocode.report.dal.mapper
                                            .ReportAuthorizationMapper.class);
                    assertThat(grants.ceilings(Long.parseLong(copy.id()))).isEmpty();
                    assertThat(grants.policy(Long.parseLong(copy.id()))).isNull();
                    assertThat(grants.acl("DATASET", Long.parseLong(copy.id()))).isNull();
                    assertThatThrownBy(
                                    () ->
                                            datasets.publish(
                                                    publish(copy, "copy_without_grant"), 10001))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("尚未授权");
                });
    }

    @Test
    void copyingRequiresUseInAdditionToMetadataAndRechecksRevision() {
        rollback(
                () -> {
                    ReportDatasets.Detail original = create(null);
                    com.lingan.ucp.nocode.report.service.authorization
                                    .ReportDatasetAuthorizationService
                            auth =
                                    context.getBean(
                                            com.lingan.ucp.nocode.report.service.authorization
                                                    .ReportDatasetAuthorizationService.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    original.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW_META"))),
                                    "可看不可复用"),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            datasets.copy(
                                                    new ReportDatasets.Copy(
                                                            original.id(),
                                                            original.revision(),
                                                            "副本",
                                                            "验证"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            datasets.copy(
                                                    new ReportDatasets.Copy(
                                                            original.id(),
                                                            original.revision() + 1,
                                                            "副本",
                                                            "验证"),
                                                    10001))
                            .isInstanceOf(ServiceException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    original.id(),
                                    1,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW_META", "USE"))),
                                    "允许复用"),
                            10001);
                    ReportDatasets.Detail otherOwner =
                            datasets.copy(
                                    new ReportDatasets.Copy(
                                            original.id(), original.revision(), "跨成员副本", "显式复用"),
                                    10002);
                    assertThat(otherOwner.ownerId()).isEqualTo("10002");
                    assertThat(otherOwner.status()).isEqualTo("INACTIVE");
                    assertThatThrownBy(() -> datasets.deletePreview(original.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            datasets.delete(
                                                    new ReportDatasets.Delete(
                                                            original.id(),
                                                            original.revision(),
                                                            "验证"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void deletionRechecksReferencesAfterPreviewAndRetainsHistory() {
        rollback(
                () -> {
                    ReportDatasets.Detail original = create(source("delete"));
                    datasets.publish(publish(original, "delete_source"), 10001);
                    ReportDatasets.Detail saved = datasets.get(original.id(), 10001);
                    assertThat(datasets.deletePreview(saved.id(), 10001).canDelete()).isTrue();
                    long id = Long.parseLong(saved.id());
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_report_dependency(source_kind,source_id,source_stage,source_version,target_kind,target_id,target_version)"
                                + " VALUES('DASHBOARD',?,'VERSION',9,'DATASET',?,1)",
                            id,
                            id);
                    assertThat(datasets.deletePreview(saved.id(), 10001).referenceCount())
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            datasets.delete(
                                                    new ReportDatasets.Delete(
                                                            saved.id(),
                                                            saved.revision(),
                                                            "旧预检不能绕过新引用"),
                                                    10001))
                            .hasMessageContaining("引用");
                    jdbc.update(
                            "DELETE FROM public.nocode_report_dependency WHERE"
                                    + " target_kind='DATASET' AND target_id=?",
                            id);
                    assertThatThrownBy(
                                    () ->
                                            datasets.delete(
                                                    new ReportDatasets.Delete(
                                                            saved.id(), 1, "过期修订"),
                                                    10001))
                            .hasMessageContaining("修改");
                    assertThat(
                                    datasets.delete(
                                                    new ReportDatasets.Delete(
                                                            saved.id(), saved.revision(), "解除后删除"),
                                                    10001)
                                            .deleted())
                            .isTrue();
                    assertThatThrownBy(() -> datasets.get(saved.id(), 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> datasets.releases(saved.id(), 1, 10, 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM"
                                                    + " public.nocode_report_dataset_version WHERE"
                                                    + " dataset_id=?",
                                            Long.class,
                                            id))
                            .isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_resource_dependency"
                                                + " WHERE source_kind='DATASET' AND source_key LIKE"
                                                + " ? AND deleted=0",
                                            Long.class,
                                            saved.id() + ":%"))
                            .isZero();
                    assertThat(objects.getVersion(saved.draft().source().root().objectId(), null))
                            .isNotNull();
                });
    }

    @Test
    void failedDeletionRollsBackTombstoneAndReleasedDependencies() {
        ReportDatasets.Detail original = create(source("delete_failure"));
        writeFailure.failAfter("INSERT INTO public.nocode_report_operation_log");
        try {
            assertThatThrownBy(
                            () ->
                                    datasets.delete(
                                            new ReportDatasets.Delete(
                                                    original.id(), original.revision(), "回滚验证"),
                                            10001))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(datasets.get(original.id(), 10001)).isEqualTo(original);
        assertThat(
                        centerMapper.dependencies(
                                Long.parseLong(original.draft().source().root().objectId())))
                .filteredOn(dependency -> "DATASET".equals(dependency.sourceKind()))
                .extracting(DataCenter.Dependency::sourceKey)
                .containsExactly(original.id() + ":draft");
    }

    private ReportDatasets.Detail create(ReportDatasets.Source source) {
        ReportDatasets.Detail draft =
                datasets.save(
                        new ReportDatasets.Save(null, 0, fixture.prefix + "数据集", "", source),
                        10001);
        ids.add(draft.id());
        if (source != null) {
            ApplicationAuthorization.ObjectGrant grant =
                    new ApplicationAuthorization.ObjectGrant(
                            source.root().objectId(),
                            Set.of("READ"),
                            "ALL",
                            source.fields().stream()
                                    .map(ReportDatasets.Field::sourceFieldId)
                                    .collect(java.util.stream.Collectors.toSet()),
                            Set.of(),
                            Set.of(),
                            Set.of());
            context.getBean(
                            com.lingan.ucp.nocode.report.service.authorization
                                    .ReportDatasetAuthorizationService.class)
                    .saveCeiling(
                            new ReportAuthorization.SaveCeiling(
                                    draft.id(), source.root().objectId(), 0, grant, "生命周期测试显式授权"),
                            10001);
        }
        return draft;
    }

    private ReportDatasets.Publish publish(ReportDatasets.Detail draft, String request) {
        return new ReportDatasets.Publish(draft.id(), draft.revision(), request, "测试发布");
    }

    private ReportDatasets.Source source(String suffix) {
        ObjectDraft draft = fixture.create(suffix);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "数据集生命周期测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published = objects.getVersion(draft.id(), null);
        return new ReportDatasets.Source(
                1,
                new ReportDatasets.ObjectReference(
                        draft.id(), published.versionNo(), published.checksum()),
                List.of(),
                List.of(
                        new ReportDatasets.Field(
                                "name",
                                List.of(),
                                draft.fields().getFirst().id(),
                                "名称",
                                "DIMENSION")));
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
