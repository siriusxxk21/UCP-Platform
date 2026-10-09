package com.lingan.ucp.framework.mybatis.core.metadata;

import java.util.List;

/** 数据库实际结构的只读快照；不包含代码生成表单配置或无代码领域属性。 */
public final class DatabaseMetadata {
    private DatabaseMetadata() {}

    /** kind 为数据库真实关系类型：TABLE、PARTITIONED_TABLE、VIEW、MATERIALIZED_VIEW 或 FOREIGN_TABLE。 */
    public record Relation(String schema, String name, String kind, String comment) {}

    /** 原生类型和默认表达式原样返回；主键序号来自约束，0 表示非主键，不能根据 id 字段名猜测。 */
    public record Column(
            String name,
            Integer ordinal,
            String nativeType,
            Boolean nullable,
            String defaultExpression,
            String identityKind,
            String generatedKind,
            String comment,
            Integer primaryKeyPosition) {}

    /** kind 使用数据库类型码 p/u/f/c/x；定义包含复合键、外键目标和检查表达式等真实信息。 */
    public record Constraint(String name, String kind, String definition) {}

    /** 索引定义保留表达式、部分索引条件与列顺序；无效索引不能误报为可用约束。 */
    public record Index(
            String name, Boolean unique, Boolean primary, Boolean valid, String definition) {}

    /** 用户触发器的实际定义；内部外键触发器不重复列出。 */
    public record Trigger(String name, String enabled, String definition) {}

    /** 行数为数据库估算值，行数和容量不参与结构指纹；权限与行安全单独作为结构兼容性事实。 */
    public record Statistics(
            Long estimatedRows,
            Long tableBytes,
            Long indexBytes,
            Long totalBytes,
            Boolean rowSecurity,
            Boolean forceRowSecurity,
            Boolean canSelect,
            Boolean canWrite) {}

    /** 在调用事务可见范围内读取的结构；不宣称能替代发布阶段的结构锁和指纹复核。 */
    public record Table(
            Relation relation,
            List<Column> columns,
            List<Constraint> constraints,
            List<Index> indexes,
            List<Trigger> triggers,
            Statistics statistics) {
        public Table {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
            indexes = List.copyOf(indexes);
            triggers = List.copyOf(triggers);
        }

        public Table(
                Relation relation,
                List<Column> columns,
                List<Constraint> constraints,
                List<Index> indexes) {
            this(relation, columns, constraints, indexes, List.of(), null);
        }
    }
}
