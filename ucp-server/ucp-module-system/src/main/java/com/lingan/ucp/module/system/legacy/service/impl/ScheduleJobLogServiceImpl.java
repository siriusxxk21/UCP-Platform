package com.lingan.ucp.module.system.legacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.common.tenant.TenantContext;
import com.lingan.ucp.module.system.legacy.entity.SysScheduleJobLog;
import com.lingan.ucp.module.system.legacy.mapper.SysScheduleJobLogMapper;
import com.lingan.ucp.module.system.legacy.service.ScheduleJobLogService;
import com.lingan.ucp.module.system.legacy.vo.ScheduleJobLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 定时任务执行日志服务实现
 */
@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class ScheduleJobLogServiceImpl implements ScheduleJobLogService {

    private final SysScheduleJobLogMapper logMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Page<ScheduleJobLogVO> list(String tenantId, String sceneId, Integer pageNum, Integer pageSize) {
        Page<SysScheduleJobLog> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<SysScheduleJobLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysScheduleJobLog::getTenantId, tenantId);

        if (sceneId != null) {
            wrapper.eq(SysScheduleJobLog::getSceneId, sceneId);
        }

        wrapper.orderByDesc(SysScheduleJobLog::getExecuteTime);

        Page<SysScheduleJobLog> result = logMapper.selectPage(page, wrapper);
        return (Page<ScheduleJobLogVO>) result.convert(this::convertToVO);
    }

    @Override
    public ScheduleJobLogVO getDetail(String id) {
        SysScheduleJobLog log = logMapper.selectById(id);
        return log != null ? convertToVO(log) : null;
    }

    @Override
    public String createLog(String sceneId, String sceneName, String jobType, String triggerType,
                            Object variableSnapshot) {
        SysScheduleJobLog log = new SysScheduleJobLog();
        log.setTenantId(TenantContext.getTenantId());
        log.setSceneId(sceneId);
        log.setSceneName(sceneName);
        log.setJobType(jobType);
        log.setTriggerType(triggerType);
        log.setExecuteTime(LocalDateTime.now());
        log.setStatus(0); // 执行中

        try {
            if (variableSnapshot != null) {
                log.setVariableSnapshot(objectMapper.writeValueAsString(variableSnapshot));
            }
        } catch (JsonProcessingException e) {
            ScheduleJobLogServiceImpl.log.error("JSON序列化失败", e);
        }

        logMapper.insert(log);
        return log.getId();
    }

    @Override
    public void updateResult(String logId, Integer status, String messageId, Integer receiverCount,
                             String errorMessage, Integer duration) {
        SysScheduleJobLog log = logMapper.selectById(logId);
        if (log == null) {
            return;
        }

        log.setStatus(status);
        log.setMessageId(messageId);
        log.setReceiverCount(receiverCount);
        log.setErrorMessage(errorMessage);
        log.setDuration(duration);

        logMapper.updateById(log);
    }

    /**
     * 转换为VO
     */
    private ScheduleJobLogVO convertToVO(SysScheduleJobLog entity) {
        ScheduleJobLogVO vo = new ScheduleJobLogVO();
        BeanUtils.copyProperties(entity, vo, "variableSnapshot");

        // 设置状态描述
        switch (entity.getStatus()) {
            case 0:
                vo.setStatusDesc("执行中");
                break;
            case 1:
                vo.setStatusDesc("成功");
                break;
            case 2:
                vo.setStatusDesc("部分成功");
                break;
            default:
                vo.setStatusDesc("失败");
        }

        try {
            if (entity.getVariableSnapshot() != null) {
                vo.setVariableSnapshot(objectMapper.readValue(entity.getVariableSnapshot(),
                        new TypeReference<Map<String, Object>>() {
                        }));
            }
        } catch (JsonProcessingException e) {
            log.error("JSON解析失败", e);
        }

        return vo;
    }
}
