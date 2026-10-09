package com.lingan.ucp.module.drive.dal.mysql.share;

import cn.hutool.core.collection.CollUtil;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveSharePageReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.share.DriveShareDO;
import com.lingan.ucp.module.drive.enums.share.DriveShareStatusEnum;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 网盘分享 Mapper
 *
 * @author os
 */
@Mapper
public interface DriveShareMapper extends BaseMapperX<DriveShareDO> {

    default PageResult<DriveShareDO> selectPage(
            DriveSharePageReqVO reqVO, Collection<String> creatorIds) {
        return selectPage(
                reqVO,
                new LambdaQueryWrapperX<DriveShareDO>()
                        .in(DriveShareDO::getCreator, creatorIds)
                        .eqIfPresent(DriveShareDO::getStatus, reqVO.getStatus())
                        .betweenIfPresent(DriveShareDO::getCreateTime, reqVO.getCreateTime())
                        .orderByDesc(DriveShareDO::getId));
    }

    /** 查询节点上仍然生效的分享 */
    default List<DriveShareDO> selectActiveListByEntryIds(Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveShareDO>()
                        .in(DriveShareDO::getEntryId, entryIds)
                        .eq(DriveShareDO::getStatus, DriveShareStatusEnum.ACTIVE.getCode()));
    }

    default List<DriveShareDO> selectListByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<DriveShareDO>().in(DriveShareDO::getId, ids));
    }

    /** 更新分享的角色与失效时间，失效时间允许被清空 */
    default int updateRoleAndExpireTime(Long id, String role, LocalDateTime expireTime) {
        return update(
                null,
                new LambdaUpdateWrapper<DriveShareDO>()
                        .set(DriveShareDO::getRole, role)
                        .set(DriveShareDO::getExpireTime, expireTime)
                        .eq(DriveShareDO::getId, id));
    }

    /** 查询节点及其子树上的全部分享（含已撤销），用于节点被彻底删除后的清理 */
    default List<DriveShareDO> selectListByEntryIds(Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds)) {
            return List.of();
        }
        return selectList(
                new LambdaQueryWrapperX<DriveShareDO>().in(DriveShareDO::getEntryId, entryIds));
    }
}
