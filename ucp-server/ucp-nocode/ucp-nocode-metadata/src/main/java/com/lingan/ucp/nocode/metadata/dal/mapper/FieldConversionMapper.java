package com.lingan.ucp.nocode.metadata.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 字段转换只读取、清空指定列；全物理范围包含可恢复的逻辑删除行。 */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface FieldConversionMapper {
    /** 物理标识由受信对象版本编译；值与分页使用绑定参数。 */
    record Statement(
            String schema,
            String table,
            String column,
            String keyColumn,
            String titleColumn,
            String parentColumn,
            String type,
            String fieldId,
            String actor,
            int offset,
            int limit) {}

    /** 同一物理快照的预期值、约束和分页参数；列名只由服务端已发布定义生成。 */
    record Assessment(
            Statement statement,
            String rule,
            String action,
            boolean targetInteger,
            int scale,
            int integerDigits,
            int limitLength,
            boolean required,
            boolean unique,
            boolean numericTarget,
            boolean textTarget,
            boolean checkSelection,
            boolean selectionRemovedOnly,
            boolean newMultiple,
            String validValues,
            String minimum,
            String maximum,
            String pattern) {}

    /** 各类原因可重叠；failedRows 是按记录去重后的准确并集。 */
    String assess(Assessment assessment);

    /** 与 assess 使用同一个 SQL 值投影与冲突表达式。 */
    List<String> assessedRows(Assessment assessment);

    String statistics(Statement statement);

    /** 不依赖公共审计列的全物理计数，不附加逻辑删除或行权限过滤。 */
    String counts(Statement statement);

    /** 按受控规则检查全物理列；不把解析失败或超出目标范围的值强制转换。 */
    long preservationFailures(
            @Param("statement") Statement statement,
            @Param("rule") String rule,
            @Param("targetInteger") boolean targetInteger,
            @Param("scale") int scale,
            @Param("integerDigits") int integerDigits,
            @Param("limitLength") int limitLength);

    /** 基础转换已核实可解析后，再逐值核对目标最小值、最大值和正则约束。 */
    long targetConstraintFailures(
            @Param("statement") Statement statement,
            @Param("rule") String rule,
            @Param("minimum") String minimum,
            @Param("maximum") String maximum,
            @Param("pattern") String pattern);

    /** 严格文本解析可使原本不同的字符串归一到同一数值，唯一约束须先按转换结果核对。 */
    long parsedNumericDuplicates(Statement statement);

    /** 按转换后的值核验新增唯一约束，避免在 DDL 阶段才发现冲突。 */
    long targetUniqueFailures(@Param("statement") Statement statement, @Param("rule") String rule);

    long invalidSelectionRows(
            @Param("statement") Statement statement,
            @Param("oldMultiple") boolean oldMultiple,
            @Param("validValues") String validValues);

    long emptyMultiRows(Statement statement);

    List<String> rows(Statement statement);

    /** 读取真实生成列依赖，包含已从对象设计停用但仍保留在物理表中的列。 */
    List<String> generatedDependents(Statement statement);

    void releaseReference(
            @Param("statement") Statement statement, @Param("relationId") String relationId);

    void releaseReferenceUnique(
            @Param("statement") Statement statement, @Param("relationId") String relationId);

    void prepare(Statement statement);

    int clear(Statement statement);

    void convert(Statement statement);

    void convertPreserving(@Param("statement") Statement statement, @Param("rule") String rule);
}
