package com.lingan.ucp.nocode.api;

import java.util.List;

/** 数据中心生命周期的只读预检；结果不能代替保存、发布和管理动作的执行时复检。 */
public final class ObjectOperationPreview {
    private ObjectOperationPreview() {}

    /** proposed 仅供字段动作模拟完整草稿，绝不通过预检保存。物理标识始终从服务端读取。 */
    public record Request(
            String objectId,
            Integer expectedLockVersion,
            String operation,
            String fieldId,
            String detailId,
            DataCenter.SaveDesign proposed) {}

    /** allowed 仅表示当前已检出的生命周期条件；执行仍使用原接口和修订号。 */
    public record Result(
            String objectId,
            int revision,
            String operation,
            boolean allowed,
            String summary,
            List<Impact> impacts,
            List<DataScope> dataScopes,
            List<String> steps) {}

    /** route 指向已有配置入口；业务数据修正和应用调整仍由对应中心及权限控制。 */
    public record Impact(
            String code,
            boolean blocking,
            String sourceKind,
            String sourceId,
            String sourceName,
            String fieldId,
            String location,
            String message,
            String resolution,
            String route) {}

    /** 聚合覆盖全部物理行，包含逻辑删除；计数不可得为 null，不得解释成零。 */
    public record DataScope(
            String kind,
            String name,
            String schemaName,
            String tableName,
            String columnName,
            Long rowCount,
            Long nonNullCount,
            boolean retained,
            String message) {}
}
