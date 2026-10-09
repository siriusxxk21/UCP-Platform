package com.lingan.ucp.module.msg.core.enums;

/**
 * <h3>功能说明</h3>
 * <p>系统消息发送状态枚举。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@code ready}：待发送</li>
 *   <li>{@code sending}：发送中</li>
 *   <li>{@code success}：发送成功</li>
 *   <li>{@code cancel}：已取消</li>
 * </ul>
 *
 * @author jun
 */
public enum SysMsgStatus {
    ready,
    sending,
    success,
    cancel;
}
