package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ErrorCode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderNode;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderScope;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.lingan.ucp.module.drive.service.permission.DriveScopes;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 限定子树：凭一条记录换来的临时角色只在那条记录的文件夹里有效。真实网盘实现 + 测试库。
 *
 * <p>夹具（每条用例新建）：团队空间 T 里 {@code /对象夹/记录甲/{a.txt, 子/d.txt}}、{@code /对象夹/记录乙/b.txt}、{@code
 * /别处/c.txt}；另一个空间 T2 里 {@code /外面}。用户 U、V 在两个空间上都没有任何网盘权限。本组钉的是「子树」：凡要改删的节点都预先打上来源键 K1， 调用也用
 * K1（来源规则由 {@link DriveFolderOriginIntegrationTest} 钉）。
 */
class DriveFolderScopeIntegrationTest {
    private static final long OWNER = 910001L;
    private static final long U = 910002L;
    private static final long V = 910003L;
    private static final String K1 = "object-1:record-1";

    private static ConfigurableApplicationContext tool;
    private static DriveFolderTestBed bed;

    private long t;
    private long t2;
    private long objectDir;
    private long recordA;
    private long recordB;
    private long elsewhere;
    private long outside;
    private long a;
    private long sub;
    private long d;
    private long b;
    private long c;

    @BeforeAll
    static void open() {
        tool = NocodeToolContext.open();
        bed = DriveFolderTestBed.open(tool);
    }

    @AfterAll
    static void close() {
        if (bed != null) bed.close();
        if (tool != null) tool.close();
    }

    @BeforeEach
    void fixture(TestInfo info) {
        String tag = info.getTestMethod().orElseThrow().getName();
        t = bed.space("T-" + tag, "TEAM", OWNER);
        t2 = bed.space("T2-" + tag, "TEAM", OWNER);
        objectDir = bed.folder(t, 0L, "对象夹", OWNER);
        recordA = bed.folder(t, objectDir, "记录甲", OWNER);
        recordB = bed.folder(t, objectDir, "记录乙", OWNER);
        elsewhere = bed.folder(t, 0L, "别处", OWNER);
        outside = bed.folder(t2, 0L, "外面", OWNER);
        a = bed.file(t, recordA, "a.txt", OWNER);
        sub = bed.folder(t, recordA, "子", OWNER);
        d = bed.file(t, sub, "d.txt", OWNER);
        b = bed.file(t, recordB, "b.txt", OWNER);
        c = bed.file(t, elsewhere, "c.txt", OWNER);
        bed.mark(K1, a, sub, d, b);
    }

    private DriveFolderScope editor() {
        return new DriveFolderScope(t, recordA, "EDITOR", K1);
    }

    private DriveFolderScope viewer() {
        return new DriveFolderScope(t, recordA, "VIEWER", K1);
    }

    private static void rejected(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(code.getCode()));
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void d1ListsRootWithoutRevealingItsId() {
        List<DriveFolderNode> nodes = bed.folders.list(editor(), U, 0L);

        assertThat(nodes).extracting(DriveFolderNode::name).containsExactly("子", "a.txt");
        assertThat(nodes).extracting(DriveFolderNode::parentId).containsOnly(0L);
        assertThat(nodes).extracting(DriveFolderNode::id).doesNotContain(recordA);
        // 详情、下一层同样不带出根的编号：根的直接子节点的父编号是 0，再下一层的父编号是真实的子目录编号。
        assertThat(bed.folders.get(editor(), U, a).parentId()).isZero();
        assertThat(bed.folders.list(editor(), U, sub))
                .extracting(DriveFolderNode::parentId)
                .containsOnly(sub);
        assertThat(bed.folders.path(editor(), U, a)).isEmpty();
        assertThat(bed.folders.path(editor(), U, d)).containsExactly("子");
    }

    @Test
    void d2EditorWritesInsideSubtree() {
        long before = bed.usedBytes(t);

        DriveFolderNode uploaded =
                bed.folders.upload(editor(), U, 0L, "新.txt", "text/plain", 6, bytes("123456"));
        assertThat(uploaded.parentId()).isZero();
        assertThat(bed.entry(uploaded.id()).getParentId()).isEqualTo(recordA);
        assertThat(bed.usedBytes(t)).isEqualTo(before + 6);

        Long created = bed.folders.createFolder(editor(), U, sub, "孙");
        assertThat(bed.entry(created).getParentId()).isEqualTo(sub);

        bed.folders.rename(editor(), U, a, "改名.txt");
        assertThat(bed.entry(a).getName()).isEqualTo("改名.txt");

        bed.folders.move(editor(), U, a, sub);
        assertThat(bed.entry(a).getParentId()).isEqualTo(sub);

        Long copy = bed.folders.copy(editor(), U, a, 0L);
        assertThat(bed.entry(copy).getParentId()).isEqualTo(recordA);

        long beforeTrash = bed.usedBytes(t);
        bed.folders.trash(editor(), U, List.of(a));
        assertThat(bed.entry(a).getTrashState()).isEqualTo("TRASHED");
        assertThat(bed.usedBytes(t)).isEqualTo(beforeTrash - bed.entry(a).getSize());
    }

    @Test
    void d3CannotListOutsideSubtree() {
        rejected(PERMISSION_DENIED, () -> bed.folders.list(editor(), U, recordB));
        rejected(PERMISSION_DENIED, () -> bed.folders.list(editor(), U, objectDir));
        rejected(PERMISSION_DENIED, () -> bed.folders.list(editor(), U, outside));
    }

    /** 限定子树里空间根没有角色：否则回收站操作会把持有临时角色的人当成空间管理者。 */
    @Test
    void d3SpaceRootHasNoRoleInsideScope() {
        DriveScopes.Scope scope = new DriveScopes.Scope(t, recordA, DrivePermissionRoleEnum.EDITOR);

        assertThat(DriveScopes.call(scope, () -> bed.permissionService.getEffectiveRole(t, 0L, U)))
                .isNull();
        DriveEntryListReqVO root = new DriveEntryListReqVO();
        root.setSpaceId(t);
        root.setParentId(0L);
        rejected(
                PERMISSION_DENIED,
                () -> DriveScopes.call(scope, () -> bed.entryService.getEntryList(root, U)));
    }

    @Test
    void d4CannotReadContentOutsideSubtree() {
        rejected(PERMISSION_DENIED, () -> bed.folders.contentInfo(editor(), U, b));
        rejected(PERMISSION_DENIED, () -> bed.folders.openContent(editor(), U, b, 0));
        rejected(PERMISSION_DENIED, () -> bed.folders.contentInfo(editor(), U, c));
        rejected(PERMISSION_DENIED, () -> bed.folders.openContent(editor(), U, c, 0));
        rejected(PERMISSION_DENIED, () -> bed.folders.get(editor(), U, b));
        rejected(PERMISSION_DENIED, () -> bed.folders.path(editor(), U, c));
    }

    @Test
    void d5CannotMoveAcrossSubtreeBoundary() {
        rejected(PERMISSION_DENIED, () -> bed.folders.move(editor(), U, a, recordB));
        rejected(PERMISSION_DENIED, () -> bed.folders.move(editor(), U, a, objectDir));
        rejected(PERMISSION_DENIED, () -> bed.folders.move(editor(), U, b, 0L));
        rejected(PERMISSION_DENIED, () -> bed.folders.copy(editor(), U, a, recordB));
        rejected(PERMISSION_DENIED, () -> bed.folders.copy(editor(), U, b, 0L));
        rejected(PERMISSION_DENIED, () -> bed.folders.trash(editor(), U, List.of(b)));
        rejected(PERMISSION_DENIED, () -> bed.folders.rename(editor(), U, b, "x.txt"));

        assertThat(bed.entry(a).getParentId()).isEqualTo(recordA);
        assertThat(bed.entry(b).getParentId()).isEqualTo(recordB);
        assertThat(bed.entry(b).getTrashState()).isEqualTo("NORMAL");
    }

    @Test
    void d6RootItselfCannotBeChanged() {
        for (Long root : List.of(0L, recordA)) {
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.rename(editor(), U, root, "新名"));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.move(editor(), U, root, sub));
            rejected(
                    ENTRY_SCOPE_ROOT_FORBIDDEN,
                    () -> bed.folders.trash(editor(), U, List.of(root)));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.copy(editor(), U, root, sub));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.get(editor(), U, root));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.path(editor(), U, root));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.restore(editor(), U, root));
            rejected(ENTRY_SCOPE_ROOT_FORBIDDEN, () -> bed.folders.contentInfo(editor(), U, root));
        }
        assertThat(bed.entry(recordA).getName()).isEqualTo("记录甲");
        assertThat(bed.entry(recordA).getParentId()).isEqualTo(objectDir);
        assertThat(bed.entry(recordA).getTrashState()).isEqualTo("NORMAL");
    }

    @Test
    void d7ViewerReads() throws Exception {
        assertThat(bed.folders.list(viewer(), U, 0L)).hasSize(2);
        assertThat(bed.folders.contentInfo(viewer(), U, a).name()).isEqualTo("a.txt");
        try (InputStream stream = bed.folders.openContent(viewer(), U, a, 0)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("a.txt");
        }
        try (InputStream stream = bed.folders.openContent(viewer(), U, a, 2)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("txt");
        }
    }

    @Test
    void d8ViewerCannotWrite() {
        rejected(
                PERMISSION_DENIED,
                () -> bed.folders.upload(viewer(), U, 0L, "x.txt", "text/plain", 1, bytes("x")));
        rejected(PERMISSION_DENIED, () -> bed.folders.createFolder(viewer(), U, 0L, "新夹"));
        rejected(PERMISSION_DENIED, () -> bed.folders.rename(viewer(), U, a, "x.txt"));
        rejected(PERMISSION_DENIED, () -> bed.folders.move(viewer(), U, a, sub));
        rejected(PERMISSION_DENIED, () -> bed.folders.trash(viewer(), U, List.of(a)));

        assertThat(bed.folders.list(viewer(), U, 0L))
                .extracting(DriveFolderNode::name)
                .containsExactly("子", "a.txt");
    }

    @Test
    void d9ViewerScopeTakesStrongerOwnDriveRole() {
        bed.grant(t, recordA, U, "EDITOR");

        DriveFolderNode uploaded =
                bed.folders.upload(viewer(), U, 0L, "x.txt", "text/plain", 1, bytes("x"));

        assertThat(bed.entry(uploaded.id()).getParentId()).isEqualTo(recordA);
        assertThat(bed.originOf(uploaded.id())).isEqualTo(K1);
    }

    @Test
    void d10SearchStaysInsideSubtree() {
        assertThat(bed.folders.search(editor(), U, "txt", null))
                .extracting(DriveFolderNode::name)
                .containsExactlyInAnyOrder("a.txt", "d.txt");
        // 根自身不在结果里；根的直接子节点的父编号是 0。
        assertThat(bed.folders.search(editor(), U, "记录甲", null)).isEmpty();
        assertThat(bed.folders.search(editor(), U, "a.txt", 10))
                .extracting(DriveFolderNode::parentId)
                .containsExactly(0L);
    }

    @Test
    void d11TrashListShowsOnlyOwnDeletions() {
        bed.folders.trash(editor(), U, List.of(a));
        bed.folders.trash(editor(), V, List.of(d));

        assertThat(bed.folders.trashList(editor(), U))
                .extracting(DriveFolderNode::name)
                .containsExactly("a.txt");
        assertThat(bed.folders.trashList(editor(), V))
                .extracting(DriveFolderNode::name)
                .containsExactly("d.txt");
        assertThat(bed.folders.trashList(editor(), U).getFirst().trashedBy())
                .isEqualTo(Long.toString(U));
        // 别人删的不能由我恢复：限定子树里空间根没有角色，回收站只认本人删的。
        rejected(PERMISSION_DENIED, () -> bed.folders.restore(editor(), U, d));
    }

    @Test
    void d12RestoreReturnsToOriginalPlaceOrRefuses() {
        bed.folders.trash(editor(), U, List.of(a));
        DriveFolderNode restored = bed.folders.restore(editor(), U, a);
        assertThat(restored.parentId()).isZero();
        assertThat(bed.entry(a).getParentId()).isEqualTo(recordA);
        assertThat(bed.entry(a).getTrashState()).isEqualTo("NORMAL");

        bed.folders.trash(editor(), U, List.of(d));
        bed.folders.trash(editor(), U, List.of(sub));
        rejected(ENTRY_RESTORE_PARENT_GONE, () -> bed.folders.restore(editor(), U, d));
        assertThat(bed.entry(d).getTrashState()).isEqualTo("TRASHED");
        assertThat(bed.entry(d).getParentId()).isEqualTo(sub);
    }

    @Test
    void d13ScopeDoesNotLeakAfterFailure() {
        rejected(PERMISSION_DENIED, () -> bed.folders.list(editor(), U, recordB));

        assertThat(DriveScopes.current()).isNull();
        assertThat(bed.permissionService.getEffectiveRole(t, recordA, U)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(t, a, U)).isNull();

        // 调用体自己抛出的异常同样不留下限定子树。
        DriveScopes.Scope scope = new DriveScopes.Scope(t, recordA, DrivePermissionRoleEnum.EDITOR);
        assertThatThrownBy(
                        () ->
                                DriveScopes.run(
                                        scope,
                                        () -> {
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DriveScopes.current()).isNull();
        assertThat(bed.permissionService.getEffectiveRole(t, a, U)).isNull();
    }

    @Test
    void d14UnavailableRootIsRefused() {
        // 根在回收站
        bed.entryService.trashEntryList(List.of(recordA), OWNER);
        rejected(ENTRY_SCOPE_UNAVAILABLE, () -> bed.folders.list(editor(), U, 0L));
        bed.entryService.restoreEntry(recordA, OWNER);
        assertThat(bed.folders.list(editor(), U, 0L)).isNotEmpty();

        // 根是受管目录
        long biz = bed.space("B-d14", "BIZ", null);
        Long managed = bed.bizFiles.createManagedDirectory(biz, 0L, "合同", OWNER);
        rejected(
                ENTRY_SCOPE_UNAVAILABLE,
                () -> bed.folders.list(new DriveFolderScope(biz, managed, "EDITOR", K1), U, 0L));

        // 根是文件、根不属于这个空间、根不存在
        rejected(
                ENTRY_SCOPE_UNAVAILABLE,
                () -> bed.folders.list(new DriveFolderScope(t, a, "EDITOR", K1), U, 0L));
        rejected(
                ENTRY_SCOPE_UNAVAILABLE,
                () -> bed.folders.list(new DriveFolderScope(t2, recordA, "EDITOR", K1), U, 0L));
        rejected(
                ENTRY_SCOPE_UNAVAILABLE,
                () -> bed.folders.list(new DriveFolderScope(t, -1L, "EDITOR", K1), U, 0L));

        // 空间停用
        bed.jdbc.update("UPDATE public.drive_space SET status=1 WHERE id=?", t);
        rejected(ENTRY_SCOPE_UNAVAILABLE, () -> bed.folders.list(editor(), U, 0L));

        // 角色只能是可查看或可编辑
        assertThatThrownBy(
                        () ->
                                bed.folders.list(
                                        new DriveFolderScope(t2, outside, "MANAGER", K1), U, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void d15EnsureChildNumbersDuplicatesAndMarksOrigin() {
        Long first = bed.folders.ensureChild(recordA, "某某工程", U, K1);
        Long second = bed.folders.ensureChild(recordA, "某某工程", U, "object-1:record-2");

        assertThat(bed.entry(first).getName()).isEqualTo("某某工程");
        assertThat(bed.entry(second).getName()).isEqualTo("某某工程 (2)");
        assertThat(bed.entry(first).getParentId()).isEqualTo(recordA);
        assertThat(bed.entry(first).getManagedBiz()).isFalse();
        assertThat(bed.entry(first).getInheritParent()).isTrue();
        assertThat(bed.entry(first).getCreator()).isEqualTo(Long.toString(U));
        assertThat(bed.originOf(first)).isEqualTo(K1);
        assertThat(bed.originOf(second)).isEqualTo("object-1:record-2");

        long biz = bed.space("B-d15", "BIZ", null);
        Long managed = bed.bizFiles.createManagedDirectory(biz, 0L, "合同", OWNER);
        rejected(ENTRY_SCOPE_UNAVAILABLE, () -> bed.folders.ensureChild(managed, "x", U, K1));
        rejected(ENTRY_SCOPE_UNAVAILABLE, () -> bed.folders.ensureChild(a, "x", U, K1));
        rejected(ENTRY_NAME_INVALID, () -> bed.folders.ensureChild(recordA, "a/b", U, K1));

        bed.entryService.trashEntryList(List.of(sub), OWNER);
        rejected(ENTRY_SCOPE_UNAVAILABLE, () -> bed.folders.ensureChild(sub, "x", U, K1));
    }

    /** 清点项：子树判断按父子链走，回收站里的节点仍算在子树里（恢复、最近删除依赖这一点）。 */
    @Test
    void subtreeMembershipIgnoresTrashState() {
        assertThat(bed.entries.selectIsInSubtree(d, recordA)).isTrue();
        assertThat(bed.entries.selectIsInSubtree(recordA, recordA)).isTrue();
        assertThat(bed.entries.selectIsInSubtree(recordA, d)).isFalse();
        assertThat(bed.entries.selectIsInSubtree(b, recordA)).isFalse();

        bed.entryService.trashEntryList(List.of(d), OWNER);

        assertThat(bed.entries.selectIsInSubtree(d, recordA)).isTrue();
    }

    @Test
    void describeRoleAndDisplayPath() {
        var info = bed.folders.describe(sub);
        assertThat(info.folder()).isTrue();
        assertThat(info.managed()).isFalse();
        assertThat(info.trashed()).isFalse();
        assertThat(info.spaceType()).isEqualTo("TEAM");
        assertThat(info.spaceEnabled()).isTrue();
        assertThat(bed.folders.describe(-1L)).isNull();

        assertThat(bed.folders.roleOf(t, sub, OWNER)).isEqualTo("MANAGER");
        assertThat(bed.folders.roleOf(t, sub, U)).isNull();

        assertThat(bed.folders.displayPath(sub)).isEqualTo(info.spaceName() + " / 对象夹 / 记录甲 / 子");
        assertThat(bed.folders.displayPath(-1L)).isNull();
    }
}
