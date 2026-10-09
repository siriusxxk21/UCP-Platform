package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.web.live.RecordLiveHub;
import com.richuang.os.nocode.web.live.RecordLiveTopicListener;
import com.richuang.os.nocode.web.live.RecordsChanged;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分发器的纯逻辑（契约 5.2、5.4、6.2–6.5；任务书 U1–U9）：不起线程、不连库，由测试调用 pump() 推进一个分发周期；订阅经真实的主题监听器
 * 建立（授权用替身），事件流在调用线程上直接消费。
 */
class RecordLiveHubTest {
    private static final String LEDGER = "3057";
    private static final String OTHER = "4001";

    private final AtomicReference<String> origin = new AtomicReference<>();
    private RecordLiveHub hub;
    private RecordLiveTopicListener listener;
    private List<ApplicationAuthorization.ObjectGrant> grants;

    /** 一个经监听器建立的订阅：收到的帧与退订句柄。 */
    private record Subscribed(List<RecordsChanged> frames, Disposable handle) {}

    @BeforeEach
    void setup() {
        hub = new RecordLiveHub(true, null, origin::get, false);
        grants = List.of(readAll(LEDGER));
        listener =
                new RecordLiveTopicListener(
                        hub, (application, object, actor) -> grants, Schedulers.immediate());
    }

    private Subscribed subscribe(Connection connection, String application, String object) {
        List<RecordsChanged> frames = new CopyOnWriteArrayList<>();
        Disposable handle =
                listener.subscribe(
                                connection.session,
                                "nocode.records." + application + "." + object,
                                null,
                                null)
                        .events()
                        .subscribe(event -> frames.add((RecordsChanged) event.data()));
        return new Subscribed(frames, handle);
    }

    @Test
    void u1_sameObjectReachesEveryApplicationsSubscribersAndNoOtherObject() {
        Subscribed finance = subscribe(Connection.admin(1), "11", LEDGER);
        Subscribed reporting = subscribe(Connection.admin(2), "22", LEDGER);
        Subscribed unrelated = subscribe(Connection.admin(3), "11", OTHER);
        hub.committed(batch(created(LEDGER, "9001")));
        hub.pump();
        assertThat(finance.frames()).as("应用 11 里订了这个对象").hasSize(1);
        assertThat(reporting.frames()).as("另一个应用里订了同一个对象，同样收到").hasSize(1);
        assertThat(reporting.frames().getFirst().created()).containsExactly("9001");
        assertThat(unrelated.frames()).as("别的对象的订阅收不到").isEmpty();
    }

    @Test
    void u2_threeChangesInOneCycleBecomeOneMergedFrame() {
        Subscribed viewer = subscribe(Connection.admin(1), "11", LEDGER);
        hub.committed(batch(created(LEDGER, "9001")));
        hub.committed(batch(updated(LEDGER, "88", "89")));
        hub.committed(batch(deleted(LEDGER, "77"), updated(OTHER, "5")));
        hub.pump();
        assertThat(viewer.frames()).as("一个分发周期只发一帧").hasSize(1);
        RecordsChanged frame = viewer.frames().getFirst();
        assertThat(frame.objectId()).isEqualTo(LEDGER);
        assertThat(frame.kind()).isEqualTo("ids");
        assertThat(frame.many()).isFalse();
        assertThat(frame.created()).containsExactly("9001");
        assertThat(frame.updated()).containsExactly("88", "89");
        assertThat(frame.deleted()).containsExactly("77");
        assertThat(frame.fromSeq()).as("覆盖三份变更的序号").isEqualTo(1);
        assertThat(frame.seq()).isEqualTo(3);
        assertThat(frame.epoch()).isEqualTo(hub.epoch()).matches("[a-z0-9]{6,12}");
    }

    @Test
    void u3_mergedBeyondTheCapBecomesObjectWithMany() {
        Subscribed viewer = subscribe(Connection.admin(1), "11", LEDGER);
        for (int part = 0; part < 3; part++) {
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < 70; index++) ids.add("r" + part + "-" + index);
            hub.committed(
                    batch(
                            new RecordChangeBatch.ObjectChange(
                                    LEDGER, false, List.of(), ids, List.of())));
        }
        hub.pump();
        assertThat(viewer.frames()).hasSize(1);
        RecordsChanged frame = viewer.frames().getFirst();
        assertThat(frame.kind()).isEqualTo("object");
        assertThat(frame.many()).isTrue();
        assertThat(frame.created()).isEmpty();
        assertThat(frame.updated()).isEmpty();
        assertThat(frame.deleted()).isEmpty();
        assertThat(frame.fromSeq()).isEqualTo(1);
        assertThat(frame.seq()).isEqualTo(3);
    }

    @Test
    void u4_originIsCarriedOnlyWhenTheWholeCycleCameFromOneInitiator() {
        Subscribed viewer = subscribe(Connection.admin(1), "11", LEDGER);
        origin.set("0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44");
        hub.committed(batch(updated(LEDGER, "88")));
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        assertThat(viewer.frames().getLast().origin())
                .as("周期内全部来自同一个发起人")
                .isEqualTo("0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44");

        hub.committed(batch(updated(LEDGER, "88")));
        origin.set("ffffffff-5d0b-4e8a-9a51-3f6a7c2d1e44");
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        assertThat(viewer.frames().getLast().origin()).as("混入另一个发起人").isNull();

        hub.committed(batch(updated(LEDGER, "88")));
        origin.set(null);
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        assertThat(viewer.frames().getLast().origin()).as("混入没有发起人的变更").isNull();

        origin.set("<script>alert(1)</script>");
        hub.committed(batch(updated(LEDGER, "88")));
        hub.pump();
        assertThat(viewer.frames().getLast().origin()).as("格式不符的请求头当作没有").isNull();
        assertThat(viewer.frames()).hasSize(4);
    }

    @Test
    void u5_consecutiveFramesAreContiguousAndShareTheEpoch() {
        Subscribed viewer = subscribe(Connection.admin(1), "11", LEDGER);
        hub.committed(batch(updated(LEDGER, "1")));
        hub.committed(batch(updated(LEDGER, "2")));
        hub.pump();
        hub.committed(batch(updated(LEDGER, "3")));
        hub.pump();
        // 别的对象的变更不占这个对象的序号。
        hub.committed(batch(updated(OTHER, "9")));
        hub.committed(batch(updated(LEDGER, "4")));
        hub.pump();
        assertThat(viewer.frames()).hasSize(3);
        for (int index = 1; index < viewer.frames().size(); index++) {
            RecordsChanged previous = viewer.frames().get(index - 1);
            RecordsChanged next = viewer.frames().get(index);
            assertThat(next.fromSeq()).as("下一帧接续上一帧").isEqualTo(previous.seq() + 1);
            assertThat(next.epoch()).isEqualTo(previous.epoch());
        }
        assertThat(viewer.frames().getLast().seq()).isEqualTo(4);
        // 没有订阅者的时候序号照常前进：后来的订阅者第一帧不从 1 开始。
        Subscribed late = subscribe(Connection.admin(2), "11", LEDGER);
        hub.committed(batch(updated(LEDGER, "5")));
        hub.pump();
        assertThat(late.frames().getFirst().fromSeq()).isEqualTo(5);
    }

    @Test
    void u6_subscriberWithoutIdVisibilityGetsObjectFramesWhileOthersGetIds() {
        Subscribed full = subscribe(Connection.admin(1), "11", LEDGER);
        grants = List.of(grant(LEDGER, "OWN", Set.of("READ"), Map.of()));
        Subscribed own = subscribe(Connection.admin(2), "11", LEDGER);
        hub.committed(batch(created(LEDGER, "9001")));
        hub.pump();
        assertThat(full.frames().getFirst().kind()).isEqualTo("ids");
        assertThat(full.frames().getFirst().created()).containsExactly("9001");
        RecordsChanged hidden = own.frames().getFirst();
        assertThat(hidden.kind()).as("只能看自己创建的记录 ⇒ 不给记录标识").isEqualTo("object");
        assertThat(hidden.created()).isEmpty();
        assertThat(hidden.updated()).isEmpty();
        assertThat(hidden.deleted()).isEmpty();
        assertThat(hidden.many()).as("不是因为量大").isFalse();
        assertThat(hidden.seq()).isEqualTo(full.frames().getFirst().seq());

        hub.committed(batch(many(LEDGER)));
        hub.pump();
        assertThat(own.frames().getLast().many()).as("量大的那一份 many 照实").isTrue();
        assertThat(full.frames().getLast().many()).isTrue();
    }

    @Test
    void u7_rejectedDeliveryIsKeptAsObjectAndTheNextDeliveredFrameFollowsTheCatchUpRule() {
        Sink slow = new Sink();
        Sink healthy = new Sink();
        hub.subscribe(new Object(), LEDGER, true, slow);
        hub.subscribe(new Object(), LEDGER, true, healthy);
        slow.accepting = false;
        hub.committed(batch(updated(LEDGER, "88")));
        hub.pump();
        assertThat(slow.frames).as("交付不进去").isEmpty();
        assertThat(healthy.frames).as("别的订阅不受影响").hasSize(1);
        hub.committed(batch(created(LEDGER, "9001")));
        hub.pump();
        assertThat(slow.frames).isEmpty();
        slow.accepting = true;
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        assertThat(slow.frames).as("恢复后只补一帧").hasSize(1);
        RecordsChanged catchUp = slow.frames.getFirst();
        assertThat(catchUp.kind()).isEqualTo("object");
        assertThat(catchUp.many()).isFalse();
        assertThat(catchUp.created()).as("落后补发不带任何记录标识").isEmpty();
        assertThat(catchUp.updated()).isEmpty();
        assertThat(catchUp.deleted()).isEmpty();
        assertThat(catchUp.origin()).isNull();
        assertThat(catchUp.fromSeq()).as("序号取该对象当前值").isEqualTo(3);
        assertThat(catchUp.seq()).isEqualTo(3);
        // 补上之后恢复正常：逐条列出、序号接续。
        hub.committed(batch(updated(LEDGER, "90")));
        hub.pump();
        assertThat(slow.frames.getLast().kind()).isEqualTo("ids");
        assertThat(slow.frames.getLast().updated()).containsExactly("90");
        assertThat(slow.frames.getLast().fromSeq()).isEqualTo(4);
        // 没有新变更时，落后的订阅在下一轮也会被补发（不必等下一次变更）。
        slow.accepting = false;
        hub.committed(batch(updated(LEDGER, "91")));
        hub.pump();
        slow.accepting = true;
        hub.pump();
        assertThat(slow.frames.getLast().kind()).isEqualTo("object");
        assertThat(slow.frames.getLast().seq()).isEqualTo(5);
    }

    @Test
    void u8_unsubscribedOrClosedSubscriptionsLeaveTheHub() {
        Connection connection = Connection.admin(1);
        Subscribed viewer = subscribe(connection, "11", LEDGER);
        Subscribed second = subscribe(connection, "11", OTHER);
        assertThat(hub.count(connection.session)).isEqualTo(2);
        viewer.handle().dispose();
        assertThat(hub.count(connection.session)).as("退订即注销").isEqualTo(1);
        hub.committed(batch(updated(LEDGER, "88"), updated(OTHER, "5")));
        hub.pump();
        assertThat(viewer.frames()).as("退订后不再收到").isEmpty();
        assertThat(second.frames()).hasSize(1);
        // 连接已关闭但事件流没有被取消（订阅建立后从未激活）：下一轮清掉。
        Sink orphan = new Sink();
        Object orphanConnection = new Object();
        hub.subscribe(orphanConnection, LEDGER, true, orphan);
        orphan.open = false;
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        assertThat(orphan.frames).isEmpty();
        assertThat(hub.count(orphanConnection)).isZero();
    }

    @Test
    void u9_queueOverflowMakesEverySubscriptionRefreshOnce() {
        Subscribed ledger = subscribe(Connection.admin(1), "11", LEDGER);
        Subscribed other = subscribe(Connection.admin(2), "11", OTHER);
        for (int index = 0; index <= RecordLiveHub.QUEUE_CAPACITY; index++)
            hub.committed(batch(updated(LEDGER, Integer.toString(index % 7))));
        hub.pump();
        for (Subscribed subscribed : List.of(ledger, other)) {
            assertThat(subscribed.frames()).as("溢出后每个订阅各补一帧").hasSize(1);
            RecordsChanged frame = subscribed.frames().getFirst();
            assertThat(frame.kind()).isEqualTo("object");
            assertThat(frame.many()).isFalse();
            assertThat(frame.updated()).isEmpty();
        }
        assertThat(ledger.frames().getFirst().seq()).isEqualTo(RecordLiveHub.QUEUE_CAPACITY);
        hub.committed(batch(updated(LEDGER, "88")));
        hub.pump();
        assertThat(ledger.frames().getLast().kind()).as("溢出标志只生效一轮").isEqualTo("ids");
        assertThat(other.frames()).hasSize(1);
    }
}
