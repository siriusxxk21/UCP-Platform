package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

/** 不依赖数据库的解析与边界契约回归。 */
class FormEnhancementTest {
    private DataCenter.Definition definition() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(
                        """
{"objectId":"1","fields":[{"id":"n","code":"n","name":"数值","type":"DECIMAL"},{"id":"m","code":"m","name":"多选","type":"MULTI_SELECT"},{"id":"user","code":"user","name":"人员","type":"INTEGER"}],"fieldOptions":{},"relations":[]}
""",
                        DataCenter.Definition.class);
    }

    @Test
    void decimalAndLazyCoalesceNeverUseFloatingPoint() {
        var parsed = FieldExpressions.parse("coalesce(n, 1 / 0) + 0.2", Map.of("n", "n"));
        assertThat(FormulaEvaluator.evaluate(parsed.expression(), id -> "0.1"))
                .isEqualTo(new BigDecimal("0.3"));
        assertThatThrownBy(() -> FormulaEvaluator.evaluate(parsed.expression(), id -> null))
                .hasMessageContaining("除数");
        assertThatThrownBy(() -> FieldExpressions.parse("pg_sleep(1)", Map.of()))
                .hasMessageContaining("不支持");
        assertThatThrownBy(() -> FieldExpressions.parse("n; DROP TABLE x", Map.of("n", "n")))
                .hasMessageContaining("不支持");
    }

    @Test
    void scopeAnyAllNullAndIdentityHaveExplicitSemantics() throws Exception {
        var d = definition();
        var any =
                new DataScope(
                        "OR",
                        List.of(
                                new DataScope.Condition("n", "gt", "10"),
                                new DataScope.Condition("m", "containsAny", List.of("B"))),
                        List.of());
        any.validate(d, false);
        assertThat(any.matches(d, Map.of("n", "0", "m", List.of("A", "B")), Map.of())).isTrue();
        assertThat(any.matches(d, Map.of("n", "0", "m", List.of("C")), Map.of())).isFalse();
        var all =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition("m", "containsAll", List.of("A", "B"))),
                        List.of());
        assertThat(all.matches(d, Map.of("m", List.of("A")), Map.of())).isFalse();
        assertThat(all.matches(d, Map.of(), Map.of())).isFalse();
        var identity =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition("user", "eq", null, "CURRENT_USER")),
                        List.of());
        identity.validate(d, true);
        assertThat(identity.matches(d, Map.of("user", "23"), Map.of("CURRENT_USER", "23")))
                .isTrue();
        assertThat(identity.matches(d, Map.of("user", "23"), Map.of())).isFalse();
        assertThatThrownBy(() -> identity.validate(d, false)).hasMessageContaining("动态");
    }

    @Test
    void emptyCandidatesAndFixedScopeCannotExpandOnClear() throws Exception {
        var d = definition();
        var q =
                new ViewQueryOptions(
                        List.of(new DataScope.Condition("m", "containsAny", List.of("A", "B"))),
                        Map.of("m", List.of("A")),
                        Map.of("m", List.of("B", "C")));
        q.validate(d);
        assertThat(q.visible(Set.of("m"), Map.of()).candidates()).containsEntry("m", List.of("B"));
        assertThat(q.visible(Set.of(), Map.of()).candidates()).isEmpty();
        var empty =
                new ViewQueryOptions(
                        List.of(new DataScope.Condition("m", "containsAny", List.of())),
                        Map.of(),
                        Map.of());
        empty.validate(d);
        assertThat(empty.scope().matches(d, Map.of("m", List.of("A")), Map.of())).isFalse();
    }

    @Test
    void hyperlinksNormalizeAndRejectActiveProtocols() {
        assertThat(HyperlinkValue.normalize(" https://example.com/?q=1 "))
                .isEqualTo(Map.of("link", "https://example.com/?q=1", "text", ""));
        assertThat(HyperlinkValue.normalize("https://例子.中国/说明"))
                .containsEntry("link", "https://例子.中国/说明");
        assertThatThrownBy(() -> HyperlinkValue.normalize("https://example.com:99999"))
                .isInstanceOf(RuntimeException.class);
        assertThat(HyperlinkValue.normalize(Map.of("link", "", "text", ""))).isNull();
        for (String url :
                List.of(
                        "javascript:alert(1)",
                        "data:text/html,x",
                        "//example.com",
                        "https://name:pass@example.com",
                        "https://example.com\\evil"))
            assertThatThrownBy(() -> HyperlinkValue.normalize(url))
                    .isInstanceOf(RuntimeException.class);
    }
}
