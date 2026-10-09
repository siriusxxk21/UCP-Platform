package com.lingan.ucp.module.msg.web.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingan.ucp.module.msg.web.entity.SysMsgTemplateTarget;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消息模板默认接收对象数据访问接口。
 */
@Mapper
public interface SysMsgTemplateTargetMapper extends BaseMapper<SysMsgTemplateTarget> {

    /** 配置替换需要物理删除，避免逻辑删除记录占用唯一键。 */
    @Delete("DELETE FROM sys_msg_template_target WHERE tenant_id = #{tenantId} AND msg_template_id = #{templateId}")
    int deleteByTemplate(@Param("tenantId") Long tenantId, @Param("templateId") Long templateId);
}
