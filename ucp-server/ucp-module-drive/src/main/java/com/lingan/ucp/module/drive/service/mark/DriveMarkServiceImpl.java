package com.lingan.ucp.module.drive.service.mark;

import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertSet;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.common.util.collection.CollectionUtils;
import com.lingan.ucp.module.drive.controller.admin.mark.vo.DriveMarkedEntryRespVO;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.dataobject.mark.DriveUserMarkDO;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.mark.DriveUserMarkMapper;
import com.lingan.ucp.module.drive.enums.entry.DriveTrashStateEnum;
import com.lingan.ucp.module.drive.enums.mark.DriveMarkTypeEnum;
import com.lingan.ucp.module.drive.service.entry.DriveEntryService;
import com.lingan.ucp.module.drive.service.permission.DrivePermissionService;
import com.lingan.ucp.module.drive.service.space.DriveSpaceService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网盘收藏与最近使用 Service 实现类
 *
 * @author os
 */
@Slf4j
@Service
@Validated
public class DriveMarkServiceImpl implements DriveMarkService {

    /** 最近访问列表返回条数上限 */
    private static final int MAX_RECENT_LIMIT = 50;

    /** 最近访问列表默认返回条数 */
    private static final int DEFAULT_RECENT_LIMIT = 20;

    @Resource private DriveUserMarkMapper markMapper;
    @Resource private DriveEntryMapper entryMapper;
    @Resource private DriveSpaceService spaceService;
    @Resource private DrivePermissionService permissionService;
    @Resource private DriveEntryService entryService;

    @Override
    public List<DriveMarkedEntryRespVO> getFavoriteList(Long userId) {
        return buildMarkedList(userId, DriveMarkTypeEnum.FAVORITE);
    }

    @Override
    public List<DriveMarkedEntryRespVO> getRecentList(Long userId, Integer limit) {
        int size =
                limit != null && limit > 0
                        ? Math.min(limit, MAX_RECENT_LIMIT)
                        : DEFAULT_RECENT_LIMIT;
        List<DriveUserMarkDO> marks =
                markMapper.selectRecentList(userId, DriveMarkTypeEnum.RECENT.getCode(), size);
        return buildMarkedList(marks, DriveMarkTypeEnum.RECENT);
    }

    @Override
    public Boolean updateFavorite(Long entryId, Boolean favorite, Long userId) {
        DriveEntryDO entry = entryService.getEntry(entryId, userId);
        boolean target = Boolean.TRUE.equals(favorite);
        DriveUserMarkDO exists =
                markMapper.selectByUserAndEntryAndType(
                        userId, entryId, DriveMarkTypeEnum.FAVORITE.getCode());
        if (target) {
            if (exists == null) {
                markMapper.insert(
                        DriveUserMarkDO.builder()
                                .userId(userId)
                                .entryId(entry.getId())
                                .markType(DriveMarkTypeEnum.FAVORITE.getCode())
                                .build());
            }
            return Boolean.TRUE;
        }
        if (exists != null) {
            markMapper.deleteById(exists.getId());
        }
        return Boolean.FALSE;
    }

    @Override
    public void recordAccess(Long entryId, Long userId) {
        // 访问记录不应影响浏览主流程，节点无权限或已删除时静默跳过
        DriveEntryDO entry = entryMapper.selectById(entryId);
        if (entry == null || DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            return;
        }
        if (permissionService.getEffectiveRole(entry.getSpaceId(), entry.getId(), userId) == null) {
            return;
        }
        DriveUserMarkDO exists =
                markMapper.selectByUserAndEntryAndType(
                        userId, entryId, DriveMarkTypeEnum.RECENT.getCode());
        LocalDateTime now = LocalDateTime.now();
        if (exists == null) {
            markMapper.insert(
                    DriveUserMarkDO.builder()
                            .userId(userId)
                            .entryId(entryId)
                            .markType(DriveMarkTypeEnum.RECENT.getCode())
                            .accessTime(now)
                            .build());
            return;
        }
        markMapper.updateById(DriveUserMarkDO.builder().id(exists.getId()).accessTime(now).build());
    }

    @Override
    public void deleteByEntryIds(List<Long> entryIds) {
        if (CollUtil.isNotEmpty(entryIds)) {
            markMapper.deleteByEntryIds(entryIds);
        }
    }

    private List<DriveMarkedEntryRespVO> buildMarkedList(Long userId, DriveMarkTypeEnum markType) {
        return buildMarkedList(
                markMapper.selectListByUserAndType(userId, markType.getCode()), markType);
    }

    /** 转换标记列表，节点已删除或已失去权限的记录直接跳过，不回显历史名称 */
    private List<DriveMarkedEntryRespVO> buildMarkedList(
            List<DriveUserMarkDO> marks, DriveMarkTypeEnum markType) {
        if (CollUtil.isEmpty(marks)) {
            return List.of();
        }
        List<Long> entryIds = CollectionUtils.convertList(marks, DriveUserMarkDO::getEntryId);
        Map<Long, DriveEntryDO> entryMap = entryService.getEntryMap(entryIds);
        Set<Long> favoriteIds =
                markType == DriveMarkTypeEnum.FAVORITE
                        ? new LinkedHashSet<>(entryIds)
                        : convertSet(
                                markMapper.selectListByUserAndTypeAndEntryIds(
                                        marks.get(0).getUserId(),
                                        DriveMarkTypeEnum.FAVORITE.getCode(),
                                        entryIds),
                                DriveUserMarkDO::getEntryId);

        List<DriveMarkedEntryRespVO> result = new ArrayList<>(marks.size());
        Set<Long> spaceIds = new LinkedHashSet<>();
        for (DriveUserMarkDO mark : marks) {
            DriveEntryDO entry = entryMap.get(mark.getEntryId());
            if (entry == null || DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
                continue;
            }
            if (permissionService.getEffectiveRole(
                            entry.getSpaceId(), entry.getId(), mark.getUserId())
                    == null) {
                continue;
            }
            spaceIds.add(entry.getSpaceId());
            result.add(convertMarkedEntry(entry, mark, favoriteIds.contains(entry.getId())));
        }
        fillSpaceNames(result, spaceIds);
        return result;
    }

    private DriveMarkedEntryRespVO convertMarkedEntry(
            DriveEntryDO entry, DriveUserMarkDO mark, boolean favorite) {
        DriveMarkedEntryRespVO vo = new DriveMarkedEntryRespVO();
        vo.setEntryId(entry.getId());
        vo.setSpaceId(entry.getSpaceId());
        vo.setParentId(entry.getParentId());
        vo.setName(entry.getName());
        vo.setType(entry.getType());
        vo.setSize(entry.getSize());
        vo.setMimeType(entry.getMimeType());
        vo.setFavorite(favorite);
        vo.setAccessTime(mark.getAccessTime());
        return vo;
    }

    private void fillSpaceNames(List<DriveMarkedEntryRespVO> list, Collection<Long> spaceIds) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Map<Long, DriveSpaceDO> spaceMap = spaceService.getSpaceMap(spaceIds);
        for (DriveMarkedEntryRespVO vo : list) {
            DriveSpaceDO space = spaceMap.get(vo.getSpaceId());
            if (space != null) {
                vo.setSpaceName(space.getName());
            }
        }
    }
}
