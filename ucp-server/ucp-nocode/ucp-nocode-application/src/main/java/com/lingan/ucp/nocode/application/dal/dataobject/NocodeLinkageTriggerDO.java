package com.lingan.ucp.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据联动自动更新的反向索引：一行 = 某应用某个发布版本里一个开启自动更新的目标字段。
 *
 * <p>全部列都由不可变的应用发布快照与对象版本推导（LinkageTriggerPlan），登记后不修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_linkage_trigger")
public class NocodeLinkageTriggerDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private Integer applicationVersion;
    private Long sourceObjectId;
    private Long targetObjectId;
    private Integer targetObjectVersion;
    private Long targetFieldId;

    /** CURRENT_RECORD：锚点字段在来源对象上；RECORD_KEY：锚点字段在目标对象上。 */
    private String anchor;

    private Long anchorFieldId;
    private String signature;
}
