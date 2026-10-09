package com.lingan.ucp.module.infra.dal.mysql.logger;

import cn.hutool.core.collection.CollUtil;
import com.lingan.ucp.framework.common.pojo.PageParam;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.infra.controller.admin.logger.vo.apiaccesslog.ApiAccessLogPageReqVO;
import com.lingan.ucp.module.infra.dal.dataobject.logger.ApiAccessLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API 访问日志 Mapper
 *
 * @author os
 */
@Mapper
public interface ApiAccessLogMapper extends BaseMapperX<ApiAccessLogDO> {

    default PageResult<ApiAccessLogDO> selectPage(ApiAccessLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ApiAccessLogDO>()
                .eqIfPresent(ApiAccessLogDO::getUserId, reqVO.getUserId())
                .eqIfPresent(ApiAccessLogDO::getUserType, reqVO.getUserType())
                .eqIfPresent(ApiAccessLogDO::getApplicationName, reqVO.getApplicationName())
                .likeIfPresent(ApiAccessLogDO::getRequestUrl, reqVO.getRequestUrl())
                .betweenIfPresent(ApiAccessLogDO::getBeginTime, reqVO.getBeginTime())
                .geIfPresent(ApiAccessLogDO::getDuration, reqVO.getDuration())
                .eqIfPresent(ApiAccessLogDO::getResultCode, reqVO.getResultCode())
                .orderByDesc(ApiAccessLogDO::getId)
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
        PageResult<ApiAccessLogDO> pageResult = selectPage(pageParam, new LambdaQueryWrapperX<ApiAccessLogDO>()
                .select(ApiAccessLogDO::getId)
                .lt(ApiAccessLogDO::getCreateTime, createTime));
        if (CollUtil.isEmpty(pageResult.getList())) {
            return 0;
        }
        List<Long> ids = pageResult.getList().stream().map(ApiAccessLogDO::getId).collect(Collectors.toList());
        return deleteBatchIds(ids);
    }

}
