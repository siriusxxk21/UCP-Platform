package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ErrorCode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderNode;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderScope;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryRenameReqVO;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 来源规则：凭记录的可编辑角色，只能改名、移动、删除、恢复经由这条记录放进去的节点。真实网盘实现 + 测试库。
 *
 * <p>夹具（每条用例新建）：团队空间 T，根 {@code /共用}；用户 U、V 在 T 上没有网盘权限，W 在 {@code /共用} 上有网盘授权「可编辑」；来源键 K1、K2。先由 U
 * 以 {@code Scope(T, 共用, EDITOR, K1)} 上传 {@code k1.txt}、新建 {@code k1夹/} 并在里面上传 {@code
 * in.txt}；再由空间归属人走 <b>普通网盘接口</b>在根上传 {@code plain.txt}。
 */
class DriveFolderOriginIntegrationTest {
    private static final long OWNER = 920001L;
    private static final long U = 920002L;
    private static final long V = 920003L;
    private static final long W = 920004L;
    private static final String K1 = "object-9:record-1";
    private static final String K2 = "object-9:record-2";

    private static ConfigurableApplicationContext tool;
    private static DriveFolderTestBed bed;

    private long t;
    private long shared;
    private long k1File;
    private long k1Folder;
    private long inFile;
    private long plain;

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
        t = bed.space("T-" + info.getTestMethod().orElseThrow().getName(), "TEAM", OWNER);
        shared = bed.folder(t, 0L, "共用", OWNER);
        bed.grant(t, shared, W, "EDITOR");
        k1File = upload(scope("EDITOR", K1), U, 0L, "k1.txt").id();
        k1Folder = bed.folders.createFolder(scope("EDITOR", K1), U, 0L, "k1夹");
        inFile = upload(scope("EDITOR", K1), U, k1Folder, "in.txt").id();
        plain = bed.file(t, shared, "plain.txt", OWNER);
    }

    private DriveFolderScope scope(String role, String key) {
        return new DriveFolderScope(t, shared, role, key);
    }

    private DriveFolderNode upload(DriveFolderScope scope, long user, long parent, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return bed.folders.upload(
                scope, user, parent, name, "text/plain", bytes.length, bytes(bytes));
    }

    private static InputStream bytes(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static void rejected(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(code.getCode()));
    }

    private Map<String, Boolean> modifiable(List<DriveFolderNode> nodes) {
        return nodes.stream()
                .collect(Collectors.toMap(DriveFolderNode::name, DriveFolderNode::modifiable));
    }

    private void adminRename(long id, String name) {
        DriveEntryRenameReqVO request = new DriveEntryRenameReqVO();
        request.setId(id);
        request.setName(name);
        bed.entryService.renameEntry(request, OWNER);
    }

    @Test
    void fixtureMarksWhatWasPutThroughTheScope() {
        assertThat(bed.originOf(k1File)).isEqualTo(K1);
        assertThat(bed.originOf(k1Folder)).isEqualTo(K1);
        assertThat(bed.originOf(inFile)).isEqualTo(K1);
        assertThat(bed.originOf(plain)).isNull();
    }

    @Test
    void d16OtherRecordCannotChangeButCanReadAndCopy() throws Exception {
        DriveFolderScope v = scope("EDITOR", K2);

        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.rename(v, V, k1File, "x.txt"));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.move(v, V, k1File, k1Folder));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.trash(v, V, List.of(k1File)));
        assertThat(bed.entry(k1File).getName()).isEqualTo("k1.txt");
        assertThat(bed.entry(k1File).getParentId()).isEqualTo(shared);
        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("NORMAL");

        try (InputStream stream = bed.folders.openContent(v, V, k1File, 0)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("k1.txt");
        }

        Long copy = bed.folders.copy(v, V, k1File, 0L);
        assertThat(bed.originOf(copy)).isEqualTo(K2);
        bed.folders.rename(v, V, copy, "副本.txt");
        assertThat(bed.entry(copy).getName()).isEqualTo("副本.txt");
        bed.folders.trash(v, V, List.of(copy));
        assertThat(bed.entry(copy).getTrashState()).isEqualTo("TRASHED");
    }

    /** 复制文件夹：复制出来的整棵新子树都算复制者这条记录的，不只是新的根。 */
    @Test
    void d16CopiedFolderIsOwnedAsAWhole() {
        DriveFolderScope v = scope("EDITOR", K2);

        Long copy = bed.folders.copy(v, V, k1Folder, 0L);

        List<Long> children =
                bed.jdbc.queryForList(
                        "SELECT id FROM public.drive_entry WHERE parent_id=?", Long.class, copy);
        assertThat(children).hasSize(1);
        assertThat(bed.originOf(copy)).isEqualTo(K2);
        assertThat(bed.originOf(children.getFirst())).isEqualTo(K2);
        bed.folders.trash(v, V, List.of(copy));
        assertThat(bed.entry(copy).getTrashState()).isEqualTo("TRASHED");
    }

    /** V 往 U 建的文件夹里放东西：返回 [v.txt, v夹]。 */
    private long[] putIntoForeignFolder() {
        DriveFolderScope v = scope("EDITOR", K2);
        long file = upload(v, V, k1Folder, "v.txt").id();
        long folder = bed.folders.createFolder(v, V, k1Folder, "v夹");
        return new long[] {file, folder};
    }

    @Test
    void d17AnyoneCanAddIntoForeignFolderAndOwnsWhatTheyAdded() {
        long[] added = putIntoForeignFolder();
        long file = added[0];
        long folder = added[1];

        assertThat(bed.originOf(file)).isEqualTo(K2);
        assertThat(bed.originOf(folder)).isEqualTo(K2);
        assertThat(bed.entry(file).getParentId()).isEqualTo(k1Folder);

        DriveFolderScope u = scope("EDITOR", K1);
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.rename(u, U, file, "x.txt"));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.trash(u, U, List.of(file)));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.rename(u, U, folder, "x夹"));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.trash(u, U, List.of(folder)));

        DriveFolderScope v = scope("EDITOR", K2);
        bed.folders.rename(v, V, file, "v2.txt");
        assertThat(bed.entry(file).getName()).isEqualTo("v2.txt");
        bed.folders.trash(v, V, List.of(file, folder));
        assertThat(bed.entry(file).getTrashState()).isEqualTo("TRASHED");
        assertThat(bed.entry(folder).getTrashState()).isEqualTo("TRASHED");
    }

    @Test
    void d18OwnFolderWithForeignContentCannotBeMovedOrDeleted() {
        putIntoForeignFolder();
        DriveFolderScope u = scope("EDITOR", K1);
        Long target = bed.folders.createFolder(u, U, 0L, "u夹");

        rejected(ENTRY_SCOPE_FOLDER_MIXED, () -> bed.folders.trash(u, U, List.of(k1Folder)));
        rejected(ENTRY_SCOPE_FOLDER_MIXED, () -> bed.folders.move(u, U, k1Folder, target));
        assertThat(bed.entry(k1Folder).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(k1Folder).getParentId()).isEqualTo(shared);

        bed.folders.rename(u, U, k1Folder, "k1夹改名");
        assertThat(bed.entry(k1Folder).getName()).isEqualTo("k1夹改名");
    }

    @Test
    void d19NodesWithoutOriginAreReadOnly() {
        DriveFolderScope u = scope("EDITOR", K1);

        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.rename(u, U, plain, "x.txt"));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.trash(u, U, List.of(plain)));
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.move(u, U, plain, k1Folder));
        assertThat(bed.entry(plain).getName()).isEqualTo("plain.txt");
        assertThat(bed.entry(plain).getTrashState()).isEqualTo("NORMAL");

        assertThat(modifiable(bed.folders.list(u, U, 0L)))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plain.txt", false, "k1.txt", true, "k1夹", true));
        assertThat(bed.folders.get(u, U, plain).modifiable()).isFalse();
        assertThat(bed.folders.get(u, U, k1File).modifiable()).isTrue();
        assertThat(modifiable(bed.folders.search(u, U, "txt", null)))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plain.txt", false, "k1.txt", true, "in.txt", true));
        // 换一条记录看同一个文件夹：别的记录放的都不能改；只读进来的一律不能改。
        assertThat(modifiable(bed.folders.list(scope("EDITOR", K2), V, 0L)))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plain.txt", false, "k1.txt", false, "k1夹", false));
        assertThat(modifiable(bed.folders.list(scope("VIEWER", K1), U, 0L)))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plain.txt", false, "k1.txt", false, "k1夹", false));
    }

    @Test
    void d20OwnDriveEditorIsNotRestrictedWithEditorScope() {
        ownDriveEditorIsNotRestricted(scope("EDITOR", K2));
    }

    @Test
    void d20OwnDriveEditorIsNotRestrictedWithViewerScope() {
        ownDriveEditorIsNotRestricted(scope("VIEWER", K2));
    }

    private void ownDriveEditorIsNotRestricted(DriveFolderScope w) {
        assertThat(modifiable(bed.folders.list(w, W, 0L)))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plain.txt", true, "k1.txt", true, "k1夹", true));
        assertThat(bed.folders.get(w, W, plain).modifiable()).isTrue();

        bed.folders.rename(w, W, plain, "plain2.txt");
        assertThat(bed.entry(plain).getName()).isEqualTo("plain2.txt");
        bed.folders.trash(w, W, List.of(k1File));
        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("TRASHED");
    }

    /** 只读进来、本人在网盘里也没有权限：按网盘的无权限拒绝，不走来源检查（否则会报成「不是经由这条记录放进去的」）。 */
    @Test
    void d20ViewerScopeWithoutOwnRoleIsDeniedByDriveNotByOrigin() {
        DriveFolderScope viewer = scope("VIEWER", K1);

        rejected(PERMISSION_DENIED, () -> bed.folders.rename(viewer, U, plain, "x.txt"));
        rejected(PERMISSION_DENIED, () -> bed.folders.trash(viewer, U, List.of(plain)));
        rejected(PERMISSION_DENIED, () -> bed.folders.move(viewer, U, plain, k1Folder));
    }

    @Test
    void d21TrashListAndRestoreFollowOrigin() {
        DriveFolderScope u = scope("EDITOR", K1);
        DriveFolderScope v = scope("EDITOR", K2);
        Long copy = bed.folders.copy(v, V, k1File, 0L);
        bed.folders.rename(v, V, copy, "副本.txt");

        bed.folders.trash(u, U, List.of(k1File));
        bed.folders.trash(v, V, List.of(copy));

        assertThat(bed.folders.trashList(u, U))
                .extracting(DriveFolderNode::name)
                .containsExactly("k1.txt");
        assertThat(bed.folders.trashList(v, V))
                .extracting(DriveFolderNode::name)
                .containsExactly("副本.txt");
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.restore(v, V, k1File));
        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("TRASHED");

        DriveFolderNode restored = bed.folders.restore(u, U, k1File);
        assertThat(restored.modifiable()).isTrue();
        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("NORMAL");
    }

    /** 同一个人经由两条记录各删了一个：每条记录的「最近删除」只列经由它放进去的那个。 */
    @Test
    void d21TrashListIsPerOrigin() {
        DriveFolderScope first = scope("EDITOR", K1);
        DriveFolderScope second = scope("EDITOR", K2);
        long other = upload(second, U, 0L, "k2.txt").id();

        bed.folders.trash(first, U, List.of(k1File));
        bed.folders.trash(second, U, List.of(other));

        assertThat(bed.folders.trashList(first, U))
                .extracting(DriveFolderNode::name)
                .containsExactly("k1.txt");
        assertThat(bed.folders.trashList(second, U))
                .extracting(DriveFolderNode::name)
                .containsExactly("k2.txt");
    }

    @Test
    void d22OriginSurvivesPlainDriveOperations() {
        DriveFolderScope u = scope("EDITOR", K1);

        DriveEntryMoveReqVO move = new DriveEntryMoveReqVO();
        move.setId(k1File);
        move.setTargetParentId(k1Folder);
        bed.entryService.moveEntry(move, OWNER);
        assertThat(bed.originOf(k1File)).isEqualTo(K1);

        adminRename(k1File, "管理员改名.txt");
        assertThat(bed.originOf(k1File)).isEqualTo(K1);

        bed.entryService.trashEntryList(List.of(k1File), OWNER);
        assertThat(bed.originOf(k1File)).isEqualTo(K1);
        bed.entryService.restoreEntry(k1File, OWNER);
        assertThat(bed.originOf(k1File)).isEqualTo(K1);

        bed.folders.rename(u, U, k1File, "又改回.txt");
        assertThat(bed.entry(k1File).getName()).isEqualTo("又改回.txt");
        bed.folders.trash(u, U, List.of(k1File));
        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("TRASHED");
    }

    @Test
    void d23PlainDriveCopyHasNoOrigin() {
        DriveEntryCopyReqVO request = new DriveEntryCopyReqVO();
        request.setId(k1File);
        request.setTargetSpaceId(t);
        request.setTargetParentId(shared);
        Long copy = bed.entryService.copyEntry(request, OWNER);

        assertThat(bed.originOf(copy)).isNull();
        for (var actor :
                List.of(Map.entry(U, scope("EDITOR", K1)), Map.entry(V, scope("EDITOR", K2)))) {
            rejected(
                    ENTRY_SCOPE_NOT_OWN,
                    () -> bed.folders.rename(actor.getValue(), actor.getKey(), copy, "x.txt"));
            rejected(
                    ENTRY_SCOPE_NOT_OWN,
                    () -> bed.folders.trash(actor.getValue(), actor.getKey(), List.of(copy)));
        }
    }

    @Test
    void d24OriginKeyIsMandatoryAndBounded() {
        for (String key : new String[] {null, "", "   ", "k".repeat(701)}) {
            DriveFolderScope invalid = scope("EDITOR", key);
            assertThatThrownBy(() -> bed.folders.list(invalid, U, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> bed.folders.ensureChild(shared, "x", U, key))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(bed.folders.list(scope("EDITOR", "k".repeat(700)), U, 0L)).hasSize(3);
    }

    @Test
    void d25BatchDeleteIsAllOrNothing() {
        DriveFolderScope u = scope("EDITOR", K1);

        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.trash(u, U, List.of(k1File, plain)));

        assertThat(bed.entry(k1File).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(plain).getTrashState()).isEqualTo("NORMAL");
    }

    @Test
    void d26TrashedForeignContentNoLongerBlocks() {
        long[] added = putIntoForeignFolder();
        DriveFolderScope u = scope("EDITOR", K1);
        rejected(ENTRY_SCOPE_FOLDER_MIXED, () -> bed.folders.trash(u, U, List.of(k1Folder)));

        bed.folders.trash(scope("VIEWER", K2), W, List.of(added[0], added[1]));

        bed.folders.trash(u, U, List.of(k1Folder));
        assertThat(bed.entry(k1Folder).getTrashState()).isEqualTo("TRASHED");
    }

    /** 清点项：三条新的子树查询与来源标记语句经得住正式的拦截器链，结果与逐条核对一致。 */
    @Test
    void originStatementsAgreeWithRowLevelChecks() {
        long[] added = putIntoForeignFolder();

        assertThat(bed.entries.countForeignInSubtree(t, k1Folder, K1)).isEqualTo(2);
        assertThat(bed.entries.countForeignInSubtree(t, k1Folder, K2)).isEqualTo(2);
        assertThat(bed.entries.countForeignInSubtree(t, added[1], K2)).isZero();
        assertThat(bed.entries.countForeignInSubtree(t, shared, K1)).isEqualTo(4);
        assertThat(bed.entries.insertOrigins(List.of(k1File, plain), K2, V)).isEqualTo(1);
        assertThat(bed.originOf(k1File)).isEqualTo(K1);
        assertThat(bed.originOf(plain)).isEqualTo(K2);
        assertThat(
                        bed.entries.selectOrigins(List.of(k1File, added[0], -5L)).stream()
                                .collect(
                                        Collectors.toMap(
                                                row -> row.getEntryId(), Function.identity()))
                                .keySet())
                .containsExactlyInAnyOrder(k1File, added[0]);
    }

    @Test
    void originRowsCarryTheCommonColumns() {
        Map<String, Object> row =
                bed.jdbc.queryForMap(
                        "SELECT creator, updater, deleted, create_time, update_time"
                                + " FROM public.drive_entry_origin WHERE entry_id=?",
                        k1File);
        assertThat(row.get("creator")).isEqualTo(String.valueOf(U));
        assertThat(row.get("updater")).isEqualTo(String.valueOf(U));
        assertThat(((Number) row.get("deleted")).intValue()).isZero();
        assertThat(row.get("create_time")).isNotNull();
        assertThat(row.get("update_time")).isNotNull();
    }

    @Test
    void voidedOriginCountsAsNoOrigin() {
        DriveFolderScope u = scope("EDITOR", K1);
        long gone = upload(u, U, 0L, "gone.txt").id();
        bed.folders.trash(u, U, List.of(gone));
        for (long id : List.of(k1File, inFile, gone))
            bed.jdbc.update("UPDATE public.drive_entry_origin SET deleted=1 WHERE entry_id=?", id);

        // 逐个节点的来源（selectOrigins）
        assertThat(modifiable(bed.folders.list(u, U, 0L))).containsEntry("k1.txt", false);
        rejected(ENTRY_SCOPE_NOT_OWN, () -> bed.folders.rename(u, U, k1File, "x.txt"));
        // 子树里别人的内容（countForeignInSubtree）
        rejected(ENTRY_SCOPE_FOLDER_MIXED, () -> bed.folders.trash(u, U, List.of(k1Folder)));
        // 本来源的最近删除（selectTrashedInSubtree）
        assertThat(bed.folders.trashList(u, U)).extracting(DriveFolderNode::name).isEmpty();
    }
}
