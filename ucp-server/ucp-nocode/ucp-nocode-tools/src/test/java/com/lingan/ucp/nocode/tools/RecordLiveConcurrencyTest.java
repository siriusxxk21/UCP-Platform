package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.websocket.core.listener.WebSocketEventMessage;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.web.live.RecordLiveConfiguration;
import com.lingan.ucp.nocode.web.live.RecordLiveHub;
import com.lingan.ucp.nocode.web.live.RecordLiveTopicListener;
import com.lingan.ucp.nocode.web.live.RecordsChanged;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 线程（契约 5.1、5.3、5.5；任务书 C1、C2、C4、C5）：真实的分发线程与发送线程。提交线程入队即返回、一个写不动的连接只堵住自己、
 * 关闭时把剩余的处理完。等待一律用闩锁或带上限的阻塞取，不用睡眠计时。（C3 需要真实事务与数据库，在 RecordLiveHooksIntegrationTest 里。）
 */
class RecordLiveConcurrencyTest {
    private static final String LEDGER = "3057";
    private static final long WAIT_SECONDS = 20;

    private final List<RecordLiveHub> hubs = new ArrayList<>();
    private final RecordLiveConfiguration.Senders senders = new RecordLiveConfiguration.Senders();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void release() {
        gates.forEach(CountDownLatch::countDown);
        hubs.forEach(RecordLiveHub::close);
        senders.destroy();
    }

    private RecordLiveHub hub(RecordLiveHub.Relay relay) {
        RecordLiveHub hub = new RecordLiveHub(true, relay);
        hubs.add(hub);
        return hub;
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    @Test
    void c1_committedReturnsImmediatelyWhileTheDispatchThreadIsStuck() throws Exception {
        RecordLiveHub hub = hub(null);
        CountDownLatch dispatching = new CountDownLatch(1);
        CountDownLatch gate = gate();
        hub.subscribe(
                new Object(),
                LEDGER,
                true,
                frame -> {
                    // 人为把分发线程卡在出口上：真实出口从不阻塞，这里只为证明提交线程不等分发。
                    dispatching.countDown();
                    await(gate);
                    return true;
                });
        hub.committed(batch(updated(LEDGER, "1")));
        assertThat(dispatching.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("分发线程已卡在出口上").isTrue();
        CompletableFuture<Void> commits =
                CompletableFuture.runAsync(
                        () -> {
                            for (int index = 2; index <= 500; index++)
                                hub.committed(batch(updated(LEDGER, Integer.toString(index))));
                        });
        assertThatCode(() -> commits.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("分发线程卡住期间，提交线程上的 committed() 仍然立即返回")
                .doesNotThrowAnyException();
    }

    @Test
    void c2_aBlockedConnectionOnlyDelaysItselfAndThenCatchesUpWithOneObjectFrame()
            throws Exception {
        RecordLiveHub hub = hub(null);
        RecordLiveTopicListener listener =
                new RecordLiveTopicListener(
                        hub,
                        (application, object, actor) -> List.of(readAll(LEDGER)),
                        senders.scheduler());
        BlockingQueue<RecordsChanged> healthy = new LinkedBlockingQueue<>();
        List<RecordsChanged> blocked = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = gate();
        CountDownLatch caughtUp = new CountDownLatch(1);
        Disposable blockedHandle =
                listener.subscribe(
                                Connection.admin(1).session,
                                "nocode.records.11." + LEDGER,
                                null,
                                null)
                        .events()
                        .subscribe(
                                event -> {
                                    RecordsChanged data = (RecordsChanged) event.data();
                                    blocked.add(data);
                                    // 第一帧的发送被卡住：相当于这个连接写不动。
                                    if (blocked.size() == 1) {
                                        entered.countDown();
                                        await(gate);
                                    }
                                    if ("object".equals(data.kind())) caughtUp.countDown();
                                });
        Disposable healthyHandle =
                listener.subscribe(
                                Connection.admin(2).session,
                                "nocode.records.11." + LEDGER,
                                null,
                                null)
                        .events()
                        .subscribe(event -> healthy.add((RecordsChanged) event.data()));
        try {
            hub.committed(batch(updated(LEDGER, "1")));
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            RecordsChanged first = next(healthy);
            assertThat(first).as("一个连接的发送卡住时，另一个订阅照常收到这一帧（分发线程没有被写不动的连接占住）").isNotNull();
            assertThat(first.updated()).containsExactly("1");
            // 被卡住的连接卡着的时候，另一个连接照常逐帧收到后续变更。
            for (int index = 2; index <= 5; index++) {
                hub.committed(batch(updated(LEDGER, Integer.toString(index))));
                RecordsChanged frame = next(healthy);
                assertThat(frame).as("第 %s 次变更：另一个订阅照常收到", index).isNotNull();
                assertThat(frame.updated()).containsExactly(Integer.toString(index));
                assertThat(frame.seq()).isEqualTo(index);
            }
            assertThat(blocked).as("被卡住的连接此时只拿到了第一帧").hasSize(1);
            gate.countDown();
            assertThat(caughtUp.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("放开后收到一帧 object")
                    .isTrue();
            RecordsChanged catchUp = blocked.getLast();
            assertThat(catchUp.kind()).isEqualTo("object");
            assertThat(catchUp.many()).isFalse();
            assertThat(catchUp.updated()).isEmpty();
            assertThat(catchUp.seq()).isEqualTo(5);
            assertThat(catchUp.fromSeq()).isEqualTo(5);
        } finally {
            blockedHandle.dispose();
            healthyHandle.dispose();
        }
    }

    @Test
    void c4_closingDrainsTheQueueAndFinishesOneRoundOfDeliveryAndRelay() throws Exception {
        List<RecordChangeBatch> relayed = new CopyOnWriteArrayList<>();
        RecordLiveHub hub = hub((batch, origin) -> relayed.add(batch));
        Sink viewer = new Sink();
        hub.subscribe(new Object(), LEDGER, true, viewer);
        int total = 400;
        for (int index = 1; index <= total; index++)
            hub.committed(batch(updated(LEDGER, "r" + (index % 50))));
        long started = System.nanoTime();
        hub.close();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).as("关闭在 2 秒上限内完成").isLessThan(RecordLiveHub.SHUTDOWN_MILLIS + 1000);
        assertThat(relayed).as("关闭前队列里剩余的批全部完成转发").hasSize(total);
        assertThat(viewer.frames).as("关闭前完成一轮发送").isNotEmpty();
        assertThat(viewer.frames.getLast().seq()).as("发出的帧覆盖到最后一份变更").isEqualTo(total);
        // 关闭之后停止接收。
        hub.committed(batch(updated(LEDGER, "late")));
        assertThat(relayed).hasSize(total);
    }

    @Test
    void c5_thousandChangesReachTenSubscribersInOrderWithoutLoss() throws Exception {
        RecordLiveHub hub = hub(null);
        RecordLiveTopicListener listener =
                new RecordLiveTopicListener(
                        hub,
                        (application, object, actor) -> List.of(readAll(LEDGER)),
                        senders.scheduler());
        int subscribers = 10;
        int total = 1000;
        List<List<RecordsChanged>> received = new ArrayList<>();
        List<CountDownLatch> done = new ArrayList<>();
        List<Disposable> handles = new ArrayList<>();
        for (int index = 0; index < subscribers; index++) {
            List<RecordsChanged> frames = new CopyOnWriteArrayList<>();
            CountDownLatch finished = new CountDownLatch(1);
            received.add(frames);
            done.add(finished);
            handles.add(
                    listener.subscribe(
                                    Connection.admin(100 + index).session,
                                    "nocode.records." + (index + 1) + "." + LEDGER,
                                    null,
                                    null)
                            .events()
                            .subscribe(
                                    (WebSocketEventMessage event) -> {
                                        RecordsChanged data = (RecordsChanged) event.data();
                                        frames.add(data);
                                        if (data.seq() == total) finished.countDown();
                                    }));
        }
        try {
            for (int index = 1; index <= total; index++)
                hub.committed(batch(updated(LEDGER, "r" + (index % 30))));
            for (CountDownLatch finished : done)
                assertThat(finished.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("每个订阅都收到覆盖最后一份变更的帧")
                        .isTrue();
            for (List<RecordsChanged> frames : received) {
                long last = 0;
                for (RecordsChanged frame : frames) {
                    boolean catchUp =
                            "object".equals(frame.kind())
                                    && !frame.many()
                                    && frame.fromSeq() == frame.seq();
                    assertThat(frame.seq()).as("序号单调不减").isGreaterThanOrEqualTo(last);
                    if (!catchUp)
                        assertThat(frame.fromSeq()).as("不是落后补发的帧必须与上一帧首尾接续").isEqualTo(last + 1);
                    assertThat(frame.seq()).isGreaterThanOrEqualTo(frame.fromSeq());
                    assertThat(frame.objectId()).isEqualTo(LEDGER);
                    last = frame.seq();
                }
                assertThat(last).as("不丢：最后覆盖到第 1000 份").isEqualTo(total);
            }
        } finally {
            handles.forEach(Disposable::dispose);
        }
    }

    private static RecordsChanged next(BlockingQueue<RecordsChanged> queue)
            throws InterruptedException {
        return queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
