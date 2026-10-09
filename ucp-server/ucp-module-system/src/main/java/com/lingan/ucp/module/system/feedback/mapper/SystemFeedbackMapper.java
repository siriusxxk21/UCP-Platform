package com.lingan.ucp.module.system.feedback.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.module.system.feedback.entity.SystemFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问题反馈数据访问接口。
 */
@Mapper
public interface SystemFeedbackMapper extends BaseMapperX<SystemFeedback> {
}
