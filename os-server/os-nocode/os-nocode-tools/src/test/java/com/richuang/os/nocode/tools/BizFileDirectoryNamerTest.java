package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.BusinessFileLabelStatusEnum;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileDirectoryNamer;
import com.richuang.os.nocode.runtime.service.record.RecordSelectionSupport;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 目录标签规则：受限分组只返回占位文案与摘要前缀，记录标签全部不可读时退化为安全短标识， 目录段规范化不产生层级分隔符。只覆盖不依赖查询协作者的路径，授权分支由浏览集成用例覆盖。 */
class BizFileDirectoryNamerTest {
    private static final String GROUP_FIELD = "f-dept";
    private static final String TITLE_FIELD = "f-title";

    /** 受限分组令牌样式：服务端摘要，无法还原来源值 */
    private static final String DIGEST = "9f6c1d2a4b5e778899aabbccddeeff00";

    private final BizFileDirectoryNamer namer = new BizFileDirectoryNamer();

    @Test
    void restrictedGroupKeepsPlaceholderAndDigestPrefixOnly() {
        BizFileDirectoryNamer.Label label =
                namer.groupView(
                        definition(),
                        new DataCenter.BusinessFileGroup(GROUP_FIELD, null),
                        DIGEST,
                        true,
                        null,
                        10001);
        assertThat(label.restricted()).isTrue();
        assertThat(label.text()).isEqualTo("受限分组 · 9f6c1d2a");
        assertThat(label.text()).doesNotContain("销售部");
    }

    @Test
    void emptyGroupIsUnclassifiedAndReadableGroupKeepsDisplayValue() {
        DataCenter.BusinessFileGroup group = new DataCenter.BusinessFileGroup(GROUP_FIELD, null);
        assertThat(namer.groupView(definition(), group, "", false, null, 10001))
                .isEqualTo(new BizFileDirectoryNamer.Label("未分类", false));
        assertThat(namer.groupView(definition(), group, "销售部", false, null, 10001))
                .isEqualTo(new BizFileDirectoryNamer.Label("销售部", false));
        assertThat(
                        namer.groupView(
                                        definition(),
                                        new DataCenter.BusinessFileGroup(GROUP_FIELD, "YEAR"),
                                        "2026-03-05",
                                        false,
                                        null,
                                        10001)
                                .text())
                .isEqualTo("2026");
    }

    @Test
    void recordLabelFallsBackToShortIdWhenNoLabelValueIsReadable() {
        DataCenter.BusinessFilePolicy policy = policy(List.of(TITLE_FIELD));
        assertThat(namer.recordView(definition(), policy, Map.of(), "abcdefghijklmn", null, 10001))
                .isEqualTo(new BizFileDirectoryNamer.Label("记录 ghijklmn", true));
        assertThat(
                        namer.recordView(
                                definition(),
                                policy,
                                Map.of(TITLE_FIELD, "采购合同A"),
                                "1",
                                null,
                                10001))
                .isEqualTo(new BizFileDirectoryNamer.Label("采购合同A", false));
    }

    @Test
    void unconfiguredNameReusesTitleAndTemplateWithoutTreatingEmptyAsRestricted() {
        DataCenter.BusinessFilePolicy defaultPolicy = policy(List.of());
        assertThat(
                        namer.recordView(
                                        definition(),
                                        defaultPolicy,
                                        Map.of(TITLE_FIELD, "采购合同A"),
                                        "1",
                                        null,
                                        10001)
                                .text())
                .isEqualTo("采购合同A");
        Map<String, Object> empty = new HashMap<>();
        empty.put(TITLE_FIELD, null);
        assertThat(namer.recordView(definition(), defaultPolicy, empty, "1", null, 10001))
                .isEqualTo(
                        new BizFileDirectoryNamer.Label(
                                "未填写标题 · ID 1", false, BusinessFileLabelStatusEnum.EMPTY));
        DataCenter.Definition template =
                configuredDefinition("{{dept}} · {{name}}", "TEXT", List.of());
        assertThat(
                        namer.recordView(
                                        template,
                                        defaultPolicy,
                                        Map.of(TITLE_FIELD, "采购合同A", GROUP_FIELD, "销售部"),
                                        "1",
                                        null,
                                        10001)
                                .text())
                .isEqualTo("销售部 · 采购合同A");
        BizFileDirectoryNamer.Label restricted =
                namer.recordView(
                        template, defaultPolicy, Map.of(TITLE_FIELD, "采购合同A"), "1", null, 10001);
        assertThat(restricted.restricted()).isTrue();
        assertThat(restricted.status()).isEqualTo(BusinessFileLabelStatusEnum.RESTRICTED);
        assertThat(restricted.text()).isEqualTo("记录 1");
    }

    @Test
    void staticObjectTitleTemplateKeepsItsConfiguredText() {
        DataCenter.Definition definition = configuredDefinition("业务档案", "TEXT", List.of());
        assertThat(namer.recordView(definition, policy(List.of()), Map.of(), "1", null, 10001))
                .isEqualTo(new BizFileDirectoryNamer.Label("业务档案", false));
    }

    @Test
    void obsoleteNameFieldAndPartiallyHiddenNameHaveDistinctStatuses() {
        BizFileDirectoryNamer.Label invalid =
                namer.recordView(
                        definition(),
                        policy(List.of("deleted-field")),
                        Map.of("deleted-field", "不应显示的历史值"),
                        "1",
                        null,
                        10001);
        assertThat(invalid.status()).isEqualTo(BusinessFileLabelStatusEnum.INVALID);
        assertThat(invalid.restricted()).isFalse();
        assertThat(invalid.text()).doesNotContain("不应显示的历史值");
        BizFileDirectoryNamer.Label partial =
                namer.recordView(
                        definition(),
                        policy(List.of(TITLE_FIELD, GROUP_FIELD)),
                        Map.of(TITLE_FIELD, "采购合同A"),
                        "1",
                        null,
                        10001);
        assertThat(partial.text()).isEqualTo("采购合同A");
        assertThat(partial.status()).isEqualTo(BusinessFileLabelStatusEnum.RESTRICTED);
        assertThat(partial.restricted()).isTrue();
    }

    @Test
    void inactiveTitleFieldIsInvalidInsteadOfPermissionLimited() {
        DataCenter.Definition base = definition();
        DataCenter.FieldOptions inactive =
                DataCenter.FieldOptions.copyOf(DataCenter.FieldOptions.defaults())
                        .state("INACTIVE")
                        .build();
        DataCenter.Definition definition =
                new DataCenter.Definition(
                        base.objectId(),
                        base.objectCode(),
                        base.objectName(),
                        base.description(),
                        base.schemaName(),
                        base.tableName(),
                        base.source(),
                        base.readOnly(),
                        base.titleFieldId(),
                        base.settings(),
                        base.fields(),
                        Map.of(TITLE_FIELD, inactive),
                        base.relations(),
                        base.indexes(),
                        base.details());
        BizFileDirectoryNamer.Label label =
                namer.recordView(definition, policy(List.of()), Map.of(), "1", null, 10001);
        assertThat(label.status()).isEqualTo(BusinessFileLabelStatusEnum.INVALID);
        assertThat(label.restricted()).isFalse();
        assertThat(
                        namer.recordView(
                                        definition,
                                        policy(List.of(TITLE_FIELD)),
                                        Map.of(TITLE_FIELD, "不应显示的历史值"),
                                        "1",
                                        null,
                                        10001)
                                .text())
                .doesNotContain("不应显示的历史值");
    }

    @Test
    void inaccessibleReferenceNeverFallsBackToItsRawRecordId() {
        RecordSelectionSupport selections = mock(RecordSelectionSupport.class);
        ReflectionTestUtils.setField(namer, "selections", selections);
        DataCenter.Relation relation =
                new DataCenter.Relation(
                        "rel",
                        "customer",
                        "客户",
                        "MANY_TO_ONE",
                        "target-object",
                        TITLE_FIELD,
                        null,
                        false,
                        "RESTRICT");
        DataCenter.Definition definition =
                configuredDefinition(null, "REFERENCE", List.of(relation));
        when(selections.referenceOptions(null, "target-object", List.of("987654321"), 10001))
                .thenReturn(Map.of());
        BizFileDirectoryNamer.Label label =
                namer.recordView(
                        definition,
                        policy(List.of(TITLE_FIELD)),
                        Map.of(TITLE_FIELD, "987654321"),
                        "1",
                        null,
                        10001);
        assertThat(label.text()).doesNotContain("987654321");
        assertThat(label.status()).isEqualTo(BusinessFileLabelStatusEnum.RESTRICTED);
        when(selections.referenceOptions(null, "target-object", List.of("987654321"), 10001))
                .thenThrow(new IllegalArgumentException("目标对象不可见"));
        assertThat(
                        namer.recordView(
                                        definition,
                                        policy(List.of(TITLE_FIELD)),
                                        Map.of(TITLE_FIELD, "987654321"),
                                        "1",
                                        null,
                                        10001)
                                .status())
                .isEqualTo(BusinessFileLabelStatusEnum.RESTRICTED);
    }

    private DataCenter.Definition configuredDefinition(
            String template, String titleType, List<DataCenter.Relation> relations) {
        DataCenter.Definition base = definition();
        FieldDefinition title =
                new FieldDefinition(
                        "new-title",
                        TITLE_FIELD,
                        "name",
                        "合同名称",
                        titleType,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1);
        return new DataCenter.Definition(
                base.objectId(),
                base.objectCode(),
                base.objectName(),
                null,
                base.schemaName(),
                base.tableName(),
                base.source(),
                false,
                TITLE_FIELD,
                new DataCenter.Settings(null, null, null, template),
                List.of(base.fields().getFirst(), title),
                Map.of(),
                relations,
                List.of(),
                List.of());
    }

    @Test
    void groupKeysReplaceLevelSeparatorAndSanitizeNormalizesSegments() {
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "验证空间",
                        List.of("合同"),
                        List.of(new DataCenter.BusinessFileGroup(GROUP_FIELD, null)),
                        List.of(),
                        List.of());
        assertThat(
                        namer.groupKeys(
                                definition(), policy, Map.of(GROUP_FIELD, "销售|部", "unused", "x")))
                .isEqualTo("销售_部");
        assertThat(namer.recordPath(definition(), policy, Map.of(), "1", null, 10001))
                .containsExactly("合同", "未分类", "记录 1");
        assertThat(namer.sanitize("合同/附件")).isEqualTo("合同 附件");
        assertThat(namer.sanitize("..")).isEqualTo("未分类");
        assertThat(namer.sanitize("   ")).isEqualTo("未分类");
        assertThat(namer.sanitize("a\nb")).doesNotContain("\n");
        assertThat(namer.sanitize("x".repeat(150))).hasSize(100);
    }

    private DataCenter.BusinessFilePolicy policy(List<String> labelFields) {
        return new DataCenter.BusinessFilePolicy(
                "验证空间", List.of(), List.of(), labelFields, List.of());
    }

    private DataCenter.Definition definition() {
        FieldDefinition dept =
                new FieldDefinition(
                        "dept",
                        GROUP_FIELD,
                        "dept",
                        "归属部门",
                        "TEXT",
                        null,
                        null,
                        null,
                        false,
                        false,
                        0);
        FieldDefinition title =
                new FieldDefinition(
                        "new-title",
                        TITLE_FIELD,
                        "name",
                        "合同名称",
                        "TEXT",
                        null,
                        null,
                        null,
                        false,
                        false,
                        1);
        return new DataCenter.Definition(
                "1",
                "verify",
                "验证对象",
                null,
                "public",
                "biz_verify",
                "GENERATED",
                false,
                TITLE_FIELD,
                null,
                List.of(dept, title),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
