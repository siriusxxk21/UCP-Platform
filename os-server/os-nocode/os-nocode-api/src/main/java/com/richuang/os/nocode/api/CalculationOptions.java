package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 公式只接受声明式配置。字段使用对象内稳定编码；跨表对象必须在应用发布版本内。 */
public record CalculationOptions(
        String mode,
        String updateMode,
        String targetObjectId,
        String relationId,
        String targetField,
        String aggregate,
        String logic,
        List<Match> conditions,
        Boolean excludeCurrent,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> groupFields,
        @JsonInclude(JsonInclude.Include.NON_NULL) RunningTotal runningTotal,
        @JsonInclude(JsonInclude.Include.NON_NULL) Sequence sequence) {
    /** 保留旧构造方式，已发布计算配置和已有调用无需补写顺序计算属性。 */
    public CalculationOptions(
            String mode,
            String updateMode,
            String targetObjectId,
            String relationId,
            String targetField,
            String aggregate,
            String logic,
            List<Match> conditions,
            Boolean excludeCurrent,
            List<String> groupFields,
            RunningTotal runningTotal) {
        this(
                mode,
                updateMode,
                targetObjectId,
                relationId,
                targetField,
                aggregate,
                logic,
                conditions,
                excludeCurrent,
                groupFields,
                runningTotal,
                null);
    }

    public CalculationOptions {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        groupFields = groupFields == null ? List.of() : List.copyOf(groupFields);
    }

    /** localField 与常量 value 二选一；字段编码由元数据校验，不能用 SQL 路径。 */
    public record Match(String targetField, String operator, String localField, Object value) {}

    /** 期初取固定值或组内首笔字段且只加一次；排序始终升序并追加物理主键消除同值歧义。 */
    public record RunningTotal(
            String orderField,
            String tieBreakerField,
            String subtractField,
            String initialValue,
            String initialField) {}

    /** 相邻计算兼容旧配置；累计计算以 expression 为逐笔贡献，期初只加入一次。 */
    public record Sequence(
            String orderField,
            String tieBreakerField,
            String direction,
            @JsonInclude(JsonInclude.Include.NON_NULL) String operation,
            @JsonInclude(JsonInclude.Include.NON_NULL) String initialValue) {
        public Sequence(String orderField, String tieBreakerField, String direction) {
            this(orderField, tieBreakerField, direction, null, null);
        }
    }
}
