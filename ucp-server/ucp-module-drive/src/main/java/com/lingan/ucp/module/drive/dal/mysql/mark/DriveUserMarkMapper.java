package com.lingan.ucp.module.drive.dal.mysql.mark;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.drive.dal.dataobject.mark.DriveUserMarkDO;

import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 网盘用户标记 Mapper
 *
 * @author os
 */
@Mapper
public interface DriveUserMarkMapper extends BaseMapperX<DriveUserMarkDO> {

    default List<DriveUserMarkDO> selectListByUserAndType(Long userId, String markType) {
        return selectList(
                new LambdaQueryWrapperX<DriveUserMarkDO>()
                        .eq(DriveUserMarkDO::getUserId, userId)
                        .eq(DriveUserMarkDO::getMarkType, markType));
    }

    default List<DriveUserMarkDO> selectListByUserAndTypeAndEntryIds(
            Long userId, String markType, Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveUserMarkDO>()
                        .eq(DriveUserMarkDO::getUserId, userId)
                        .eq(DriveUserMarkDO::getMarkType, markType)
                        .in(DriveUserMarkDO::getEntryId, entryIds));
    }

    /** 查询最近访问记录，按访问时间倒序 */
    default List<DriveUserMarkDO> selectRecentList(Long userId, String markType, Integer limit) {
        return selectList(
                new LambdaQueryWrapperX<DriveUserMarkDO>()
                        .eq(DriveUserMarkDO::getUserId, userId)
                        .eq(DriveUserMarkDO::getMarkType, markType)
                        .orderByDesc(DriveUserMarkDO::getAccessTime)
                        .last("LIMIT " + limit));
    }

    default DriveUserMarkDO selectByUserAndEntryAndType(
            Long userId, Long entryId, String markType) {
        return selectOne(
                new LambdaQueryWrapperX<DriveUserMarkDO>()
                        .eq(DriveUserMarkDO::getUserId, userId)
                        .eq(DriveUserMarkDO::getEntryId, entryId)
                        .eq(DriveUserMarkDO::getMarkType, markType));
    }

    default int deleteByEntryIds(Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return 0;
        }
        return deleteBatch(DriveUserMarkDO::getEntryId, entryIds);
    }
}
