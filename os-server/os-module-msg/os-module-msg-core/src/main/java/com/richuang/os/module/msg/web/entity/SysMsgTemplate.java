package com.richuang.os.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;

@Data
@TableName("sys_msg_template")
public class SysMsgTemplate extends BaseDO {

    @TableId
    private Long id;

    /**
     * 消息类型Code
     */
    private String code;

    /**
     * 类型名称
     */
    private String name;

    /**
     * 优先级越小越高
     */
    private Integer priority;

    /**
     * JSON
     */
    private String metaData;

    /**
     * 属性
     */
    private String properties;

    /**
     * 是否可订阅
     */
    private Integer subscribeAble;

    private String templateTitle;

    private String templateContent;

    private String templateUrl;

    private String noticeConfig;

}
