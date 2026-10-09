package com.richuang.os.framework.mybatis.core.metadata;

import com.baomidou.mybatisplus.annotation.DbType;

import java.util.List;
import java.util.Optional;

/** 当前数据源的元数据读取契约，使用底座 MyBatis 会话和事务。 不接受连接凭据、SQL 片段或写结构指令；DDL 发布与业务记录读写使用各自的业务入口。 */
public interface DatabaseMetadataReader {
    DbType databaseType();

    /** 当前账号可使用的非系统 schema，不接受另一数据源。 */
    List<String> listSchemas();

    /** 检查任何占用该关系名称的对象，包含表、视图、序列、索引；名称按精确值匹配。 */
    boolean relationExists(String schema, String name);

    boolean isReservedIdentifier(String name);

    /** schema 显式指定；nameContains 是普通子串，百分号和下划线不作为通配符。 */
    List<DatabaseMetadata.Relation> listRelations(String schema, String nameContains, int limit);

    /** 表/视图不存在时返回 empty；不会从其他 schema 猜测或补造主键。 */
    Optional<DatabaseMetadata.Table> readTable(String schema, String name);
}
