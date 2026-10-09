package com.richuang.os.module.msg.api;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * <h3>功能说明</h3>
 * <p>消息发送目标类型枚举，定义消息可以发送给哪些范围的对象。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@code USER}：指定用户</li>
 *   <li>{@code ROLE}：指定角色下的所有用户</li>
 *   <li>{@code DEPT}：指定部门下的所有用户</li>
 *   <li>{@code ALL}：全体用户</li>
 * </ul>
 *
 * @author jun
 */
@Data
@AllArgsConstructor
public class MsgTargetType {
    private String code;
    private String name;

    public static final MsgTargetType USER = new MsgTargetType("USER", "用户");
    public static final MsgTargetType ROLE = new MsgTargetType("ROLE", "角色");
    public static final MsgTargetType DEPT = new MsgTargetType("DEPT", "部门");
    public static final MsgTargetType ALL = new MsgTargetType("ALL", "全员");
}
