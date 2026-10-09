package com.lingan.ucp.module.drive.service.permission;

import com.lingan.ucp.module.drive.controller.admin.permission.vo.DrivePermissionRespVO;
import com.lingan.ucp.module.drive.controller.admin.permission.vo.DrivePermissionSaveReqVO;
import com.lingan.ucp.module.drive.controller.admin.permission.vo.DrivePermissionSourceRespVO;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;

import java.util.List;
import java.util.Set;

/**
 * 网盘授权 Service 接口
 *
 * <p>网盘的全部内容访问都必须经过本接口判定：空间归属、节点授权、组织内分享三者合并为有效角色。
 *
 * @author os
 */
public interface DrivePermissionService {

    /**
     * 获得用户在节点上的有效角色
     *
     * @param spaceId 空间编号
     * @param entryId 节点编号，0 表示空间根
     * @param userId 用户编号
     * @return 有效角色；无任何权限时返回 null
     */
    DrivePermissionRoleEnum getEffectiveRole(Long spaceId, Long entryId, Long userId);

    /** 校验用户在节点上是否具备所需角色，不满足时抛出业务异常 */
    void validatePermission(
            Long spaceId, Long entryId, Long userId, DrivePermissionRoleEnum required);

    /** 获得用户可访问的空间编号集合 */
    Set<Long> getAccessibleSpaceIds(Long userId);

    /** 获得节点上直接配置的授权列表，含主体与节点名称；需对该节点可查看 */
    List<DrivePermissionRespVO> getPermissionList(Long spaceId, Long entryId, Long userId);

    /** 获得节点上的授权来源列表，含空间归属主体与继承而来的授权；需对该节点可查看 */
    List<DrivePermissionSourceRespVO> getPermissionSourceList(
            Long spaceId, Long entryId, Long userId);

    /**
     * 新增或修改节点授权
     *
     * @return 授权编号
     */
    Long savePermission(DrivePermissionSaveReqVO reqVO, Long operatorId);

    /** 移除节点授权 */
    void deletePermission(Long id, Long operatorId);
}
