package com.richuang.os.module.msg.api;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.*;

/**
 * <h3>功能说明</h3>
 * <p>消息发送参数，通过 Builder 模式构建。提供链式 API 设置消息编码、数据、目标和属性。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>封装消息发送所需的所有参数（msgCode、msgData、targets、properties 等）</li>
 *   <li>Builder 校验：msgCode 和 msgData 不可为空</li>
 *   <li>提供静态工厂方法 {@code buildTargetsByUserIds()} 快速构建用户目标列表</li>
 * </ul>
 *
 * <h3>归正修复</h3>
 * <ul>
 *   <li>P4：通配符 {@code import java.util.*} 展开为具体 import</li>
 * </ul>
 *
 * @author jun
 */
@Accessors(chain = true)
@Data
public class MsgSendParam {

    /**
     * 消息类型编码
     */
    private String msgCode;

    /**
     * 不提供title数据时，系统将通过 msgData + titleTemplate生成
     */
    private String title;

    /**
     * 不提供content数据时，系统将通过 msgData + contentTemplate生成
     */
    private String content;

    /**
     * 消息数据
     */
    private Map<String, Object> msgData;

    /**
     * 消息源
     */
    private String sourceType;

    /**
     * 消息源ID
     */
    private String sourceId;

    /**
     * 消息发送人
     */
    private String ownerId;

    /**
     * 消息发送人名称
     */
    private String ownerName;

    /**
     * 消息发送目标对象
     */
    private List<MsgTarget> targets;

    /**
     * 自定义策略, 覆盖消息类型定义策略
     */
    private Map<String, Object> properties;

}
