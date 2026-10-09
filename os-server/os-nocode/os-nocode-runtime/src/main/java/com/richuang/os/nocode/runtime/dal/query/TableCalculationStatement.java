package com.richuang.os.nocode.runtime.dal.query;

import com.richuang.os.nocode.enums.CalculationAggregateEnum;

import java.math.BigDecimal;
import java.util.List;

/** 全量统计参数仅由固定版本元数据编译；候选行在预览中替换原记录而非重复计入。 */
public record TableCalculationStatement(
        RecordStatement record,
        CalculationAggregateEnum aggregate,
        String column,
        String subtractColumn,
        String initialColumn,
        BigDecimal initialValue,
        List<String> orderColumns,
        String rowId,
        String candidateId,
        String candidatePayload,
        boolean next) {}
