package com.richuang.os.module.drive.dal.mysql.permission;

import cn.hutool.core.collection.CollUtil;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.drive.dal.dataobject.permission.DrivePermissionDO;

import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 网盘授权 Mapper
 *
 * @author os
 */
@Mapper
public interface DrivePermissionMapper extends BaseMapperX<DrivePermissionDO> {

    /** 查询节点链上的全部授权，由调用方按主体过滤，避免按主体拼多段 SQL */
    default List<DrivePermissionDO> selectListBySpaceAndEntryIds(
            Long spaceId, Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DrivePermissionDO>()
                        .eq(DrivePermissionDO::getSpaceId, spaceId)
                        .in(DrivePermissionDO::getEntryId, entryIds)
                        .orderByAsc(DrivePermissionDO::getEntryId));
    }

    /** 查询空间内与指定主体相关的授权 */
    default List<DrivePermissionDO> selectListBySpaceAndSubject(
            Long spaceId, String subjectType, Long subjectId) {
        return selectList(
                new LambdaQueryWrapperX<DrivePermissionDO>()
                        .eq(DrivePermissionDO::getSpaceId, spaceId)
                        .eq(DrivePermissionDO::getSubjectType, subjectType)
                        .eq(DrivePermissionDO::getSubjectId, subjectId));
    }

    /** 查询与指定主体相关的全部授权，用于汇总用户可见的团队空间 */
    default List<DrivePermissionDO> selectListBySubjects(
            String subjectType, Collection<Long> subjectIds) {
        if (CollUtil.isEmpty(subjectIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DrivePermissionDO>()
                        .eq(DrivePermissionDO::getSubjectType, subjectType)
                        .in(DrivePermissionDO::getSubjectId, subjectIds));
    }

    default DrivePermissionDO selectByEntryAndSubject(
            Long spaceId, Long entryId, String subjectType, Long subjectId) {
        return selectOne(
                new LambdaQueryWrapperX<DrivePermissionDO>()
                        .eq(DrivePermissionDO::getSpaceId, spaceId)
                        .eq(DrivePermissionDO::getEntryId, entryId)
                        .eq(DrivePermissionDO::getSubjectType, subjectType)
                        .eq(DrivePermissionDO::getSubjectId, subjectId));
    }

    /** 删除节点及其子树上的全部授权，用于节点被彻底删除后的清理 */
    default int deleteByEntryIds(Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return 0;
        }
        return deleteBatch(DrivePermissionDO::getEntryId, entryIds);
    }
}
