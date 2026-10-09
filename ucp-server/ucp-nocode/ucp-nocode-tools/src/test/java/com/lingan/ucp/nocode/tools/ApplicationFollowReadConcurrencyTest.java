package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;
import com.lingan.ucp.nocode.runtime.service.view.DataViewService;
import com.lingan.ucp.nocode.tools.LinkagePublishConcurrencyTest.PausePoint;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 对象发布（同一笔里带出自动跟随）与并发的读请求：读的人不能把跟随打成「待处理（系统错误）」。
 *
 * <p>对象发布的取锁顺序是「目录独占锁 → 设计写锁 → 对象头排他行锁 → 应用头排他行锁」（跟随 / 暂停都在这一笔里改应用头）。
 * 读路径只读数据，但读应用头、读对象头各加一把共享行锁，读发布快照还要目录共享锁，所以每条读路径都必须「进事务先取目录共享锁」： 不先取，就会握着应用头去等对象头（或握着应用头 /
 * 对象头去等目录锁），对方握着另一头等它，数据库判死锁。被杀的多半是跟随那一步 （保存点隔离，对象发布本身成功），结果是只要有人开着这些页面，自动跟随就要等下一轮重试。
 *
 * <p>交错点用 MyBatis 拦截器钉死（只对被点名的那个线程、那一条语句停一次），不靠睡眠碰运气：读线程停在「已经拿到它的第一把锁、正要取下一把」的位置，
 * 这时发起对象发布；发布要么做完、要么在库里等锁，然后放行读线程。
 */
class ApplicationFollowReadConcurrencyTest {
    private static final String OBJECT_HEAD = "ObjectDraftMapper.selectHead";
    private static final String CATALOG_LOCK = "ApplicationMapper.automationCatalogLock";

    private FollowFixture f;
    private ExecutorService pool;
    private RecordService records;
    private ApplicationReportService reports;
    private DataViewService views;
    private ApplicationRuntimePolicy policy;
    private String jia;
    private String a, b;

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new PausePoint());
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
        reports = servicesContext.getBean(ApplicationReportService.class);
        views = servicesContext.getBean(DataViewService.class);
        policy = servicesContext.getBean(ApplicationRuntimePolicy.class);
        jia = f.object("jia", "甲");
        a = f.app("a", "应用A", jia);
        // 应用 B 带一个列表视图和一个统计视图，供运行端读路径用。
        b =
                f.app(
                        "b",
                        "应用B",
                        f.owner,
                        List.of(f.view("view", jia), f.resource("report", "REPORT", report())),
                        jia);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanup() {
        PausePoint.clear();
        pool.shutdownNow();
        f.cleanup();
    }

    /** 应用设计页一打开就读应用详情：先应用头、再逐个读草稿引用的对象头。 */
    @Test
    void objectPublishFollowsWhileTheApplicationDetailIsBeingRead() throws Exception {
        publishWhileReading("应用详情", OBJECT_HEAD, () -> f.apps.get(a));
    }

    /** 成员授权页的读取先读应用详情，再读授权行。 */
    @Test
    void objectPublishFollowsWhileTheMemberAuthorizationIsBeingRead() throws Exception {
        publishWhileReading("成员授权", OBJECT_HEAD, () -> f.authorization.get(a));
    }

    /** 运行端表单每次改值都会重算字段规则：先核对入口（应用头），再读发布快照（目录共享锁）。 */
    @Test
    void objectPublishFollowsWhileFieldRulesAreBeingEvaluated() throws Exception {
        publishWhileReading(
                "字段规则求值",
                CATALOG_LOCK,
                () ->
                        records.evaluateRules(
                                new FieldRules.EvaluateQuery(
                                        b, jia, null, null, true, Map.of(), List.of(), List.of(),
                                        List.of()),
                                f.owner));
    }

    /** 运行端统计视图：查询入口整段在一个事务里（线上是声明式事务）。 */
    @Test
    void objectPublishFollowsWhileAReportIsBeingQueried() throws Exception {
        publishWhileReading(
                "统计视图查询",
                CATALOG_LOCK,
                declarative(
                        () ->
                                reports.query(
                                        new ApplicationReports.Query(
                                                b, "report", null, null, null, null, null, 1, 20),
                                        f.owner)));
    }

    /** 设计期统计预览：先核对设计者（应用头），再读草稿引用的对象版本（对象头）。 */
    @Test
    void objectPublishFollowsWhileAReportIsBeingPreviewed() throws Exception {
        var refs = f.apps.get(b).draft().objects();
        publishWhileReading(
                "统计视图预览",
                OBJECT_HEAD,
                declarative(
                        () ->
                                reports.preview(
                                        new ApplicationReports.Preview(
                                                b, refs, report(), List.of()),
                                        f.owner)));
    }

    /** 运行端数据视图模型：查询入口整段在一个事务里（线上是声明式事务）。 */
    @Test
    void objectPublishFollowsWhileADataViewModelIsBeingRead() throws Exception {
        publishWhileReading(
                "数据视图模型", CATALOG_LOCK, declarative(() -> views.model(b, jia, "view", f.owner)));
    }

    /**
     * 先算授权、后读发布快照（规则设计预览就是这个顺序：预览用的是草稿里的对象定义，不先核对入口）。算授权要判断是不是创建人（读应用头）， 读发布快照要目录共享锁；这一笔在线上是同一个事务。
     */
    @Test
    void objectPublishFollowsWhileGrantsAreResolvedBeforeTheReleaseIsRead() throws Exception {
        var definition = f.latest(jia);
        publishWhileReading(
                "先算授权、再读发布快照",
                CATALOG_LOCK,
                declarative(
                        () -> {
                            policy.access(b, definition, f.owner);
                            return f.apps.published(b);
                        }));
    }

    private ApplicationReports.Config report() {
        return new ApplicationReports.Config(
                jia,
                List.of(),
                List.of(new ApplicationReports.Metric("count", "记录数", "COUNT", null)),
                Map.of(),
                List.of(),
                null,
                "Asia/Shanghai",
                "METRIC",
                null,
                false,
                30,
                null);
    }

    /** 这几个入口在线上是 {@code @Transactional}（整段一个事务）；测试用的服务上下文没有声明式事务代理，这里用同一个事务管理器包一层， 边界与线上相同。 */
    private static <T> Callable<T> declarative(Callable<T> work) {
        return () ->
                new TransactionTemplate(manager)
                        .execute(
                                status -> {
                                    try {
                                        return work.call();
                                    } catch (RuntimeException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                });
    }

    /**
     * 读线程停在 pauseAt 这条语句之前（此前它已经拿到这条读路径的第一把锁），这时给甲加字段并发布（兼容改动 ⇒ 两个应用都自动跟随）。
     *
     * <p>判据落在库里：读请求不报错、对象发布成功、两个应用各多一个版本、各一行 FOLLOWED 日志、没有任何「待处理」行。
     */
    private void publishWhileReading(String what, String pauseAt, Callable<?> reader)
            throws Exception {
        PausePoint.Gate gate = PausePoint.gate(pauseAt);
        Future<?> read =
                pool.submit(
                        () -> {
                            gate.arm();
                            return reader.call();
                        });
        Future<String> publish;
        try {
            assertThat(gate.reached.await(60, TimeUnit.SECONDS))
                    .as("%s 走到了 %s 这一步", what, pauseAt)
                    .isTrue();
            publish = pool.submit(() -> f.publishNewField(jia, "note", "增加备注二"));
            // 发布要么已经做完（读线程此刻没有挡住它），要么在库里等锁（读线程握着它要的锁）；两种情形都放行读线程。
            awaitDoneOrWaitingForLock(publish);
        } finally {
            gate.release.countDown();
        }
        assertThatCode(() -> read.get(60, TimeUnit.SECONDS))
                .as("%s 这一边：不被判死锁、不报错", what)
                .doesNotThrowAnyException();
        assertThatCode(() -> publish.get(60, TimeUnit.SECONDS))
                .as("对象发布这一边：不被判死锁、不失败")
                .doesNotThrowAnyException();
        String planId = publish.get();
        for (String app : List.of(a, b)) {
            List<Map<String, Object>> logs = f.logs(app, jia);
            assertThat(logs).as("%s 进行中发布对象：应用当场跟上，不落「待处理」（日志 %s）", what, logs).hasSize(1);
            assertThat(logs.getFirst())
                    .containsEntry("outcome", "FOLLOWED")
                    .containsEntry("to_version", 2)
                    .containsEntry("plan_id", planId);
            assertThat(f.publishedVersion(app)).isEqualTo(2);
            assertThat(f.state(app, jia))
                    .containsEntry("state", "FOLLOWING")
                    .containsEntry("pending_code", null);
        }
    }

    /** 等到任务做完，或者库里出现等锁的会话（本测试库此刻没有别的并发）。 */
    private static void awaitDoneOrWaitingForLock(Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (task.isDone()) return;
            Integer waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname ="
                                    + " current_database() AND wait_event_type = 'Lock'",
                            Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("对象发布既没有完成，也没有在等锁");
    }
}
