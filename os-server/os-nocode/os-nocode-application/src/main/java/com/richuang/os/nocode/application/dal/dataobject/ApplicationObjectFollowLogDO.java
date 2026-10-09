package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 自动跟随的逐次结果：每次「跟上 / 没跟上」一行，只增不改。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_application_object_follow_log")
public class ApplicationObjectFollowLogDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private Long objectId;
    private Integer fromVersion;
    private Integer toVersion;
    private Integer applicationVersionBefore;
    private Integer applicationVersionAfter;

    /** FOLLOWED 或 PENDING。 */
    private String outcome;

    private String pendingCode;
    private String reason;

    /** 触发来源：对象发布、拨开开关、手工、定时重试、应用发布、迁移工具。 */
    private String triggerKind;

    /** 对象发布计划（uuid）；不是由对象发布触发时为空。 */
    private String planId;

    /** 查询投影：应用名称，不落库。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String applicationName;
}
