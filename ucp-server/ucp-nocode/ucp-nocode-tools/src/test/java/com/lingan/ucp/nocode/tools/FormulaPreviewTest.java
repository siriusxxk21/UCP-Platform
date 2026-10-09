package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.DataCenter.FormulaPreview;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaPreviewService;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 受控样例试算不装配数据库，核对与正式单据共用的十进制与空值语义。 */
class FormulaPreviewTest {
    private final FormulaPreviewService service = new FormulaPreviewService();

    @Test
    void decimalRoundingPreservesLargeNumber() {
        var result =
                service.preview(
                        new FormulaPreview(
                                "round(qty * price, 2)",
                                List.of("qty", "price"),
                                Map.of("qty", "3", "price", "12.345")));
        assertThat(result.value()).isEqualTo("37.04");
        assertThat(result.referencedFields()).containsExactly("price", "qty");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "amount + 1",
                                                List.of("amount"),
                                                Map.of("amount", "9007199254740993")))
                                .value())
                .isEqualTo("9007199254740994");
    }

    @Test
    void nullFallbackAndTextAreExplicit() {
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "amount * 2", List.of("amount"), Map.of()))
                                .value())
                .isNull();
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "coalesce(amount, 0)", List.of("amount"), Map.of()))
                                .value())
                .isEqualTo("0");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "upper(name) || '备选'",
                                                List.of("name"),
                                                Map.of("name", "abc")))
                                .value())
                .isEqualTo("ABC备选");
    }

    @Test
    void invalidExpressionsAndUnknownInputsAreRejected() {
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview(
                                                "1 / divisor",
                                                List.of("divisor"),
                                                Map.of("divisor", "0"))))
                .hasMessageContaining("除数不能为零");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview(
                                                "round(1.23, 1.5)", List.of(), Map.of())))
                .hasMessageContaining("整数");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview("system('x')", List.of(), Map.of())))
                .hasMessageContaining("函数");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview(
                                                "1", List.of("a"), Map.of("hidden", "secret"))))
                .hasMessageContaining("未知字段");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview(
                                                "amount * 2",
                                                List.of("amount"),
                                                Map.of("amount", "1e999999999"))))
                .hasMessageContaining("38");
    }

    @Test
    void conditionalsShortCircuitAndCompareTypedValues() {
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "IF(1=1, 12.30, 1/0)", List.of(), Map.of()))
                                .value())
                .isEqualTo("12.30");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "IF(AND(qty > 0, NOT(ISBLANK(price))), price / qty,"
                                                        + " 0)",
                                                List.of("qty", "price"),
                                                Map.of("qty", "0")))
                                .value())
                .isEqualTo("0");
        assertThat(
                        service.preview(new FormulaPreview("OR(TRUE, 1/0=0)", List.of(), Map.of()))
                                .value())
                .isEqualTo("true");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "AND(FALSE, 1/0=0)", List.of(), Map.of()))
                                .value())
                .isEqualTo("false");
        assertThat(
                        service.preview(new FormulaPreview("IF(NULL, 1, 2)", List.of(), Map.of()))
                                .value())
                .isEqualTo("2");
        assertThat(service.preview(new FormulaPreview("NULL = 1", List.of(), Map.of())).value())
                .isNull();
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview("IF(2, 1, 0)", List.of(), Map.of())))
                .hasMessageContaining("布尔");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "code = '01'",
                                                List.of("code"),
                                                Map.of("code", "001"),
                                                null,
                                                null,
                                                null,
                                                Map.of("code", "TEXT")))
                                .value())
                .isEqualTo("false");
        assertThat(
                        service.preview(
                                        new FormulaPreview(
                                                "amount > 2",
                                                List.of("amount"),
                                                Map.of("amount", "10")))
                                .value())
                .isEqualTo("true");
    }

    @Test
    void sequencePreviewSortsPartitionsAndPreservesInputIndices() {
        List<Map<String, String>> rows =
                List.of(
                        Map.of("account", "A", "order", "10", "kind", "OUT", "amount", "80"),
                        Map.of("account", "A", "order", "2", "kind", "IN", "amount", "200"),
                        Map.of("account", "B", "order", "1", "kind", "IN", "amount", "5"));
        List<String> codes = List.of("account", "order", "kind", "amount");
        com.lingan.ucp.nocode.api.CalculationOptions.Sequence cumulative =
                new com.lingan.ucp.nocode.api.CalculationOptions.Sequence(
                        "order", null, "PREVIOUS", "CUMULATIVE", "1000");
        com.lingan.ucp.nocode.api.DataCenter.FormulaPreviewResult result =
                service.preview(
                        new FormulaPreview(
                                "if(kind = 'IN', amount, -amount)",
                                codes,
                                Map.of(),
                                rows,
                                cumulative,
                                List.of("account"),
                                Map.of("order", "INTEGER", "amount", "DECIMAL")));
        assertThat(result.rows())
                .extracting(com.lingan.ucp.nocode.api.DataCenter.FormulaPreviewRow::value)
                .containsExactly("1120", "1200", "1005");
        assertThat(result.rows())
                .extracting(com.lingan.ucp.nocode.api.DataCenter.FormulaPreviewRow::contribution)
                .containsExactly("-80", "200", "5");
        com.lingan.ucp.nocode.api.CalculationOptions.Sequence adjacent =
                new com.lingan.ucp.nocode.api.CalculationOptions.Sequence("order", null, "NEXT");
        result =
                service.preview(
                        new FormulaPreview(
                                "coalesce(__previous_amount, 0) - amount",
                                codes,
                                Map.of(),
                                rows,
                                adjacent,
                                List.of("account"),
                                Map.of("order", "INTEGER", "amount", "DECIMAL")));
        assertThat(result.rows())
                .extracting(com.lingan.ucp.nocode.api.DataCenter.FormulaPreviewRow::value)
                .containsExactly("-80", "-120", "-5");
        assertThat(result.rows().get(1).adjacentIndex()).isEqualTo(0);
        assertThat(result.rows().get(0).adjacentIndex()).isNull();
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new FormulaPreview(
                                                "__previous_amount + amount",
                                                codes,
                                                Map.of(),
                                                rows,
                                                cumulative,
                                                List.of("account"),
                                                Map.of())))
                .hasMessageContaining("字段不存在");
    }
}
