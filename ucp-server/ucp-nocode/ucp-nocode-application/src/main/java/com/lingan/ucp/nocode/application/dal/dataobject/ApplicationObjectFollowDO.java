package com.lingan.ucp.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 应用对数据对象的自动跟随开关与状态；独立于应用草稿与发布版本，没有行表示默认开启且正常跟随。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_application_object_follow")
public class ApplicationObjectFollowDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private Long objectId;
    private Boolean enabled;

    /** FOLLOWING 或 PENDING。 */
    private String state;

    /** 没跟上的那个对象版本；PENDING 时必填。 */
    private Integer pendingVersion;

    /** IN_FLIGHT、VALIDATION 或 ERROR；PENDING 时必填。 */
    private String pendingCode;

    /** 给人看的原因，可直接显示。 */
    private String pendingReason;

    private Integer followedVersion;
    private java.time.LocalDateTime followedAt;

    /** 开关的乐观锁。 */
    private Integer lockVersion;
}
