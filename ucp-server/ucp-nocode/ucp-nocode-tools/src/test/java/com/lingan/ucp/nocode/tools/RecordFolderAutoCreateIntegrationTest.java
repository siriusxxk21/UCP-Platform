package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RecordFolderFixture.*;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatus;
import com.lingan.ucp.module.drive.api.folder.DriveFolderApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.lingan.ucp.nocode.runtime.service.folder.RecordFolderAutoCreator;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 「记录保存时就建」：记录变更提交之后由后台把子文件夹建出来；不在保存事务里做任何事，建失败不影响保存。真实网盘实现 + 测试库。
 *
 * <p>夹具：「合同」的来源 a1 = 指定文件夹 + 每条记录一个子文件夹 + 保存时就建 + 名称「编号-名称」，a2 = 同放法但第一次放东西时建；「凭证」的来源 b2 = 关联(f,
 * a1) + 建子文件夹 + 保存时就建。每条断言前先等后台队列处理完。
 */
class RecordFolderAutoCreateIntegrationTest {
    private static final long WAIT = 120_000;

    private static DriveFolderTestBed bed;
    private RecordFolderFixture x;
    private DataCenter.Definition a;
    private DataCenter.Definition b;
    private String app;
    private long[] onSaveFolder;
    private long[] lazyFolder;
    private RecordFolders.Source a1;
    private RecordFolders.Source a2;
    private RecordFolders.Source b2;

    @BeforeAll
    static void open() throws Exception {
        connect();
        bed = RecordFolderFixture.bed();
        // 记下保存这条线程上执行的每条语句（只在测量时记，别的线程不受影响）
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new StatementLedger());
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
        onSaveFolder = x.bizFolder("合同资料");
        lazyFolder = x.bizFolder("合同图纸");
        List<RecordFolders.Source> onContract =
                x.configure(
                        a,
                        new RecordFolders.SourceInput(
                                null,
                                "FOLDER",
                                "RECORD_SUBFOLDER",
                                "合同文件",
                                onSaveFolder[0],
                                onSaveFolder[1],
                                null,
                                null,
                                "ON_SAVE",
                                new RecordFolders.NameTemplate(
                                        "-",
                                        List.of(
                                                new RecordFolders.NamePart(
                                                        "FIELD", x.live.field(a, "code"), null),
                                                new RecordFolders.NamePart(
                                                        "FIELD", x.live.field(a, "name"), null)))),
                        folderSource(lazyFolder, "RECORD_SUBFOLDER", "图纸", "ON_FIRST_WRITE"));
        a1 = onContract.get(0);
        a2 = onContract.get(1);
        b2 =
                x.configure(
                                b,
                                relationSource(
                                        x.relationField(b),
                                        a1.id(),
                                        "RECORD_SUBFOLDER",
                                        "本凭证文件",
                                        "ON_SAVE"))
                        .getFirst();
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private void drained() {
        assertThat(x.autoCreator.drain(WAIT)).as("后台队列在时限内处理完").isTrue();
    }

    private Row contract(String code, String name) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", code);
        if (name != null) values.put("name", name);
        return x.create(app, a, values);
    }

    @Test
    void a1SavedRecordGetsItsFolderWithTheTemplateName() {
        Row contract = contract("HT-001", "某某工程");
        drained();

        Long entry = x.boundEntry(a, contract.id(), a1);
        assertThat(entry).isNotNull();
        assertThat(bed.entry(entry).getName()).isEqualTo("HT-001-某某工程");
        assertThat(bed.entry(entry).getParentId()).isEqualTo(onSaveFolder[1]);
        assertThat(bed.originOf(entry)).isEqualTo(a.objectId() + ":" + contract.id());
        assertThat(x.boundEntry(a, contract.id(), a2)).as("第一次放东西时建的来源不建").isNull();
        assertThat(x.children(lazyFolder[1])).isEmpty();
        assertThat(bed.drive.ensureThreads).containsOnly("nocode-record-folder");

        // 清点项：日期按前 10 个字符、单选按选项名称进名字
        RecordFolders.Source renamed =
                x.configure(
                                a,
                                new RecordFolders.SourceInput(
                                        a1.id(),
                                        "FOLDER",
                                        "RECORD_SUBFOLDER",
                                        "合同文件",
                                        onSaveFolder[0],
                                        onSaveFolder[1],
                                        null,
                                        null,
                                        "ON_SAVE",
                                        new RecordFolders.NameTemplate(
                                                " · ",
                                                List.of(
                                                        new RecordFolders.NamePart(
                                                                "FIELD",
                                                                x.live.field(a, "signed"),
                                                                null),
                                                        new RecordFolders.NamePart(
                                                                "FIELD",
                                                                x.live.field(a, "kind"),
                                                                null)))))
                        .getFirst();
        Row dated =
                x.create(
                        app,
                        a,
                        Map.of("code", "HT-002", "name", "二", "signed", "2026-03-01", "kind", "A"));
        drained();
        assertThat(bed.entry(x.boundEntry(a, dated.id(), renamed)).getName())
                .isEqualTo("2026-03-01 · 施工");
        // 已有的文件夹名字不跟着模板变
        assertThat(bed.entry(entry).getName()).isEqualTo("HT-001-某某工程");
    }

    @Test
    void a2RolledBackSaveBuildsNothing() {
        long enqueued = x.autoCreator.enqueued();

        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            contract("HT-回滚", "不会留下");
                            status.setRollbackOnly();
                        });
        drained();

        assertThat(x.autoCreator.enqueued()).isEqualTo(enqueued);
        assertThat(x.bindings(a)).isZero();
        assertThat(x.children(onSaveFolder[1])).isEmpty();
    }

    @Test
    void a3SameNameGetsASequenceNumber() {
        Row first = contract("HT-001", "某某工程");
        drained();
        Row second = contract("HT-001", "某某工程");
        drained();

        assertThat(bed.entry(x.boundEntry(a, first.id(), a1)).getName()).isEqualTo("HT-001-某某工程");
        assertThat(bed.entry(x.boundEntry(a, second.id(), a1)).getName())
                .isEqualTo("HT-001-某某工程 (2)");
    }

    @Test
    void a4ImportsAreCoveredRowByRowOrBySweep() throws Exception {
        List<RecordChangeBatch> batches = new ArrayList<>();
        try (var ignored = closing(x.live.collector.listen(batches::add))) {
            assertThat(x.live.runtime.importRecords(app, a.objectId(), rows(1, 150), OWNER))
                    .isEqualTo(150);
            drained();
            assertThat(batches.getLast().objects().getFirst().many()).as("150 行逐条给出").isFalse();
            assertThat(x.bindings(a)).isEqualTo(150);
            assertThat(x.children(onSaveFolder[1])).hasSize(150);

            assertThat(x.live.runtime.importRecords(app, a.objectId(), rows(151, 450), OWNER))
                    .isEqualTo(300);
            drained();
            assertThat(batches.getLast().objects().getFirst().many())
                    .as("300 行拿不到逐条编号 ⇒ 整对象补扫")
                    .isTrue();
            assertThat(x.bindings(a)).isEqualTo(450);
            assertThat(x.children(onSaveFolder[1])).hasSize(450);
            assertThat(x.children(lazyFolder[1])).isEmpty();
        }
    }

    private List<Map<String, Object>> rows(int from, int to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = from; index <= to; index++)
            rows.add(x.values(a, Map.of("code", "HT-" + index, "name", "工程" + index)));
        return rows;
    }

    private static AutoCloseable closing(AutoCloseable handle) {
        return handle;
    }

    @Test
    void a5DriveFailureNeverBreaksTheSave() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(RecordFolderAutoCreator.class);
        logs.start();
        logger.addAppender(logs);
        try {
            bed.drive.failure = new IllegalStateException("网盘不可用（用例注入）");

            Row contract = contract("HT-001", "某某工程");
            drained();

            assertThat(contract.id()).isNotBlank();
            assertThat(x.live.runtime.get(app, a.objectId(), contract.id(), OWNER).record().id())
                    .isEqualTo(contract.id());
            assertThat(x.bindings(a)).isZero();
            assertThat(x.children(onSaveFolder[1])).isEmpty();
            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("记录文件夹后台建立失败"));

            // 网盘恢复后打开这条记录：后台补上
            bed.drive.failure = null;
            RecordFolders.Opened opened =
                    x.folders.open(
                            new RecordFolders.OpenQuery(app, a.objectId(), contract.id()), OWNER);
            assertThat(opened.tabs().getFirst().state()).isEqualTo("PENDING");
            drained();
            assertThat(x.boundEntry(a, contract.id(), a1)).isNotNull();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void a6MaintenanceSaveAndApprovalEffectAreCovered() {
        ObjectDataMaintenanceService maintenance =
                servicesContext.getBean(ObjectDataMaintenanceService.class);
        ObjectDataMaintenance.Model model = maintenance.model(a.objectId(), OWNER);
        Row maintained =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        a.objectId(),
                                        model.versionNo(),
                                        model.checksum(),
                                        null,
                                        null,
                                        x.values(a, Map.of("code", "HT-维护", "name", "维护入口")),
                                        UUID.randomUUID().toString()),
                                OWNER)
                        .record();
        drained();
        assertThat(bed.entry(x.boundEntry(a, maintained.id(), a1)).getName())
                .isEqualTo("HT-维护-维护入口");

        // 审批生效：审批前只有申请、没有记录，不建；通过后写入业务数据才建
        BusinessHandlingIntegrationTest handling = new BusinessHandlingIntegrationTest();
        handling.setup();
        String approved = handling.business.object.objectId();
        try {
            handling.policy("APPROVAL", "APPROVAL");
            x.configs.save(
                    new RecordFolders.SaveConfig(
                            approved,
                            List.of(
                                    folderSource(
                                            lazyFolder, "RECORD_SUBFOLDER", "审批文件", "ON_SAVE"))),
                    OWNER);
            var pending = handling.service.submit(handling.command("待审批"), OWNER);
            drained();
            assertThat(bindingsOf(approved)).isZero();

            handling.event(pending.request(), BpmProcessInstanceStatus.APPROVED);
            drained();
            assertThat(handling.business.recordCount()).isEqualTo(1);
            assertThat(bindingsOf(approved)).isEqualTo(1);
            assertThat(x.children(lazyFolder[1])).hasSize(1);
        } finally {
            jdbc.update(
                    "DELETE FROM public.nocode_record_folder_binding WHERE object_id=?", approved);
            jdbc.update(
                    "DELETE FROM public.nocode_record_folder_source WHERE object_id=?", approved);
            handling.cleanup();
        }
    }

    private int bindingsOf(String objectId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_folder_binding WHERE object_id=? AND"
                        + " deleted=0",
                Integer.class,
                objectId);
    }

    @Test
    void a7RelationFilledInLaterBuildsTheChain() {
        Row contract = contract("HT-001", "某某工程");
        drained();
        // 另一份合同不受影响：它的文件夹里不会多出凭证的子文件夹
        Row orphan = contract("HT-002", "另一份");
        drained();
        Row voucher = x.create(app, b, Map.of("name", "凭证一"));
        drained();
        assertThat(x.bindings(b)).as("关联为空：什么都不建").isZero();

        x.update(app, b, voucher.id(), Map.of(x.relationField(b), contract.id()));
        drained();

        Long contractEntry = x.boundEntry(a, contract.id(), a1);
        Long voucherEntry = x.boundEntry(b, voucher.id(), b2);
        assertThat(voucherEntry).isNotNull();
        assertThat(bed.entry(voucherEntry).getParentId()).isEqualTo(contractEntry);
        assertThat(bed.entry(voucherEntry).getName()).isEqualTo("凭证一");
        assertThat(bed.originOf(voucherEntry)).isEqualTo(b.objectId() + ":" + voucher.id());
        assertThat(bed.originOf(contractEntry)).isEqualTo(a.objectId() + ":" + contract.id());
        assertThat(x.boundEntry(a, orphan.id(), a1)).isNotNull();
        assertThat(x.children(x.boundEntry(a, orphan.id(), a1))).isEmpty();
    }

    @Test
    void a8RepeatedSavesKeepOneFolder() {
        Row contract = contract("HT-001", "某某工程");
        drained();
        Long entry = x.boundEntry(a, contract.id(), a1);

        for (int round = 0; round < 10; round++)
            x.update(app, a, contract.id(), Map.of("name", "改名" + round));
        drained();

        assertThat(x.boundEntry(a, contract.id(), a1)).isEqualTo(entry);
        assertThat(x.bindings(a)).isEqualTo(1);
        assertThat(x.children(onSaveFolder[1])).as("文件夹不随记录改名").containsExactly("HT-001-某某工程");
    }

    @Test
    void a9FolderIsNeverBuiltOnTheCommittingThread() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        bed.drive.hold = hold;
        ExecutorService saver =
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "saver"));
        try {
            Future<Row> saving = saver.submit(() -> contract("HT-001", "某某工程"));

            // 建目录被卡住时保存照样返回：建目录不发生在提交线程上
            Row contract = saving.get(30, TimeUnit.SECONDS);
            assertThat(contract.id()).isNotBlank();
            assertThat(x.bindings(a)).as("保存返回时文件夹还没建出来").isZero();

            hold.countDown();
            drained();
            assertThat(x.boundEntry(a, contract.id(), a1)).isNotNull();
            assertThat(bed.drive.ensureThreads).containsOnly("nocode-record-folder");
        } finally {
            hold.countDown();
            saver.shutdownNow();
        }
    }

    @Test
    void a10ObjectsWithoutOnSaveSourcesCostOneCachedLookup() {
        DataCenter.Definition plain = x.contract();
        String other = x.app(plain);
        x.configs.save(
                new RecordFolders.SaveConfig(
                        plain.objectId(),
                        List.of(folderSource(lazyFolder, "RECORD_SUBFOLDER", "图纸", null))),
                OWNER);
        drained();
        long enqueued = x.autoCreator.enqueued();
        long lookups = x.autoCreator.lookups();

        Row row = x.create(other, plain, Map.of("name", "第 0 次"));
        for (int round = 1; round < 100; round++)
            x.live.runtime.save(
                    new Save(
                            other,
                            plain.objectId(),
                            row.id(),
                            x.live.runtime
                                    .get(other, plain.objectId(), row.id(), OWNER)
                                    .record()
                                    .revision(),
                            x.values(plain, Map.of("name", "第 " + round + " 次")),
                            null),
                    OWNER);
        drained();

        assertThat(x.autoCreator.enqueued()).as("队列里没有入过任务").isEqualTo(enqueued);
        assertThat(x.autoCreator.lookups() - lookups).as("有没有保存时就建的来源只查了一次库").isEqualTo(1);
        assertThat(bed.drive.ensureThreads).isEmpty();

        // 配置一改缓存就清：改成保存时就建之后，下一次保存立即生效
        RecordFolders.Source lazy = x.configs.list(plain.objectId(), OWNER).getFirst();
        x.configs.save(
                new RecordFolders.SaveConfig(
                        plain.objectId(),
                        List.of(
                                new RecordFolders.SourceInput(
                                        lazy.id(),
                                        "FOLDER",
                                        "RECORD_SUBFOLDER",
                                        "图纸",
                                        lazyFolder[0],
                                        lazyFolder[1],
                                        null,
                                        null,
                                        "ON_SAVE",
                                        null))),
                OWNER);
        Row later = x.create(other, plain, Map.of("name", "之后新建"));
        drained();
        assertThat(x.boundEntry(plain, later.id(), lazy)).isNotNull();
    }

    /** 不该变的内容：配了「保存时就建」来源的对象，保存这条线程上执行的语句与没有任何来源的对象完全相同——建目录的语句一条都不在这条线程上。 */
    @Test
    void savingThreadRunsTheSameStatementsWithOrWithoutOnSaveSource() {
        DataCenter.Definition plain = x.contract();
        DataCenter.Definition eager = x.contract();
        String other = x.app(plain, eager);
        RecordFolders.Source onSave =
                x.configs
                        .save(
                                new RecordFolders.SaveConfig(
                                        eager.objectId(),
                                        List.of(
                                                folderSource(
                                                        lazyFolder,
                                                        "RECORD_SUBFOLDER",
                                                        "勤",
                                                        "ON_SAVE"))),
                                OWNER)
                        .getFirst();
        // 预热：对象定义、授权、「有没有保存时就建的来源」的缓存都就位
        Row plainRow = x.create(other, plain, Map.of("code", "W", "name", "预热"));
        Row eagerRow = x.create(other, eager, Map.of("code", "W", "name", "预热"));
        drained();

        Map<String, Integer> createWithout =
                StatementLedger.record(
                        () -> x.create(other, plain, Map.of("code", "C1", "name", "对照")));
        Map<String, Integer> createWith =
                StatementLedger.record(
                        () -> x.create(other, eager, Map.of("code", "C1", "name", "对照")));
        Map<String, Integer> updateWithout =
                StatementLedger.record(
                        () -> x.update(other, plain, plainRow.id(), Map.of("name", "改名")));
        Map<String, Integer> updateWith =
                StatementLedger.record(
                        () -> x.update(other, eager, eagerRow.id(), Map.of("name", "改名")));
        drained();

        assertThat(StatementLedger.totals(createWithout)).isNotEmpty();
        assertThat(StatementLedger.totals(createWith))
                .isEqualTo(StatementLedger.totals(createWithout));
        assertThat(StatementLedger.totals(updateWith))
                .isEqualTo(StatementLedger.totals(updateWithout));
        assertThat(StatementLedger.totals(createWith).keySet())
                .noneMatch(statement -> statement.contains("RecordFolder"));
        assertThat(bed.drive.ensureThreads).containsOnly("nocode-record-folder");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_folder_binding WHERE"
                                        + " object_id=? AND source_id=? AND deleted=0",
                                Integer.class,
                                eager.objectId(),
                                Long.valueOf(onSave.id())))
                .as("预热与对照共两条记录，后台都建了")
                .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a11FullQueueDropsWithoutBlockingOrThrowing() throws Exception {
        RecordFolderAutoCreator small = new RecordFolderAutoCreator(2);
        CountDownLatch stuck = new CountDownLatch(1);
        RecordFolderSourceDO onSave = new RecordFolderSourceDO();
        onSave.setId(1L);
        RecordFolderSourceMapper mapper = Mockito.mock(RecordFolderSourceMapper.class);
        Mockito.when(mapper.selectOnSave("77")).thenReturn(List.of(onSave));
        RecordQueryAccess stalled = Mockito.mock(RecordQueryAccess.class);
        Mockito.when(stalled.storedRow(Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            stuck.await();
                            return null;
                        });
        inject(small, "driveFolders", Mockito.mock(DriveFolderApi.class));
        inject(small, "sources", mapper);
        inject(small, "records", stalled);
        try {
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < 50; index++) ids.add("r" + index);

            long started = System.nanoTime();
            small.committed(
                    new RecordChangeBatch(
                            List.of(
                                    new RecordChangeBatch.ObjectChange(
                                            "77", false, ids, List.of(), List.of()))));
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(millis).as("队列满时不阻塞").isLessThan(5_000);
            // 后台线程最多取走 1 个卡在那里，队列里最多再留 2 个，其余都被丢弃
            assertThat(small.enqueued()).isBetween(2L, 3L);
            assertThat(small.enqueued() + small.dropped()).isEqualTo(50);
            assertThat(small.drain(200)).as("卡住的任务还没处理完").isFalse();
        } finally {
            stuck.countDown();
            assertThat(small.drain(10_000)).isTrue();
            small.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(RecordFolderAutoCreator target, String field, Object bean) {
        ObjectProvider<Object> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(bean);
        Mockito.when(provider.getObject()).thenReturn(bean);
        ReflectionTestUtils.setField(target, field, provider);
    }

    /** 清点项：共享的无代码容器里没有网盘接口的实现——组件照常装配、保存照常成功、它静默不工作。 */
    @Test
    void componentStaysSilentWhereDriveIsAbsent() {
        RecordFolderAutoCreator inert = servicesContext.getBean(RecordFolderAutoCreator.class);
        assertThat(inert).isNotSameAs(x.autoCreator);
        assertThat(servicesContext.getBeanProvider(DriveFolderApi.class).getIfAvailable()).isNull();

        Row contract = contract("HT-001", "某某工程");
        inert.request(a.objectId(), contract.id());
        drained();

        assertThat(inert.enqueued()).isZero();
        assertThat(inert.lookups()).isZero();
        assertThat(inert.drain(10)).isTrue();
        assertThat(x.boundEntry(a, contract.id(), a1)).as("带网盘实现的那个容器里的组件照常工作").isNotNull();
    }
}
