package com.lingan.ucp.module.drive.dal.mysql.share;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.drive.dal.dataobject.share.DriveShareSubjectDO;

import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 网盘分享接收主体 Mapper
 *
 * @author os
 */
@Mapper
public interface DriveShareSubjectMapper extends BaseMapperX<DriveShareSubjectDO> {

    default List<DriveShareSubjectDO> selectListByShareIds(Collection<Long> shareIds) {
        if (CollUtil.isEmpty(shareIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveShareSubjectDO>()
                        .in(DriveShareSubjectDO::getShareId, shareIds));
    }

    /** 查询与指定主体相关的接收记录，用于汇总"与我共享" */
    default List<DriveShareSubjectDO> selectListBySubjects(
            String subjectType, Collection<Long> subjectIds) {
        if (CollUtil.isEmpty(subjectIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveShareSubjectDO>()
                        .eq(DriveShareSubjectDO::getSubjectType, subjectType)
                        .in(DriveShareSubjectDO::getSubjectId, subjectIds));
    }

    default int deleteByShareIds(Collection<Long> shareIds) {
        if (CollUtil.isEmpty(shareIds)) {
            return 0;
        }
        return deleteBatch(DriveShareSubjectDO::getShareId, shareIds);
    }
}
