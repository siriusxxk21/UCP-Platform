package com.richuang.os.nocode.tools;

import com.richuang.os.framework.security.core.LoginUser;
import com.richuang.os.framework.websocket.core.listener.WebSocketEventMessage;
import com.richuang.os.framework.websocket.core.util.WebSocketFrameworkUtils;
import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.api.DataScope;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.web.live.RecordLiveHub;
import com.richuang.os.nocode.web.live.RecordsChanged;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 实时推送不连库用例共用的替身：假连接、授权样本、批与帧的小工具。 */
final class RecordLiveStubs {
    private RecordLiveStubs() {}

    /** 一个假的 WebSocket 连接：带登录用户属性，记录发给它的每一条文本。 */
    static final class Connection {
        final WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        final AtomicBoolean open = new AtomicBoolean(true);

        Connection(Long userId, Integer userType) {
            Map<String, Object> attributes = new HashMap<>();
            if (userId != null) {
                LoginUser user = new LoginUser();
                user.setId(userId);
                user.setUserType(userType);
                attributes.put(WebSocketFrameworkUtils.ATTRIBUTE_LOGIN_USER, user);
            }
            org.mockito.Mockito.lenient().when(session.getAttributes()).thenReturn(attributes);
            org.mockito.Mockito.lenient().when(session.getId()).thenReturn("s-" + userId);
            org.mockito.Mockito.lenient().when(session.isOpen()).thenAnswer(call -> open.get());
            try {
                org.mockito.Mockito.lenient()
                        .doAnswer(
                                call -> {
                                    sent.add(call.getArgument(0, TextMessage.class).getPayload());
                                    return null;
                                })
                        .when(session)
                        .sendMessage(org.mockito.ArgumentMatchers.any());
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        /** 管理端登录用户的连接。 */
        static Connection admin(long userId) {
            return new Connection(userId, 2);
        }

        /** 没有登录用户的连接。 */
        static Connection anonymous() {
            return new Connection(null, null);
        }

        String next() throws InterruptedException {
            return sent.poll(10, TimeUnit.SECONDS);
        }
    }

    /** 收集一个订阅出口收到的帧；accepting 为假时拒收（模拟上一帧还没被取走）。 */
    static final class Sink implements RecordLiveHub.Outlet {
        final List<RecordsChanged> frames = new CopyOnWriteArrayList<>();
        final List<WebSocketEventMessage> events = new CopyOnWriteArrayList<>();
        volatile boolean accepting = true;
        volatile boolean open = true;

        @Override
        public boolean offer(WebSocketEventMessage frame) {
            if (!accepting) return false;
            events.add(frame);
            frames.add((RecordsChanged) frame.data());
            return true;
        }

        @Override
        public boolean open() {
            return open;
        }
    }

    static ApplicationAuthorization.ObjectGrant grant(
            String objectId, String scope, Set<String> actions, Map<String, DataScope> scopes) {
        return new ApplicationAuthorization.ObjectGrant(
                objectId,
                actions,
                scope,
                Set.of("f1"),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                scopes,
                Set.of());
    }

    /** 能看全部记录、查看不带记录条件的授权。 */
    static ApplicationAuthorization.ObjectGrant readAll(String objectId) {
        return grant(objectId, "ALL", Set.of("READ"), Map.of());
    }

    static RecordChangeBatch batch(RecordChangeBatch.ObjectChange... changes) {
        return new RecordChangeBatch(List.of(changes));
    }

    static RecordChangeBatch.ObjectChange updated(String objectId, String... ids) {
        return new RecordChangeBatch.ObjectChange(
                objectId, false, List.of(), List.of(ids), List.of());
    }

    static RecordChangeBatch.ObjectChange created(String objectId, String... ids) {
        return new RecordChangeBatch.ObjectChange(
                objectId, false, List.of(ids), List.of(), List.of());
    }

    static RecordChangeBatch.ObjectChange deleted(String objectId, String... ids) {
        return new RecordChangeBatch.ObjectChange(
                objectId, false, List.of(), List.of(), List.of(ids));
    }

    static RecordChangeBatch.ObjectChange many(String objectId) {
        return new RecordChangeBatch.ObjectChange(objectId, true, List.of(), List.of(), List.of());
    }
}
