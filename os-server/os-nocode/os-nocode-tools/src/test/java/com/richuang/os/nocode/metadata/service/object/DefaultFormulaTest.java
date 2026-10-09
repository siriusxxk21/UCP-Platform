package com.richuang.os.nocode.metadata.service.object;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;
import com.richuang.os.nocode.metadata.service.formula.MoneyRounding;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 公式默认值的元数据层约束（设计稿 15.3.3、B32′）：金额目标不能写 round()，取整统一由取整方式决定；floor 等函数本来就不受支持； 非金额目标照常可用
 * round。运行时求值与取整由保存侧另测。
 */
class DefaultFormulaTest {
    private static FieldDefinition field(String id, String code, String name, String type) {
        return new FieldDefinition(id, id, code, name, type, null, null, null, false, false, 0);
    }

    private static DataCenter.Definition object(
            String targetType, String formula, String rounding) {
        var options = new HashMap<String, FieldOptions>();
        options.put(
                "3",
                FieldOptions.defaults()
                        .withRules(new FieldRules(null, null, formula, rounding, null, null)));
        return new DataCenter.Definition(
                "1",
                "o1",
                "订单",
                null,
                "public",
                "biz_o1",
                "GENERATED",
                false,
                "1",
                DataCenter.Settings.defaults(),
                List.of(
                        field("1", "c_dj", "单价", "DECIMAL"),
                        field("2", "c_sl", "数量", "INTEGER"),
                        field("3", "c_hj", "合计", targetType),
                        field("4", "c_round", "舍入备注", "TEXT")),
                options,
                List.of(),
                List.of(),
                List.of());
    }

    private static void publish(DataCenter.Definition d) {
        FieldRuleValidator.validate(d, id -> null, id -> "「?」未发布或已停用");
    }

    @Test
    void moneyTargetRoundRejected() {
        assertThatThrownBy(() -> publish(object("MONEY", "round(c_dj * c_sl)", null)))
                .hasMessage("字段「合计」是金额字段，公式里不能写 round()：取整统一由「取整方式」决定（当前：向下取整）");
        assertThatThrownBy(() -> publish(object("MONEY", "abs(round(c_dj, 1) * c_sl)", "HALF_UP")))
                .hasMessage("字段「合计」是金额字段，公式里不能写 round()：取整统一由「取整方式」决定（当前：四舍五入）");
        assertThatCode(() -> publish(object("MONEY", "c_dj * c_sl", "HALF_UP")))
                .doesNotThrowAnyException();
    }

    @Test
    void floorUnsupported() {
        for (String function : List.of("floor", "ceil", "trunc"))
            assertThatThrownBy(() -> publish(object("MONEY", function + "(c_dj * c_sl)", null)))
                    .hasMessage("字段「合计」的公式默认值：不支持公式函数：" + function);
    }

    @Test
    void numberRoundAllowed() {
        assertThatCode(() -> publish(object("DECIMAL", "round(c_dj * c_sl, 2)", null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> publish(object("INTEGER", "round(c_dj * c_sl)", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void roundDetectionUsesSyntaxTreeNotText() {
        var columns = Map.of("c_round", "c_round", "c_dj", "c_dj");
        assertThat(
                        MoneyRounding.usesRound(
                                FieldExpressions.parse("c_round || 'round()'", columns)
                                        .expression()))
                .isFalse();
        assertThat(
                        MoneyRounding.usesRound(
                                FieldExpressions.parse("coalesce(c_dj, round(c_dj))", columns)
                                        .expression()))
                .isTrue();
        // 字段编码里含 round、文本里写 round() 都不是取整函数；其它受支持函数在金额目标上照常放行。
        assertThatCode(() -> publish(object("MONEY", "abs(c_dj) * coalesce(c_sl, 0)", null)))
                .doesNotThrowAnyException();
    }
}
