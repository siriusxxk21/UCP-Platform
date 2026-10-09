package com.richuang.os.module.drive.service.space;

import com.richuang.os.module.drive.controller.admin.space.vo.DriveBizSpaceCreateReqVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceCreateReqVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceRespVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceUpdateReqVO;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 网盘空间 Service 接口
 *
 * @author os
 */
public interface DriveSpaceService {

    /**
     * 获得个人空间，不存在时按用户自动建立
     *
     * @param userId 用户编号
     * @return 个人空间
     */
    DriveSpaceDO getOrCreatePersonalSpace(Long userId);

    /**
     * 创建团队空间
     *
     * @return 空间编号
     */
    Long createTeamSpace(DriveSpaceCreateReqVO reqVO, Long operatorId);

    /** 创建业务空间，不设归属人且不建立普通网盘授权 */
    Long createBizSpace(DriveBizSpaceCreateReqVO reqVO);

    /** 修改团队空间 */
    void updateTeamSpace(DriveSpaceUpdateReqVO reqVO, Long operatorId);

    /** 删除团队空间，仅允许在空间无内容时删除 */
    void deleteTeamSpace(Long id, Long operatorId);

    /** 获得空间，不存在时抛出业务异常 */
    DriveSpaceDO getSpace(Long id);

    /** 获得空间详情，含归属信息与当前用户的角色；无权访问时抛出业务异常 */
    DriveSpaceRespVO getSpaceDetail(Long id, Long userId);

    /** 获得当前用户可见的空间列表，含个人空间与已授权的团队空间 */
    List<DriveSpaceRespVO> getMySpaceList(Long userId);

    /** 获得当前治理用户可管理的普通空间与全部业务空间 */
    List<DriveSpaceRespVO> getManageSpaceList(Long userId);

    /** 当前用户可进入的业务空间（文件夹区）：在空间根上有角色的业务空间，按名称排序 */
    List<DriveSpaceRespVO> getBusinessSpaceList(Long userId);

    /** 获得空间 Map，供标记与分享列表补齐空间名称 */
    Map<Long, DriveSpaceDO> getSpaceMap(Collection<Long> ids);
}
