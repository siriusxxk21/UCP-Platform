package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 「开新草稿」与并发读对象头：读取方按「最新版本号」联表并对对象头加共享行锁。它排在开新草稿后面等锁，对方提交时改了最新版本号， PostgreSQL
 * 重新核对这一行时联表条件不再成立、这一行被丢掉——读请求偶发返回「数据对象不存在」。
 *
 * <p>交错点用 MyBatis 拦截器钉死：开新草稿在推进最新版本号之后、提交之前停住；读取方此时进库等锁；放行后读取必须拿到新草稿。不靠睡眠碰运气。
 */
class ObjectHeadReadDuringDraftOpenConcurrencyTest {
    private static final String ADVANCE = "DataCenterMapper.advanceVersion";

    private NocodeIntegrationSupport fixture;
    private ExecutorService pool;

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new PauseAfter());
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanup() {
        PauseAfter.clear();
        pool.shutdownNow();
        fixture.clean();
    }

    /** 设计器读取（ObjectDesignReader#head）。 */
    @Test
    void designReadWaitingBehindDraftOpenStillFindsTheObject() throws Exception {
        assertReadSurvivesDraftOpen(id -> designs.get(id).draft().lockVersion());
    }

    /** 基础草稿读取（ObjectDraftService#requireHead）。 */
    @Test
    void draftReadWaitingBehindDraftOpenStillFindsTheObject() throws Exception {
        assertReadSurvivesDraftOpen(id -> service.get(id).lockVersion());
    }

    private void assertReadSurvivesDraftOpen(Function<String, Integer> read) throws Exception {
        var published = publishedObject();
        String id = published.draft().id();
        int publishedVersionNo = latestVersionNo(id);
        var gate = PauseAfter.gate(ADVANCE);
        Future<Design> opened =
                pool.submit(
                        () -> {
                            gate.arm();
                            return designs.editPublished(
                                    new Revision(id, published.draft().lockVersion(), null), 10001);
                        });
        Future<Integer> reader;
        try {
            assertThat(gate.reached.await(60, TimeUnit.SECONDS)).as("开新草稿已推进最新版本号、尚未提交").isTrue();
            reader = pool.submit(() -> read.apply(id));
            awaitWaitingForLock(reader);
        } finally {
            gate.release.countDown();
        }
        assertThat(opened.get(60, TimeUnit.SECONDS).draft().id()).isEqualTo(id);
        assertThatCode(() -> reader.get(60, TimeUnit.SECONDS))
                .as("排在开新草稿后面的读取不能返回「数据对象不存在」")
                .doesNotThrowAnyException();
        assertThat(reader.get()).isEqualTo(designs.get(id).draft().lockVersion());
        assertThat(latestVersionNo(id)).isEqualTo(publishedVersionNo + 1);
    }

    private Design publishedObject() {
        var request = fixture.createRequest("head");
        var created =
                designs.save(
                        new SaveDesign(
                                request,
                                Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new Revision(created.draft().id(), created.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "对象头并发读取夹具"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return designs.get(created.draft().id());
    }

    private static int latestVersionNo(String id) {
        return jdbc.queryForObject(
                "SELECT latest_version_no FROM public.nocode_object WHERE id=?",
                Integer.class,
                Long.parseLong(id));
    }

    /** 读取方必须真的在库里等对象头的行锁（本测试库此刻没有别的并发）；先做完说明交错没有成立。 */
    private static void awaitWaitingForLock(Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            assertThat(task.isDone()).as("读取不应在开新草稿提交之前完成").isFalse();
            Integer waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname ="
                                    + " current_database() AND wait_event_type = 'Lock'",
                            Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("读取方没有进入等锁");
    }

    /** 让被点名的线程在执行指定 Mapper 语句之后停一次（事务未提交），直到放行。 */
    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class PauseAfter implements Interceptor {
        static final class Gate {
            final String statement;
            final CountDownLatch reached = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            volatile Thread thread;

            Gate(String statement) {
                this.statement = statement;
            }

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
            Object result = invocation.proceed();
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
            return result;
        }
    }
}
