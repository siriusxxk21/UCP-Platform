package com.lingan.ucp.module.drive.dal.dataobject.permission;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.lingan.ucp.module.drive.enums.permission.DriveSubjectTypeEnum;

import lombok.*;

/**
 * 网盘授权 DO
 *
 * <p>授权到空间或目录节点，对该节点及其子树生效；继承边界由被授权节点的 inherit_parent 决定。
 *
 * @author os
 */
@TableName("drive_permission")
@KeySequence("drive_permission_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrivePermissionDO extends BaseDO {

    public static final Long ENTRY_ID_SPACE = 0L;

    /** 授权编号 */
    private Long id;

    /** 空间编号 */
    private Long spaceId;

    /** 被授权的节点编号，{@link #ENTRY_ID_SPACE} 表示整个空间 */
    private Long entryId;

    /**
     * 授权主体类型
     *
     * <p>枚举 {@link DriveSubjectTypeEnum}
     */
    private String subjectType;

    /** 授权主体编号：用户编号或部门编号 */
    private Long subjectId;

    /**
     * 授权角色
     *
     * <p>枚举 {@link DrivePermissionRoleEnum}
     */
    private String role;

    /**
     * 部门授权是否含下级部门
     *
     * <p>true 时主体所在部门为该授权部门的子孙部门也算命中；用户授权忽略该列
     */
    private Boolean includeChildren;
}
