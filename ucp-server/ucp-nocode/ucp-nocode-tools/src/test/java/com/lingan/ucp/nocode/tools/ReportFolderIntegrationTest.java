package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dataset.*;
import com.lingan.ucp.nocode.report.service.folder.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

/** 真实开发库验证目录权限、版本隔离和并发；只清理本测试记录的精确 ID。 */
class ReportFolderIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext context;
    private ReportFolderService folders;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService auth;
    private final Set<String> folderIds = new HashSet<>(), datasetIds = new HashSet<>();

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
        folders = context.getBean(ReportFolderService.class);
        datasets = context.getBean(ReportDatasetService.class);
        auth = context.getBean(ReportDatasetAuthorizationService.class);
        ReportIntegrationSupport.activeUsers(10001, 10002);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        for (long actor : List.of(10001L, 10002L))
            for (String code : List.of("query", "create", "update", "publish", "manage"))
                when(permissions.hasAnyPermissions(actor, "nocode:report:" + code))
                        .thenReturn(true);
        when(permissions.hasAnyPermissions(10002L, "nocode:report:manage")).thenReturn(false);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        context.close();
        for (String id : datasetIds) {
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
        for (String id : folderIds)
            jdbc.update(
                    "UPDATE public.nocode_report_folder SET parent_id=NULL WHERE id=? AND name LIKE"
                            + " ?",
                    Long.parseLong(id),
                    fixture.prefix + "%");
        for (String id : folderIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_report_operation_log WHERE resource_kind='FOLDER'"
                            + " AND resource_id=?",
                    Long.parseLong(id));
            jdbc.update(
                    "DELETE FROM public.nocode_report_folder WHERE id=? AND name LIKE ?",
                    Long.parseLong(id),
                    fixture.prefix + "%");
        }
        fixture.clean();
    }

    @Test
    void rejectsCyclesDuplicateSiblingsStaleChangesAndExcessDepth() {
        rollback(
                () -> {
                    ReportFolders.Item root = folder(null, "root"),
                            child = folder(root.id(), "child");
                    assertThatThrownBy(() -> folder(null, "ROOT")).hasMessageContaining("已存在");
                    assertThatThrownBy(() -> folders.save(change(root, child.id()), 10001))
                            .hasMessageContaining("后代");
                    folders.save(change(child, null), 10001);
                    assertThatThrownBy(() -> folders.save(change(child, root.id()), 10001))
                            .hasMessageContaining("修改");
                    ReportFolders.Item level = root;
                    for (int n = 2; n <= 8; n++) level = folder(level.id(), "level" + n);
                    String deepest = level.id();
                    assertThatThrownBy(() -> folder(deepest, "overflow"))
                            .hasMessageContaining("8 层");
                });
    }

    // 将 actor 固定在测试辅助方法，避免并发用例意外使用其他身份。
    private ReportFolders.Save change(ReportFolders.Item item, String parent) {
        return new ReportFolders.Save(
                item.id(), item.revision(), parent, item.name(), item.sortNo(), "目录验收");
    }

    @Test
    void ordinaryUsersSeeOnlyAuthorizedResourcesAndAncestorNames() {
        rollback(
                () -> {
                    ReportFolders.Item root = folder(null, "root"),
                            child = folder(root.id(), "child"),
                            hidden = folder(null, "hidden");
                    ReportDatasets.Detail target = dataset(child.id()),
                            secret = dataset(hidden.id());
                    assertThat(folders.tree(10002))
                            .extracting(ReportFolders.Item::id)
                            .doesNotContain(root.id(), child.id(), hidden.id());
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    target.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW_META"))),
                                    "可见性测试"),
                            10001);
                    assertThat(folders.tree(10002))
                            .extracting(ReportFolders.Item::id)
                            .contains(root.id(), child.id())
                            .doesNotContain(hidden.id());
                    assertThat(datasets.page(1, 100, fixture.prefix, root.id(), 10002).getList())
                            .extracting(ReportDatasets.Detail::id)
                            .containsExactly(target.id());
                    assertThat(datasets.page(1, 100, fixture.prefix, hidden.id(), 10002).getList())
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            datasets.move(
                                                    new ReportDatasets.Move(
                                                            target.id(),
                                                            target.revision(),
                                                            null,
                                                            "越权移动"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            datasets.save(
                                                    new ReportDatasets.Save(
                                                            null,
                                                            0,
                                                            fixture.prefix + "denied",
                                                            "",
                                                            null,
                                                            null,
                                                            hidden.id()),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            folders.save(
                                                    new ReportFolders.Save(
                                                            null, 0, null, "越权目录", 0, "越权"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(target.id(), 1, List.of(), "撤销发现"),
                            10001);
                    assertThat(folders.tree(10002))
                            .extracting(ReportFolders.Item::id)
                            .doesNotContain(root.id(), child.id(), hidden.id());
                    // 全局分类管理员可以管理目录名称，仍不能读取其中未授权的数据集。
                    when(servicesContext
                                    .getBean(PermissionCommonApi.class)
                                    .hasAnyPermissions(10002L, "nocode:report:manage"))
                            .thenReturn(true);
                    assertThat(folders.tree(10002))
                            .extracting(ReportFolders.Item::id)
                            .contains(hidden.id());
                    assertThat(datasets.page(1, 100, fixture.prefix, hidden.id(), 10002).getList())
                            .isEmpty();
                    assertThatThrownBy(() -> datasets.get(secret.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void movingPreservesContentPoliciesAndCopyLocationAndProtectsNonemptyFolders() {
        rollback(
                () -> {
                    ReportFolders.Item root = folder(null, "root"),
                            child = folder(root.id(), "child");
                    ReportDatasets.Detail draft = dataset(null);
                    ReportDatasets.Detail moved =
                            datasets.move(
                                    new ReportDatasets.Move(
                                            draft.id(), draft.revision(), child.id(), "分类"),
                                    10001);
                    assertThat(moved.checksum()).isEqualTo(draft.checksum());
                    assertThat(moved.draft()).isEqualTo(draft.draft());
                    assertThat(moved.folderId()).isEqualTo(child.id());
                    assertThat(auth.ceilings(draft.id(), 10001)).isEmpty();
                    assertThat(auth.resourcePolicy(draft.id(), 10001).members()).isEmpty();
                    assertThat(datasets.page(1, 100, fixture.prefix, root.id(), 10001).getList())
                            .extracting(ReportDatasets.Detail::id)
                            .contains(draft.id());
                    assertThat(datasets.page(1, 100, fixture.prefix, "0", 10001).getList())
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            folders.delete(
                                                    new ReportFolders.Delete(
                                                            child.id(), child.revision(), "非空"),
                                                    10001))
                            .hasMessageContaining("非空");
                    assertThatThrownBy(
                                    () ->
                                            folders.delete(
                                                    new ReportFolders.Delete(
                                                            root.id(), root.revision(), "非空"),
                                                    10001))
                            .hasMessageContaining("非空");
                    assertThatThrownBy(
                                    () ->
                                            datasets.move(
                                                    new ReportDatasets.Move(
                                                            draft.id(),
                                                            draft.revision(),
                                                            null,
                                                            "旧修订"),
                                                    10001))
                            .hasMessageContaining("修改");
                    ReportDatasets.Detail copied =
                            datasets.copy(
                                    new ReportDatasets.Copy(
                                            moved.id(),
                                            moved.revision(),
                                            fixture.prefix + "copy",
                                            "复制"),
                                    10001);
                    datasetIds.add(copied.id());
                    assertThat(copied.folderId()).isEqualTo(child.id());
                    ReportDatasets.Detail saved =
                            datasets.save(
                                    new ReportDatasets.Save(
                                            moved.id(),
                                            moved.revision(),
                                            moved.draft().name(),
                                            "新说明",
                                            null),
                                    10001);
                    assertThat(saved.folderId()).isEqualTo(child.id());
                    datasets.move(
                            new ReportDatasets.Move(saved.id(), saved.revision(), null, "移出"),
                            10001);
                    datasets.delete(
                            new ReportDatasets.Delete(copied.id(), copied.revision(), "清空目录"),
                            10001);
                    folders.delete(
                            new ReportFolders.Delete(child.id(), child.revision(), "删除空目录"), 10001);
                    assertThatThrownBy(
                                    () ->
                                            datasets.move(
                                                    new ReportDatasets.Move(
                                                            draft.id(),
                                                            saved.revision() + 1,
                                                            child.id(),
                                                            "过期目标"),
                                                    10001))
                            .hasMessageContaining("不存在");
                    folders.delete(
                            new ReportFolders.Delete(root.id(), root.revision(), "删除根目录"), 10001);
                });
    }

    @Test
    void auditFailureRollsBackFolderAndDatasetMovement() {
        ReportFolders.Item root = folder(null, "root");
        ReportDatasets.Detail draft = dataset(null);
        writeFailure.failAfter("INSERT INTO public.nocode_report_operation_log");
        try {
            assertThatThrownBy(
                            () ->
                                    datasets.move(
                                            new ReportDatasets.Move(
                                                    draft.id(),
                                                    draft.revision(),
                                                    root.id(),
                                                    "故障测试"),
                                            10001))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(datasets.get(draft.id(), 10001)).isEqualTo(draft);
        writeFailure.failAfter("INSERT INTO public.nocode_report_operation_log");
        try {
            assertThatThrownBy(
                            () ->
                                    folders.save(
                                            new ReportFolders.Save(
                                                    root.id(),
                                                    root.revision(),
                                                    null,
                                                    fixture.prefix + "renamed",
                                                    0,
                                                    "故障测试"),
                                            10001))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(folders.tree(10001)).contains(root);
    }

    @Test
    void concurrentOppositeMovesCannotCreateCycle() throws Exception {
        ReportFolders.Item left = folder(null, "left"), right = folder(null, "right");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> attempt(start, left, right));
            Future<Boolean> second = pool.submit(() -> attempt(start, right, left));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingFolderRacingWithDatasetMoveCannotLeaveDanglingClassification() throws Exception {
        ReportFolders.Item folder = folder(null, "race");
        ReportDatasets.Detail dataset = dataset(null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> deletion =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    folders.delete(
                                            new ReportFolders.Delete(
                                                    folder.id(), folder.revision(), "并发删除"),
                                            10001);
                                    return true;
                                } catch (
                                        com.lingan.ucp.framework.common.exception.ServiceException
                                                exception) {
                                    assertThat(exception.getMessage()).contains("非空");
                                    return false;
                                }
                            });
            Future<Boolean> movement =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    datasets.move(
                                            new ReportDatasets.Move(
                                                    dataset.id(),
                                                    dataset.revision(),
                                                    folder.id(),
                                                    "并发移动"),
                                            10001);
                                    return true;
                                } catch (AccessDeniedException exception) {
                                    assertThat(exception.getMessage()).contains("不存在");
                                    return false;
                                }
                            });
            start.countDown();
            boolean deleted = deletion.get(15, TimeUnit.SECONDS);
            boolean moved = movement.get(15, TimeUnit.SECONDS);
            assertThat(deleted).isNotEqualTo(moved);
            ReportDatasets.Detail actual = datasets.get(dataset.id(), 10001);
            assertThat(actual.folderId()).isEqualTo(moved ? folder.id() : null);
            assertThat(folders.tree(10001).stream().anyMatch(item -> item.id().equals(folder.id())))
                    .isEqualTo(moved);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean attempt(
            CountDownLatch start, ReportFolders.Item source, ReportFolders.Item target)
            throws Exception {
        start.await();
        try {
            folders.save(change(source, target.id()), 10001);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException exception) {
            assertThat(exception.getMessage()).contains("后代");
            return false;
        }
    }

    private ReportFolders.Item folder(String parent, String name) {
        ReportFolders.Item result =
                folders.save(
                        new ReportFolders.Save(null, 0, parent, fixture.prefix + name, 0, "目录验收"),
                        10001);
        folderIds.add(result.id());
        return result;
    }

    private ReportDatasets.Detail dataset(String folder) {
        ReportDatasets.Detail result =
                datasets.save(
                        new ReportDatasets.Save(
                                null, 0, fixture.prefix + "数据集", "", null, null, folder),
                        10001);
        datasetIds.add(result.id());
        return result;
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
