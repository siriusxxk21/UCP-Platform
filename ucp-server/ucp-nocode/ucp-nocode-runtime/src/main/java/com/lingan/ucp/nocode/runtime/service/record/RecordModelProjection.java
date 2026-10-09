package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 按当前能力裁剪模型和整单返回值，避免隐藏字段与规则泄露。 */
@Component
public class RecordModelProjection {
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordSummaries summaries;

    Aggregate visibleAggregate(Aggregate source, Row main) {
        Map<String, List<Row>> output = new LinkedHashMap<>(source.details());
        output.keySet().retainAll(main.permissions().readDetails());
        return new Aggregate(main, output);
    }

    /** 元数据也按可读字段裁剪，避免在模型接口泄露隐藏字段默认值和设计说明。 */
    DataCenter.Definition visibleDefinition(
            DataCenter.Definition d, ApplicationAuthorization.Capabilities caps) {
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>(d.fieldOptions());
        options.keySet().retainAll(caps.readFields());
        // 对象规则只向客户端暴露依赖字段；来源对象、常量与条件留在服务端。
        var graph = FieldRuleGraph.of(d);
        options.replaceAll((id, o) -> visibleRules(o, graph, id));
        return new DataCenter.Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                d.readOnly(),
                caps.readFields().contains(d.titleFieldId()) ? d.titleFieldId() : null,
                visibleDocumentSettings(d, caps),
                d.fields().stream()
                        .filter(f -> caps.readFields().contains(f.id()))
                        .filter(
                                f ->
                                        !FieldTypeEnum.SUMMARY.matches(f.type())
                                                || caps.readDetails()
                                                        .contains(summaries.detailId(d, f)))
                        .toList(),
                options,
                d.relations().stream()
                        .filter(
                                r ->
                                        RelationTypeEnum.MANY_TO_MANY.matches(r.kind())
                                                ? caps.readRelations().contains(r.id())
                                                : r.sourceDetailId() != null
                                                        ? caps.readDetails()
                                                                .contains(r.sourceDetailId())
                                                        : r.fieldId() != null
                                                                && caps.readFields()
                                                                        .contains(r.fieldId()))
                        .toList(),
                List.of(),
                d.details().stream()
                        .filter(t -> caps.readDetails().contains(t.id()))
                        .map(t -> visibleRules(t, graph))
                        .toList(),
                d.mainBinding());
    }

    /**
     * 规则只保留服务端计算的 dependsOn、readOnly（有只读联动或公式默认值时为 true，前端据此锁住字段）与只读联动标记 linkage.readOnly=true
     * （兼容前端已有读取；配置为 null 时按只读，也下发 true）；来源对象、条件、取值字段、多行档位、公式与取整一律抹掉。无规则的字段原样返回。
     *
     * <p>只读联动开启了「来源变化时自动更新」时另下发 linkage.autoUpdate=true（前端据此显示「系统自动更新」标识）；没开时这个键不出现。
     * 「没有匹配记录时填入」的值永不下发。
     */
    static DataCenter.FieldOptions visibleRules(
            DataCenter.FieldOptions options, FieldRuleGraph graph, String fieldId) {
        if (options == null || options.rules() == null) return options;
        var rules = options.rules();
        var linkage =
                rules.readOnlyLinkage()
                        ? new FieldRules.Linkage(
                                null,
                                List.of(),
                                null,
                                null,
                                true,
                                rules.linkage().autoUpdateOn() ? Boolean.TRUE : null,
                                null)
                        : null;
        return options.withRules(
                new FieldRules(
                        null,
                        linkage,
                        null,
                        null,
                        graph.dependsOn(fieldId),
                        rules.effectiveReadOnly() ? Boolean.TRUE : null));
    }

    static DataCenter.Detail visibleRules(DataCenter.Detail detail, FieldRuleGraph graph) {
        Map<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<>(
                        detail.fieldOptions() == null ? Map.of() : detail.fieldOptions());
        options.replaceAll((id, o) -> visibleRules(o, graph, id));
        return new DataCenter.Detail(
                detail.id(),
                detail.code(),
                detail.name(),
                detail.tableName(),
                detail.state(),
                detail.fields(),
                options,
                detail.indexes(),
                detail.binding());
    }

    /** 设置按可读能力整体重建：整单规则与业务文件规则都必须裁剪后再下发， 任一规则未启用或不含可读字段时对应部分返回 null。 */
    static DataCenter.Settings visibleDocumentSettings(
            DataCenter.Definition d, ApplicationAuthorization.Capabilities caps) {
        DataCenter.Settings settings = d.settings();
        if (settings == null) return null;
        return new DataCenter.Settings(
                settings.icon(),
                settings.ownerId(),
                settings.organizationId(),
                settings.titleTemplate(),
                visibleDocumentPolicy(d, caps),
                visibleBusinessFilePolicy(d, caps));
    }

    private static DocumentPolicy visibleDocumentPolicy(
            DataCenter.Definition d, ApplicationAuthorization.Capabilities caps) {
        DocumentPolicy policy = DocumentPolicies.policy(d);
        if (policy == null) return null;
        List<DocumentPolicy.Rule> rules =
                policy.rules().stream()
                        .filter(
                                r ->
                                        (r.detailId() == null
                                                        || caps.readDetails()
                                                                .contains(r.detailId()))
                                                && RecordDocumentValidation.readableExpression(
                                                        r.when(), d, caps)
                                                && RecordDocumentValidation.readableExpression(
                                                        r.assertion(), d, caps)
                                                && (r.fieldId() == null
                                                        || caps.readFields().contains(r.fieldId())
                                                        || r.detailId() != null))
                        .toList();
        DocumentPolicy.Lifecycle lifecycle = policy.lifecycle();
        if (lifecycle != null && caps.readFields().contains(lifecycle.fieldId())) {
            lifecycle =
                    new DocumentPolicy.Lifecycle(
                            lifecycle.fieldId(),
                            lifecycle.initialState(),
                            lifecycle.states().stream()
                                    .map(
                                            state ->
                                                    new DocumentPolicy.State(
                                                            state.code(),
                                                            state.name(),
                                                            state.lockedFields().stream()
                                                                    .filter(
                                                                            caps.readFields()
                                                                                    ::contains)
                                                                    .toList(),
                                                            state.lockedDetails().stream()
                                                                    .filter(
                                                                            caps.readDetails()
                                                                                    ::contains)
                                                                    .toList(),
                                                            state.allowDelete()))
                                    .toList(),
                            lifecycle.actions().stream()
                                    .filter(action -> caps.actions().contains(action.permission()))
                                    .toList());
        } else lifecycle = null;
        return new DocumentPolicy(rules, lifecycle, policy.handling());
    }

    /** 业务文件规则按可读字段裁剪：目录模板与字段名称属于设计信息，隐藏字段不能借位置提示泄露； 全部参与字段都不可读时整体返回 null，前端按普通附件呈现。 */
    private static DataCenter.BusinessFilePolicy visibleBusinessFilePolicy(
            DataCenter.Definition d, ApplicationAuthorization.Capabilities caps) {
        DataCenter.BusinessFilePolicy policy =
                d.settings() == null ? null : d.settings().businessFilePolicy();
        if (!DataCenter.BusinessFilePolicy.enabled(policy)) return null;
        List<String> fieldIds =
                policy.fieldIds().stream().filter(id -> readableField(d, caps, id)).toList();
        if (fieldIds.isEmpty()) return null;
        List<DataCenter.BusinessFileGroup> groups =
                policy.groups() == null
                        ? List.of()
                        : policy.groups().stream()
                                .filter(group -> caps.readFields().contains(group.fieldId()))
                                .toList();
        List<String> labels =
                policy.recordLabelFields() == null
                        ? List.of()
                        : policy.recordLabelFields().stream()
                                .filter(caps.readFields()::contains)
                                .toList();
        return new DataCenter.BusinessFilePolicy(
                policy.spaceId(), policy.spaceName(), policy.fixedPath(), groups, labels, fieldIds);
    }

    /** 主表字段按字段能力判定；明细字段按所属明细的可读能力判定。 */
    private static boolean readableField(
            DataCenter.Definition d, ApplicationAuthorization.Capabilities caps, String fieldId) {
        if (caps.readFields().contains(fieldId)) return true;
        return d.details().stream()
                .anyMatch(
                        detail ->
                                caps.readDetails().contains(detail.id())
                                        && detail.fields().stream()
                                                .anyMatch(f -> f.id().equals(fieldId)));
    }
}
