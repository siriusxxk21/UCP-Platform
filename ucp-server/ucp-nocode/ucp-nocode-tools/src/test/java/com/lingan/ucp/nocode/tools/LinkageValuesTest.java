package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.runtime.service.rules.LinkageValues;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.*;

/** 数据联动纯值层；用例改写自老系统 linkage-value.spec.ts 与 data-linkage.service.spec.ts 的求和段，取整按设计稿 15.3。 */
class LinkageValuesTest {
    private static final FieldDefinition TEXT = target("TEXT");
    private static final FieldDefinition MONEY = target("MONEY");
    private static final FieldDefinition DECIMAL = target("DECIMAL");
    private static final FieldDefinition INTEGER = target("INTEGER");

    private static FieldDefinition target(String type) {
        return new FieldDefinition("t", "t", "t", "目标", type, null, null, null, false, false, 0);
    }

    private static LinkageValues.Outcome reduce(
            String mode, List<?> cells, boolean numeric, FieldDefinition target, String rounding) {
        return LinkageValues.reduce(mode, cells, numeric, target, rounding);
    }

    /** B18：拼接用 ASCII 逗号，跳过空值，不产出 "A,,B"；数组格按老系统 String() 展开。 */
    @Test
    void concatSkipsEmpty() {
        var out = reduce("CONCAT", Arrays.asList("A", "", null, "B"), false, TEXT, null);
        assertThat(out.applied()).isTrue();
        assertThat(out.value()).isEqualTo("A,B");
        assertThat(reduce(null, Arrays.asList("A", List.of("x", "y")), false, TEXT, null).value())
                .isEqualTo("A,x,y");
        assertThat(reduce("CONCAT", Arrays.asList(null, ""), false, TEXT, null).value()).isNull();
        assertThat(reduce("CONCAT", List.of("三菱", "みずほ", "三井住友"), false, TEXT, null).value())
                .isEqualTo("三菱,みずほ,三井住友");
    }

    /** B19：四档两两不同；报错档多于 1 行失败且不给值。 */
    @Test
    void fourModesDiffer() {
        var cells = List.of("1", "2", "3");
        var concat = reduce("CONCAT", cells, true, TEXT, null);
        var first = reduce("FIRST", cells, true, TEXT, null);
        var sum = reduce("SUM", cells, true, TEXT, null);
        var error = reduce("ERROR", cells, true, TEXT, null);
        assertThat(concat.value()).isEqualTo("1,2,3");
        assertThat(first.value()).isEqualTo("1");
        assertThat((BigDecimal) sum.value()).isEqualByComparingTo("6");
        assertThat(error.state()).isEqualTo("MULTI_ROW_ERROR");
        assertThat(error.value()).isNull();
        assertThat(Set.of(concat.value(), first.value(), sum.value().toString())).hasSize(3);
        assertThat(reduce("ERROR", List.of("唯一"), false, TEXT, null).value()).isEqualTo("唯一");
    }

    /** B19：恰好 1 行时四档结果相同，但求和到金额目标仍取整。 */
    @Test
    void singleRowSumStillFloors() {
        for (String mode : List.of("CONCAT", "FIRST", "ERROR"))
            assertThat(reduce(mode, List.of("7"), true, TEXT, null).value()).isEqualTo("7");
        assertThat((BigDecimal) reduce("SUM", List.of("7"), true, TEXT, null).value())
                .isEqualByComparingTo("7");
        assertThat(reduce("SUM", List.of("10.9"), true, MONEY, null).value()).isEqualTo("10");
        assertThat(reduce("FIRST", List.of("10.9"), true, MONEY, null).value()).isEqualTo("10");
    }

    /** B20′：空格不当 0；全空得 null。 */
    @Test
    void sumSkipsEmpty() {
        assertThat(
                        (BigDecimal)
                                reduce("SUM", Arrays.asList("1", null, "", 2), true, TEXT, null)
                                        .value())
                .isEqualByComparingTo("3");
        var none = reduce("SUM", Arrays.asList(null, ""), true, MONEY, null);
        assertThat(none.applied()).isTrue();
        assertThat(none.value()).isNull();
    }

    /** B20′：定点相加，0.1 + 0.2 = 0.3，小数位不齐也对得上。 */
    @Test
    void decimalExact() {
        var sum = (BigDecimal) reduce("SUM", List.of("0.1", "0.2"), true, DECIMAL, null).value();
        assertThat(sum.toPlainString()).isEqualTo("0.3");
        var doubles = (BigDecimal) reduce("SUM", List.of(0.1d, 0.2d), true, DECIMAL, null).value();
        assertThat(doubles).isEqualByComparingTo("0.3");
        assertThat(
                        ((BigDecimal)
                                        reduce(
                                                        "SUM",
                                                        List.of("1.5", "2.25", "3"),
                                                        true,
                                                        DECIMAL,
                                                        null)
                                                .value())
                                .toPlainString())
                .isEqualTo("6.75");
    }

    /** B20′：无法解析的格子不当 0，明确失败。 */
    @Test
    void sumNotNumeric() {
        var out = reduce("SUM", List.of("1", "abc"), true, DECIMAL, null);
        assertThat(out.state()).isEqualTo("SUM_NOT_NUMERIC");
        assertThat(out.value()).isNull();
        assertThat(reduce("SUM", List.of("1", true), true, DECIMAL, null).state())
                .isEqualTo("SUM_NOT_NUMERIC");
    }

    /** B20′：金额目标缺省向下取整成整数串。100.5 + 200.4 + 300 = 600.9 → 600。 */
    @Test
    void sumMoneyDefaultFloor() {
        var out = reduce("SUM", List.of("100.5", "200.4", 300), true, MONEY, null);
        assertThat(out.value()).isEqualTo("600");
        assertThat(reduce("SUM", List.of("100.5", "200.4", 300, "-1.5"), true, MONEY, null).value())
                .isEqualTo("599");
    }

    /** B20′：非金额目标保持小数，不取整。 */
    @Test
    void nonMoneyKeepsDecimals() {
        var out = reduce("SUM", List.of("100.5", "200.4", 300), true, DECIMAL, null);
        assertThat(((BigDecimal) out.value()).toPlainString()).isEqualTo("600.9");
        assertThat(reduce("FIRST", List.of("10.75"), true, DECIMAL, "HALF_UP").value())
                .isEqualTo("10.75");
    }

    /** B43：三档取整，1.5 / -1.5 / 2.5 / 整数。 */
    @ParameterizedTest
    @CsvSource({
        "HALF_UP,1.5,2",
        "FLOOR,1.5,1",
        "DOWN,1.5,1",
        "HALF_UP,-1.5,-2",
        "FLOOR,-1.5,-2",
        "DOWN,-1.5,-1",
        "HALF_UP,2.5,3",
        "FLOOR,2.5,2",
        "DOWN,2.5,2",
        "HALF_UP,7,7",
        "FLOOR,7,7",
        "DOWN,-7,-7"
    })
    void roundingModes(String rounding, String input, String expected) {
        assertThat(reduce("SUM", List.of(input), true, MONEY, rounding).value())
                .isEqualTo(expected);
        assertThat(LinkageValues.round(new BigDecimal(input), MONEY, rounding)).isEqualTo(expected);
    }

    /** 除法尾差（1000/3*3 = 999.9999999999999999）在取整前先归到 10 位小数，三档都得 1000；真小数不受影响。 */
    @Test
    void divisionTailSettledBeforeRounding() {
        var tail = new BigDecimal("999.9999999999999999");
        for (String rounding : Arrays.asList("FLOOR", "DOWN", "HALF_UP", null))
            assertThat(LinkageValues.round(tail, MONEY, rounding)).isEqualTo("1000");
        assertThat(LinkageValues.round(new BigDecimal("-999.9999999999999999"), MONEY, "DOWN"))
                .isEqualTo("-1000");
        assertThat(LinkageValues.round(new BigDecimal("999.9"), MONEY, "FLOOR")).isEqualTo("999");
        assertThat(LinkageValues.round(new BigDecimal("999.999999999"), MONEY, "DOWN"))
                .isEqualTo("999");
        assertThat(LinkageValues.round(new BigDecimal("0.4999999999999999"), MONEY, "HALF_UP"))
                .isEqualTo("1");
    }

    /** B20′/B43：缺省（null）按 FLOOR，负数向负无穷。 */
    @Test
    void floorNegative() {
        assertThat(reduce("SUM", List.of("-1.5"), true, MONEY, null).value()).isEqualTo("-2");
        assertThat(reduce("SUM", List.of("-1.5"), true, MONEY, "DOWN").value()).isEqualTo("-1");
    }

    /** B44：取第一行、报错档把带小数的值带到金额目标时同样按取整方式取整。 */
    @Test
    void firstIntoMoneyRounded() {
        assertThat(reduce("FIRST", List.of("10.5", "99"), true, MONEY, "HALF_UP").value())
                .isEqualTo("11");
        assertThat(reduce("FIRST", List.of(new BigDecimal("10.5")), true, MONEY, null).value())
                .isEqualTo("10");
        assertThat(reduce("ERROR", List.of("-10.5"), true, MONEY, "DOWN").value()).isEqualTo("-10");
        assertThat(reduce("CONCAT", List.of("10.5"), true, MONEY, "HALF_UP").value())
                .isEqualTo("11");
    }

    /** B45：取整编码不认识时 fail-closed，不猜。 */
    @Test
    void unknownRounding() {
        var out = reduce("SUM", List.of("1.5"), true, MONEY, "CEILING");
        assertThat(out.state()).isEqualTo("ROUNDING_MODE_UNKNOWN");
        assertThat(out.value()).isNull();
        assertThat(out.message()).contains("CEILING");
        assertThat(LinkageValues.checkRounding("HALF_UP")).isNull();
        assertThat(LinkageValues.checkRounding(null)).isNull();
    }

    /** B21：非数值来源配求和在运行时兜底拒绝。 */
    @Test
    void sumGuard() {
        var out = reduce("SUM", List.of("1"), false, TEXT, null);
        assertThat(out.state()).isEqualTo("MULTI_ROW_MODE_REJECTED");
        assertThat(out.value()).isNull();
        assertThat(LinkageValues.checkMode("SUM", false, null).state())
                .isEqualTo("MULTI_ROW_MODE_REJECTED");
        assertThat(LinkageValues.checkMode("SUM", true, null)).isNull();
    }

    /** B22：不认识的档位报 UNKNOWN（含已裁掉的「去重取值」与旧词表）；空值按缺省档 CONCAT。 */
    @Test
    void unknownModeNotGuessed() {
        for (String mode : List.of("拼字符串", "DISTINCT", "去重取值", "concat")) {
            var out = reduce(mode, List.of("A", "B"), false, TEXT, null);
            assertThat(out.state()).isEqualTo("MULTI_ROW_MODE_UNKNOWN");
            assertThat(out.value()).isNull();
        }
        assertThat(reduce("", List.of("A", "B"), false, TEXT, null).value()).isEqualTo("A,B");
        assertThat(LinkageValues.mode(null)).isEqualTo("CONCAT");
    }

    /** 5.3：来源格是数组、目标是标量且档位是取第一行或报错 → UNSUPPORTED_VALUE_KIND。 */
    @Test
    void arrayIntoScalarRejected() {
        var out = reduce("FIRST", List.of(List.of("A", "B")), false, TEXT, null);
        assertThat(out.state()).isEqualTo("UNSUPPORTED_VALUE_KIND");
        var multi =
                new FieldDefinition(
                        "m", "m", "m", "多选", "MULTI_SELECT", null, null, null, false, false, 0);
        assertThat(reduce("FIRST", List.of(List.of("A", "B")), false, multi, null).value())
                .isEqualTo(List.of("A", "B"));
        assertThat(INTEGER.type()).isEqualTo("INTEGER");
    }

    private static FieldDefinition text(String type, Integer length) {
        return new FieldDefinition(
                "t", "t", "t", "流水编号", type, length, null, null, false, false, 0);
    }

    /** 2026-10-01：文本目标收自动编号（数据库自增为数字）与链接（地址加显示文字）时，格子先统一成文本；多行拼接同样可用。 */
    @Test
    void textTargetNormalisesAutoNumberAndLinkCells() {
        var link = Map.of("link", "https://example.com/a", "text", "官网");
        for (var field : List.of(TEXT, target("TEXTAREA"))) {
            assertThat(reduce("FIRST", List.of(7L, 8L), false, field, null).value()).isEqualTo("7");
            assertThat(reduce("CONCAT", List.of(7L, 8L, 9), false, field, null).value())
                    .isEqualTo("7,8,9");
            assertThat(reduce(null, List.of("LS-0001", "LS-0002"), false, field, null).value())
                    .isEqualTo("LS-0001,LS-0002");
            assertThat(reduce("ERROR", List.of(link), false, field, null).value())
                    .isEqualTo("https://example.com/a");
            assertThat(
                            reduce(
                                            "CONCAT",
                                            Arrays.asList(link, null, "", link),
                                            false,
                                            field,
                                            null)
                                    .value())
                    .isEqualTo("https://example.com/a,https://example.com/a");
        }
        // 非文本目标不改格子：链接目标仍拿到地址加文字，数值目标仍拿到数字。
        assertThat(reduce("FIRST", List.of(link), false, target("URL"), null).value())
                .isEqualTo(link);
        assertThat(reduce("FIRST", List.of(7L), true, INTEGER, null).value()).isEqualTo(7L);
    }

    /** 2026-10-01：文本超过目标长度上限时不截断，给出点名字段、实际长度与上限的原因；不超时不拦。 */
    @Test
    void overLongTextIsReportedNotTruncated() {
        var six = text("TEXT", 6);
        assertThat(LinkageValues.tooLong(six, "LS-001")).isNull();
        assertThat(LinkageValues.tooLong(six, "LS-0001"))
                .isEqualTo("结果「LS-0001」共 7 个字符，超过字段「流水编号」的长度上限 6，不填值（不截断）");
        String joined = "LS-0001,LS-0002,LS-0003,LS-0004";
        assertThat(LinkageValues.tooLong(six, joined))
                .isEqualTo("结果「LS-0001,LS-0002,LS-0…」共 31 个字符，超过字段「流水编号」的长度上限 6，不填值（不截断）");
        // 多行文本没有字段长度，只受 10 万字符硬上限约束；非文本目标与非文本值不归这里管。
        var area = text("TEXTAREA", null);
        assertThat(LinkageValues.tooLong(area, "x".repeat(100000))).isNull();
        assertThat(LinkageValues.tooLong(area, "x".repeat(100001))).contains("长度上限 100000");
        assertThat(LinkageValues.tooLong(MONEY, "1234567890")).isNull();
        assertThat(LinkageValues.tooLong(six, 1234567890L)).isNull();
    }
}
