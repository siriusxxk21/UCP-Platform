package com.lingan.ucp.module.msg.core.enums;

import lombok.Getter;

/**
 * <h3>功能说明</h3>
 * <p>消息已读状态枚举。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@code UN_READ("未读", 0)}：消息未读</li>
 *   <li>{@code READ("已读", 1)}：消息已读</li>
 * </ul>
 *
 * @author GuoLong
 */
@Getter
public enum MsgReadStatus {
    UN_READ("未读", 0),
    READ("已读", 1);

    private final String key;

    private final int value;

    MsgReadStatus(String key, int value) {
        this.key = key;
        this.value = value;
    }

    /*
     * 根据value获取status
     */
    public static MsgReadStatus getByValue(int value) {
        for (MsgReadStatus appStatus : MsgReadStatus.values()) {
            if (appStatus.value == value) {
                return appStatus;
            }
        }
        return MsgReadStatus.UN_READ;
    }
}
