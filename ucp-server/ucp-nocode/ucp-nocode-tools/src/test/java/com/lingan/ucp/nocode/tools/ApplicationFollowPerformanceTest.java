package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationFollows;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.ApplicationUi;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.application.service.application.ApplicationFollowService;
import com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自动跟随的耗时测量（任务书 5.4）。不进常规门禁：只在设了环境变量 FOLLOW_PERFORMANCE=1 时运行；这里的数字是测量值，不是通过线。
 *
 * <p>每一行输出以 FOLLOW_PERFORMANCE 开头，从服务器日志里取。断言只守「功能上跑对了」（该跟的都跟上了、保存没失败），不守耗时。
 */
@Tag("follow-performance")
@EnabledIfEnvironmentVariable(named = "FOLLOW_PERFORMANCE", matches = "1")
class ApplicationFollowPerformanceTest {
    private FollowFixture f;
    private RecordService records;

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
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, "nocode:app:manage"))
                .thenReturn(true);
        f = new FollowFixture();
        records = servicesContext.getBean(RecordService.class);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private ApplicationCenter.Resource form(String objectId) {
        return f.resource(
                "form",
                "FORM",
                new ApplicationUi.Form(
                        objectId,
                        List.of(
                                new ApplicationUi.Node(
                                        "name_node",
                                        "FIELD",
                                        f.fieldId(objectId, "name"),
                                        null,
                                        null,
                                        null,
                                        List.of())),
                        List.of()));
    }

    private long millis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** 后台线程持续做业务保存，记下单次保存的最长耗时与次数；返回停止它并取结果的句柄。 */
    private final class Saver implements AutoCloseable {
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicLong longest = new AtomicLong(), count = new AtomicLong();
        private final List<Throwable> failures =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> running;

        Saver(String app, String objectId) {
            String name = f.fieldId(objectId, "name");
            running =
                    executor.submit(
                            () -> {
                                while (!stop.get() && count.get() < 2000) {
                                    long start = System.nanoTime();
                                    try {
                                        records.save(
                                                new ApplicationRecords.Save(
                                                        app,
                                                        objectId,
                                                        null,
                                                        null,
                                                        Map.of(name, "耗时" + count.get()),
                                                        Map.of(),
                                                        Map.of(),
                                                        null,
                                                        "form",
                                                        UUID.randomUUID().toString(),
                                                        null),
                                                f.owner);
                                    } catch (Throwable failure) {
                                        failures.add(failure);
                                    }
                                    longest.accumulateAndGet(
                                            (System.nanoTime() - start) / 1_000_000, Math::max);
                                    count.incrementAndGet();
                                }
                            });
        }

        void awaitSaves(long n) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 30_000;
            while (count.get() < n && System.currentTimeMillis() < deadline) Thread.sleep(10);
        }

        void reset() {
            longest.set(0);
        }

        @Override
        public void close() throws Exception {
            stop.set(true);
            running.get(60, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    /** 对象发布，引用它的应用 1 / 5 / 20 个全部跟随：整笔耗时、与「总开关关着」的同规模发布对比得出跟随阶段耗时、期间业务保存的最长等待。 */
    @Test
    void publishWithOneFiveAndTwentyFollowingApplications() throws Exception {
        for (int n : List.of(1, 5, 20)) {
            String objectId = f.object("perf" + n, "耗时对象" + n);
            List<String> apps = new ArrayList<>();
            for (int i = 0; i < n; i++)
                apps.add(
                        f.app(
                                "p" + n + "_" + i,
                                "耗时应用" + i,
                                f.owner,
                                List.of(form(objectId)),
                                objectId));
            // 对照：总开关关着，发布同样规模的一次变更。
            f.configure(false, "strict");
            f.addField(objectId, "ctl", "对照字段");
            DataCenter.PublishPlan offPlan = f.plan(objectId);
            long off = millis(() -> f.publish(offPlan, "对照发布", f.owner));
            f.configure(true, "strict");
            // 让应用回到「没有落后」的起点：各补一次跟随（不计时）。
            for (String app : apps)
                f.follows.run(new ApplicationFollows.Run(app, objectId), f.owner);
            for (String app : apps) assertThat(f.publishedVersion(app)).isEqualTo(2);

            f.addField(objectId, "timed", "计时字段");
            long planning = millis(() -> f.plan(objectId));
            DataCenter.PublishPlan plan = f.plan(objectId);
            long on;
            long longest, saves;
            try (Saver saver = new Saver(apps.getFirst(), objectId)) {
                saver.awaitSaves(5);
                long baseline = saver.longest.get();
                saver.reset();
                long before = saver.count.get();
                on = millis(() -> f.publish(plan, "计时发布", f.owner));
                saver.awaitSaves(saver.count.get() + 2);
                longest = saver.longest.get();
                saves = saver.count.get() - before;
                assertThat(saver.failures).as("发布期间业务保存不失败").isEmpty();
                System.out.println(
                        "FOLLOW_PERFORMANCE scenario=publish-save apps="
                                + n
                                + " longest_save_ms="
                                + longest
                                + " baseline_longest_save_ms="
                                + baseline
                                + " saves_during="
                                + saves);
            }
            for (String app : apps) assertThat(f.publishedVersion(app)).isEqualTo(3);
            System.out.println(
                    "FOLLOW_PERFORMANCE scenario=publish apps="
                            + n
                            + " plan_ms="
                            + planning
                            + " execute_follow_on_ms="
                            + on
                            + " execute_follow_off_ms="
                            + off
                            + " follow_phase_ms="
                            + (on - off)
                            + " per_application_ms="
                            + (on - off) / n);
        }
    }

    /** 定时重试 50 行待处理、全部仍在途：耗时；期间另一线程的业务保存不被阻塞（没有拿独占锁）。 */
    @Test
    void retryJobOverFiftyStillPendingRowsDoesNotBlockSaves() throws Exception {
        String objectId = f.object("retry", "重试对象");
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String app = f.app("r" + i, "重试应用" + i, f.owner, List.of(form(objectId)), objectId);
            f.runningProcess(app, objectId);
            apps.add(app);
        }
        String free = f.app("free", "不在途的应用", f.owner, List.of(form(objectId)), objectId);
        f.addField(objectId, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(objectId);
        long publish = millis(() -> f.publish(plan, "五十个在途", f.owner));
        for (String app : apps)
            assertThat(f.state(app, objectId)).containsEntry("state", "PENDING");
        assertThat(f.publishedVersion(free)).isEqualTo(2);
        ApplicationFollowService.Retried retried;
        long retry;
        try (Saver saver = new Saver(free, objectId)) {
            saver.awaitSaves(5);
            long baseline = saver.longest.get();
            saver.reset();
            long before = saver.count.get();
            long start = System.nanoTime();
            retried = f.follows.retryPending(50);
            retry = (System.nanoTime() - start) / 1_000_000;
            long saves = saver.count.get() - before;
            assertThat(saver.failures).isEmpty();
            System.out.println(
                    "FOLLOW_PERFORMANCE scenario=retry-pending rows=50 publish_ms="
                            + publish
                            + " retry_ms="
                            + retry
                            + " examined="
                            + retried.examined()
                            + " followed="
                            + retried.followed()
                            + " saves_during="
                            + saves
                            + " longest_save_ms="
                            + saver.longest.get()
                            + " baseline_longest_save_ms="
                            + baseline);
        }
        assertThat(retried.examined()).isEqualTo(50);
        assertThat(retried.followed()).isZero();
    }

    /** P20：隐式可读判断的耗时。冷（第一次算关联闭包）与热（命中缓存）各量一次。 */
    @Test
    void impliedObjectLookupColdAndWarm() {
        String objectId = f.object("implied", "隐式对象");
        String app = f.app("i", "隐式应用", objectId);
        ImpliedObjects implied = servicesContext.getBean(ImpliedObjects.class);
        long start = System.nanoTime();
        boolean cold = implied.implied(app, objectId);
        long coldMicros = (System.nanoTime() - start) / 1_000;
        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) implied.implied(app, objectId);
        long warmMicros = (System.nanoTime() - start) / 1_000;
        assertThat(cold).as("显式引用的对象不算隐式").isFalse();
        System.out.println(
                "FOLLOW_PERFORMANCE scenario=implied-lookup cold_us="
                        + coldMicros
                        + " warm_1000_calls_us="
                        + warmMicros);
    }
}
