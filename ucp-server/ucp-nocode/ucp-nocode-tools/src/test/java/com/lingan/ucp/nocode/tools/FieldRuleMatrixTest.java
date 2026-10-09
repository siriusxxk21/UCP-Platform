package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.FieldRuleMatrix;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

/**
 * 能力矩阵与前端读同一份期望表（contract-baseline/field-rule-matrix.json，归前端抽屉一路维护）；本类不另写期望。 条件算子词表按设计稿 7.2，链接与
 * UUID 以运行端查询能力为准不可作为条件字段。
 */
class FieldRuleMatrixTest {
    private static final String BASELINE = "/contract-baseline/field-rule-matrix.json";

    private static List<JsonNode> cases() throws Exception {
        try (var input = FieldRuleMatrixTest.class.getResourceAsStream(BASELINE)) {
            assertThat(input).as("前后端共用的矩阵期望表必须存在：" + BASELINE).isNotNull();
            var root = new ObjectMapper().readTree(input);
            var cases = new ArrayList<JsonNode>();
            root.path("cases").forEach(cases::add);
            return cases;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @TestFactory
    Stream<DynamicTest> matrixMatchesSharedBaseline() throws Exception {
        var cases = cases();
        // 空表会让逐条断言恒真：先钉住覆盖面，再逐条比较。
        assertThat(cases).hasSizeGreaterThanOrEqualTo(33);
        assertThat(
                        cases.stream()
                                .filter(c -> text(c.get("relation")) == null)
                                .map(c -> c.get("type").asText())
                                .toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(FieldTypeEnum.values()).map(FieldTypeEnum::getCode).toList());
        return cases.stream()
                .map(
                        c -> {
                            String type = c.get("type").asText();
                            String relation = text(c.get("relation"));
                            List<String> modes = new ArrayList<>();
                            c.get("modes").forEach(m -> modes.add(m.asText()));
                            return DynamicTest.dynamicTest(
                                    type + "/" + relation,
                                    () -> {
                                        assertThat(FieldRuleMatrix.block(type, relation))
                                                .isEqualTo(c.get("block").asText());
                                        assertThat(FieldRuleMatrix.modes(type, relation))
                                                .containsExactlyElementsOf(modes);
                                    });
                        });
    }

    /** 2026-10-01：可作条件的字段都有「为空 / 不为空」；选项、关联、目录、布尔与文本另有「不等于」。 */
    @Test
    void conditionOperatorsFollowFieldKind() {
        for (String type : List.of("TEXT", "TEXTAREA", "AUTO_NUMBER"))
            assertThat(FieldRuleMatrix.operators(type, null, false))
                    .containsExactly("eq", "neq", "like", "notLike", "isNull", "notNull");
        for (String type : List.of("DATE", "DATETIME", "TIME"))
            assertThat(FieldRuleMatrix.operators(type, null, false))
                    .containsExactly("lt", "gt", "between", "isNull", "notNull");
        for (String type : List.of("INTEGER", "DECIMAL", "MONEY", "PERCENT"))
            assertThat(FieldRuleMatrix.operators(type, null, false))
                    .containsExactly("eq", "neq", "gt", "gte", "lt", "lte", "isNull", "notNull");
        for (String type :
                List.of(
                        "SELECT",
                        "REFERENCE",
                        "USER",
                        "DEPARTMENT",
                        "ORGANIZATION",
                        "POST",
                        "USER_GROUP",
                        "BOOLEAN"))
            assertThat(FieldRuleMatrix.operators(type, null, false))
                    .as(type)
                    .containsExactly("eq", "neq", "isNull", "notNull");
        assertThat(FieldRuleMatrix.operators("MULTI_SELECT", null, false))
                .containsExactly("containsAny", "isNull", "notNull");
        assertThat(FieldRuleMatrix.operators("INTEGER", null, true))
                .as("单值引用列按关联字段的词表，不按列的数值类型")
                .containsExactly("eq", "neq", "isNull", "notNull");
        assertThat(FieldRuleMatrix.operators("FORMULA", "DECIMAL", false))
                .containsExactly("eq", "neq", "gt", "gte", "lt", "lte", "isNull", "notNull");
        assertThat(FieldRuleMatrix.recordKeyOperators()).containsExactly("eq");
        assertThat(FieldRuleMatrix.OPERATORS).contains("isNull", "notNull");
        assertThat(FieldRuleMatrix.valueless("isNull")).isTrue();
        assertThat(FieldRuleMatrix.valueless("notNull")).isTrue();
        assertThat(FieldRuleMatrix.valueless("neq")).isFalse();
        assertThat(FieldRuleMatrix.valueless(null)).isFalse();
    }

    @Test
    void unfilterableFieldsHaveNoOperators() {
        for (String type :
                List.of(
                        "URL",
                        "UUID",
                        "IMAGE",
                        "ATTACHMENT",
                        "RICH_TEXT",
                        "REGION",
                        "CASCADE",
                        "SUMMARY"))
            assertThat(FieldRuleMatrix.operators(type, null, false)).as(type).isEmpty();
        assertThat(FieldRuleMatrix.operators("FORMULA", null, false)).isEmpty();
    }
}
