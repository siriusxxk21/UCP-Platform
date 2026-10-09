package com.richuang.os.nocode.metadata.service.object;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.nocode.api.FieldRules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 设计保存与对象复制都会用 remapRules 重建数据联动：自动更新开关、空值填入与「等于当前记录」条件必须原样带过去。
 * 漏带不会有任何报错——每保存一次就静默丢一次。完整的保存→再保存→复制链路见 LinkageRulesSurviveDesignSaveIntegrationTest。
 */
class LinkageRulesSurviveRemapTest {
    private static FieldRules rules() {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        "200",
                        List.of(
                                new FieldRules.Condition("203", "eq", "CURRENT_RECORD", null, null),
                                new FieldRules.Condition("204", "eq", "FORM_FIELD", null, "k1")),
                        "201",
                        "FIRST",
                        true,
                        true,
                        "wdj"),
                null,
                null,
                null,
                null);
    }

    @Test
    void designSaveKeepsAutoUpdateEmptyValueAndCurrentRecordCondition() {
        // 设计保存：临时键改指正式字段 ID，来源不是本对象。
        var saved = ObjectDesignService.remapRules(rules(), Map.of("k1", "105"), null, null);
        var l = saved.linkage();
        assertThat(l.autoUpdate()).isTrue();
        assertThat(l.emptyValue()).isEqualTo("wdj");
        assertThat(l.readOnly()).isTrue();
        assertThat(l.conditions())
                .containsExactly(
                        new FieldRules.Condition("203", "eq", "CURRENT_RECORD", null, null),
                        new FieldRules.Condition("204", "eq", "FORM_FIELD", null, "105"));
        // 再保存一次仍在。
        var again = ObjectDesignService.remapRules(saved, Map.of(), null, null);
        assertThat(again).isEqualTo(saved);
    }

    @Test
    void objectCopyKeepsThemToo() {
        // 复制对象：来源是别的对象时来源对象与条件字段不改指，三项配置原样保留。
        var copied = ObjectDesignService.remapRules(rules(), Map.of("105", "905"), "100", "900");
        var l = copied.linkage();
        assertThat(l.sourceObjectId()).isEqualTo("200");
        assertThat(l.autoUpdate()).isTrue();
        assertThat(l.emptyValue()).isEqualTo("wdj");
        assertThat(l.conditions().getFirst())
                .isEqualTo(new FieldRules.Condition("203", "eq", "CURRENT_RECORD", null, null));
    }
}
