package com.richuang.os.module.drive.service.mark;

import com.richuang.os.module.drive.controller.admin.mark.vo.DriveMarkedEntryRespVO;

import java.util.List;

/**
 * 网盘收藏与最近使用 Service 接口
 *
 * <p>标记只记录用户个人视图状态，不改变节点授权；返回结果一律按当前有效权限过滤。
 *
 * @author os
 */
public interface DriveMarkService {

    /** 获得用户的收藏列表 */
    List<DriveMarkedEntryRespVO> getFavoriteList(Long userId);

    /**
     * 获得用户的最近访问列表
     *
     * @param limit 返回条数上限
     */
    List<DriveMarkedEntryRespVO> getRecentList(Long userId, Integer limit);

    /**
     * 收藏或取消收藏节点
     *
     * @return 操作后的收藏状态
     */
    Boolean updateFavorite(Long entryId, Boolean favorite, Long userId);

    /** 记录节点访问，已存在时仅刷新访问时间 */
    void recordAccess(Long entryId, Long userId);

    /** 清理用户在已删除节点上的标记 */
    void deleteByEntryIds(List<Long> entryIds);
}
