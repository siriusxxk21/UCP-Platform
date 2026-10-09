package com.lingan.ucp.framework.mybatis.core.metadata;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 执行受控工厂生成的命令，沿用调用方的底座事务；没有接收原始 SQL 的公开方法。 */
@Mapper
public interface PostgreSqlCommandMapper {
    // 受控 DDL/表锁没有业务行谓词；跳过行权限 SQL 解析，调用入口仍须校验平台权限。
    @InterceptorIgnore(dataPermission = "true", tenantLine = "true")
    void execute(@Param("command") PostgreSqlCommands.Command command);

    Boolean check(@Param("command") PostgreSqlCommands.Command command);

    List<String> rows(@Param("command") PostgreSqlCommands.Command command);
}
