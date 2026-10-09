package com.richuang.os.module.msg.core;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richuang.os.module.msg.api.*;
import com.richuang.os.module.msg.channel.SysMessageWebSocketListener;
import com.richuang.os.module.msg.core.channel.NoticeChannelHandler;
import com.richuang.os.module.msg.core.enums.MsgNoticeStatus;
import com.richuang.os.module.msg.core.enums.MsgReadStatus;
import com.richuang.os.module.msg.core.enums.SysMsgStatus;
import com.richuang.os.module.msg.web.entity.*;
import com.richuang.os.module.msg.web.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MsgSendService implements IMsgSendService, ApplicationRunner {

    private final SysMsgTemplateService sysMsgTemplateService;

    private final SysMsgService sysMsgService;

    private final SysMsgTargetsService sysMsgTargetsService;

    private final SysMsgSubscribeService sysMsgSubscribeService;

    private final SysMsgNoticeService sysMsgNoticeService;

    private final SysMsgNoticeLogService sysMsgNoticeLogService;

    private final List<MsgTargetService> msgTargetServiceList;

    private final List<NoticeChannelHandler> noticeChannelHandlerList;

    private final SysMessageWebSocketListener sysMessageWebSocketListener;

    private final SysMsgTemplateTargetService sysMsgTemplateTargetService;

    public MsgSendService(SysMsgTemplateService sysMsgTemplateService, SysMsgService sysMsgService, SysMsgTargetsService sysMsgTargetsService, SysMsgSubscribeService sysMsgSubscribeService, SysMsgNoticeService sysMsgNoticeService, SysMsgNoticeLogService sysMsgNoticeLogService, List<MsgTargetService> msgTargetServiceList, List<NoticeChannelHandler> noticeChannelHandlerList, SysMessageWebSocketListener sysMessageWebSocketListener, SysMsgTemplateTargetService sysMsgTemplateTargetService) {
        this.sysMsgTemplateService = sysMsgTemplateService;
        this.sysMsgService = sysMsgService;
        this.sysMsgTargetsService = sysMsgTargetsService;
        this.sysMsgSubscribeService = sysMsgSubscribeService;
        this.sysMsgNoticeService = sysMsgNoticeService;
        this.sysMsgNoticeLogService = sysMsgNoticeLogService;
        this.msgTargetServiceList = msgTargetServiceList;
        this.noticeChannelHandlerList = noticeChannelHandlerList;
        this.sysMessageWebSocketListener = sysMessageWebSocketListener;
        this.sysMsgTemplateTargetService = sysMsgTemplateTargetService;
    }

    /**
     * 消息类型(模板)注册
     * <p>
     * 根据 code 判断是否已存在，已存在则更新，不存在则新增。
     * </p>
     *
     * @param registParam 注册参数
     */
    /** 系统管理员用户ID（程序自动注册时使用） */
    private static final String SYSTEM_USER_ID = "0";

    @Override
    @Transactional
    public void registMsgType(MsgTypeRegistParam registParam) {
        Assert.notNull(registParam);
        Assert.notBlank(registParam.getCode(), "消息类型编码不能为空");
        Assert.notBlank(registParam.getName(), "消息类型名称不能为空");

        SysMsgTemplate existing = sysMsgTemplateService.getOne(
                new QueryWrapper<SysMsgTemplate>().lambda()
                        .eq(SysMsgTemplate::getCode, registParam.getCode()));

        if (existing != null) {
            // 已存在则更新
            existing.setName(registParam.getName());
            if (registParam.getPriority() != null) {
                existing.setPriority(registParam.getPriority());
            }
            if (registParam.getSubscribeAble() != null) {
                existing.setSubscribeAble(registParam.getSubscribeAble());
            }
            if (registParam.getTemplateTitle() != null) {
                existing.setTemplateTitle(registParam.getTemplateTitle());
            }
            if (registParam.getTemplateContent() != null) {
                existing.setTemplateContent(registParam.getTemplateContent());
            }
            if (registParam.getTemplateUrl() != null) {
                existing.setTemplateUrl(registParam.getTemplateUrl());
            }
            if (registParam.getNoticeConfig() != null) {
                existing.setNoticeConfig(registParam.getNoticeConfig());
            }
            if (registParam.getMetaData() != null) {
                existing.setMetaData(registParam.getMetaData());
            }
            if (registParam.getProperties() != null) {
                existing.setProperties(registParam.getProperties());
            }
            existing.setUpdater(SYSTEM_USER_ID);
            sysMsgTemplateService.updateById(existing);
            log.info("[MsgTypeRegist] 消息类型已更新: code={}, name={}", registParam.getCode(), registParam.getName());
        } else {
            // 不存在则新增
            SysMsgTemplate template = new SysMsgTemplate();
            template.setCode(registParam.getCode());
            template.setName(registParam.getName());
            template.setPriority(registParam.getPriority() != null ? registParam.getPriority() : 0);
            template.setSubscribeAble(registParam.getSubscribeAble() != null ? registParam.getSubscribeAble() : 0);
            template.setTemplateTitle(registParam.getTemplateTitle());
            template.setTemplateContent(registParam.getTemplateContent());
            template.setTemplateUrl(registParam.getTemplateUrl());
            template.setNoticeConfig(registParam.getNoticeConfig());
            template.setMetaData(registParam.getMetaData());
            template.setProperties(registParam.getProperties());
            template.setCreator(SYSTEM_USER_ID);
            sysMsgTemplateService.save(template);
            log.info("[MsgTypeRegist] 消息类型已注册: code={}, name={}", registParam.getCode(), registParam.getName());
        }
    }

    @Override
    @Transactional
    public void registMsgTypeIfAbsent(MsgTypeRegistParam registParam) {
        Assert.notNull(registParam);
        Assert.notBlank(registParam.getCode(), "消息类型编码不能为空");
        if (sysMsgTemplateService.getByCode(registParam.getCode()) == null) {
            registMsgType(registParam);
        }
    }

    @Override
    @Transactional
    public Serializable sendToTemplateTargets(MsgSendParam param) {
        Assert.notNull(param);
        List<MsgTarget> targets = sysMsgTemplateTargetService.resolveUserTargets(param.getMsgCode());
        Assert.notEmpty(targets, "反馈接收人尚未配置，请联系管理员");
        param.setTargets(targets);
        return send(param);
    }

    @Override
    public boolean hasTemplateTargets(String msgCode) {
        return sysMsgTemplateService.getByCode(msgCode) != null
                && !sysMsgTemplateTargetService.resolveUserTargets(msgCode).isEmpty();
    }

    /**
     * 消息发送 Api
     * @param param
     * @return
     */
    @Override
    @Transactional
    public Serializable send(MsgSendParam param) {
        Assert.notNull(param);
        Assert.notEmpty(param.getMsgCode());

        SysMsgTemplate msgTemplate = sysMsgTemplateService.getOne(new QueryWrapper<SysMsgTemplate>().lambda()
                .eq(SysMsgTemplate::getCode, param.getMsgCode()));
        Assert.notNull(msgTemplate, "未找到消息类型：" + param.getMsgCode());
        if (msgTemplate.getSubscribeAble() != 1) {
            Assert.notEmpty(param.getTargets(), "消息发送目标不能为空");
        } else {
            param.setTargets(List.of()); // 订阅类消息忽略显式发送目标
        }

        // 构建消息
        SysMsg sysMsg = new SysMsg();
        sysMsg.setMsgTemplateId(msgTemplate.getId());
        sysMsg.setPriority(ObjUtil.defaultIfNull(msgTemplate.getPriority(), 0));
        sysMsg.setSourceType(param.getSourceType());
        sysMsg.setSourceId(param.getSourceId());
        sysMsg.setOwnerId(param.getOwnerId());
        sysMsg.setOwnerName(param.getOwnerName());
        sysMsg.setTitle(param.getTitle());
        if (StrUtil.isBlank(sysMsg.getTitle())) {
            sysMsg.setTitle(MsgStrTemplateUtil.parse(msgTemplate.getTemplateTitle(), param.getMsgData()));
        }
        sysMsg.setContent(param.getContent());
        if (StrUtil.isBlank(sysMsg.getContent())) {
            sysMsg.setContent(MsgStrTemplateUtil.parse(msgTemplate.getTemplateContent(), param.getMsgData()));
        }
        sysMsg.setUrl(MsgStrTemplateUtil.parse(msgTemplate.getTemplateUrl(), param.getMsgData()));
        sysMsg.setMsgData(JSON.toJSONString(param.getMsgData()));

        JSONObject properties = new JSONObject();
        if (StrUtil.isNotBlank(msgTemplate.getProperties())) {
            properties.putAll(JSON.parseObject(msgTemplate.getProperties()));
        }
        if (param.getProperties() != null) {
            properties.putAll(param.getProperties());
        }
        sysMsg.setProperties(properties.toJSONString());

        sysMsg.setStatus(SysMsgStatus.ready.name());
        // 后台任务没有登录态，不能依赖 MyBatis 自动填充审计人；优先记录业务发送人，否则使用系统用户。
        String operatorId = StrUtil.blankToDefault(param.getOwnerId(), SYSTEM_USER_ID);
        sysMsg.setCreator(operatorId);
        sysMsg.setUpdater(operatorId);
        sysMsgService.save(sysMsg);

        // 构建消息发送对象
        List<SysMsgTargets> targets = param.getTargets().stream().map(target -> {
            SysMsgTargets msgTargets = new SysMsgTargets();
            msgTargets.setMsgId(sysMsg.getId());
            msgTargets.setTargetType(target.getTargetType().getCode());
            msgTargets.setTargetId(target.getTargetId());
            msgTargets.setTargetName(target.getTargetName());
            return msgTargets;
        }).collect(Collectors.toList());

        if (ObjUtil.isNotEmpty(targets)) {
            sysMsgTargetsService.saveBatch(targets);
        }

        // 消息发送异步任务 入队
        MsgSendTask task = new MsgSendTask();
        task.setMsg(sysMsg);
        task.setTargets(param.getTargets());
        task.setMsgTemplate(msgTemplate);
        enqueueAfterCommit(task);
        return sysMsg.getId();
    }

    /**
     * 消息与业务数据经常处于同一事务中，必须等事务提交后再让异步线程读取消息。
     */
    private void enqueueAfterCommit(MsgSendTask task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            taskQueue.offer(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskQueue.offer(task);
            }
        });
    }

    private final DelayQueue<MsgSendTask> taskQueue = new DelayQueue<>();

    private final Sinks.Many<MsgNoticeTask> noticeTaskSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Map<Long, AtomicInteger> msgUndoAtomic = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 通知任务队列 - 消费
        this.noticeTaskSink.asFlux().publishOn(Schedulers.parallel())
            .doOnNext(this::notice)
            .onErrorContinue((e, o) -> log.error(e.getMessage(), o.toString()))
            .subscribe();

        // 消息发送任务异步队列-消费
        Thread thread = new Thread(() -> {
            while (true) {
                MsgSendTask task = null;
                try {
                    task = taskQueue.take();
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException interruptedException) {
                        log.error(interruptedException.getMessage(), interruptedException);
                    }
                    continue;
                }
                this.send(task);
            }
        }, "Thread-SYS_MSG_SEND_QUEUE-CONSUMER");
        thread.setDaemon(true);
        thread.start();

        // 启动时加载未完成的消息继续执行
        List<SysMsg> sysMsgs = sysMsgService.list(new QueryWrapper<SysMsg>().lambda()
                .in(SysMsg::getStatus, SysMsgStatus.ready.name(), SysMsgStatus.sending.name()));
        sysMsgs.forEach(sysMsg -> {
            SysMsgTemplate msgTemplate = sysMsgTemplateService.getById(sysMsg.getMsgTemplateId());
            Assert.notNull(msgTemplate, "未找到消息类型：" + sysMsg.getMsgTemplateId());

            MsgSendTask task = new MsgSendTask();
            task.setMsg(sysMsg);
            task.setMsgTemplate(msgTemplate);
            task.setTargets(sysMsgTargetsService.list(new QueryWrapper<SysMsgTargets>().lambda()
                    .eq(SysMsgTargets::getMsgId, sysMsg.getId()))
                    .stream()
                    .map(entity -> {
                        MsgTarget msgTarget = new MsgTarget();
                        msgTarget.setTargetType(new MsgTargetType(entity.getTargetType(), null));
                        msgTarget.setTargetId(entity.getTargetId());
                        msgTarget.setTargetName(entity.getTargetName());
                        return msgTarget;
                    }).toList());

            JSONObject properties = new JSONObject();
            if (StrUtil.isNotBlank(sysMsg.getProperties())) {
                properties.putAll(JSON.parseObject(sysMsg.getProperties()));
            }
            task.setProperties(properties);

            taskQueue.offer(task);
        });
    }

    /**
     * 消息发送 - 发送任务执行
     * @param task
     */
    private void send(MsgSendTask task) {
        // 撤回可能发生在任务入队之后，执行前必须重新读取状态，避免使用任务中的旧快照继续投递。
        SysMsg latestMsg = sysMsgService.getById(task.getMsg().getId());
        if (latestMsg == null || SysMsgStatus.cancel.name().equals(latestMsg.getStatus())) {
            return;
        }
        task.setMsg(latestMsg);
        // 首次发送
        if (SysMsgStatus.ready.name().equals(task.getMsg().getStatus())) {
            // 发送对象转具体目标接收者
            List<MsgReceiver> receiverList = new ArrayList<>(task.getTargets().stream().map(target -> msgTargetServiceList.stream().filter(s -> s.support().equals(target.getTargetType()))
                            .map(service -> service.getMsgReceivers(target))
                            .flatMap(Collection::stream)
                            .toList())
                    .flatMap(Collection::stream)
                    .toList());

            // 订阅者转接收者
            if (task.getMsgTemplate().getSubscribeAble() == 1) {
                receiverList.addAll(sysMsgSubscribeService.list(new QueryWrapper<SysMsgSubscribe>().lambda()
                                .eq(SysMsgSubscribe::getMsgTemplateId, task.getMsgTemplate().getId()))
                    .stream().map(subscriber -> {
                        MsgReceiver subReceiver = new MsgReceiver();
                        subReceiver.setReceiverType(MsgReceiverType.USER);
                        subReceiver.setReceiverId(subscriber.getUserId());
                        subReceiver.setReceiverId(subscriber.getUserName());
                        return subReceiver;
                    }).toList());
            }

            // 存接收者的消息记录
            List<SysMsgNotice> msgNoticeList = receiverList.stream().distinct().map(receiver -> {
                SysMsgNotice msgNotice = new SysMsgNotice();
                msgNotice.setMsgId(task.getMsg().getId());
                msgNotice.setReceiverType(receiver.getReceiverType().name());
                msgNotice.setReceiverId(receiver.getReceiverId());
                msgNotice.setReceiverName(receiver.getReceiverName());
                msgNotice.setStatus(MsgNoticeStatus.NO.name());
                msgNotice.setHasRead(MsgReadStatus.UN_READ.getValue());
                return msgNotice;
            }).toList();
            if (!msgNoticeList.isEmpty()) {
                sysMsgNoticeService.saveBatch(msgNoticeList);
                msgNoticeList.forEach(notice -> sysMessageWebSocketListener.msgCountNotice(Long.valueOf(notice.getReceiverId())));
            }
            String noticeConfig = task.getMsgTemplate().getNoticeConfig();
            JSONArray noticeTemplates = JSON.parseArray(StrUtil.blankToDefault(noticeConfig, "[]"));
            if (noticeTemplates.isEmpty()) { // 无配置通知模板，直接标记发送成功
                sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                        .set(SysMsg::getStatus, SysMsgStatus.success.name())
                        .set(SysMsg::getSendTime, LocalDateTime.now())
                        .eq(SysMsg::getId, task.getMsg().getId()));
            } else {
                if (msgNoticeList.isEmpty()) { // 无接收者, 消息标记发送成功
                    sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                            .set(SysMsg::getStatus, SysMsgStatus.success.name())
                            .set(SysMsg::getSendTime, LocalDateTime.now())
                            .eq(SysMsg::getId, task.getMsg().getId()));
                } else { // 有接收者，发送通知任务，并标记消息发送中
                    sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                            .set(SysMsg::getStatus, SysMsgStatus.sending.name())
                            .eq(SysMsg::getId, task.getMsg().getId()));
                    msgUndoAtomic.put(task.getMsg().getId(), new AtomicInteger(msgNoticeList.size()));
                    msgNoticeList.forEach(msgNotice -> {
                        MsgNoticeTask noticeTask = new MsgNoticeTask();
                        BeanUtil.copyProperties(task, noticeTask);
                        noticeTask.setNotice(msgNotice);
                        synchronized (this.noticeTaskSink) {
                            this.noticeTaskSink.tryEmitNext(noticeTask);
                        }
                    });
                }
            }
        } else if (SysMsgStatus.sending.name().equals(task.getMsg().getStatus())) { // 二次进入发送队列（一搬由重启进将未发送成功消息重新入队）
            String noticeConfig = task.getMsgTemplate().getNoticeConfig();
            JSONArray noticeTemplates = JSON.parseArray(StrUtil.blankToDefault(noticeConfig, "[]"));
            if (noticeTemplates.isEmpty()) { // 无配置通知模板，直接标记发送成功
                sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                        .set(SysMsg::getStatus, SysMsgStatus.success.name())
                        .set(SysMsg::getSendTime, LocalDateTime.now())
                        .eq(SysMsg::getId, task.getMsg().getId()));
                msgUndoAtomic.remove(task.getMsg().getId());
            } else {
                // 查询未通知成功任务
                List<SysMsgNotice> msgNoticeList = sysMsgNoticeService.list(new QueryWrapper<SysMsgNotice>().lambda()
                        .eq(SysMsgNotice::getMsgId, task.getMsg().getId())
                        .eq(SysMsgNotice::getStatus, MsgNoticeStatus.NO.name()));
                if (msgNoticeList.isEmpty()) { // 未通知成功任务为空则，直接标记发送成功
                    sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                            .set(SysMsg::getStatus, SysMsgStatus.success.name())
                            .set(SysMsg::getSendTime, LocalDateTime.now())
                            .eq(SysMsg::getId, task.getMsg().getId()));
                } else { // 通知任务入队
                    msgUndoAtomic.put(task.getMsg().getId(), new AtomicInteger(msgNoticeList.size()));
                    msgNoticeList.forEach(msgNotice -> {
                        MsgNoticeTask noticeTask = new MsgNoticeTask();
                        BeanUtil.copyProperties(task, noticeTask);
                        noticeTask.setNotice(msgNotice);
                        synchronized (this.noticeTaskSink) {
                            this.noticeTaskSink.tryEmitNext(noticeTask);
                        }
                    });
                }
            }
        }
    }

    /**
     * 通知任务
     * @param noticeTask
     */
    private void notice(MsgNoticeTask noticeTask) {
        SysMsg latestMsg = sysMsgService.getById(noticeTask.getMsg().getId());
        if (latestMsg == null || SysMsgStatus.cancel.name().equals(latestMsg.getStatus())) {
            return;
        }
        // 查找启用的通道模板配置
        JSONArray noticeTemplates = JSON.parseArray(StrUtil.blankToDefault(noticeTask.getMsgTemplate().getNoticeConfig(), "[]"));
        List<JSONObject> templateList = noticeTemplates.stream().map(o -> (JSONObject) o)
                .filter(template -> template.getInteger("open") == 1).toList();
        for (JSONObject template : templateList) {
            String channel = template.getString("channel");
            // 获取消息发送通道handler
            Optional<NoticeChannelHandler> handlerOptional = noticeChannelHandlerList.stream()
                    .filter(handler -> handler.support().getCode().equals(channel)).findFirst();
            if (handlerOptional.isPresent()) {
                try {
                    // 通知执行
                    handlerOptional.get().execute(noticeTask, template.getJSONObject("msgTemplate"));
                    // 成功 - 记录日志
                    SysMsgNoticeLog noticeLog = new SysMsgNoticeLog();
                    noticeLog.setNoticeId(noticeTask.getNotice().getId());
                    noticeLog.setNoticeChannel(channel);
                    noticeLog.setLogInfo("通知成功");
                    noticeLog.setLogTime(new Date());
                    sysMsgNoticeLogService.save(noticeLog);
                    // 成功 - 更新通知结果
                    sysMsgNoticeService.update(new UpdateWrapper<SysMsgNotice>().lambda()
                            .set(SysMsgNotice::getNoticeChannel, channel)
                            .set(SysMsgNotice::getNoticeTime, new Date())
                            .set(SysMsgNotice::getStatus, MsgNoticeStatus.YES.name())
                            .eq(SysMsgNotice::getId, noticeTask.getNotice().getId()));
                } catch (Exception e) { // 通知异常 - 记录日志
                    SysMsgNoticeLog noticeLog = new SysMsgNoticeLog();
                    noticeLog.setNoticeId(noticeTask.getNotice().getId());
                    noticeLog.setNoticeChannel(channel);
                    noticeLog.setLogInfo("通知失败! " + e.getMessage());
                    noticeLog.setLogTime(new Date());
                    sysMsgNoticeLogService.save(noticeLog);
                }
            } else { // 通知类型不支持异常
                SysMsgNoticeLog noticeLog = new SysMsgNoticeLog();
                noticeLog.setNoticeId(noticeTask.getNotice().getId());
                noticeLog.setNoticeChannel(channel);
                noticeLog.setLogInfo("不支持通知类型：" + channel);
                noticeLog.setLogTime(new Date());
                sysMsgNoticeLogService.save(noticeLog);
            }
        }

        msgUndoAtomic.getOrDefault(noticeTask.getMsg().getId(), new AtomicInteger()).decrementAndGet();
        //未通知对象数为0,则更新消息发送状态
        if (msgUndoAtomic.getOrDefault(noticeTask.getMsg().getId(), new AtomicInteger()).get() <= 0) {
            sysMsgService.update(new UpdateWrapper<SysMsg>().lambda()
                    .set(SysMsg::getStatus, SysMsgStatus.success.name())
                    .set(SysMsg::getSendTime, LocalDateTime.now())
                    .eq(SysMsg::getId, noticeTask.getMsg().getId()));
            msgUndoAtomic.remove(noticeTask.getMsg().getId());
        }
    }
}
