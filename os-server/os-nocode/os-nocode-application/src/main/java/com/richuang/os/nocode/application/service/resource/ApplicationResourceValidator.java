package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationCenter.ObjectReference;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 发布前验证完整资源图；设计器隐藏设置不能替代服务端配置白名单。 */
@Component
public class ApplicationResourceValidator {
    @Resource private ApplicationPageValidator pageValidator;
    @Resource private ApplicationNavigation navigation;
    @Resource private ApplicationResourceContext resourceContext;
    @Resource private ApplicationViewValidator viewValidator;
    @Resource private DataViewDefinitions dataViews;
    @Resource private ObjectMapper json;
    @Resource private DataObjectApi objects;
    @Resource private DraftValidator validator;
    @Resource private ApplicationProcessDefinition processes;
    @Resource private ApplicationReportValidator reports;
    @Resource private TaskEntryConfigValidator taskEntries;
    @Resource private ApplicationAutomationValidator automations;

    @Resource
    private com.richuang.os.nocode.application.service.sharing.ImpliedObjects impliedObjects;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<SelectionTargetValidator>
            selectionTargets;

    @PostConstruct
    void initialize() {
        json =
                json.copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /** 旧入口仅供存量任务按原权限继续执行；整份草稿保存不得新增、改写或误删隐藏资源。 */
    public void validateLegacyTaskEntries(
            ApplicationCenter.Definition previous, ApplicationCenter.Definition next) {
        Map<String, ApplicationCenter.Resource> legacy = new LinkedHashMap<>();
        if (previous != null && previous.resources() != null)
            for (ApplicationCenter.Resource resource : previous.resources())
                if (ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind()))
                    legacy.put(resource.id(), resource);
        Set<String> retained = new HashSet<>();
        if (next != null && next.resources() != null)
            for (ApplicationCenter.Resource resource : next.resources()) {
                if (resource == null) continue;
                ApplicationCenter.Resource stored = legacy.get(resource.id());
                if (stored != null) {
                    if (!json.valueToTree(stored).equals(json.valueToTree(resource)))
                        throw invalid("旧版任务入口已退役，不能修改存量入口配置；请在任务中心配置业务数据");
                    retained.add(resource.id());
                } else if (ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind())) {
                    throw invalid("旧版任务入口已退役，不能新增任务入口；请在任务中心配置业务数据");
                }
            }
        if (!retained.containsAll(legacy.keySet()))
            throw invalid("不能删除存量任务入口，请保留原配置，避免影响已有任务和业务申请");
    }

    public List<ApplicationCenter.Resource> normalize(
            List<ObjectReference> refs, List<ApplicationCenter.Resource> input) {
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        for (ObjectReference ref : refs)
            definitions.put(
                    ref.objectId(),
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition());
        // 解析「关系目标、规则来源」用的定义表：显式引用之外补上因关联而隐式可读的对象（最新发布版）。
        // 资源归属（视图、表单、统计、任务入口、关联录入绑定哪个对象）仍只认 definitions 里的显式引用。
        com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions readable =
                new com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Definitions();
        readable.putAll(definitions);
        impliedObjects.complete(readable);
        // 表单默认值、统计固定筛选里的挑取值按本次引用的对象版本解析（应用设计时口径）。
        var targets = selectionTargets.getIfAvailable();
        return targets == null
                ? normalizeResources(input, definitions, readable)
                : targets.inDefinitions(
                        readable, () -> normalizeResources(input, definitions, readable));
    }

    private List<ApplicationCenter.Resource> normalizeResources(
            List<ApplicationCenter.Resource> input,
            Map<String, DataCenter.Definition> definitions,
            Map<String, DataCenter.Definition> readable) {
        Map<String, ApplicationCenter.Resource> resources = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>();
        for (ApplicationCenter.Resource r : input) {
            if (r == null || r.config() == null) throw invalid("资源及配置不能为空");
            resourceContext.identifier(r.id());
            validator.code(r.code(), "资源编码", 64, false);
            validator.text(r.name(), "资源名称", 160);
            ApplicationResourceKindEnum.fromCode(r.kind());
            if (resources.putIfAbsent(r.id(), r) != null || !codes.add(r.code()))
                throw invalid("资源 ID 或编码重复");
        }
        resourceContext.defaultForms(resources.values());
        navigation.validate(resources);
        List<ApplicationCenter.Resource> result = new ArrayList<>();
        for (ApplicationCenter.Resource r : input) {
            Object normalized;
            switch (ApplicationResourceKindEnum.fromCode(r.kind())) {
                case REPORT_DASHBOARD ->
                        normalized =
                                ApplicationDashboardValidator.normalize(
                                        resourceContext.decode(
                                                r.config(), ApplicationDashboards.Config.class),
                                        definitions,
                                        resources);
                case AUTOMATION ->
                        normalized =
                                automations.validate(
                                        resourceContext.decode(
                                                r.config(), ApplicationAutomations.Config.class),
                                        definitions);
                case TASK_ENTRY ->
                        normalized =
                                taskEntries.validate(
                                        resourceContext.decode(
                                                r.config(), TaskEntries.Config.class),
                                        definitions,
                                        resources);
                case REPORT -> {
                    ApplicationReports.Config report =
                            reports.normalize(
                                    resourceContext.decode(
                                            r.config(), ApplicationReports.Config.class),
                                    definitions);
                    // 固定等值的键按这张统计的粒度解析：明细粒度下可以是所选明细的字段。
                    DataCenter.Definition reportRoot =
                            resourceContext.object(definitions, report.objectId());
                    DataCenter.Detail reportDetail = reports.grainDetail(report, reportRoot);
                    for (var entry : report.equal().entrySet()) {
                        var resolved =
                                reports.resolve(
                                        resourceContext.object(definitions, report.objectId()),
                                        entry.getKey(),
                                        definitions,
                                        reportDetail);
                        var option =
                                resolved.owner()
                                        .fieldOptions()
                                        .getOrDefault(
                                                resolved.field().id(),
                                                DataCenter.FieldOptions.defaults());
                        SelectionFields.Source source =
                                SelectionFields.source(resolved.field(), option);
                        if (entry.getValue() != null
                                && source != null
                                && !SelectionSourceEnum.OBJECT_RELATION.matches(source.kind())) {
                            if (!(entry.getValue() instanceof String))
                                throw invalid("统计固定筛选应选择一个选项：" + resolved.field().name());
                            try {
                                selectionTargets
                                        .getObject()
                                        .validatePresentation(
                                                resolved.field(),
                                                option,
                                                new SelectionFields.Presentation(
                                                        null,
                                                        List.of(),
                                                        false,
                                                        null,
                                                        null,
                                                        entry.getValue()));
                            } catch (
                                    com.richuang.os.framework.common.exception.ServiceException e) {
                                throw invalid("统计固定筛选值不存在、已停用或超出范围：" + resolved.field().name());
                            }
                        }
                    }
                    if (report.detailViewId() != null) {
                        ApplicationUi.View view =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        report.detailViewId(),
                                                        ApplicationResourceKindEnum.VIEW)
                                                .config(),
                                        ApplicationUi.View.class);
                        if (!Objects.equals(view.objectId(), report.objectId()))
                            throw invalid("统计明细视图必须绑定同一对象");
                        // 明细粒度：下钻视图须按同一明细逐行显示；主记录粒度不判（存量行为不变）。
                        reports.requireDrillView(reportDetail, view);
                    }
                    // 多个数据来源：每个附加来源的下钻明细视图同样必须绑定该来源的对象（固定等值只属于来源 1，上面已判）。
                    if (report.multiSource())
                        for (ApplicationReports.Source source : report.extraSources())
                            if (source.detailViewId() != null) {
                                ApplicationUi.View view =
                                        resourceContext.decode(
                                                ReportMultiSourceNormalizer.prefixed(
                                                                source.name(),
                                                                () ->
                                                                        resourceContext.resource(
                                                                                resources,
                                                                                source
                                                                                        .detailViewId(),
                                                                                ApplicationResourceKindEnum
                                                                                        .VIEW))
                                                        .config(),
                                                ApplicationUi.View.class);
                                if (!Objects.equals(view.objectId(), source.objectId()))
                                    throw invalid(
                                            ReportSourceMessages.prefix(
                                                    source.name(), "统计明细视图必须绑定同一对象"));
                                // 明细粒度的附加来源（R6 衔接，与来源 1 同一判定）：下钻视图须按该来源所按的同一明细逐行显示。
                                DataCenter.Detail sourceDetail =
                                        reports.grainDetail(
                                                ReportMultiSourceNormalizer.project(
                                                        report, source, report.metrics()),
                                                resourceContext.object(
                                                        definitions, source.objectId()));
                                ReportMultiSourceNormalizer.prefixed(
                                        source.name(),
                                        () -> {
                                            reports.requireDrillView(sourceDetail, view);
                                            return null;
                                        });
                            }
                    normalized = report;
                }
                case VIEW -> {
                    ApplicationUi.View v =
                            resourceContext.decode(r.config(), ApplicationUi.View.class);
                    DataCenter.Definition d = resourceContext.object(definitions, v.objectId());
                    if (v.query() != null) {
                        v.query().validate(d);
                        if (!v.query().defaults().isEmpty()
                                && (v.list() == null
                                        || !v.list()
                                                .queryFieldIds()
                                                .containsAll(v.query().defaults().keySet())))
                            throw invalid("默认查询须属于常用查询字段");
                    }
                    Set<String> liveFields =
                            d.fieldOptions().entrySet().stream()
                                    .filter(
                                            e ->
                                                    com.richuang.os.nocode.metadata.service.formula
                                                            .Calculations.live(e.getValue()))
                                    .map(Map.Entry::getKey)
                                    .collect(java.util.stream.Collectors.toSet());
                    if (v.sortFieldId() != null && liveFields.contains(v.sortFieldId())
                            || v.equal() != null
                                    && !Collections.disjoint(liveFields, v.equal().keySet())
                            || v.list() != null
                                    && (!Collections.disjoint(liveFields, v.list().queryFieldIds())
                                            || v.list().advancedFieldIds() != null
                                                    && !Collections.disjoint(
                                                            liveFields,
                                                            v.list().advancedFieldIds())))
                        throw invalid("实时计算字段仅用于展示，暂不支持查询或排序");
                    DataViews.Composition composition =
                            dataViews.validate(v, definitions, new ArrayList<>(resources.values()));
                    LinkedHashMap<String, FieldDefinition> fields =
                            new LinkedHashMap<>(resourceContext.fields(d));
                    if (composition != null)
                        composition
                                .columns()
                                .forEach(c -> fields.put(c.id(), DataViewDefinitions.field(c)));
                    if (v.fieldIds() == null
                            || v.fieldIds().isEmpty()
                            || v.fieldIds().size() > 100
                            || new HashSet<>(v.fieldIds()).size() != v.fieldIds().size()
                            || !fields.keySet().containsAll(v.fieldIds()))
                        throw invalid("视图显示字段无效");
                    if (v.sortFieldId() != null
                            && (!fields.containsKey(v.sortFieldId())
                                    || BusinessFields.multiple(
                                            BusinessFields.relation(d, v.sortFieldId()))))
                        throw invalid("视图排序字段无效");
                    if (v.sortFieldId() != null
                            && FieldTypeEnum.SUMMARY.matches(fields.get(v.sortFieldId()).type()))
                        throw invalid("汇总字段首版用于展示，不能用作排序");
                    if (v.pageSize() < 1 || v.pageSize() > 100) throw invalid("视图每页数量应为 1 到 100");
                    Map<String, Object> equal =
                            v.equal() == null ? Map.<String, Object>of() : v.equal();
                    if (equal.size() > 20 || !fields.keySet().containsAll(equal.keySet()))
                        throw invalid("视图筛选字段无效");
                    if (equal.keySet().stream()
                            .anyMatch(id -> FieldTypeEnum.SUMMARY.matches(fields.get(id).type())))
                        throw invalid("汇总字段首版用于展示，不能用作筛选");
                    resourceContext.viewForm(resources.values(), v);
                    if (v.detailPageId() != null) {
                        ApplicationUi.Page detail =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        v.detailPageId(),
                                                        ApplicationResourceKindEnum.PAGE)
                                                .config(),
                                        ApplicationUi.Page.class);
                        if (!Objects.equals(detail.contextObjectId(), v.objectId()))
                            throw invalid("列表详情页面必须绑定同一当前对象");
                    }
                    Map<String, String> dictionaries =
                            v.filterDictionaries() == null
                                    ? Map.<String, String>of()
                                    : v.filterDictionaries();
                    if (dictionaries.size() > 20
                            || !fields.keySet().containsAll(dictionaries.keySet()))
                        throw invalid("筛选字典字段无效");
                    for (Map.Entry<String, String> binding : dictionaries.entrySet()) {
                        ApplicationBusiness.Dictionary dictionary =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        binding.getValue(),
                                                        ApplicationResourceKindEnum.DICTIONARY)
                                                .config(),
                                        ApplicationBusiness.Dictionary.class);
                        FieldDefinition field = fields.get(binding.getKey());
                        if (!Set.of(
                                        FieldTypeEnum.TEXT,
                                        FieldTypeEnum.SELECT,
                                        FieldTypeEnum.MULTI_SELECT)
                                .contains(FieldTypeEnum.fromCode(field.type())))
                            throw invalid("筛选字典只用于文本、单选或多选字段");
                        if ((FieldTypeEnum.SELECT.matches(field.type())
                                        || FieldTypeEnum.MULTI_SELECT.matches(field.type()))
                                && dictionary.items() != null) {
                            Set<String> declared =
                                    d
                                            .fieldOptions()
                                            .getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults())
                                            .options()
                                            .stream()
                                            .map(DataCenter.Option::code)
                                            .collect(java.util.stream.Collectors.toSet());
                            if (dictionary.items().stream()
                                    .anyMatch(i -> i == null || !declared.contains(i.code())))
                                throw invalid("筛选字典不能引入全局字段未声明的选项编码");
                        }
                    }
                    normalized =
                            new ApplicationUi.View(
                                    v.objectId(),
                                    List.copyOf(v.fieldIds()),
                                    equal,
                                    v.sortFieldId(),
                                    v.descending(),
                                    v.pageSize(),
                                    v.formId(),
                                    dictionaries,
                                    v.detailPageId(),
                                    viewValidator.viewInteraction(v, resources),
                                    viewValidator.viewList(v, fields),
                                    v.query(),
                                    composition);
                }
                case FORM -> {
                    ApplicationUi.Form form =
                            resourceContext.decode(r.config(), ApplicationUi.Form.class);
                    DataCenter.Definition d = resourceContext.object(definitions, form.objectId());
                    com.richuang.os.nocode.metadata.service.form.DetailForms.validateLayout(
                            form, d);
                    HashSet<String> fieldIds = new HashSet<String>();
                    pageValidator.nodes(
                            form.nodes(),
                            resources,
                            resourceContext.fields(d),
                            fieldIds,
                            true,
                            0,
                            new HashSet<>(),
                            "业务表单“" + r.name() + "”的主表");
                    SelectionFields.validatePresentations(
                            form, d, readable, "业务表单“" + r.name() + "”的主表");
                    resourceContext.validateSelectionViews(form, d, resources);
                    com.richuang.os.nocode.metadata.service.form.FormBehaviors.validate(form, d);
                    com.richuang.os.nocode.metadata.service.form.FormFillBindings.validate(
                            form, d, readable);
                    for (var entry : SelectionFields.presentations(form.nodes()).entrySet()) {
                        var presentation =
                                entry.getValue() == null ? null : entry.getValue().selection();
                        if (presentation == null || presentation.defaultValue() == null) continue;
                        FieldDefinition field =
                                BusinessFields.fields(d).stream()
                                        .filter(f -> f.id().equals(entry.getKey()))
                                        .findFirst()
                                        .orElseThrow();
                        if (BusinessFields.relation(d, field.id()) != null)
                            throw invalid("关联字段暂不支持固定表单默认值：" + field.name());
                        selectionTargets
                                .getObject()
                                .validatePresentation(
                                        field,
                                        d.fieldOptions()
                                                .getOrDefault(
                                                        field.id(),
                                                        DataCenter.FieldOptions.defaults()),
                                        presentation);
                    }
                    if (fieldIds.isEmpty()) throw invalid("表单至少包含一个对象字段");
                    List<String> details =
                            form.detailIds() == null ? List.<String>of() : form.detailIds();
                    HashSet<String> available = new HashSet<String>();
                    d.details().stream()
                            .filter(t -> MemberStateEnum.ACTIVE.matches(t.state()))
                            .forEach(t -> available.add(t.id()));
                    if (!available.containsAll(details)
                            || new HashSet<>(details).size() != details.size())
                        throw invalid("表单内部明细不存在或重复");
                    Map<String, List<ApplicationUi.Node>> detailNodes =
                            form.detailNodes() == null
                                    ? Map.<String, List<ApplicationUi.Node>>of()
                                    : form.detailNodes();
                    if (!details.containsAll(detailNodes.keySet())) throw invalid("明细字段配置必须属于当前表单");
                    for (DataCenter.Detail detail : d.details()) {
                        ApplicationUi.Form detailForm =
                                com.richuang.os.nocode.metadata.service.form.DetailForms.form(
                                        form, detail.id());
                        if (detailForm == null) continue;
                        DataCenter.Definition detailDefinition =
                                com.richuang.os.nocode.metadata.service.form.DetailForms.definition(
                                        d, detail);
                        HashSet<String> used = new HashSet<String>();
                        pageValidator.nodes(
                                detailForm.nodes(),
                                resources,
                                resourceContext.fields(detailDefinition),
                                used,
                                true,
                                0,
                                new HashSet<>(),
                                "业务表单“" + r.name() + "”的内部明细“" + detail.name() + "”");
                        if (used.isEmpty()) throw invalid("明细表单至少包含一个字段");
                        ApplicationUi.Form selectionForm =
                                com.richuang.os.nocode.metadata.service.form.DetailForms
                                        .selectionForm(form, detail.id());
                        DataCenter.Definition selectionDefinition =
                                com.richuang.os.nocode.metadata.service.form.DetailForms
                                        .selectionDefinition(d, detail);
                        SelectionFields.validatePresentations(
                                selectionForm,
                                selectionDefinition,
                                readable,
                                "业务表单“" + r.name() + "”的内部明细“" + detail.name() + "”");
                        resourceContext.validateSelectionViews(
                                selectionForm, selectionDefinition, resources);
                        com.richuang.os.nocode.metadata.service.form.FormBehaviors.validate(
                                detailForm, detailDefinition);
                        com.richuang.os.nocode.metadata.service.form.FormFillBindings.validate(
                                detailForm, detailDefinition, readable);
                        for (var field : detail.fields()) {
                            var option =
                                    detail.fieldOptions()
                                            .getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults());
                            if (Boolean.TRUE.equals(field.required())
                                    && !used.contains(field.id())
                                    && option.defaultValue() == null
                                    && !FieldTypeEnum.fromCode(field.type()).isComputed()
                                    && !FieldTypeEnum.AUTO_NUMBER.matches(field.type()))
                                throw invalid("明细表单缺少必填字段：" + field.name());
                        }
                    }
                    for (FieldDefinition f : resourceContext.fields(d).values()) {
                        DataCenter.FieldOptions options =
                                d.fieldOptions()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
                        FieldTypeEnum type = FieldTypeEnum.fromCode(f.type());
                        if (Boolean.TRUE.equals(f.required())
                                && (!BusinessFields.multiple(BusinessFields.relation(d, f.id()))
                                        || form.options() != null
                                                && Boolean.TRUE.equals(
                                                        form.options().relationLayout()))
                                && !com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns
                                        .NAMES
                                        .contains(Objects.toString(options.columnName(), f.code()))
                                && options.defaultValue() == null
                                // 关系字段的 generated 表示由平台建列，仍需由用户选择业务值。
                                && (!Boolean.TRUE.equals(options.generated())
                                        || d.relations().stream()
                                                .anyMatch(
                                                        r2 -> Objects.equals(r2.fieldId(), f.id())))
                                && type != FieldTypeEnum.FORMULA
                                && type != FieldTypeEnum.AUTO_NUMBER
                                && !fieldIds.contains(f.id())
                                && input.stream()
                                        .filter(
                                                r2 ->
                                                        ApplicationResourceKindEnum.NUMBER_RULE
                                                                .matches(r2.kind()))
                                        .map(
                                                r2 ->
                                                        resourceContext.decode(
                                                                r2.config(),
                                                                ApplicationBusiness.NumberRule
                                                                        .class))
                                        .noneMatch(
                                                n ->
                                                        Objects.equals(n.objectId(), d.objectId())
                                                                && Objects.equals(
                                                                        n.fieldId(), f.id())))
                            throw invalid("表单缺少必填字段：" + f.name());
                    }
                    List<RelatedForms.Binding> relatedForms =
                            form.relatedForms() == null
                                    ? List.<RelatedForms.Binding>of()
                                    : form.relatedForms();
                    if (relatedForms.size() > 10) throw invalid("每张表单最多配置 10 个关联录入区域");
                    HashSet<String> bindingIds = new HashSet<String>();
                    HashSet<String> bindingRelations = new HashSet<String>();
                    for (RelatedForms.Binding binding : relatedForms) {
                        if (binding == null
                                || binding.id() == null
                                || !bindingIds.add(binding.id())
                                || !bindingRelations.add(
                                        binding.sourceObjectId() + ":" + binding.relationId()))
                            throw invalid("关联录入区域不能重复");
                        DataCenter.Definition source =
                                resourceContext.object(definitions, binding.sourceObjectId());
                        DataCenter.Relation relation =
                                source.relations().stream()
                                        .filter(
                                                rel ->
                                                        Objects.equals(
                                                                rel.id(), binding.relationId()))
                                        .findFirst()
                                        .orElseThrow(() -> invalid("关联录入关系不存在"));
                        RelationDirectionEnum direction =
                                RelationDirectionEnum.fromCode(binding.direction());
                        boolean incoming = direction == RelationDirectionEnum.INCOMING;
                        if (relation.sourceDetailId() != null
                                || RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())
                                || (incoming
                                        ? !Objects.equals(relation.targetObjectId(), d.objectId())
                                        : !Objects.equals(source.objectId(), d.objectId())))
                            throw invalid("关联录入关系与主对象不匹配");
                        String target = incoming ? source.objectId() : relation.targetObjectId();
                        if (Objects.equals(target, d.objectId())) throw invalid("关联录入暂不支持对象自身递归");
                        ApplicationCenter.Resource targetResource = resources.get(binding.formId());
                        if (targetResource == null
                                || !ApplicationResourceKindEnum.FORM.matches(targetResource.kind()))
                            throw invalid("请选择关联对象的业务表单");
                        ApplicationUi.Form targetForm =
                                resourceContext.decode(
                                        targetResource.config(), ApplicationUi.Form.class);
                        if (!Objects.equals(targetForm.objectId(), target))
                            throw invalid("关联表单对象不匹配");
                        if (targetForm.relatedForms() != null
                                && !targetForm.relatedForms().isEmpty())
                            throw invalid("关联录入暂支持一层，请选择不含关联区的表单");
                    }
                    normalized =
                            new ApplicationUi.Form(
                                    form.objectId(),
                                    form.nodes(),
                                    List.copyOf(details),
                                    form.options(),
                                    detailNodes,
                                    relatedForms);
                    if (form.options() != null) {
                        FormLayoutEnum.fromCode(form.options().layout());
                        validator.text(form.options().submitText(), "表单提交按钮", 30);
                    }
                }
                case PAGE -> {
                    ApplicationUi.Page page =
                            resourceContext.decode(r.config(), ApplicationUi.Page.class);
                    if (page.protocolVersion() != null && page.protocolVersion() != 2)
                        throw invalid("不支持的页面协议版本");
                    if (page.contextObjectId() != null)
                        resourceContext.object(definitions, page.contextObjectId());
                    pageValidator.nodes(
                            page.nodes(),
                            resources,
                            Map.of(),
                            new HashSet<>(),
                            false,
                            0,
                            new HashSet<>(),
                            "业务页面“" + r.name() + "”");
                    pageValidator.pageContext(page, page.nodes(), resources, definitions);
                    pageValidator.pageActions(page, page.nodes(), resources, definitions);
                    pageValidator.reportFilters(page, resources, definitions);
                    normalized = page;
                }
                case MENU -> {
                    ApplicationUi.Menu menu =
                            resourceContext.decode(r.config(), ApplicationUi.Menu.class);
                    ApplicationCenter.Resource target =
                            resourceContext.resource(
                                    resources,
                                    menu.targetId(),
                                    ApplicationResourceKindEnum.PAGE,
                                    ApplicationResourceKindEnum.VIEW,
                                    ApplicationResourceKindEnum.REPORT_DASHBOARD);
                    // 旧 MENU 始终是可见入口；v2 隐藏且非首页时只保留配置身份，允许改为记录上下文看板。
                    // v2 显示入口及首页的独立性已经由 ApplicationNavigation 统一校验。
                    if (menu.navigationVersion() == null
                            && ApplicationResourceKindEnum.REPORT_DASHBOARD.matches(
                                    target.kind())) {
                        String contextObject =
                                resourceContext
                                        .decode(target.config(), ApplicationDashboards.Config.class)
                                        .contextObjectId();
                        if (contextObject != null && !contextObject.isBlank())
                            throw invalid("含记录上下文的仪表板需通过对应业务页面访问");
                    }
                    normalized = menu;
                }
                case DICTIONARY -> {
                    ApplicationBusiness.Dictionary dictionary =
                            resourceContext.decode(
                                    r.config(), ApplicationBusiness.Dictionary.class);
                    if (dictionary.items() == null
                            || dictionary.items().isEmpty()
                            || dictionary.items().size() > 500) throw invalid("应用字典需包含 1 至 500 项");
                    if (dictionary.sourceType() != null
                            && !dictionary.sourceType().matches("[A-Za-z0-9_:-]{1,100}"))
                        throw invalid("来源字典类型无效");
                    Set<String> values = new HashSet<>();
                    for (DataCenter.Option item : dictionary.items()) {
                        if (item == null) throw invalid("字典项不能为空");
                        validator.text(item.code(), "字典编码", 100);
                        validator.text(item.label(), "字典名称", 160);
                        if (!values.add(item.code())) throw invalid("字典编码不能重复");
                    }
                    normalized = dictionary;
                }
                case NUMBER_RULE -> {
                    ApplicationBusiness.NumberRule n =
                            resourceContext.decode(
                                    r.config(), ApplicationBusiness.NumberRule.class);
                    DataCenter.Definition d = resourceContext.object(definitions, n.objectId());
                    FieldDefinition field = resourceContext.fields(d).get(n.fieldId());
                    if (field == null
                            || !FieldTypeEnum.TEXT.matches(field.type())
                            || Boolean.TRUE.equals(
                                    d.fieldOptions()
                                            .getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults())
                                            .generated())) throw invalid("业务编号必须绑定可写文本字段");
                    boolean unique =
                            Boolean.TRUE.equals(field.unique())
                                    || Boolean.TRUE.equals(
                                            d.fieldOptions()
                                                    .getOrDefault(
                                                            field.id(),
                                                            DataCenter.FieldOptions.defaults())
                                                    .primaryKey())
                                    || d.indexes().stream()
                                            .anyMatch(
                                                    i ->
                                                            Boolean.TRUE.equals(i.unique())
                                                                    && i.fieldIds()
                                                                            .equals(
                                                                                    List.of(
                                                                                            field
                                                                                                    .id())));
                    if (!unique) throw invalid("业务编号字段必须在数据中心设置唯一约束");
                    NumberPeriodEnum period = NumberPeriodEnum.fromCode(n.period());
                    if (n.prefix() == null
                            || !n.prefix().matches("[\\p{L}\\p{N}_-]{0,32}")
                            || n.width() < 1
                            || n.width() > 12)
                        throw invalid("编号前缀最多 32 个文字/数字/下划线/横线，流水号位数为 1 至 12");
                    int dateLength =
                            switch (period) {
                                case NONE -> 0;
                                case YEAR -> 4;
                                case MONTH -> 6;
                                case DAY -> 8;
                            };
                    if (field.length() != null
                            && field.length() < n.prefix().length() + dateLength + n.width())
                        throw invalid("编号字段长度不足");
                    if (input.stream()
                                    .filter(
                                            r2 ->
                                                    ApplicationResourceKindEnum.NUMBER_RULE.matches(
                                                            r2.kind()))
                                    .map(
                                            r2 ->
                                                    resourceContext.decode(
                                                            r2.config(),
                                                            ApplicationBusiness.NumberRule.class))
                                    .filter(
                                            other ->
                                                    Objects.equals(other.objectId(), n.objectId())
                                                            && Objects.equals(
                                                                    other.fieldId(), n.fieldId()))
                                    .count()
                            > 1) throw invalid("同一字段只能有一个业务编号规则");
                    normalized = n;
                }
                case ACTION -> {
                    ApplicationBusiness.Action action =
                            resourceContext.decode(r.config(), ApplicationBusiness.Action.class);
                    DataCenter.Definition d =
                            resourceContext.object(definitions, action.objectId());
                    Map<String, FieldDefinition> available = resourceContext.fields(d);
                    available
                            .keySet()
                            .removeIf(
                                    id -> BusinessFields.multiple(BusinessFields.relation(d, id)));
                    BusinessActionKindEnum kind = BusinessActionKindEnum.fromCode(action.kind());
                    if (kind != BusinessActionKindEnum.CAPTURE_VALUES
                            && !action.captures().isEmpty()) throw invalid("仅留存计算结果动作可以配置留存字段");
                    if (kind == BusinessActionKindEnum.CAPTURE_VALUES) {
                        normalized = ApplicationCaptureRules.validate(action, d);
                    } else if (kind == BusinessActionKindEnum.START_PROCESS) {
                        processes.require(action.processDefinitionId());
                        if (action.values() != null && !action.values().isEmpty())
                            throw invalid("流程动作不能同时更新字段");
                        Map<String, String> variables =
                                action.variables() == null
                                        ? Map.<String, String>of()
                                        : action.variables();
                        if (variables.size() > 30) throw invalid("流程最多映射 30 个业务变量");
                        for (Map.Entry<String, String> entry : variables.entrySet()) {
                            if (!entry.getKey().matches("nc_[a-zA-Z][a-zA-Z0-9_]{0,59}")
                                    || !available.containsKey(entry.getValue()))
                                throw invalid("流程变量以 nc_ 开头并绑定有效业务字段");
                            if (FieldTypeEnum.fromCode(available.get(entry.getValue()).type())
                                    .isComputed()) throw invalid("流程变量暂不支持虚拟计算字段");
                        }
                        normalized =
                                new ApplicationBusiness.Action(
                                        action.objectId(),
                                        action.kind(),
                                        Map.of(),
                                        action.processDefinitionId(),
                                        variables);
                    } else {
                        if (action.values() == null
                                || action.values().isEmpty()
                                || action.values().size() > 20
                                || !available.keySet().containsAll(action.values().keySet()))
                            throw invalid("动作更新字段无效");
                        if (action.processDefinitionId() != null
                                || action.variables() != null && !action.variables().isEmpty())
                            throw invalid("字段更新动作不能包含流程配置");
                        for (String id : action.values().keySet()) {
                            if (FieldTypeEnum.fromCode(available.get(id).type()).isComputed()
                                    || FieldTypeEnum.AUTO_NUMBER.matches(available.get(id).type()))
                                throw invalid("动作不能修改系统计算字段");
                            if (resources.values().stream()
                                    .filter(
                                            x ->
                                                    ApplicationResourceKindEnum.NUMBER_RULE.matches(
                                                            x.kind()))
                                    .map(
                                            x ->
                                                    resourceContext.decode(
                                                            x.config(),
                                                            ApplicationBusiness.NumberRule.class))
                                    .anyMatch(
                                            n ->
                                                    Objects.equals(n.objectId(), action.objectId())
                                                            && Objects.equals(n.fieldId(), id)))
                                throw invalid("动作不能修改自动业务编号");
                        }
                        normalized =
                                new ApplicationBusiness.Action(
                                        action.objectId(),
                                        action.kind(),
                                        action.values(),
                                        null,
                                        Map.of());
                    }
                }
                default -> throw invalid("不支持的业务资源类型");
            }
            result.add(
                    new ApplicationCenter.Resource(
                            r.id(),
                            r.kind(),
                            r.code(),
                            r.name(),
                            json.convertValue(
                                    normalized, new TypeReference<Map<String, Object>>() {})));
        }
        automations.graph(
                result.stream()
                        .filter(r -> ApplicationResourceKindEnum.AUTOMATION.matches(r.kind()))
                        .map(r -> decode(r.config(), ApplicationAutomations.Config.class))
                        .toList());
        return List.copyOf(result);
    }

    public <T> T decode(Map<String, Object> config, Class<T> type) {
        return resourceContext.decode(config, type);
    }

    /** 保存完整草稿时与原快照比较，避免删除默认资源后静默退回通用布局。 */
    public void validateDefaultFormRemoval(
            ApplicationCenter.Definition before, ApplicationCenter.Definition after) {
        resourceContext.validateDefaultFormRemoval(before, after);
    }
}
