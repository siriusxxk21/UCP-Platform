package com.richuang.os.nocode.metadata.service.request;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 只读请求内的记忆化：同一次请求里对同一份发布定义、授权上限、物理表结构的重复读取只执行一次。
 *
 * <p>只有运行端只读接口显式开启作用域后才生效；未开启时所有方法直接执行装载器，保存、删除、发布等写入路径的锁次数、锁顺序和读取时机保持原样。
 *
 * <p>记忆按事务分段：调用时已有事务，值只记在这个事务名下（事务挂起、结束或回滚到保存点后不再可见），因此共享锁在每个事务里仍然至少取得一次，
 * 之后的重复读取才被省略——锁一直持有到事务结束，被锁住的发布指针在此期间不会变化，重复读取的结果必然相同。调用时没有事务，值记在请求名下，
 * 只供同一请求里其它无事务的调用使用；这些调用原本各自开启的短事务在返回时就已释放锁，本就不为后续读取提供保护。事务内的调用从不读取请求名下的值。
 *
 * <p>不跨请求、不跨线程；装载器抛出异常时不记值。记住的对象在请求内共享，调用方只读不改。
 */
public final class ReadRequestMemo {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Object NULL = new Object();

    private ReadRequestMemo() {}

    /** 一次只读请求的记忆；事务段各有自己的表，随事务同步器的生命周期结束。 */
    private static final class Scope {
        private final Map<Object, Object> detached = new HashMap<>();
    }

    /** 挂在当前事务上的记忆表。事务挂起时同步器随之离开线程，新事务看不到；结束或保存点回滚后清空。 */
    private static final class TransactionValues implements TransactionSynchronization {
        private final Scope scope;
        private final Map<Object, Object> values = new HashMap<>();

        private TransactionValues(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void savepointRollback(Object savepoint) {
            // 保存点之后取得的锁随回滚释放，保守地丢弃整张表，下一次读取重新取锁。
            values.clear();
        }

        @Override
        public void afterCompletion(int status) {
            values.clear();
        }
    }

    /** 开启只读作用域；已在作用域内时沿用外层，退出最外层时清理。只给不写元数据、不改表结构的只读入口使用。 */
    public static <T> T within(Supplier<T> action) {
        if (CURRENT.get() != null) return action.get();
        CURRENT.set(new Scope());
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** 由若干部分组成的记忆键；部分可为空，按值比较。 */
    public static Object key(Object... parts) {
        return Arrays.asList(parts.clone());
    }

    /** 同一事务内（无事务时为同一请求的无事务段内）同一个键只装载一次；作用域未开启时每次都装载。 */
    public static <T> T once(Object key, Supplier<T> loader) {
        Scope scope = CURRENT.get();
        if (scope == null) return loader.get();
        Map<Object, Object> values = values(scope, true);
        if (values == null) return loader.get();
        return remembered(values, key, loader);
    }

    /** 只在事务内记忆，用于事务级锁：没有事务时锁不会保留，每次都执行。 */
    public static <T> T onceInTransaction(Object key, Supplier<T> loader) {
        Scope scope = CURRENT.get();
        if (scope == null) return loader.get();
        Map<Object, Object> values = values(scope, false);
        if (values == null) return loader.get();
        return remembered(values, key, loader);
    }

    /** 把已读到的值登记到另一个等价的键下（已有值时不覆盖）；登记位置与 once 相同，作用域未开启时不做任何事。 */
    public static void seed(Object key, Object value) {
        Scope scope = CURRENT.get();
        if (scope == null) return;
        Map<Object, Object> values = values(scope, true);
        if (values != null) values.putIfAbsent(key, value == null ? NULL : value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T remembered(Map<Object, Object> values, Object key, Supplier<T> loader) {
        Object known = values.get(key);
        if (known != null) return known == NULL ? null : (T) known;
        T loaded = loader.get();
        values.put(key, loaded == null ? NULL : loaded);
        return loaded;
    }

    /** 当前事务的记忆表；没有事务时按参数返回请求名下的表或空。事务存在但不允许登记同步器时不记忆。 */
    private static Map<Object, Object> values(Scope scope, boolean detachedAllowed) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            return detachedAllowed ? scope.detached : null;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof TransactionValues current && current.scope == scope)
                return current.values;
        TransactionValues created = new TransactionValues(scope);
        TransactionSynchronizationManager.registerSynchronization(created);
        return created.values;
    }
}
