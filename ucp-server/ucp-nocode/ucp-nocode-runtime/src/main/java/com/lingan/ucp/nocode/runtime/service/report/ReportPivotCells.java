package com.lingan.ucp.nocode.runtime.service.report;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.enums.ReportPivotPercentEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 透视格装配：识别每个格所在的行/列层级、按开关决定输出、计算占比。 数值全部来自 SQL 的 GROUPING SETS（各层按原始记录重新聚合），这里不做任何相加；
 * 占比分母取同一次查询里的行合计列组、列合计行或总计格。
 */
final class ReportPivotCells {
    /** 占比保留 10 位小数（四舍五入），去掉末尾 0；分母为 0 或任一方为空时为 null。 */
    static final int RATIO_SCALE = 10;

    private ReportPivotCells() {}

    /** SQL 返回的一格：rowKeys/columnKeys 已按 GROUPING 标志截成前缀。 */
    record Raw(List<String> rowKeys, List<String> columnKeys, Map<String, String> values) {}

    /**
     * 每格是扁平数组：[行 GROUPING 标志 × rowDimensions, 列 GROUPING 标志 × columnDimensions, 行键 × rowDimensions,
     * 列键 × columnDimensions, 指标值 × metricIds（顺序同 metrics）]，与 ReportMapper.xml 的 pivot 输出一致。
     */
    static List<Raw> parse(
            JsonNode cells, int rowDimensions, int columnDimensions, List<String> metricIds) {
        int flags = rowDimensions + columnDimensions;
        int width = flags * 2 + metricIds.size();
        List<Raw> result = new ArrayList<>();
        for (var cell : cells) {
            if (!cell.isArray() || cell.size() != width) throw invalid("透视格结构无效");
            int rows = prefix(cell, 0, rowDimensions);
            int columns = prefix(cell, rowDimensions, columnDimensions);
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < metricIds.size(); i++) {
                var value = cell.get(flags * 2 + i);
                values.put(
                        metricIds.get(i), value == null || value.isNull() ? null : value.asText());
            }
            result.add(
                    new Raw(
                            keys(cell, flags, rows),
                            keys(cell, flags + rowDimensions, columns),
                            Collections.unmodifiableMap(values)));
        }
        return result;
    }

    /** GROUPING 标志为 0 的维度参与分组；分组集合总是前缀形状，否则说明 SQL 与装配不一致。 */
    private static int prefix(JsonNode flags, int offset, int size) {
        int length = 0;
        boolean rolled = false;
        for (int i = 0; i < size; i++) {
            boolean grouped = flags.get(offset + i).asInt() == 0;
            if (grouped && rolled) throw invalid("透视分组层级无效");
            if (grouped) length++;
            else rolled = true;
        }
        return length;
    }

    private static List<String> keys(JsonNode cell, int offset, int length) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            var key = cell.get(offset + i);
            keys.add(key == null || key.isNull() ? null : key.asText());
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * 按开关输出：叶子总是输出；行前缀更短时，[] 为列合计行（columnTotals），其余为小计（subtotals）； 列前缀同理，[] 为行合计列组（rowTotals）。
     * 输出顺序：行按叶子顺序、小计紧随其组、合计行最后；列同理。
     */
    static List<ApplicationReports.PivotCell> cells(
            List<Raw> raw,
            int rowDimensions,
            int columnDimensions,
            ApplicationReports.Pivot settings,
            List<List<String>> rows,
            List<List<String>> columns) {
        Map<List<List<String>>, Map<String, String>> index = new HashMap<>();
        for (var cell : raw) index.put(key(cell.rowKeys(), cell.columnKeys()), cell.values());
        var percent = ReportPivotPercentEnum.fromCode(settings.percent());
        var rowPositions = new Positions(rows, rowDimensions);
        var columnPositions = new Positions(columns, columnDimensions);
        List<Placed> shown = new ArrayList<>();
        for (var cell : raw)
            if (rowShown(cell.rowKeys().size(), rowDimensions, settings)
                    && columnShown(cell.columnKeys().size(), columnDimensions, settings))
                shown.add(
                        new Placed(
                                cell,
                                rowPositions.of(cell.rowKeys()),
                                columnPositions.of(cell.columnKeys())));
        // 位次按前缀查表一次算好（格数 × 叶子数的逐个比对在多维度时是秒级）；比较器只比整数。
        shown.sort(Comparator.comparingInt(Placed::row).thenComparingInt(Placed::column));
        List<ApplicationReports.PivotCell> result = new ArrayList<>();
        for (var placed : shown) {
            var cell = placed.cell();
            Map<String, String> ratios = null;
            if (percent != ReportPivotPercentEnum.NONE) {
                var denominator =
                        index.get(
                                switch (percent) {
                                    case ROW -> key(cell.rowKeys(), List.of());
                                    case COLUMN -> key(List.of(), cell.columnKeys());
                                    default -> key(List.of(), List.of());
                                });
                ratios = new LinkedHashMap<>();
                for (var entry : cell.values().entrySet())
                    ratios.put(
                            entry.getKey(),
                            ratio(
                                    entry.getValue(),
                                    denominator == null ? null : denominator.get(entry.getKey())));
            }
            result.add(
                    new ApplicationReports.PivotCell(
                            cell.rowKeys(), cell.columnKeys(), cell.values(), ratios));
        }
        return result;
    }

    static boolean rowShown(int length, int dimensions, ApplicationReports.Pivot settings) {
        if (length == dimensions) return true;
        return length == 0 ? settings.columnTotals() : settings.subtotals();
    }

    static boolean columnShown(int length, int dimensions, ApplicationReports.Pivot settings) {
        if (length == dimensions) return true;
        return length == 0 ? settings.rowTotals() : settings.subtotals();
    }

    private record Placed(Raw cell, int row, int column) {}

    /**
     * 叶子排在其位置，小计排在所属组最后一个叶子之后（同一叶子后先深层小计、再浅层小计），合计排最后。 每个叶子占 dimensions+1 个位次：0 为叶子本身，dimensions-k
     * 为长度 k 的小计。叶子与前缀各查一次表：叶子取首次出现的序号，真前缀取最后一个所属叶子的序号。
     */
    private static final class Positions {
        private final int dimensions, slots, end;
        private final Map<List<String>, Integer> firstLeaf = new HashMap<>();
        private final Map<List<String>, Integer> lastLeaf = new HashMap<>();

        Positions(List<List<String>> leaves, int dimensions) {
            this.dimensions = dimensions;
            this.slots = dimensions + 1;
            this.end = leaves.size() * slots;
            for (int i = 0; i < leaves.size(); i++) {
                var leaf = leaves.get(i);
                firstLeaf.putIfAbsent(new ArrayList<>(leaf.subList(0, dimensions)), i);
                for (int k = 1; k < dimensions; k++)
                    lastLeaf.put(new ArrayList<>(leaf.subList(0, k)), i);
            }
        }

        int of(List<String> prefix) {
            if (prefix.isEmpty() && dimensions > 0) return end;
            if (prefix.size() == dimensions) {
                var index = firstLeaf.get(prefix);
                return index == null ? end : index * slots;
            }
            var last = lastLeaf.get(prefix);
            return last == null ? end : last * slots + dimensions - prefix.size();
        }
    }

    static String ratio(String value, String denominator) {
        if (value == null || denominator == null) return null;
        var d = new BigDecimal(denominator);
        if (d.signum() == 0) return null;
        return new BigDecimal(value)
                .divide(d, RATIO_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static List<List<String>> key(List<String> rows, List<String> columns) {
        return Arrays.asList(new ArrayList<>(rows), new ArrayList<>(columns));
    }
}
