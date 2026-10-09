package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.DetailForms;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.metadata.service.form.FormFillBindings;
import com.richuang.os.nocode.metadata.service.object.BusinessFilePolicies;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.dal.query.*;
import com.richuang.os.nocode.runtime.dal.support.*;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.application.ApplicationBusinessRules;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess.QueryPlan;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleLabels;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleService;
import com.richuang.os.nocode.runtime.service.rules.ReferenceScope;
import com.richuang.os.nocode.runtime.service.rules.RuleContext;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;
import com.richuang.os.nocode.runtime.service.view.DataViewService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 运行模型、候选和分页查询编排；复用固定版本、权限范围和查询计划。 */
@Component
public class RecordQueryService {
    @Resource
    private com.richuang.os.nocode.metadata.service.formula.OrderedCalculationStateService
            orderedStates;

    @Resource private RecordContextResolver contexts;
    @Resource private RecordModelProjection projection;
    @Resource private RecordPersistence persistence;
    @Resource private RecordReadService reader;
    @Resource private RecordSelectionSupport selections;
    @Resource private RecordTransactions transactions;
    @Resource private RecordAutomations automations;
    @Resource private RecordConditions conditionCompiler;
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ApplicationService applications;

    @Resource
    private com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator
            resourceValidator;

    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ApplicationBusinessRules businessRules;
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordRelations relations;
    @Resource private RecordSummaries summaries;
    @Resource private FixedViewConditions fixedViewConditions;
    @Resource private RecordCalculations calculations;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordMapper records;
    @Resource private ReportMapper reportRecords;
    @Resource private org.springframework.beans.factory.ObjectProvider<DataViewService> dataViews;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.richuang.os.nocode.runtime.service.report.ApplicationReportService>
            reports;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.richuang.os.nocode.runtime.service.report
                            .ApplicationDashboardRuntimeService>
            dashboards;

    @Resource private FieldRuleService fieldRules;

    @Resource
    private com.richuang.os.nocode.application.service.sharing.ImpliedObjects impliedObjects;

    /** 运行模型只返回可见字段状态，不暴露管理员校准游标和失败细节。 */
    private List<OrderedCalculations.State> visibleOrderedStates(
            DataCenter.Definition d, Set<String> visible) {
        return orderedStates.forObject(d.objectId()).stream()
                .filter(
                        state ->
                                visible.contains(state.fieldId())
                                        && com.richuang.os.nocode.metadata.service.formula
                                                .Calculations.orderedStored(
                                                d.fieldOptions().get(state.fieldId())))
                .map(
                        state ->
                                new OrderedCalculations.State(
                                        state.objectId(),
                                        state.fieldId(),
                                        state.signature(),
                                        state.state(),
                                        Map.of(),
                                        0,
                                        0,
                                        0,
                                        null,
                                        state.revision()))
                .toList();
    }

    /** 读取固定应用版本的运行模型，叠加当前完整性规则和实际字段/明细能力。 */
    public Model model(String app, String object, long actor) {
        return transactions.tx(
                () -> {
                    DataCenter.Definition fixed = contexts.definition(app, object, actor);
                    DataCenter.Definition current = objects.getPublished(object);
                    DataCenter.Settings settings = fixed.settings();
                    DataCenter.Definition d =
                            new DataCenter.Definition(
                                    fixed.objectId(),
                                    fixed.objectCode(),
                                    fixed.objectName(),
                                    fixed.description(),
                                    fixed.schemaName(),
                                    fixed.tableName(),
                                    fixed.source(),
                                    fixed.readOnly(),
                                    fixed.titleFieldId(),
                                    new DataCenter.Settings(
                                            settings.icon(),
                                            settings.ownerId(),
                                            settings.organizationId(),
                                            settings.titleTemplate(),
                                            DocumentPolicies.policy(current),
                                            BusinessFilePolicies.policyOf(current)),
                                    fixed.fields(),
                                    fixed.fieldOptions(),
                                    fixed.relations(),
                                    fixed.indexes(),
                                    fixed.details(),
                                    fixed.mainBinding());
                    ApplicationRuntimePolicy.Access access = policy.access(app, d, actor);
                    ApplicationAuthorization.Capabilities caps =
                            access.forRow(Long.toString(actor));
                    if (caps.actions().contains(ApplicationActionEnum.CREATE.getCode()))
                        caps = access.require(Long.toString(actor), ApplicationActionEnum.CREATE);
                    RuntimeSchema.Table t = schemas.main(d);
                    Map<String, TableModel> details = new LinkedHashMap<>();
                    for (DataCenter.Detail detail : d.details())
                        if (MemberStateEnum.ACTIVE.matches(detail.state())
                                && caps.readDetails().contains(detail.id())) {
                            RuntimeSchema.Table table = schemas.detail(d, detail);
                            details.put(
                                    detail.id(),
                                    new TableModel(
                                            table.writable()
                                                    && access.forRow(Long.toString(actor))
                                                            .writeDetails()
                                                            .contains(detail.id()),
                                            table.generatedKey(),
                                            table.keyField(),
                                            table.key().nativeType()));
                        }
                    return new Model(
                            businessRules.decorate(
                                    app,
                                    projection.visibleDefinition(
                                            d, access.forRow(Long.toString(actor))),
                                    t),
                            t.writable()
                                    && (access.any(ApplicationActionEnum.CREATE)
                                            || access.any(ApplicationActionEnum.UPDATE)
                                            || access.any(ApplicationActionEnum.DELETE)),
                            t.generatedKey(),
                            t.keyField(),
                            t.key().nativeType(),
                            details,
                            caps,
                            automations.managedFields(object),
                            visibleOrderedStates(
                                    d, access.forRow(Long.toString(actor)).readFields()));
                });
    }

    /** 仅返回发布表单声明的填充值；目标对象及来源字段仍按当前行、字段授权裁剪。 */
    /** 按已发布表单的关联填充配置读取来源值，来源记录和目标字段都经过当前授权。 */
    public Map<String, Object> formFill(FormFills.Query query, long actor) {
        if (query == null || query.formId() == null) throw invalid("关联填充需要已发布表单");
        return transactions.tx(
                () -> {
                    DataCenter.Definition d =
                            contexts.definition(query.applicationId(), query.objectId(), actor);
                    ApplicationUi.Form form =
                            selections.selectionForm(
                                    query.applicationId(), query.objectId(), query.formId());
                    return formFill(
                            query,
                            d,
                            form,
                            actor,
                            objectId -> contexts.definition(query.applicationId(), objectId, actor),
                            relation ->
                                    reader.get(
                                                    query.applicationId(),
                                                    relation.targetObjectId(),
                                                    query.selectedId(),
                                                    actor)
                                            .record());
                });
    }

    /** 未发布表单预览固定使用请求中经服务端核验的对象版本；不要求也不读取 formId。 */
    public Map<String, Object> previewFormFill(FormFills.PreviewQuery request, long actor) {
        if (request == null || request.query() == null || request.form() == null)
            throw invalid("关联带入预览请求不能为空");
        FormFills.Query query = request.query();
        applications.requireDesigner(query.applicationId(), actor);
        ApplicationCenter.Definition normalized =
                applications.normalize(
                        new ApplicationCenter.Definition(request.objects(), List.of()));
        com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions definitions =
                new com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions();
        normalized
                .objects()
                .forEach(
                        ref ->
                                definitions.put(
                                        ref.objectId(),
                                        objects.getVersion(ref.objectId(), ref.versionNo())
                                                .definition()));
        // 草稿没引用、但因关联而隐式可读的对象也进定义表；预览的主对象仍只认显式引用。
        impliedObjects.complete(definitions);
        var d = definitions.explicit(query.objectId());
        if (d == null) throw invalid("关联带入对象不属于当前草稿引用");
        if (!d.objectId().equals(request.form().objectId())) throw invalid("表单不属于当前对象");
        DetailForms.validateLayout(request.form(), d);
        validatePreviewFill(request.form(), d, definitions, query.detailId());
        return transactions.tx(
                () ->
                        formFill(
                                query,
                                d,
                                request.form(),
                                actor,
                                definitions::get,
                                relation ->
                                        previewFillSource(
                                                query.applicationId(),
                                                definitions.get(relation.targetObjectId()),
                                                query.selectedId(),
                                                actor,
                                                definitions)));
    }

    private void validatePreviewFill(
            ApplicationUi.Form form,
            DataCenter.Definition d,
            Map<String, DataCenter.Definition> definitions,
            String detailId) {
        if (detailId == null) {
            FormFillBindings.validate(form, d, definitions);
            return;
        }
        DataCenter.Detail detail =
                d.details().stream()
                        .filter(t -> t.id().equals(detailId))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联带入明细不存在"));
        ApplicationUi.Form detailForm = DetailForms.form(form, detailId);
        if (detailForm == null) throw invalid("当前明细未配置关联填充");
        FormFillBindings.validate(detailForm, DetailForms.definition(d, detail), definitions);
    }

    private Row previewFillSource(
            String app,
            DataCenter.Definition source,
            String selectedId,
            long actor,
            Map<String, DataCenter.Definition> definitions) {
        if (source == null) throw invalid("关联带入来源对象不属于当前草稿引用");
        ApplicationRecords.Row selected =
                persistence.authorizedRead(
                        schemas.main(source),
                        selectedId,
                        actor,
                        false,
                        policy.access(app, source, actor),
                        ApplicationActionEnum.READ);
        return calculations
                .enrich(
                        app,
                        source,
                        summaries.enrich(source, List.of(selected)),
                        actor,
                        definitions)
                .getFirst();
    }

    private Map<String, Object> formFill(
            FormFills.Query query,
            DataCenter.Definition d,
            ApplicationUi.Form rootForm,
            long actor,
            java.util.function.Function<String, DataCenter.Definition> definitionReader,
            java.util.function.Function<DataCenter.Relation, Row> sourceReader) {
        ApplicationUi.Form form = rootForm;
        DataCenter.Definition targetDefinition = d;
        if (query.detailId() != null) {
            form = DetailForms.form(form, query.detailId());
            if (form == null) throw invalid("当前明细未配置关联填充");
            DataCenter.Detail detail =
                    d.details().stream()
                            .filter(item -> Objects.equals(item.id(), query.detailId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("关联带入明细不存在"));
            targetDefinition = DetailForms.definition(d, detail);
        }
        Map<String, DataCenter.FieldOptions> fillOptions = targetDefinition.fieldOptions();
        List<Map.Entry<String, ApplicationUi.FieldPresentation>> bindings =
                SelectionFields.presentations(form.nodes()).entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue() != null
                                                && e.getValue().fill() != null
                                                && !FieldRules.hasValueRule(
                                                        fillOptions.get(e.getKey()))
                                                && Objects.equals(
                                                        e.getValue().fill().sourceFieldId(),
                                                        query.sourceFieldId()))
                        .toList();
        if (bindings.isEmpty()) throw invalid("当前表单未配置此关联填充");
        ApplicationRuntimePolicy.Access access = policy.access(query.applicationId(), d, actor);
        ApplicationAuthorization.Capabilities caps =
                query.recordId() == null
                        ? access.forRow(Long.toString(actor))
                        : persistence
                                .authorizedRead(
                                        schemas.main(d),
                                        query.recordId(),
                                        actor,
                                        false,
                                        access,
                                        ApplicationActionEnum.READ)
                                .permissions();
        if (query.detailId() == null
                ? !caps.readFields().contains(query.sourceFieldId())
                : !caps.readDetails().contains(query.detailId())
                        || !caps.writeDetails().contains(query.detailId()))
            throw invalid("无权读取关联填充来源");
        if (query.selectedId() == null || query.selectedId().isBlank()) return Map.of();
        DataCenter.Relation relation =
                d.relations().stream()
                        .filter(
                                r ->
                                        Objects.equals(r.fieldId(), query.sourceFieldId())
                                                && Objects.equals(
                                                        r.sourceDetailId(), query.detailId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联填充来源不存在"));
        ApplicationRecords.Row selected = sourceReader.apply(relation);
        DataCenter.Definition sourceDefinition = definitionReader.apply(relation.targetObjectId());
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ApplicationUi.FieldPresentation> binding : bindings) {
            String id = binding.getKey(), source = binding.getValue().fill().valueFieldId();
            if (query.detailId() == null
                    && (!caps.readFields().contains(id) || !caps.writeFields().contains(id)))
                continue;
            if (!selected.permissions().readFields().contains(source)) throw invalid("无权读取关联填充字段");
            Object value = selected.values().get(source);
            FieldDefinition targetField =
                    targetDefinition.fields().stream()
                            .filter(field -> Objects.equals(field.id(), id))
                            .findFirst()
                            .orElseThrow(() -> invalid("关联带入目标字段不存在"));
            if (FieldTypeEnum.SELECT.matches(targetField.type())) {
                FieldDefinition sourceField =
                        sourceDefinition.fields().stream()
                                .filter(field -> Objects.equals(field.id(), source))
                                .findFirst()
                                .orElseThrow(() -> invalid("关联带入来源字段不存在"));
                DataCenter.FieldOptions targetOptions =
                        targetDefinition
                                .fieldOptions()
                                .getOrDefault(id, DataCenter.FieldOptions.defaults());
                DataCenter.FieldOptions sourceOptions =
                        sourceDefinition
                                .fieldOptions()
                                .getOrDefault(source, DataCenter.FieldOptions.defaults());
                if (!SelectionCompatibility.compatible(
                        targetField, targetOptions, sourceField, sourceOptions))
                    throw invalid("关联带入单选来源与目标必须使用同一字典或一致的选项编码和标签");
                selectionCatalog.validateFillValue(sourceField, sourceOptions, value);
                selectionCatalog.validateFillValue(targetField, targetOptions, value);
            }
            result.put(id, value);
        }
        return result;
    }

    /** 来源只能从授权应用所固定的对象版本取得。明细字段也校验其所属明细权限。 */
    /** 在表单与记录上下文中读取候选，保留固定范围、引用约束和当前选择权限。 */
    public SelectionFields.Result selection(SelectionFields.Query query, long actor) {
        return selection(query, actor, null, null);
    }

    /** 草稿预览只读；来源由服务端核验引用快照，权限仍使用实时应用授权与对象共享上限。 */
    public SelectionFields.Result previewSelection(
            SelectionFields.PreviewQuery request, long actor) {
        if (request == null || request.query() == null) throw invalid("预览请求不能为空");
        SelectionFields.Query query = request.query();
        applications.requireDesigner(query.applicationId(), actor);
        ApplicationCenter.Definition normalized =
                applications.normalize(
                        new ApplicationCenter.Definition(request.objects(), List.of()));
        com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions definitions =
                new com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions();
        normalized
                .objects()
                .forEach(
                        ref ->
                                definitions.put(
                                        ref.objectId(),
                                        objects.getVersion(ref.objectId(), ref.versionNo())
                                                .definition()));
        // 草稿没引用、但因关联而隐式可读的对象也进定义表；预览的主对象仍只认显式引用。
        impliedObjects.complete(definitions);
        var d = definitions.explicit(query.objectId());
        if (d == null) throw invalid("选择字段对象不属于当前草稿引用");
        if (request.form() != null) {
            if (!d.objectId().equals(request.form().objectId())) throw invalid("表单不属于当前对象");
            DetailForms.validateLayout(request.form(), d);
            SelectionFields.validatePresentations(request.form(), d, definitions);
            FormDependencies.validate(request.form(), d);
            if (query.detailId() != null) {
                DataCenter.Detail detail =
                        d.details().stream()
                                .filter(item -> Objects.equals(item.id(), query.detailId()))
                                .findFirst()
                                .orElseThrow(() -> invalid("联动明细不存在"));
                ApplicationUi.Form rowForm = DetailForms.selectionForm(request.form(), detail.id());
                if (rowForm != null)
                    SelectionFields.validatePresentations(
                            rowForm, DetailForms.selectionDefinition(d, detail), definitions);
            }
        }
        SelectionFields.Result result = selection(query, actor, definitions, request.form());
        if (request.validate()) {
            List<String> selected = query.selected() == null ? List.<String>of() : query.selected();
            List<FieldDefinition> fields =
                    query.detailId() == null
                            ? BusinessFields.fields(d)
                            : d.details().stream()
                                    .filter(detail -> detail.id().equals(query.detailId()))
                                    .findFirst()
                                    .orElseThrow()
                                    .fields();
            FieldDefinition field =
                    fields.stream()
                            .filter(f -> f.id().equals(query.fieldId()))
                            .findFirst()
                            .orElseThrow();
            if (Boolean.TRUE.equals(field.required()) && selected.isEmpty()
                    || !SelectionFields.multiple(field) && selected.size() > 1
                    || new HashSet<>(selected).size() != selected.size())
                throw invalid("选择数量或必填约束不满足：" + field.name());
            if (result.selected().stream()
                    .filter(o -> !o.disabled() && !o.unavailable())
                    .map(SelectionFields.Option::value)
                    .toList()
                    .containsAll(selected)) return result;
            throw invalid("选择值不存在、已停用、无权限或超出预览范围");
        }
        return result;
    }

    SelectionFields.Result selection(
            SelectionFields.Query query,
            long actor,
            Map<String, DataCenter.Definition> preview,
            ApplicationUi.Form previewForm) {
        if (query == null
                || query.pageNo() < 1
                || query.pageNo() > 10000
                || query.pageSize() < 1
                || query.pageSize() > 100
                || query.search() != null && query.search().length() > 128
                || query.selected() != null && query.selected().size() > 500)
            throw invalid("选择查询范围无效");
        // 挑取值的来源对象按本应用固定版本解析（设计预览按核验过的草稿引用）。
        return selectionCatalog.inApplication(
                query.applicationId(),
                preview,
                () ->
                        transactions.tx(
                                () -> {
                                    var d =
                                            preview == null
                                                    ? contexts.definition(
                                                            query.applicationId(),
                                                            query.objectId(),
                                                            actor)
                                                    : preview.get(query.objectId());
                                    var access = policy.access(query.applicationId(), d, actor);
                                    var caps =
                                            query.recordId() == null
                                                    ? access.forRow(Long.toString(actor))
                                                    : persistence
                                                            .authorizedRead(
                                                                    schemas.main(d),
                                                                    query.recordId(),
                                                                    actor,
                                                                    false,
                                                                    access,
                                                                    ApplicationActionEnum.READ)
                                                            .permissions();
                                    List<FieldDefinition> fields = BusinessFields.fields(d);
                                    Map<String, DataCenter.FieldOptions> options = d.fieldOptions();
                                    if (query.detailId() != null) {
                                        var detail =
                                                d.details().stream()
                                                        .filter(
                                                                t ->
                                                                        t.id().equals(
                                                                                                query
                                                                                                        .detailId())
                                                                                && caps.readDetails()
                                                                                        .contains(
                                                                                                t
                                                                                                        .id()))
                                                        .findFirst()
                                                        .orElseThrow(() -> invalid("没有此明细的查看权限"));
                                        fields = detail.fields();
                                        options = detail.fieldOptions();
                                        if (query.detailRecordId() != null) {
                                            if (query.recordId() == null)
                                                throw invalid("已有明细必须指定所属主记录");
                                            persistence.read(
                                                    schemas.detail(d, detail),
                                                    query.detailRecordId(),
                                                    query.recordId(),
                                                    actor,
                                                    false);
                                        }
                                    } else {
                                        var logicalRelation =
                                                BusinessFields.relation(d, query.fieldId());
                                        if (BusinessFields.multiple(logicalRelation)) {
                                            if (!(query.recordId() == null
                                                            ? access.queryRelations()
                                                            : caps.readRelations())
                                                    .contains(logicalRelation.id()))
                                                throw invalid("没有此关系的查看权限");
                                        } else if (!(query.recordId() == null
                                                        ? access.queryFields()
                                                        : caps.readFields())
                                                .contains(query.fieldId()))
                                            throw invalid("没有此字段的查看权限");
                                    }
                                    var field =
                                            fields.stream()
                                                    .filter(f -> f.id().equals(query.fieldId()))
                                                    .findFirst()
                                                    .orElseThrow(() -> invalid("字段不属于当前对象或明细"));
                                    var option =
                                            options.getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults());
                                    var relation = BusinessFields.relation(d, field.id());
                                    if (relation != null
                                            && !Objects.equals(
                                                    relation.sourceDetailId(), query.detailId()))
                                        throw invalid("引用来源与当前明细不一致");
                                    var form =
                                            preview == null
                                                    ? selections.selectionForm(
                                                            query.applicationId(),
                                                            query.objectId(),
                                                            query.formId())
                                                    : previewForm;
                                    var presentation =
                                            selections.selectionPresentation(
                                                    query.detailId() == null
                                                            ? form
                                                            : DetailForms.form(
                                                                    form, query.detailId()),
                                                    field.id());
                                    if (presentation != null
                                            && presentation.linkFieldId() != null
                                            && !caps.readFields()
                                                    .contains(presentation.linkFieldId())
                                            && !(query.detailId() != null
                                                    && fields.stream()
                                                            .anyMatch(
                                                                    f ->
                                                                            Objects.equals(
                                                                                    f.id(),
                                                                                    presentation
                                                                                            .linkFieldId()))))
                                        throw invalid("无权读取联动来源字段");
                                    List<SelectionFields.Option> all;
                                    long total;
                                    boolean tree = false;
                                    List<SelectionFields.Option> selected = new ArrayList<>();
                                    ReferenceScope ruleScope = null;
                                    String ruleState = null, ruleMessage = null;
                                    List<String> pendingFields = null;
                                    if (relation != null) {
                                        // 关系目标不必被应用引用：没引用但因关联而隐式可读的用最新发布版。
                                        var target =
                                                preview == null
                                                        ? contexts.readable(
                                                                query.applicationId(),
                                                                relation.targetObjectId(),
                                                                actor)
                                                        : preview.get(relation.targetObjectId());
                                        if (target == null) throw invalid("应用未引用关系目标对象版本");
                                        // 候选、已选回显、联动匹配核对都只用这一份引用访问，不按成员在目标对象上的授权另算。
                                        ApplicationRuntimePolicy.Access targetAccess =
                                                policy.referenceAccess(
                                                        query.applicationId(),
                                                        d,
                                                        relation,
                                                        target,
                                                        actor,
                                                        presentation == null
                                                                        || presentation
                                                                                        .linkTargetFieldId()
                                                                                == null
                                                                ? Set.of()
                                                                : Set.of(
                                                                        presentation
                                                                                .linkTargetFieldId()),
                                                        com.richuang.os.nocode.application.service
                                                                .sharing.ImpliedObjects.impliedIn(
                                                                preview,
                                                                relation.targetObjectId()));
                                        var view =
                                                presentation == null
                                                                || presentation.viewId() == null
                                                        ? null
                                                        : selections.selectionView(
                                                                selectionViewResources(
                                                                        query, preview),
                                                                presentation.viewId(),
                                                                relation.targetObjectId());
                                        // 对象引用筛选：依赖的当前字段没值时候选恒为空，不退化成全量；已选值照常回显。
                                        var ruleContext =
                                                new RuleContext(
                                                        query.applicationId(),
                                                        d,
                                                        actor,
                                                        preview,
                                                        null);
                                        ruleScope =
                                                fieldRules.referenceScope(
                                                        ruleContext,
                                                        query.detailId(),
                                                        relation,
                                                        query.formValues() == null
                                                                ? Map.of()
                                                                : query.formValues());
                                        String labelFieldId =
                                                FieldRuleLabels.labelFieldId(d, relation);
                                        if (FieldRuleStateEnum.APPLIED.matches(ruleScope.state())) {
                                            var page =
                                                    queryPage(
                                                            new Query(
                                                                    query.applicationId(),
                                                                    relation.targetObjectId(),
                                                                    query.pageNo(),
                                                                    query.pageSize(),
                                                                    query.search(),
                                                                    selections.linkedEqual(
                                                                            presentation,
                                                                            query.formValues()),
                                                                    null,
                                                                    false),
                                                            actor,
                                                            ApplicationActionEnum.READ,
                                                            preview == null ? null : target,
                                                            view,
                                                            ruleScope,
                                                            new ReferenceRead(
                                                                    target,
                                                                    targetAccess,
                                                                    fieldRules
                                                                            .referenceFilterFields(
                                                                                    ruleContext,
                                                                                    relation)));
                                            all =
                                                    page.getList().stream()
                                                            .map(
                                                                    r ->
                                                                            new SelectionFields
                                                                                    .Option(
                                                                                    r.id(),
                                                                                    FieldRuleLabels
                                                                                            .label(
                                                                                                    target,
                                                                                                    r
                                                                                                            .values(),
                                                                                                    labelFieldId),
                                                                                    r.id(),
                                                                                    null,
                                                                                    null,
                                                                                    false,
                                                                                    false))
                                                            .toList();
                                            total = page.getTotal();
                                        } else {
                                            all = List.of();
                                            total = 0;
                                            ruleState = ruleScope.state();
                                            ruleMessage = ruleScope.message();
                                            pendingFields = ruleScope.pendingFields();
                                        }
                                        var resolved =
                                                selections.referenceOptions(
                                                        query.applicationId(),
                                                        target,
                                                        query.selected() == null
                                                                ? List.of()
                                                                : query.selected(),
                                                        actor,
                                                        null,
                                                        null,
                                                        null,
                                                        labelFieldId,
                                                        targetAccess);
                                        // 不满足对象引用筛选的已选记录仍回显名称，但标记为不可重新选入。
                                        if (FieldRuleStateEnum.APPLIED.matches(ruleScope.state())
                                                && (ruleScope.conditions() != null
                                                        || ruleScope.hasRecordKey())
                                                && !resolved.isEmpty()) {
                                            var inScope =
                                                    fieldRules.idsWithinScope(
                                                            ruleContext,
                                                            relation,
                                                            ruleScope,
                                                            resolved.keySet());
                                            for (var entry : resolved.entrySet())
                                                if (!inScope.contains(entry.getKey())) {
                                                    var outside = entry.getValue();
                                                    entry.setValue(
                                                            new SelectionFields.Option(
                                                                    outside.value(),
                                                                    outside.label(),
                                                                    outside.code(),
                                                                    outside.parentValue(),
                                                                    outside.path(),
                                                                    true,
                                                                    outside.unavailable()));
                                                }
                                        }
                                        // 超出视图固定范围的已选记录仍返回名称，但标记为不可重新选入。
                                        if (view != null
                                                && query.selected() != null
                                                && !query.selected().isEmpty()) {
                                            var inRange =
                                                    selections.referenceOptions(
                                                            query.applicationId(),
                                                            target,
                                                            query.selected(),
                                                            actor,
                                                            view,
                                                            null,
                                                            null,
                                                            null,
                                                            targetAccess);
                                            for (var entry : resolved.entrySet())
                                                if (!inRange.containsKey(entry.getKey())) {
                                                    var outside = entry.getValue();
                                                    entry.setValue(
                                                            new SelectionFields.Option(
                                                                    outside.value(),
                                                                    outside.label(),
                                                                    outside.code(),
                                                                    outside.parentValue(),
                                                                    outside.path(),
                                                                    true,
                                                                    outside.unavailable()));
                                                }
                                        }
                                        if (presentation != null
                                                && presentation.linkTargetFieldId() != null) {
                                            var equal =
                                                    selections.linkedEqual(
                                                            presentation, query.formValues());
                                            for (var entry : resolved.entrySet()) {
                                                var row =
                                                        persistence.authorizedRead(
                                                                schemas.main(target),
                                                                entry.getKey(),
                                                                actor,
                                                                false,
                                                                targetAccess,
                                                                ApplicationActionEnum.READ);
                                                if (!row.permissions()
                                                        .readFields()
                                                        .contains(presentation.linkTargetFieldId()))
                                                    throw invalid("无权查询关联目标筛选字段");
                                                if (!Objects.equals(
                                                        SelectionCatalog.ids(
                                                                row.values()
                                                                        .get(
                                                                                presentation
                                                                                        .linkTargetFieldId())),
                                                        SelectionCatalog.ids(
                                                                equal.get(
                                                                        presentation
                                                                                .linkTargetFieldId())))) {
                                                    var label = entry.getValue();
                                                    entry.setValue(
                                                            new SelectionFields.Option(
                                                                    label.value(),
                                                                    label.label(),
                                                                    label.code(),
                                                                    label.parentValue(),
                                                                    label.path(),
                                                                    true,
                                                                    label.unavailable()));
                                                }
                                            }
                                        }
                                        for (String id :
                                                query.selected() == null
                                                        ? List.<String>of()
                                                        : query.selected())
                                            selected.add(
                                                    resolved.getOrDefault(
                                                            id,
                                                            new SelectionFields.Option(
                                                                    id,
                                                                    "已失效或无权限的引用",
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    true,
                                                                    true)));
                                    } else {
                                        var source = SelectionFields.source(field, option);
                                        if (source == null) throw invalid("字段不是选择字段");
                                        if (SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(
                                                source.kind())) {
                                            var picked =
                                                    selectionCatalog.objectFieldOptions(source);
                                            if (!FieldRuleStateEnum.APPLIED.matches(
                                                    picked.state())) {
                                                ruleState = picked.state();
                                                ruleMessage = picked.message();
                                                pendingFields = List.of();
                                            }
                                        }
                                        var userPage =
                                                selectionCatalog.userPage(
                                                        field,
                                                        option,
                                                        query.search(),
                                                        query.pageNo(),
                                                        query.pageSize());
                                        var selectedIds =
                                                query.selected() == null
                                                        ? List.<String>of()
                                                        : query.selected();
                                        var labels =
                                                selectionCatalog.selectedOptions(
                                                        field, option, selectedIds);
                                        all =
                                                userPage == null
                                                        ? selectionCatalog.scope(
                                                                selectionCatalog.options(
                                                                        field, option),
                                                                presentation,
                                                                query.formValues())
                                                        : userPage.getList();
                                        var selectable =
                                                all.stream()
                                                        .map(SelectionFields.Option::value)
                                                        .collect(
                                                                java.util.stream.Collectors
                                                                        .toSet());
                                        for (String id : selectedIds) {
                                            var label =
                                                    labels.stream()
                                                            .filter(o -> o.value().equals(id))
                                                            .findFirst()
                                                            .orElse(
                                                                    new SelectionFields.Option(
                                                                            id,
                                                                            "已失效或超出可见范围",
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            true,
                                                                            true));
                                            selected.add(
                                                    new SelectionFields.Option(
                                                            label.value(),
                                                            label.label(),
                                                            label.code(),
                                                            label.parentValue(),
                                                            label.path(),
                                                            label.disabled()
                                                                    || userPage == null
                                                                            && !selectable.contains(
                                                                                    id),
                                                            label.unavailable()));
                                        }
                                        tree =
                                                SelectionSourceEnum.DIRECTORY.matches(source.kind())
                                                        && Set.of(
                                                                        FieldTypeEnum.ORGANIZATION
                                                                                .getCode(),
                                                                        FieldTypeEnum.DEPARTMENT
                                                                                .getCode())
                                                                .contains(
                                                                        Objects.toString(
                                                                                source.directory(),
                                                                                ""));
                                        String search =
                                                Objects.toString(query.search(), "")
                                                        .trim()
                                                        .toLowerCase(Locale.ROOT);
                                        if (userPage == null)
                                            all =
                                                    all.stream()
                                                            .filter(
                                                                    o ->
                                                                            !o.disabled()
                                                                                    || selections
                                                                                            .treeDirectory(
                                                                                                    source))
                                                            .filter(
                                                                    o ->
                                                                            search.isEmpty()
                                                                                    || (o.label()
                                                                                                    + " "
                                                                                                    + o
                                                                                                            .code()
                                                                                                    + " "
                                                                                                    + o
                                                                                                            .path())
                                                                                            .toLowerCase(
                                                                                                    Locale
                                                                                                            .ROOT)
                                                                                            .contains(
                                                                                                    search))
                                                            .toList();
                                        total = userPage == null ? all.size() : userPage.getTotal();
                                        if (!tree && userPage == null)
                                            all =
                                                    all.stream()
                                                            .skip(
                                                                    (long) (query.pageNo() - 1)
                                                                            * query.pageSize())
                                                            .limit(query.pageSize())
                                                            .toList();
                                    }
                                    var defaultValue =
                                            selectionCatalog.evaluateDefault(
                                                    field,
                                                    option,
                                                    presentation,
                                                    query.formValues(),
                                                    actor);
                                    boolean initialize =
                                            (query.recordId() == null
                                                            || query.detailId() != null
                                                                    && Boolean.TRUE.equals(
                                                                            query.creating())
                                                                    && query.detailRecordId()
                                                                            == null)
                                                    && (query.detailId() == null
                                                            ? caps.writeFields()
                                                                    .contains(field.id())
                                                            : caps.writeDetails()
                                                                    .contains(query.detailId()));
                                    return new SelectionFields.Result(
                                            all,
                                            total,
                                            selected,
                                            tree,
                                            initialize ? defaultValue.value() : null,
                                            initialize ? defaultValue.warning() : null,
                                            ruleState,
                                            ruleMessage,
                                            pendingFields);
                                }));
    }

    /** 限定视图在运行端取发布快照，在设计预览取当前草稿；两端都不接受客户端指定的视图。 */
    List<ApplicationCenter.Resource> selectionViewResources(
            SelectionFields.Query query, Map<String, DataCenter.Definition> preview) {
        return preview == null
                ? applications.published(query.applicationId()).definition().resources()
                : applications.get(query.applicationId()).draft().resources();
    }

    /** 分页读取可见记录，客户端条件与视图固定范围、授权条件共同生效。 */
    public PageResult<Row> page(Query request, long actor) {
        return page(request, actor, ApplicationActionEnum.READ);
    }

    /**
     * 数据视图的统计下钻分页：记录集 = report-details 同一条件构造的结果 ∩ 视图自身条件（固定范围、搜索、筛选、上下文、授权）。 drillTotal
     * 只按下钻条件计数，供前端提示视图自身条件排除了多少条。
     */
    public ApplicationRecords.DrillPage drillPage(Query request, long actor) {
        if (request == null || request.reportDrill() == null) throw invalid("缺少统计下钻参数");
        if (request.viewId() == null) throw invalid("统计下钻必须在已发布数据视图中查看");
        return transactions.tx(
                () -> {
                    var page = page(request, actor, ApplicationActionEnum.READ);
                    // 明细粒度的统计只进按同一明细逐行显示的数据视图：计数与列表用同一判定。
                    var view =
                            dataViews
                                    .getObject()
                                    .view(
                                            request.applicationId(),
                                            request.objectId(),
                                            request.viewId(),
                                            actor);
                    long drillTotal =
                            reports.getObject()
                                    .drillTotal(
                                            request.reportDrill(),
                                            request.applicationId(),
                                            request.objectId(),
                                            com.richuang.os.nocode.runtime.service.report
                                                    .ApplicationReportService.viewDetailId(view),
                                            actor);
                    return new ApplicationRecords.DrillPage(
                            page.getList(), page.getTotal(), drillTotal);
                });
    }

    /** 看板范围计数和视图分页必须在同一授权回调内完成，不能另开自由看板查询或读取主键集合。 */
    public ApplicationRecords.DashboardDrillPage dashboardDrillPage(Query request, long actor) {
        if (request == null || request.dashboardDrill() == null) throw invalid("缺少应用看板业务明细参数");
        return dashboards
                .getObject()
                .withBusinessDetails(
                        request,
                        actor,
                        source -> {
                            PageResult<Row> page =
                                    viewPage(request, actor, ApplicationActionEnum.READ, source);
                            long drillTotal = reportRecords.count(source);
                            ApplicationUi.View view = publishedView(request, actor);
                            boolean differentGrain =
                                    view.composition() != null
                                            && ViewGrainEnum.DETAIL.matches(
                                                    view.composition().grain());
                            return new ApplicationRecords.DashboardDrillPage(
                                    page.getList(), page.getTotal(), drillTotal, differentGrain);
                        });
    }

    PageResult<Row> page(Query request, long actor, ApplicationActionEnum operation) {
        // 必须先进入看板授权链再查询视图，避免先锁应用后反序获取设计锁。
        if (request != null && request.dashboardDrill() != null) {
            if (operation != ApplicationActionEnum.READ)
                throw invalid("应用看板业务明细仅支持受控查看，写入仍通过原业务权限入口");
            return dashboards
                    .getObject()
                    .withBusinessDetails(
                            request, actor, source -> viewPage(request, actor, operation, source));
        }
        if (request != null && request.viewId() != null) return viewPage(request, actor, operation);
        if (request != null && request.context() != null) throw invalid("关联查询必须指定已发布视图");
        return queryPage(request, actor, operation);
    }

    PageResult<Row> queryPage(Query request, long actor, ApplicationActionEnum operation) {
        return queryPage(request, actor, operation, null);
    }

    PageResult<Row> queryPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition) {
        return queryPage(request, actor, operation, previewDefinition, null);
    }

    PageResult<Row> queryPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView) {
        return queryPage(request, actor, operation, previewDefinition, fixedView, null);
    }

    /** 候选分页：对象引用筛选与限定视图、授权条件按 AND 叠加，只能更严。 */
    PageResult<Row> queryPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReferenceScope ruleScope) {
        return queryPage(
                request, actor, operation, previewDefinition, fixedView, ruleScope, null, null);
    }

    PageResult<Row> queryPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReferenceScope ruleScope,
            ReferenceRead reference) {
        return queryPage(
                request,
                actor,
                operation,
                previewDefinition,
                fixedView,
                ruleScope,
                reference,
                null);
    }

    /**
     * 引用候选用的读取上下文：目标对象的定义（显式引用取固定版本，隐式可读取最新发布版）、这个人在引用上下文里的访问权，以及引用筛选条件可用的目标字段（系统取数口径）。
     * 给了它，候选分页整条路径只用这份访问权，不再按成员在目标对象上的授权另算。
     */
    record ReferenceRead(
            DataCenter.Definition definition,
            ApplicationRuntimePolicy.Access access,
            Set<String> ruleFields) {}

    /** 同上；reference 非空时目标定义与访问权都由调用方给出（引用候选）。 */
    PageResult<Row> queryPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReferenceScope ruleScope,
            ReferenceRead reference,
            ReportStatement dashboardSource) {
        // 引用上下文里这个人对目标对象没有任何查看权（未授权或授权已撤销）：候选为空，不报错。
        if (reference != null && !reference.access().any(operation))
            return new PageResult<>(List.of(), 0L);
        return transactions.tx(
                () -> {
                    var plan =
                            queryPlan(
                                    request,
                                    actor,
                                    operation,
                                    previewDefinition,
                                    fixedView,
                                    ruleScope,
                                    reference,
                                    dashboardSource);
                    return new PageResult<>(
                            queryRows(
                                    request.applicationId(),
                                    plan,
                                    records.rows(plan.statement()),
                                    actor,
                                    previewDefinition != null),
                            records.count(plan.statement()));
                });
    }

    QueryPlan queryPlan(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView) {
        return queryPlan(request, actor, operation, previewDefinition, fixedView, null);
    }

    QueryPlan queryPlan(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReferenceScope ruleScope) {
        return queryPlan(
                request, actor, operation, previewDefinition, fixedView, ruleScope, null, null);
    }

    QueryPlan queryPlan(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReferenceScope ruleScope,
            ReferenceRead reference,
            ReportStatement dashboardSource) {
        if (request == null
                || request.pageNo() < 1
                || request.pageNo() > 100000
                || request.pageSize() < 1
                || request.pageSize() > 100) throw invalid("分页参数无效");
        if (request.dashboardDrill() != null && dashboardSource == null)
            throw invalid("应用看板业务明细缺少已授权查询范围");
        DataCenter.Definition d =
                previewDefinition != null
                        ? previewDefinition
                        : reference != null
                                ? reference.definition()
                                : contexts.definition(
                                        request.applicationId(), request.objectId(), actor);
        ApplicationRuntimePolicy.Access access =
                reference == null
                        ? policy.access(request.applicationId(), d, actor)
                        : reference.access();
        if (!access.any(operation)) throw invalid("没有此对象的操作权限");
        orderedStates.requireReady(d, d.fields().stream().map(FieldDefinition::id).toList());
        RecordContextResolver.PageRecord context =
                contexts.context(
                        request.applicationId(),
                        request.objectId(),
                        request.viewId(),
                        request.context(),
                        actor,
                        false);
        HashSet<String> queryFields = new HashSet<>(access.queryFields());
        queryFields.removeIf(
                id ->
                        com.richuang.os.nocode.metadata.service.formula.Calculations.live(
                                d.fieldOptions().get(id)));
        RuntimeSchema.Table t = schemas.main(d);
        RecordStatement base = t.statement(null, null, Long.toString(actor), false);
        Map<String, Object> filters = new LinkedHashMap<>();
        LinkedHashMap<FieldDefinition, List<String>> selectionFilters =
                new LinkedHashMap<FieldDefinition, List<String>>();
        LinkedHashMap<DataCenter.Relation, List<String>> relationFilters =
                new LinkedHashMap<DataCenter.Relation, List<String>>();
        if (request.equal() != null) {
            if (request.equal().size() > 20) throw invalid("筛选条件最多 20 项");
            for (Map.Entry<String, Object> e : request.equal().entrySet()) {
                DataCenter.Relation relation = BusinessFields.relation(d, e.getKey());
                if (BusinessFields.multiple(relation)) {
                    if (!access.queryRelations().contains(relation.id()))
                        throw invalid("需要全部可见记录的关系查看权限才能筛选此关系");
                    List<String> ids = SelectionCatalog.ids(e.getValue());
                    if (ids.isEmpty() || ids.size() > 100 || e.getValue() instanceof Map<?, ?>)
                        throw invalid("请选择最多 100 个筛选记录");
                    relationFilters.put(relation, ids);
                    continue;
                }
                if (!queryFields.contains(e.getKey())) throw invalid("不能按无权查看的字段筛选");
                FieldDefinition f =
                        t.fields().stream()
                                .filter(
                                        v ->
                                                v.id().equals(e.getKey())
                                                        && t.columns().containsKey(v.id()))
                                .findFirst()
                                .orElseThrow(() -> invalid("筛选字段不存在"));
                if (e.getValue() instanceof Map<?, ?> scope
                        && Boolean.TRUE.equals(scope.get("includeDescendants"))) {
                    DataCenter.FieldOptions option =
                            t.options().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
                    SelectionFields.Source source = SelectionFields.source(f, option);
                    if (!selections.treeDirectory(source)
                            || !scope.keySet().equals(Set.of("value", "includeDescendants"))
                            || scope.get("value") == null
                            || scope.get("value") instanceof Map<?, ?>
                            || SelectionCatalog.ids(scope.get("value")).size() > 100)
                        throw invalid("下级范围筛选仅支持组织或部门");
                    List<SelectionFields.Option> all = selectionCatalog.options(f, option);
                    SelectionFields.Source root =
                            new SelectionFields.Source(
                                    source.kind(),
                                    source.directory(),
                                    null,
                                    SelectionCatalog.ids(scope.get("value")),
                                    true,
                                    null,
                                    null);
                    selectionFilters.put(
                            f,
                            SelectionCatalog.restrict(all, root).stream()
                                    .map(SelectionFields.Option::value)
                                    .toList());
                    continue;
                }
                filters.put(
                        t.column(f),
                        values.convert(
                                f,
                                t.options()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                                e.getValue()));
            }
        }
        String search =
                request.search() == null || request.search().isBlank()
                        ? null
                        : request.search().trim();
        if (search != null && search.length() > 200) throw invalid("搜索内容最多 200 字符");
        if (search != null)
            search = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String sort = request.sortFieldId() == null ? null : t.columns().get(request.sortFieldId());
        if (request.sortFieldId() != null && sort == null) throw invalid("排序字段不存在");
        if (request.sortFieldId() != null && !queryFields.contains(request.sortFieldId()))
            throw invalid("不能按无权查看的字段排序");
        List<String> searchable =
                base.textFields().stream()
                        .filter(
                                c ->
                                        base.fields().entrySet().stream()
                                                .anyMatch(
                                                        e ->
                                                                e.getValue().equals(c)
                                                                        && queryFields.contains(
                                                                                e.getKey())))
                        .toList();
        if (search != null && searchable.isEmpty()) throw invalid("没有可搜索字段");
        RecordStatement sql =
                new RecordStatement(
                        base.schema(),
                        base.table(),
                        base.keyColumn(),
                        base.fields(),
                        searchable,
                        base.numericFields(),
                        base.deletedColumn(),
                        null,
                        null,
                        null,
                        access.creatorFilter(operation),
                        search,
                        new ArrayList<>(filters.keySet()),
                        persistence.write(filters),
                        sort,
                        request.descending(),
                        request.pageSize(),
                        (request.pageNo() - 1) * request.pageSize(),
                        List.of(),
                        "{}",
                        base.actor(),
                        false);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where =
                conditionCompiler.compile(request.conditions(), t, queryFields);
        where = fixedViewConditions.append(fixedView, d, t, where, actor);
        where = reportDrill(where, request, fixedView, d, t, actor);
        where =
                selections.appendRuleScope(
                        where,
                        ruleScope,
                        t,
                        reference == null ? queryFields : reference.ruleFields());
        if (!selectionFilters.isEmpty()) {
            if (where == null) {
                where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object>();
                where.setParamAlias("dynamicQuery");
            }
            for (Map.Entry<FieldDefinition, List<String>> entry : selectionFilters.entrySet()) {
                String column = sqlFragments.column("t", t.column(entry.getKey()), false);
                if (entry.getValue().isEmpty()) where.apply(sqlFragments.alwaysFalse());
                else if (SelectionFields.multiple(entry.getKey()))
                    where.apply(
                            sqlFragments.containsAny(column, true),
                            persistence.write(entry.getValue()));
                else
                    where.in(
                            sqlFragments.column("t", t.column(entry.getKey()), true),
                            entry.getValue());
            }
        }
        for (Map.Entry<DataCenter.Relation, List<String>> entry : relationFilters.entrySet()) {
            if (where == null) {
                where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object>();
                where.setParamAlias("dynamicQuery");
            }
            RelationStatement relationTable =
                    relations.statement(d, entry.getKey(), null, null, actor);
            where.apply(
                    sqlFragments.relationFilter(
                            relationTable.schema(), relationTable.table(), base.keyColumn()),
                    persistence.write(entry.getValue()));
        }
        RecordConditions.validateReferences(request.conditions(), d);
        sql = sql.conditions(policy.conditions(access, t, where, operation, "t"));
        if (dashboardSource != null) {
            if (fixedView == null
                    || previewDefinition != null
                    || !sql.schema().equals(dashboardSource.base().schema())
                    || !sql.table().equals(dashboardSource.base().table())
                    || !sql.keyColumn().equals(dashboardSource.base().keyColumn()))
                throw invalid("应用看板业务明细与当前视图物理来源不一致");
            sql =
                    sql.dashboardScope(
                            sqlFragments.reportRootScope(dashboardSource, sql.keyColumn()));
        }
        if (context != null) sql = contexts.scope(sql, context, access, actor);
        return new QueryPlan(d, access, sql);
    }

    /** 统计下钻只能收窄：主键必须属于 report-details 同一条件构造的记录集，且只在已发布数据视图内生效。 */
    private com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> reportDrill(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where,
            Query request,
            ApplicationUi.View fixedView,
            DataCenter.Definition d,
            RuntimeSchema.Table t,
            long actor) {
        if (request.reportDrill() == null) return where;
        if (fixedView == null) throw invalid("统计下钻必须在已发布数据视图中查看");
        var scope =
                reports.getObject()
                        .drillScope(
                                request.reportDrill(),
                                request.applicationId(),
                                d.objectId(),
                                com.richuang.os.nocode.runtime.service.report
                                        .ApplicationReportService.viewDetailId(fixedView),
                                actor);
        if (where == null) {
            where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
        }
        if (scope.keys().isEmpty()) where.apply(sqlFragments.alwaysFalse());
        else where.apply(sqlFragments.keyIn(t.key().name()), persistence.write(scope.keys()));
        return where;
    }

    List<Row> queryRows(String app, QueryPlan plan, List<String> raw, long actor, boolean preview) {
        List<ApplicationRecords.Row> rows =
                raw.stream().map(value -> persistence.visible(value, plan.access())).toList();
        if (preview) return rows;
        DataCenter.Definition d = plan.definition();
        return selections.selectionLabels(
                app,
                d,
                calculations.enrich(
                        app,
                        d,
                        summaries.enrich(
                                d,
                                rows.stream()
                                        .map(row -> reader.processPermissions(d.objectId(), row))
                                        .toList()),
                        actor),
                actor);
    }

    /** 发布视图及其粒度只能从当前应用快照解析，查询参数不能自行声明。 */
    private ApplicationUi.View publishedView(Query request, long actor) {
        policy.requireEntry(request.applicationId(), actor);
        ApplicationCenter.Resource resource =
                applications.published(request.applicationId()).definition().resources().stream()
                        .filter(
                                r ->
                                        r.id().equals(request.viewId())
                                                && ApplicationResourceKindEnum.VIEW.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("已发布视图不存在"));
        ApplicationUi.View view =
                resourceValidator.decode(resource.config(), ApplicationUi.View.class);
        if (!view.objectId().equals(request.objectId())) throw invalid("视图不属于此对象");
        return view;
    }

    /** 固定视图条件从发布快照读取，客户端只能叠加筛选，不能覆盖视图条件。 */
    PageResult<Row> viewPage(Query request, long actor, ApplicationActionEnum operation) {
        return viewPage(request, actor, operation, null);
    }

    private PageResult<Row> viewPage(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            ReportStatement dashboardSource) {
        return transactions.tx(
                () -> {
                    ApplicationUi.View view = publishedView(request, actor);
                    if (view.composition() != null)
                        return dataViews
                                .getObject()
                                .page(request, view, actor, operation, dashboardSource);
                    Map<String, Object> equal =
                            request.equal() == null ? Map.of() : request.equal();
                    PageResult<ApplicationRecords.Row> page =
                            queryPage(
                                    new Query(
                                            request.applicationId(),
                                            view.objectId(),
                                            request.pageNo(),
                                            request.pageSize(),
                                            request.search(),
                                            equal,
                                            request.sortFieldId() == null
                                                    ? view.sortFieldId()
                                                    : request.sortFieldId(),
                                            request.sortFieldId() == null
                                                    ? view.descending()
                                                    : request.descending(),
                                            null,
                                            request.context(),
                                            request.conditions(),
                                            List.of(),
                                            request.reportDrill(),
                                            request.dashboardDrill()),
                                    actor,
                                    operation,
                                    null,
                                    view,
                                    null,
                                    null,
                                    dashboardSource);
                    return new PageResult<>(
                            page.getList().stream()
                                    .map(
                                            row -> {
                                                Map<String, Object> selected =
                                                        new LinkedHashMap<>();
                                                view.fieldIds()
                                                        .forEach(
                                                                id -> {
                                                                    if (row.values()
                                                                            .containsKey(id))
                                                                        selected.put(
                                                                                id,
                                                                                row.values()
                                                                                        .get(id));
                                                                });
                                                return new Row(
                                                        row.id(),
                                                        row.revision(),
                                                        selected,
                                                        row.permissions(),
                                                        row.displayValues().entrySet().stream()
                                                                .filter(
                                                                        e ->
                                                                                selected
                                                                                        .containsKey(
                                                                                                e
                                                                                                        .getKey()))
                                                                .collect(
                                                                        java.util.stream.Collectors
                                                                                .toMap(
                                                                                        Map.Entry
                                                                                                ::getKey,
                                                                                        Map.Entry
                                                                                                ::getValue)));
                                            })
                                    .toList(),
                            page.getTotal());
                });
    }

    /** 导出必须单独授权，查询范围在 SQL 内按 EXPORT 约束，不能先查 READ 再在客户端筛选。 */
    /** 按导出操作权限读取记录；不借用普通列表读权绕过导出范围。 */
    public List<Row> export(Query request, long actor) {
        if (request != null && request.dashboardDrill() != null)
            throw invalid("应用看板业务明细暂不支持业务列表导出");
        return transactions.tx(
                () -> {
                    List<Row> output = new ArrayList<>();
                    for (int number = 1; number <= 50; number++) {
                        PageResult<ApplicationRecords.Row> page =
                                page(
                                        new Query(
                                                request.applicationId(),
                                                request.objectId(),
                                                number,
                                                100,
                                                request.search(),
                                                request.equal(),
                                                request.sortFieldId(),
                                                request.descending(),
                                                request.viewId(),
                                                request.context(),
                                                request.conditions(),
                                                request.childFilters()),
                                        actor,
                                        ApplicationActionEnum.EXPORT);
                        if (page.getTotal() > 5000) throw invalid("一次最多导出 5000 条记录，请缩小筛选范围");
                        output.addAll(page.getList());
                        if (page.getList().size() < 100 || output.size() >= page.getTotal()) break;
                    }
                    return output;
                });
    }

    public Model importModel(String app, String object, long actor) {
        return transactions.tx(
                () -> {
                    DataCenter.Definition d = contexts.definition(app, object, actor);
                    ApplicationRuntimePolicy.Access access = policy.access(app, d, actor);
                    ApplicationAuthorization.Capabilities allowed =
                            access.require(Long.toString(actor), ApplicationActionEnum.IMPORT);
                    ApplicationRecords.Model model = model(app, object, actor);
                    if (!model.writable()
                            || !model.permissions()
                                    .actions()
                                    .contains(ApplicationActionEnum.CREATE.getCode()))
                        throw invalid("导入需要新增记录权限和可写数据表");
                    Set<String> fields = new HashSet<>(allowed.writeFields());
                    fields.retainAll(model.permissions().writeFields());
                    ApplicationAuthorization.Capabilities caps =
                            new ApplicationAuthorization.Capabilities(
                                    model.permissions().actions(),
                                    model.permissions().readFields(),
                                    fields,
                                    Set.of(),
                                    Set.of());
                    return new Model(
                            model.object(),
                            model.writable(),
                            model.generatedKey(),
                            model.keyFieldId(),
                            model.keyType(),
                            Map.of(),
                            caps,
                            model.managedFieldIds(),
                            model.orderedStates());
                });
    }

    /** 导入转换复用授权后的对象字段来源，不接受客户端传入目录或字典配置。 */
    public Object selectionImportValue(
            String app, String object, FieldDefinition field, Object value, long actor) {
        ApplicationRecords.Model model = importModel(app, object, actor);
        if (!model.permissions().writeFields().contains(field.id())) throw invalid("没有字段导入权限");
        return selectionCatalog.inApplication(
                app,
                null,
                () ->
                        selectionCatalog.importValue(
                                field,
                                model.object()
                                        .fieldOptions()
                                        .getOrDefault(
                                                field.id(), DataCenter.FieldOptions.defaults()),
                                value));
    }
}
