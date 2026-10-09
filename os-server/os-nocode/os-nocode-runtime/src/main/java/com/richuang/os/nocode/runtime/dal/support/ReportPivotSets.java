package com.richuang.os.nocode.runtime.dal.support;

import com.richuang.os.nocode.enums.ReportOperationEnum;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 透视表的分组集合：行维度前缀 × 列维度前缀的全部组合，共 (行维度数+1)×(列维度数+1) 组（行+列合计上限 {@link
 * com.richuang.os.nocode.api.ApplicationReports#MAX_PIVOT_DIMENSIONS}，最多 6×6=36 组）。只输出服务端生成的列别名
 * d0..dn/c0..cm，由 XML 渲染 GROUPING SETS；每一层小计与合计都由数据库按原始记录重新聚合，不由叶子值相加。
 */
public final class ReportPivotSets {
    private ReportPivotSets() {}

    public static List<List<String>> groupingSets(ReportStatement statement) {
        int rows = statement.dimensions().size(), columns = statement.columnDimensions().size();
        List<List<String>> sets = new ArrayList<>();
        for (int r = rows; r >= 0; r--)
            for (int c = columns; c >= 0; c--) {
                List<String> set = new ArrayList<>();
                for (int i = 0; i < r; i++) set.add("d" + i);
                for (int i = 0; i < c; i++) set.add("c" + i);
                sets.add(set);
            }
        return sets;
    }

    /** 预聚合的一项局部值：metric 的下标、计算方式、是否带指标条件（p{index}）。公式指标没有局部值，由其引用的指标再聚合后组合。 */
    public record Partial(int index, ReportOperationEnum operation, boolean filtered) {}

    /** 每个非公式指标一项，顺序同 metrics；供 XML 渲染 leaf 的局部值与分组键（去重计数列）。 */
    public static List<Partial> partials(ReportStatement statement) {
        List<Partial> partials = new ArrayList<>();
        for (int i = 0; i < statement.metrics().size(); i++) {
            var operation = ReportOperationEnum.fromCode(statement.metrics().get(i).operation());
            if (operation != ReportOperationEnum.FORMULA)
                partials.add(
                        new Partial(i, operation, statement.predicates().containsKey("m" + i)));
        }
        return partials;
    }

    /**
     * 求和/平均列的物理类型是否精确（整数、numeric 等）：精确时局部和再相加与逐条累加逐位相同，可以预聚合；real / double precision
     * 的累加结果随顺序变化，读不到类型时也按不精确处理。
     */
    public static boolean exactNumber(String nativeType) {
        String type = nativeType == null ? "" : nativeType.trim().toLowerCase(Locale.ROOT);
        return !type.isEmpty()
                && !type.startsWith("real")
                && !type.startsWith("double")
                && !type.startsWith("float");
    }
}
