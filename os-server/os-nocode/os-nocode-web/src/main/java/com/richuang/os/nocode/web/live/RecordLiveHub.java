package com.richuang.os.nocode.web.live;

import com.richuang.os.framework.websocket.core.listener.WebSocketEventMessage;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.runtime.service.live.RecordChangeListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 记录变更分发器：登记簿在事务提交后把变更交到这里，这里只入队就返回；分发线程按对象并入各订阅的待发状态，每个周期给每个订阅至多发一帧， 并把本进程提交的变更转给别的进程。
 *
 * <p>三条线互不拖累：提交事务的线程只入队（队列满则置溢出标志，不阻塞、不抛出）；分发线程只做内存合并与「放进订阅的出口」，不做任何网络
 * 发送；每个订阅的出口由别的线程消费，一个写不动的连接只堵住它自己。
 */
public class RecordLiveHub implements RecordChangeListener {
    public static final String EVENT = "records.changed";
    public static final String ORIGIN_HEADER = "X-Realtime-Client";

    /** 每个连接最多持有的本类订阅数。 */
    public static final int MAX_SUBSCRIPTIONS = 50;

    public static final int QUEUE_CAPACITY = 10_000;
    public static final long FLUSH_MILLIS = 300;
    public static final long SHUTDOWN_MILLIS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(RecordLiveHub.class);
    private static final Pattern ORIGIN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final String EPOCH_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** 一个订阅的出口：分发线程把一帧放进去；放不进去（上一帧还没被取走）返回 false。实现不得阻塞。 */
    public interface Outlet {
        boolean offer(WebSocketEventMessage frame);

        /** 连接是否还在；不在了的订阅在下一轮被清掉。 */
        default boolean open() {
            return true;
        }
    }

    /** 把本进程提交的变更转给别的进程；实现自行吞掉失败。 */
    public interface Relay {
        void publish(RecordChangeBatch batch, String origin);
    }

    /** 一个连接对一个对象的订阅。 */
    public static final class Subscription {
        private final Object connection;
        private final String objectId;
        private final Outlet outlet;
        private final RecordLivePending pending;

        private Subscription(
                Object connection, String objectId, boolean idsVisible, Outlet outlet) {
            this.connection = connection;
            this.objectId = objectId;
            this.outlet = outlet;
            this.pending = new RecordLivePending(idsVisible);
        }
    }

    private record Entry(RecordChangeBatch batch, String origin, boolean local) {}

    private final boolean enabled;
    private final Relay relay;
    private final Supplier<String> origins;
    private final boolean threaded;
    private final String epoch = newEpoch();
    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final Map<String, List<Subscription>> byObject = new ConcurrentHashMap<>();
    private final Map<Object, Set<Subscription>> byConnection = new ConcurrentHashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final Object lifecycle = new Object();
    private volatile boolean closing;
    private volatile Thread thread;

    /** 正式装配：发起人标识取当前请求头，分发线程在第一次入队时创建。 */
    public RecordLiveHub(boolean enabled, Relay relay) {
        this(enabled, relay, RecordLiveHub::requestOrigin, true);
    }

    /** threaded 为假时不起线程，由调用方调用 {@link #pump()} 推进（纯逻辑测试用）。 */
    public RecordLiveHub(boolean enabled, Relay relay, Supplier<String> origins, boolean threaded) {
        this.enabled = enabled;
        this.relay = relay;
        this.origins = origins;
        this.threaded = threaded;
    }

    public boolean enabled() {
        return enabled;
    }

    public String epoch() {
        return epoch;
    }

    /** 在提交事务的线程上被调用：读发起人标识、入队、立即返回；任何情况下不阻塞、不抛出。 */
    @Override
    public void committed(RecordChangeBatch batch) {
        if (!enabled) return;
        try {
            enqueue(new Entry(batch, normalize(origins.get()), true));
        } catch (Throwable error) {
            log.warn("记录变更入队失败，已忽略", error);
        }
    }

    /** 别的进程转来的变更：只入队，分发给本地订阅，不再转发。 */
    public void accept(RecordChangeBatch batch, String origin) {
        if (!enabled) return;
        try {
            enqueue(new Entry(batch, normalize(origin), false));
        } catch (Throwable error) {
            log.warn("转发来的记录变更入队失败，已忽略", error);
        }
    }

    private void enqueue(Entry entry) {
        if (closing || entry.batch() == null || entry.batch().objects().isEmpty()) return;
        if (!queue.offer(entry)) overflow.set(true);
        if (threaded && thread == null) start();
    }

    private void start() {
        synchronized (lifecycle) {
            if (thread != null || closing) return;
            Thread worker = new Thread(this::run, "nocode-live");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
        }
    }

    /** 本连接已持有的本类订阅数。 */
    public int count(Object connection) {
        Set<Subscription> held = byConnection.get(connection);
        return held == null ? 0 : held.size();
    }

    /**
     * 登记一个订阅；之后该对象的变更会并入它的待发状态。同一连接超过上限时抛出 IllegalStateException。
     *
     * @param idsVisible 这个订阅能不能拿到记录标识；为假时每一帧都只说「整体刷新」
     */
    public Subscription subscribe(
            Object connection, String objectId, boolean idsVisible, Outlet outlet) {
        Subscription subscription = new Subscription(connection, objectId, idsVisible, outlet);
        byConnection.compute(
                connection,
                (key, held) -> {
                    Set<Subscription> next = held == null ? ConcurrentHashMap.newKeySet() : held;
                    if (next.size() >= MAX_SUBSCRIPTIONS)
                        throw new IllegalStateException("订阅数超过上限");
                    next.add(subscription);
                    return next;
                });
        byObject.computeIfAbsent(objectId, key -> new CopyOnWriteArrayList<>()).add(subscription);
        return subscription;
    }

    /** 退订或连接关闭：之后的变更不再为它生成待发状态。可重复调用。 */
    public void unsubscribe(Subscription subscription) {
        if (subscription == null) return;
        byObject.computeIfPresent(
                subscription.objectId,
                (key, list) -> {
                    list.remove(subscription);
                    return list.isEmpty() ? null : list;
                });
        byConnection.computeIfPresent(
                subscription.connection,
                (key, held) -> {
                    held.remove(subscription);
                    return held.isEmpty() ? null : held;
                });
    }

    /** 处理完队列里现有的全部变更并发一轮。只在不起线程的模式下由调用方使用。 */
    public void pump() {
        drain();
        flush();
    }

    private void run() {
        long nextFlush = 0;
        while (true) {
            Entry entry = null;
            try {
                if (closing) break;
                if (nextFlush == 0) entry = queue.take();
                else
                    entry =
                            queue.poll(
                                    Math.max(1, nextFlush - System.currentTimeMillis()),
                                    TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                // 关闭时用中断唤醒；循环顶部看到 closing 后退出，剩余的由 shutdown 收尾。
                continue;
            }
            try {
                if (entry != null) {
                    List<Entry> local = new ArrayList<>();
                    apply(entry, local);
                    drain(local);
                    if (nextFlush == 0) nextFlush = System.currentTimeMillis() + FLUSH_MILLIS;
                    forward(local);
                }
                if (overflow.get() && nextFlush == 0)
                    nextFlush = System.currentTimeMillis() + FLUSH_MILLIS;
                if (nextFlush != 0 && System.currentTimeMillis() >= nextFlush)
                    nextFlush = flush() ? System.currentTimeMillis() + FLUSH_MILLIS : 0;
            } catch (Throwable error) {
                log.warn("记录变更分发出错，已忽略", error);
            }
        }
        // 清掉关闭时用来唤醒的中断标记，收尾的转发不被它打断。
        Thread.interrupted();
        try {
            pump();
        } catch (Throwable error) {
            log.warn("记录变更分发收尾出错，已忽略", error);
        }
    }

    private void drain() {
        List<Entry> local = new ArrayList<>();
        drain(local);
        forward(local);
    }

    private void drain(List<Entry> local) {
        Entry next;
        while ((next = queue.poll()) != null) apply(next, local);
    }

    /** 给批里每个对象取下一个序号，并入该对象下每个订阅的待发状态。 */
    private void apply(Entry entry, List<Entry> local) {
        for (RecordChangeBatch.ObjectChange change : entry.batch().objects()) {
            long sequence = sequences.merge(change.objectId(), 1L, Long::sum);
            List<Subscription> subscriptions = byObject.get(change.objectId());
            if (subscriptions == null) continue;
            for (Subscription subscription : subscriptions)
                subscription.pending.merge(change, sequence, entry.origin());
        }
        if (entry.local()) local.add(entry);
    }

    /** 本地合并之后再转发：转发慢或失败不耽误本地订阅拿到这一轮。 */
    private void forward(List<Entry> local) {
        if (relay == null) return;
        for (Entry entry : local) {
            try {
                relay.publish(entry.batch(), entry.origin());
            } catch (Throwable error) {
                log.warn("记录变更转发失败，已忽略", error);
            }
        }
    }

    /** 发一轮：每个有待发内容的订阅至多一帧。返回是否还有订阅落后、需要下一轮再试。 */
    private boolean flush() {
        boolean resync = overflow.getAndSet(false);
        boolean retry = false;
        for (Map.Entry<String, List<Subscription>> object : byObject.entrySet()) {
            long current = sequences.getOrDefault(object.getKey(), 0L);
            for (Subscription subscription : object.getValue()) {
                boolean open;
                try {
                    open = subscription.outlet.open();
                } catch (Throwable error) {
                    open = false;
                }
                if (!open) {
                    unsubscribe(subscription);
                    continue;
                }
                if (resync) subscription.pending.fallBehind();
                RecordsChanged data = subscription.pending.frame(object.getKey(), epoch, current);
                if (data == null) continue;
                boolean accepted;
                try {
                    accepted = subscription.outlet.offer(WebSocketEventMessage.now(EVENT, data));
                } catch (Throwable error) {
                    accepted = false;
                }
                if (accepted) {
                    subscription.pending.delivered();
                } else {
                    subscription.pending.fallBehind();
                    retry = true;
                }
            }
        }
        return retry;
    }

    /** 容器关闭：停止接收，把队列里剩余的处理完并完成一轮发送与转发，最长等 2 秒。 */
    public void close() {
        Thread worker;
        synchronized (lifecycle) {
            if (closing) return;
            closing = true;
            worker = thread;
        }
        if (worker == null) {
            if (threaded) {
                try {
                    pump();
                } catch (Throwable error) {
                    log.warn("记录变更分发收尾出错，已忽略", error);
                }
            }
            return;
        }
        worker.interrupt();
        try {
            worker.join(SHUTDOWN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) log.warn("记录变更分发线程未在 {} 毫秒内结束", SHUTDOWN_MILLIS);
    }

    private static String normalize(String origin) {
        return origin != null && ORIGIN.matcher(origin).matches() ? origin : null;
    }

    /** 当前线程绑定的 HTTP 请求里的发起人标识；没有请求时为 null。 */
    private static String requestOrigin() {
        return RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes request
                ? request.getRequest().getHeader(ORIGIN_HEADER)
                : null;
    }

    private static String newEpoch() {
        SecureRandom random = new SecureRandom();
        StringBuilder text = new StringBuilder(8);
        for (int index = 0; index < 8; index++)
            text.append(EPOCH_ALPHABET.charAt(random.nextInt(EPOCH_ALPHABET.length())));
        return text.toString();
    }
}
