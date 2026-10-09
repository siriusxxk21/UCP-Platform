package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import org.junit.jupiter.api.*;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 只读请求记忆的边界：只在作用域内生效；按事务分段，事务之间、事务与无事务段之间互不可见；锁类键只在事务内记忆。
 *
 * <p>事务边界、挂起恢复、保存点与同步器回调全部由 Spring 的 AbstractPlatformTransactionManager 驱动，这里只把「连接」换成内存对象，
 * 所以不连数据库；装载器只计数。真实数据源事务管理器上的同一组边界由 ReadRequestDedupIntegrationTest 用真实语句核对。
 */
class ReadRequestMemoTest {
    /** 内存事务：每个物理事务一个 Holder，绑定在线程上；挂起时摘下，恢复时放回。 */
    private static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private static final class Holder {}

        private static final class Transaction implements SavepointManager {
            private Holder holder;

            @Override
            public Object createSavepoint() {
                return new Object();
            }

            @Override
            public void rollbackToSavepoint(Object savepoint) {}

            @Override
            public void releaseSavepoint(Object savepoint) {}
        }

        private final ThreadLocal<Holder> bound = new ThreadLocal<>();

        private MemoryTransactions() {
            setNestedTransactionAllowed(true);
        }

        @Override
        protected Object doGetTransaction() {
            Transaction transaction = new Transaction();
            transaction.holder = bound.get();
            return transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Transaction) transaction).holder != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            Transaction started = (Transaction) transaction;
            started.holder = new Holder();
            bound.set(started.holder);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            Transaction suspended = (Transaction) transaction;
            Holder holder = suspended.holder;
            suspended.holder = null;
            bound.remove();
            return holder;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            bound.set((Holder) suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            bound.remove();
        }
    }

    private static final MemoryTransactions manager = new MemoryTransactions();
    private final AtomicInteger loads = new AtomicInteger();
    private final Supplier<String> loader = () -> "v" + loads.incrementAndGet();

    private static TransactionTemplate tx(int propagation) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(propagation);
        return template;
    }

    private static TransactionTemplate tx() {
        return tx(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void outsideScopeEveryCallLoads() {
        assertThat(ReadRequestMemo.active()).isFalse();
        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v2");
        tx().executeWithoutResult(
                        status -> {
                            assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v3");
                            assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v4");
                            assertThat(ReadRequestMemo.onceInTransaction("k", loader))
                                    .isEqualTo("v5");
                            assertThat(ReadRequestMemo.onceInTransaction("k", loader))
                                    .isEqualTo("v6");
                        });
        ReadRequestMemo.seed("k", "seeded");
        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v7");
    }

    @Test
    void scopeWithoutTransactionLoadsEachKeyOnce() {
        ReadRequestMemo.within(
                () -> {
                    assertThat(ReadRequestMemo.active()).isTrue();
                    assertThat(ReadRequestMemo.once(ReadRequestMemo.key("a", 1, null), loader))
                            .isEqualTo("v1");
                    assertThat(ReadRequestMemo.once(ReadRequestMemo.key("a", 1, null), loader))
                            .isEqualTo("v1");
                    // 键按值比较，任何一部分不同都是另一个键。
                    assertThat(ReadRequestMemo.once(ReadRequestMemo.key("a", 2, null), loader))
                            .isEqualTo("v2");
                    assertThat(ReadRequestMemo.once(ReadRequestMemo.key("a", 1, 1), loader))
                            .isEqualTo("v3");
                    return null;
                });
        assertThat(ReadRequestMemo.active()).isFalse();
        // 下一个作用域从头开始，不继承上一个请求读到的值。
        assertThat(
                        ReadRequestMemo.<String>within(
                                () ->
                                        ReadRequestMemo.once(
                                                ReadRequestMemo.key("a", 1, null), loader)))
                .isEqualTo("v4");
    }

    @Test
    void nullResultIsRememberedAndFailureIsNot() {
        AtomicInteger calls = new AtomicInteger();
        ReadRequestMemo.within(
                () -> {
                    Supplier<String> empty =
                            () -> {
                                calls.incrementAndGet();
                                return null;
                            };
                    assertThat(ReadRequestMemo.once("empty", empty)).isNull();
                    assertThat(ReadRequestMemo.once("empty", empty)).isNull();
                    assertThat(calls).hasValue(1);
                    Supplier<String> failing =
                            () -> {
                                if (calls.incrementAndGet() == 2)
                                    throw new IllegalStateException("第一次装载失败");
                                return "ok";
                            };
                    assertThatThrownBy(() -> ReadRequestMemo.once("failing", failing))
                            .hasMessage("第一次装载失败");
                    assertThat(ReadRequestMemo.once("failing", failing)).isEqualTo("ok");
                    assertThat(ReadRequestMemo.once("failing", failing)).isEqualTo("ok");
                    assertThat(calls).hasValue(3);
                    return null;
                });
    }

    @Test
    void eachTransactionLoadsOnceAndNeverSeesOtherSegments() {
        ReadRequestMemo.within(
                () -> {
                    // 无事务段读到的值。
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    tx().executeWithoutResult(
                                    status -> {
                                        // 事务内不使用无事务段的值：本事务必须自己装载一次（锁至少取一次）。
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v2");
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v2");
                                        // 加入同一事务的内层模板共用同一份。
                                        tx().executeWithoutResult(
                                                        inner ->
                                                                assertThat(
                                                                                ReadRequestMemo
                                                                                        .once(
                                                                                                "k",
                                                                                                loader))
                                                                        .isEqualTo("v2"));
                                    });
                    // 事务结束后，事务内的值不外泄；无事务段仍是自己的值。
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    // 下一个事务重新装载。
                    tx().executeWithoutResult(
                                    status ->
                                            assertThat(ReadRequestMemo.once("k", loader))
                                                    .isEqualTo("v3"));
                    return null;
                });
        assertThat(loads).hasValue(3);
    }

    /** 同一事务里先后开启的两个作用域（写入流程中先后两次只读调用）互不继承：后一个不能读到前一个留在事务上的值。 */
    @Test
    void laterScopeInTheSameTransactionStartsFresh() {
        tx().executeWithoutResult(
                        status -> {
                            ReadRequestMemo.within(
                                    () -> {
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v1");
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v1");
                                        return null;
                                    });
                            // 两个作用域之间事务可以写入；作用域外不记忆。
                            assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v2");
                            ReadRequestMemo.within(
                                    () -> {
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v3");
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v3");
                                        return null;
                                    });
                        });
    }

    @Test
    void suspendedTransactionKeepsItsOwnValues() {
        ReadRequestMemo.within(
                () -> {
                    tx().executeWithoutResult(
                                    outer -> {
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v1");
                                        tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                                                .executeWithoutResult(
                                                        inner -> {
                                                            // 新事务看不到被挂起事务的值，需要在自己的连接上重新取锁、读取。
                                                            assertThat(
                                                                            ReadRequestMemo.once(
                                                                                    "k", loader))
                                                                    .isEqualTo("v2");
                                                            assertThat(
                                                                            ReadRequestMemo.once(
                                                                                    "k", loader))
                                                                    .isEqualTo("v2");
                                                        });
                                        // 外层事务恢复后沿用自己的值，不被内层覆盖。
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v1");
                                    });
                    return null;
                });
        assertThat(loads).hasValue(2);
    }

    @Test
    void savepointRollbackForgetsTransactionValues() {
        ReadRequestMemo.within(
                () -> {
                    tx().executeWithoutResult(
                                    outer -> {
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v1");
                                        tx(TransactionDefinition.PROPAGATION_NESTED)
                                                .executeWithoutResult(
                                                        nested -> {
                                                            assertThat(
                                                                            ReadRequestMemo.once(
                                                                                    "k", loader))
                                                                    .isEqualTo("v1");
                                                            assertThat(
                                                                            ReadRequestMemo.once(
                                                                                    "inside",
                                                                                    loader))
                                                                    .isEqualTo("v2");
                                                            nested.setRollbackOnly();
                                                        });
                                        // 回滚到保存点会释放其后取得的锁；记忆整体作废，后续读取重新装载。
                                        assertThat(ReadRequestMemo.once("inside", loader))
                                                .isEqualTo("v3");
                                        assertThat(ReadRequestMemo.once("k", loader))
                                                .isEqualTo("v4");
                                    });
                    return null;
                });
    }

    @Test
    void lockKeysAreRememberedOnlyInsideTransaction() {
        ReadRequestMemo.within(
                () -> {
                    // 没有事务时事务级锁不会保留，每次都执行。
                    assertThat(ReadRequestMemo.onceInTransaction("lock", loader)).isEqualTo("v1");
                    assertThat(ReadRequestMemo.onceInTransaction("lock", loader)).isEqualTo("v2");
                    tx().executeWithoutResult(
                                    status -> {
                                        assertThat(
                                                        ReadRequestMemo.onceInTransaction(
                                                                "lock", loader))
                                                .isEqualTo("v3");
                                        assertThat(
                                                        ReadRequestMemo.onceInTransaction(
                                                                "lock", loader))
                                                .isEqualTo("v3");
                                    });
                    tx().executeWithoutResult(
                                    status ->
                                            assertThat(
                                                            ReadRequestMemo.onceInTransaction(
                                                                    "lock", loader))
                                                    .isEqualTo("v4"));
                    return null;
                });
    }

    @Test
    void seedRegistersAnEquivalentKeyWithoutOverwriting() {
        ReadRequestMemo.within(
                () -> {
                    ReadRequestMemo.seed("alias", "seeded");
                    assertThat(ReadRequestMemo.once("alias", loader)).isEqualTo("seeded");
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    ReadRequestMemo.seed("k", "late");
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    tx().executeWithoutResult(
                                    status -> {
                                        // 登记位置与读取位置一致：事务内登记的只在本事务可见。
                                        assertThat(ReadRequestMemo.once("alias", loader))
                                                .isEqualTo("v2");
                                        ReadRequestMemo.seed("inTx", "seeded-in-tx");
                                        assertThat(ReadRequestMemo.once("inTx", loader))
                                                .isEqualTo("seeded-in-tx");
                                    });
                    assertThat(ReadRequestMemo.once("inTx", loader)).isEqualTo("v3");
                    return null;
                });
    }

    @Test
    void nestedScopeSharesTheOuterOneAndExitAlwaysCleans() {
        ReadRequestMemo.within(
                () -> {
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    ReadRequestMemo.within(
                            () -> {
                                assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                                return null;
                            });
                    // 内层退出不关闭外层作用域。
                    assertThat(ReadRequestMemo.active()).isTrue();
                    assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                    return null;
                });
        assertThatThrownBy(
                        () ->
                                ReadRequestMemo.within(
                                        () -> {
                                            throw new IllegalStateException("请求失败");
                                        }))
                .hasMessage("请求失败");
        // 线程会被连接池复用：异常退出也必须清理，否则下一个请求会读到上一个请求的值。
        assertThat(ReadRequestMemo.active()).isFalse();
        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v2");
        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v3");
    }

    @Test
    void scopeIsNotVisibleToOtherThreads() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ReadRequestMemo.within(
                    () -> {
                        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                        try {
                            // 另一个线程没有作用域：既读不到本请求的值，也不会把值留给本请求。
                            assertThat(
                                            worker.submit(ReadRequestMemo::active)
                                                    .get(10, TimeUnit.SECONDS))
                                    .isFalse();
                            assertThat(
                                            worker.submit(() -> ReadRequestMemo.once("k", loader))
                                                    .get(10, TimeUnit.SECONDS))
                                    .isEqualTo("v2");
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                        assertThat(ReadRequestMemo.once("k", loader)).isEqualTo("v1");
                        return null;
                    });
        } finally {
            worker.shutdownNow();
        }
    }
}
