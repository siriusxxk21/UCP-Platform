package com.lingan.ucp.module.drive.service.share;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveSharePageReqVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareRespVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareUpdateReqVO;

import java.util.List;

/**
 * 网盘分享 Service 接口
 *
 * <p>组织内分享：把节点授权给指定用户或部门，不做匿名外链。分享只是授予来源之一， 实际访问判定统一由授权 Service 完成。
 *
 * @author os
 */
public interface DriveShareService {

    /**
     * 创建分享
     *
     * @return 分享编号
     */
    Long createShare(DriveShareCreateReqVO reqVO, Long userId);

    /** 修改分享的角色与失效时间 */
    void updateShare(DriveShareUpdateReqVO reqVO, Long userId);

    /** 撤销分享 */
    void revokeShare(Long id, Long userId);

    /** 获得我发起的分享分页 */
    PageResult<DriveShareRespVO> getSharePage(DriveSharePageReqVO reqVO, Long userId);

    /** 获得节点上的生效分享列表 */
    List<DriveShareRespVO> getShareListByEntry(Long entryId, Long userId);

    /** 获得分享给当前用户的列表，含直接分享与所在部门收到的分享 */
    List<DriveShareRespVO> getSharedToMeList(Long userId);

    /** 获得分享列表并补齐节点、空间、创建人与接收主体信息，供"我发出的分享"与"与我共享"复用 */
    List<DriveShareRespVO> buildShareRespList(List<Long> shareIds, String viewerId);
}
