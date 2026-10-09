package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.lingan.ucp.nocode.runtime.dal.support.ReportPivotSets;

import org.junit.jupiter.api.Test;

/** 透视表预聚合只在求和/平均列是精确数值时启用；浮点列的累加结果随顺序变化，必须退回逐条分组集合。 */
class ReportPivotSetsTest {
    @Test
    void exactNumericTypesAllowRollupButFloatingAndUnknownTypesDoNot() {
        for (var type :
                new String[] {
                    "bigint", "integer", "smallint", "numeric", "numeric(30,4)", "NUMERIC(12, 2)"
                }) assertThat(ReportPivotSets.exactNumber(type)).as(type).isTrue();
        for (var type :
                new String[] {"double precision", "real", "float8", "float4", "DOUBLE PRECISION"})
            assertThat(ReportPivotSets.exactNumber(type)).as(type).isFalse();
        assertThat(ReportPivotSets.exactNumber(null)).isFalse();
        assertThat(ReportPivotSets.exactNumber(" ")).isFalse();
    }
}
