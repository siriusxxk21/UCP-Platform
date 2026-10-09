package com.lingan.ucp.module.msg.api;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <h3>功能说明</h3>
 * <p>消息发送目标 DTO，描述消息要发送给谁。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>封装目标类型（USER/ROLE/DEPT/ALL）和目標 ID</li>
 *   <li>支持链式 setter（{@code @Accessors(chain = true)}）</li>
 * </ul>
 *
 * @author jun
 */
@Data
@Accessors(chain = true)
public class MsgTarget {

    private MsgTargetType targetType;

    private String targetId;

    private String targetName;
}
