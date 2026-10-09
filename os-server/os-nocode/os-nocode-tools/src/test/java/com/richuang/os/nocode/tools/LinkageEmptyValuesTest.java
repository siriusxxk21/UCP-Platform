package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.metadata.service.object.LinkageEmptyValues;
import com.richuang.os.nocode.metadata.service.object.LinkageEmptyValues.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 「没有匹配记录时填入」字面量按目标字段类型的解析口径（契约 2.3）；发布校验与运行期求值共用这一份。 */
class LinkageEmptyValuesTest {
    private static FieldDefinition target(
            String type, Integer length, Integer precision, Integer scale) {
        return new FieldDefinition(
                "t", "t", "t", "目标", type, length, precision, scale, false, false, 0);
    }

    @ParameterizedTest
    @CsvSource({
        "TEXT,true",
        "TEXTAREA,true",
        "SELECT,true",
        "INTEGER,true",
        "DECIMAL,true",
        "PERCENT,true",
        "MONEY,true",
        "BOOLEAN,true",
        "MULTI_SELECT,false",
        "DATE,false",
        "DATETIME,false",
        "TIME,false",
        "USER,false",
        "DEPARTMENT,false",
        "RICH_TEXT,false",
        "REGION,false",
        "CASCADE,false",
        "URL,false",
        "ATTACHMENT,false",
        "IMAGE,false",
        "REFERENCE,false"
    })
    void supportedTypes(String type, boolean supported) {
        assertThat(LinkageEmptyValues.supported(target(type, null, null, null)))
                .isEqualTo(supported);
        if (!supported)
            assertThat(LinkageEmptyValues.check(target(type, null, null, null), "x"))
                    .isEqualTo(Problem.UNSUPPORTED_TYPE);
    }

    @Test
    void textKeepsLiteralAndRespectsLength() {
        assertThat(LinkageEmptyValues.parse(target("TEXT", 5, null, null), "未登记")).isEqualTo("未登记");
        assertThat(LinkageEmptyValues.check(target("TEXT", 3, null, null), "未登记")).isNull();
        assertThat(LinkageEmptyValues.check(target("TEXT", 2, null, null), "未登记"))
                .isEqualTo(Problem.TEXT_TOO_LONG);
        // 没设长度按 4000。
        assertThat(LinkageEmptyValues.check(target("TEXTAREA", null, null, null), "长".repeat(4000)))
                .isNull();
        assertThat(LinkageEmptyValues.check(target("TEXTAREA", null, null, null), "长".repeat(4001)))
                .isEqualTo(Problem.TEXT_TOO_LONG);
    }

    @Test
    void integerMustBeExactLong() {
        assertThat(LinkageEmptyValues.parse(target("INTEGER", null, null, null), "-7"))
                .isEqualTo("-7");
        for (String bad : new String[] {"1.0", "abc", "", "9223372036854775808"})
            assertThat(LinkageEmptyValues.check(target("INTEGER", null, null, null), bad))
                    .as(bad)
                    .isEqualTo(Problem.MALFORMED);
    }

    @Test
    void decimalFollowsFieldPrecisionAndScale() {
        var field = target("DECIMAL", null, 6, 2);
        assertThat(LinkageEmptyValues.parse(field, "1234.56")).isEqualTo("1234.56");
        assertThat(LinkageEmptyValues.check(field, "1.234")).isEqualTo(Problem.MALFORMED);
        assertThat(LinkageEmptyValues.check(field, "12345.6")).isEqualTo(Problem.MALFORMED);
        assertThat(LinkageEmptyValues.check(target("PERCENT", null, 5, 2), "12.5")).isNull();
    }

    /** 金额按日元整数保存：带小数直接拒绝，不取整、不四舍五入。 */
    @Test
    void moneyOnlyTakesIntegers() {
        var field = target("MONEY", null, 18, 0);
        assertThat(LinkageEmptyValues.parse(field, "1200")).isEqualTo("1200");
        assertThat(LinkageEmptyValues.parse(field, "-1200")).isEqualTo("-1200");
        for (String fraction : new String[] {"12.5", "12.0", "0.4"})
            assertThat(LinkageEmptyValues.check(field, fraction))
                    .as(fraction)
                    .isEqualTo(Problem.MONEY_NOT_INTEGER);
        for (String bad : new String[] {"abc", "1e2", "１２"})
            assertThat(LinkageEmptyValues.check(field, bad)).as(bad).isEqualTo(Problem.MALFORMED);
        assertThatThrownBy(() -> LinkageEmptyValues.parse(field, "12.5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void booleanParsesToBoolean() {
        var field = target("BOOLEAN", null, null, null);
        assertThat(LinkageEmptyValues.parse(field, "true")).isEqualTo(Boolean.TRUE);
        assertThat(LinkageEmptyValues.parse(field, "false")).isEqualTo(Boolean.FALSE);
        assertThat(LinkageEmptyValues.check(field, "TRUE")).isEqualTo(Problem.MALFORMED);
    }

    /** 单选存选项编码原文；是不是有效选项由发布校验按生效选项集核对。 */
    @Test
    void selectKeepsOptionCode() {
        assertThat(LinkageEmptyValues.parse(target("SELECT", null, null, null), "wdj"))
                .isEqualTo("wdj");
    }
}
