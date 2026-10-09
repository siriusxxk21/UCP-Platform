package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.web.RecordExcelService;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

/** 独立图表共用格式器必须保留超大十进制文本，并且绝不执行维度内容中的公式。 */
class ReportDashboardExcelTest {
    private static final String AMOUNT = "9007199254740993.0101";
    private static final String LABEL = "=HYPERLINK(\"https://example.invalid\",\"literal\")";

    @Test
    void aggregateWorkbookKeepsExactTextAndLiteralLabels() throws Exception {
        verify(
                new ApplicationReports.Result(
                        List.of("公司"),
                        metrics(),
                        List.of(
                                new ApplicationReports.Group(
                                        List.of("a"), List.of(LABEL), Map.of("sum", AMOUNT))),
                        Map.of("sum", AMOUNT),
                        1,
                        1,
                        true,
                        "Asia/Shanghai"));
    }

    @Test
    void pivotWorkbookKeepsMergedHeadersTotalsAndExactText() throws Exception {
        ApplicationReports.PivotResult pivot =
                new ApplicationReports.PivotResult(
                        List.of("公司"),
                        List.of("月份"),
                        List.of(new ApplicationReports.PivotHeader(List.of("a"), List.of(LABEL))),
                        List.of(
                                new ApplicationReports.PivotHeader(
                                        List.of("2026-01"), List.of("2026-01"))),
                        List.of(
                                cell(List.of("a"), List.of("2026-01")),
                                cell(List.of("a"), List.of()),
                                cell(List.of(), List.of("2026-01")),
                                cell(List.of(), List.of())),
                        false,
                        false,
                        1,
                        1);
        verify(
                new ApplicationReports.Result(
                        List.of("公司"),
                        metrics(),
                        List.of(),
                        Map.of("sum", AMOUNT),
                        1,
                        1,
                        true,
                        "Asia/Shanghai",
                        pivot));
    }

    private List<ApplicationReports.Metric> metrics() {
        return List.of(new ApplicationReports.Metric("sum", "金额", "SUM", "amount"));
    }

    private ApplicationReports.PivotCell cell(List<String> row, List<String> column) {
        return new ApplicationReports.PivotCell(row, column, Map.of("sum", AMOUNT), null);
    }

    private void verify(ApplicationReports.Result report) throws Exception {
        byte[] bytes = new RecordExcelService().reportResult(report);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> texts = new ArrayList<>();
            for (Sheet sheet : workbook)
                for (Row row : sheet)
                    for (Cell cell : row) {
                        assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                        if (cell.getCellType() == CellType.STRING)
                            texts.add(cell.getStringCellValue());
                    }
            assertThat(texts).contains(AMOUNT, LABEL);
        }
    }
}
