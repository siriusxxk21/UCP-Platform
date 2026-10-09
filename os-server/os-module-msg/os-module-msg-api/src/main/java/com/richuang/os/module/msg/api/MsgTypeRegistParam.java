package com.richuang.os.module.msg.api;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 消息类型(模板)注册参数
 */
@Data
@Accessors(chain = true)
public class MsgTypeRegistParam {

    /** 消息类型编码（唯一） */
    private String code;

    /** 消息类型名称 */
    private String name;

    /** 优先级（越小越高） */
    private Integer priority;

    /** 是否可订阅（1=可订阅，0=不可订阅） */
    private Integer subscribeAble;

    /** 模板标题（支持 {{变量名}} 占位符） */
    private String templateTitle;

    /** 模板内容（支持 {{变量名}} 占位符） */
    private String templateContent;

    /** 模板URL（支持 {{变量名}} 占位符） */
    private String templateUrl;

    /** 通知配置（JSON） */
    private String noticeConfig;

    /** 元数据（JSON） */
    private String metaData;

    /** 属性（JSON） */
    private String properties;
}
