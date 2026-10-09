package com.lingan.ucp.nocode.web;

import com.lingan.ucp.framework.excel.core.util.ExcelUtils;
import com.lingan.ucp.nocode.api.ApplicationReports;

import java.util.*;

/**
 * 透视表导出版式，与屏幕一致：表头层数 = 列维度数 + 1（各层列维度 → 指标），行表头按行维度分列；小计行紧随其组、合计行在最下，
 * 小计列组紧随其组、合计列组在最右。列组表头按层合并：上层格横跨其下全部列组（含该组小计），小计/合计格位于所属组的下一层，
 * 纵向跨到指标层之上。只排版服务端已算好的格，不做任何相加；是否含小计/合计由结果中实际存在的格决定（即报表开关）。
 */
final class ReportPivotWorkbook {
    static final String SUBTOTAL = "小计";
    static final String TOTAL = "合计";

    private ReportPivotWorkbook() {}

    record Sheet(
            List<List<String>> heads, List<List<String>> rows, List<ExcelUtils.HeadMerge> merges) {}

    static Sheet build(ApplicationReports.Result report, String ratioSuffix) {
        var pivot = report.pivot();
        int rowDimensions = pivot.rowDimensionNames().size();
        int columnDimensions = pivot.columnDimensionNames().size();
        Map<List<List<String>>, ApplicationReports.PivotCell> cells = new HashMap<>();
        Set<List<String>> rowKeys = new LinkedHashSet<>(), columnKeys = new LinkedHashSet<>();
        for (var cell : pivot.cells()) {
            cells.put(key(cell.rowKeys(), cell.columnKeys()), cell);
            rowKeys.add(new ArrayList<>(cell.rowKeys()));
            columnKeys.add(new ArrayList<>(cell.columnKeys()));
        }
        var rowLeaves = pivot.rows().stream().map(ApplicationReports.PivotHeader::keys).toList();
        var columnLeaves =
                pivot.columns().stream().map(ApplicationReports.PivotHeader::keys).toList();
        var rows = ordered(rowKeys, rowLeaves, rowDimensions);
        var columns = ordered(columnKeys, columnLeaves, columnDimensions);
        // 行数不设上限后导出可达十万行：表头按键前缀建一次索引，不再每行线性扫描全部叶子。
        var rowHeaders = prefixes(pivot.rows());
        var columnHeaders = prefixes(pivot.columns());
        boolean ratios = pivot.cells().stream().anyMatch(c -> c.ratios() != null);
        int height = columnDimensions + 1;
        List<List<String>> heads = new ArrayList<>();
        List<List<String>> owners = new ArrayList<>();
        for (var name : pivot.rowDimensionNames()) {
            heads.add(Collections.nCopies(height, name));
            owners.add(null);
        }
        for (var column : columns) {
            var path = columnPath(column, columnHeaders, columnDimensions);
            for (var metric : report.metrics()) {
                heads.add(append(path, metric.name()));
                owners.add(column);
                if (ratios) {
                    heads.add(append(path, metric.name() + ratioSuffix));
                    owners.add(column);
                }
            }
        }
        List<List<String>> output = new ArrayList<>();
        for (var row : rows) {
            List<String> line = new ArrayList<>(rowLabels(row, rowHeaders, rowDimensions));
            for (var column : columns) {
                var cell = cells.get(key(row, column));
                for (var metric : report.metrics()) {
                    line.add(
                            cell == null
                                    ? ""
                                    : Objects.toString(cell.values().get(metric.id()), ""));
                    if (ratios)
                        line.add(
                                cell == null || cell.ratios() == null
                                        ? ""
                                        : Objects.toString(cell.ratios().get(metric.id()), ""));
                }
            }
            output.add(line);
        }
        return new Sheet(heads, output, merges(owners, rowDimensions, columnDimensions));
    }

    /**
     * 一个列组在各列维度层上的表头文本：叶子为各层标签；长度 k 的小计在前 k 层沿用所属组标签，第 k 层起为「小计」 （k = 0 即合计列组，为「合计」），纵向填满到指标层之上。
     */
    private static List<String> columnPath(
            List<String> keys,
            Map<List<String>, ApplicationReports.PivotHeader> headers,
            int dimensions) {
        List<String> path = new ArrayList<>();
        var leaf = headerOf(keys, headers);
        for (int i = 0; i < dimensions; i++) {
            if (i < keys.size()) path.add(leaf == null ? "" : leaf.labels().get(i));
            else path.add(keys.isEmpty() ? TOTAL : SUBTOTAL);
        }
        return path;
    }

    private static List<String> append(List<String> path, String last) {
        List<String> head = new ArrayList<>(path);
        head.add(last);
        return head;
    }

    /**
     * 按层合并：行维度列纵向跨全部表头行；第 i 层上，属于同一上层分组（前 i+1 个键相同）的相邻列横向合并； 长度 k 的小计/合计格在第 k 层横跨本列组全部指标列、纵向跨到第
     * 列维度数-1 层。指标层不合并。
     */
    private static List<ExcelUtils.HeadMerge> merges(
            List<List<String>> owners, int rowDimensions, int columnDimensions) {
        List<ExcelUtils.HeadMerge> merges = new ArrayList<>();
        if (columnDimensions > 0)
            for (int c = 0; c < rowDimensions; c++)
                merges.add(new ExcelUtils.HeadMerge(0, columnDimensions, c, c));
        for (int level = 0; level < columnDimensions; level++) {
            int start = rowDimensions;
            while (start < owners.size()) {
                var node = node(owners.get(start), level);
                int end = start;
                while (end + 1 < owners.size()
                        && node != null
                        && node.equals(node(owners.get(end + 1), level))) end++;
                if (node != null) {
                    int lastRow = owners.get(start).size() == level ? columnDimensions - 1 : level;
                    if (end > start || lastRow > level)
                        merges.add(new ExcelUtils.HeadMerge(level, lastRow, start, end));
                }
                start = end + 1;
            }
        }
        return merges;
    }

    /** 第 level 层上此列所属的表头格：上层分组以键前缀标识，本层小计/合计以整组键标识；已被上方小计格纵向覆盖时为 null。 */
    private static List<Object> node(List<String> keys, int level) {
        if (keys.size() > level)
            return Arrays.asList("group", new ArrayList<>(keys.subList(0, level + 1)));
        if (keys.size() == level) return Arrays.asList("summary", new ArrayList<>(keys));
        return null;
    }

    /** 叶子按服务端顺序；每个小计紧随其组最后一个叶子；合计最后。 */
    private static List<List<String>> ordered(
            Set<List<String>> present, List<List<String>> leaves, int dimensions) {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            var leaf = leaves.get(i);
            if (present.contains(leaf)) result.add(leaf);
            for (int length = dimensions - 1; length >= 1; length--) {
                var prefix = leaf.subList(0, length);
                boolean closes =
                        i + 1 == leaves.size()
                                || !leaves.get(i + 1).subList(0, length).equals(prefix);
                if (closes && present.contains(prefix)) result.add(new ArrayList<>(prefix));
            }
        }
        if (dimensions > 0 && present.contains(List.of())) result.add(List.of());
        return result;
    }

    private static List<String> rowLabels(
            List<String> keys,
            Map<List<String>, ApplicationReports.PivotHeader> headers,
            int dimensions) {
        List<String> labels = new ArrayList<>();
        var leaf = headerOf(keys, headers);
        for (int i = 0; i < dimensions; i++) {
            if (i < keys.size()) labels.add(leaf == null ? "" : leaf.labels().get(i));
            else if (i == keys.size()) labels.add(keys.isEmpty() ? TOTAL : SUBTOTAL);
            else labels.add("");
        }
        return labels;
    }

    private static ApplicationReports.PivotHeader headerOf(
            List<String> prefix, Map<List<String>, ApplicationReports.PivotHeader> headers) {
        return headers.get(prefix);
    }

    /** 每个键前缀（含空前缀与完整键）对应的第一个叶子表头，与原先「按顺序找第一个前缀相同的叶子」同义。 */
    private static Map<List<String>, ApplicationReports.PivotHeader> prefixes(
            List<ApplicationReports.PivotHeader> headers) {
        Map<List<String>, ApplicationReports.PivotHeader> index = new HashMap<>();
        for (ApplicationReports.PivotHeader header : headers)
            for (int length = 0; length <= header.keys().size(); length++)
                index.putIfAbsent(new ArrayList<>(header.keys().subList(0, length)), header);
        return index;
    }

    private static List<List<String>> key(List<String> rows, List<String> columns) {
        return Arrays.asList(new ArrayList<>(rows), new ArrayList<>(columns));
    }
}
