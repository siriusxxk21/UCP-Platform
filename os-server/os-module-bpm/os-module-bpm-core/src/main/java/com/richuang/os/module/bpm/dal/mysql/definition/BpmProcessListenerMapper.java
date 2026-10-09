package com.richuang.os.module.bpm.dal.mysql.definition;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.bpm.controller.admin.definition.vo.listener.BpmProcessListenerPageReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessListenerDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * BPM 流程监听器 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface BpmProcessListenerMapper extends BaseMapperX<BpmProcessListenerDO> {

    default PageResult<BpmProcessListenerDO> selectPage(BpmProcessListenerPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<BpmProcessListenerDO>()
                .likeIfPresent(BpmProcessListenerDO::getName, reqVO.getName())
                .eqIfPresent(BpmProcessListenerDO::getType, reqVO.getType())
                .eqIfPresent(BpmProcessListenerDO::getEvent, reqVO.getEvent())
                .eqIfPresent(BpmProcessListenerDO::getStatus, reqVO.getStatus())
                .orderByDesc(BpmProcessListenerDO::getId));
    }

}