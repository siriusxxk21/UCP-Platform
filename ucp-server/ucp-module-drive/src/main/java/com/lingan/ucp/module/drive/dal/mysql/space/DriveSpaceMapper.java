package com.lingan.ucp.module.drive.dal.mysql.space;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;

import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 网盘空间 Mapper
 *
 * @author os
 */
@Mapper
public interface DriveSpaceMapper extends BaseMapperX<DriveSpaceDO> {

    default DriveSpaceDO selectByOwnerIdAndType(Long ownerId, String type) {
        return selectOne(
                new LambdaQueryWrapperX<DriveSpaceDO>()
                        .eq(DriveSpaceDO::getOwnerId, ownerId)
                        .eq(DriveSpaceDO::getType, type));
    }

    /** 按名称与类型查询空间，用于业务空间的取回或建立 */
    default DriveSpaceDO selectByNameAndType(String name, String type) {
        return selectOne(
                new LambdaQueryWrapperX<DriveSpaceDO>()
                        .eq(DriveSpaceDO::getName, name)
                        .eq(DriveSpaceDO::getType, type));
    }

    /** 业务空间名称唯一；同名并发建立冲突后按名称重查。 */
    default DriveSpaceDO selectBizByName(String name) {
        return selectByNameAndType(name, "BIZ");
    }

    default List<DriveSpaceDO> selectBizList() {
        return selectList(
                new LambdaQueryWrapperX<DriveSpaceDO>()
                        .eq(DriveSpaceDO::getType, "BIZ")
                        .orderByAsc(DriveSpaceDO::getName));
    }

    /** 查询归属指定用户的全部空间 */
    default List<DriveSpaceDO> selectListByOwnerId(Long ownerId) {
        return selectList(
                new LambdaQueryWrapperX<DriveSpaceDO>().eq(DriveSpaceDO::getOwnerId, ownerId));
    }

    default List<DriveSpaceDO> selectListByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveSpaceDO>()
                        .in(DriveSpaceDO::getId, ids)
                        .orderByDesc(DriveSpaceDO::getId));
    }

    /**
     * 按增量调整已用容量，扣减时不低于 0
     *
     * <p>使用数据库自增避免并发上传相互覆盖。
     */
    int updateUsedBytes(Long id, long delta);
}
