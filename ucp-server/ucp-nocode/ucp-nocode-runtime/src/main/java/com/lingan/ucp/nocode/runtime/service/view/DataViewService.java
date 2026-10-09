package com.lingan.ucp.nocode.runtime.service.view;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationPageBindings;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.application.service.resource.DataViewDefinitions;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.dal.support.*;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.record.RecordConditions;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.record.RecordRelations;
import com.lingan.ucp.nocode.runtime.service.record.RecordValues;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 主记录、明细粒度和关联区块复用同一对象权限与固定发布版本。 */
@Service
public class DataViewService {
    @Resource private RecordConditions conditionCompiler;
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private RecordQueryAccess records;
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator validator;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordRelations relations;
    @Resource private ApplicationPageBindings bindings;
    @Resource private DataViewMapper mapper;
    @Resource private ObjectMapper json;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService>
            reports;

    private record SectionPlan(
            DataViews.Section section,
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            ApplicationRuntimePolicy.Access access,
            Set<String> readable,
            String correlation) {}

    public ApplicationUi.View view(String app, String object, String id, long actor) {
        records.definition(app, object, actor);
        ApplicationCenter.Resource resource =
                applications.published(app).definition().resources().stream()
                        .filter(
                                r ->
                                        r.id().equals(id)
                                                && ApplicationResourceKindEnum.VIEW.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("已发布视图不存在"));
        ApplicationUi.View view = validator.decode(resource.config(), ApplicationUi.View.class);
        if (!view.objectId().equals(object)) throw invalid("视图不属于此对象");
        return view;
    }

    private List<SectionPlan> sections(
            String app, DataCenter.Definition root, ApplicationUi.View view, long actor) {
        if (view.composition() == null) return List.of();
        ApplicationRuntimePolicy.Access rootAccess = policy.access(app, root, actor);
        RuntimeSchema.Table main = schemas.main(root);
        ArrayList<DataViewService.SectionPlan> output = new ArrayList<SectionPlan>();
        for (DataViews.Section section : view.composition().sections()) {
            if (section.detailId() != null) {
                if (!rootAccess.queryDetails().contains(section.detailId())) continue;
                DataCenter.Detail detail = DataViewDefinitions.detail(root, section.detailId());
                if (MemberStateEnum.INACTIVE.matches(detail.state())) continue;
                RuntimeSchema.Table table = schemas.detail(root, detail);
                output.add(
                        new SectionPlan(
                                section,
                                root,
                                table,
                                rootAccess,
                                table.columns().keySet(),
                                sqlFragments.correlation(
                                        table.binding().parentColumn(), main.key().name())));
                continue;
            }
            DataCenter.Definition target = records.definition(app, section.objectId(), actor);
            ApplicationRuntimePolicy.Access access = policy.access(app, target, actor);
            if (!access.any(ApplicationActionEnum.READ)) continue;
            ApplicationPageBindings.Resolved resolved =
                    bindings.resolve(
                            root.objectId(),
                            target.objectId(),
                            section.binding(),
                            id -> id.equals(root.objectId()) ? root : target);
            DataCenter.Relation relation = resolved.relation();
            boolean incoming = resolved.direction() == RelationDirectionEnum.INCOMING;
            RuntimeSchema.Table table = schemas.main(target);
            String correlation;
            if (BusinessFields.multiple(relation)) {
                if (!(incoming ? access : rootAccess).queryRelations().contains(relation.id()))
                    continue;
                RelationStatement link =
                        relations.statement(resolved.source(), relation, null, null, actor);
                correlation =
                        sqlFragments.correlation(
                                link.schema(),
                                link.table(),
                                table.key().name(),
                                main.key().name(),
                                incoming);
            } else if (incoming) {
                if (!access.queryFields().contains(relation.fieldId())) continue;
                correlation =
                        sqlFragments.correlation(
                                table.columns().get(relation.fieldId()), main.key().name());
            } else {
                if (!rootAccess.queryFields().contains(relation.fieldId())) continue;
                correlation =
                        sqlFragments.correlation(
                                table.key().name(), main.columns().get(relation.fieldId()));
            }
            output.add(
                    new SectionPlan(
                            section, target, table, access, access.queryFields(), correlation));
        }
        return output;
    }

    private DataViews.Model model(ApplicationUi.View view, List<SectionPlan> plans) {
        if (view.composition() == null) return new DataViews.Model(null, List.of(), Map.of());
        ArrayList<DataViews.Section> sections = new ArrayList<DataViews.Section>();
        for (DataViewService.SectionPlan plan : plans) {
            DataViews.Section s = plan.section();
            sections.add(
                    new DataViews.Section(
                            s.id(),
                            s.name(),
                            s.detailId(),
                            s.objectId(),
                            s.viewId(),
                            s.binding(),
                            s.fieldIds().stream().filter(plan.readable()::contains).toList(),
                            null,
                            s.pageSize(),
                            s.showTable()));
        }
        List<DataViews.Column> columns =
                view.composition().columns().stream()
                        .filter(
                                c ->
                                        plans.stream()
                                                .anyMatch(
                                                        p ->
                                                                p.section()
                                                                                .id()
                                                                                .equals(
                                                                                        c
                                                                                                .sectionId())
                                                                        && (ViewColumnKindEnum.COUNT
                                                                                        .matches(
                                                                                                c
                                                                                                        .kind())
                                                                                || p.readable()
                                                                                        .contains(
                                                                                                c
                                                                                                        .fieldId()))))
                        .toList();
        LinkedHashMap<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<String, DataCenter.FieldOptions>();
        for (DataViews.Column column : columns) {
            DataViewService.SectionPlan plan =
                    plans.stream()
                            .filter(p -> p.section().id().equals(column.sectionId()))
                            .findFirst()
                            .orElseThrow();
            if (column.fieldId() != null
                    && (ViewColumnKindEnum.LOOKUP.matches(column.kind())
                            || ViewColumnKindEnum.DETAIL.matches(column.kind())))
                options.put(
                        column.id(),
                        plan.table()
                                .options()
                                .getOrDefault(
                                        column.fieldId(), DataCenter.FieldOptions.defaults()));
        }
        LinkedHashMap<String, DataViews.SectionModel> sectionModels =
                new LinkedHashMap<String, DataViews.SectionModel>();
        for (DataViewService.SectionPlan p : plans) {
            LinkedHashMap<String, DataCenter.FieldOptions> fieldOptions =
                    new LinkedHashMap<>(p.table().options());
            fieldOptions.keySet().retainAll(p.readable());
            sectionModels.put(
                    p.section().id(),
                    new DataViews.SectionModel(
                            p.table().fields().stream()
                                    .filter(f -> p.readable().contains(f.id()))
                                    .toList(),
                            fieldOptions,
                            null));
        }
        return new DataViews.Model(
                new DataViews.Composition(
                        view.composition().grain(),
                        view.composition().detailId(),
                        sections,
                        columns),
                columns.stream().map(DataViewDefinitions::field).toList(),
                options,
                sectionModels);
    }

    // 授权读取会持有发布版本共享锁，不能声明为 PostgreSQL 只读事务。
    @Transactional
    public DataViews.Model model(String app, String object, String viewId, long actor) {
        ApplicationUi.View view = view(app, object, viewId, actor);
        DataViews.Model model =
                model(view, sections(app, records.definition(app, object, actor), view, actor));
        if (model.composition() == null) return model;
        LinkedHashMap<String, DataViews.SectionModel> sectionModels =
                new LinkedHashMap<>(model.sections());
        for (DataViews.Section section : model.composition().sections())
            if (section.objectId() != null) {
                DataViews.SectionModel old = sectionModels.get(section.id());
                sectionModels.put(
                        section.id(),
                        new DataViews.SectionModel(
                                old.fields(),
                                old.fieldOptions(),
                                records.model(app, section.objectId(), actor)));
            }
        return new DataViews.Model(
                model.composition(), model.fields(), model.fieldOptions(), sectionModels);
    }

    private RecordStatement sectionQuery(
            String app, SectionPlan plan, DataViews.ChildFilter filter, long actor) {
        RuntimeSchema.Table table = plan.table();
        RecordStatement result;
        if (plan.section().detailId() == null) {
            ApplicationUi.View fixed =
                    plan.section().viewId() == null
                            ? null
                            : view(
                                    app,
                                    plan.definition().objectId(),
                                    plan.section().viewId(),
                                    actor);
            result =
                    records.queryPlan(
                                    new Query(
                                            app,
                                            plan.definition().objectId(),
                                            1,
                                            100,
                                            filter == null ? null : filter.search(),
                                            filter == null ? Map.of() : filter.equal(),
                                            null,
                                            false,
                                            null,
                                            null,
                                            filter == null ? null : filter.conditions()),
                                    actor,
                                    ApplicationActionEnum.READ,
                                    null,
                                    fixed)
                            .statement();
        } else {
            RecordStatement base = table.statement(null, null, Long.toString(actor), false);
            LinkedHashMap<String, Object> equal = new LinkedHashMap<String, Object>();
            if (filter != null && filter.equal() != null) {
                if (filter.equal().size() > 20) throw invalid("子表筛选最多 20 项");
                for (Map.Entry<String, Object> e : filter.equal().entrySet()) {
                    FieldDefinition field =
                            table.fields().stream()
                                    .filter(
                                            f ->
                                                    f.id().equals(e.getKey())
                                                            && plan.readable().contains(f.id()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("子表筛选字段不可用"));
                    equal.put(
                            table.column(field),
                            values.convert(
                                    field,
                                    table.options()
                                            .getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults()),
                                    e.getValue()));
                }
            }
            String search = filter == null ? null : filter.search();
            if (search != null && !search.isBlank()) {
                if (search.length() > 200) throw invalid("搜索内容最多 200 字符");
                search =
                        "%"
                                + search.trim()
                                        .replace("!", "!!")
                                        .replace("%", "!%")
                                        .replace("_", "!_")
                                + "%";
            } else search = null;
            result =
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
                                    null,
                                    search,
                                    new ArrayList<>(equal.keySet()),
                                    write(equal),
                                    null,
                                    false,
                                    100,
                                    0,
                                    List.of(),
                                    "{}",
                                    base.actor(),
                                    false)
                            .conditions(
                                    conditionCompiler.compile(
                                            filter == null ? null : filter.conditions(),
                                            table,
                                            plan.readable()));
        }
        return result.conditions(
                conditionCompiler.append(
                        result.dynamicQuery(),
                        plan.section().conditions(),
                        table,
                        table.columns().keySet()));
    }

    private record Prepared(
            RecordQueryAccess.QueryPlan root,
            DataViews.Model model,
            List<SectionPlan> sections,
            DataViewStatement sql) {}

    private Prepared prepare(
            Query request, ApplicationUi.View view, long actor, ApplicationActionEnum operation) {
        return prepare(request, view, actor, operation, null);
    }

    private Prepared prepare(
            Query request,
            ApplicationUi.View view,
            long actor,
            ApplicationActionEnum operation,
            ReportStatement dashboardSource) {
        DataCenter.Definition definition =
                records.definition(request.applicationId(), request.objectId(), actor);
        List<DataViewService.SectionPlan> plans =
                sections(request.applicationId(), definition, view, actor);
        DataViews.Model model = model(view, plans);
        Set<String> ids =
                model.fields().stream()
                        .map(FieldDefinition::id)
                        .collect(java.util.stream.Collectors.toSet());
        LinkedHashMap<String, Object> rootEqual = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> extraEqual = new LinkedHashMap<String, Object>();
        if (request.equal() != null)
            request.equal()
                    .forEach(
                            (id, value) ->
                                    (ids.contains(id) ? extraEqual : rootEqual).put(id, value));
        // 主对象固定范围和关系上下文先解析；可含关联列的用户 AND/OR 条件由外层完整编译。
        RecordQueryAccess.QueryPlan root =
                records.queryPlan(
                        new Query(
                                request.applicationId(),
                                request.objectId(),
                                request.pageNo(),
                                request.pageSize(),
                                request.search(),
                                rootEqual,
                                null,
                                false,
                                null,
                                request.context(),
                                null,
                                List.of(),
                                request.reportDrill(),
                                request.dashboardDrill()),
                        actor,
                        operation,
                        null,
                        view,
                        dashboardSource);
        LinkedHashMap<String, DataViews.ChildFilter> filters =
                new LinkedHashMap<String, DataViews.ChildFilter>();
        if (request.childFilters() != null)
            for (DataViews.ChildFilter filter : request.childFilters()) {
                if (filter == null
                        || plans.stream()
                                .noneMatch(p -> p.section().id().equals(filter.sectionId()))
                        || filters.putIfAbsent(filter.sectionId(), filter) != null)
                    throw invalid("子表筛选不存在、重复或无权查看");
            }
        ArrayList<DataViewStatement.Section> sections = new ArrayList<DataViewStatement.Section>();
        for (DataViewService.SectionPlan plan : plans) {
            DataViews.ChildFilter filter = filters.get(plan.section().id());
            sections.add(
                    new DataViewStatement.Section(
                            sectionQuery(request.applicationId(), plan, filter, actor),
                            plan.correlation(),
                            filter != null && filter.requireMatch()));
        }
        Integer grain = null;
        if (ViewGrainEnum.DETAIL.matches(view.composition().grain())) {
            for (int i = 0; i < plans.size(); i++)
                if (Objects.equals(
                        plans.get(i).section().detailId(), view.composition().detailId())) {
                    grain = i;
                    break;
                }
            if (grain == null) throw invalid("没有当前明细粒度的查看权限或未配置来源子表");
        }
        // 按明细行统计的下钻：主表那一段已按命中的主记录收窄（上面的 root），这里再把粒度明细段收窄到命中的明细行。
        // 按主记录统计的下钻没有明细行范围（null），明细粒度视图照旧显示命中主记录的全部明细。
        String grainKeys = null;
        if (grain != null && request.reportDrill() != null) {
            var detailKeys =
                    reports.getObject()
                            .drillDetailKeys(
                                    request.reportDrill(),
                                    request.applicationId(),
                                    request.objectId(),
                                    view.composition().detailId(),
                                    actor);
            if (detailKeys != null) grainKeys = write(detailKeys);
        }
        var projected = new ArrayList<DataViewStatement.Column>();
        for (var col : model.composition().columns()) {
            int index = -1;
            for (int i = 0; i < plans.size(); i++)
                if (plans.get(i).section().id().equals(col.sectionId())) {
                    index = i;
                    break;
                }
            projected.add(
                    new DataViewStatement.Column(
                            col.id(),
                            index,
                            col.fieldId() == null
                                    ? null
                                    : plans.get(index).table().columns().get(col.fieldId()),
                            col.kind(),
                            DataViewDefinitions.numeric(col.type())));
        }
        RuntimeSchema.Table main = schemas.main(definition);
        ArrayList<FieldDefinition> fields = new ArrayList<>(main.fields());
        fields.addAll(model.fields());
        LinkedHashMap<String, String> columns = new LinkedHashMap<>(main.columns());
        ids.forEach(id -> columns.put(id, id));
        LinkedHashMap<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<>(main.options());
        options.putAll(model.fieldOptions());
        RuntimeSchema.Table virtual =
                new RuntimeSchema.Table(
                        main.schema(),
                        main.name(),
                        main.binding(),
                        main.physical(),
                        fields,
                        options,
                        columns,
                        main.key(),
                        false);
        HashSet<String> allowed = new HashSet<>(root.access().queryFields());
        allowed.addAll(ids);
        allowed.removeIf(
                id ->
                        !ids.contains(id)
                                && com.lingan.ucp.nocode.metadata.service.formula.Calculations.live(
                                        definition.fieldOptions().get(id)));
        QueryWrapper<Object> where =
                conditionCompiler.compile(request.conditions(), virtual, allowed);
        RecordConditions.validateReferences(request.conditions(), definition);
        if (!extraEqual.isEmpty()) {
            if (where == null) {
                where = new QueryWrapper<>();
                where.setParamAlias("dynamicQuery");
            }
            for (Map.Entry<String, Object> entry : extraEqual.entrySet()) {
                FieldDefinition f =
                        model.fields().stream()
                                .filter(item -> item.id().equals(entry.getKey()))
                                .findFirst()
                                .orElseThrow();
                Object converted =
                        values.convert(
                                f,
                                model.fieldOptions()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                                entry.getValue());
                if (converted == null) where.isNull(sqlFragments.column("t", f.id(), false));
                else where.eq(sqlFragments.column("t", f.id(), false), converted);
            }
        }
        String sortId = request.sortFieldId() == null ? view.sortFieldId() : request.sortFieldId();
        if (sortId != null && (!allowed.contains(sortId) || !columns.containsKey(sortId)))
            throw invalid("排序字段不存在或无权查看");
        DataViewStatement sql =
                new DataViewStatement(
                        root.statement(),
                        sections,
                        projected,
                        grain,
                        where,
                        sortId == null ? null : columns.get(sortId),
                        request.sortFieldId() == null ? view.descending() : request.descending(),
                        request.pageSize(),
                        (request.pageNo() - 1) * request.pageSize(),
                        grainKeys);
        return new Prepared(root, model, plans, sql);
    }

    @Transactional
    public PageResult<Row> page(
            Query query, ApplicationUi.View view, long actor, ApplicationActionEnum operation) {
        return page(query, view, actor, operation, null);
    }

    @Transactional
    public PageResult<Row> page(
            Query query,
            ApplicationUi.View view,
            long actor,
            ApplicationActionEnum operation,
            ReportStatement dashboardSource) {
        DataViewService.Prepared prepared = prepare(query, view, actor, operation, dashboardSource);
        List<com.fasterxml.jackson.databind.JsonNode> raw =
                mapper.rows(prepared.sql()).stream().map(this::read).toList();
        List<String> recordsOnly = raw.stream().map(n -> n.get("record").toString()).toList();
        List<ApplicationRecords.Row> enriched =
                records.queryRows(
                        query.applicationId(), prepared.root(), recordsOnly, actor, false);
        ArrayList<ApplicationRecords.Row> output = new ArrayList<Row>();
        for (int i = 0; i < enriched.size(); i++) {
            ApplicationRecords.Row row = enriched.get(i);
            LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
            view.fieldIds()
                    .forEach(
                            id -> {
                                if (row.values().containsKey(id))
                                    data.put(id, row.values().get(id));
                            });
            com.fasterxml.jackson.databind.JsonNode extra = raw.get(i).get("extra");
            prepared.model()
                    .fields()
                    .forEach(
                            f ->
                                    data.put(
                                            f.id(),
                                            json.convertValue(extra.get(f.id()), Object.class)));
            com.fasterxml.jackson.databind.JsonNode child = raw.get(i).get("detailId");
            HashSet<String> readable = new HashSet<>(row.permissions().readFields());
            readable.addAll(prepared.model().fields().stream().map(FieldDefinition::id).toList());
            ApplicationAuthorization.Capabilities cap = row.permissions();
            cap =
                    new ApplicationAuthorization.Capabilities(
                            cap.actions(),
                            readable,
                            cap.writeFields(),
                            cap.readDetails(),
                            cap.writeDetails(),
                            cap.readRelations(),
                            cap.writeRelations());
            output.add(
                    new Row(
                            child.isNull() ? row.id() : row.id() + ":" + child.asText(),
                            row.revision(),
                            data,
                            cap,
                            row.displayValues(),
                            null,
                            child.isNull() ? null : row.id()));
        }
        return new PageResult<>(output, mapper.count(prepared.sql()));
    }

    private com.fasterxml.jackson.databind.JsonNode read(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw invalid("视图查询结果无效");
        }
    }

    @Transactional
    public PageResult<Row> children(DataViews.ChildQuery query, long actor) {
        ApplicationUi.View view =
                view(query.applicationId(), query.objectId(), query.viewId(), actor);
        if (view.composition() == null) throw invalid("此视图没有子表配置");
        if (query.recordId() == null || query.recordId().isBlank()) throw invalid("请选择主记录");
        DataViewService.Prepared prepared =
                prepare(
                        new Query(
                                query.applicationId(),
                                query.objectId(),
                                query.pageNo(),
                                query.pageSize(),
                                null,
                                Map.of(),
                                null,
                                false,
                                query.viewId(),
                                null,
                                null,
                                List.of(
                                        new DataViews.ChildFilter(
                                                query.sectionId(),
                                                query.search(),
                                                query.equal(),
                                                query.conditions(),
                                                false))),
                        view,
                        actor,
                        ApplicationActionEnum.READ);
        int index = -1;
        for (int i = 0; i < prepared.sections().size(); i++)
            if (prepared.sections().get(i).section().id().equals(query.sectionId())) {
                index = i;
                break;
            }
        if (index < 0) throw invalid("子表不存在或无权查看");
        DataViewService.SectionPlan plan = prepared.sections().get(index);
        String sort =
                query.sortFieldId() == null
                        ? null
                        : plan.table().columns().get(query.sortFieldId());
        if (query.sortFieldId() != null
                && (sort == null || !plan.readable().contains(query.sortFieldId())))
            throw invalid("子表排序字段不可用");
        DataViewChildStatement sql =
                new DataViewChildStatement(
                        prepared.sql(),
                        index,
                        query.recordId(),
                        sort,
                        query.descending(),
                        query.pageSize(),
                        (query.pageNo() - 1) * query.pageSize());
        List<Row> rows;
        List<String> raw = mapper.childRows(sql);
        if (plan.section().detailId() == null) {
            rows =
                    records.queryRows(
                            query.applicationId(),
                            new RecordQueryAccess.QueryPlan(
                                    plan.definition(),
                                    plan.access(),
                                    prepared.sql().sections().get(index).statement()),
                            raw,
                            actor,
                            false);
        } else {
            Set<String> fieldIds = plan.readable();
            ApplicationAuthorization.Capabilities caps =
                    new ApplicationAuthorization.Capabilities(
                            Set.of(ApplicationActionEnum.READ.getCode()),
                            fieldIds,
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of());
            rows =
                    raw.stream()
                            .map(this::read)
                            .map(
                                    n ->
                                            new Row(
                                                    n.get("id").asText(),
                                                    n.get("revision").asText(),
                                                    json.convertValue(
                                                            n.get("values"),
                                                            new com.fasterxml.jackson.core.type
                                                                            .TypeReference<
                                                                    Map<String, Object>>() {}),
                                                    caps))
                            .toList();
            rows =
                    records.selectionLabels(
                            query.applicationId(),
                            plan.definition(),
                            rows,
                            actor,
                            plan.section().detailId());
        }
        rows =
                rows.stream()
                        .map(
                                row -> {
                                    LinkedHashMap<String, Object> selected =
                                            new LinkedHashMap<>(row.values());
                                    selected.keySet().retainAll(plan.section().fieldIds());
                                    return new Row(
                                            row.id(),
                                            row.revision(),
                                            selected,
                                            row.permissions(),
                                            row.displayValues(),
                                            null,
                                            query.recordId());
                                })
                        .toList();
        return new PageResult<>(rows, mapper.childCount(sql));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("视图查询参数无效");
        }
    }
}
