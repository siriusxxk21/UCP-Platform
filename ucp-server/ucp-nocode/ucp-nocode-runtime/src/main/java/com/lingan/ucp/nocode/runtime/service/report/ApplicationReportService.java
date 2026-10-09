package com.lingan.ucp.nocode.runtime.service.report;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.common.util.DynamicQueryProcessor;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationReportValidator;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.application.service.resource.ReportGrainMessages;
import com.lingan.ucp.nocode.application.service.resource.ReportSourceMessages;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DetailForms;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.dal.support.*;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.record.FixedViewConditions;
import com.lingan.ucp.nocode.runtime.service.record.RecordConditions;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.record.RecordValues;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** 统计、下钻和导出统一编译权限与条件；不向浏览器发送未授权行后再做汇总。 */
@Service
public class ApplicationReportService {
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private FixedViewConditions fixedViewConditions;
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resources;
    @Resource private ApplicationReportValidator validator;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordQueryAccess records;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private ReportMapper mapper;
    @Resource private ReportAggregateReader aggregates;
    @Resource private ObjectMapper json;
    @Resource private SelectionCatalog selections;
    @Resource private DataObjectApi objects;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog
            automations;

    private record PreviewSource(
            ApplicationReports.Config config,
            Map<String, DataCenter.Definition> definitions,
            List<ApplicationCenter.Resource> resources) {}

    @Transactional
    public ApplicationReports.Result preview(ApplicationReports.Preview request, long actor) {
        if (request == null || request.config() == null) throw invalid("统计预览配置不能为空");
        // 这一笔先核对设计者（应用头共享行锁）、再读草稿引用的对象版本（对象头共享行锁）；对象发布是
        // 「目录独占锁 → 对象头 → 应用头」，所以先取目录共享锁，不与它交叉。
        automations.lock(false);
        applications.requireDesigner(request.applicationId(), actor);
        List<ApplicationCenter.ObjectReference> refs =
                applications
                        .normalize(new ApplicationCenter.Definition(request.objects(), List.of()))
                        .objects();
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        refs.forEach(
                ref ->
                        definitions.put(
                                ref.objectId(),
                                objects.getVersion(ref.objectId(), ref.versionNo()).definition()));
        ApplicationReports.Config config = validator.normalize(request.config(), definitions);
        ApplicationReports.Query q =
                new ApplicationReports.Query(
                        request.applicationId(), "preview", null, null, null, null, null, 1, 20);
        ApplicationReportService.PreviewSource source =
                new PreviewSource(
                        config,
                        definitions,
                        request.resources() == null ? List.of() : request.resources());
        if (source.resources().size() > 500) throw invalid("预览资源数量过多");
        // 维度标签里的挑取值来源按草稿引用的对象版本解析。
        return selections.inApplication(
                request.applicationId(),
                definitions,
                () ->
                        result(
                                prepare(q, actor, ApplicationActionEnum.READ, false, source),
                                q,
                                actor,
                                true));
    }

    /** 数据视图统计下钻的记录上限：超过时要求缩小统计范围，避免把过多主键带入列表查询。 */
    public static final int MAX_DRILL_RECORDS = 100000;

    /**
     * pivot 仅 PIVOT 时非空（已补默认值）；dimensions 在 PIVOT 下为行维度，columnDimensions 为列维度，其它展示方式列维度为空。 detail /
     * detailTable 仅按明细行统计时非空：所选内部明细及其物理表。
     */
    private record Plan(
            ApplicationReports.Config config,
            DataCenter.Definition definition,
            ApplicationRuntimePolicy.Access access,
            ReportStatement statement,
            List<FieldDefinition> dimensions,
            List<DataCenter.Definition> dimensionOwners,
            List<String> detailFields,
            List<FieldDefinition> columnDimensions,
            List<DataCenter.Definition> columnOwners,
            ApplicationReports.Pivot pivot,
            DataCenter.Detail detail,
            RuntimeSchema.Table detailTable,
            List<SourcePlan> sources) {
        /** 单来源（含多来源拆出的一个来源）的编译结果：sources 为空。 */
        Plan(
                ApplicationReports.Config config,
                DataCenter.Definition definition,
                ApplicationRuntimePolicy.Access access,
                ReportStatement statement,
                List<FieldDefinition> dimensions,
                List<DataCenter.Definition> dimensionOwners,
                List<String> detailFields,
                List<FieldDefinition> columnDimensions,
                List<DataCenter.Definition> columnOwners,
                ApplicationReports.Pivot pivot,
                DataCenter.Detail detail,
                RuntimeSchema.Table detailTable) {
            this(
                    config,
                    definition,
                    access,
                    statement,
                    dimensions,
                    dimensionOwners,
                    detailFields,
                    columnDimensions,
                    columnOwners,
                    pivot,
                    detail,
                    detailTable,
                    null);
        }
    }

    /** 多个数据来源的一个来源：id（来源 1 为 main）、显示名与它自己的单来源编译结果。 */
    private record SourcePlan(String id, String name, Plan plan) {}

    /**
     * 统计下钻命中的记录主键与条数；条数只按下钻条件计算，不含数据视图自身条件。按明细行统计时 keys 是去重后的所属主记录主键， detailKeys 是命中的明细行主键、total
     * 是明细行数；按主记录统计时 detailKeys 为空。
     */
    public record DrillScope(List<String> keys, long total, List<String> detailKeys) {
        public DrillScope(List<String> keys, long total) {
            this(keys, total, null);
        }
    }

    /** 实时裁剪不可用报表，创建者身份不会跳过共享上限或来源字段权限。 */
    public boolean available(String app, String report, long actor) {
        try {
            prepare(
                    new ApplicationReports.Query(app, report, null, null, null, null, null, 1, 20),
                    actor,
                    ApplicationActionEnum.READ,
                    true);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
            return false;
        }
    }

    @Transactional
    public ApplicationReports.Result query(ApplicationReports.Query query, long actor) {
        if (query.metricId() != null) throw invalid("指标选择仅用于明细下钻");
        if (query.sourceId() != null) throw invalid("指标选择仅用于明细下钻");
        return result(prepare(query, actor, ApplicationActionEnum.READ), query, actor);
    }

    @Transactional
    public ApplicationReports.Result export(ApplicationReports.Query query, long actor) {
        if (query.metricId() != null) throw invalid("汇总导出不接收明细指标选择");
        if (query.sourceId() != null) throw invalid("汇总导出不接收明细指标选择");
        var p = prepare(query, actor, ApplicationActionEnum.EXPORT);
        var result = result(p, query, actor);
        // 导出的是当前统计视图展示的组数，保留总体指标及截断说明，由导出文件明确标识。
        return result;
    }

    @Transactional
    public PageResult<ApplicationRecords.Row> details(ApplicationReports.Query query, long actor) {
        var p = drillPlan(query, actor);
        if (query.pageNo() < 1
                || query.pageNo() > 100000
                || query.pageSize() < 1
                || query.pageSize() > 100) throw invalid("明细分页参数无效");
        if (p.detail() != null) return detailRows(query, p, actor);
        var rows =
                records.reportRows(
                        query.applicationId(),
                        p.definition(),
                        mapper.rows(p.statement()),
                        p.access(),
                        actor);
        if (p.detailFields() != null)
            rows =
                    rows.stream()
                            .map(
                                    r -> {
                                        Map<String, Object> selected = new LinkedHashMap<>();
                                        Map<String, String> display = new LinkedHashMap<>();
                                        for (String id : p.detailFields())
                                            if (r.values().containsKey(id)) {
                                                selected.put(id, r.values().get(id));
                                                if (r.displayValues().containsKey(id))
                                                    display.put(id, r.displayValues().get(id));
                                            }
                                        return new ApplicationRecords.Row(
                                                r.id(),
                                                r.revision(),
                                                selected,
                                                r.permissions(),
                                                display);
                                    })
                            .toList();
        return new PageResult<>(rows, mapper.count(p.statement()));
    }

    /**
     * 按明细行统计的下钻：每行是一条命中的明细行（id = 主记录ID:明细行ID，parentId = 主记录ID），带着所属主记录的字段，
     * 条数与同一格子的明细行数同口径。主表字段沿用记录读取的逐行裁剪；明细字段整组可见（能进到这里已过明细可读判定）。
     */
    private PageResult<ApplicationRecords.Row> detailRows(
            ApplicationReports.Query query, Plan p, long actor) {
        RuntimeSchema.Table table = p.detailTable();
        List<String> raw =
                mapper.detailRows(
                        p.statement()
                                .withDetailRows(
                                        table.statement(null, null, Long.toString(actor), false)));
        Set<String> lineFields = table.columns().keySet();
        ApplicationAuthorization.Capabilities lineCapabilities =
                new ApplicationAuthorization.Capabilities(
                        Set.of(ApplicationActionEnum.READ.getCode()),
                        lineFields,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        // 同一条主记录在一页里会出现多次：主表部分只读一次，再按主键回填到每一行。
        Map<String, String> rootRaw = new LinkedHashMap<>();
        List<String> parents = new ArrayList<>();
        List<ApplicationRecords.Row> lines = new ArrayList<>();
        try {
            for (String pair : raw) {
                JsonNode parts = json.readTree(pair);
                String root = parts.get(0).asText();
                String parent = json.readTree(root).get("id").asText();
                rootRaw.putIfAbsent(parent, root);
                parents.add(parent);
                JsonNode line = json.readTree(parts.get(1).asText());
                lines.add(
                        new ApplicationRecords.Row(
                                line.get("id").asText(),
                                line.get("revision").asText(),
                                json.convertValue(
                                        line.get("values"),
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {}),
                                lineCapabilities));
            }
        } catch (java.io.IOException e) {
            throw invalid("统计结果无法读取");
        }
        Map<String, ApplicationRecords.Row> roots = new LinkedHashMap<>();
        for (ApplicationRecords.Row row :
                records.reportRows(
                        query.applicationId(),
                        p.definition(),
                        List.copyOf(rootRaw.values()),
                        p.access(),
                        actor)) roots.put(row.id(), row);
        lines =
                records.selectionLabels(
                        query.applicationId(), p.definition(), lines, actor, p.detail().id());
        List<ApplicationRecords.Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ApplicationRecords.Row root = roots.get(parents.get(i));
            ApplicationRecords.Row line = lines.get(i);
            Map<String, Object> values = new LinkedHashMap<>(root.values());
            values.putAll(line.values());
            Map<String, String> display = new LinkedHashMap<>(root.displayValues());
            display.putAll(line.displayValues());
            ApplicationAuthorization.Capabilities own = root.permissions();
            Set<String> readable = new LinkedHashSet<>(own.readFields());
            readable.addAll(lineFields);
            rows.add(
                    new ApplicationRecords.Row(
                            root.id() + ":" + line.id(),
                            root.revision(),
                            values,
                            new ApplicationAuthorization.Capabilities(
                                    own.actions(),
                                    readable,
                                    own.writeFields(),
                                    own.readDetails(),
                                    own.writeDetails(),
                                    own.readRelations(),
                                    own.writeRelations()),
                            display,
                            null,
                            root.id()));
        }
        return new PageResult<>(rows, mapper.count(p.statement()));
    }

    /**
     * report-details 与数据视图统计下钻共用的唯一条件构造：同一 prepare（应用入口、报表存在、来源/关联/字段授权、固定条件、equal、
     * 指标条件、用户筛选、日期范围、页面上下文、下钻明细视图固定范围、行列键前缀与下钻指标），再由 ReportMapper 的同一段 from/drill 片段取数。两处不得各自拼条件。
     */
    private Plan drillPlan(ApplicationReports.Query query, long actor) {
        Plan plan = prepare(query, actor, ApplicationActionEnum.READ);
        // 多个数据来源：只有带了指标（或来源）才会落到某一个来源上；落到来源后的编译结果是该来源自己的单来源结果。
        if (plan.sources() != null) throw invalid(ReportSourceMessages.DRILL_METRIC);
        return plan;
    }

    /** 数据视图统计下钻：取得与 report-details 完全相同的记录集主键，由记录查询与视图自身条件取交集。 报表必须属于当前应用、对当前用户可用， 且统计对象与视图对象一致。 */
    @Transactional
    public DrillScope drillScope(
            ApplicationReports.Drill drill, String applicationId, String objectId, long actor) {
        return drillScope(drill, applicationId, objectId, null, actor);
    }

    /**
     * viewDetailId 为承载下钻的数据视图按哪个内部明细逐行显示（一行一条主记录的视图为空）。按明细行统计的下钻只进「按同一明细逐行显示」的数据视图，
     * 并且统计配置里选了下钻明细视图；其它情形（含未选下钻视图的存量明细粒度统计）照旧拒绝。
     */
    @Transactional
    public DrillScope drillScope(
            ApplicationReports.Drill drill,
            String applicationId,
            String objectId,
            String viewDetailId,
            long actor) {
        var p = drillSource(drill, applicationId, objectId, viewDetailId, actor);
        long total = mapper.count(p.statement());
        if (total > MAX_DRILL_RECORDS)
            throw invalid("下钻记录超过 " + MAX_DRILL_RECORDS + " 条，请缩小统计范围后再查看明细");
        return new DrillScope(
                mapper.keys(p.statement()),
                total,
                p.detail() == null ? null : mapper.detailKeys(p.statement()));
    }

    /** 明细粒度数据视图承载统计下钻时命中的明细行主键：与 {@link #drillScope} 同一判定与同一段条件；按主记录统计的下钻返回空（不按明细行收窄）。 */
    @Transactional
    public List<String> drillDetailKeys(
            ApplicationReports.Drill drill,
            String applicationId,
            String objectId,
            String viewDetailId,
            long actor) {
        var p = drillSource(drill, applicationId, objectId, viewDetailId, actor);
        return p.detail() == null ? null : mapper.detailKeys(p.statement());
    }

    /** 承载下钻的视图按哪个内部明细逐行显示：明细粒度数据视图为其明细，其它视图为空。 */
    public static String viewDetailId(ApplicationUi.View view) {
        return view != null
                        && view.composition() != null
                        && ViewGrainEnum.DETAIL.matches(view.composition().grain())
                ? view.composition().detailId()
                : null;
    }

    /** 仅按下钻条件计数（与 report-details 的 total 相同），不叠加数据视图自身的条件。 */
    @Transactional
    public long drillTotal(
            ApplicationReports.Drill drill, String applicationId, String objectId, long actor) {
        return drillTotal(drill, applicationId, objectId, null, actor);
    }

    @Transactional
    public long drillTotal(
            ApplicationReports.Drill drill,
            String applicationId,
            String objectId,
            String viewDetailId,
            long actor) {
        return mapper.count(
                drillSource(drill, applicationId, objectId, viewDetailId, actor).statement());
    }

    private Plan drillSource(
            ApplicationReports.Drill drill,
            String applicationId,
            String objectId,
            String viewDetailId,
            long actor) {
        if (drill == null) throw invalid("统计下钻参数不能为空");
        if (!Objects.equals(drill.applicationId(), applicationId))
            throw invalid("统计下钻与数据视图不属于同一应用");
        var p = drillPlan(drill.query(), actor);
        if (!Objects.equals(p.config().objectId(), objectId)) throw invalid("统计下钻与数据视图的对象不一致");
        if (p.detail() != null
                && (p.config().detailViewId() == null
                        || !Objects.equals(p.detail().id(), viewDetailId)))
            throw invalid(ReportGrainMessages.VIEW_DRILL);
        return p;
    }

    /** 运行期报表：维度标签里的挑取值来源按本应用固定的对象版本解析。 */
    private ApplicationReports.Result result(Plan p, ApplicationReports.Query request, long actor) {
        return selections.inApplication(
                request.applicationId(), null, () -> result(p, request, actor, false));
    }

    private ApplicationReports.Result result(
            Plan p, ApplicationReports.Query request, long actor, boolean preview) {
        if (p.pivot() != null) return pivotResult(p, request, actor, preview);
        try {
            var raw = json.readTree(mapper.result(p.statement()));
            List<List<String>> keyRows = new ArrayList<>();
            for (var row : raw.path("groups"))
                keyRows.add(ReportDimensionLabels.keys(row.path("keys")));
            var labelRows = labels(p, false, keyRows, request.applicationId(), actor);
            List<ApplicationReports.Group> groups = new ArrayList<>();
            int index = 0;
            for (var row : raw.path("groups")) {
                groups.add(
                        new ApplicationReports.Group(
                                keyRows.get(index),
                                labelRows.get(index),
                                strings(row.path("values"))));
                index++;
            }
            boolean canExport = canExport(request, actor, preview);
            return new ApplicationReports.Result(
                    names(p, false),
                    p.config().metrics(),
                    groups,
                    strings(raw.path("totals")),
                    raw.path("totalGroups").asLong(),
                    raw.path("recordCount").asLong(),
                    canExport,
                    p.config().timeZone(),
                    null,
                    p.detail() == null ? null : p.detail().name(),
                    summaries(p, raw.path("sourceCounts")));
        } catch (java.io.IOException e) {
            throw invalid("统计结果无法读取");
        }
    }

    /** 透视表：叶子、小计、合计同一语句快照；表头标签与汇总表同一解析规则。 */
    private ApplicationReports.Result pivotResult(
            Plan p, ApplicationReports.Query request, long actor, boolean preview) {
        try {
            var raw = json.readTree(pivotJson(p));
            int rowCount = p.dimensions().size(), columnCount = p.columnDimensions().size();
            List<List<String>> rowKeys = new ArrayList<>(), columnKeys = new ArrayList<>();
            for (var row : raw.path("rows")) rowKeys.add(ReportDimensionLabels.keys(row));
            for (var column : raw.path("columns"))
                columnKeys.add(ReportDimensionLabels.keys(column));
            var rowLabels = labels(p, false, rowKeys, request.applicationId(), actor);
            var columnLabels = labels(p, true, columnKeys, request.applicationId(), actor);
            List<ApplicationReports.PivotHeader> rows = new ArrayList<>(),
                    columns = new ArrayList<>();
            for (int i = 0; i < rowKeys.size(); i++)
                rows.add(new ApplicationReports.PivotHeader(rowKeys.get(i), rowLabels.get(i)));
            for (int i = 0; i < columnKeys.size(); i++)
                columns.add(
                        new ApplicationReports.PivotHeader(columnKeys.get(i), columnLabels.get(i)));
            var metricIds =
                    p.config().metrics().stream().map(ApplicationReports.Metric::id).toList();
            var cells =
                    ReportPivotCells.cells(
                            ReportPivotCells.parse(
                                    raw.path("cells"), rowCount, columnCount, metricIds),
                            rowCount,
                            columnCount,
                            p.pivot(),
                            rowKeys,
                            columnKeys);
            long totalRows = raw.path("totalRowGroups").asLong(),
                    totalColumns = raw.path("totalColumnGroups").asLong();
            var pivot =
                    new ApplicationReports.PivotResult(
                            names(p, false),
                            names(p, true),
                            rows,
                            columns,
                            cells,
                            totalRows > rows.size(),
                            totalColumns > columns.size(),
                            totalRows,
                            totalColumns);
            return new ApplicationReports.Result(
                    pivot.rowDimensionNames(),
                    p.config().metrics(),
                    List.of(),
                    strings(raw.path("grand")),
                    totalRows,
                    raw.path("recordCount").asLong(),
                    canExport(request, actor, preview),
                    p.config().timeZone(),
                    pivot,
                    p.detail() == null ? null : p.detail().name(),
                    summaries(p, raw.path("sourceCounts")));
        } catch (java.io.IOException e) {
            throw invalid("统计结果无法读取");
        }
    }

    /** 透视查询超时转成可读的业务错误（减少维度、改粗日期分组或加筛选），不再是 500「系统异常」。 */
    private String pivotJson(Plan p) {
        try {
            return mapper.pivot(p.statement());
        } catch (RuntimeException e) {
            if (ReportQueryTimeouts.isTimeout(e)) throw invalid(ReportQueryTimeouts.PIVOT_MESSAGE);
            throw e;
        }
    }

    private boolean canExport(ApplicationReports.Query request, long actor, boolean preview) {
        try {
            if (!preview) prepare(request, actor, ApplicationActionEnum.EXPORT);
            return !preview;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
            return false;
        }
    }

    private Map<String, String> strings(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(
                        e ->
                                result.put(
                                        e.getKey(),
                                        e.getValue().isNull() ? null : e.getValue().asText()));
        return result;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw invalid("统计筛选格式无效");
        }
    }

    /** 编译过程缓存同一单值路径；每一段均检查来源引用字段与目标对象权限。 */
    private final class Compiler {
        private final String app;
        private final long actor;
        private final ApplicationActionEnum operation;
        private final DataCenter.Definition root;
        private final Map<String, DataCenter.Definition> preview;
        private final Map<String, CompiledField> owners = new LinkedHashMap<>();
        private final List<ReportStatement.Join> joins = new ArrayList<>();
        private final Map<String, QueryWrapper<Object>> accessPredicates = new LinkedHashMap<>();
        // 按明细行统计时所选的明细（空 = 按主记录）及其来源（别名 g）。它的定义是投影出来的，只用于读字段与关系；
        // 能不能看这个明细只从主对象的访问权判，⛔ 不把投影定义传给 policy.access。
        private final DataCenter.Detail detail;
        private final CompiledField detailOwner;
        private final ApplicationRuntimePolicy.Access rootAccess;

        Compiler(
                String app,
                long actor,
                ApplicationActionEnum operation,
                DataCenter.Definition root,
                Map<String, DataCenter.Definition> preview,
                DataCenter.Detail detail,
                ApplicationRuntimePolicy.Access rootAccess) {
            this.app = app;
            this.actor = actor;
            this.operation = operation;
            this.root = root;
            this.preview = preview;
            this.detail = detail;
            this.rootAccess = rootAccess;
            owners.put("", new CompiledField(root, schemas.main(root), null, "t"));
            detailOwner =
                    detail == null
                            ? null
                            : new CompiledField(
                                    DetailForms.definition(root, detail),
                                    schemas.detail(root, detail),
                                    null,
                                    "g");
        }

        CompiledField field(String key) {
            if (key == null) throw invalid("统计字段不能为空");
            String[] parts = key.split(":", -1);
            if (parts.length > 2) throw invalid("统计字段路径无效");
            String path = "";
            ApplicationReportService.CompiledField current = owners.get("");
            if (parts.length == 2)
                for (var relationId : validator.path(parts[0])) {
                    // 第一段：主表上的关系，或粒度明细上的关系（来源换成明细 g）；第二段：只能是目标对象主表上的关系。
                    var relation =
                            path.isEmpty()
                                    ? validator.relation(root, relationId, detail)
                                    : validator.targetRelation(current.owner(), relationId);
                    if (path.isEmpty() && relation.sourceDetailId() != null) current = detailOwner;
                    require(current, validator.field(current.owner(), relation.fieldId()));
                    String next = path.isEmpty() ? relationId : path + "/" + relationId;
                    ApplicationReportService.CompiledField existing = owners.get(next);
                    if (existing == null) {
                        DataCenter.Definition owner =
                                preview == null
                                        ? records.definition(app, relation.targetObjectId(), actor)
                                        : validator.object(preview, relation.targetObjectId());
                        ApplicationRuntimePolicy.Access access = policy.access(app, owner, actor);
                        if (!access.any(operation)) throw invalid("没有关联统计对象的操作权限");
                        RuntimeSchema.Table table = schemas.main(owner);
                        String alias = "r" + joins.size();
                        QueryWrapper<Object> permission = new QueryWrapper<Object>();
                        permission.setParamAlias("predicates.access_" + alias);
                        accessPredicates.put(
                                "access_" + alias,
                                policy.conditions(access, table, permission, operation, alias));
                        joins.add(
                                new ReportStatement.Join(
                                        table.schema(),
                                        table.name(),
                                        table.key().name(),
                                        current.alias(),
                                        column(current.table(), relation.fieldId()),
                                        creator(access, operation),
                                        table.statement(null, null, null, false).deletedColumn()));
                        existing = new CompiledField(owner, table, null, alias);
                        owners.put(next, existing);
                    }
                    current = existing;
                    path = next;
                }
            String id = parts[parts.length - 1];
            if (path.isEmpty() && validator.scope(root, detail, id) != root) current = detailOwner;
            FieldDefinition field = validator.field(current.owner(), id);
            require(current, field);
            return new CompiledField(current.owner(), current.table(), field, current.alias());
        }

        /**
         * 每个字段的判定顺序：范围（上面的 relation / scope）→ 存在与停用（validator.field）→ 这里的授权。
         * 明细字段没有字段级授权，看得到这个明细就能用它的全部字段，只从主对象的访问权判；主表字段与关系目标对象的字段判可查看字段。
         */
        private void require(CompiledField source, FieldDefinition field) {
            if (source == detailOwner) {
                if (!rootAccess.queryDetails().contains(detail.id()))
                    throw invalid(ReportGrainMessages.detailRead(detail.name()));
                return;
            }
            ApplicationRuntimePolicy.Access access = policy.access(app, source.owner(), actor);
            if (!access.any(operation)) throw invalid(ReportGrainMessages.UNAUTHORIZED);
            if (!access.queryFields().contains(field.id()))
                throw invalid(
                        ReportGrainMessages.fieldRead(source.owner().objectName(), field.name()));
        }
    }

    private record CompiledField(
            DataCenter.Definition owner,
            RuntimeSchema.Table table,
            FieldDefinition field,
            String alias) {}

    private Plan prepare(ApplicationReports.Query q, long actor, ApplicationActionEnum operation) {
        return prepare(q, actor, operation, false);
    }

    /** 可见性只检查完整的对象及字段授权；配置值失效应由可见区块显示运行错误。 */
    private Plan prepare(
            ApplicationReports.Query q,
            long actor,
            ApplicationActionEnum operation,
            boolean availabilityOnly) {
        return prepare(q, actor, operation, availabilityOnly, null);
    }

    private Plan prepare(
            ApplicationReports.Query q,
            long actor,
            ApplicationActionEnum operation,
            boolean availabilityOnly,
            PreviewSource preview) {
        if (q == null) throw invalid("统计请求不能为空");
        if (preview == null) policy.requireEntry(q.applicationId(), actor);
        List<ApplicationCenter.Resource> sourceResources =
                preview == null
                        ? applications.published(q.applicationId()).definition().resources()
                        : preview.resources();
        ApplicationReports.Config sourceConfig;
        if (preview != null) sourceConfig = preview.config();
        else {
            ApplicationCenter.Resource resource =
                    sourceResources.stream()
                            .filter(
                                    r ->
                                            Objects.equals(r.id(), q.reportId())
                                                    && ApplicationResourceKindEnum.REPORT.matches(
                                                            r.kind()))
                            .findFirst()
                            .orElseThrow(() -> invalid("已发布统计视图不存在"));
            sourceConfig = resources.decode(resource.config(), ApplicationReports.Config.class);
        }
        if (sourceConfig.multiSource())
            return multiSource(
                    q, actor, operation, availabilityOnly, preview, sourceResources, sourceConfig);
        if (q.sourceId() != null) throw invalid(ReportSourceMessages.SINGLE_SOURCE);
        return compile(
                q, actor, operation, availabilityOnly, preview, sourceResources, sourceConfig);
    }

    /**
     * 从一份单来源配置到可执行语句（引入多个数据来源前 prepare 的后半段，原样留在这里）：对象与授权、粒度、字段解析与权限、条件、筛选、日期范围、记录范围、
     * 下钻视图固定范围、排序与上限。多来源时每个来源的来源投影配置各走一遍（{@link #multiSource}）。
     */
    private Plan compile(
            ApplicationReports.Query q,
            long actor,
            ApplicationActionEnum operation,
            boolean availabilityOnly,
            PreviewSource preview,
            List<ApplicationCenter.Resource> sourceResources,
            ApplicationReports.Config sourceConfig) {
        DataCenter.Definition d =
                preview == null
                        ? records.definition(q.applicationId(), sourceConfig.objectId(), actor)
                        : validator.object(preview.definitions(), sourceConfig.objectId());
        // 仅补全本次响应的展示格式，不重校验或回写已发布快照及其校验和。
        ApplicationReports.Config c = validator.normalizeFormats(sourceConfig, d);
        ApplicationRuntimePolicy.Access access = policy.access(q.applicationId(), d, actor);
        if (!access.any(operation)) throw invalid("没有统计来源对象的" + operation.getCode() + "权限");
        // 按明细行统计：所选明细须存在且启用；粒度相关的规则对早先发布的配置按保存时的同一句话再拦一次；
        // 这个人须看得到该明细（主对象访问权里的可查看明细，带记录条件的授权取交集，与数据视图同一判定）。
        DataCenter.Detail detail = validator.grainDetail(c, d);
        validator.requireGrain(c, d, detail);
        if (detail != null && !access.queryDetails().contains(detail.id()))
            throw invalid(ReportGrainMessages.detailRead(detail.name()));
        var compiler =
                new Compiler(
                        q.applicationId(),
                        actor,
                        operation,
                        d,
                        preview == null ? null : preview.definitions(),
                        detail,
                        access);
        var table = compiler.owners.get("").table();
        boolean pivotDisplay = ReportDisplayEnum.PIVOT == ReportDisplayEnum.fromCode(c.display());
        var pivot =
                pivotDisplay
                        ? (c.pivot() == null ? ApplicationReports.Pivot.defaults() : c.pivot())
                                .withDefaults()
                        : null;
        List<ReportStatement.Dimension> dimensions = new ArrayList<>();
        List<FieldDefinition> dimensionFields = new ArrayList<>();
        List<DataCenter.Definition> dimensionOwners = new ArrayList<>();
        for (var dim : c.dimensions())
            compileDimension(compiler, dim, dimensions, dimensionFields, dimensionOwners);
        List<ReportStatement.Dimension> columnDimensions = new ArrayList<>();
        List<FieldDefinition> columnFields = new ArrayList<>();
        List<DataCenter.Definition> columnOwners = new ArrayList<>();
        if (pivotDisplay && c.columnDimensions() != null)
            for (var dim : c.columnDimensions())
                compileDimension(compiler, dim, columnDimensions, columnFields, columnOwners);
        List<String> metricColumns = new ArrayList<>();
        // 每个指标列带自己的来源别名（主表 t、粒度明细 g）。「主记录数」换成对主表主键的去重计数再进语句：
        // 每一层小计、合计各自重新去重，语句与表达式展开都看不到 COUNT_ROOT；结果里返回的仍是配置里的原指标。
        List<String> metricAliases = new ArrayList<>();
        List<ApplicationReports.Metric> statementMetrics = new ArrayList<>();
        for (var m : c.metrics()) {
            boolean rootCount =
                    ReportOperationEnum.COUNT_ROOT == ReportOperationEnum.fromCode(m.operation());
            CompiledField source = m.fieldId() == null ? null : compiler.field(m.fieldId());
            metricColumns.add(
                    rootCount
                            ? table.key().name()
                            : source == null ? null : column(source.table(), m.fieldId()));
            metricAliases.add(rootCount ? "t" : source == null ? null : source.alias());
            statementMetrics.add(
                    rootCount
                            ? new ApplicationReports.Metric(
                                    m.id(),
                                    m.name(),
                                    ReportOperationEnum.COUNT_DISTINCT.getCode(),
                                    null,
                                    m.conditions(),
                                    null,
                                    m.format())
                            : m);
        }
        Map<String, QueryWrapper<Object>> predicates = new LinkedHashMap<>();
        compilePredicate(c.conditions(), "fixed", compiler, predicates, null, availabilityOnly);
        compilePredicate(
                q.conditions(),
                "user",
                compiler,
                predicates,
                new HashSet<>(c.filterFieldIds()),
                availabilityOnly);
        Integer drillMetric = null;
        for (int i = 0; i < c.metrics().size(); i++) {
            ApplicationReports.Metric metric = c.metrics().get(i);
            compilePredicate(
                    metric.conditions(), "m" + i, compiler, predicates, null, availabilityOnly);
            if (Objects.equals(metric.id(), q.metricId())) {
                if (ReportOperationEnum.FORMULA == ReportOperationEnum.fromCode(metric.operation()))
                    throw invalid("计算指标请分别查看其引用指标的明细");
                drillMetric = i;
            }
        }
        if (q.metricId() != null && drillMetric == null) throw invalid("下钻指标不存在");
        for (String key : c.filterFieldIds()) compiler.field(key);
        if (c.dateFieldId() != null) compiler.field(c.dateFieldId());
        Map<String, Object> filters = new LinkedHashMap<>(c.equal());
        List<String> detailFields = null;
        ApplicationUi.View detailView = null;
        if (c.detailViewId() != null) {
            ApplicationCenter.Resource viewResource =
                    sourceResources.stream()
                            .filter(
                                    r ->
                                            r.id().equals(c.detailViewId())
                                                    && ApplicationResourceKindEnum.VIEW.matches(
                                                            r.kind()))
                            .findFirst()
                            .orElseThrow(() -> invalid("统计明细视图不存在"));
            ApplicationUi.View view =
                    resources.decode(viewResource.config(), ApplicationUi.View.class);
            detailView = view;
            if (!Objects.equals(view.objectId(), c.objectId())) throw invalid("统计与明细视图的对象不一致");
            // 明细粒度：早先发布的配置也按保存时的同一句话再拦一次；粒度明细段的固定条件与主表固定范围一样并入统计（只作用在明细行 g 上）。
            validator.requireDrillView(detail, view);
            if (detail != null)
                compilePredicate(
                        grainSectionConditions(view, detail),
                        "detailView",
                        compiler,
                        predicates,
                        null,
                        availabilityOnly);
            merge(filters, view.equal());
            detailFields = view.fieldIds();
        }
        if (q.equal() != null) {
            if (!c.filterFieldIds().containsAll(q.equal().keySet()) || q.equal().size() > 12)
                throw invalid("只能使用统计视图开放的筛选字段");
            merge(filters, q.equal());
        }
        List<ReportStatement.Filter> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            ApplicationReportService.CompiledField f = compiler.field(entry.getKey());
            if (availabilityOnly) continue;
            String col = column(f.table(), f.field().id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    col,
                    values.convert(
                            f.field(),
                            f.table()
                                    .options()
                                    .getOrDefault(
                                            f.field().id(), DataCenter.FieldOptions.defaults()),
                            entry.getValue()));
            conditions.add(
                    new ReportStatement.Filter(
                            f.alias(), f.table().schema(), f.table().name(), col, write(payload)));
        }
        String start = null, end = null;
        if (q.dateFrom() != null || q.dateTo() != null) {
            if (c.dateFieldId() == null) throw invalid("统计视图未开放日期范围");
            try {
                LocalDate from = q.dateFrom() == null ? null : LocalDate.parse(q.dateFrom());
                LocalDate to = q.dateTo() == null ? null : LocalDate.parse(q.dateTo());
                if (from != null && to != null && from.isAfter(to)) throw invalid("开始日期不能晚于结束日期");
                start = from == null ? null : from.toString();
                end = to == null ? null : to.plusDays(1).toString();
            } catch (java.time.DateTimeException e) {
                throw invalid("日期范围格式应为 YYYY-MM-DD");
            }
        }
        // 非透视：group 必须覆盖全部维度；透视：group 为行键前缀（可为小计），columnGroup 为列键前缀。
        if (q.group() != null
                && ((pivotDisplay
                                ? q.group().size() > dimensions.size()
                                : q.group().size() != dimensions.size())
                        || q.group().stream().anyMatch(k -> k != null && k.length() > 2000)))
            throw invalid("下钻分组键无效");
        var columnGroup =
                q.columnGroup() == null || q.columnGroup().isEmpty() ? null : q.columnGroup();
        if (columnGroup != null
                && (!pivotDisplay
                        || columnGroup.size() > columnDimensions.size()
                        || columnGroup.stream().anyMatch(k -> k != null && k.length() > 2000)))
            throw invalid("下钻列键无效");
        var b = table.statement(null, null, Long.toString(actor), false);
        var sql =
                new RecordStatement(
                        b.schema(),
                        b.table(),
                        b.keyColumn(),
                        b.fields(),
                        List.of(),
                        b.numericFields(),
                        b.deletedColumn(),
                        null,
                        null,
                        null,
                        creator(access, operation),
                        null,
                        List.of(),
                        "{}",
                        null,
                        false,
                        q.pageSize() > 0 && q.pageSize() <= 100 ? q.pageSize() : 10,
                        Math.max(0, q.pageNo() - 1) * Math.max(1, q.pageSize()),
                        List.of(),
                        "{}",
                        b.actor(),
                        false);
        if (preview == null)
            sql =
                    records.reportScope(
                            sql,
                            q.applicationId(),
                            c.objectId(),
                            q.reportId(),
                            q.context(),
                            access,
                            actor);
        sql =
                sql.conditions(
                        policy.conditions(
                                access,
                                table,
                                fixedViewConditions.append(
                                        detailView, d, table, sql.dynamicQuery(), actor),
                                operation,
                                "t"));
        predicates.putAll(compiler.accessPredicates);
        Integer sort = null;
        for (int i = 0; i < c.metrics().size(); i++)
            if (c.metrics().get(i).id().equals(c.sortMetricId())) sort = i;
        RuntimeSchema.Table detailTable = detail == null ? null : compiler.detailOwner.table();
        if (detailTable != null && detailTable.binding().parentColumn() == null)
            throw invalid("统计所选的内部明细没有归属列");
        var compiled =
                new ReportStatement(
                        sql,
                        dimensions,
                        compiler.joins,
                        conditions,
                        statementMetrics,
                        metricColumns,
                        c.dateFieldId() == null ? null : column(table, c.dateFieldId()),
                        c.dateFieldId() != null && withZone(table, c.dateFieldId()),
                        c.timeZone(),
                        start,
                        end,
                        q.group(),
                        ReportOrdering.groupLimit(c, operation == ApplicationActionEnum.EXPORT),
                        sort,
                        c.descending(),
                        predicates,
                        drillMetric,
                        columnDimensions,
                        columnGroup,
                        pivot == null ? 0 : pivot.maxColumnGroups(),
                        pivot != null && exactRollup(c, compiler),
                        availabilityOnly
                                ? null
                                : ReportOrdering.of(
                                        c,
                                        q.sort(),
                                        sort,
                                        pivot,
                                        dimensions.size(),
                                        columnDimensions.size()),
                        pivot == null
                                ? 0
                                : operation == ApplicationActionEnum.EXPORT
                                        ? ApplicationReports.MAX_EXPORT_PIVOT_CELLS
                                        : ApplicationReports.MAX_PIVOT_CELLS,
                        detailTable == null
                                ? null
                                : new ReportStatement.Detail(
                                        detailTable.schema(),
                                        detailTable.name(),
                                        detailTable.key().name(),
                                        detailTable.binding().parentColumn(),
                                        detailTable
                                                .statement(null, null, null, false)
                                                .deletedColumn()),
                        metricAliases);
        return new Plan(
                c,
                d,
                access,
                compiled,
                dimensionFields,
                dimensionOwners,
                detailFields,
                columnFields,
                columnOwners,
                pivot,
                detail,
                detailTable);
    }

    /**
     * 多个数据来源（契约第 7、9、11 章）：下钻（带指标或来源）只编译所落到的那个来源，返回它自己的单来源结果——之后的明细行、数据视图下钻都与单来源同一段代码；
     * 其余（取数、导出、可用性）每个来源各编译一遍（各自的对象、粒度、字段授权、记录范围、条件与筛选），再合成一条语句：逐行 UNION ALL 之后的分组、排序、截断与单来源相同。
     * 附加来源的业务报错加「来源「…」：」前缀；任一来源不可用 ⇒ 整张统计不可用。
     */
    private Plan multiSource(
            ApplicationReports.Query q,
            long actor,
            ApplicationActionEnum operation,
            boolean availabilityOnly,
            PreviewSource preview,
            List<ApplicationCenter.Resource> sourceResources,
            ApplicationReports.Config c) {
        String target = ReportMultiSources.target(c, q);
        if (target != null)
            return compileSource(
                    q,
                    actor,
                    operation,
                    availabilityOnly,
                    preview,
                    sourceResources,
                    c,
                    source(c, target));
        List<SourcePlan> plans = new ArrayList<>();
        Plan main =
                compileSource(
                        q, actor, operation, availabilityOnly, preview, sourceResources, c, null);
        plans.add(
                new SourcePlan(
                        ReportSourceMessages.MAIN,
                        c.sourceName() == null ? main.definition().objectName() : c.sourceName(),
                        main));
        for (ApplicationReports.Source s : c.extraSources())
            plans.add(
                    new SourcePlan(
                            s.id(),
                            s.name(),
                            compileSource(
                                    q,
                                    actor,
                                    operation,
                                    availabilityOnly,
                                    preview,
                                    sourceResources,
                                    c,
                                    s)));
        return merge(c, q, operation, availabilityOnly, plans);
    }

    private static ApplicationReports.Source source(ApplicationReports.Config c, String id) {
        if (ReportSourceMessages.MAIN.equals(id)) return null;
        return c.extraSources().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> invalid(ReportSourceMessages.DRILL_SOURCE));
    }

    /** 一个来源：来源投影配置 + 投影请求走 {@link #compile}；附加来源的报错加前缀（错误码不变）。 */
    private Plan compileSource(
            ApplicationReports.Query q,
            long actor,
            ApplicationActionEnum operation,
            boolean availabilityOnly,
            PreviewSource preview,
            List<ApplicationCenter.Resource> sourceResources,
            ApplicationReports.Config c,
            ApplicationReports.Source s) {
        String sourceId = s == null ? ReportSourceMessages.MAIN : s.id();
        if (s == null)
            return compile(
                    ReportMultiSources.query(validator, json, c, null, q, sourceId),
                    actor,
                    operation,
                    availabilityOnly,
                    preview,
                    sourceResources,
                    validator.project(c, null));
        try {
            for (String key : c.filterFieldIds()) ReportMultiSources.map(validator, c, s, key);
            return compile(
                    ReportMultiSources.query(validator, json, c, s, q, sourceId),
                    actor,
                    operation,
                    availabilityOnly,
                    preview,
                    sourceResources,
                    validator.project(c, s));
        } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
            throw new com.lingan.ucp.framework.common.exception.ServiceException(
                    e.getCode(), ReportSourceMessages.prefix(s.name(), e.getMessage()));
        }
    }

    /**
     * 合成多来源语句（契约 11.1）：顶层的维度取来源 1 的（只用个数、numeric 与分组方式），指标是合并后的全部指标（基础指标取其来源编译后的形状，主记录数已换成去重计数），
     * 每个非公式指标恒有 m{k}（空条件，只为让聚合带上 FILTER），排序、上限与格数预算按顶层配置照常计算；不走叶子预聚合。
     */
    private Plan merge(
            ApplicationReports.Config c,
            ApplicationReports.Query q,
            ApplicationActionEnum operation,
            boolean availabilityOnly,
            List<SourcePlan> plans) {
        Plan first = plans.getFirst().plan();
        Map<String, Integer> owner = new HashMap<>();
        Map<String, ApplicationReports.Metric> shown = new HashMap<>();
        for (int s = 0; s < plans.size(); s++)
            for (ApplicationReports.Metric m : plans.get(s).plan().config().metrics()) {
                owner.put(m.id(), s);
                shown.put(m.id(), m);
            }
        List<ApplicationReports.Metric> metrics = new ArrayList<>();
        List<ApplicationReports.Metric> statementMetrics = new ArrayList<>();
        Map<String, QueryWrapper<Object>> predicates = new LinkedHashMap<>();
        List<List<ReportStatement.Slot>> slots = new ArrayList<>();
        for (int s = 0; s < plans.size(); s++) slots.add(new ArrayList<>());
        for (int k = 0; k < c.metrics().size(); k++) {
            ApplicationReports.Metric m = c.metrics().get(k);
            Integer s = owner.get(m.id());
            if (s == null) {
                metrics.add(m);
                statementMetrics.add(m);
                for (List<ReportStatement.Slot> list : slots) list.add(null);
                continue;
            }
            ApplicationReports.Metric own = shown.get(m.id());
            metrics.add(
                    new ApplicationReports.Metric(
                            own.id(),
                            own.name(),
                            own.operation(),
                            own.fieldId(),
                            own.conditions(),
                            own.formula(),
                            own.format(),
                            m.sourceId()));
            Plan plan = plans.get(s).plan();
            int local = plan.config().metrics().indexOf(own);
            ReportStatement statement = plan.statement();
            statementMetrics.add(statement.metrics().get(local));
            predicates.put("m" + k, new QueryWrapper<>());
            String column = statement.metricColumns().get(local);
            String alias = statement.metricAliases().get(local);
            String nullType = column == null ? null : nullType(plan, alias, column);
            for (int other = 0; other < plans.size(); other++)
                slots.get(other)
                        .add(
                                other == s
                                        ? new ReportStatement.Slot(local, column, alias, nullType)
                                        : new ReportStatement.Slot(null, null, null, nullType));
        }
        List<ReportStatement.Source> sources = new ArrayList<>();
        for (int s = 0; s < plans.size(); s++)
            sources.add(
                    new ReportStatement.Source(
                            plans.get(s).plan().statement(),
                            Collections.unmodifiableList(slots.get(s))));
        ApplicationReports.Config config = c.withMetrics(metrics);
        ApplicationReports.Pivot pivot = first.pivot();
        Integer sort = null;
        for (int i = 0; i < c.metrics().size(); i++)
            if (c.metrics().get(i).id().equals(c.sortMetricId())) sort = i;
        List<String> none = Collections.nCopies(c.metrics().size(), null);
        ReportStatement merged =
                new ReportStatement(
                        first.statement().base(),
                        first.statement().dimensions(),
                        List.of(),
                        List.of(),
                        statementMetrics,
                        none,
                        null,
                        false,
                        c.timeZone(),
                        null,
                        null,
                        null,
                        ReportOrdering.groupLimit(c, operation == ApplicationActionEnum.EXPORT),
                        sort,
                        c.descending(),
                        predicates,
                        null,
                        first.statement().columnDimensions(),
                        null,
                        pivot == null ? 0 : pivot.maxColumnGroups(),
                        false,
                        availabilityOnly
                                ? null
                                : ReportOrdering.of(
                                        c,
                                        q.sort(),
                                        sort,
                                        pivot,
                                        first.statement().dimensions().size(),
                                        first.statement().columnDimensions().size()),
                        pivot == null
                                ? 0
                                : operation == ApplicationActionEnum.EXPORT
                                        ? ApplicationReports.MAX_EXPORT_PIVOT_CELLS
                                        : ApplicationReports.MAX_PIVOT_CELLS,
                        null,
                        none,
                        List.copyOf(sources));
        return new Plan(
                config,
                first.definition(),
                first.access(),
                merged,
                first.dimensions(),
                first.dimensionOwners(),
                null,
                first.columnDimensions(),
                first.columnOwners(),
                pivot,
                null,
                null,
                List.copyOf(plans));
    }

    /** 指标列的物理类型（其它来源分支里放同类型的空）：主表 t 取对象主表，明细 g 取粒度明细表。 */
    private String nullType(Plan plan, String alias, String column) {
        RuntimeSchema.Table table =
                "g".equals(alias) ? plan.detailTable() : schemas.main(plan.definition());
        String nativeType =
                table.physical().columns().stream()
                        .filter(v -> v.name().equals(column))
                        .map(v -> v.nativeType())
                        .findFirst()
                        .orElse(null);
        return ReportMultiSources.nullType(nativeType);
    }

    /** 维度显示文本：单来源与原先相同；多个数据来源时单值引用字段的维度显示目标记录名称。 */
    private List<List<String>> labels(
            Plan p, boolean columns, List<List<String>> keys, String app, long actor) {
        List<FieldDefinition> fields = columns ? p.columnDimensions() : p.dimensions();
        List<DataCenter.Definition> owners = columns ? p.columnOwners() : p.dimensionOwners();
        if (p.sources() == null)
            return ReportDimensionLabels.labels(selections, fields, owners, keys);
        return ReportDimensionLabels.labels(
                selections,
                fields,
                owners,
                keys,
                (index, ids) -> {
                    DataCenter.Definition dimensionOwner = owners.get(index);
                    // 明细字段的归属是投影定义：引用关系与名称按主对象上的那条关系取（同一对象里字段 ID 不重复）。
                    DataCenter.Definition relationOwner =
                            Objects.equals(dimensionOwner.objectId(), p.definition().objectId())
                                    ? p.definition()
                                    : dimensionOwner;
                    DataCenter.Relation relation =
                            BusinessFields.relation(relationOwner, fields.get(index).id());
                    if (relation == null || BusinessFields.multiple(relation)) return null;
                    return records.referenceLabels(app, relationOwner, relation, ids, actor);
                });
    }

    /** 维度名：多个数据来源时取配置的维度显示名，为空取来源 1 的字段名；单来源与原先相同。 */
    private List<String> names(Plan p, boolean columns) {
        List<String> fields =
                (columns ? p.columnDimensions() : p.dimensions())
                        .stream().map(FieldDefinition::name).toList();
        List<String> labels =
                p.sources() == null
                        ? null
                        : columns
                                ? p.config().columnDimensionLabels()
                                : p.config().dimensionLabels();
        if (labels == null) return fields;
        List<String> names = new ArrayList<>(fields);
        for (int i = 0; i < names.size() && i < labels.size(); i++)
            if (labels.get(i) != null) names.set(i, labels.get(i));
        return names;
    }

    /** 多个数据来源的各来源条数（契约 2.1）：顺序 = 来源 1、附加来源按配置顺序；没有行的来源记 0。单来源为空。 */
    private List<ApplicationReports.SourceSummary> summaries(Plan p, JsonNode counts) {
        if (p.sources() == null) return null;
        Map<Integer, Long> bySource = new HashMap<>();
        for (JsonNode pair : counts) bySource.put(pair.get(0).asInt(), pair.get(1).asLong());
        List<ApplicationReports.SourceSummary> summaries = new ArrayList<>();
        for (int s = 0; s < p.sources().size(); s++) {
            SourcePlan source = p.sources().get(s);
            summaries.add(
                    new ApplicationReports.SourceSummary(
                            source.id(),
                            source.name(),
                            source.plan().config().objectId(),
                            bySource.getOrDefault(s, 0L),
                            source.plan().detail() == null ? null : source.plan().detail().name()));
        }
        return List.copyOf(summaries);
    }

    /** 明细粒度数据视图里粒度明细那一段的固定条件（与数据视图取第一个同明细的子表段同一规则）；没有则为空。 */
    private static DynamicConditionDTO grainSectionConditions(
            ApplicationUi.View view, DataCenter.Detail detail) {
        if (view.composition() == null || view.composition().sections() == null) return null;
        for (var section : view.composition().sections())
            if (Objects.equals(section.detailId(), detail.id())) {
                var conditions = section.conditions();
                // 空条件组不进语句（否则是一段空的括号）。
                return conditions == null
                                || conditions.getItems() == null
                                || conditions.getItems().isEmpty()
                        ? null
                        : conditions;
            }
        return null;
    }

    /** 维度字段按同一编译器校验来源关联与字段授权；numeric 仅用于透视表按数值排序行列。 */
    private void compileDimension(
            Compiler compiler,
            ApplicationReports.Dimension dim,
            List<ReportStatement.Dimension> output,
            List<FieldDefinition> fields,
            List<DataCenter.Definition> owners) {
        var f = compiler.field(validator.key(dim));
        output.add(
                new ReportStatement.Dimension(
                        f.alias(),
                        column(f.table(), f.field().id()),
                        ReportBucketEnum.fromCode(dim.bucket()),
                        withZone(f.table(), f.field().id()),
                        FieldTypeEnum.fromCode(f.field().type()).isNumeric()
                                && BusinessFields.relation(f.owner(), f.field().id()) == null));
        fields.add(f.field());
        owners.add(f.owner());
    }

    private String creator(ApplicationRuntimePolicy.Access access, ApplicationActionEnum op) {
        // 导出范围不能扩大 READ；一项为 OWN 时仍限定本人创建。
        return access.all(ApplicationActionEnum.READ) && access.all(op)
                ? null
                : Long.toString(access.actor());
    }

    /** 复用底座条件处理器，先校验每个路径与字段权限，所有值通过 MyBatis 绑定。 */
    private void compilePredicate(
            DynamicConditionDTO input,
            String key,
            Compiler compiler,
            Map<String, QueryWrapper<Object>> output,
            Set<String> allowed,
            boolean availabilityOnly) {
        if (input == null) return;
        Map<String, String> mapping = new HashMap<>();
        DynamicConditionDTO normalized = json.convertValue(input, DynamicConditionDTO.class);
        validator.visitConditions(
                normalized,
                item -> {
                    if (allowed != null && !allowed.contains(item.getField()))
                        throw invalid("只能使用统计视图开放的筛选字段");
                    ApplicationReportService.CompiledField f = compiler.field(item.getField());
                    validator.validateCondition(item, f.owner(), f.field());
                    FieldTypeEnum type = FieldTypeEnum.fromCode(f.field().type());
                    boolean typed =
                            type.isNumeric()
                                    || type == FieldTypeEnum.BOOLEAN
                                    || type == FieldTypeEnum.DATE
                                    || type == FieldTypeEnum.DATETIME
                                    || type == FieldTypeEnum.TIME;
                    mapping.put(
                            item.getField(),
                            sqlFragments.column(
                                    f.alias(), column(f.table(), f.field().id()), !typed));
                    // 相对日期在下面整体展开成边界条件，这里不按具体值转换。
                    if (!availabilityOnly && !RelativeDates.isRelative(item.getValue())) {
                        var op = RecordQueryOperatorEnum.fromCode(item.getOperator());
                        var options =
                                f.table()
                                        .options()
                                        .getOrDefault(
                                                f.field().id(), DataCenter.FieldOptions.defaults());
                        if (op == RecordQueryOperatorEnum.IN
                                || op == RecordQueryOperatorEnum.BETWEEN)
                            item.setValue(
                                    ((List<?>) item.getValue())
                                            .stream()
                                                    .map(
                                                            v ->
                                                                    RecordConditions.value(
                                                                            f.field(), options, v,
                                                                            op))
                                                    .toList());
                        else
                            item.setValue(
                                    RecordConditions.value(
                                            f.field(), options, item.getValue(), op));
                    }
                });
        if (availabilityOnly) return;
        relative(normalized.getItems(), compiler, RelativeDates.today());
        var wrapper = new QueryWrapper<Object>();
        wrapper.setParamAlias("predicates." + key);
        wrapper.nested(w -> DynamicQueryProcessor.applyConditions(w, normalized, mapping));
        output.put(key, wrapper);
    }

    /** 相对日期叶子原位换成同字段的边界条件组（按执行当天换算、值已类型化）；同一次查询共用一个「今天」。 */
    private void relative(
            List<DynamicConditionDTO.Item> items, Compiler compiler, LocalDate today) {
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item.isGroup()) relative(item.getGroupItems(), compiler, today);
            else if (RelativeDates.isRelative(item.getValue())) {
                var f = compiler.field(item.getField());
                items.set(
                        i,
                        RelativeDates.expand(
                                f.field(),
                                f.table()
                                        .options()
                                        .getOrDefault(
                                                f.field().id(), DataCenter.FieldOptions.defaults()),
                                item.getOperator(),
                                item.getValue(),
                                today));
            }
        }
    }

    private String column(RuntimeSchema.Table table, String field) {
        String col = table.columns().get(field);
        if (col == null) throw invalid("统计字段没有可读取的物理列");
        return col;
    }

    /** 求和、平均的列都是精确数值（不是 real / double precision）时，透视表可先按叶子预聚合：局部和再相加与逐条累加逐位相同。 */
    private boolean exactRollup(ApplicationReports.Config c, Compiler compiler) {
        for (var m : c.metrics()) {
            var operation = ReportOperationEnum.fromCode(m.operation());
            if (m.fieldId() == null
                    || (operation != ReportOperationEnum.SUM
                            && operation != ReportOperationEnum.AVG)) continue;
            var table = compiler.field(m.fieldId()).table();
            String col = column(table, m.fieldId());
            if (table.physical().columns().stream()
                    .noneMatch(
                            v ->
                                    v.name().equals(col)
                                            && ReportPivotSets.exactNumber(v.nativeType())))
                return false;
        }
        return true;
    }

    private boolean withZone(RuntimeSchema.Table table, String field) {
        String col = column(table, field);
        return table.physical().columns().stream()
                .anyMatch(v -> v.name().equals(col) && v.nativeType().contains("with time zone"));
    }

    private void merge(Map<String, Object> target, Map<String, Object> additions) {
        additions.forEach(
                (key, value) -> {
                    if (target.containsKey(key) && !Objects.equals(target.get(key), value))
                        throw invalid("不能覆盖固定统计条件");
                    target.put(key, value);
                });
    }
}
