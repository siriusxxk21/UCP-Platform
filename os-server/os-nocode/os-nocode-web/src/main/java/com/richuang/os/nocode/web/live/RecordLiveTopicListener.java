package com.richuang.os.nocode.web.live;

import com.richuang.os.framework.common.enums.UserTypeEnum;
import com.richuang.os.framework.security.core.LoginUser;
import com.richuang.os.framework.websocket.core.listener.WebSocketEventMessage;
import com.richuang.os.framework.websocket.core.listener.WebSocketMessageListener;
import com.richuang.os.framework.websocket.core.listener.WebSocketSubscription;
import com.richuang.os.framework.websocket.core.util.WebSocketFrameworkUtils;
import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.enums.ApplicationScopeEnum;

import org.springframework.web.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.util.concurrent.Queues;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主题 nocode.records.{应用}.{对象} 的订阅：登录的管理端用户、对该应用里的该对象有查看授权才能订；授权范围不是「全部记录」
 * 或查看带记录条件时，这个订阅收不到记录标识。只在订阅时判断一次。
 */
public class RecordLiveTopicListener implements WebSocketMessageListener<Object> {
    public static final String PREFIX = "nocode.records.";

    private static final Pattern TOPIC =
            Pattern.compile("^nocode\\.records\\.([1-9][0-9]{0,18})\\.([1-9][0-9]{0,18})$");

    /** 查某个操作者在某个应用里对某个对象的有效授权（只用其中的操作、记录范围、记录条件）；即 ApplicationRuntimePolicy.scopeGrants。 */
    @FunctionalInterface
    public interface Grants {
        List<ApplicationAuthorization.ObjectGrant> effective(
                String applicationId, String objectId, long actor);
    }

    private final RecordLiveHub hub;
    private final Grants grants;
    private final Scheduler senders;

    /**
     * @param senders 消费各订阅事件流的调度器：协议处理器在消费线程上对连接做阻塞写，必须与分发线程分开
     */
    public RecordLiveTopicListener(RecordLiveHub hub, Grants grants, Scheduler senders) {
        this.hub = hub;
        this.grants = grants;
        this.senders = senders;
    }

    @Override
    public String getTopic() {
        return PREFIX;
    }

    @Override
    public boolean supportsTopic(String topic) {
        return topic != null && TOPIC.matcher(topic).matches();
    }

    @Override
    public WebSocketSubscription subscribe(
            WebSocketSession session, String topic, String offset, Object data) {
        Matcher matcher = TOPIC.matcher(topic == null ? "" : topic);
        if (!matcher.matches()) throw new IllegalStateException("主题格式无效");
        String applicationId = matcher.group(1);
        String objectId = matcher.group(2);
        LoginUser user = WebSocketFrameworkUtils.getLoginUser(session);
        if (user == null
                || user.getId() == null
                || !UserTypeEnum.ADMIN.getValue().equals(user.getUserType()))
            throw new SecurityException("实时更新需要登录");
        if (!hub.enabled()) throw new IllegalStateException("实时更新已关闭");
        if (hub.count(session) >= RecordLiveHub.MAX_SUBSCRIPTIONS)
            throw new IllegalStateException("订阅数超过上限");
        List<ApplicationAuthorization.ObjectGrant> effective;
        try {
            effective = grants.effective(applicationId, objectId, user.getId());
        } catch (RuntimeException error) {
            // 应用或对象不存在、未发布等内部原因一律按无权处理，不把内部信息带给客户端。
            throw new SecurityException("没有此对象的查看权限");
        }
        String read = ApplicationActionEnum.READ.getCode();
        if (effective == null || effective.stream().noneMatch(g -> g.actions().contains(read)))
            throw new SecurityException("没有此对象的查看权限");
        boolean idsVisible =
                effective.stream()
                        .anyMatch(
                                g ->
                                        g.actions().contains(read)
                                                && ApplicationScopeEnum.ALL
                                                        .getCode()
                                                        .equals(g.scope())
                                                && !g.actionScopes().containsKey(read));

        // 出口容量为一帧：上一帧还没被发送线程取走时放不进去，由分发器把这个订阅标记为落后。
        Sinks.Many<WebSocketEventMessage> sink =
                Sinks.many()
                        .unicast()
                        .onBackpressureBuffer(Queues.<WebSocketEventMessage>get(1).get());
        RecordLiveHub.Subscription subscription =
                hub.subscribe(
                        session,
                        objectId,
                        idsVisible,
                        new RecordLiveHub.Outlet() {
                            @Override
                            public boolean offer(WebSocketEventMessage frame) {
                                return sink.tryEmitNext(frame).isSuccess();
                            }

                            @Override
                            public boolean open() {
                                return session.isOpen();
                            }
                        });
        // 退订、连接关闭或事件流出错时从分发器注销；随后切到发送线程，分发线程不做任何网络发送。
        Flux<WebSocketEventMessage> events =
                sink.asFlux()
                        .doFinally(signal -> hub.unsubscribe(subscription))
                        .publishOn(senders, 1);
        return new WebSocketSubscription(events, false, null);
    }
}
