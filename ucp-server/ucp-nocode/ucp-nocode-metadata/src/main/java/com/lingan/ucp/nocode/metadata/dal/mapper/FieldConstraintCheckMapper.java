package com.lingan.ucp.nocode.metadata.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 发布前按完整物理范围检查列约束，包含可恢复的逻辑删除行；只返回计数和记录身份。 */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface FieldConstraintCheckMapper {
    /** 标识仅由已核验对象定义和实际结构产生，规则值使用绑定参数。 */
    record Statement(String schema, String table, String column, String keyColumn, String value) {}

    record Conflict(long total, String recordId) {}

    List<Conflict> belowMinimum(Statement statement);

    List<Conflict> aboveMaximum(Statement statement);

    List<Conflict> patternMismatch(Statement statement);
}
