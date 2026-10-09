package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RecordFolderFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;

import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 表单下方的文件夹：每个请求都按「能看 / 能改这条记录」重新算根与角色。真实网盘实现 + 测试库。
 *
 * <p>夹具：「合同」的来源 a1 = 指定文件夹 + 每条记录一个子文件夹；「凭证」（单值关联 f→合同）的来源 b1 = 关联(f, a1) + 直接用，b2 = 关联(f, a1) +
 * 建子文件夹，b3 = 指定文件夹 + 所有记录共用。应用成员：甲能改凭证、乙只能看、丙只能看自己建的（别人建的看不到）、丁能看凭证但看不到关联字段 f。
 */
class RecordFolderAccessIntegrationTest {
    private static DriveFolderTestBed bed;
    private RecordFolderFixture x;
    private DataCenter.Definition a;
    private DataCenter.Definition b;
    private String app;
    private long[] contractFolder;
    private long[] sharedFolder;
    private RecordFolders.Source a1;
    private RecordFolders.Source b1;
    private RecordFolders.Source b2;
    private RecordFolders.Source b3;
    private Row contract;
    private Row voucher;

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
                        JIA, List.of(x.editor(b)),
                        YI, List.of(x.viewer(b)),
                        BING, List.of(x.grant(b, Set.of("READ"), "OWN")),
                        DING, List.of(x.grant(b, Set.of("READ"), "ALL", x.relationField(b)))));
        contractFolder = x.bizFolder("合同资料");
        sharedFolder = x.bizFolder("公司制度");
        a1 =
                x.configure(a, folderSource(contractFolder, "RECORD_SUBFOLDER", "合同文件", null))
                        .getFirst();
        List<RecordFolders.Source> saved =
                x.configure(
                        b,
                        relationSource(x.relationField(b), a1.id(), "DIRECT", "合同文件夹", null),
                        relationSource(
                                x.relationField(b), a1.id(), "RECORD_SUBFOLDER", "本凭证文件", null),
                        folderSource(sharedFolder, "DIRECT", "", null));
        b1 = saved.get(0);
        b2 = saved.get(1);
        b3 = saved.get(2);
        contract = x.create(app, a, Map.of("code", "HT-001", "name", "某某工程"));
        voucher = voucher("凭证一");
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private Row voucher(String name) {
        return x.create(app, b, Map.of("name", name, x.relationField(b), contract.id()));
    }

    private RecordFolders.Opened open(long actor) {
        return x.folders.open(new RecordFolders.OpenQuery(app, b.objectId(), voucher.id()), actor);
    }

    private RecordFolders.EntryQuery q(RecordFolders.Source source) {
        return x.query(app, b, voucher.id(), source);
    }

    private List<String> names(List<RecordFolders.Entry> entries) {
        return entries.stream().map(RecordFolders.Entry::name).toList();
    }

    /** 三张新表的行数与网盘节点数：打开前后必须完全相同。 */
    private List<Integer> footprint() {
        List<Integer> counts = new ArrayList<>();
        for (String table :
                List.of(
                        "nocode_record_folder_source",
                        "nocode_record_folder_binding",
                        "drive_entry_origin",
                        "drive_entry"))
            counts.add(jdbc.queryForObject("SELECT count(*) FROM public." + table, Integer.class));
        return counts;
    }

    @Test
    void p1EditorSeesAllTabsWritable() {
        RecordFolders.Opened opened = open(JIA);

        assertThat(opened.tabs())
                .extracting(RecordFolders.Tab::sourceId)
                .containsExactly(b1.id(), b2.id(), b3.id());
        assertThat(opened.tabs())
                .extracting(RecordFolders.Tab::label)
                .containsExactly("合同文件夹", "本凭证文件", "公司制度");
        assertThat(opened.tabs())
                .extracting(RecordFolders.Tab::state)
                .containsExactly("PENDING", "PENDING", "READY");
        assertThat(opened.tabs()).extracting(RecordFolders.Tab::writable).containsOnly(true);
        assertThat(opened.tabs()).extracting(RecordFolders.Tab::message).containsOnlyNulls();
        // 还没建的文件夹列出来是空的；没有来源的对象没有页签
        assertThat(x.folders.list(q(b2), JIA)).isEmpty();
        assertThat(x.folders.search(with(q(b2), null, null, null, null, "txt"), JIA)).isEmpty();
        assertThat(x.folders.trashList(q(b2), JIA)).isEmpty();
        DataCenter.Definition plain = x.contract();
        String other = x.app(plain);
        Row lonely = x.create(other, plain, Map.of("name", "无文件夹"));
        assertThat(
                        x.folders
                                .open(
                                        new RecordFolders.OpenQuery(
                                                other, plain.objectId(), lonely.id()),
                                        OWNER)
                                .tabs())
                .isEmpty();
    }

    @Test
    void p2ViewerSeesTabsReadOnlyAndCannotWrite() {
        RecordFolders.Opened opened = open(YI);

        assertThat(opened.tabs()).hasSize(3);
        assertThat(opened.tabs()).extracting(RecordFolders.Tab::writable).containsOnly(false);
        rejected("没有此记录的修改权限", () -> x.upload(q(b2), YI, "x.txt"));
        rejected(
                "没有此记录的修改权限",
                () -> x.folders.createFolder(with(q(b2), null, 0L, null, null, "新夹"), YI));
        rejected("无权执行该操作", () -> x.upload(q(b3), YI, "x.txt"));
        rejected(
                "无权执行该操作",
                () -> x.folders.createFolder(with(q(b3), null, 0L, null, null, "新夹"), YI));
        assertThat(x.bindings(b)).isZero();

        // 能看、能下载甲放进去的
        RecordFolders.Entry uploaded = x.upload(q(b3), JIA, "制度.txt");
        assertThat(names(x.folders.list(q(b3), YI))).containsExactly("制度.txt");
        assertThat(x.folders.list(q(b3), YI).getFirst().modifiable()).isFalse();
        RecordFolders.Content content =
                x.folders.content(
                        new RecordFolders.ContentQuery(
                                app, b.objectId(), voucher.id(), b3.id(), uploaded.id()),
                        YI);
        assertThat(content.name()).isEqualTo("制度.txt");
        assertThat(content.role()).isEqualTo("VIEWER");
        rejected("无权执行该操作", () -> x.trash(q(b3), YI, uploaded.id()));
        rejected(
                "无权执行该操作",
                () -> x.folders.rename(with(q(b3), uploaded.id(), null, null, null, "x.txt"), YI));
    }

    @Test
    void p3RecordInvisibleToTheActorIsHidden() {
        rejected("记录不存在或不可访问", () -> open(BING));
        rejected("记录不存在或不可访问", () -> x.folders.list(q(b3), BING));
        rejected("记录不存在或不可访问", () -> x.upload(q(b3), BING, "x.txt"));
        // 记录不存在与看不到是同一句话
        rejected(
                "记录不存在或不可访问",
                () ->
                        x.folders.open(
                                new RecordFolders.OpenQuery(app, b.objectId(), "999999999"), JIA));
        // 不是应用成员：入口的原文案
        rejected("没有此应用的运行权限", () -> open(JI));
        // 自己建的记录看得到（只读）
        Row own = x.create(app, b, Map.of("name", "丙的凭证"), OWNER);
        jdbc.update(
                "UPDATE public.\"" + b.tableName() + "\" SET creator=? WHERE id=?",
                Long.toString(BING),
                Long.valueOf(own.id()));
        assertThat(
                        x.folders
                                .open(
                                        new RecordFolders.OpenQuery(app, b.objectId(), own.id()),
                                        BING)
                                .tabs())
                .extracting(RecordFolders.Tab::writable)
                .containsOnly(false);
    }

    @Test
    void p4UnreadableRelationFieldHidesItsTabs() {
        RecordFolders.Opened opened = open(DING);

        assertThat(opened.tabs()).extracting(RecordFolders.Tab::sourceId).containsExactly(b3.id());
        rejected("记录不存在或不可访问", () -> x.folders.list(q(b1), DING));
        rejected("记录不存在或不可访问", () -> x.folders.list(q(b2), DING));
        assertThat(x.folders.list(q(b3), DING)).isEmpty();
    }

    @Test
    void p5ApprovalInProgressDoesNotBlockTheFolder() {
        RecordFolders.Entry before = x.upload(q(b2), JIA, "审批前.txt");
        x.running(app, b, voucher.id());
        assertThat(
                        x.live.runtime
                                .get(app, b.objectId(), voucher.id(), JIA)
                                .record()
                                .permissions()
                                .actions())
                .as("审批中记录本身不能改")
                .doesNotContain("UPDATE");

        assertThat(open(JIA).tabs()).extracting(RecordFolders.Tab::writable).containsOnly(true);
        RecordFolders.Entry during = x.upload(q(b2), JIA, "审批中.txt");
        x.trash(q(b2), JIA, before.id());

        assertThat(names(x.folders.list(q(b2), JIA))).containsExactly("审批中.txt");
        assertThat(bed.entry(before.id()).getTrashState()).isEqualTo("TRASHED");
        assertThat(during.modifiable()).isTrue();
    }

    @Test
    void p6CredentialOfOneRecordCannotReachAnotherRecordsFolder() {
        Row second = voucher("凭证二");
        RecordFolders.Entry mine = x.upload(q(b2), JIA, "一.txt");
        RecordFolders.Entry theirs = x.upload(x.query(app, b, second.id(), b2), JIA, "二.txt");
        Long myFolder = x.boundEntry(b, voucher.id(), b2);
        Long secondFolder = x.boundEntry(b, second.id(), b2);
        Long contractEntry = x.boundEntry(a, contract.id(), a1);

        // 兄弟目录、父目录、上级目录、别的空间
        for (Long outside :
                List.of(secondFolder, contractEntry, contractFolder[1], sharedFolder[1])) {
            rejected(
                    "无权执行该操作",
                    () -> x.folders.list(with(q(b2), null, outside, null, null, null), JIA));
            rejected(
                    "无权执行该操作",
                    () -> x.folders.move(with(q(b2), mine.id(), null, outside, null, null), JIA));
            rejected(
                    "无权执行该操作",
                    () -> x.folders.copy(with(q(b2), mine.id(), null, outside, null, null), JIA));
            rejected(
                    "无权执行该操作",
                    () -> x.folders.createFolder(with(q(b2), null, outside, null, null, "x"), JIA));
        }
        // 别的记录的文件：读、改、删都不行
        rejected(
                "无权执行该操作",
                () -> x.folders.get(with(q(b2), theirs.id(), null, null, null, null), JIA));
        rejected(
                "无权执行该操作",
                () ->
                        x.folders.content(
                                new RecordFolders.ContentQuery(
                                        app, b.objectId(), voucher.id(), b2.id(), theirs.id()),
                                JIA));
        rejected("无权执行该操作", () -> x.trash(q(b2), JIA, theirs.id()));
        rejected(
                "无权执行该操作",
                () -> x.folders.move(with(q(b2), theirs.id(), null, 0L, null, null), JIA));
        // 根自身
        rejected(
                "不能对这个文件夹本身做此操作",
                () -> x.folders.rename(with(q(b2), myFolder, null, null, null, "新名"), JIA));
        rejected("不能对这个文件夹本身做此操作", () -> x.trash(q(b2), JIA, 0L));

        assertThat(bed.entry(mine.id()).getParentId()).isEqualTo(myFolder);
        assertThat(bed.entry(theirs.id()).getParentId()).isEqualTo(secondFolder);
        assertThat(bed.entry(theirs.id()).getTrashState()).isEqualTo("NORMAL");
        // 根的编号不出现在返回值里
        assertThat(x.folders.list(q(b2), JIA))
                .extracting(RecordFolders.Entry::parentId)
                .containsOnly(0L);
    }

    @Test
    void p7SourceOfAnotherObjectIsRefused() {
        rejected("记录不存在或不可访问", () -> x.folders.list(q(a1), JIA));
        rejected("记录不存在或不可访问", () -> x.upload(q(a1), JIA, "x.txt"));
        for (String invalid : Arrays.asList("999999999", "abc", "", null))
            rejected(
                    "记录不存在或不可访问",
                    () ->
                            x.folders.list(
                                    new RecordFolders.EntryQuery(
                                            app,
                                            b.objectId(),
                                            voucher.id(),
                                            invalid,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null),
                                    JIA));
        // 来源已删除
        x.configure(b, folderSource(sharedFolder, "DIRECT", "", null));
        rejected("记录不存在或不可访问", () -> x.folders.list(q(b3), JIA));
    }

    @Test
    void p8OpenCreatesNothing() {
        List<Integer> before = footprint();
        long enqueued = x.autoCreator.enqueued();

        RecordFolders.Opened opened = open(JIA);
        open(YI);

        assertThat(opened.tabs()).hasSize(3);
        assertThat(footprint()).isEqualTo(before);
        assertThat(x.autoCreator.enqueued()).as("来源都是第一次放东西时建：后台队列里没有入过任务").isEqualTo(enqueued);
        assertThat(bed.drive.ensureThreads).isEmpty();
    }

    @Test
    void p8bOpenQueuesOnSaveSourcesInsteadOfBuilding() {
        // 记录是改设置之前就有的：把 b2 改成「记录保存时就建」之后，它还没有文件夹
        RecordFolders.Source onSave =
                x.configure(
                                b,
                                relationSource(
                                        x.relationField(b), a1.id(), "DIRECT", "合同文件夹", null),
                                new RecordFolders.SourceInput(
                                        b2.id(),
                                        "RELATION",
                                        "RECORD_SUBFOLDER",
                                        "本凭证文件",
                                        null,
                                        null,
                                        x.relationField(b),
                                        a1.id(),
                                        "ON_SAVE",
                                        null),
                                folderSource(sharedFolder, "DIRECT", "", null))
                        .get(1);
        assertThat(x.boundEntry(b, voucher.id(), onSave)).isNull();
        long enqueued = x.autoCreator.enqueued();

        RecordFolders.Opened opened = open(YI);

        assertThat(opened.tabs().get(1).state()).as("打开不同步建：返回的仍是还没建").isEqualTo("PENDING");
        assertThat(opened.tabs().get(1).writable()).isFalse();
        assertThat(x.autoCreator.enqueued()).isEqualTo(enqueued + 1);
        assertThat(x.autoCreator.drain(10_000)).isTrue();
        assertThat(x.boundEntry(b, voucher.id(), onSave)).isNotNull();
        assertThat(bed.drive.ensureThreads).as("建目录只发生在后台线程上").containsOnly("nocode-record-folder");
        assertThat(open(YI).tabs().get(1).state()).isEqualTo("READY");
    }

    @Test
    void p9FirstUploadBuildsTheFolder() {
        RecordFolders.Entry uploaded = x.upload(q(b2), JIA, "首次.txt");

        Long contractEntry = x.boundEntry(a, contract.id(), a1);
        Long voucherEntry = x.boundEntry(b, voucher.id(), b2);
        assertThat(bed.entry(contractEntry).getName()).isEqualTo("某某工程");
        assertThat(bed.entry(voucherEntry).getParentId()).isEqualTo(contractEntry);
        assertThat(bed.entry(uploaded.id()).getParentId()).isEqualTo(voucherEntry);
        assertThat(uploaded.parentId()).isZero();
        assertThat(uploaded.modifiable()).isTrue();
        assertThat(bed.originOf(uploaded.id())).isEqualTo(b.objectId() + ":" + voucher.id());
        assertThat(open(JIA).tabs())
                .extracting(RecordFolders.Tab::state)
                .containsExactly("READY", "READY", "READY");
        // 「合同文件夹」页签看到的是合同的子文件夹，里面有本凭证的子文件夹
        assertThat(names(x.folders.list(q(b1), JIA))).containsExactly("凭证一");
        // 新建文件夹同样先建根
        Row second = voucher("凭证二");
        Long folder =
                x.folders.createFolder(
                        with(x.query(app, b, second.id(), b2), null, 0L, null, null, "资料"), JIA);
        assertThat(bed.entry(folder).getParentId()).isEqualTo(x.boundEntry(b, second.id(), b2));
    }

    @Test
    void p10DeletingTheRecordLeavesFolderAndFiles() {
        RecordFolders.Entry uploaded = x.upload(q(b2), JIA, "留下.txt");
        Long voucherEntry = x.boundEntry(b, voucher.id(), b2);
        Row current = x.live.runtime.get(app, b.objectId(), voucher.id(), JIA).record();

        x.live.runtime.delete(new Delete(app, b.objectId(), voucher.id(), current.revision()), JIA);

        assertThatThrownBy(() -> x.live.runtime.get(app, b.objectId(), voucher.id(), JIA));
        assertThat(bed.entry(voucherEntry).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(uploaded.id()).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(uploaded.id()).getParentId()).isEqualTo(voucherEntry);
        assertThat(x.boundEntry(b, voucher.id(), b2)).isEqualTo(voucherEntry);
        assertThat(bed.originOf(uploaded.id())).isEqualTo(b.objectId() + ":" + voucher.id());
        // 记录没了，表单入口自然进不去；文件夹仍在网盘里
        rejected("记录不存在或不可访问", () -> x.folders.list(q(b2), JIA));
        assertThat(x.children(voucherEntry)).containsExactly("留下.txt");
    }

    @Test
    void p11MaintenanceEntryRequiresObjectManagement() {
        RecordFolders.OpenQuery query =
                new RecordFolders.OpenQuery(null, b.objectId(), voucher.id());

        RecordFolders.Opened opened = x.folders.open(query, OWNER);
        assertThat(opened.tabs()).hasSize(3);
        assertThat(opened.tabs()).extracting(RecordFolders.Tab::writable).containsOnly(true);
        RecordFolders.EntryQuery maintenance = x.query(null, b, voucher.id(), b2);
        RecordFolders.Entry uploaded = x.upload(maintenance, OWNER, "维护.txt");
        assertThat(names(x.folders.list(maintenance, OWNER))).containsExactly("维护.txt");
        assertThat(bed.originOf(uploaded.id())).isEqualTo(b.objectId() + ":" + voucher.id());

        // 应用里能改这条记录的人没有对象管理权：数据维护入口进不去
        assertThatThrownBy(() -> x.folders.open(query, JIA))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("需要数据对象管理权限才能维护对象数据");
        assertThatThrownBy(() -> x.folders.list(maintenance, JIA))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> x.upload(maintenance, JIA, "x.txt"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void p12OthersFilesAreReadOnlyAndTrashIsPersonal() {
        // 合同负责人经合同表单放进合同文件夹
        RecordFolders.Entry theirs = x.upload(x.query(app, a, contract.id(), a1), OWNER, "合同.pdf");

        assertThat(x.folders.list(q(b1), JIA).getFirst().modifiable()).isFalse();
        rejected("只能修改或删除经由这条记录放进去的文件", () -> x.trash(q(b1), JIA, theirs.id()));
        assertThat(bed.entry(theirs.id()).getTrashState()).isEqualTo("NORMAL");

        RecordFolders.Entry mine = x.upload(q(b1), JIA, "凭证附件.pdf");
        x.trash(q(b1), JIA, mine.id());
        assertThat(names(x.folders.trashList(q(b1), JIA))).containsExactly("凭证附件.pdf");
        assertThat(x.folders.trashList(q(b1), YI)).as("乙看不到甲删的").isEmpty();
        RecordFolders.Entry restored =
                x.folders.restore(with(q(b1), mine.id(), null, null, null, null), JIA);
        assertThat(restored.parentId()).isZero();
        assertThat(bed.entry(mine.id()).getTrashState()).isEqualTo("NORMAL");
    }

    @Test
    void p13UploadHoldsNoTransactionWhileStreaming() {
        for (RecordFolders.EntryQuery query :
                List.of(
                        // 根还没建（先在短事务里建根）、根已有、数据维护入口
                        q(b2), q(b2), q(b3), x.query(null, b, voucher.id(), b3))) {
            List<Boolean> active = new ArrayList<>();
            byte[] bytes = "内容".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream source = new ByteArrayInputStream(bytes);
            // 只实现逐字节读取：不管存储侧用哪种方式读，都会经过这里
            InputStream probe =
                    new InputStream() {
                        @Override
                        public int read() {
                            active.add(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return source.read();
                        }
                    };

            x.folders.upload(
                    new RecordFolders.UploadQuery(
                            query.applicationId(),
                            query.objectId(),
                            query.recordId(),
                            query.sourceId(),
                            0L,
                            "探针.txt",
                            "text/plain",
                            bytes.length),
                    query.applicationId() == null ? OWNER : JIA,
                    probe);

            assertThat(active).as("上传内容时读过流").isNotEmpty();
            assertThat(active).as("读内容时不在事务内").containsOnly(false);
        }
    }

    /**
     * 页签的 canWrite = 本人在这个页签里能不能写：能改这条记录，或文件夹已建、本人在它上面的网盘角色达到可编辑；与写操作的服务端判定同一口径。
     * 只能看记录、但在团队空间那个文件夹上有网盘「可编辑」的乙：那个页签能写（也真的能传）；业务空间里没有网盘角色的页签、还没建的页签都不能写。
     */
    @Test
    void p14CanWriteIsRecordEditorOrOwnDriveEditor() {
        long team = bed.space("T" + System.nanoTime(), "TEAM", OWNER);
        long dept = bed.folder(team, 0L, "部门共享", OWNER);
        bed.grant(team, dept, YI, "EDITOR");
        List<RecordFolders.Source> saved =
                x.configure(
                        b,
                        relationSource(
                                x.relationField(b), a1.id(), "RECORD_SUBFOLDER", "本凭证文件", null),
                        folderSource(sharedFolder, "DIRECT", "", null),
                        folderSource(new long[] {team, dept}, "DIRECT", "部门共享", null));

        RecordFolders.Opened yi = open(YI);
        assertThat(yi.tabs())
                .extracting(RecordFolders.Tab::label, RecordFolders.Tab::state)
                .containsExactly(
                        tuple("本凭证文件", "PENDING"), tuple("公司制度", "READY"), tuple("部门共享", "READY"));
        assertThat(yi.tabs()).extracting(RecordFolders.Tab::writable).containsOnly(false);
        assertThat(yi.tabs())
                .extracting(RecordFolders.Tab::canWrite)
                .containsExactly(false, false, true);
        // 与服务端最终判定一致：能写的那个真的能传，不能写的真的传不了
        assertThat(x.upload(x.query(app, b, voucher.id(), saved.get(2)), YI, "乙传的.txt").name())
                .isEqualTo("乙传的.txt");
        rejected(
                "无权执行该操作",
                () -> x.upload(x.query(app, b, voucher.id(), saved.get(1)), YI, "x.txt"));
        rejected(
                "没有此记录的修改权限",
                () -> x.upload(x.query(app, b, voucher.id(), saved.get(0)), YI, "x.txt"));

        // 能改记录的甲：三个页签都能写（还没建的也能，第一次放东西时建）
        assertThat(open(JIA).tabs()).extracting(RecordFolders.Tab::canWrite).containsOnly(true);
        // 只能看、又没有网盘角色的丁：都不能写
        assertThat(open(DING).tabs()).extracting(RecordFolders.Tab::canWrite).containsOnly(false);
        // 丁在「部门共享」上只是「可查看」：不算能写
        bed.grant(team, dept, DING, "VIEWER");
        assertThat(open(DING).tabs()).extracting(RecordFolders.Tab::canWrite).containsOnly(false);
        jdbc.update(
                "UPDATE public.drive_permission SET role = 'MANAGER' WHERE space_id = ? AND"
                        + " entry_id = ? AND subject_id = ? AND deleted = 0",
                team,
                dept,
                DING);
        // 改成「可管理」：同样算能写（与写操作判定时取大的规则一致）；丁看不到还没建的「本凭证文件」，所以只有两个页签
        assertThat(open(DING).tabs())
                .extracting(RecordFolders.Tab::label, RecordFolders.Tab::canWrite)
                .containsExactly(tuple("公司制度", false), tuple("部门共享", true));
        assertThat(x.upload(x.query(app, b, voucher.id(), saved.get(2)), DING, "丁传的.txt").name())
                .isEqualTo("丁传的.txt");
    }
}
