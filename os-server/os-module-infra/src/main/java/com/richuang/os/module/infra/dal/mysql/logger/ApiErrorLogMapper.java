package com.richuang.os.module.infra.dal.mysql.logger;

import cn.hutool.core.collection.CollUtil;
import com.richuang.os.framework.common.pojo.PageParam;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.infra.controller.admin.logger.vo.apierrorlog.ApiErrorLogPageReqVO;
import com.richuang.os.module.infra.dal.dataobject.logger.ApiErrorLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API 错误日志 Mapper
 *
 * @author os
 */
@Mapper
public interface ApiErrorLogMapper extends BaseMapperX<ApiErrorLogDO> {

    default PageResult<ApiErrorLogDO> selectPage(ApiErrorLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ApiErrorLogDO>()
                .eqIfPresent(ApiErrorLogDO::getUserId, reqVO.getUserId())
                .eqIfPresent(ApiErrorLogDO::getUserType, reqVO.getUserType())
                .eqIfPresent(ApiErrorLogDO::getApplicationName, reqVO.getApplicationName())
                .likeIfPresent(ApiErrorLogDO::getRequestUrl, reqVO.getRequestUrl())
                .betweenIfPresent(ApiErrorLogDO::getExceptionTime, reqVO.getExceptionTime())
                .eqIfPresent(ApiErrorLogDO::getProcessStatus, reqVO.getProcessStatus())
                .orderByDesc(ApiErrorLogDO::getId)
        );
    }

    /**
     * 物理删除指定时间之前的日志
     *
     * @param createTime 最大时间
     * @param limit      删除条数，防止一次删除太多
     * @return 删除条数
     */
    default Integer deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") Integer limit) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(1);
        pageParam.setPageSize(limit != null && limit > 0 ? limit : 1000);
        PageResult<ApiErrorLogDO> pageResult = selectPage(pageParam, new LambdaQueryWrapperX<ApiErrorLogDO>()
                .select(ApiErrorLogDO::getId)
                .lt(ApiErrorLogDO::getCreateTime, createTime));
        if (CollUtil.isEmpty(pageResult.getList())) {
            return 0;
        }
        List<Long> ids = pageResult.getList().stream().map(ApiErrorLogDO::getId).collect(Collectors.toList());
        return deleteBatchIds(ids);
    }

}
