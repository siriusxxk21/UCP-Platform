package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.ApplicationFollows;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.application.service.application.ApplicationFollowService;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动跟随的并发：对象发布（含跟随）与应用保存 / 发布、与业务保存、以及两个人同时点「立即跟随」。
 *
 * <p>判据落在库里：每个应用最终要么在旧版本要么在新版本，版本号不重、不跳；业务保存一条不丢、一条不错。业务异常（修订号冲突之类）是并发下的正常结果， 其余任何异常（死锁、锁超时、SQL
 * 报错）都算失败。只操作本夹具前缀的对象与应用。
 */
class ApplicationFollowConcurrencyTest {
    private FollowFixture f;
    private RecordService records;
    private String jia;

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
        jia = f.object("jia", "甲");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private String applicationWithForm(String suffix) {
        ApplicationCenter.Resource form =
                f.resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                jia,
                                List.of(
                                        new ApplicationUi.Node(
                                                "name_node",
                                                "FIELD",
                                                f.fieldId(jia, "name"),
                                                null,
                                                null,
                                                null,
                                                List.of())),
                                List.of()));
        return f.app(suffix, "并发" + suffix, f.owner, List.of(form), jia);
    }

    /** 版本表不重号、不跳号，发布指针指向最大的那个版本。 */
    private void assertVersionsContiguous(String app) {
        List<Integer> numbers =
                jdbc.queryForList(
                        "SELECT version_no FROM public.nocode_application_version WHERE"
                                + " application_id=? ORDER BY version_no",
                        Integer.class,
                        Long.valueOf(app));
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= numbers.size(); i++) expected.add(i);
        assertThat(numbers).as("应用版本号连续、不重").isEqualTo(expected);
        assertThat(f.publishedVersion(app)).isEqualTo(numbers.size());
    }

    /** 5.3-1：对象发布（含跟随）与应用保存、发布并发。无死锁；应用最终在旧版本或新版本之一；版本号不重。 */
    @Test
    void objectPublishWithFollowRacesApplicationSaveAndPublish() throws Exception {
        String a = f.app("a", "应用A", jia), b = f.app("b", "应用B", jia);
        f.addField(jia, "note", "备注二");
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> publishing =
                    executor.submit(
                            () -> {
                                start.await();
                                f.publish(f.plan(jia), "并发发布", f.owner);
                                return null;
                            });
            List<Future<Integer>> editing = new ArrayList<>();
            for (String app : List.of(a, b))
                editing.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    int published = 0;
                                    for (int i = 0; i < 6; i++)
                                        try {
                                            ApplicationCenter.Detail head = f.apps.get(app);
                                            ApplicationCenter.Detail saved =
                                                    f.apps.save(
                                                            new ApplicationCenter.Save(
                                                                    app,
                                                                    head.application().revision(),
                                                                    head.application().code(),
                                                                    head.application().name(),
                                                                    "并发保存 " + i,
                                                                    null,
                                                                    head.draft()),
                                                            f.owner);
                                            f.apps.publish(
                                                    new ApplicationCenter.Revision(
                                                            app,
                                                            saved.application().revision(),
                                                            "并发发布 " + i),
                                                    f.owner);
                                            published++;
                                        } catch (ServiceException conflict) {
                                            // 修订号被跟随顶掉之类的业务冲突：并发下的正常结果，重读后再试。
                                        } catch (Throwable other) {
                                            unexpected.add(other);
                                        }
                                    return published;
                                }));
            start.countDown();
            publishing.get(60, TimeUnit.SECONDS);
            for (Future<Integer> edit : editing) edit.get(60, TimeUnit.SECONDS);
        }
        assertThat(unexpected).as("除业务冲突外不许有别的异常（死锁、锁超时、SQL 报错）").isEmpty();
        int latest = f.reference(jia).versionNo();
        assertThat(latest).isEqualTo(2);
        for (String app : List.of(a, b)) {
            assertVersionsContiguous(app);
            int pinned = f.publishedReference(app, jia).path("versionNo").asInt();
            assertThat(pinned).as("应用最终要么在旧版本要么在新版本").isIn(1, 2);
            Map<String, Object> state = f.state(app, jia);
            if (pinned == 1)
                assertThat(state).as("没跟上的必须留下待处理状态，不能无声落后").containsEntry("state", "PENDING");
        }
    }

    /**
     * 5.3-2：对象发布（含跟随）期间另一个线程持续做业务保存。每一次保存都成功；保存完的记录都读得回来、值没错； 发布之后的保存按新应用版本进行（应用固定的对象版本与对象最新版一致）。
     */
    @Test
    void businessSavesKeepWorkingAcrossAnObjectPublishWithFollow() throws Exception {
        String app = applicationWithForm("save");
        String name = f.fieldId(jia, "name");
        f.addField(jia, "note", "备注二");
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch warmed = new CountDownLatch(3);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String[]> saved = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> saving =
                    executor.submit(
                            () -> {
                                for (int i = 0; !stop.get() && i < 400; i++) {
                                    String value = "并发保存" + i;
                                    try {
                                        String id =
                                                records.save(
                                                                new ApplicationRecords.Save(
                                                                        app,
                                                                        jia,
                                                                        null,
                                                                        null,
                                                                        Map.of(name, value),
                                                                        Map.of(),
                                                                        Map.of(),
                                                                        null,
                                                                        "form",
                                                                        UUID.randomUUID()
                                                                                .toString(),
                                                                        null),
                                                                f.owner)
                                                        .record()
                                                        .id();
                                        saved.add(new String[] {id, value});
                                    } catch (Throwable failure) {
                                        failures.add(failure);
                                    }
                                    warmed.countDown();
                                }
                                return null;
                            });
            assertThat(warmed.await(30, TimeUnit.SECONDS)).as("先让保存跑起来").isTrue();
            f.publish(f.plan(jia), "保存期间发布", f.owner);
            int during = saved.size();
            // 发布完成后再让它保存几条：这些必然落在新应用版本上。
            long deadline = System.currentTimeMillis() + 15_000;
            while (saved.size() < during + 3
                    && failures.isEmpty()
                    && System.currentTimeMillis() < deadline) Thread.sleep(20);
            stop.set(true);
            saving.get(60, TimeUnit.SECONDS);
        }
        assertThat(failures).as("发布前后的每一次业务保存都成功").isEmpty();
        assertThat(saved.size()).isGreaterThanOrEqualTo(6);
        assertThat(f.publishedVersion(app)).as("应用已跟到新版本").isEqualTo(2);
        assertThat(f.publishedReference(app, jia).path("versionNo").asInt()).isEqualTo(2);
        for (String[] row : saved)
            assertThat(records.get(app, jia, row[0], f.owner).record().values())
                    .as("保存的记录按新版本读回，值没错")
                    .containsEntry(name, row[1]);
        assertThat(
                        records.get(app, jia, saved.getLast()[0], f.owner)
                                .record()
                                .permissions()
                                .readFields())
                .as("新字段在应用里可见")
                .contains(f.fieldId(jia, "note"));
    }

    /** 5.3-3：两个线程同时对同一个应用点「立即跟随」：只产生一个新版本，另一个要么是「已是最新」，要么是业务冲突。 */
    @Test
    void twoSimultaneousRunsProduceExactlyOneNewVersion() throws Exception {
        String app = f.app("run", "应用R", jia);
        f.configure(false, "strict");
        f.publishNewField(jia, "note", "总开关关着时发布");
        f.configure(true, "strict");
        assertThat(f.publishedVersion(app)).isEqualTo(1);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                runs.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        outcomes.add(
                                                f.follows
                                                        .run(
                                                                new ApplicationFollows.Run(
                                                                        app, jia),
                                                                f.owner)
                                                        .outcome());
                                    } catch (ServiceException conflict) {
                                        outcomes.add("CONFLICT");
                                    } catch (Throwable other) {
                                        unexpected.add(other);
                                    }
                                    return null;
                                }));
            start.countDown();
            for (Future<?> run : runs) run.get(60, TimeUnit.SECONDS);
        }
        assertThat(unexpected).isEmpty();
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter("FOLLOWED"::equals).count())
                .as("只有一个人真的跟上了：" + outcomes)
                .isEqualTo(1);
        assertThat(f.versionCount(app)).as("只产生一个新版本").isEqualTo(2);
        assertVersionsContiguous(app);
        assertThat(f.logs(app, jia).stream().filter(log -> "FOLLOWED".equals(log.get("outcome"))))
                .hasSize(1);
        assertThat(f.state(app, jia)).containsEntry("state", "FOLLOWING");
    }

    /**
     * 5.4 的后半句：定时重试遇到仍在途的应用只读不锁。另一个连接持着设计写锁（独占）和目录锁（共享，业务保存拿的就是它）时， 重试照样跑完；它要是去拿任何一把设计锁，就会卡在这里直到超时。
     */
    @Test
    void retryOfStillInFlightRowsTakesNoDesignLocks() throws Exception {
        String app = f.app("pend", "应用P", jia);
        f.runningProcess(app, jia);
        f.publishNewField(jia, "note", "有在途流程");
        assertThat(f.state(app, jia)).containsEntry("state", "PENDING");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (java.sql.Connection other = ds.getConnection()) {
            other.setAutoCommit(false);
            try (java.sql.Statement statement = other.createStatement()) {
                statement.execute(
                        "SELECT pg_advisory_xact_lock(hashtextextended('nocode-design-write',0))");
                statement.execute(
                        "SELECT pg_advisory_xact_lock_shared("
                                + "hashtextextended('nocode-automation-catalog',0))");
                ApplicationFollowService.Retried retried =
                        executor.submit(() -> f.follows.retryPending(50)).get(15, TimeUnit.SECONDS);
                assertThat(retried.examined()).isGreaterThanOrEqualTo(1);
                assertThat(retried.followed()).isZero();
            } finally {
                other.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(f.state(app, jia)).containsEntry("state", "PENDING");
        assertThat(f.publishedVersion(app)).isEqualTo(1);
    }
}
