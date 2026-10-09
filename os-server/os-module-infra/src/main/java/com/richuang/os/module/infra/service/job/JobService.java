package com.richuang.os.module.infra.service.job;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.infra.controller.admin.job.vo.job.JobPageReqVO;
import com.richuang.os.module.infra.controller.admin.job.vo.job.JobSaveReqVO;
import com.richuang.os.module.infra.dal.dataobject.job.JobDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 定时任务 Service 接口
 *
 * @author os
 */
public interface JobService {

    /**
     * 创建定时任务
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createJob(@Valid JobSaveReqVO createReqVO);

    /**
     * 更新定时任务
     *
     * @param updateReqVO 更新信息
     */
    void updateJob(@Valid JobSaveReqVO updateReqVO);

    /**
     * 更新定时任务的状态
     *
     * @param id     任务编号
     * @param status 状态
     */
    void updateJobStatus(Long id, Integer status);

    /**
     * 触发定时任务
     *
     * @param id 任务编号
     */
    void triggerJob(Long id);

    /**
     * 同步定时任务
     *
     * 目的：将本地存储的 Job 信息强制同步到 PowerJob
     */
    void syncJob();

    /**
     * 删除定时任务
     *
     * @param id 编号
     */
    void deleteJob(Long id);

    /**
     * 批量删除定时任务
     *
     * @param ids 编号列表
     */
    void deleteJobList(List<Long> ids);

    /**
     * 获得定时任务
     *
     * @param id 编号
     * @return 定时任务
     */
    JobDO getJob(Long id);

    /**
     * 获得定时任务分页
     *
     * @param pageReqVO 分页查询
     * @return 定时任务分页
     */
    PageResult<JobDO> getJobPage(JobPageReqVO pageReqVO);

}
