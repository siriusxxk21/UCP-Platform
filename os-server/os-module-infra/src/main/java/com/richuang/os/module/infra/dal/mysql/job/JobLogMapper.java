package com.richuang.os.module.infra.dal.mysql.job;

import cn.hutool.core.collection.CollUtil;
import com.richuang.os.framework.common.pojo.PageParam;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.infra.controller.admin.job.vo.log.JobLogPageReqVO;
import com.richuang.os.module.infra.dal.dataobject.job.JobLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务日志 Mapper
 *
 * @author os
 */
@Mapper
public interface JobLogMapper extends BaseMapperX<JobLogDO> {

    default PageResult<JobLogDO> selectPage(JobLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<JobLogDO>()
                .eqIfPresent(JobLogDO::getJobId, reqVO.getJobId())
                .likeIfPresent(JobLogDO::getHandlerName, reqVO.getHandlerName())
                .geIfPresent(JobLogDO::getBeginTime, reqVO.getBeginTime())
                .leIfPresent(JobLogDO::getEndTime, reqVO.getEndTime())
                .eqIfPresent(JobLogDO::getStatus, reqVO.getStatus())
                .orderByDesc(JobLogDO::getId) // ID 倒序
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
        PageResult<JobLogDO> pageResult = selectPage(pageParam, new LambdaQueryWrapperX<JobLogDO>()
                .select(JobLogDO::getId)
                .lt(JobLogDO::getCreateTime, createTime));
        if (CollUtil.isEmpty(pageResult.getList())) {
            return 0;
        }
        List<Long> ids = pageResult.getList().stream().map(JobLogDO::getId).collect(Collectors.toList());
        return deleteBatchIds(ids);
    }

}
