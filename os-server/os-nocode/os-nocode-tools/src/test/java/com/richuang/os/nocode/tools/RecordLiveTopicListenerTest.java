package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.websocket.core.listener.WebSocketSubscription;
import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.api.DataScope;
import com.richuang.os.nocode.web.live.RecordLiveHub;
import com.richuang.os.nocode.web.live.RecordLiveTopicListener;
import com.richuang.os.nocode.web.live.RecordsChanged;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主题监听器（契约第 4 章、6.1、6.3、6.4；任务书 U10–U14）：主题格式、谁能订、给不给记录标识、每连接上限。授权用替身。 */
class RecordLiveTopicListenerTest {
    private static final String TOPIC = "nocode.records.11.3057";

    private RecordLiveHub hub;
    private RecordLiveTopicListener listener;
    private List<ApplicationAuthorization.ObjectGrant> grants;
    private RuntimeException policyFailure;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setup() {
        hub = new RecordLiveHub(true, null, () -> null, false);
        grants = List.of(readAll("3057"));
        listener = listener(hub);
    }

    private RecordLiveTopicListener listener(RecordLiveHub target) {
        return new RecordLiveTopicListener(
                target,
                (application, object, actor) -> {
                    asked.add(application + "/" + object + "/" + actor);
                    if (policyFailure != null) throw policyFailure;
                    return grants;
                },
                Schedulers.immediate());
    }

    @Test
    void u10_onlyWellFormedTopicsAreClaimed() {
        assertThat(listener.getTopic()).isEqualTo("nocode.records.");
        assertThat(listener.supportsTopic("nocode.records.11.3057")).isTrue();
        assertThat(listener.supportsTopic("nocode.records.1.9223372036854775807")).isTrue();
        for (String topic :
                List.of(
                        "nocode.records.11.3057.9",
                        "nocode.records.11",
                        "nocode.records.11.",
                        "nocode.records..3057",
                        "nocode.records.app.3057",
                        "nocode.records.11.30a7",
                        "nocode.records.011.3057",
                        "nocode.records.11.03057",
                        "nocode.records.0.3057",
                        "nocode.records.11.12345678901234567890",
                        "nocode.records.11.3057 ",
                        "xnocode.records.11.3057",
                        "nocode.record.11.3057",
                        "user.notifications",
                        ""))
            assertThat(listener.supportsTopic(topic)).as("不认领：%s", topic).isFalse();
        assertThat(listener.supportsTopic(null)).isFalse();
    }

    @Test
    void u11_anonymousOrUnauthorizedSubscriptionsAreRejectedWithoutInternalDetails() {
        assertThatThrownBy(() -> subscribe(Connection.anonymous()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("实时更新需要登录");
        assertThatThrownBy(() -> subscribe(new Connection(7L, 1)))
                .as("不是管理端用户")
                .isInstanceOf(SecurityException.class)
                .hasMessage("实时更新需要登录");
        assertThat(asked).as("没登录不去查授权").isEmpty();

        grants = List.of();
        assertThatThrownBy(() -> subscribe(Connection.admin(7)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("没有此对象的查看权限");
        assertThat(asked).as("按主题里的应用、对象与连接上的用户去查").containsExactly("11/3057/7");

        grants = List.of(grant("3057", "ALL", Set.of("UPDATE"), Map.of()));
        assertThatThrownBy(() -> subscribe(Connection.admin(7)))
                .as("有授权但不含查看")
                .isInstanceOf(SecurityException.class)
                .hasMessage("没有此对象的查看权限");

        policyFailure = new IllegalStateException("应用 11 的发布版本表 nocode_application_version 不存在");
        assertThatThrownBy(() -> subscribe(Connection.admin(7)))
                .as("策略内部抛错也按无权处理，消息里不含内部信息")
                .isInstanceOf(SecurityException.class)
                .hasMessage("没有此对象的查看权限");
        assertThat(hub.count(new Object())).isZero();
    }

    @Test
    void u12_authorizedSubscriptionSucceedsWithoutRecovery() {
        Connection connection = Connection.admin(7);
        WebSocketSubscription subscription = subscribe(connection);
        assertThat(subscription.recovered()).isFalse();
        assertThat(subscription.headOffset()).isNull();
        assertThat(subscription.events()).isNotNull();
        assertThat(hub.count(connection.session)).isEqualTo(1);
        // 订阅命令里的 offset、data 被忽略。
        Connection other = Connection.admin(8);
        assertThat(
                        listener.subscribe(other.session, TOPIC, "42", Map.of("anything", 1))
                                .recovered())
                .isFalse();
    }

    @Test
    void u13_idsAreVisibleOnlyWithAnUnconditionalAllScopeReadGrant() {
        DataScope condition =
                new DataScope("AND", List.of(new DataScope.Condition("f1", "eq", "x")), List.of());
        assertThat(kindWith(List.of(readAll("3057")))).as("范围 ALL 且无记录条件").isEqualTo("ids");
        assertThat(kindWith(List.of(grant("3057", "OWN", Set.of("READ"), Map.of()))))
                .as("范围 OWN")
                .isEqualTo("object");
        assertThat(
                        kindWith(
                                List.of(
                                        grant(
                                                "3057",
                                                "ALL",
                                                Set.of("READ"),
                                                Map.of("READ", condition)))))
                .as("范围 ALL 但查看带记录条件")
                .isEqualTo("object");
        assertThat(
                        kindWith(
                                List.of(
                                        grant(
                                                "3057",
                                                "ALL",
                                                Set.of("READ", "UPDATE"),
                                                Map.of("UPDATE", condition)))))
                .as("记录条件只加在别的操作上，不影响查看")
                .isEqualTo("ids");
        assertThat(
                        kindWith(
                                List.of(
                                        grant("3057", "OWN", Set.of("READ"), Map.of()),
                                        readAll("3057"))))
                .as("两条授权一真一假")
                .isEqualTo("ids");
        assertThat(
                        kindWith(
                                List.of(
                                        grant("3057", "ALL", Set.of("UPDATE"), Map.of()),
                                        grant("3057", "OWN", Set.of("READ"), Map.of()))))
                .as("范围 ALL 的那条不含查看，不算")
                .isEqualTo("object");
    }

    @Test
    void u14_fiftyFirstSubscriptionOnOneConnectionIsRejected() {
        Connection connection = Connection.admin(7);
        for (int index = 1; index <= RecordLiveHub.MAX_SUBSCRIPTIONS; index++)
            listener.subscribe(connection.session, "nocode.records.11." + index, null, null);
        assertThat(hub.count(connection.session)).isEqualTo(50);
        int askedBefore = asked.size();
        assertThatThrownBy(
                        () ->
                                listener.subscribe(
                                        connection.session, "nocode.records.11.51", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("订阅数超过上限");
        assertThat(asked).as("超过上限时不再去查授权").hasSize(askedBefore);
        // 上限按连接计：别的连接不受影响；本连接退订一个后可以再订。
        assertThatCode(() -> subscribe(Connection.admin(7))).doesNotThrowAnyException();
    }

    @Test
    void disabledHubRejectsEverySubscription() {
        RecordLiveTopicListener closed =
                listener(new RecordLiveHub(false, null, () -> null, false));
        assertThatThrownBy(() -> closed.subscribe(Connection.admin(7).session, TOPIC, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("实时更新已关闭");
        assertThatThrownBy(
                        () -> closed.subscribe(Connection.anonymous().session, TOPIC, null, null))
                .as("未登录的仍先回未登录")
                .isInstanceOf(SecurityException.class);
    }

    private WebSocketSubscription subscribe(Connection connection) {
        return listener.subscribe(connection.session, TOPIC, null, null);
    }

    /** 用给定授权订阅后提交一次变更，返回这个订阅收到的那一帧的 kind。 */
    private String kindWith(List<ApplicationAuthorization.ObjectGrant> granted) {
        grants = granted;
        List<RecordsChanged> frames = new ArrayList<>();
        var handle =
                subscribe(Connection.admin(7))
                        .events()
                        .subscribe(event -> frames.add((RecordsChanged) event.data()));
        hub.committed(batch(created("3057", "9001")));
        hub.pump();
        handle.dispose();
        assertThat(frames).hasSize(1);
        return frames.getFirst().kind();
    }
}
