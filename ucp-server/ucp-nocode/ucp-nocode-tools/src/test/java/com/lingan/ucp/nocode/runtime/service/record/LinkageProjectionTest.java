package com.lingan.ucp.nocode.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRuleGraph;
import com.lingan.ucp.nocode.api.FieldRules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 运行模型投影里的数据联动：只读联动开启自动更新时下发 autoUpdate=true，没开时这个键不出现；空值填入、来源与条件永不下发。 */
class LinkageProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static FieldDefinition field(String id) {
        return new FieldDefinition(
                id, id, "c_" + id, "字段" + id, "TEXT", 100, null, null, false, false, 0);
    }

    private static DataCenter.FieldOptions projected(FieldRules.Linkage linkage) {
        var options =
                DataCenter.FieldOptions.defaults()
                        .withRules(new FieldRules(null, linkage, null, null, null, null));
        var d =
                new DataCenter.Definition(
                        "100",
                        "target",
                        "目标",
                        null,
                        "public",
                        "biz_target",
                        "GENERATED",
                        false,
                        "101",
                        DataCenter.Settings.defaults(),
                        List.of(field("101"), field("102")),
                        Map.of("102", options),
                        List.of(),
                        List.of(),
                        List.of());
        return RecordModelProjection.visibleRules(options, FieldRuleGraph.of(d), "102");
    }

    private static FieldRules.Linkage linkage(
            Boolean readOnly, Boolean autoUpdate, String emptyValue) {
        return new FieldRules.Linkage(
                "200",
                List.of(new FieldRules.Condition("203", "eq", "CURRENT_RECORD", null, null)),
                "201",
                "FIRST",
                readOnly,
                autoUpdate,
                emptyValue);
    }

    @Test
    void autoUpdateOnIsProjectedWithoutEmptyValueSourceOrConditions() throws Exception {
        var rules = projected(linkage(true, true, "wdj")).rules();
        assertThat(rules.linkage().autoUpdate()).isTrue();
        assertThat(rules.linkage().readOnly()).isTrue();
        assertThat(rules.linkage().emptyValue()).isNull();
        assertThat(rules.linkage().sourceObjectId()).isNull();
        assertThat(rules.linkage().valueFieldId()).isNull();
        assertThat(rules.linkage().multiRow()).isNull();
        assertThat(rules.linkage().conditions()).isEmpty();
        assertThat(rules.readOnly()).isTrue();
        String json = JSON.writeValueAsString(rules);
        assertThat(json).contains("\"autoUpdate\":true");
        assertThat(json).doesNotContain("emptyValue").doesNotContain("wdj").doesNotContain("203");
    }

    @Test
    void autoUpdateOffLeavesTheKeyOut() throws Exception {
        for (Boolean autoUpdate : new Boolean[] {null, false}) {
            var rules = projected(linkage(null, autoUpdate, null)).rules();
            assertThat(rules.linkage().readOnly()).isTrue();
            assertThat(rules.linkage().autoUpdate()).isNull();
            assertThat(JSON.writeValueAsString(rules))
                    .doesNotContain("autoUpdate")
                    .doesNotContain("emptyValue");
        }
    }

    /** 可手改的联动整体不投影（现有口径不变）。 */
    @Test
    void editableLinkageIsNotProjected() {
        var rules = projected(linkage(false, null, null)).rules();
        assertThat(rules.linkage()).isNull();
        assertThat(rules.readOnly()).isNull();
    }
}
