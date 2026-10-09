package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.RecordFolderFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 来源规则在表单入口上的样子：凭「能改这条记录」得到的权限，只能改、删经由这条记录（这张表单）放进去的文件。真实网盘实现 + 测试库。
 *
 * <p>夹具：「凭证」的两条记录 r1、r2 都用来源「公司制度」= 指定文件夹 + 所有记录共用；甲能看全部凭证、只能改 r1，己能看全部、只能改 r2，庚能改全部凭证。 「合同」C
 * 的文件夹里有合同负责人经合同表单传的 {@code 合同.pdf}，凭证经「合同文件夹」页签直接用它。「制度二」页签经关联指到同一个共用文件夹。
 */
class RecordFolderOriginAccessIntegrationTest {
    private static final String NOT_OWN = "只能修改或删除经由这条记录放进去的文件";

    private static DriveFolderTestBed bed;
    private RecordFolderFixture x;
    private DataCenter.Definition a;
    private DataCenter.Definition b;
    private String app;
    private long[] contractFolder;
    private long[] sharedFolder;
    private long[] archiveFolder;
    private RecordFolders.Source a1;
    private RecordFolders.Source contractTab;
    private RecordFolders.Source sharedTab;
    private RecordFolders.Source sharedViaRelation;
    private RecordFolders.Source ownTab;
    private Row contract;
    private Row r1;
    private Row r2;

    @BeforeAll
    static void open() throws Exception {
        connect();
        bed = RecordFolderFixture.bed();
    }

    @AfterAll
    static void shutdown() {
        if (bed != null) bed.close();
        close();
    }

    @BeforeEach
    void setup() {
        x = new RecordFolderFixture(bed);
        a = x.contract();
        b = x.voucher(a);
        app = x.app(a, b);
        x.members(
                app,
                Map.of(
                        JIA, List.of(x.editorOf(b, "r1")),
                        JI, List.of(x.editorOf(b, "r2")),
                        GENG, List.of(x.editor(b))));
        contractFolder = x.bizFolder("合同资料");
        sharedFolder = x.bizFolder("公司制度");
        archiveFolder = x.bizFolder("凭证档案");
        List<RecordFolders.Source> onContract =
                x.configure(
                        a,
                        folderSource(contractFolder, "RECORD_SUBFOLDER", "合同文件", null),
                        folderSource(sharedFolder, "DIRECT", "制度", null));
        a1 = onContract.get(0);
        List<RecordFolders.Source> onVoucher =
                x.configure(
                        b,
                        relationSource(x.relationField(b), a1.id(), "DIRECT", "合同文件夹", null),
                        folderSource(sharedFolder, "DIRECT", "公司制度", null),
                        relationSource(
                                x.relationField(b), onContract.get(1).id(), "DIRECT", "制度二", null),
                        folderSource(archiveFolder, "RECORD_SUBFOLDER", "本凭证文件", null));
        contractTab = onVoucher.get(0);
        sharedTab = onVoucher.get(1);
        sharedViaRelation = onVoucher.get(2);
        ownTab = onVoucher.get(3);
        contract = x.create(app, a, Map.of("code", "HT-001", "name", "某某工程"));
        r1 = x.create(app, b, Map.of("name", "r1", x.relationField(b), contract.id()));
        r2 = x.create(app, b, Map.of("name", "r2", x.relationField(b), contract.id()));
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private RecordFolders.EntryQuery q(Row record, RecordFolders.Source source) {
        return x.query(app, b, record.id(), source);
    }

    private RecordFolders.EntryQuery rename(RecordFolders.EntryQuery q, Long id, String name) {
        return with(q, id, null, null, null, name);
    }

    private Map<String, Boolean> modifiable(List<RecordFolders.Entry> entries) {
        return entries.stream()
                .collect(
                        Collectors.toMap(
                                RecordFolders.Entry::name, RecordFolders.Entry::modifiable));
    }

    private RecordFolders.Content content(RecordFolders.EntryQuery q, Long id, long actor) {
        return x.folders.content(
                new RecordFolders.ContentQuery(
                        q.applicationId(), q.objectId(), q.recordId(), q.sourceId(), id),
                actor);
    }

    @Test
    void o1SharedFolderFilesBelongToTheRecordThatPutThem() throws Exception {
        RecordFolders.Entry file = x.upload(q(r1, sharedTab), JIA, "r1.txt");

        assertThat(modifiable(x.folders.list(q(r1, sharedTab), JIA))).containsEntry("r1.txt", true);
        assertThat(modifiable(x.folders.list(q(r2, sharedTab), JI))).containsEntry("r1.txt", false);
        RecordFolders.Content content = content(q(r2, sharedTab), file.id(), JI);
        try (var stream = x.folders.contentStream(content, JI, 0)) {
            assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("r1.txt");
        }
        rejected(NOT_OWN, () -> x.folders.rename(rename(q(r2, sharedTab), file.id(), "x.txt"), JI));
        rejected(NOT_OWN, () -> x.trash(q(r2, sharedTab), JI, file.id()));
        assertThat(bed.entry(file.id()).getName()).isEqualTo("r1.txt");
        assertThat(bed.entry(file.id()).getTrashState()).isEqualTo("NORMAL");

        // 己自己传的能改能删
        RecordFolders.Entry mine = x.upload(q(r2, sharedTab), JI, "r2.txt");
        x.folders.rename(rename(q(r2, sharedTab), mine.id(), "r2改.txt"), JI);
        x.trash(q(r2, sharedTab), JI, mine.id());
        assertThat(bed.entry(mine.id()).getTrashState()).isEqualTo("TRASHED");
    }

    @Test
    void o2RelatedRecordsFolderIsReadOnlyExceptWhatThisRecordPut() {
        // 合同负责人经合同表单放进合同文件夹
        RecordFolders.Entry theirs =
                x.upload(x.query(app, a, contract.id(), a1), contractOperator(), "合同.pdf");

        assertThat(content(q(r1, contractTab), theirs.id(), JIA).name()).isEqualTo("合同.pdf");
        rejected(NOT_OWN, () -> x.trash(q(r1, contractTab), JIA, theirs.id()));
        assertThat(bed.entry(theirs.id()).getTrashState()).isEqualTo("NORMAL");

        RecordFolders.Entry mine = x.upload(q(r1, contractTab), JIA, "凭证附件.pdf");
        assertThat(bed.originOf(mine.id())).isEqualTo(b.objectId() + ":" + r1.id());
        assertThat(bed.originOf(theirs.id())).isEqualTo(a.objectId() + ":" + contract.id());
        assertThat(modifiable(x.folders.list(q(r1, contractTab), JIA)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("合同.pdf", false, "凭证附件.pdf", true));
        x.trash(q(r1, contractTab), JIA, mine.id());
        assertThat(bed.entry(mine.id()).getTrashState()).isEqualTo("TRASHED");
    }

    /** 合同表单的操作者：应用负责人（他同时是网盘管理员，但经表单放进去的仍记合同这条记录的键）。 */
    private static long contractOperator() {
        return OWNER;
    }

    @Test
    void o3AnotherEditorOfTheSameRecordCanChangeIt() {
        RecordFolders.Entry file = x.upload(q(r1, sharedTab), JIA, "r1.txt");

        assertThat(modifiable(x.folders.list(q(r1, sharedTab), GENG)))
                .containsEntry("r1.txt", true);
        x.folders.rename(rename(q(r1, sharedTab), file.id(), "同事改名.txt"), GENG);
        x.trash(q(r1, sharedTab), GENG, file.id());

        assertThat(bed.entry(file.id()).getTrashState()).isEqualTo("TRASHED");
        // 同一个人换成 r2 的凭据就不行：看的是经由哪条记录，不是谁
        RecordFolders.Entry again = x.upload(q(r1, sharedTab), JIA, "r1-2.txt");
        rejected(NOT_OWN, () -> x.trash(q(r2, sharedTab), GENG, again.id()));
    }

    @Test
    void o4TwoTabsOfOneRecordShareTheOrigin() {
        RecordFolders.Entry file = x.upload(q(r1, sharedTab), JIA, "r1.txt");

        assertThat(modifiable(x.folders.list(q(r1, sharedViaRelation), JIA)))
                .containsEntry("r1.txt", true);
        x.trash(q(r1, sharedViaRelation), JIA, file.id());

        assertThat(bed.entry(file.id()).getTrashState()).isEqualTo("TRASHED");
        assertThat(x.folders.trashList(q(r1, sharedTab), JIA))
                .extracting(RecordFolders.Entry::name)
                .containsExactly("r1.txt");
    }

    @Test
    void o5ApprovalInProgressChangesNothing() {
        RecordFolders.Entry theirs = x.upload(x.query(app, a, contract.id(), a1), OWNER, "合同.pdf");
        RecordFolders.Entry mine = x.upload(q(r1, sharedTab), JIA, "r1.txt");
        x.running(app, b, r1.id());

        x.trash(q(r1, sharedTab), JIA, mine.id());
        rejected(NOT_OWN, () -> x.trash(q(r1, contractTab), JIA, theirs.id()));

        assertThat(bed.entry(mine.id()).getTrashState()).isEqualTo("TRASHED");
        assertThat(bed.entry(theirs.id()).getTrashState()).isEqualTo("NORMAL");
    }

    @Test
    void o6SwappingTheRecordIdUsesThatRecordsPermission() {
        RecordFolders.Entry file = x.upload(q(r1, sharedTab), JIA, "r1.txt");

        // 甲能看不能改 r2：拿 r2 的凭据就是只读
        assertThat(
                        x.folders
                                .open(new RecordFolders.OpenQuery(app, b.objectId(), r2.id()), JIA)
                                .tabs())
                .extracting(RecordFolders.Tab::writable)
                .containsOnly(false);
        assertThat(modifiable(x.folders.list(q(r2, sharedTab), JIA)))
                .containsEntry("r1.txt", false);
        rejected("无权执行该操作", () -> x.upload(q(r2, sharedTab), JIA, "x.txt"));
        rejected("无权执行该操作", () -> x.trash(q(r2, sharedTab), JIA, file.id()));
        rejected(
                "无权执行该操作",
                () -> x.folders.rename(rename(q(r2, sharedTab), file.id(), "x.txt"), JIA));
        rejected("没有此记录的修改权限", () -> x.upload(q(r2, ownTab), JIA, "x.txt"));
    }

    @Test
    void o7MaintenanceEntryFollowsTheSameOriginRule() {
        RecordFolders.Entry file = x.upload(q(r1, sharedTab), JIA, "r1.txt");
        RecordFolders.EntryQuery maintenance = x.query(null, b, r2.id(), sharedTab);

        assertThat(modifiable(x.folders.list(maintenance, WU))).containsEntry("r1.txt", false);
        rejected(NOT_OWN, () -> x.trash(maintenance, WU, file.id()));
        rejected(NOT_OWN, () -> x.folders.rename(rename(maintenance, file.id(), "x.txt"), WU));
        assertThat(bed.entry(file.id()).getTrashState()).isEqualTo("NORMAL");

        // 经 r1 的数据维护入口进来就是 r1 的键
        x.trash(x.query(null, b, r1.id(), sharedTab), WU, file.id());
        assertThat(bed.entry(file.id()).getTrashState()).isEqualTo("TRASHED");
    }

    @Test
    void o8FoldersMovedInByDriveAdministratorAreReadOnly() {
        RecordFolders.Entry seed = x.upload(q(r1, ownTab), JIA, "先建根.txt");
        Long root = x.boundEntry(b, r1.id(), ownTab);
        long legacy = bed.folder(archiveFolder[0], 0L, "旧资料", OWNER);
        long old = bed.file(archiveFolder[0], legacy, "old.txt", OWNER);
        DriveEntryMoveReqVO move = new DriveEntryMoveReqVO();
        move.setId(legacy);
        move.setTargetParentId(root);
        bed.entryService.moveEntry(move, OWNER);

        assertThat(modifiable(x.folders.list(q(r1, ownTab), JIA)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("旧资料", false, "先建根.txt", true));
        assertThat(
                        modifiable(
                                x.folders.list(
                                        with(q(r1, ownTab), null, legacy, null, null, null), JIA)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("old.txt", false));
        assertThat(content(q(r1, ownTab), old, JIA).name()).isEqualTo("old.txt");

        RecordFolders.Entry added =
                x.upload(with(q(r1, ownTab), null, legacy, null, null, null), JIA, "新传.txt");
        assertThat(added.modifiable()).isTrue();
        assertThat(bed.entry(added.id()).getParentId()).isEqualTo(legacy);

        rejected(NOT_OWN, () -> x.trash(q(r1, ownTab), JIA, old));
        rejected(NOT_OWN, () -> x.trash(q(r1, ownTab), JIA, legacy));
        rejected(NOT_OWN, () -> x.folders.rename(rename(q(r1, ownTab), legacy, "新名"), JIA));
        assertThat(bed.entry(legacy).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(old).getTrashState()).isEqualTo("NORMAL");
        // 自己传进去的仍能删；网盘管理员在表单里和网盘里都能改删移进来的
        x.trash(q(r1, ownTab), JIA, added.id(), seed.id());
        x.trash(q(r1, ownTab), OWNER, old);
        assertThat(bed.entry(old).getTrashState()).isEqualTo("TRASHED");
    }
}
