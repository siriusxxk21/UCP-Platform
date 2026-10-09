package com.richuang.os.module.infra.service.job;

import cn.hutool.extra.spring.SpringUtil;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.framework.job.core.handler.JobHandler;
import com.richuang.os.framework.job.core.scheduler.PowerJobManager;
import com.richuang.os.framework.job.core.util.CronUtils;
import com.richuang.os.module.infra.controller.admin.job.vo.job.JobPageReqVO;
import com.richuang.os.module.infra.controller.admin.job.vo.job.JobSaveReqVO;
import com.richuang.os.module.infra.dal.dataobject.job.JobDO;
import com.richuang.os.module.infra.dal.mysql.job.JobMapper;
import com.richuang.os.module.infra.enums.job.JobStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.framework.common.util.collection.CollectionUtils.containsAny;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.*;

/**
 * 定时任务 Service 实现类
 *
 * @author os
 */
@Service
@Validated
@Slf4j
public class JobServiceImpl implements JobService {

    @Resource
    private JobMapper jobMapper;

    @Resource
    private PowerJobManager powerJobManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(JobSaveReqVO createReqVO) {
        validateCronExpression(createReqVO.getCronExpression());
        // 1.1 校验唯一性
        if (jobMapper.selectByHandlerName(createReqVO.getHandlerName()) != null) {
            throw exception(JOB_HANDLER_EXISTS);
        }
        // 1.2 校验 JobHandler 是否存在
        validateJobHandlerExists(createReqVO.getHandlerName());

        // 2. 插入 JobDO
        JobDO job = BeanUtils.toBean(createReqVO, JobDO.class);
        job.setStatus(JobStatusEnum.INIT.getStatus());
        fillJobMonitorTimeoutEmpty(job);
        jobMapper.insert(job);

        // 3.1 创建 PowerJob 任务，本地主键作为执行日志的稳定业务标识
        Long powerJobId = powerJobManager.addJob(job.getId(), job.getName(), job.getHandlerName(),
                job.getHandlerParam(), job.getCronExpression(), job.getRetryCount(), job.getRetryInterval(), true);
        // 3.2 保存 PowerJob 标识并启用本地任务
        JobDO updateObj = JobDO.builder().id(job.getId()).powerjobJobId(powerJobId)
                .status(JobStatusEnum.NORMAL.getStatus()).build();
        jobMapper.updateById(updateObj);
        return job.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateJob(JobSaveReqVO updateReqVO) {
        validateCronExpression(updateReqVO.getCronExpression());
        // 1.1 校验存在
        JobDO job = validateJobExists(updateReqVO.getId());
        // 1.2 保持旧接口语义：暂停状态不允许修改，避免远程任务被意外重新启用
        if (!job.getStatus().equals(JobStatusEnum.NORMAL.getStatus())) {
            throw exception(JOB_UPDATE_ONLY_NORMAL_STATUS);
        }
        // 1.3 校验 JobHandler 是否存在
        validateJobHandlerExists(updateReqVO.getHandlerName());

        // 2. 更新 JobDO
        JobDO updateObj = BeanUtils.toBean(updateReqVO, JobDO.class);
        fillJobMonitorTimeoutEmpty(updateObj);
        jobMapper.updateById(updateObj);

        // 3. 更新 PowerJob；历史未迁移任务在首次修改时自动创建
        Long powerJobId = savePowerJob(job.getPowerjobJobId(), job.getId(), updateReqVO.getName(),
                updateReqVO.getHandlerName(), updateReqVO.getHandlerParam(), updateReqVO.getCronExpression(),
                updateReqVO.getRetryCount(), updateReqVO.getRetryInterval(), true);
        if (job.getPowerjobJobId() == null) {
            jobMapper.updateById(JobDO.builder().id(job.getId()).powerjobJobId(powerJobId).build());
        }
    }

    private void validateJobHandlerExists(String handlerName) {
        try {
            Object handler = SpringUtil.getBean(handlerName);
            assert handler != null;
            if (!(handler instanceof JobHandler)) {
                throw exception(JOB_HANDLER_BEAN_TYPE_ERROR);
            }
        } catch (NoSuchBeanDefinitionException e) {
            throw exception(JOB_HANDLER_BEAN_NOT_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateJobStatus(Long id, Integer status) {
        // 校验 status
        if (!containsAny(status, JobStatusEnum.NORMAL.getStatus(), JobStatusEnum.STOP.getStatus())) {
            throw exception(JOB_CHANGE_STATUS_INVALID);
        }
        // 校验存在
        JobDO job = validateJobExists(id);
        // 校验是否已经为当前状态
        if (job.getStatus().equals(status)) {
            throw exception(JOB_CHANGE_STATUS_EQUALS);
        }
        // 先更新 PowerJob，远程操作成功后再落库，避免页面状态与调度中心不一致
        if (JobStatusEnum.NORMAL.getStatus().equals(status)) { // 开启
            powerJobManager.resumeJob(job.getPowerjobJobId());
        } else { // 暂停
            powerJobManager.pauseJob(job.getPowerjobJobId());
        }
        jobMapper.updateById(JobDO.builder().id(id).status(status).build());
    }

    @Override
    public void triggerJob(Long id) {
        // 校验存在
        JobDO job = validateJobExists(id);

        powerJobManager.triggerJob(job.getPowerjobJobId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncJob() {
        // 1. 查询 Job 配置
        List<JobDO> jobList = jobMapper.selectList();

        // 2. 遍历处理
        for (JobDO job : jobList) {
            boolean enabled = JobStatusEnum.NORMAL.getStatus().equals(job.getStatus());
            Long powerJobId = savePowerJob(job.getPowerjobJobId(), job.getId(), job.getName(),
                    job.getHandlerName(), job.getHandlerParam(), job.getCronExpression(), job.getRetryCount(),
                    job.getRetryInterval(), enabled);
            if (job.getPowerjobJobId() == null) {
                jobMapper.updateById(JobDO.builder().id(job.getId()).powerjobJobId(powerJobId).build());
            }
            log.info("[syncJob][id({}) powerJobId({}) handlerName({}) 同步完成]",
                    job.getId(), powerJobId, job.getHandlerName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJob(Long id) {
        // 校验存在
        JobDO job = validateJobExists(id);
        powerJobManager.deleteJob(job.getPowerjobJobId());
        jobMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobList(List<Long> ids) {
        // 批量删除
        List<JobDO> jobs = jobMapper.selectByIds(ids);
        for (JobDO job : jobs) {
            powerJobManager.deleteJob(job.getPowerjobJobId());
        }
        jobMapper.deleteByIds(ids);
    }

    private JobDO validateJobExists(Long id) {
        JobDO job = jobMapper.selectById(id);
        if (job == null) {
            throw exception(JOB_NOT_EXISTS);
        }
        return job;
    }

    private void validateCronExpression(String cronExpression) {
        if (!CronUtils.isValid(cronExpression)) {
            throw exception(JOB_CRON_EXPRESSION_VALID);
        }
    }

    @Override
    public JobDO getJob(Long id) {
        return jobMapper.selectById(id);
    }

    @Override
    public PageResult<JobDO> getJobPage(JobPageReqVO pageReqVO) {
        return jobMapper.selectPage(pageReqVO);
    }

    private static void fillJobMonitorTimeoutEmpty(JobDO job) {
        if (job.getMonitorTimeout() == null) {
            job.setMonitorTimeout(0);
        }
    }

    private Long savePowerJob(Long powerJobId, Long jobId, String jobName, String handlerName,
                              String handlerParam, String cronExpression, Integer retryCount,
                              Integer retryInterval, boolean enabled) {
        if (powerJobId == null) {
            return powerJobManager.addJob(jobId, jobName, handlerName, handlerParam, cronExpression,
                    retryCount, retryInterval, enabled);
        }
        powerJobManager.updateJob(powerJobId, jobId, jobName, handlerName, handlerParam, cronExpression,
                retryCount, retryInterval, enabled);
        return powerJobId;
    }

}
