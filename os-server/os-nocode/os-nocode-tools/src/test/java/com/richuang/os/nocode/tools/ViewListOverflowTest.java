package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.enums.ListOverflowEnum;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** 视图列表的「内容超出列宽时」：可选键；没有配置的旧定义读出再写回不变，配置了只接受“自动截断”。 */
class ViewListOverflowTest {
    /** 与设计入口 StrictRequestDecoder 相同：未知属性直接拒绝。 */
    private final ObjectMapper strict =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 现有已保存视图的形状（取自线上同形状的定义）：list 里只有四个键。 */
    private static final String OLD_VIEW =
            "{\"objectId\":\"110\",\"fieldIds\":[\"634\",\"635\"],\"equal\":{},\"sortFieldId\":null,"
                + "\"descending\":true,\"pageSize\":10,\"formId\":\"form_flow\","
                + "\"filterDictionaries\":{},\"detailPageId\":null,"
                + "\"interaction\":{\"buttons\":[\"CREATE\",\"VIEW\"],\"actionIds\":[],"
                + "\"editMode\":\"DRAWER\",\"detailMode\":\"DRAWER\"},"
                + "\"list\":{\"queryFieldIds\":[\"634\"],\"advancedFieldIds\":null,"
                + "\"columnWidths\":{\"634\":300},\"batchDelete\":true},"
                + "\"query\":null,\"composition\":null}";

    @Test
    void oldDefinitionsReadAndWriteBackUnchanged() throws Exception {
        var view = strict.readValue(OLD_VIEW, ApplicationUi.View.class);
        assertThat(view.list().overflow()).isNull();
        assertThat(view.list().columnWidths()).containsEntry("634", 300);
        // 写回：没有配置时不出现这个键，整份定义与原来逐键相同（校验和按这份内容算，不会因为升级而变）。
        assertThat(strict.readTree(strict.writeValueAsString(view)))
                .isEqualTo(strict.readTree(OLD_VIEW));
        var list = (Map<?, ?>) strict.convertValue(view, Map.class).get("list");
        assertThat(list.containsKey("overflow")).isFalse();
        assertThat(list.size()).isEqualTo(4);
    }

    @Test
    void configuredValueSurvivesTheRoundTrip() throws Exception {
        String json =
                OLD_VIEW.replace(
                        "\"batchDelete\":true", "\"batchDelete\":true,\"overflow\":\"ELLIPSIS\"");
        var view = strict.readValue(json, ApplicationUi.View.class);
        assertThat(view.list().overflow()).isEqualTo("ELLIPSIS");
        assertThat(strict.readTree(strict.writeValueAsString(view)))
                .isEqualTo(strict.readTree(json));
        // 写成 null 与不写等价：读得进来，写回时不出现这个键。
        var explicitNull =
                strict.readValue(
                        OLD_VIEW.replace(
                                "\"batchDelete\":true", "\"batchDelete\":true,\"overflow\":null"),
                        ApplicationUi.View.class);
        assertThat(strict.readTree(strict.writeValueAsString(explicitNull)))
                .isEqualTo(strict.readTree(OLD_VIEW));
    }

    @Test
    void onlyEllipsisIsAccepted() {
        assertThat(ListOverflowEnum.optional(null)).isNull();
        assertThat(ListOverflowEnum.optional("ELLIPSIS")).isEqualTo("ELLIPSIS");
        assertThat(ListOverflowEnum.values()).containsExactly(ListOverflowEnum.ELLIPSIS);
        // 「自动扩展行高」（WRAP）业务方裁定不做：与其它无效值一样被拒。
        for (String bad : new String[] {"WRAP", "", "ellipsis", "AUTO", "ELLIPSIS "})
            assertThatThrownBy(() -> ListOverflowEnum.optional(bad))
                    .as(bad)
                    .hasMessage("“内容超出列宽时”只支持“自动截断”");
    }

    @Test
    void neighbouringUnknownKeysAreStillRejected() {
        String typo =
                OLD_VIEW.replace(
                        "\"batchDelete\":true", "\"batchDelete\":true,\"overflows\":\"WRAP\"");
        assertThatThrownBy(() -> strict.readValue(typo, ApplicationUi.View.class))
                .hasMessageContaining("overflows");
    }
}
