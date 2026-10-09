package com.richuang.os.framework.excel.core.util;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.converters.longconverter.LongStringConverter;
import cn.idev.excel.event.AnalysisEventListener;
import cn.idev.excel.write.merge.OnceAbsoluteMergeStrategy;

import com.richuang.os.framework.common.util.http.HttpUtils;
import com.richuang.os.framework.excel.core.handler.ColumnWidthMatchStyleStrategy;
import com.richuang.os.framework.excel.core.handler.SelectSheetWriteHandler;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Excel 工具类
 *
 * @author os
 */
public class ExcelUtils {

    /**
     * 将列表以 Excel 响应给前端
     *
     * @param response 响应
     * @param filename 文件名
     * @param sheetName Excel sheet 名
     * @param head Excel head 头
     * @param data 数据列表哦
     * @param <T> 泛型，保证 head 和 data 类型的一致性
     * @throws IOException 写入失败的情况
     */
    public static <T> void write(
            HttpServletResponse response,
            String filename,
            String sheetName,
            Class<T> head,
            List<T> data)
            throws IOException {
        // 输出 Excel
        FastExcelFactory.write(response.getOutputStream(), head)
                .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                .registerWriteHandler(
                        new ColumnWidthMatchStyleStrategy()) // 基于 column 长度，自动适配。最大 255 宽度
                .registerWriteHandler(new SelectSheetWriteHandler(head)) // 基于固定 sheet 实现下拉框
                .registerConverter(new LongStringConverter()) // 避免 Long 类型丢失精度
                .sheet(sheetName)
                .doWrite(data);
        // 设置 header 和 contentType。写在最后的原因是，避免报错时，响应 contentType 已经被修改了
        response.addHeader(
                "Content-Disposition", "attachment;filename=" + HttpUtils.encodeUtf8(filename));
        response.setContentType("application/vnd.ms-excel;charset=UTF-8");
    }

    /** 动态业务字段复用同一 Excel 引擎；字符串按文本写入，保留长整型及公式字面量。 */
    public static void write(
            java.io.OutputStream output,
            String sheetName,
            List<String> headers,
            List<? extends List<?>> rows) {
        FastExcelFactory.write(output)
                .autoCloseStream(false)
                .head(headers.stream().map(List::of).toList())
                .registerWriteHandler(new ColumnWidthMatchStyleStrategy())
                .registerConverter(new LongStringConverter())
                .sheet(sheetName)
                .doWrite(rows);
    }

    /**
     * 多层表头写出：每列给出自上而下的表头路径，相邻且上层相同的表头单元格由 Excel 引擎合并（如透视表的「列组 → 指标」两层表头）。 字符串按文本写入，保留金额精度及公式字面量。
     */
    public static void writeHeads(
            java.io.OutputStream output,
            String sheetName,
            List<List<String>> heads,
            List<? extends List<?>> rows) {
        FastExcelFactory.write(output)
                .autoCloseStream(false)
                .head(heads)
                .registerWriteHandler(new ColumnWidthMatchStyleStrategy())
                .registerConverter(new LongStringConverter())
                .sheet(sheetName)
                .doWrite(rows);
    }

    /** 表头合并区域（0 起的行列号，含首尾）。 */
    public record HeadMerge(int firstRow, int lastRow, int firstColumn, int lastColumn) {}

    /**
     * 多层表头写出，合并区域由调用方按层级给出：关闭引擎按「相邻同文本」的自动合并（它会把不同上层下的同名表头、 或不同列组下的同名指标错误地连成一格），只合并 merges
     * 指定的区域。字符串按文本写入，保留金额精度及公式字面量。
     */
    public static void writeHeads(
            java.io.OutputStream output,
            String sheetName,
            List<List<String>> heads,
            List<HeadMerge> merges,
            List<? extends List<?>> rows) {
        var writer =
                FastExcelFactory.write(output)
                        .autoCloseStream(false)
                        .head(heads)
                        .automaticMergeHead(false)
                        .registerWriteHandler(new ColumnWidthMatchStyleStrategy())
                        .registerConverter(new LongStringConverter());
        for (var merge : merges)
            writer.registerWriteHandler(
                    new OnceAbsoluteMergeStrategy(
                            merge.firstRow(),
                            merge.lastRow(),
                            merge.firstColumn(),
                            merge.lastColumn()));
        writer.sheet(sheetName).doWrite(rows);
    }

    /** 动态模板的严格、限量文本读取，只读取首个工作表，不推断字段或执行单元格公式。 */
    public static List<Map<Integer, String>> read(
            MultipartFile file, List<String> headers, int maximumRows) throws IOException {
        if (maximumRows < 1 || maximumRows > 10000) throw new IllegalArgumentException("导入行数上限无效");
        Map<Integer, String> expected = new TreeMap<>();
        for (int i = 0; i < headers.size(); i++) expected.put(i, headers.get(i));
        List<Map<Integer, String>> rows = new java.util.ArrayList<>();
        try (var stream = file.getInputStream()) {
            FastExcelFactory.read(
                            stream,
                            new AnalysisEventListener<Map<Integer, String>>() {
                                @Override
                                public void invokeHeadMap(
                                        Map<Integer, String> actual, AnalysisContext context) {
                                    if (!expected.equals(actual))
                                        throw invalidParamException("导入表头与模板不一致，请使用最新模板");
                                }

                                @Override
                                public void invoke(
                                        Map<Integer, String> row, AnalysisContext context) {
                                    if (rows.size() >= maximumRows)
                                        throw invalidParamException("导入最多支持 {} 行", maximumRows);
                                    if (row.keySet().stream().anyMatch(i -> i >= headers.size()))
                                        throw invalidParamException("模板包含未定义列");
                                    rows.add(new LinkedHashMap<>(row));
                                }

                                @Override
                                public void doAfterAllAnalysed(AnalysisContext context) {}
                            })
                    .autoCloseStream(false)
                    .ignoreEmptyRow(false)
                    .sheet()
                    .doRead();
        }
        return rows;
    }

    public static <T> List<T> read(MultipartFile file, Class<T> head) throws IOException {
        // 参考 https://t.zsxq.com/zM77F 帖子，增加 try 处理，兼容 windows 场景
        try (InputStream inputStream = file.getInputStream()) {
            return FastExcelFactory.read(inputStream, head, null)
                    .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                    .doReadAllSync();
        }
    }

    /** 限量、严格表头的业务导入。边读取边限制行数，避免先把全部工作簿装入内存后再判断。 原有无上限方法保留兼容；小型配置导入使用此重载。 */
    public static <T> List<T> read(MultipartFile file, Class<T> head, int maximumRows)
            throws IOException {
        if (maximumRows < 1 || maximumRows > 10000) throw new IllegalArgumentException("导入行数上限无效");
        Map<Integer, String> expected = getExpectedHeaders(head);
        List<T> result = new java.util.ArrayList<>();
        try (InputStream stream = file.getInputStream()) {
            FastExcelFactory.read(
                            stream,
                            head,
                            new AnalysisEventListener<T>() {
                                @Override
                                public void invokeHeadMap(
                                        Map<Integer, String> actual, AnalysisContext context) {
                                    if (!expected.equals(actual))
                                        throw invalidParamException("导入表头与模板不一致，请使用最新模板");
                                }

                                @Override
                                public void invoke(T value, AnalysisContext context) {
                                    if (result.size() >= maximumRows)
                                        throw invalidParamException("导入最多支持 {} 行", maximumRows);
                                    result.add(value);
                                }

                                @Override
                                public void doAfterAllAnalysed(AnalysisContext context) {}
                            })
                    .autoCloseStream(false)
                    .sheet()
                    .doRead();
        }
        return result;
    }

    /**
     * 校验导入文件的首行表头是否与 VO 定义一致，防止列名错误时发生静默错列导入。
     *
     * @param file 导入文件
     * @param head Excel 表头定义类
     */
    public static <T> void validateHeader(MultipartFile file, Class<T> head) throws IOException {
        Map<Integer, String> expectedHeaders = getExpectedHeaders(head);
        if (expectedHeaders.isEmpty()) {
            return;
        }
        Map<Integer, String> actualHeaders = readHeaders(file);
        expectedHeaders.forEach(
                (index, expectedHeader) -> {
                    String actualHeader = actualHeaders.get(index);
                    if (!expectedHeader.equals(actualHeader)) {
                        throw invalidParamException(
                                "导入模板表头不正确，第 {} 列应为“{}”，当前为“{}”，请重新下载模板",
                                index + 1,
                                expectedHeader,
                                actualHeader == null ? "空" : actualHeader);
                    }
                });
        actualHeaders.forEach(
                (index, actualHeader) -> {
                    if (!expectedHeaders.containsKey(index)) {
                        throw invalidParamException(
                                "导入模板第 {} 列“{}”未定义，请重新下载模板", index + 1, actualHeader);
                    }
                });
    }

    private static <T> Map<Integer, String> getExpectedHeaders(Class<T> head) {
        Map<Integer, String> headers = new TreeMap<>();
        int defaultIndex = 0;
        for (Field field : head.getDeclaredFields()) {
            ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
            if (excelProperty == null || excelProperty.value().length == 0) {
                continue;
            }
            int index = excelProperty.index() >= 0 ? excelProperty.index() : defaultIndex;
            headers.put(index, excelProperty.value()[excelProperty.value().length - 1]);
            defaultIndex++;
        }
        return headers;
    }

    private static Map<Integer, String> readHeaders(MultipartFile file) throws IOException {
        Map<Integer, String> headers = new LinkedHashMap<>();
        try (InputStream inputStream = file.getInputStream()) {
            FastExcelFactory.read(
                            inputStream,
                            new AnalysisEventListener<Map<Integer, String>>() {
                                @Override
                                public void invokeHeadMap(
                                        Map<Integer, String> headMap, AnalysisContext context) {
                                    headers.putAll(headMap);
                                }

                                @Override
                                public void invoke(
                                        Map<Integer, String> data, AnalysisContext context) {
                                    // 仅用于读取表头，数据行无需处理
                                }

                                @Override
                                public void doAfterAllAnalysed(AnalysisContext context) {
                                    // 无后续处理
                                }
                            })
                    .headRowNumber(1)
                    .sheet()
                    .doRead();
        }
        return headers;
    }
}
