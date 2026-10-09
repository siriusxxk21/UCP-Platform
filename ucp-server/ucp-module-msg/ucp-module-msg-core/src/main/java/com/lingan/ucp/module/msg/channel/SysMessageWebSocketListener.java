package com.lingan.ucp.module.msg.channel;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.framework.common.enums.UserTypeEnum;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketEventMessage;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketMessageListener;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketSubscription;
import com.lingan.ucp.framework.websocket.core.session.WebSocketSessionManager;
import com.lingan.ucp.framework.websocket.core.util.WebSocketFrameworkUtils;
import com.lingan.ucp.module.msg.api.MsgReceiverType;
import com.lingan.ucp.module.msg.core.MsgNoticeTask;
import com.lingan.ucp.module.msg.core.MsgStrTemplateUtil;
import com.lingan.ucp.module.msg.core.channel.*;
import com.lingan.ucp.module.msg.web.entity.SysMsgNotice;
import com.lingan.ucp.module.msg.web.service.SysMsgNoticeService;
import com.lingan.ucp.module.msg.web.vo.SysMsgSubscribeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

@Slf4j
@Service
@Order(1)
public class SysMessageWebSocketListener implements NoticeChannelHandler, WebSocketMessageListener<SysMsgSubscribeInfo> {

    private final Sinks.Many<Tuple2<Long, JSONObject>> messageSink = Sinks.many().multicast().onBackpressureBuffer(1024, false);

    private final Sinks.Many<Tuple2<Long, Long>> messageUnReadCountSink = Sinks.many().multicast().onBackpressureBuffer(1024, false);

    private final WebSocketSessionManager webSocketSessionManager;

    private final SysMsgNoticeService sysMsgNoticeService;

    public SysMessageWebSocketListener(WebSocketSessionManager webSocketSessionManager, SysMsgNoticeService sysMsgNoticeService) {
        this.webSocketSessionManager = webSocketSessionManager;
        this.sysMsgNoticeService = sysMsgNoticeService;
    }

    @Override
    public String getTopic() {
        return "user.notifications";
    }

    @Override
    public WebSocketSubscription subscribe(WebSocketSession session, String topic, String offset,
                                           SysMsgSubscribeInfo message) {
        Long userId = WebSocketFrameworkUtils.getLoginUserId(session);
        if (userId == null) {
            throw new SecurityException("系统通知订阅需要登录");
        }
        Flux<WebSocketEventMessage> messageFlux = messageSink.asFlux()
                .filter(tuple -> tuple.getT1().equals(userId))
                .map(tuple -> WebSocketEventMessage.now("notification.created", tuple.getT2()));

        Flux<WebSocketEventMessage> messageCountFlux = messageUnReadCountSink.asFlux()
                .filter(tuple -> tuple.getT1().equals(userId))
                .map(tuple -> WebSocketEventMessage.now(
                        "notification.unread-count.changed", tuple.getT2()));
        Flux<WebSocketEventMessage> initialUnreadCount = Mono.just(WebSocketEventMessage.now(
                "notification.unread-count.changed", unReadCouont(userId))).flux();
        Flux<WebSocketEventMessage> events = Flux.merge(initialUnreadCount, messageCountFlux, messageFlux)
                .concatWith(Flux.never());
        return new WebSocketSubscription(events, false, null);
    }

    public void msgCountNotice(Long userId) {
        Collection<WebSocketSession> sessionList = webSocketSessionManager.getSessionList(UserTypeEnum.ADMIN.getValue(), userId);
        if (!sessionList.isEmpty()) {
            synchronized (this.messageUnReadCountSink) {
                messageUnReadCountSink.tryEmitNext(Tuples.of(userId, unReadCouont(userId)));
            }
        }
    }

    private long unReadCouont(Long userId) {
        return this.sysMsgNoticeService.count(new QueryWrapper<SysMsgNotice>().lambda()
                .eq(SysMsgNotice::getReceiverType, MsgReceiverType.USER.name())
                .eq(SysMsgNotice::getReceiverId, String.valueOf(userId))
                .eq(SysMsgNotice::getHasRead, 0));
    }

    @Override
    public NoticeChannel support() {
        return new NoticeChannel("default", "系统弹窗", null,
                Arrays.asList(
                        Metadata.of("title", "标题", MetadataType.text, null, false),
                        Metadata.of("content", "内容", MetadataType.richText, null, false)));
    }

    @Override
    public void execute(MsgNoticeTask noticeTask, JSONObject template) throws NoticeException {
        Collection<WebSocketSession> sessionList = webSocketSessionManager.getSessionList(UserTypeEnum.ADMIN.getValue(), Long.valueOf(noticeTask.getNotice().getReceiverId()));
        if (sessionList.isEmpty()) {
            log.warn("用户不在线，跳过 WebSocket 推送: receiverId={}, msgId={}",
                    noticeTask.getNotice().getReceiverId(), noticeTask.getMsg().getId());
            throw new BusinessException(String.format("用户不在线，跳过 WebSocket 推送: receiverId=%s, msgId=%s",
                    noticeTask.getNotice().getReceiverId(), noticeTask.getMsg().getId()));
        }
        JSONObject msg = new JSONObject();
        msg.put("msgId", noticeTask.getMsg().getId() + "");
        msg.put("type", noticeTask.getMsgTemplate().getCode());
        msg.put("typeName", noticeTask.getMsgTemplate().getName());
        msg.put("priority", noticeTask.getMsg().getPriority());
        msg.put("ownerId", noticeTask.getMsg().getOwnerId());
        msg.put("ownerName", noticeTask.getMsg().getOwnerName());
        msg.put("time", LocalDateTimeUtil.formatNormal(noticeTask.getMsg().getCreateTime()));
        msg.put("sourceType", noticeTask.getMsg().getSourceType());
        msg.put("sourceId", noticeTask.getMsg().getSourceId());
        msg.put("data", new JSONObject());

        Map<String, Object> context = JSONObject.parseObject(noticeTask.getMsg().getMsgData());
        String renderedTitle = MsgStrTemplateUtil.parse(template == null ? null : template.getString("title"), context);
        String renderedContent = MsgStrTemplateUtil.parse(template == null ? null : template.getString("content"), context);
        msg.getJSONObject("data").put("title", StrUtil.blankToDefault(renderedTitle, noticeTask.getMsg().getTitle()));
        msg.getJSONObject("data").put("content", isVisuallyBlank(renderedContent)
                ? noticeTask.getMsg().getContent() : renderedContent);
        msg.getJSONObject("data").put("route", StrUtil.blankToDefault(
                MsgStrTemplateUtil.parse(template == null ? null : template.getString("route"), context),
                noticeTask.getMsg().getUrl()));

        synchronized (this.messageSink) {
            messageSink.tryEmitNext(Tuples.of(Long.valueOf(noticeTask.getNotice().getReceiverId()), msg));
        }
    }

    /** 富文本编辑器的空值通常是 &lt;p&gt;&lt;/p&gt;，不能只用字符串空白判断。 */
    private boolean isVisuallyBlank(String content) {
        if (StrUtil.isBlank(content)) {
            return true;
        }
        String plainText = HtmlUtils.htmlUnescape(content.replaceAll("<[^>]*>", ""))
                .replace('\u00a0', ' ');
        return StrUtil.isBlank(plainText);
    }
}
