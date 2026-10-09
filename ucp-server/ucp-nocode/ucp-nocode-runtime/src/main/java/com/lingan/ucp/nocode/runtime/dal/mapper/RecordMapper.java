package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.lingan.ucp.nocode.runtime.dal.query.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 动态业务表不虚构固定 DO；通过 Mapper XML 使用白名单字段，所有值由参数绑定。 */
@Mapper
public interface RecordMapper {
    /** 对已裁剪的父记录集合批量汇总，按当前明细表逻辑删除配置过滤。 */
    List<String> summaries(SummaryStatement statement);

    /** 读取当前关联目标并刷新会话缓存；保留 501 条上限供业务层识别超限。 */
    List<String> relationTargets(RelationStatement statement);

    /** 一次读取多个源记录的目标，维持来源和目标的稳定顺序。 */
    List<String> relationBatchTargets(
            @Param("relation") RelationStatement relation, @Param("ids") String ids);

    /** 反查来源时同时核对源记录的删除状态，不能只依据关联行。 */
    List<String> relationSources(RelationStatement statement);

    /** 恢复或创建唯一关联行，保留原有审计和逻辑删除语义。 */
    int attach(RelationStatement statement);

    /** targetId 为空时解除该来源的全部有效关联，否则仅解除指定目标。 */
    int detach(RelationStatement statement);

    /** 按固定版本字段投影并分页；lock 为真时在原事务中锁定返回行。 */
    List<String> rows(RecordStatement statement);

    /** 仅有序派生审计使用已读出的主键；按真实列类型匹配，保留主键索引。 */
    List<String> orderedRows(RecordStatement statement);

    /** 与 rows 复用同一读取范围和动态条件。 */
    long count(RecordStatement statement);

    /** 全量数据库聚合，不使用列表分页或条件取值的 501 条探测上限。 */
    String statistics(TableCalculationStatement statement);

    /** 固定业务顺序累计后再定位本记录，历史增删改在下次读取时自然生效。 */
    String runningTotal(TableCalculationStatement statement);

    /** 完整分组一次计算，避免为每个结果重复执行累计窗口。 */
    List<String> runningTotals(TableCalculationStatement statement);

    /** 返回含 NULL 的稳定分组及行数，校准按完整组推进。 */
    List<String> orderedGroups(OrderedGroupStatement statement);

    /** 返回稳定顺序中相邻记录的原始字段值；公式仍由受限表达式在服务层执行。 */
    String sequenceValues(TableCalculationStatement statement);

    /** 每个顺序公式分组只读取一次，10001 行用于识别超限，禁止返回部分累计值。 */
    List<String> sequenceRows(TableCalculationStatement statement);

    /** PostgreSQL RETURNING 是实际写操作，显式标记以维护事务和会话缓存。 */
    // INSERT RETURNING 不是行查询，底座查询权限解析器不支持此语句；写入授权仍由运行策略强制执行。
    @InterceptorIgnore(dataPermission = "true")
    String insert(RecordStatement statement);

    /** 仅更新目录映射中允许写入的列，并按原范围维护审计信息。 */
    int update(RecordStatement statement);

    /** 仅有序派生写入已读出的主键，审计字段与普通更新保持相同。 */
    int orderedUpdate(RecordStatement statement);

    /** 在当前记录/关系范围内逻辑删除，保留原物理行。 */
    int delete(RecordStatement statement);
}
