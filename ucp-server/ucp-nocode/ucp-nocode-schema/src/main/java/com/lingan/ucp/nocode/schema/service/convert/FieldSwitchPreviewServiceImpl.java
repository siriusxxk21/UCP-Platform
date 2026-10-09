package com.lingan.ucp.nocode.schema.service.convert;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldConversionDependencyInspector.Impact;
import com.lingan.ucp.nocode.api.FieldSwitchPreview.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.table.TableBindingService;
import com.lingan.ucp.nocode.schema.service.compile.FieldConversionPlanner;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 全量计数来自已发布列，切换意图只在内存构造；最终发布继续持锁重检。 */
@Service
public class FieldSwitchPreviewServiceImpl implements FieldSwitchPreviewService {
    @Resource private ObjectDesignService designs;
    @Resource private DraftValidator validator;
    @Resource private DataObjectApi objects;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;
    @Resource private FieldConversionMapper mapper;
    @Resource private FieldConversionPlanner conversions;
    @Resource private ObjectProvider<FieldConversionDependencyInspector> inspectors;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate transaction;

    /** 只读一致快照不获取对象行锁，发布仍在独立事务中重新校验。 */
    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private record Located(
            FieldDefinition field, FieldOptions options, String table, TableBinding binding) {}

    @Override
    public Result preview(Request request) {
        if (request == null) throw invalid("缺少字段切换参数");
        validator.id(request.objectId(), "对象 ID");
        validator.id(request.fieldId(), "字段 ID");
        if (request.detailId() != null) validator.id(request.detailId(), "明细 ID");
        if (!FieldTypeEnum.containsCode(request.targetType())) throw invalid("目标字段类型无效");
        return transaction.execute(
                status -> {
                    FieldConversionPlanner.Candidate[] candidate = {null};
                    Result result = inspect(request, value -> candidate[0] = value);
                    Storage storage = storage(request, result, candidate[0]);
                    return new Result(
                            result.objectId(),
                            result.detailId(),
                            result.fieldId(),
                            result.fieldName(),
                            result.sourceType(),
                            result.targetType(),
                            result.deploymentState(),
                            result.totalRows(),
                            result.valueRows(),
                            result.decision(),
                            result.explanation(),
                            result.impacts(),
                            result.deletedRows(),
                            result.failedRows(),
                            result.conversionRule(),
                            result.conflicts(),
                            storage);
                });
    }

    /** 在同一只读快照查询真实列，不能把草稿类型当作已部署类型；仅说明候选发布动作。 */
    private Storage storage(
            Request request, Result result, FieldConversionPlanner.Candidate candidate) {
        Definition published = designs.published(request.objectId());
        Definition current = designs.definition(request.objectId());
        Located selected = locate(current, request.detailId(), request.fieldId());
        Located original =
                published == null ? null : locate(published, request.detailId(), request.fieldId());
        Located location = original == null ? selected : original;
        if (location == null) return null;
        DatabaseMetadata.Table table =
                database.readTable(location.binding().schemaName(), location.table()).orElse(null);
        DatabaseMetadata.Column actual =
                table == null
                        ? null
                        : table.columns().stream()
                                .filter(column -> column.name().equals(column(location)))
                                .findFirst()
                                .orElse(null);
        String target = null;
        boolean selectionDdl = false;
        FieldOptions options = targetOptions(selected, request);
        try {
            FieldDefinition next =
                    validator.field(
                            new FieldDefinition(
                                    request.fieldId(),
                                    request.fieldId(),
                                    selected.field().code(),
                                    selected.field().name(),
                                    request.targetType(),
                                    request.length(),
                                    request.precision(),
                                    request.scale(),
                                    request.targetRequired(),
                                    request.targetUnique(),
                                    selected.field().sort()),
                            request.fieldId());
            selectionDdl =
                    original != null
                            && !SelectionFields.identity(next, options)
                                    .equals(
                                            SelectionFields.identity(
                                                    original.field(), original.options()));
            if (FieldTypeEnum.REFERENCE.matches(next.type())) {
                String targetId = request.targetObjectId();
                if (targetId == null && published != null)
                    targetId =
                            published.relations().stream()
                                    .filter(
                                            relation ->
                                                    request.fieldId().equals(relation.fieldId()))
                                    .map(Relation::targetObjectId)
                                    .findFirst()
                                    .orElse(null);
                if (targetId != null) {
                    Definition targetDefinition = objects.getPublished(targetId);
                    TableBinding binding = ObjectTables.main(targetDefinition);
                    DatabaseMetadata.Table targetTable =
                            database.readTable(binding.schemaName(), targetDefinition.tableName())
                                    .orElse(null);
                    if (targetTable != null)
                        target = TableBindingService.key(targetTable).nativeType();
                }
            } else target = FieldStorage.sqlType(next, options);
        } catch (ServiceException | IllegalArgumentException ignored) {
            // 目标配置不完整时保留真实列事实，不能用臆测的物理类型替代检查结果。
        }
        Boolean ddl = null;
        String explanation = "目标结构尚不能确定，请完善配置并重新检查；当前检查和保存草稿都不会执行 DDL。";
        if (target != null && original == null && actual == null) {
            ddl = true;
            explanation = "首次发布时创建物理列；当前检查和保存草稿都不会创建或修改物理列。";
        } else if (actual != null && target != null && original != null) {
            boolean typeChange =
                    !canonicalType(FieldStorage.sqlType(original.field(), original.options()))
                            .equals(canonicalType(target));
            boolean conversionDdl =
                    candidate != null
                            && !FieldConversionActionEnum.KEEP_COLUMN.matches(
                                    candidate.change().action());
            boolean configurationDdl =
                    request.targetRequired() != null
                                    && !Objects.equals(
                                            request.targetRequired(), original.field().required())
                            || request.targetUnique() != null
                                    && !Objects.equals(
                                            request.targetUnique(), original.field().unique())
                            || !Objects.equals(
                                    options.defaultValue(), original.options().defaultValue())
                            || !Objects.equals(options.minimum(), original.options().minimum())
                            || !Objects.equals(options.maximum(), original.options().maximum())
                            || !Objects.equals(options.pattern(), original.options().pattern());
            ddl = typeChange || conversionDdl || configurationDdl || selectionDdl;
            explanation =
                    typeChange
                            ? "发布时将实际物理列类型从 "
                                    + actual.nativeType()
                                    + " 改为 "
                                    + target
                                    + "，会执行 ALTER COLUMN TYPE；数据按本次确认方案保留或清空。"
                            : ddl
                                    ? "新旧物理类型相同，但发布转换或约束调整仍会执行列默认值、必填、索引或约束等 DDL；具体步骤以发布计划为准。"
                                    : "本次字段配置不改变物理列类型或数据库约束，不需要列结构 DDL；数据处理方式以本次预检结果为准。";
            if (result.decision() == Decision.BLOCKED)
                explanation = "当前被阻止，不会执行。解除阻碍并通过复检后：" + explanation;
        }
        return new Storage(
                location.binding().schemaName(),
                location.table(),
                column(location),
                actual == null ? null : actual.nativeType(),
                target,
                actual == null ? null : actual.nullable(),
                actual == null ? null : actual.defaultExpression(),
                actual == null
                        ? null
                        : actual.primaryKeyPosition() != null && actual.primaryKeyPosition() > 0,
                actual == null
                        ? null
                        : actual.generatedKind() != null && !actual.generatedKind().isBlank()
                                || actual.identityKind() != null
                                        && !actual.identityKind().isBlank(),
                ddl,
                explanation);
    }

    private String canonicalType(String type) {
        return type.toLowerCase(Locale.ROOT)
                .trim()
                .replace("character varying", "varchar")
                .replaceAll("\\s*,\\s*", ",");
    }

    /** 草稿确认前直接读取发布基线；分页只影响展示，包含逻辑删除行并在服务端遮蔽敏感值。 */
    @Override
    public FieldConversions.Page rows(
            String objectId, String detailId, String fieldId, int pageNo, int pageSize) {
        validator.id(objectId, "对象 ID");
        validator.id(fieldId, "字段 ID");
        if (detailId != null) validator.id(detailId, "明细 ID");
        if (pageNo < 1
                || pageSize < 1
                || pageSize > 50
                || (long) (pageNo - 1) * pageSize > Integer.MAX_VALUE) throw invalid("影响记录分页范围无效");
        return transaction.execute(
                status -> readRows(objectId, detailId, fieldId, pageNo, pageSize));
    }

    @Override
    public FieldConversions.Page rows(RowsRequest request) {
        if (request == null) throw invalid("缺少字段切换参数");
        Request switchRequest =
                new Request(
                        request.objectId(),
                        request.detailId(),
                        request.fieldId(),
                        request.targetType(),
                        request.length(),
                        request.precision(),
                        request.scale(),
                        request.selection(),
                        request.targetObjectId(),
                        request.detachRelation(),
                        request.targetRequired(),
                        request.targetUnique(),
                        request.targetMinimum(),
                        request.targetMaximum(),
                        request.targetPattern(),
                        request.targetDefaultValue(),
                        request.targetOptions());
        validator.id(request.objectId(), "对象 ID");
        validator.id(request.fieldId(), "字段 ID");
        if (request.detailId() != null) validator.id(request.detailId(), "明细 ID");
        if (!FieldTypeEnum.containsCode(request.targetType())) throw invalid("目标字段类型无效");
        if (request.pageNo() < 1
                || request.pageSize() < 1
                || request.pageSize() > 50
                || (long) (request.pageNo() - 1) * request.pageSize() > Integer.MAX_VALUE)
            throw invalid("影响记录分页范围无效");
        return transaction.execute(
                status -> {
                    FieldConversionPlanner.Candidate[] selected = {null};
                    inspect(switchRequest, candidate -> selected[0] = candidate);
                    if (selected[0] != null)
                        return conversions.rows(selected[0], request.pageNo(), request.pageSize());
                    return readRows(
                            request.objectId(),
                            request.detailId(),
                            request.fieldId(),
                            request.pageNo(),
                            request.pageSize());
                });
    }

    private FieldConversions.Page readRows(
            String objectId, String detailId, String fieldId, int pageNo, int pageSize) {
        Definition published = designs.published(objectId);
        if (published == null) throw invalid("该字段尚未发布，没有可预览的历史值");
        Located field = locate(published, detailId, fieldId);
        if (field == null) throw invalid("字段不属于已发布对象或明细，或已停用");
        DatabaseMetadata.Table physical =
                database.readTable(field.binding().schemaName(), field.table())
                        .orElseThrow(() -> invalid("已发布物理表不存在，无法预览历史值"));
        String column = column(field);
        DatabaseMetadata.Column actual =
                physical.columns().stream()
                        .filter(value -> value.name().equals(column))
                        .findFirst()
                        .orElseThrow(() -> invalid("已发布物理列不存在，无法预览历史值"));
        if (!BaseDOColumns.differences(physical).isEmpty()
                || !FieldStorage.accepts(
                        FieldStorage.sqlType(field.field(), field.options()), actual.nativeType()))
            throw invalid("实际列与已发布定义不一致，请先处理结构漂移");
        FieldDefinition title =
                detailId == null
                        ? published.fields().stream()
                                .filter(value -> value.id().equals(published.titleFieldId()))
                                .findFirst()
                                .orElse(null)
                        : null;
        FieldOptions titleOptions =
                title == null
                        ? FieldOptions.defaults()
                        : published
                                .fieldOptions()
                                .getOrDefault(title.id(), FieldOptions.defaults());
        String titleColumn =
                title == null
                        ? field.binding().keyColumn()
                        : Objects.requireNonNullElse(titleOptions.columnName(), title.code());
        if (physical.columns().stream().noneMatch(value -> value.name().equals(titleColumn)))
            throw invalid("已发布记录标题列不存在，请先处理结构漂移");
        boolean titleMasked =
                title != null
                        && !DataClassificationEnum.NORMAL.matches(titleOptions.classification());
        boolean valueMasked =
                !DataClassificationEnum.NORMAL.matches(field.options().classification());
        commands.execute(PostgreSqlCommands.statementTimeout());
        FieldConversionMapper.Statement statement =
                new FieldConversionMapper.Statement(
                        field.binding().schemaName(),
                        field.table(),
                        column,
                        field.binding().keyColumn(),
                        titleColumn,
                        field.binding().parentColumn(),
                        actual.nativeType(),
                        fieldId,
                        "0",
                        (pageNo - 1) * pageSize,
                        pageSize);
        JsonNode counts;
        try {
            counts = json.readTree(mapper.counts(statement));
        } catch (java.io.IOException exception) {
            throw invalid("无法核实本列记录数，请重新预览");
        }
        if (counts == null || !counts.path("affected").isIntegralNumber())
            throw invalid("无法核实本列记录数，请重新预览");
        List<FieldConversions.Row> rows =
                mapper.rows(statement).stream()
                        .map(
                                value -> {
                                    JsonNode row;
                                    try {
                                        row = json.readTree(value);
                                    } catch (java.io.IOException exception) {
                                        throw invalid("无法读取字段历史值，请重新预览");
                                    }
                                    String oldValue = row.path("oldValue").asText("");
                                    return new FieldConversions.Row(
                                            row.path("id").asText(),
                                            titleMasked ? "••••" : row.path("title").asText(""),
                                            row.path("parentId").asText(null),
                                            valueMasked ? "••••" : oldValue,
                                            row.path("deleted").asBoolean(),
                                            null,
                                            null);
                                })
                        .toList();
        return new FieldConversions.Page(rows, counts.path("affected").asLong(), pageNo, pageSize);
    }

    private Result inspect(
            Request request,
            java.util.function.Consumer<FieldConversionPlanner.Candidate> candidateSink) {
        Definition current = designs.definition(request.objectId());
        Located selected = locate(current, request.detailId(), request.fieldId());
        if (selected == null) throw invalid("字段不属于指定对象或明细，或已停用");
        Definition previous = designs.published(request.objectId());
        Located original =
                previous == null ? null : locate(previous, request.detailId(), request.fieldId());
        if (original == null)
            return result(
                    request,
                    selected,
                    DeploymentState.UNPUBLISHED,
                    null,
                    null,
                    Decision.UNPUBLISHED,
                    "该字段尚未发布，没有可核实的已发布物理列；本次可调整设计，发布时仍会检查实际结构。",
                    List.of());
        DatabaseMetadata.Table physical =
                database.readTable(original.binding().schemaName(), original.table()).orElse(null);
        String column = column(original);
        DatabaseMetadata.Column actual =
                physical == null
                        ? null
                        : physical.columns().stream()
                                .filter(c -> c.name().equals(column))
                                .findFirst()
                                .orElse(null);
        if (actual == null)
            return result(
                    request,
                    original,
                    DeploymentState.MISSING_COLUMN,
                    null,
                    null,
                    Decision.BLOCKED,
                    "已发布字段的物理表或列缺失，无法确认历史数据；请先处理结构漂移。",
                    List.of());
        commands.execute(PostgreSqlCommands.statementTimeout());
        FieldConversionMapper.Statement statement =
                new FieldConversionMapper.Statement(
                        original.binding().schemaName(),
                        original.table(),
                        column,
                        original.binding().keyColumn(),
                        original.binding().keyColumn(),
                        original.binding().parentColumn(),
                        actual.nativeType(),
                        request.fieldId(),
                        "0",
                        0,
                        1);
        JsonNode counts;
        try {
            counts = json.readTree(mapper.counts(statement));
        } catch (java.io.IOException exception) {
            throw invalid("无法核实本列记录数，请重新预检");
        }
        if (counts == null
                || !counts.path("rows").isIntegralNumber()
                || !counts.path("affected").isIntegralNumber()
                || !counts.path("deleted").isIntegralNumber()) throw invalid("无法核实本列记录数，请重新预检");
        long rows = counts.path("rows").asLong(), values = counts.path("affected").asLong();
        long deleted = counts.path("deleted").asLong();
        List<Impact> impacts = new ArrayList<>();
        try {
            boolean detaching =
                    Boolean.TRUE.equals(request.detachRelation())
                            && FieldTypeEnum.REFERENCE.matches(original.field().type())
                            && !FieldTypeEnum.REFERENCE.matches(request.targetType());
            FieldDefinition next =
                    validator.field(
                            new FieldDefinition(
                                    request.fieldId(),
                                    request.fieldId(),
                                    selected.field().code(),
                                    selected.field().name(),
                                    request.targetType(),
                                    request.length(),
                                    request.precision(),
                                    request.scale(),
                                    request.targetRequired() == null
                                            ? detaching ? false : selected.field().required()
                                            : request.targetRequired(),
                                    request.targetUnique() == null
                                            ? detaching ? false : selected.field().unique()
                                            : request.targetUnique(),
                                    selected.field().sort()),
                            request.fieldId());
            FieldOptions options = targetOptions(selected, request);
            if (!BaseDOColumns.differences(physical).isEmpty())
                throw invalid("实际表的审计列或主键结构不完整，请先处理结构漂移");
            if (!conversions.ordinary(original.field(), original.options())
                    || !conversions.ordinary(next, options)
                    || original.binding().adopted()
                    || Boolean.TRUE.equals(original.binding().readOnly())
                    || column.equals(original.binding().keyColumn())
                    || BaseDOColumns.NAMES.contains(column)
                    || actual.generatedKind() != null && !actual.generatedKind().isBlank())
                throw invalid("主键、系统或生成字段、计算/编号字段以及纳管/只读列不能通过此入口原地转换，请使用对应配置入口或新增字段");
            if (!FieldStorage.accepts(
                    FieldStorage.sqlType(original.field(), original.options()),
                    actual.nativeType())) throw invalid("实际列类型与已发布定义不一致，请先处理结构漂移");
            List<Relation> relations = new ArrayList<>(current.relations());
            Relation oldRelation =
                    previous.relations().stream()
                            .filter(r -> request.fieldId().equals(r.fieldId()))
                            .findFirst()
                            .orElse(null);
            if (!FieldTypeEnum.REFERENCE.matches(next.type()) && oldRelation != null) {
                if (!Boolean.TRUE.equals(request.detachRelation())
                        || !FieldTypeEnum.REFERENCE.matches(original.field().type())
                        || !(RelationTypeEnum.REFERENCE.matches(oldRelation.kind())
                                || RelationTypeEnum.ONE_TO_ONE.matches(oldRelation.kind())))
                    throw invalid("该列仍承担对象关系；仅显式单值引用可确认解除后转换，主从及关系生成列需在关系配置中处理");
                relations.removeIf(r -> request.fieldId().equals(r.fieldId()));
            }
            if (FieldTypeEnum.REFERENCE.matches(next.type())) {
                String target =
                        request.targetObjectId() == null && oldRelation != null
                                ? oldRelation.targetObjectId()
                                : request.targetObjectId();
                if (target == null) throw invalid("请选择引用的目标业务数据对象后重新预检");
                validator.id(target, "目标对象 ID");
                Definition targetDefinition = objects.getPublished(target);
                TableBinding targetBinding = ObjectTables.main(targetDefinition);
                DatabaseMetadata.Table targetTable =
                        database.readTable(targetBinding.schemaName(), targetDefinition.tableName())
                                .orElseThrow(() -> invalid("目标对象物理表不存在"));
                DatabaseMetadata.Column targetKey = TableBindingService.key(targetTable);
                if (!targetKey.name().equals(targetBinding.keyColumn()))
                    throw invalid("目标对象主键与已发布绑定不一致");
                options = optionsWithNative(options, targetKey.nativeType());
                if (oldRelation != null && !oldRelation.targetObjectId().equals(target)) {
                    if (!(RelationTypeEnum.REFERENCE.matches(oldRelation.kind())
                            || RelationTypeEnum.ONE_TO_ONE.matches(oldRelation.kind())))
                        throw invalid("主从关系不能通过字段类型切换更换目标对象");
                    relations.removeIf(r -> request.fieldId().equals(r.fieldId()));
                    relations.add(
                            new Relation(
                                    oldRelation.id(),
                                    oldRelation.code(),
                                    oldRelation.name(),
                                    oldRelation.kind(),
                                    target,
                                    request.fieldId(),
                                    null,
                                    next.required(),
                                    oldRelation.onDelete(),
                                    request.detailId()));
                }
                if (oldRelation == null) {
                    relations.removeIf(r -> request.fieldId().equals(r.fieldId()));
                    relations.add(
                            new Relation(
                                    "preview_" + request.fieldId(),
                                    "preview_reference",
                                    "待配置引用",
                                    "REFERENCE",
                                    target,
                                    request.fieldId(),
                                    null,
                                    next.required(),
                                    "RESTRICT",
                                    request.detailId()));
                }
            }
            // 默认值仅影响未来写入；目标配置使用草稿保存的同一规则验证。
            validateTargetOptions(next, options, request);
            Definition proposed = replace(current, request.detailId(), next, options, relations);
            Optional<FieldConversionPlanner.Candidate> conversion =
                    conversions.preview(proposed, previous).stream()
                            .filter(c -> c.change().fieldId().equals(request.fieldId()))
                            .findFirst();
            conversion.ifPresent(candidateSink);
            boolean clears =
                    conversion.isPresent()
                            && FieldConversionActionEnum.CLEAR_COLUMN.matches(
                                    conversion.orElseThrow().change().action());
            String conversionRule =
                    conversion
                            .map(value -> value.change().conversionRule())
                            .filter(value -> !value.isBlank())
                            .orElse(
                                    clears
                                            ? "本次不支持直接保留旧值转换；继续时只清空本列旧值，记录及其他列保留。"
                                            : preservation(original, next, options));
            if (conversion.isPresent()) impacts.addAll(conversion.orElseThrow().change().impacts());
            else {
                if (!Objects.equals(
                        FieldStorage.sqlType(original.field(), original.options()),
                        FieldStorage.sqlType(next, options)))
                    for (String dependency : mapper.generatedDependents(statement))
                        impacts.add(
                                impact(
                                        request,
                                        original,
                                        "物理计算列 / " + dependency,
                                        "该物理计算列仍依赖本列，请先处理生成列依赖或新增替代字段"));
                List<FieldConversionDependencyInspector> available =
                        inspectors.orderedStream().toList();
                if (available.isEmpty()) throw invalid("字段依赖检查服务不可用");
                for (FieldConversionDependencyInspector inspector : available)
                    impacts.addAll(
                            inspector.inspect(
                                    previous, proposed, Set.of(request.fieldId()), Set.of()));
            }
            if (impacts.stream().anyMatch(Impact::blocking))
                return result(
                        request,
                        original,
                        DeploymentState.DEPLOYED,
                        rows,
                        values,
                        Decision.BLOCKED,
                        "存在必须先处理的数据、配置或依赖影响，请按下面的具体位置处理后再切换。",
                        impacts,
                        deleted,
                        conversion.map(c -> c.change().failedRows()).orElse(null),
                        conversionRule,
                        conversion.map(c -> c.change().conflicts()).orElse(List.of()));
            return result(
                    request,
                    original,
                    DeploymentState.DEPLOYED,
                    rows,
                    values,
                    clears && values > 0 ? Decision.CLEAR_COLUMN : Decision.PRESERVE,
                    clears
                            ? values > 0
                                    ? (conversion.isPresent()
                                                    && conversion
                                                                    .orElseThrow()
                                                                    .change()
                                                                    .failedRows()
                                                            > 0
                                            ? "本列有 "
                                                    + conversion.orElseThrow().change().failedRows()
                                                    + " 条值不能按保留规则转换。"
                                                    + conversionRule
                                                    + " 如继续，发布时需确认清空本列全部 "
                                                    + values
                                                    + " 条已有值；记录及其他列保留。"
                                            : "发布时需要明确确认：仅清空本列的 "
                                                    + values
                                                    + " 条已有值（含逻辑删除记录）；记录及其他列保留。")
                                    : "已核实本列没有非 NULL 值，可转换；记录及其他列保留。"
                            : conversion.isPresent()
                                            && FieldConversionActionEnum.PRESERVE_VALUES.matches(
                                                    conversion.orElseThrow().change().action())
                                    ? "本列 " + values + " 条已有值将按规则完整保留。" + conversionRule
                                    : conversion.isPresent()
                                                    && FieldConversionActionEnum.KEEP_COLUMN
                                                            .matches(
                                                                    conversion
                                                                            .orElseThrow()
                                                                            .change()
                                                                            .action())
                                            ? "本列 " + values + " 条已有值保持不变；" + conversionRule
                                            : preservation(original, next, options),
                    impacts,
                    deleted,
                    conversion.map(c -> c.change().failedRows()).orElse(null),
                    conversionRule,
                    conversion.map(c -> c.change().conflicts()).orElse(List.of()));
        } catch (ServiceException exception) {
            impacts.add(impact(request, original, "字段配置", exception.getMessage()));
            return result(
                    request,
                    original,
                    DeploymentState.DEPLOYED,
                    rows,
                    values,
                    Decision.BLOCKED,
                    exception.getMessage(),
                    impacts,
                    deleted);
        }
    }

    private String preservation(Located original, FieldDefinition next, FieldOptions options) {
        SelectionFields.Source source = SelectionFields.source(next, options);
        if (source != null
                && source.migrationMap() != null
                && !source.migrationMap().isEmpty()
                && !SelectionFields.identity(original.field(), original.options())
                        .equals(SelectionFields.identity(next, options)))
            return "本列将按已配置的值映射迁移为新来源的选项值，不执行整列清空；发布时仍会检查映射完整性和目标值。";
        String from = FieldStorage.sqlType(original.field(), original.options());
        String to = FieldStorage.sqlType(next, options);
        if (from.equals(to)) return "新旧字段使用相同的物理存储类型，本列已有值保持不变，界面按新字段配置展示；发布时仍会复查依赖与结构。";
        return "本次扩展字段可容纳的文本长度或数值范围，数据库会将原值按新类型保存，不截断、不舍入、不清空；发布时仍会复查数据与依赖。";
    }

    private FieldOptions targetOptions(Located selected, Request request) {
        FieldOptions old = selected.options();
        SelectionFields.Source source = request.selection();
        if (source == null
                && (request.targetType().equals(selected.field().type())
                        || Set.of(
                                                FieldTypeEnum.SELECT.getCode(),
                                                FieldTypeEnum.MULTI_SELECT.getCode())
                                        .contains(request.targetType())
                                && Set.of(
                                                FieldTypeEnum.SELECT.getCode(),
                                                FieldTypeEnum.MULTI_SELECT.getCode())
                                        .contains(selected.field().type())))
            source = old.selection();
        // 预检只推演物理列与依赖影响，不落库。请求里不带字段对象规则（抽屉里可能已经改过），拿库里的旧规则去配
        // 新的默认值、新的类型只会制造误拦（例如原有公式默认值 + 本次改成自定义默认值）。所以推演用的目标配置
        // 显式不带规则；规则本身在保存对象时按新类型校验，且不会因预检而变动。
        return FieldOptions.copyOf(old)
                .defaultValue(request.targetDefaultValue())
                .pattern(request.targetPattern())
                .minimum(request.targetMinimum())
                .maximum(request.targetMaximum())
                .options(
                        source == null || SelectionSourceEnum.LOCAL_OPTIONS.matches(source.kind())
                                ? request.targetOptions() == null
                                        ? old.options()
                                        : request.targetOptions()
                                : List.of())
                .expression(null)
                .resultType(null)
                .resolver(null)
                .nativeType(null)
                .selection(source)
                .calculation(null)
                .autoNumber(null)
                .rules(null)
                .build();
    }

    private boolean pendingDictionary(Request request) {
        return request.selection() != null
                && SelectionSourceEnum.SYSTEM_DICTIONARY.matches(request.selection().kind())
                && request.selection().dictionaryType() == null;
    }

    /** 来源切换后才出现字典选择器；仅预检允许这一中间态，其余来源结构仍完整校验。 */
    private void validateTargetOptions(
            FieldDefinition next, FieldOptions options, Request request) {
        if (!pendingDictionary(request)) {
            designs.validateFieldOptions(next, options);
            return;
        }
        SelectionFields.Source source = request.selection();
        SelectionFields.Source validationSource =
                new SelectionFields.Source(
                        source.kind(),
                        source.directory(),
                        "__field_switch_pending__",
                        source.rootIds(),
                        source.includeDescendants(),
                        source.organizationTypes(),
                        source.defaultMode(),
                        source.migrationMap());
        // 占位值只用于必填格式校验，不进入候选定义、持久化、实际值映射或返回结果。
        SelectionFields.validate(
                next,
                FieldOptions.copyOf(options)
                        .selection(validationSource)
                        .calculation(null)
                        .autoNumber(null)
                        .build());
    }

    private FieldOptions optionsWithNative(FieldOptions o, String nativeType) {
        return FieldOptions.copyOf(o)
                .nativeType(nativeType)
                .calculation(null)
                .autoNumber(null)
                .build();
    }

    private Located locate(Definition definition, String detailId, String fieldId) {
        if (detailId == null)
            return definition.fields().stream()
                    .filter(f -> f.id().equals(fieldId))
                    .filter(
                            f ->
                                    !MemberStateEnum.INACTIVE.matches(
                                            definition
                                                    .fieldOptions()
                                                    .getOrDefault(f.id(), FieldOptions.defaults())
                                                    .state()))
                    .map(
                            f ->
                                    new Located(
                                            f,
                                            definition
                                                    .fieldOptions()
                                                    .getOrDefault(f.id(), FieldOptions.defaults()),
                                            definition.tableName(),
                                            ObjectTables.main(definition)))
                    .findFirst()
                    .orElse(null);
        for (Detail detail : definition.details())
            if (detail.id().equals(detailId) && !MemberStateEnum.INACTIVE.matches(detail.state()))
                return detail.fields().stream()
                        .filter(f -> f.id().equals(fieldId))
                        .filter(
                                f ->
                                        !MemberStateEnum.INACTIVE.matches(
                                                detail.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(), FieldOptions.defaults())
                                                        .state()))
                        .map(
                                f ->
                                        new Located(
                                                f,
                                                detail.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(), FieldOptions.defaults()),
                                                detail.tableName(),
                                                ObjectTables.detail(definition, detail)))
                        .findFirst()
                        .orElse(null);
        return null;
    }

    private String column(Located field) {
        return Objects.requireNonNullElse(field.options().columnName(), field.field().code());
    }

    private Definition replace(
            Definition d,
            String detailId,
            FieldDefinition next,
            FieldOptions options,
            List<Relation> relations) {
        Map<String, FieldOptions> mainOptions = new HashMap<>(d.fieldOptions());
        if (detailId == null) mainOptions.put(next.id(), options);
        List<Detail> details =
                d.details().stream()
                        .map(
                                t -> {
                                    if (!t.id().equals(detailId)) return t;
                                    Map<String, FieldOptions> changed =
                                            new HashMap<>(t.fieldOptions());
                                    changed.put(next.id(), options);
                                    return new Detail(
                                            t.id(),
                                            t.code(),
                                            t.name(),
                                            t.tableName(),
                                            t.state(),
                                            t.fields().stream()
                                                    .map(f -> f.id().equals(next.id()) ? next : f)
                                                    .toList(),
                                            changed,
                                            t.indexes(),
                                            t.binding());
                                })
                        .toList();
        return new Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                d.readOnly(),
                d.titleFieldId(),
                d.settings(),
                detailId == null
                        ? d.fields().stream().map(f -> f.id().equals(next.id()) ? next : f).toList()
                        : d.fields(),
                mainOptions,
                relations,
                d.indexes(),
                details,
                d.mainBinding());
    }

    private Impact impact(Request request, Located field, String location, String message) {
        return new Impact(
                request.fieldId(),
                FieldConversionDependencyInspector.SourceKind.OBJECT.name(),
                request.objectId(),
                field.field().name(),
                location,
                message,
                "/nocode/object/editor?id=" + request.objectId(),
                true);
    }

    private Result result(
            Request r,
            Located field,
            DeploymentState state,
            Long rows,
            Long values,
            Decision decision,
            String explanation,
            List<Impact> impacts) {
        return result(r, field, state, rows, values, decision, explanation, impacts, null);
    }

    private Result result(
            Request r,
            Located field,
            DeploymentState state,
            Long rows,
            Long values,
            Decision decision,
            String explanation,
            List<Impact> impacts,
            Long deleted) {
        return result(
                r, field, state, rows, values, decision, explanation, impacts, deleted, null, null);
    }

    private Result result(
            Request r,
            Located field,
            DeploymentState state,
            Long rows,
            Long values,
            Decision decision,
            String explanation,
            List<Impact> impacts,
            Long deleted,
            Long failedRows,
            String conversionRule) {
        return result(
                r,
                field,
                state,
                rows,
                values,
                decision,
                explanation,
                impacts,
                deleted,
                failedRows,
                conversionRule,
                List.of());
    }

    private Result result(
            Request r,
            Located field,
            DeploymentState state,
            Long rows,
            Long values,
            Decision decision,
            String explanation,
            List<Impact> impacts,
            Long deleted,
            Long failedRows,
            String conversionRule,
            List<Conflict> conflicts) {
        return new Result(
                r.objectId(),
                r.detailId(),
                r.fieldId(),
                field.field().name(),
                field.field().type(),
                r.targetType(),
                state,
                rows,
                values,
                decision,
                pendingDictionary(r)
                        ? explanation + " 目标公共字典尚未选择；选择后需重新预检，发布时再次校验目标来源与数据。"
                        : explanation,
                impacts.stream().distinct().toList(),
                deleted,
                failedRows,
                conversionRule,
                conflicts);
    }
}
