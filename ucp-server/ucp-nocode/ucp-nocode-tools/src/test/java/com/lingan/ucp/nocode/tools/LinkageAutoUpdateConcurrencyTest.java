package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据联动自动更新的并发：参与自动更新的对象经全局执行锁串行，最终结果必须等价于某个串行顺序——判据是「预告对账为 0」 （按当前数据重算的应有值 =
 * 库中值）。「值没变就不写」的预判是在没有行锁的情况下读的，没有串行就会留下过期值。
 */
class LinkageAutoUpdateConcurrencyTest {
    private LinkageSyncFixture x;

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
        x = new LinkageSyncFixture();
        x.benchmark();
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    /** 4 线程并发给同一条流水新增凭证，再并发删除：每一步之后都与串行结果一致。 */
    @Test
    void concurrentSourcesOnOneTargetConvergeToTheSerialResult() throws Exception {
        var a = x.createFlow("并发目标");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            List<Future<Row>> creates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                creates.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return x.createVoucher(
                                            "并发凭证" + n, a.id(), n % 2 == 0 ? "ylr" : "ysh");
                                }));
            }
            gate.countDown();
            List<Row> created = new ArrayList<>();
            for (var future : creates) created.add(future.get(60, TimeUnit.SECONDS));
            assertThat(x.pending()).as("并发新增后：库中值 = 按当前数据重算的值").isZero();
            assertThat(x.flowStatus(a.id())).isIn("ylr", "ysh");
            List<Future<?>> deletes = new ArrayList<>();
            for (var row : created) deletes.add(pool.submit(() -> x.delete(x.voucher, row.id())));
            for (var future : deletes) future.get(60, TimeUnit.SECONDS);
            assertThat(x.pending()).as("并发删除后").isZero();
            assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        }
    }

    /** 一个线程反复保存凭证，另一个线程反复保存它指向的流水的别的字段：无死锁、无丢更新、对账为 0。 */
    @Test
    void sourceAndTargetSavedConcurrentlyNeitherDeadlockNorLoseUpdates() throws Exception {
        int rounds = 200;
        var a = x.createFlow("被两头改的流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        String memo = id(x.flow, "memo");
        var conflicts = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Future<String> source =
                    pool.submit(
                            () -> {
                                gate.await();
                                String last = null;
                                for (int i = 0; i < rounds; i++) {
                                    last = i % 2 == 0 ? "ysh" : "ylr";
                                    x.update(
                                            x.voucher,
                                            v.id(),
                                            Map.of(id(x.voucher, "status"), last));
                                }
                                return last;
                            });
            Future<String> target =
                    pool.submit(
                            () -> {
                                gate.await();
                                String last = null;
                                for (int i = 0; i < rounds; i++) {
                                    String value = "备注" + i;
                                    // 目标被系统写入后修订会变：按乐观锁的正常用法，冲突了刷新重试。
                                    // 来源那头不停顿地连写时，这一头可能连着撞很多次（每次拿到锁时修订都已被
                                    // 系统写入推进），所以不按次数封顶，按时间封顶：来源写完后必然能写进去。
                                    while (true) {
                                        try {
                                            x.update(x.flow, a.id(), Map.of(memo, value));
                                            break;
                                        } catch (ServiceException e) {
                                            if (!Objects.equals(
                                                            e.getCode(), NocodeErrorCodes.CONFLICT)
                                                    || System.nanoTime() > deadline) throw e;
                                            conflicts.incrementAndGet();
                                        }
                                    }
                                    last = value;
                                }
                                return last;
                            });
            gate.countDown();
            String lastStatus = source.get(300, TimeUnit.SECONDS);
            String lastMemo = target.get(300, TimeUnit.SECONDS);
            assertThat(x.column(x.voucher, v.id(), "status")).isEqualTo(lastStatus);
            assertThat(x.flowStatus(a.id())).as("联动字段跟到来源最后一次保存").isEqualTo(lastStatus);
            assertThat(x.column(x.flow, a.id(), "memo")).as("目标自己的字段没有丢更新").isEqualTo(lastMemo);
            assertThat(x.pending()).isZero();
            System.out.println(
                    "LINKAGE_CONCURRENCY rounds="
                            + rounds
                            + " targetRevisionConflicts="
                            + conflicts.get());
        }
    }

    /** 回填进行中并发保存来源：结束后对账为 0。 */
    @Test
    void backfillRunningAlongsideSourceSavesEndsConsistent() throws Exception {
        List<Row> flows = new ArrayList<>();
        List<Row> vouchers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            flows.add(x.createFlow("流水" + i));
            vouchers.add(x.createVoucher("凭证" + i, flows.get(i).id(), "ylr"));
        }
        jdbc.update("UPDATE public.\"" + x.flow.tableName() + "\" SET status = 'wdj'");
        String signature =
                x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001)
                        .fields()
                        .getFirst()
                        .signature();
        String status = id(x.flow, "status");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Future<Integer> backfill =
                    pool.submit(
                            () -> {
                                gate.await();
                                int updated = 0;
                                String cursor = null;
                                while (true) {
                                    var page =
                                            x.sync.backfill(
                                                    new LinkageSync.BackfillRequest(
                                                            x.app,
                                                            x.flow.objectId(),
                                                            status,
                                                            signature,
                                                            cursor,
                                                            5),
                                                    10001);
                                    assertThat(page.failedCount()).isZero();
                                    updated += page.updated();
                                    if (page.done()) return updated;
                                    cursor = page.nextCursor();
                                }
                            });
            Future<?> saves =
                    pool.submit(
                            () -> {
                                gate.await();
                                for (var voucher : vouchers)
                                    x.update(
                                            x.voucher,
                                            voucher.id(),
                                            Map.of(id(x.voucher, "status"), "ysh"));
                                return null;
                            });
            gate.countDown();
            saves.get(300, TimeUnit.SECONDS);
            int updated = backfill.get(300, TimeUnit.SECONDS);
            assertThat(updated).isBetween(0, 30);
        }
        assertThat(x.pending()).isZero();
        for (var flow : flows) assertThat(x.flowStatus(flow.id())).isEqualTo("ysh");
    }

    /** 应用发布（目录排它锁）与保存并发：每次保存要么按旧版本、要么按新版本，不会出现查到的规则与求值用的定义对不上。 */
    @Test
    void publishingAlongsideSavesNeverMixesVersions() throws Exception {
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Future<Integer> publishing =
                    pool.submit(
                            () -> {
                                gate.await();
                                for (int i = 0; i < 8; i++) {
                                    var head = x.f.applications.get(x.app).application();
                                    x.f.applications.publish(
                                            new ApplicationCenter.Revision(
                                                    x.app, head.revision(), "并发发布" + i),
                                            10001);
                                }
                                return x.f.applications.get(x.app).application().publishedVersion();
                            });
            Future<String> saving =
                    pool.submit(
                            () -> {
                                gate.await();
                                String last = null;
                                for (int i = 0; i < 60; i++) {
                                    last = i % 2 == 0 ? "ysh" : "ylr";
                                    x.update(
                                            x.voucher,
                                            v.id(),
                                            Map.of(id(x.voucher, "status"), last));
                                }
                                return last;
                            });
            gate.countDown();
            assertThat(publishing.get(300, TimeUnit.SECONDS)).isEqualTo(9);
            String last = saving.get(300, TimeUnit.SECONDS);
            assertThat(x.flowStatus(a.id())).isEqualTo(last);
        }
        assertThat(x.triggerRows(x.app)).as("每个发布版本各登记一行").isEqualTo(9);
        assertThat(x.pending()).isZero();
    }
}
