package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.LinkageSyncFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;

import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.*;

/** 回填 5000 条目标期间另一个线程照常保存：总耗时与并发保存的最长等待。单独跑。 */
@Tag("linkage-performance")
@EnabledIfEnvironmentVariable(named = "LINKAGE_PERFORMANCE", matches = "1")
class LinkageBackfillPerformanceTest extends LinkagePerformanceSupport {
    /** 回填 5000 条目标（每页 100）：每页是独立请求；期间另一个线程的普通保存能在回填中途完成，记下它的最长等待。 */
    @Test
    void backfillingFiveThousandTargetsLetsOtherSavesThrough() throws Exception {
        x.benchmark();
        var flows = bulkFlows(5000);
        jdbc.update("UPDATE public.\"" + x.flow.tableName() + "\" SET status = NULL");
        String status = id(x.flow, "status");
        String signature =
                x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001)
                        .fields()
                        .getFirst()
                        .signature();
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        var duringBackfill = new java.util.concurrent.atomic.AtomicInteger();
        var slowest = new java.util.concurrent.atomic.AtomicLong();
        try (var pool = Executors.newFixedThreadPool(1)) {
            Future<?> saves =
                    pool.submit(
                            () -> {
                                int n = 0;
                                while (!finished.get()) {
                                    long started = System.nanoTime();
                                    x.createVoucher("并行凭证" + n, flows.get(n % flows.size()), "ylr");
                                    long millis = (System.nanoTime() - started) / 1_000_000;
                                    slowest.accumulateAndGet(millis, Math::max);
                                    if (!finished.get()) duringBackfill.incrementAndGet();
                                    n++;
                                    // 模拟有人在录凭证，而不是一个线程不停顿地连写：连写时回填与它逐条交替取执行锁，
                                    // 5000 条要跑半小时以上（实测 13 分钟只走了 1800 条），超出构建服务器的持锁时限。
                                    Thread.sleep(300);
                                }
                                return null;
                            });
            long started = System.nanoTime();
            int updated = 0, pages = 0;
            String cursor = null;
            while (true) {
                var page =
                        x.sync.backfill(
                                new LinkageSync.BackfillRequest(
                                        x.app, x.flow.objectId(), status, signature, cursor, 100),
                                10001);
                assertThat(page.failedCount()).as("回填失败：%s", page.failed()).isZero();
                updated += page.updated();
                pages++;
                if (page.done()) break;
                cursor = page.nextCursor();
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            finished.set(true);
            saves.get(120, TimeUnit.SECONDS);
            System.out.println(
                    "LINKAGE_PERFORMANCE backfill targets=5000 pages="
                            + pages
                            + " updated="
                            + updated
                            + " totalMs="
                            + millis
                            + " concurrentSavesDuringBackfill="
                            + duringBackfill.get()
                            + " slowestConcurrentSaveMs="
                            + slowest.get());
            assertThat(pages).isEqualTo(50);
            assertThat(duringBackfill.get()).as("并发保存能在回填中途完成").isPositive();
        }
        assertThat(x.pending()).isZero();
    }
}
