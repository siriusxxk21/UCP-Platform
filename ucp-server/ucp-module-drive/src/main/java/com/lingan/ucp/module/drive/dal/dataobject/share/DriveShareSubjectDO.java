package com.lingan.ucp.module.drive.dal.dataobject.share;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import com.lingan.ucp.module.drive.enums.permission.DriveSubjectTypeEnum;

import lombok.*;

/**
 * 网盘分享接收主体 DO
 *
 * <p>与分享一对一展开，支持用户与部门。
 *
 * @author os
 */
@TableName("drive_share_subject")
@KeySequence("drive_share_subject_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveShareSubjectDO extends BaseDO {

    /** 编号 */
    private Long id;

    /** 分享编号 */
    private Long shareId;

    /**
     * 主体类型
     *
     * <p>枚举 {@link DriveSubjectTypeEnum}
     */
    private String subjectType;

    /** 主体编号：用户编号或部门编号 */
    private Long subjectId;
}
