package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

/**
 * 自动更新的总览 / 预告与「发布应用」并发：发布对话框一打开就发总览、接着逐页预告，用户随时可能点发布。
 *
 * <p>发布的取锁顺序是「目录独占锁 → 应用头排他行锁」。总览与预告只读数据，但读应用草稿、读发布快照都会对应用头加共享行锁， 所以它们必须：① 与发布同一个顺序（先目录共享锁、再应用头），②
 * 不把应用头的锁带进逐条求值那一段（一页可能要几秒到几十秒）。
 *
 * <p>交错点用 MyBatis 拦截器钉死（只对被点名的那个线程、那一条语句停一次），不靠睡眠碰运气。
 */
class LinkagePublishConcurrencyTest {
    private static final String CATALOG_LOCK = "ApplicationMapper.automationCatalogLock";
    private static final String RECORD_ROWS = "RecordMapper.rows";
    private static final String OBJECT_HEAD = "ObjectDraftMapper.selectHead";

    private LinkageSyncFixture x;
    private ExecutorService pool;

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
        x = new LinkageSyncFixture();
        x.benchmark();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanup() {
        PausePoint.clear();
        pool.shutdownNow();
        x.cleanup();
    }

    /**
     * 总览（草稿基准）读到一半时点发布：两边都成功。
     *
     * <p>草稿基准的总览先读应用草稿（应用头共享行锁），之后为了和当前发布版比较又要读发布快照（目录共享锁）。不先取目录锁，就与发布「目录独占锁 → 应用头」
     * 倒序：总览拿着应用头等目录锁、发布拿着目录锁等应用头，数据库判死锁、杀掉其中一个（界面上是「系统异常」）。
     */
    @Test
    void publishDuringDraftOverviewNeitherDeadlocksNorFails() throws Exception {
        int before = publishedVersion();
        var gate = PausePoint.gate(CATALOG_LOCK);
        Future<LinkageSync.Overview> overview =
                pool.submit(
                        () -> {
                            gate.arm();
                            return x.sync.overview(x.app, LinkageSync.BASIS_DRAFT, 10001);
                        });
        assertThat(gate.reached.await(60, TimeUnit.SECONDS)).as("总览走到了取目录共享锁这一步").isTrue();
        Future<ApplicationCenter.Detail> publish = pool.submit(this::publish);
        // 发布要么已经做完（总览此刻什么锁都没拿），要么在库里等锁（总览已经拿了它要的锁）；两种情形都放行总览。
        awaitDoneOrWaitingForLock(publish);
        gate.release.countDown();
        assertThatCode(() -> publish.get(60, TimeUnit.SECONDS))
                .as("总览进行中发布应用：不死锁、不失败")
                .doesNotThrowAnyException();
        assertThatCode(() -> overview.get(60, TimeUnit.SECONDS))
                .as("发布插进来之后总览照常返回")
                .doesNotThrowAnyException();
        assertThat(publishedVersion()).isEqualTo(before + 1);
        assertThat(overview.get().fields())
                .extracting(LinkageSync.Field::targetFieldId)
                .containsExactly(id(x.flow, "status"));
    }

    /** 预告一页正在逐条求值（草稿基准，发布对话框用的就是它）：发布不用等这一页，这一页也照常算完。 */
    @Test
    void publishDoesNotWaitForADraftPreviewPageInProgress() throws Exception {
        publishWhilePreviewPageIsEvaluating(LinkageSync.BASIS_DRAFT);
    }

    /** 同上，发布基准（「自动更新」面板的「检查」）：这一页不能拿着目录共享锁和应用头去求值。 */
    @Test
    void publishDoesNotWaitForAPublishedPreviewPageInProgress() throws Exception {
        publishWhilePreviewPageIsEvaluating(LinkageSync.BASIS_PUBLISHED);
    }

    /**
     * 预告核对规则那一段先读应用草稿（应用头共享行锁）、再逐个读固定的对象版本（对象头共享行锁）。对象发布以及它在同一笔里带出的「暂停应用 / 应用跟随」 是「目录独占锁 → 对象头排他行锁
     * → 应用头排他行锁」（SchemaPublishService#execute）。这一段不先取目录共享锁，就会拿着应用头等对象头、对方拿着对象头等应用头。
     *
     * <p>写入方用同样的取锁顺序直接在库里模拟，不依赖某一种对象发布的业务形态。
     */
    @Test
    void draftPreviewResolvesUnderTheCatalogLockSoAnObjectPublishCannotInterleave()
            throws Exception {
        x.createFlow("一条流水");
        var automations =
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.resource
                                .ApplicationAutomationCatalog.class);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        // 预告线程在这一段第一次读对象头之前停住：此刻它已经读过应用草稿。
        var gate = PausePoint.gate(OBJECT_HEAD);
        Future<LinkageSync.Preview> preview =
                pool.submit(
                        () -> {
                            gate.arm();
                            return x.sync.preview(
                                    new LinkageSync.PreviewRequest(
                                            x.app,
                                            LinkageSync.BASIS_DRAFT,
                                            x.flow.objectId(),
                                            id(x.flow, "status"),
                                            null,
                                            100),
                                    10001);
                        });
        Future<?> objectPublish;
        try {
            assertThat(gate.reached.await(60, TimeUnit.SECONDS)).as("预告走到了读对象头这一步").isTrue();
            objectPublish =
                    pool.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                automations.lock(true);
                                                jdbc.queryForList(
                                                        "SELECT id FROM public.nocode_object WHERE"
                                                                + " id = ? FOR UPDATE",
                                                        Long.valueOf(x.flow.objectId()));
                                                jdbc.queryForList(
                                                        "SELECT id FROM public.nocode_application"
                                                                + " WHERE id = ? FOR UPDATE",
                                                        Long.valueOf(x.app));
                                            }));
            awaitDoneOrWaitingForLock(objectPublish);
        } finally {
            gate.release.countDown();
        }
        assertThatCode(() -> objectPublish.get(60, TimeUnit.SECONDS))
                .as("对象发布那一边：不被判死锁")
                .doesNotThrowAnyException();
        assertThatCode(() -> preview.get(60, TimeUnit.SECONDS))
                .as("预告这一边：不被判死锁")
                .doesNotThrowAnyException();
        assertThat(preview.get().scanned()).isEqualTo(1);
        assertThat(preview.get().failedCount()).isZero();
    }

    private void publishWhilePreviewPageIsEvaluating(String basis) throws Exception {
        var a = x.createFlow("有凭证的流水");
        x.createFlow("没有凭证的流水");
        x.createVoucher("凭证", a.id(), "ylr");
        int before = publishedVersion();
        String field = id(x.flow, "status");
        // 预告线程在「读这一页的记录」之前停住：规则与对象定义已经核对完，接下来是逐条求值。
        var gate = PausePoint.gate(RECORD_ROWS);
        Future<LinkageSync.Preview> preview =
                pool.submit(
                        () -> {
                            gate.arm();
                            return x.sync.preview(
                                    new LinkageSync.PreviewRequest(
                                            x.app, basis, x.flow.objectId(), field, null, 100),
                                    10001);
                        });
        try {
            assertThat(gate.reached.await(60, TimeUnit.SECONDS)).as("预告走到了读记录这一步").isTrue();
            Future<ApplicationCenter.Detail> publish = pool.submit(this::publish);
            assertThatCode(() -> publish.get(30, TimeUnit.SECONDS))
                    .as("预告这一页还在求值，发布应当直接完成（%s 基准）", basis)
                    .doesNotThrowAnyException();
            assertThat(preview.isDone()).as("发布完成时预告这一页还没放行").isFalse();
            assertThat(publishedVersion()).isEqualTo(before + 1);
        } finally {
            gate.release.countDown();
        }
        var page = preview.get(60, TimeUnit.SECONDS);
        assertThat(page.done()).isTrue();
        assertThat(page.scanned()).isEqualTo(2);
        assertThat(page.failedCount()).as("求值失败：%s", page.failed()).isZero();
        assertThat(page.unchanged()).as("两条流水的库中值都等于应有值").isEqualTo(2);
        assertThat(page.total()).isEqualTo(2);
    }

    private ApplicationCenter.Detail publish() {
        var head = x.f.applications.get(x.app).application();
        return x.f.applications.publish(
                new ApplicationCenter.Revision(head.id(), head.revision(), "并发发布"), 10001);
    }

    private int publishedVersion() {
        return jdbc.queryForObject(
                "SELECT published_version FROM public.nocode_application WHERE id = ?",
                Integer.class,
                Long.valueOf(x.app));
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
        throw new AssertionError("发布既没有完成，也没有在等锁");
    }

    /** 让被点名的线程在执行指定 Mapper 语句之前停一次，直到放行。 */
    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class PausePoint implements Interceptor {
        static final class Gate {
            final String statement;
            final CountDownLatch reached = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            volatile Thread thread;

            Gate(String statement) {
                this.statement = statement;
            }

            /** 在要被停住的线程里调用。 */
            void arm() {
                thread = Thread.currentThread();
            }
        }

        private static volatile Gate current;

        static Gate gate(String statement) {
            Gate gate = new Gate(statement);
            current = gate;
            return gate;
        }

        static void clear() {
            Gate gate = current;
            current = null;
            if (gate != null) gate.release.countDown();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Gate gate = current;
            if (gate != null
                    && Thread.currentThread() == gate.thread
                    && invocation.getArgs()[0] instanceof MappedStatement mapped
                    && mapped.getId().endsWith(gate.statement)) {
                current = null;
                gate.reached.countDown();
                if (!gate.release.await(120, TimeUnit.SECONDS))
                    throw new IllegalStateException("暂停点没有被放行：" + gate.statement);
            }
            return invocation.proceed();
        }
    }
}
