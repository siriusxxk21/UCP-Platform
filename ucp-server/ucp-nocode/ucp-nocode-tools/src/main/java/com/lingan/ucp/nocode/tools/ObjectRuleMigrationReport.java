package com.lingan.ucp.nocode.tools;

import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.FieldRules;

import java.util.List;
import java.util.Map;

/**
 * 对象规则迁移报告（设计稿 9.2、15.5）：dry-run 只读产出，apply、rollback、compare 只按报告执行。
 *
 * <p>报告同时是回滚依据：每个受影响应用记录迁移前的对象固定版本与表单节点 JSON。
 *
 * <p>infos 只作提示、不影响执行：目前记录「要写的对象上有与已发布版本一致的空草稿、apply 时沿用」（设计稿 9.2）。旧报告没有 infos、
 * reusedDraftVersion，读出为 null，含义与「没有沿用草稿」相同。
 */
public record ObjectRuleMigrationReport(
        String tool,
        int formatVersion,
        String executedAt,
        String scopePrefix,
        Summary summary,
        List<Fill> fills,
        List<Conflict> conflicts,
        List<ObjectChange> objects,
        List<ApplicationChange> applications,
        List<Impact> impacts,
        List<String> blockers,
        List<String> warnings,
        List<String> infos) {
    public static final String TOOL = "ObjectRuleMigrationTool";
    public static final int FORMAT_VERSION = 1;

    /** 本工具计划迁移。 */
    public static final String PLANNED = "PLANNED";

    /** 对象上已有相同的数据联动（二次运行或回滚后重跑）。 */
    public static final String ALREADY_APPLIED = "ALREADY_APPLIED";

    /** 同一对象同一目标字段在不同表单上有不同带入：整条跳过并标红，表单上的带入原样保留。 */
    public static final String CONFLICT = "CONFLICT";

    /** 目标字段在对象最新发布版本中不可用或已有其它值来源：跳过，表单上的带入原样保留。 */
    public static final String BLOCKED = "BLOCKED";

    /** 内部明细表单上的带入：本工具不迁移，表单上的带入原样保留（线上核实无此类配置，15.5.1）。 */
    public static final String DETAIL_NOT_MIGRATED = "DETAIL_NOT_MIGRATED";

    public record Summary(
            int fills,
            int planned,
            int alreadyApplied,
            int conflicts,
            int blocked,
            int detailFills,
            int objectOptionDefaults,
            int formOptionDefaults,
            int objects,
            int applications,
            int impacts,
            int blockers,
            int reusedDrafts) {}

    /** 每个表单节点上的一条关联带入。value* 描述来源值字段的类型与计算模式（15.5.2），供 compare 对照； linkage 为计划写入目标字段的数据联动。 */
    public record Fill(
            String applicationId,
            String applicationCode,
            String formId,
            String formName,
            String nodeKey,
            String detailId,
            String objectId,
            String objectName,
            String targetFieldId,
            String targetFieldName,
            String targetFieldType,
            String sourceFieldId,
            String sourceFieldName,
            String sourceObjectId,
            String sourceObjectName,
            String valueFieldId,
            String valueFieldName,
            String valueFieldType,
            String valueResultType,
            String valueCalculationMode,
            String valueCalculationUpdateMode,
            String valueStorage,
            String mode,
            String modeNote,
            FieldRules.Linkage linkage,
            String status,
            String reason) {}

    public record Conflict(
            String objectId,
            String objectName,
            String targetFieldId,
            String targetFieldName,
            List<String> bindings,
            List<String> forms) {}

    /**
     * blocker 非空时整次 apply 拒绝执行。reusedDraftVersion：dry-run 时对象上有与已发布版本一致的空草稿，记其版本号，apply
     * 沿用该草稿写入并发布；没有草稿时为 null。
     */
    public record ObjectChange(
            String objectId,
            String objectCode,
            String objectName,
            int publishedVersion,
            List<Linkage> linkages,
            List<OptionDefault> optionDefaults,
            String blocker,
            Integer reusedDraftVersion) {}

    public record Linkage(
            String fieldId,
            String fieldName,
            String fieldType,
            FieldRules.Linkage linkage,
            String status) {}

    /** 对象字段上的选项类默认值；detailId 为空表示主表字段。 */
    public record OptionDefault(
            String detailId,
            String fieldId,
            String fieldName,
            String fieldType,
            String defaultValue) {}

    /**
     * 受影响应用。objectsBefore 与 forms[].configBefore、forms[].nodesBefore 是回滚依据；upgradeObjectIds 是 apply
     * 时要升到最新发布版本的对象。
     */
    public record ApplicationChange(
            String applicationId,
            String applicationCode,
            String applicationName,
            int publishedVersion,
            boolean draftDiffers,
            List<ApplicationCenter.ObjectReference> objectsBefore,
            List<String> upgradeObjectIds,
            List<Form> forms,
            List<String> warnings) {}

    /** stripFillNodes：要去掉 fill 的节点；optionDefaults：要去掉 selection.defaultValue 的节点。 */
    public record Form(
            String formId,
            String formName,
            String objectId,
            Map<String, Object> configBefore,
            List<String> stripFillNodes,
            List<FormOptionDefault> optionDefaults,
            Map<String, Map<String, Object>> nodesBefore) {}

    public record FormOptionDefault(
            String nodeKey,
            String detailId,
            String fieldId,
            String fieldName,
            String fieldType,
            Object defaultValue) {}

    /** 影响面扩大：同一对象下没有该带入、但含目标字段的其它表单，迁移后也会带出建议值（设计稿 9.2、U3）。 */
    public record Impact(
            String applicationId,
            String applicationCode,
            String objectId,
            String objectName,
            String formId,
            String formName,
            List<String> targetFieldNames) {}
}
