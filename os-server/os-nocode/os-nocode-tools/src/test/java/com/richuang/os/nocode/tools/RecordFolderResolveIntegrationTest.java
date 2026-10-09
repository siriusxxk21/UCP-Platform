package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderResolver;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderResolver.Target;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 一条记录怎么解析出文件夹，以及「还没建」的那一段怎么建出来。真实网盘实现 + 测试库。
 *
 * <p>夹具：对象 A「合同」的来源 a1 = 指定文件夹 + 每条记录一个子文件夹；对象 B「凭证」有单值关联 f→A，来源 b1 = 用关联记录的文件夹(f, a1) + 直接用， b2 =
 * 同一关联 + 在其中建子文件夹，b3 = 指定文件夹 + 所有记录共用。
 */
class RecordFolderResolveIntegrationTest {
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
        contractFolder = x.bizFolder("合同资料");
        sharedFolder = x.bizFolder("公司制度");
        a1 =
                x.configure(
                                a,
                                RecordFolderFixture.folderSource(
                                        contractFolder, "RECORD_SUBFOLDER", "合同文件", null))
                        .getFirst();
        List<RecordFolders.Source> saved =
                x.configure(
                        b,
                        RecordFolderFixture.relationSource(
                                x.relationField(b), a1.id(), "DIRECT", "合同文件夹", null),
                        RecordFolderFixture.relationSource(
                                x.relationField(b), a1.id(), "RECORD_SUBFOLDER", "本凭证文件", null),
                        RecordFolderFixture.folderSource(sharedFolder, "DIRECT", "公司制度", null));
        b1 = saved.get(0);
        b2 = saved.get(1);
        b3 = saved.get(2);
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private Row contract(String name) {
        return x.create(app, a, Map.of("code", "HT-" + name, "name", name));
    }

    private Row voucher(String name, String contractId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        if (contractId != null) values.put(x.relationField(b), contractId);
        return x.create(app, b, values);
    }

    private Target resolve(DataCenter.Definition d, RecordFolders.Source source, String id) {
        return x.resolver.resolve(
                x.source(source), d.objectId(), id, x.records.storedRow(d.objectId(), id, 0L), 0L);
    }

    private Target ensure(DataCenter.Definition d, RecordFolders.Source source, String id) {
        return x.resolver.ensure(
                x.source(source),
                d.objectId(),
                id,
                x.records.storedRow(d.objectId(), id, 0L),
                RecordFolderFixture.OWNER);
    }

    @Test
    void s1UnwrittenRecordIsPending() {
        Row contract = contract("某某工程");

        Target target = resolve(a, a1, contract.id());

        assertThat(target.state()).isEqualTo("PENDING");
        assertThat(target.name()).isEqualTo("某某工程");
        assertThat(target.parent().state()).isEqualTo("READY");
        assertThat(target.parent().entryId()).isEqualTo(contractFolder[1]);
        assertThat(target.parent().spaceId()).isEqualTo(contractFolder[0]);
        assertThat(x.children(contractFolder[1])).isEmpty();
        assertThat(x.bindings(a)).isZero();
    }

    @Test
    void s2RelationSourcesFollowTheRelatedRecord() {
        Row contract = contract("某某工程");
        Row voucher = voucher("凭证一", contract.id());

        Target direct = resolve(b, b1, voucher.id());
        Target sub = resolve(b, b2, voucher.id());
        Target shared = resolve(b, b3, voucher.id());

        assertThat(direct.state()).isEqualTo("PENDING");
        assertThat(direct.name()).isEqualTo("某某工程");
        assertThat(sub.state()).isEqualTo("PENDING");
        assertThat(sub.name()).isEqualTo("凭证一");
        assertThat(sub.parent().state()).isEqualTo("PENDING");
        assertThat(sub.parent().name()).isEqualTo("某某工程");
        assertThat(shared.state()).isEqualTo("READY");
        assertThat(shared.entryId()).isEqualTo(sharedFolder[1]);
    }

    @Test
    void s3EnsureBuildsTheWholeChain() {
        Row contract = contract("某某工程");
        Row voucher = voucher("凭证一", contract.id());

        Target built = ensure(b, b2, voucher.id());

        assertThat(built.state()).isEqualTo("READY");
        Long contractEntry = x.boundEntry(a, contract.id(), a1);
        Long voucherEntry = x.boundEntry(b, voucher.id(), b2);
        assertThat(built.entryId()).isEqualTo(voucherEntry);
        assertThat(bed.entry(contractEntry).getParentId()).isEqualTo(contractFolder[1]);
        assertThat(bed.entry(contractEntry).getName()).isEqualTo("某某工程");
        assertThat(bed.entry(voucherEntry).getParentId()).isEqualTo(contractEntry);
        assertThat(bed.entry(voucherEntry).getName()).isEqualTo("凭证一");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT anchor_entry_id FROM public.nocode_record_folder_binding"
                                        + " WHERE object_id=? AND record_id=?",
                                Long.class,
                                a.objectId(),
                                contract.id()))
                .isZero();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT anchor_entry_id, origin, auto_name, space_id FROM"
                                        + " public.nocode_record_folder_binding WHERE object_id=?"
                                        + " AND record_id=?",
                                b.objectId(),
                                voucher.id()))
                .containsEntry("anchor_entry_id", contractEntry)
                .containsEntry("origin", "AUTO")
                .containsEntry("auto_name", "凭证一")
                .containsEntry("space_id", contractFolder[0]);
        // 来源键用每一层自己的记录：凭证触发建出的合同子文件夹记合同的键
        assertThat(bed.originOf(contractEntry)).isEqualTo(a.objectId() + ":" + contract.id());
        assertThat(bed.originOf(voucherEntry)).isEqualTo(b.objectId() + ":" + voucher.id());

        assertThat(resolve(a, a1, contract.id()).entryId()).isEqualTo(contractEntry);
        assertThat(resolve(b, b1, voucher.id()).state()).isEqualTo("READY");
        assertThat(resolve(b, b1, voucher.id()).entryId()).isEqualTo(contractEntry);
        assertThat(resolve(b, b2, voucher.id()).entryId()).isEqualTo(voucherEntry);
        // 再建一次什么都不变
        assertThat(ensure(b, b2, voucher.id()).entryId()).isEqualTo(voucherEntry);
        assertThat(x.bindings(a)).isEqualTo(1);
        assertThat(x.bindings(b)).isEqualTo(1);
        assertThat(x.children(contractFolder[1])).containsExactly("某某工程");
    }

    @Test
    void s4EmptyRelationIsReportedWithTheFieldName() {
        Row voucher = voucher("凭证一", null);

        for (RecordFolders.Source source : List.of(b1, b2)) {
            Target target = resolve(b, source, voucher.id());
            assertThat(target.state()).isEqualTo("RELATION_EMPTY");
            assertThat(target.message()).isEqualTo("请先选择「所属」");
        }
        assertThat(resolve(b, b3, voucher.id()).state()).isEqualTo("READY");
        // 没选关联的记录建不出东西
        assertThat(ensure(b, b2, voucher.id()).state()).isEqualTo("RELATION_EMPTY");
        assertThat(x.bindings(b)).isZero();
    }

    @Test
    void s5ChangingTheRelationMovesToANewAnchor() {
        Row first = contract("合同一");
        Row second = contract("合同二");
        Row voucher = voucher("凭证一", first.id());
        Long old = ensure(b, b2, voucher.id()).entryId();
        Long secondEntry = ensure(a, a1, second.id()).entryId();

        x.update(app, b, voucher.id(), Map.of(x.relationField(b), second.id()));

        Target moved = resolve(b, b2, voucher.id());
        assertThat(moved.state()).isEqualTo("PENDING");
        assertThat(moved.parent().entryId()).isEqualTo(secondEntry);
        assertThat(resolve(b, b1, voucher.id()).entryId()).isEqualTo(secondEntry);
        // 旧的子文件夹留在原处不搬
        assertThat(bed.entry(old).getTrashState()).isEqualTo("NORMAL");
        assertThat(bed.entry(old).getParentId()).isEqualTo(x.boundEntry(a, first.id(), a1));

        Long rebuilt = ensure(b, b2, voucher.id()).entryId();
        assertThat(rebuilt).isNotEqualTo(old);
        assertThat(bed.entry(rebuilt).getParentId()).isEqualTo(secondEntry);
        assertThat(x.bindings(b)).isEqualTo(2);
    }

    @Test
    void s6TrashedSubfolderIsUnavailableAndPurgedOneIsRebuilt() {
        Row contract = contract("某某工程");
        Long first = ensure(a, a1, contract.id()).entryId();

        bed.entryService.trashEntryList(List.of(first), RecordFolderFixture.OWNER);
        Target trashed = resolve(a, a1, contract.id());
        assertThat(trashed.state()).isEqualTo("UNAVAILABLE");
        assertThat(trashed.message()).isEqualTo("文件夹在回收站里，请联系网盘管理员恢复");
        assertThat(ensure(a, a1, contract.id()).state()).isEqualTo("UNAVAILABLE");

        bed.entryService.purgeEntryList(List.of(first), RecordFolderFixture.OWNER);
        assertThat(resolve(a, a1, contract.id()).state()).isEqualTo("PENDING");

        Long second = ensure(a, a1, contract.id()).entryId();
        assertThat(second).isNotEqualTo(first);
        assertThat(
                        jdbc.queryForList(
                                "SELECT entry_id || ':' || deleted FROM"
                                        + " public.nocode_record_folder_binding WHERE object_id=?"
                                        + " ORDER BY id",
                                String.class,
                                a.objectId()))
                .containsExactly(first + ":1", second + ":0");
    }

    @Test
    void s7AppointedFolderGoneIsUnavailable() {
        Row contract = contract("某某工程");

        bed.entryService.trashEntryList(List.of(contractFolder[1]), RecordFolderFixture.OWNER);
        assertThat(resolve(a, a1, contract.id()).message()).isEqualTo("文件夹在回收站里，请联系网盘管理员恢复");

        bed.entryService.purgeEntryList(List.of(contractFolder[1]), RecordFolderFixture.OWNER);
        Target deleted = resolve(a, a1, contract.id());
        assertThat(deleted.state()).isEqualTo("UNAVAILABLE");
        assertThat(deleted.message()).isEqualTo("文件夹已被删除");

        // 所在空间停用
        Row voucher = voucher("凭证一", null);
        jdbc.update("UPDATE public.drive_space SET status=1 WHERE id=?", sharedFolder[0]);
        Target disabled = resolve(b, b3, voucher.id());
        assertThat(disabled.state()).isEqualTo("UNAVAILABLE");
        assertThat(disabled.message()).isEqualTo("所在空间已停用");
    }

    @Test
    void s8ConcurrentEnsureBuildsExactlyOne() throws Exception {
        Row contract = contract("某某工程");
        Row voucher = voucher("凭证一", contract.id());
        int workers = 4;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Target>> results = new ArrayList<>();
            for (int index = 0; index < workers; index++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await(10, TimeUnit.SECONDS);
                                    return ensure(b, b2, voucher.id());
                                }));
            Set<Long> entries = new HashSet<>();
            for (Future<Target> result : results) {
                Target target = result.get(60, TimeUnit.SECONDS);
                assertThat(target.state()).isEqualTo("READY");
                entries.add(target.entryId());
            }
            assertThat(entries).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(x.bindings(a)).isEqualTo(1);
        assertThat(x.bindings(b)).isEqualTo(1);
        assertThat(x.children(contractFolder[1])).containsExactly("某某工程");
        assertThat(x.children(x.boundEntry(a, contract.id(), a1))).containsExactly("凭证一");
    }

    @Test
    void s9NamesAreAlwaysLegal() {
        Row blank = x.create(app, a, Map.of("code", "HT-空"));
        Row slashed = contract("甲/乙\\丙");
        Row lengthy = contract("长".repeat(150));

        Target fallback = resolve(a, a1, blank.id());
        String tail =
                blank.id().length() > 8
                        ? blank.id().substring(blank.id().length() - 8)
                        : blank.id();
        assertThat(fallback.name()).isEqualTo("记录 " + tail);
        assertThat(resolve(a, a1, slashed.id()).name()).isEqualTo("甲 乙 丙");
        assertThat(resolve(a, a1, lengthy.id()).name()).isEqualTo("长".repeat(100));

        // 这些名字网盘都收
        for (Row row : List.of(blank, slashed, lengthy))
            assertThat(ensure(a, a1, row.id()).state()).isEqualTo("READY");
        assertThat(x.children(contractFolder[1]))
                .containsExactlyInAnyOrder("记录 " + tail, "甲 乙 丙", "长".repeat(100));
    }

    @Test
    void s10ThreeRelationLayersResolveAndTheFourthDoesNot() {
        // a ← b ← c ← d ← e：每个对象直接用上一个对象的文件夹
        DataCenter.Definition c = x.related(b, "三层");
        DataCenter.Definition d = x.related(c, "四层");
        DataCenter.Definition e = x.related(d, "五层");
        String deep = x.app(a, b, c, d, e);
        RecordFolders.Source c1 =
                x.configure(
                                c,
                                RecordFolderFixture.relationSource(
                                        x.relationField(c), b1.id(), "DIRECT", null, null))
                        .getFirst();
        RecordFolders.Source d1 =
                x.configure(
                                d,
                                RecordFolderFixture.relationSource(
                                        x.relationField(d), c1.id(), "DIRECT", null, null))
                        .getFirst();
        // 第四层保存时就被拒；这里直接插表伪造一条「上线后才变得过深」的存量配置
        Long e1 =
                jdbc.queryForObject(
                        "INSERT INTO public.nocode_record_folder_source(object_id, kind, placement,"
                                + " relation_field_id, target_source_id) VALUES"
                                + " (?, 'RELATION', 'DIRECT', ?, ?) RETURNING id",
                        Long.class,
                        e.objectId(),
                        x.relationField(e),
                        Long.valueOf(d1.id()));
        Row contract = x.create(deep, a, Map.of("code", "HT-1", "name", "某某工程"));
        Row second = x.create(deep, b, Map.of("name", "二", x.relationField(b), contract.id()));
        Row third = x.create(deep, c, Map.of("name", "三", x.relationField(c), second.id()));
        Row fourth = x.create(deep, d, Map.of("name", "四", x.relationField(d), third.id()));
        Row fifth = x.create(deep, e, Map.of("name", "五", x.relationField(e), fourth.id()));

        Target three = resolve(d, d1, fourth.id());
        assertThat(three.state()).isEqualTo("PENDING");
        Target built = ensure(d, d1, fourth.id());
        assertThat(built.state()).isEqualTo("READY");
        assertThat(built.entryId()).isEqualTo(x.boundEntry(a, contract.id(), a1));

        Target four =
                x.resolver.resolve(
                        x.sources.selectActive(e1),
                        e.objectId(),
                        fifth.id(),
                        x.records.storedRow(e.objectId(), fifth.id(), 0L),
                        0L);
        assertThat(four.state()).isEqualTo("UNAVAILABLE");
        assertThat(four.message()).isEqualTo("配置已失效");
        assertThat(RecordFolderResolver.MAX_RELATION_DEPTH).isEqualTo(3);
    }

    /** 清点项：建目录与登记对应关系同成同败——外层事务回滚，网盘节点、来源标记、对应关系一起消失。 */
    @Test
    void ensureJoinsTheCallersTransaction() {
        Row contract = contract("某某工程");
        Long[] built = new Long[1];

        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            built[0] = ensure(a, a1, contract.id()).entryId();
                            assertThat(bed.entry(built[0])).isNotNull();
                            status.setRollbackOnly();
                        });

        assertThat(bed.entry(built[0])).isNull();
        assertThat(bed.originOf(built[0])).isNull();
        assertThat(x.bindings(a)).isZero();
        assertThat(resolve(a, a1, contract.id()).state()).isEqualTo("PENDING");
    }

    /** 清点项：对方的来源被删、关联的记录被删、关联字段不在当前版本里。 */
    @Test
    void brokenConfigurationsAreUnavailable() {
        Row contract = contract("某某工程");
        Row voucher = voucher("凭证一", contract.id());

        Target missing =
                x.resolver.resolve(
                        x.source(b1),
                        b.objectId(),
                        voucher.id(),
                        new Row(
                                voucher.id(),
                                null,
                                Map.of(x.relationField(b), "999999999"),
                                null,
                                Map.of(),
                                null),
                        0L);
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.message()).isEqualTo("关联的记录已不存在");

        jdbc.update(
                "UPDATE public.nocode_record_folder_source SET deleted=1 WHERE id=?",
                Long.valueOf(a1.id()));
        Target removed = resolve(b, b1, voucher.id());
        assertThat(removed.state()).isEqualTo("UNAVAILABLE");
        assertThat(removed.message()).isEqualTo("配置已失效");

        jdbc.update(
                "UPDATE public.nocode_record_folder_source SET relation_field_id='999999' WHERE"
                        + " id=?",
                Long.valueOf(b2.id()));
        assertThat(resolve(b, b2, voucher.id()).message()).isEqualTo("配置已失效");
    }
}
