package com.lingan.ucp.module.drive.dal.dataobject.space;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import com.lingan.ucp.module.drive.enums.space.DriveSpaceTypeEnum;

import lombok.*;

/**
 * 网盘空间 DO
 *
 * <p>个人空间按用户唯一，团队空间按部门或责任人建立并依赖授权共享。
 *
 * @author os
 */
@TableName("drive_space")
@KeySequence("drive_space_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveSpaceDO extends BaseDO {

    /** 空间编号 */
    private Long id;

    /** 空间名称 */
    private String name;

    /**
     * 空间类型
     *
     * <p>枚举 {@link DriveSpaceTypeEnum}
     */
    private String type;

    /** 归属主体用户编号：个人空间的所属用户，团队空间的责任人 */
    private Long ownerId;

    /** 归属部门编号：团队空间所属部门，个人空间为空 */
    private Long ownerDeptId;

    /**
     * 容量配额（字节），0 表示不限制
     *
     * <p>V1 只统计与展示，不做上传拦截
     */
    private Long quotaBytes;

    /** 已用容量（字节），回收站内容不计入 */
    private Long usedBytes;

    /**
     * 空间状态
     *
     * <p>枚举 {@link com.lingan.ucp.framework.common.enums.CommonStatusEnum}
     */
    private Integer status;
}
