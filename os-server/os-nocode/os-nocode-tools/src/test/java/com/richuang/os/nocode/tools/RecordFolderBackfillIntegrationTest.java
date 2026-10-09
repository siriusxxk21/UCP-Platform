package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.RecordFolderFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 为已有记录补建文件夹：前端一页一页驱动，每页最多 100 条；幂等，一条失败不中断这一页。真实网盘实现 + 测试库。
 *
 * <p>夹具：「合同」有 250 条记录，其中 30 条已有子文件夹；来源 a1 = 指定文件夹 + 每条记录一个子文件夹（第一次放东西时建）。
 */
class RecordFolderBackfillIntegrationTest {
    private static DriveFolderTestBed bed;
    private RecordFolderFixture x;
    private DataCenter.Definition a;
    private String app;
    private long[] folder;
    private RecordFolders.Source a1;
    private List<String> ids;

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
        app = x.app(a);
        folder = x.bizFolder("合同资料");
        a1 = x.configure(a, folderSource(folder, "RECORD_SUBFOLDER", "合同文件", null)).getFirst();
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    /** 导入 count 条合同，并给其中前 prebuilt 条（按主键升序）先建好子文件夹。 */
    private void seed(int count, int prebuilt) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 1; index <= count; index++)
            rows.add(x.values(a, Map.of("code", "HT-" + index, "name", "工程" + index)));
        assertThat(x.live.runtime.importRecords(app, a.objectId(), rows, OWNER)).isEqualTo(count);
        ids =
                jdbc.queryForList(
                        // 按主键的数值排序（ORDER BY 写输出列名会按文本排）
                        "SELECT t.id::text FROM public.\"" + a.tableName() + "\" t ORDER BY t.id",
                        String.class);
        assertThat(ids).hasSize(count);
        for (String id : ids.subList(0, prebuilt))
            x.resolver.ensure(
                    x.source(a1),
                    a.objectId(),
                    id,
                    x.records.storedRow(a.objectId(), id, 0L),
                    OWNER);
        assertThat(x.bindings(a)).isEqualTo(prebuilt);
    }

    private RecordFolders.BackfillResult page(String cursor, Integer limit) {
        return x.configs.backfill(
                new RecordFolders.Backfill(a.objectId(), a1.id(), cursor, limit), OWNER);
    }

    @Test
    void k1PagesThroughAllRecords() {
        seed(250, 30);

        RecordFolders.BackfillResult first = page(null, 100);
        RecordFolders.BackfillResult second = page(first.cursor(), 100);
        RecordFolders.BackfillResult third = page(second.cursor(), 100);

        assertThat(List.of(first.scanned(), second.scanned(), third.scanned()))
                .containsExactly(100, 100, 50);
        assertThat(List.of(first.done(), second.done(), third.done()))
                .containsExactly(false, false, true);
        assertThat(first.cursor()).isEqualTo(ids.get(99));
        assertThat(second.cursor()).isEqualTo(ids.get(199));
        assertThat(third.cursor()).isEqualTo(ids.get(249));
        assertThat(first.created() + second.created() + third.created()).isEqualTo(220);
        assertThat(first.existing() + second.existing() + third.existing()).isEqualTo(30);
        assertThat(first.existing()).isEqualTo(30);
        assertThat(first.skipped() + second.skipped() + third.skipped()).isZero();
        assertThat(first.failed() + second.failed() + third.failed()).isZero();
        assertThat(first.failures()).isEmpty();
        assertThat(x.bindings(a)).isEqualTo(250);
        assertThat(x.children(folder[1])).hasSize(250);
        // 补建出来的文件夹记的是各自记录的键
        Long sample = x.boundEntry(a, ids.get(120), a1);
        assertThat(bed.originOf(sample)).isEqualTo(a.objectId() + ":" + ids.get(120));
        assertThat(bed.entry(sample).getName()).isEqualTo("工程121");
    }

    @Test
    void k2RerunCreatesNothing() {
        seed(250, 30);
        String cursor = null;
        for (int round = 0; round < 3; round++) cursor = page(cursor, null).cursor();

        int created = 0;
        int existing = 0;
        cursor = null;
        boolean done = false;
        int rounds = 0;
        while (!done) {
            RecordFolders.BackfillResult result = page(cursor, 500);
            assertThat(result.scanned()).as("一页最多 100 条").isLessThanOrEqualTo(100);
            created += result.created();
            existing += result.existing();
            cursor = result.cursor();
            done = result.done();
            rounds++;
        }

        assertThat(rounds).isEqualTo(3);
        assertThat(created).isZero();
        assertThat(existing).isEqualTo(250);
        assertThat(x.children(folder[1])).hasSize(250);
        assertThat(x.bindings(a)).isEqualTo(250);
        // 游标之后没有记录：一页空的
        RecordFolders.BackfillResult beyond = page(ids.getLast(), 100);
        assertThat(beyond.scanned()).isZero();
        assertThat(beyond.done()).isTrue();
        assertThat(beyond.cursor()).isEqualTo(ids.getLast());
    }

    @Test
    void k3OnlySubfolderSourcesOfThisObjectCanBeBackfilled() {
        seed(3, 0);
        DataCenter.Definition other = x.contract();
        RecordFolders.Source direct =
                x.configure(
                                a,
                                new RecordFolders.SourceInput(
                                        a1.id(),
                                        "FOLDER",
                                        "RECORD_SUBFOLDER",
                                        "合同文件",
                                        folder[0],
                                        folder[1],
                                        null,
                                        null,
                                        null,
                                        null),
                                folderSource(folder, "DIRECT", "共用", null))
                        .get(1);
        RecordFolders.Source foreign =
                x.configure(other, folderSource(folder, "RECORD_SUBFOLDER", "别的对象", null))
                        .getFirst();

        rejected(
                "这个文件夹不需要补建",
                () ->
                        x.configs.backfill(
                                new RecordFolders.Backfill(a.objectId(), direct.id(), null, 100),
                                OWNER));
        for (String sourceId : Arrays.asList(foreign.id(), "999999999", "abc", null))
            rejected(
                    "文件夹配置无效",
                    () ->
                            x.configs.backfill(
                                    new RecordFolders.Backfill(a.objectId(), sourceId, null, 100),
                                    OWNER));
        rejected(
                "数据对象不存在或尚未发布",
                () ->
                        x.configs.backfill(
                                new RecordFolders.Backfill("999999999", a1.id(), null, 100),
                                OWNER));
        assertThat(x.bindings(a)).isZero();
    }

    @Test
    void k4OneFailureDoesNotStopThePage() {
        seed(20, 0);
        bed.drive.refusedName = "工程7";

        RecordFolders.BackfillResult result = page(null, 100);

        assertThat(result.scanned()).isEqualTo(20);
        assertThat(result.created()).isEqualTo(19);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures())
                .containsExactly(
                        new RecordFolders.BackfillFailure(ids.get(6), "建立这条记录的文件夹时出错，请稍后重试"));
        assertThat(result.done()).isTrue();
        assertThat(x.bindings(a)).isEqualTo(19);
        assertThat(x.boundEntry(a, ids.get(6), a1)).isNull();
        assertThat(x.boundEntry(a, ids.get(7), a1)).as("失败的后面照常建").isNotNull();
        assertThat(x.children(folder[1])).hasSize(19).doesNotContain("工程7");

        // 故障排除后再跑一遍：只补上那一条
        bed.drive.refusedName = null;
        RecordFolders.BackfillResult retry = page(null, 100);
        assertThat(retry.created()).isEqualTo(1);
        assertThat(retry.existing()).isEqualTo(19);
        assertThat(retry.failed()).isZero();
    }

    @Test
    void k5RecordsWithoutTheRelationAreSkipped() {
        seed(2, 0);
        DataCenter.Definition b = x.voucher(a);
        String deep = x.app(a, b);
        RecordFolders.Source b2 =
                x.configure(
                                b,
                                relationSource(
                                        x.relationField(b),
                                        a1.id(),
                                        "RECORD_SUBFOLDER",
                                        "本凭证文件",
                                        null))
                        .getFirst();
        Row linked = x.create(deep, b, Map.of("name", "有关联", x.relationField(b), ids.get(0)));
        Row second = x.create(deep, b, Map.of("name", "也有关联", x.relationField(b), ids.get(0)));
        x.create(deep, b, Map.of("name", "没选关联"));
        x.create(deep, b, Map.of("name", "也没选"));

        RecordFolders.BackfillResult result =
                x.configs.backfill(
                        new RecordFolders.Backfill(b.objectId(), b2.id(), null, null), OWNER);

        assertThat(result.scanned()).isEqualTo(4);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.existing()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.done()).isTrue();
        Long contractEntry = x.boundEntry(a, ids.get(0), a1);
        assertThat(x.children(contractEntry)).containsExactly("也有关联", "有关联");
        assertThat(bed.entry(x.boundEntry(b, linked.id(), b2)).getParentId())
                .isEqualTo(contractEntry);
        assertThat(x.boundEntry(b, second.id(), b2)).isNotNull();

        RecordFolders.BackfillResult again =
                x.configs.backfill(
                        new RecordFolders.Backfill(b.objectId(), b2.id(), null, null), OWNER);
        assertThat(again.created()).isZero();
        assertThat(again.existing()).isEqualTo(2);
        assertThat(again.skipped()).isEqualTo(2);
    }
}
