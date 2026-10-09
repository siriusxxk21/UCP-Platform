package com.lingan.ucp.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingan.ucp.module.system.legacy.entity.SysScheduleJobLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务执行日志Mapper
 */
// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysScheduleJobLogMapper extends BaseMapper<SysScheduleJobLog> {
}
