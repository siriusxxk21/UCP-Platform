package com.lingan.ucp.module.bpm.dal.mysql.definition;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.group.BpmUserGroupPageReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmUserGroupDO;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户组 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface BpmUserGroupMapper extends BaseMapperX<BpmUserGroupDO> {

    default PageResult<BpmUserGroupDO> selectPage(BpmUserGroupPageReqVO reqVO) {
        return selectPage(
                reqVO,
                new LambdaQueryWrapperX<BpmUserGroupDO>()
                        .likeIfPresent(BpmUserGroupDO::getName, reqVO.getName())
                        .eqIfPresent(BpmUserGroupDO::getStatus, reqVO.getStatus())
                        .betweenIfPresent(BpmUserGroupDO::getCreateTime, reqVO.getCreateTime())
                        .orderByDesc(BpmUserGroupDO::getId));
    }

    default List<BpmUserGroupDO> selectListByStatus(Integer status) {
        // 目录候选需要包含停用组以回显历史引用；未传状态时不添加过滤条件。
        return selectList(
                new LambdaQueryWrapperX<BpmUserGroupDO>()
                        .eqIfPresent(BpmUserGroupDO::getStatus, status));
    }
}
