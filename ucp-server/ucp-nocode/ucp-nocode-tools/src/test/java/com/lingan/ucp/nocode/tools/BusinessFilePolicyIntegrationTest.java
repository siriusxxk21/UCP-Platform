package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.PublishCheckEnum;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 在当前开发库验证对象业务文件规则：保存阶段的结构校验拒绝、字段身份重映射与规则保持、 发布预检的默认文件阻断与在途会话/影响范围提示。 业务文件绑定表不在通用清理范围，本测试按对象前缀自行清理。
 */
class BusinessFilePolicyIntegrationTest {
    private NocodeIntegrationSupport fixture;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
    }

    @AfterEach
    void cleanup() {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_object WHERE object_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : ids) {
            jdbc.update(
                    "DELETE FROM public.nocode_biz_upload_session WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_directory_binding WHERE object_id=?",
                    id.toString());
        }
        fixture.clean();
    }

    @Test
    void invalidPoliciesAreRejectedAtDesignSave() {
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_text",
                                        policy(null, List.of("new-title"), List.of("dept")),
                                        null))
                .hasMessageContaining("业务文件仅支持附件或图片字段");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_empty",
                                        policy(null, List.of("new-title"), List.of()),
                                        null))
                .hasMessageContaining("至少选择一个附件或图片字段");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_groups",
                                        policy(
                                                List.of(
                                                        new DataCenter.BusinessFileGroup(
                                                                "dept", null),
                                                        new DataCenter.BusinessFileGroup(
                                                                "new-title", null),
                                                        new DataCenter.BusinessFileGroup(
                                                                "signed", null)),
                                                List.of("new-title"),
                                                List.of("files")),
                                        null))
                .hasMessageContaining("业务分组最多 2 层");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_group_type",
                                        policy(
                                                List.of(
                                                        new DataCenter.BusinessFileGroup(
                                                                "flag", null)),
                                                List.of("new-title"),
                                                List.of("files")),
                                        null))
                .hasMessageContaining("业务分组仅支持主表文本、单选、日期或单值关联字段");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_label_type",
                                        policy(null, List.of("files"), List.of("files")),
                                        null))
                .hasMessageContaining("记录目录名称不支持此字段类型");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_path",
                                        new DataCenter.BusinessFilePolicy(
                                                "合同空间",
                                                List.of("合同/附件"),
                                                null,
                                                List.of("new-title"),
                                                List.of("files")),
                                        null))
                .hasMessageContaining("不能包含路径分隔符");
        assertThatThrownBy(
                        () ->
                                save(
                                        "reject_space",
                                        new DataCenter.BusinessFilePolicy(
                                                " ",
                                                List.of("合同"),
                                                null,
                                                List.of("new-title"),
                                                List.of("files")),
                                        null))
                .hasMessageContaining("业务空间名称不能为空");
    }

    @Test
    void policyKeepsStableFieldIdsAcrossDesignSaves() {
        DataCenter.Design design =
                save(
                        "remap",
                        policy(
                                List.of(new DataCenter.BusinessFileGroup("signed", "YEAR")),
                                List.of("new-title"),
                                List.of("files")),
                        null);
        String titleId = id(design, "name");
        String dateId = id(design, "signed");
        String filesId = id(design, "files");
        DataCenter.BusinessFilePolicy saved = design.settings().businessFilePolicy();
        assertThat(saved.spaceName()).isEqualTo("合同空间");
        assertThat(saved.fixedPath()).containsExactly("合同");
        assertThat(saved.groups()).hasSize(1);
        assertThat(saved.groups().getFirst().fieldId()).isEqualTo(dateId);
        assertThat(saved.groups().getFirst().format()).isEqualTo("YEAR");
        assertThat(saved.recordLabelFields()).containsExactly(titleId);
        assertThat(saved.fieldIds()).containsExactly(filesId);
        // 省略配置的重新保存沿用原规则（Settings 为 null 时读取上一版）
        ObjectDraft draft = design.draft();
        DataCenter.Design again =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(draft, List.of(), List.of(), draft.titleFieldId()),
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        assertThat(again.settings().businessFilePolicy()).isEqualTo(saved);
        assertThat(again.draft().lockVersion()).isEqualTo(draft.lockVersion() + 1);
    }

    @Test
    void publishBlocksAttachmentDefaultValueAndProceedsAfterClearing() {
        Map<String, DataCenter.FieldOptions> options = new HashMap<>();
        options.put("files", options("[\"101\"]"));
        DataCenter.Design design =
                save(
                        "default",
                        policy(
                                List.of(new DataCenter.BusinessFileGroup("signed", "YEAR")),
                                List.of("new-title"),
                                List.of("files")),
                        options);
        DataCenter.PublishPlan blocked = plan(design);
        assertThat(blocked.state()).isEqualTo("BLOCKED");
        DataCenter.Check blocking =
                check(blocked, PublishCheckEnum.BUSINESS_FILE_DEFAULT.getCode());
        assertThat(blocking).isNotNull();
        assertThat(blocking.blocking()).isTrue();
        assertThat(blocking.message()).contains("配置了默认文件");
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new DataCenter.ExecutePlan(blocked.id(), "默认文件应阻断发布"),
                                        10001))
                .hasMessageContaining("该计划不可执行");
        // 清除默认值后放行发布，规则随版本冻结
        Map<String, DataCenter.FieldOptions> cleared = new HashMap<>(design.fieldOptions());
        cleared.put(id(design, "files"), options(null));
        ObjectDraft draft = design.draft();
        DataCenter.Design fixed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        draft, draft.fields(), List.of(), draft.titleFieldId()),
                                null,
                                cleared,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan ready = plan(fixed);
        assertThat(ready.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(ready.id(), "业务文件接入发布"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        assertThat(objects.getPublished(fixed.draft().id()).settings().businessFilePolicy())
                .isEqualTo(fixed.settings().businessFilePolicy());
    }

    @Test
    void publishHintsInFlightSessionsAndArchivedScope() {
        DataCenter.Design design =
                save(
                        "scope",
                        policy(
                                List.of(new DataCenter.BusinessFileGroup("signed", "YEAR")),
                                List.of("new-title"),
                                List.of("files")),
                        null);
        DataCenter.PublishPlan first = plan(design);
        assertThat(first.checks()).noneMatch(DataCenter.Check::blocking);
        publisher.execute(new DataCenter.ExecutePlan(first.id(), "业务文件首版发布"), 10001);
        String objectId = design.draft().id();
        jdbc.update(
                "INSERT INTO public.nocode_biz_upload_session (session_key, user_id, object_id,"
                        + " field_id, file_id, file_name, state, expires_at) VALUES"
                        + " (?,?,?,?,?,?,?, now() + interval '1 day')",
                fixture.prefix + "-session",
                10001L,
                objectId,
                id(design, "files"),
                900001L,
                "待保存合同.pdf",
                "TEMPORARY");
        jdbc.update(
                "INSERT INTO public.nocode_biz_directory_binding (object_id, record_id, space_id,"
                        + " entry_id, rule_version) VALUES (?,?,?,?,1)",
                objectId,
                "record-1",
                800001L,
                810001L);
        DataCenter.Design current = designs.get(objectId);
        DataCenter.Design next =
                designs.editPublished(
                        new DataCenter.Revision(
                                current.draft().id(), current.draft().lockVersion(), null),
                        10001);
        ObjectDraft draft = next.draft();
        DataCenter.Design changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(draft, List.of(), List.of(), draft.titleFieldId()),
                                next.settings(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan second = plan(changed);
        DataCenter.Check sessions =
                check(second, PublishCheckEnum.BUSINESS_FILE_SESSIONS.getCode());
        assertThat(sessions).isNotNull();
        assertThat(sessions.blocking()).isFalse();
        assertThat(sessions.message()).contains("上传会话");
        DataCenter.Check scope = check(second, PublishCheckEnum.BUSINESS_FILE_SCOPE.getCode());
        assertThat(scope).isNotNull();
        assertThat(scope.blocking()).isFalse();
        assertThat(scope.message()).contains("沿用原规则版本");
        // 关闭接入：已归档记录保持原身份；阻断与会话检查不再出现
        DataCenter.Design disabled =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        changed.draft(),
                                        List.of(),
                                        List.of(),
                                        changed.draft().titleFieldId()),
                                new DataCenter.Settings(null, null, null, null),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan offboarding = plan(disabled);
        DataCenter.Check cancelled =
                check(offboarding, PublishCheckEnum.BUSINESS_FILE_SCOPE.getCode());
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.blocking()).isFalse();
        assertThat(cancelled.message()).contains("取消接入业务网盘");
        assertThat(check(offboarding, PublishCheckEnum.BUSINESS_FILE_DEFAULT.getCode())).isNull();
        assertThat(check(offboarding, PublishCheckEnum.BUSINESS_FILE_SESSIONS.getCode())).isNull();
    }

    private SaveObjectDraft request(String suffix) {
        SaveObjectDraft base = fixture.createRequest(suffix);
        List<FieldDefinition> fields =
                List.of(
                        fixture.field("new-title", "name", "TEXT", 0),
                        fixture.field("dept", "dept", "TEXT", 1),
                        fixture.field("signed", "signed", "DATE", 2),
                        fixture.field("flag", "flag", "BOOLEAN", 3),
                        fixture.field("files", "files", "ATTACHMENT", 4));
        return new SaveObjectDraft(
                null,
                null,
                base.objectCode(),
                "业务文件规则验证",
                null,
                base.tableName(),
                "new-title",
                fields,
                List.of());
    }

    private DataCenter.Design save(
            String suffix,
            DataCenter.BusinessFilePolicy policy,
            Map<String, DataCenter.FieldOptions> options) {
        return designs.save(
                new DataCenter.SaveDesign(
                        request(suffix),
                        policy == null
                                ? null
                                : new DataCenter.Settings(null, null, null, null, null, policy),
                        options,
                        List.of(),
                        List.of(),
                        List.of()),
                10001);
    }

    private DataCenter.BusinessFilePolicy policy(
            List<DataCenter.BusinessFileGroup> groups, List<String> labels, List<String> fields) {
        return new DataCenter.BusinessFilePolicy("合同空间", List.of("合同"), groups, labels, fields);
    }

    private DataCenter.FieldOptions options(String defaultValue) {
        return new DataCenter.FieldOptions(
                null,
                "NORMAL",
                defaultValue,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(),
                null,
                null,
                "NONE",
                null,
                false,
                false,
                null,
                null,
                null);
    }

    private DataCenter.PublishPlan plan(DataCenter.Design design) {
        return publisher.plan(
                new DataCenter.Revision(design.draft().id(), design.draft().lockVersion(), null),
                10001);
    }

    private DataCenter.Check check(DataCenter.PublishPlan plan, String code) {
        return plan.checks().stream().filter(c -> code.equals(c.code())).findFirst().orElse(null);
    }

    private String id(DataCenter.Design design, String code) {
        return design.draft().fields().stream()
                .filter(f -> code.equals(f.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }
}
