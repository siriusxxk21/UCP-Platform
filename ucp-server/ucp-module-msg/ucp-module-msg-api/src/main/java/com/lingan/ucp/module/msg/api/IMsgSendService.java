package com.lingan.ucp.module.msg.api;

import java.io.Serializable;

public interface IMsgSendService {

    /**
     * 消息发送
     *
     * @param param 发送参数
     * @return 消息ID
     */
    Serializable send(MsgSendParam param);

    /**
     * 使用当前租户在消息模板中配置的默认目标发送消息。
     *
     * @param param 发送参数
     * @return 消息ID
     */
    Serializable sendToTemplateTargets(MsgSendParam param);

    /**
     * 判断当前租户是否为指定模板配置了至少一个有效接收人。
     *
     * @param msgCode 消息模板编码
     * @return 是否可发送
     */
    boolean hasTemplateTargets(String msgCode);

    /**
     * 消息类型(模板)注册
     * <p>
     * 根据 code 判断是否已存在，已存在则更新，不存在则新增。
     * 方便各业务模块在启动时通过 ApplicationRunner 自动注册所需的消息类型。
     * </p>
     *
     * @param registParam 注册参数
     */
    void registMsgType(MsgTypeRegistParam registParam);

    /**
     * 仅在消息模板不存在时注册，避免覆盖管理员后续维护的模板内容。
     *
     * @param registParam 注册参数
     */
    void registMsgTypeIfAbsent(MsgTypeRegistParam registParam);

}
