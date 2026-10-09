package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.dal.support.*;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleLabels;
import com.lingan.ucp.nocode.runtime.service.rules.ReferenceScope;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 选择值标签、默认值及表单联动范围；查询和写入复用同一规则。 */
@Component
public class RecordSelectionSupport {
    @Resource private RecordContextResolver contexts;
    @Resource private RecordPersistence persistence;
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ApplicationService applications;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator
            resourceValidator;

    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordRelations relations;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordMapper records;
    @Resource private FixedViewConditions fixedViewConditions;
    @Resource private RecordConditions conditionCompiler;

    ApplicationUi.Form selectionForm(String app, String object, String formId) {
        if (formId == null) return null;
        ApplicationCenter.Resource resource =
                applications.published(app).definition().resources().stream()
                        .filter(
                                r ->
                                        r.id().equals(formId)
                                                && ApplicationResourceKindEnum.FORM.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("已发布表单不存在"));
        ApplicationUi.Form form =
                resourceValidator.decode(resource.config(), ApplicationUi.Form.class);
        if (!form.objectId().equals(object)) throw invalid("表单不属于当前对象");
        return form;
    }

    SelectionFields.Presentation selectionPresentation(ApplicationUi.Form form, String fieldId) {
        if (form == null) return null;
        Map<String, ApplicationUi.FieldPresentation> nodes =
                SelectionFields.presentations(form.nodes());
        if (!nodes.containsKey(fieldId)) {
            if (fieldId.startsWith("relation_")
                    && (form.options() == null
                            || !Boolean.TRUE.equals(form.options().relationLayout()))) return null;
            throw invalid("选择字段不属于当前表单");
        }
        ApplicationUi.FieldPresentation p = nodes.get(fieldId);
        return p == null ? null : p.selection();
    }

    Map<String, Object> linkedEqual(SelectionFields.Presentation p, Map<String, Object> values) {
        if (p == null || p.linkTargetFieldId() == null) return Map.of();
        Object value = values == null ? null : values.get(p.linkFieldId());
        if (value == null || value instanceof Collection<?> || value instanceof Map<?, ?>)
            throw invalid("请先填写联动来源字段");
        return Map.of(p.linkTargetFieldId(), value);
    }

    /** 表单限定视图只解析资源快照中的视图，客户端不能改用其它视图绕过候选范围。 */
    ApplicationUi.View selectionView(
            List<ApplicationCenter.Resource> resources, String viewId, String objectId) {
        ApplicationCenter.Resource resource =
                resources.stream()
                        .filter(
                                r ->
                                        r.id().equals(viewId)
                                                && ApplicationResourceKindEnum.VIEW.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("限定视图不存在或已删除"));
        ApplicationUi.View view =
                resourceValidator.decode(resource.config(), ApplicationUi.View.class);
        if (!view.objectId().equals(objectId)) throw invalid("限定视图必须与引用目标对象一致");
        return view;
    }

    /** 一次查询一组引用；目标记录和标题字段均按目标对象权限裁剪。业务文件目录命名复用同一解析。 */
    public Map<String, SelectionFields.Option> referenceOptions(
            String app, String object, Collection<String> ids, long actor) {
        return referenceOptions(app, contexts.definition(app, object, actor), ids, actor);
    }

    Map<String, SelectionFields.Option> referenceOptions(
            String app, DataCenter.Definition d, Collection<String> ids, long actor) {
        return referenceOptions(app, d, ids, actor, null);
    }

    /** 限定视图只收紧候选范围，不改变读取权限和已选回显。 */
    Map<String, SelectionFields.Option> referenceOptions(
            String app,
            DataCenter.Definition d,
            Collection<String> ids,
            long actor,
            ApplicationUi.View view) {
        return referenceOptions(app, d, ids, actor, view, null, null, null);
    }

    /**
     * 引用筛选与限定视图按 AND 叠加，只能更严；显示名字段可读且非空时作为标签，否则回落标题模板。
     *
     * @param scope 对象引用筛选的编译结果；null 表示不追加
     * @param allowed 目标对象上可参与条件的字段（已剔除 LIVE 计算字段）
     * @param labelFieldId 关系字段配置的显示名字段；null 使用标题模板
     */
    Map<String, SelectionFields.Option> referenceOptions(
            String app,
            DataCenter.Definition d,
            Collection<String> ids,
            long actor,
            ApplicationUi.View view,
            ReferenceScope scope,
            Set<String> allowed,
            String labelFieldId) {
        return referenceOptions(app, d, ids, actor, view, scope, allowed, labelFieldId, null);
    }

    /**
     * 同上；supplied 非空时整条查询只用调用方给的访问权（引用上下文里的 {@link ApplicationRuntimePolicy#referenceAccess}，
     * 或规则求值的系统只读授权），不再按成员在目标对象上的授权另算一遍。
     */
    Map<String, SelectionFields.Option> referenceOptions(
            String app,
            DataCenter.Definition d,
            Collection<String> ids,
            long actor,
            ApplicationUi.View view,
            ReferenceScope scope,
            Set<String> allowed,
            String labelFieldId,
            ApplicationRuntimePolicy.Access supplied) {
        if (ids.isEmpty()) return Map.of();
        var access = supplied == null ? policy.access(app, d, actor) : supplied;
        if (!access.any(ApplicationActionEnum.READ)) return Map.of();
        RuntimeSchema.Table t = schemas.main(d);
        RecordStatement base = t.statement(null, null, Long.toString(actor), false);
        Map<String, SelectionFields.Option> result = new LinkedHashMap<>();
        ArrayList<String> keys = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int start = 0; start < keys.size(); start += 100) {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object>();
            where.setParamAlias("dynamicQuery");
            where.in(
                    sqlFragments.column("t", base.keyColumn(), true),
                    keys.subList(start, Math.min(start + 100, keys.size())));
            where = fixedViewConditions.append(view, d, t, where, actor);
            where = appendRuleScope(where, scope, t, allowed);
            var sql =
                    new RecordStatement(
                                    base.schema(),
                                    base.table(),
                                    base.keyColumn(),
                                    base.fields(),
                                    base.textFields(),
                                    base.numericFields(),
                                    base.deletedColumn(),
                                    null,
                                    null,
                                    null,
                                    access.creatorFilter(ApplicationActionEnum.READ),
                                    null,
                                    List.of(),
                                    "{}",
                                    null,
                                    false,
                                    100,
                                    0,
                                    List.of(),
                                    "{}",
                                    base.actor(),
                                    false)
                            .conditions(
                                    policy.conditions(
                                            access, t, where, ApplicationActionEnum.READ, "t"));
            for (String raw : records.rows(sql)) {
                ApplicationRecords.Row row = persistence.visible(raw, access);
                result.put(
                        row.id(),
                        new SelectionFields.Option(
                                row.id(),
                                FieldRuleLabels.label(d, row.values(), labelFieldId),
                                row.id(),
                                null,
                                null,
                                false,
                                false));
            }
        }
        return result;
    }

    /** 引用筛选追加到候选或取数条件上；非 APPLIED 的范围一律不命中，不退化成全量。条件按对象规则的空值口径执行（appendRule）。 */
    com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> appendRuleScope(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where,
            ReferenceScope scope,
            RuntimeSchema.Table t,
            Set<String> allowed) {
        if (scope == null) return where;
        if (where == null) {
            where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
        }
        if (!FieldRuleStateEnum.APPLIED.matches(scope.state())) {
            where.apply(sqlFragments.alwaysFalse());
            return where;
        }
        if (scope.conditions() != null)
            where = conditionCompiler.appendRule(where, scope.conditions(), t, allowed);
        if (scope.hasRecordKey()) {
            var keys = scope.recordKeys() == null ? List.<String>of() : scope.recordKeys();
            if (keys.isEmpty()) where.apply(sqlFragments.alwaysFalse());
            else where.in(sqlFragments.column("t", t.key().name(), true), keys);
        }
        return where;
    }

    /** 规则只读取应用固定的对象版本；对象未加入应用或已停用时抛出，由规则服务转为状态码。 */
    public DataCenter.Definition ruleDefinition(String app, String object, long actor) {
        return contexts.definition(app, object, actor);
    }

    /** 规则的来源对象、引用筛选的目标对象：显式引用取固定版本；应用没引用但因关联而隐式可读的取最新发布版；都不是则抛出。 */
    public DataCenter.Definition ruleSource(String app, String object, long actor) {
        return contexts.readable(app, object, actor);
    }

    /** 新建时按当前操作者能力；已有记录按该记录的实际授权（不可读则抛出）。 */
    public ApplicationAuthorization.Capabilities ruleCapabilities(
            DataCenter.Definition d,
            ApplicationRuntimePolicy.Access access,
            String recordId,
            long actor) {
        return recordId == null
                ? access.forRow(Long.toString(actor))
                : persistence
                        .authorizedRead(
                                schemas.main(d),
                                recordId,
                                actor,
                                false,
                                access,
                                ApplicationActionEnum.READ)
                        .permissions();
    }

    /** 规则取数：来源对象按当前操作者 READ 行范围与引用筛选查询，create_time、主键升序；没有 create_time 列时只按主键。行值按行授权裁剪。 */
    public List<Row> ruleRows(
            String app,
            DataCenter.Definition d,
            ApplicationRuntimePolicy.Access access,
            ReferenceScope scope,
            Set<String> allowed,
            int limit,
            long actor) {
        var t = schemas.main(d);
        var base = t.statement(null, null, Long.toString(actor), false);
        String sort =
                t.physical().columns().stream().anyMatch(c -> "create_time".equals(c.name()))
                        ? "create_time"
                        : null;
        var sql =
                new RecordStatement(
                                base.schema(),
                                base.table(),
                                base.keyColumn(),
                                base.fields(),
                                base.textFields(),
                                base.numericFields(),
                                base.deletedColumn(),
                                null,
                                null,
                                null,
                                access.creatorFilter(ApplicationActionEnum.READ),
                                null,
                                List.of(),
                                "{}",
                                sort,
                                false,
                                limit,
                                0,
                                List.of(),
                                "{}",
                                base.actor(),
                                false)
                        .conditions(
                                policy.conditions(
                                        access,
                                        t,
                                        appendRuleScope(null, scope, t, allowed),
                                        ApplicationActionEnum.READ,
                                        "t"));
        return records.rows(sql).stream().map(raw -> persistence.visible(raw, access)).toList();
    }

    /** 在引用筛选与目标 READ 范围内，返回 ids 中存在的那些；每 100 个 ID 一次查询。 */
    public Set<String> ruleIdsWithin(
            String app,
            DataCenter.Definition target,
            Collection<String> ids,
            ReferenceScope scope,
            Set<String> allowed,
            long actor) {
        return ruleIdsWithin(app, target, ids, scope, allowed, actor, null);
    }

    /** 同上；access 非空时按它判断（规则求值的系统只读授权），不看成员在目标对象上的授权。 */
    public Set<String> ruleIdsWithin(
            String app,
            DataCenter.Definition target,
            Collection<String> ids,
            ReferenceScope scope,
            Set<String> allowed,
            long actor,
            ApplicationRuntimePolicy.Access access) {
        return new LinkedHashSet<>(
                referenceOptions(app, target, ids, actor, null, scope, allowed, null, access)
                        .keySet());
    }

    Map<String, SelectionFields.Option> referenceLabels(
            String app,
            DataCenter.Definition owner,
            DataCenter.Relation relation,
            DataCenter.Definition target,
            Collection<String> ids,
            long actor) {
        return referenceOptions(
                app,
                target,
                ids,
                actor,
                null,
                null,
                null,
                FieldRuleLabels.labelFieldId(owner, relation),
                policy.referenceAccess(app, owner, relation, target, actor, Set.of(), null));
    }

    List<Row> selectionLabels(String app, DataCenter.Definition d, List<Row> rows, long actor) {
        return selectionLabels(app, d, rows, actor, null);
    }

    List<Row> selectionLabels(
            String app, DataCenter.Definition d, List<Row> rows, long actor, String detailId) {
        return selectionLabels(app, d, rows, actor, detailId, null);
    }

    /** 申请预览的多选关系来自已校验输入，不能读成数据库里尚未变更的旧关系。 */
    List<Row> selectionLabels(
            String app,
            DataCenter.Definition d,
            List<Row> rows,
            long actor,
            String detailId,
            Map<String, List<String>> stagedRelations) {
        return selectionCatalog.inApplication(
                app, null, () -> labelRows(app, d, rows, actor, detailId, stagedRelations));
    }

    private List<Row> labelRows(
            String app,
            DataCenter.Definition d,
            List<Row> rows,
            long actor,
            String detailId,
            Map<String, List<String>> stagedRelations) {
        var detail =
                detailId == null
                        ? null
                        : d.details().stream()
                                .filter(t -> t.id().equals(detailId))
                                .findFirst()
                                .orElseThrow();
        List<ApplicationRecords.Row> output =
                selectionLabels(
                        detail == null ? d.fields() : detail.fields(),
                        detail == null ? d.fieldOptions() : detail.fieldOptions(),
                        rows);
        for (DataCenter.Relation relation : RelationSources.in(d, detailId)) {
            boolean multiple = BusinessFields.multiple(relation);
            String key = multiple ? BusinessFields.key(relation) : relation.fieldId();
            if (key == null) continue;
            Map<String, List<String>> linked = new LinkedHashMap<>();
            if (multiple && stagedRelations != null) {
                for (ApplicationRecords.Row row : rows)
                    if (row.permissions().readRelations().contains(relation.id()))
                        linked.put(
                                row.id(), stagedRelations.getOrDefault(relation.id(), List.of()));
            } else if (multiple)
                linked =
                        relations.targets(
                                d,
                                relation,
                                rows.stream()
                                        .filter(
                                                row ->
                                                        row.permissions()
                                                                .readRelations()
                                                                .contains(relation.id()))
                                        .map(Row::id)
                                        .toList(),
                                actor);
            Map<String, List<String>> selectedLinks = linked;
            List<String> ids =
                    multiple
                            ? linked.values().stream()
                                    .flatMap(Collection::stream)
                                    .distinct()
                                    .toList()
                            : rows.stream()
                                    .flatMap(
                                            row ->
                                                    SelectionCatalog.ids(row.values().get(key))
                                                            .stream())
                                    .distinct()
                                    .toList();
            Map<String, SelectionFields.Option> labels;
            try {
                // 名称回显走引用访问：目标对象不必被应用引用，成员也不必有它的授权。
                labels =
                        ids.isEmpty()
                                ? Map.of()
                                : referenceLabels(
                                        app,
                                        d,
                                        relation,
                                        contexts.readable(app, relation.targetObjectId(), actor),
                                        ids,
                                        actor);
            } catch (ServiceException e) {
                labels = Map.of();
            }
            Map<String, SelectionFields.Option> resolved = labels;
            output =
                    output.stream()
                            .map(
                                    row -> {
                                        if (multiple
                                                ? !row.permissions()
                                                        .readRelations()
                                                        .contains(relation.id())
                                                : !row.values().containsKey(key)) return row;
                                        LinkedHashMap<String, Object> values =
                                                new LinkedHashMap<>(row.values());
                                        LinkedHashMap<String, String> display =
                                                new LinkedHashMap<>(row.displayValues());
                                        List<String> selected =
                                                multiple
                                                        ? selectedLinks.getOrDefault(
                                                                row.id(), List.of())
                                                        : SelectionCatalog.ids(values.get(key));
                                        if (multiple) values.put(key, selected);
                                        display.put(
                                                key,
                                                selected.stream()
                                                        .map(
                                                                id ->
                                                                        resolved.containsKey(id)
                                                                                ? resolved.get(id)
                                                                                        .label()
                                                                                : "已失效或无权限的引用")
                                                        .collect(
                                                                java.util.stream.Collectors.joining(
                                                                        "、")));
                                        return new Row(
                                                row.id(),
                                                row.revision(),
                                                values,
                                                row.permissions(),
                                                display,
                                                row.clientRowKey(),
                                                row.parentId());
                                    })
                            .toList();
        }
        return output;
    }

    boolean treeDirectory(SelectionFields.Source source) {
        return source != null
                && SelectionSourceEnum.DIRECTORY.matches(source.kind())
                && Set.of(FieldTypeEnum.ORGANIZATION.getCode(), FieldTypeEnum.DEPARTMENT.getCode())
                        .contains(Objects.toString(source.directory(), ""));
    }

    List<Row> selectionLabels(
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<Row> rows) {
        Map<String, Map<String, SelectionFields.Option>> labels = new HashMap<>();
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions o =
                    options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            SelectionFields.Source source = SelectionFields.source(field, o);
            if (source == null
                    || SelectionSourceEnum.OBJECT_RELATION.matches(source.kind())
                    || rows.stream().noneMatch(r -> r.values().get(field.id()) != null)) continue;
            Map<String, SelectionFields.Option> map = new HashMap<>();
            selectionCatalog
                    .selectedOptions(
                            field,
                            o,
                            rows.stream()
                                    .flatMap(
                                            r ->
                                                    SelectionCatalog.ids(r.values().get(field.id()))
                                                            .stream())
                                    .distinct()
                                    .toList())
                    .forEach(v -> map.put(v.value(), v));
            labels.put(field.id(), map);
        }
        return rows.stream()
                .map(
                        row -> {
                            Map<String, String> display = new LinkedHashMap<>(row.displayValues());
                            for (Map.Entry<String, Map<String, SelectionFields.Option>> entry :
                                    labels.entrySet())
                                if (row.values().containsKey(entry.getKey())) {
                                    display.put(
                                            entry.getKey(),
                                            SelectionCatalog.ids(row.values().get(entry.getKey()))
                                                    .stream()
                                                    .map(
                                                            id -> {
                                                                SelectionFields.Option label =
                                                                        entry.getValue().get(id);
                                                                return label == null
                                                                        ? "已失效或超出可见范围"
                                                                        : label.label()
                                                                                + (label.disabled()
                                                                                        ? "（已停用或不可选）"
                                                                                        : "");
                                                            })
                                                    .collect(
                                                            java.util.stream.Collectors.joining(
                                                                    "、")));
                                }
                            return new Row(
                                    row.id(),
                                    row.revision(),
                                    row.values(),
                                    row.permissions(),
                                    display,
                                    row.clientRowKey(),
                                    row.parentId());
                        })
                .toList();
    }

    Map<String, List<Row>> selectionDetailLabels(
            String app, DataCenter.Definition d, Map<String, List<Row>> values, long actor) {
        Map<String, List<Row>> output = new LinkedHashMap<>();
        for (DataCenter.Detail detail : d.details())
            if (values.containsKey(detail.id()))
                output.put(
                        detail.id(),
                        selectionLabels(app, d, values.get(detail.id()), actor, detail.id()));
        return output;
    }

    void validateSelectionScene(
            String app,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            ApplicationUi.Form form,
            Map<String, Object> payload,
            Map<String, Object> previous,
            long actor,
            Set<String> readable,
            Map<String, List<String>> relationValues,
            String recordId) {
        validateSelectionScene(
                app,
                d,
                table,
                form,
                payload,
                previous,
                actor,
                readable,
                relationValues,
                recordId,
                Map.of());
    }

    void validateSelectionScene(
            String app,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            ApplicationUi.Form form,
            Map<String, Object> payload,
            Map<String, Object> previous,
            long actor,
            Set<String> readable,
            Map<String, List<String>> relationValues,
            String recordId,
            Map<String, Object> contextValues) {
        if (form == null) return;
        selectionCatalog.inApplication(
                app,
                () ->
                        validateScene(
                                app,
                                d,
                                table,
                                form,
                                payload,
                                previous,
                                actor,
                                readable,
                                relationValues,
                                recordId,
                                contextValues));
    }

    private void validateScene(
            String app,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            ApplicationUi.Form form,
            Map<String, Object> payload,
            Map<String, Object> previous,
            long actor,
            Set<String> readable,
            Map<String, List<String>> relationValues,
            String recordId,
            Map<String, Object> contextValues) {
        previous = new LinkedHashMap<>(previous);
        for (DataCenter.Relation relation : d.relations())
            if (BusinessFields.multiple(relation)
                    && SelectionFields.presentations(form.nodes())
                            .containsKey(BusinessFields.key(relation)))
                previous.put(
                        BusinessFields.key(relation),
                        recordId == null
                                ? List.of()
                                : relations.targets(d, relation, recordId, actor));
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        merged.putAll(contextValues);
        if (relationValues != null)
            relationValues.forEach((id, ids) -> merged.put("relation_" + id, ids));
        final Map<String, Object> before = previous;
        for (FieldDefinition f : table.fields())
            if (payload.containsKey(table.column(f)))
                merged.put(f.id(), payload.get(table.column(f)));
        for (Map.Entry<String, ApplicationUi.FieldPresentation> entry :
                SelectionFields.presentations(form.nodes()).entrySet()) {
            SelectionFields.Presentation p =
                    entry.getValue() == null ? null : entry.getValue().selection();
            if (p == null) continue;
            FieldDefinition f =
                    BusinessFields.fields(d).stream()
                            .filter(v -> v.id().equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow();
            Object value = merged.get(f.id());
            if (value == null) continue;
            boolean upstreamChanged =
                    p.linkFieldId() != null
                            && !Objects.equals(
                                    merged.get(p.linkFieldId()), before.get(p.linkFieldId()));
            List<String> checkedIds =
                    SelectionCatalog.ids(value).stream()
                            .filter(
                                    id ->
                                            upstreamChanged
                                                    || !SelectionCatalog.ids(before.get(f.id()))
                                                            .contains(id))
                            .toList();
            if (checkedIds.isEmpty()) continue;
            if (Objects.equals(
                            SelectionCatalog.ids(value), SelectionCatalog.ids(before.get(f.id())))
                    && (p.linkFieldId() == null
                            || Objects.equals(
                                    merged.get(p.linkFieldId()), before.get(p.linkFieldId()))))
                continue;
            if (p.linkFieldId() != null && !readable.contains(p.linkFieldId()))
                throw invalid("无权读取联动来源字段");
            DataCenter.Relation relation = BusinessFields.relation(d, f.id());
            if (relation == null) {
                DataCenter.FieldOptions o =
                        table.options().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
                List<SelectionFields.Option> available =
                        selectionCatalog.scope(
                                treeDirectory(SelectionFields.source(f, o))
                                        ? selectionCatalog.options(f, o)
                                        : selectionCatalog.selectedOptions(f, o, checkedIds),
                                p,
                                merged);
                if (!available.stream()
                        .filter(v -> !v.disabled())
                        .map(SelectionFields.Option::value)
                        .toList()
                        .containsAll(checkedIds)) throw invalid("选择值超出当前表单范围，请重新选择：" + f.name());
            } else {
                // 视图固定范围只约束本轮新选的引用；已保存的超范围值保留回显但不可重新选入。
                if (p.viewId() != null) {
                    List<String> beforeIds = SelectionCatalog.ids(before.get(f.id()));
                    List<String> added =
                            checkedIds.stream().filter(id -> !beforeIds.contains(id)).toList();
                    if (!added.isEmpty()) {
                        var target = contexts.readable(app, relation.targetObjectId(), actor);
                        var view =
                                selectionView(
                                        applications.published(app).definition().resources(),
                                        p.viewId(),
                                        relation.targetObjectId());
                        if (!referenceOptions(
                                        app,
                                        target,
                                        added,
                                        actor,
                                        view,
                                        null,
                                        null,
                                        null,
                                        policy.referenceAccess(
                                                app, d, relation, target, actor, Set.of(), null))
                                .keySet()
                                .containsAll(added))
                            throw invalid("选择值超出当前表单视图范围，请重新选择：" + f.name());
                    }
                }
                if (p.linkTargetFieldId() != null) {
                    var equal = linkedEqual(p, merged);
                    var target = contexts.readable(app, relation.targetObjectId(), actor);
                    var access =
                            policy.referenceAccess(
                                    app,
                                    d,
                                    relation,
                                    target,
                                    actor,
                                    Set.of(p.linkTargetFieldId()),
                                    null);
                    for (String id : checkedIds) {
                        ApplicationRecords.Row row =
                                persistence.authorizedRead(
                                        schemas.main(target),
                                        id,
                                        actor,
                                        false,
                                        access,
                                        ApplicationActionEnum.READ);
                        if (!row.permissions().readFields().contains(p.linkTargetFieldId()))
                            throw invalid("无权查询关联目标筛选字段");
                        if (!Objects.equals(
                                SelectionCatalog.ids(row.values().get(p.linkTargetFieldId())),
                                SelectionCatalog.ids(equal.get(p.linkTargetFieldId()))))
                            throw invalid("关联记录不符合当前表单联动条件：" + f.name());
                    }
                }
            }
        }
    }

    Map<String, Object> selectionDefaults(
            RuntimeSchema.Table table,
            Map<String, Object> input,
            boolean creating,
            long actor,
            Set<String> writable) {
        return selectionDefaults(table, input, creating, actor, writable, Map.of());
    }

    Map<String, Object> selectionDefaults(
            RuntimeSchema.Table table,
            Map<String, Object> input,
            boolean creating,
            long actor,
            Set<String> writable,
            Map<String, ApplicationUi.FieldPresentation> presentations) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>(input);
        if (creating)
            for (FieldDefinition field : table.fields())
                selectionDefault(
                        table, field, result, actor, writable, presentations, new HashSet<>());
        return result;
    }

    /** 先求上游选择默认值，再按当前表单范围求下游；显式 null 不参与重新初始化。 */
    void selectionDefault(
            RuntimeSchema.Table table,
            FieldDefinition field,
            Map<String, Object> result,
            long actor,
            Set<String> writable,
            Map<String, ApplicationUi.FieldPresentation> presentations,
            Set<String> resolving) {
        if (!writable.contains(field.id()) || result.containsKey(field.id())) return;
        DataCenter.FieldOptions option =
                table.options().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
        if (SelectionFields.source(field, option) == null) return;
        if (!resolving.add(field.id())) throw invalid("选择字段默认值存在循环联动");
        ApplicationUi.FieldPresentation configured = presentations.get(field.id());
        SelectionFields.Presentation presentation =
                configured == null ? null : configured.selection();
        if (presentation != null && presentation.linkFieldId() != null)
            table.fields().stream()
                    .filter(f -> f.id().equals(presentation.linkFieldId()))
                    .findFirst()
                    .ifPresent(
                            upstream ->
                                    selectionDefault(
                                            table,
                                            upstream,
                                            result,
                                            actor,
                                            writable,
                                            presentations,
                                            resolving));
        result.put(
                field.id(),
                selectionCatalog.defaultValue(field, option, presentation, result, actor));
        resolving.remove(field.id());
    }
}
