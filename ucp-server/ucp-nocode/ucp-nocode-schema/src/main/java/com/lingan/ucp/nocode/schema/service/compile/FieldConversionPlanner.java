package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.BaseDOColumns;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldConversionDependencyInspector.Impact;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper;
import com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.TableDesign;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 原地转换的只读预检与受控执行。仅发布事务持有表锁并复核计划后可以清空原列。 */
@Component
public class FieldConversionPlanner {
    private static final Set<FieldTypeEnum> STORED_TYPES =
            EnumSet.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.TEXTAREA,
                    FieldTypeEnum.RICH_TEXT,
                    FieldTypeEnum.URL,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DECIMAL,
                    FieldTypeEnum.MONEY,
                    FieldTypeEnum.PERCENT,
                    FieldTypeEnum.BOOLEAN,
                    FieldTypeEnum.DATE,
                    FieldTypeEnum.DATETIME,
                    FieldTypeEnum.TIME,
                    FieldTypeEnum.UUID,
                    FieldTypeEnum.SELECT,
                    FieldTypeEnum.MULTI_SELECT,
                    FieldTypeEnum.ORGANIZATION,
                    FieldTypeEnum.DEPARTMENT,
                    FieldTypeEnum.USER,
                    FieldTypeEnum.POST,
                    FieldTypeEnum.USER_GROUP,
                    FieldTypeEnum.REFERENCE);

    @Resource private SchemaTableDefinitions definitions;
    @Resource private DatabaseMetadataReader database;
    @Resource private FieldConversionMapper mapper;
    @Resource private SelectionMigrationMapper selectionMapper;
    @Resource private ObjectMapper json;
    @Resource private ObjectProvider<FieldConversionDependencyInspector> inspectors;
    @Resource private ObjectProvider<SelectionTargetValidator> selectionTargets;

    /** 执行参数不发给客户端；客户端只能提交计划中的稳定字段 ID。 */
    public record Candidate(
            FieldConversions.Change change,
            FieldConversionMapper.Statement statement,
            boolean titleMasked,
            List<String> releasedRelations,
            FieldConversionPreservation.Rule preservation,
            FieldConversionMapper.Assessment assessment) {}

    public List<Candidate> preview(Definition current, Definition previous) {
        if (previous == null) return List.of();
        Map<ObjectTables.Ref, TableDesign> oldTables =
                definitions.tables(previous, true).stream()
                        .collect(Collectors.toMap(TableDesign::ref, table -> table));
        List<Candidate> candidates = new ArrayList<>();
        for (TableDesign table : definitions.tables(current)) {
            TableDesign old = oldTables.get(table.ref());
            if (old == null
                    || table.binding().adopted()
                    || old.binding().adopted()
                    || Boolean.TRUE.equals(table.binding().readOnly())) continue;
            DatabaseMetadata.Table actual =
                    database.readTable(table.schema(), table.name()).orElse(null);
            if (actual == null || !BaseDOColumns.differences(actual).isEmpty()) continue;
            for (FieldDefinition field : table.fields()) {
                FieldDefinition before =
                        old.fields().stream()
                                .filter(f -> f.id().equals(field.id()))
                                .findFirst()
                                .orElse(null);
                if (before == null) continue;
                FieldOptions fromOptions = definitions.options(old, before);
                FieldOptions toOptions = definitions.options(table, field);
                String column = SchemaTableDefinitions.columnName(field, toOptions);
                if (!column.equals(SchemaTableDefinitions.columnName(before, fromOptions))
                        || !ordinary(before, fromOptions)
                        || !ordinary(field, toOptions)
                        || column.equals(table.binding().keyColumn())
                        || BaseDOColumns.NAMES.contains(column)) continue;
                if (actual.columns().stream()
                        .noneMatch(c -> c.name().equals(column) && c.generatedKind().isEmpty()))
                    continue;
                String from = SchemaTableDefinitions.sqlType(before, fromOptions);
                String to = SchemaTableDefinitions.sqlType(field, toOptions);
                boolean selectionChanged =
                        !SelectionFields.identity(before, fromOptions)
                                .equals(SelectionFields.identity(field, toOptions));
                boolean toRelation =
                        FieldTypeEnum.REFERENCE.matches(field.type())
                                && !FieldTypeEnum.REFERENCE.matches(before.type());
                boolean fromRelation =
                        FieldTypeEnum.REFERENCE.matches(before.type())
                                && !FieldTypeEnum.REFERENCE.matches(field.type());
                Relation previousRelation =
                        previous.relations().stream()
                                .filter(r -> field.id().equals(r.fieldId()))
                                .findFirst()
                                .orElse(null);
                Relation currentRelation =
                        current.relations().stream()
                                .filter(r -> field.id().equals(r.fieldId()))
                                .findFirst()
                                .orElse(null);
                boolean retargetRelation =
                        FieldTypeEnum.REFERENCE.matches(before.type())
                                && FieldTypeEnum.REFERENCE.matches(field.type())
                                && previousRelation != null
                                && currentRelation != null
                                && previousRelation.id().equals(currentRelation.id())
                                && !previousRelation
                                        .targetObjectId()
                                        .equals(currentRelation.targetObjectId());
                boolean storageChange = !from.equals(to);
                FieldConversionPreservation.Rule rule =
                        FieldConversionPreservation.rule(
                                before, fromOptions, field, toOptions, from, to);
                // 同一 SQL 类型也可能代表不同的业务值身份，例如金额/百分比或不同目录的 ID。
                boolean semanticChange = !before.type().equals(field.type());
                boolean valueConstraintChanged =
                        !Objects.equals(fromOptions.minimum(), toOptions.minimum())
                                || !Objects.equals(fromOptions.maximum(), toOptions.maximum())
                                || !Objects.equals(fromOptions.pattern(), toOptions.pattern());
                boolean selectionConfigChanged =
                        !Objects.equals(fromOptions.options(), toOptions.options())
                                || !Objects.equals(fromOptions.selection(), toOptions.selection());
                boolean dataChange =
                        storageChange
                                || semanticChange
                                || selectionChanged
                                || toRelation
                                || fromRelation
                                || retargetRelation;
                boolean constraintChange =
                        valueConstraintChanged
                                || !Objects.equals(before.required(), field.required())
                                || !Objects.equals(before.unique(), field.unique())
                                || !Objects.equals(
                                        fromOptions.defaultValue(), toOptions.defaultValue())
                                || selectionConfigChanged;
                if (!dataChange && !constraintChange) continue;
                // 用户已选择完整值映射时沿用原迁移协议，不能悄悄替换为清空。
                SelectionFields.Source source = SelectionFields.source(field, toOptions);
                if (selectionChanged
                        && source != null
                        && source.migrationMap() != null
                        && !source.migrationMap().isEmpty()) continue;
                FieldDefinition title =
                        table.detailId() == null
                                ? old.fields().stream()
                                        .filter(f -> f.id().equals(previous.titleFieldId()))
                                        .findFirst()
                                        .orElse(null)
                                : null;
                String titleColumn =
                        title == null
                                ? table.binding().keyColumn()
                                : SchemaTableDefinitions.columnName(
                                        title, definitions.options(old, title));
                boolean titleMasked =
                        title != null
                                && !DataClassificationEnum.NORMAL.matches(
                                        definitions.options(old, title).classification());
                FieldConversionMapper.Statement statement =
                        new FieldConversionMapper.Statement(
                                table.schema(),
                                table.name(),
                                column,
                                table.binding().keyColumn(),
                                titleColumn,
                                table.detailId() == null ? null : table.binding().parentColumn(),
                                to,
                                field.id(),
                                "0",
                                0,
                                50);
                boolean selectionShapeChange =
                        rule != null
                                && ("SINGLE_TO_MULTI".equals(rule.code())
                                        || "MULTI_TO_SINGLE".equals(rule.code()));
                boolean required =
                        Boolean.TRUE.equals(field.required())
                                || current.relations().stream()
                                        .anyMatch(
                                                r ->
                                                        field.id().equals(r.fieldId())
                                                                && (Boolean.TRUE.equals(
                                                                                r.required())
                                                                        || RelationTypeEnum
                                                                                .MASTER_DETAIL
                                                                                .matches(
                                                                                        r.kind())));
                boolean oldRequired =
                        Boolean.TRUE.equals(before.required())
                                || previous.relations().stream()
                                        .anyMatch(
                                                r ->
                                                        field.id().equals(r.fieldId())
                                                                && (Boolean.TRUE.equals(
                                                                                r.required())
                                                                        || RelationTypeEnum
                                                                                .MASTER_DETAIL
                                                                                .matches(
                                                                                        r.kind())));
                String ruleCode =
                        !dataChange
                                ? "IDENTITY"
                                : rule != null
                                                && (!selectionChanged || selectionShapeChange)
                                                && !toRelation
                                                && !fromRelation
                                                && !retargetRelation
                                        ? rule.code()
                                        : "UNSUPPORTED";
                SelectionValidation selection =
                        selectionValidation(
                                table,
                                before,
                                fromOptions,
                                field,
                                toOptions,
                                statement,
                                dataChange,
                                selectionConfigChanged);
                FieldTypeEnum targetType = FieldTypeEnum.fromCode(field.type());
                boolean numericTarget =
                        Set.of(FieldTypeEnum.INTEGER, FieldTypeEnum.DECIMAL, FieldTypeEnum.MONEY)
                                .contains(targetType);
                boolean textTarget =
                        Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA, FieldTypeEnum.RICH_TEXT)
                                .contains(targetType);
                FieldConversionMapper.Assessment assessment =
                        new FieldConversionMapper.Assessment(
                                statement,
                                ruleCode,
                                dataChange
                                        ? FieldConversionActionEnum.PRESERVE_VALUES.getCode()
                                        : FieldConversionActionEnum.KEEP_COLUMN.getCode(),
                                rule != null && rule.targetInteger(),
                                rule == null ? 0 : rule.scale(),
                                rule == null ? 0 : rule.integerDigits(),
                                rule == null ? 0 : rule.limitLength(),
                                required && (dataChange || !oldRequired),
                                Boolean.TRUE.equals(field.unique())
                                        && (dataChange || !Boolean.TRUE.equals(before.unique())),
                                numericTarget,
                                textTarget,
                                selection.check(),
                                selection.removedOnly(),
                                FieldTypeEnum.MULTI_SELECT.matches(field.type()),
                                selection.validValues(),
                                dataChange
                                                || SchemaFieldConstraintChecks.tightened(
                                                        toOptions.minimum(),
                                                        fromOptions.minimum(),
                                                        true)
                                        ? toOptions.minimum()
                                        : null,
                                dataChange
                                                || SchemaFieldConstraintChecks.tightened(
                                                        toOptions.maximum(),
                                                        fromOptions.maximum(),
                                                        false)
                                        ? toOptions.maximum()
                                        : null,
                                dataChange
                                                || toOptions.pattern() != null
                                                        && !Objects.equals(
                                                                toOptions.pattern(),
                                                                fromOptions.pattern())
                                        ? toOptions.pattern()
                                        : null);
                JsonNode assessed = read(mapper.assess(assessment));
                JsonNode stats = read(mapper.statistics(statement));
                long failedRows = assessed.path("failedRows").asLong();
                List<FieldSwitchPreview.Conflict> conflicts = conflicts(assessed);
                boolean preserve =
                        dataChange
                                && !selection.unavailable()
                                && !"UNSUPPORTED".equals(ruleCode)
                                && failedRows == 0
                                && stats.path("affected").asLong() > 0;
                String action =
                        !dataChange
                                ? FieldConversionActionEnum.KEEP_COLUMN.getCode()
                                : preserve
                                        ? FieldConversionActionEnum.PRESERVE_VALUES.getCode()
                                        : FieldConversionActionEnum.CLEAR_COLUMN.getCode();
                assessment = withAction(assessment, action);
                List<Impact> impacts = new ArrayList<>();
                if (dataChange)
                    for (String dependent : mapper.generatedDependents(statement))
                        impacts.add(
                                local(
                                        current,
                                        field,
                                        "计算列 → " + dependent,
                                        "该计算列仍依赖本列，停用后保留的计算列也会限制转换；请先处理计算列结构，或新增替代字段"));
                if (selection.unavailable())
                    impacts.add(local(current, field, "选择来源", "历史选项种类超过 1000，无法完整核验；请先缩小或修复来源数据"));
                if (!dataChange && failedRows > 0)
                    for (FieldSwitchPreview.Conflict conflict : conflicts)
                        impacts.add(
                                local(
                                        current,
                                        field,
                                        "字段约束 → " + conflictName(conflict.code()),
                                        conflict.message() + "；请在已授权的业务入口修正冲突记录或调整目标约束"));
                if (dataChange
                        && required
                        && stats.path("rows").asLong() > 0
                        && (FieldConversionActionEnum.CLEAR_COLUMN.matches(action)
                                || assessed.path("requiredRows").asLong() > 0))
                    impacts.add(
                            local(current, field, "字段配置 → 必填", "转换结果仍有空值，与必填或主从归属冲突；请先补齐或调整约束"));
                List<String> releasedRelations = new ArrayList<>();
                for (Relation relation : previous.relations())
                    if (dataChange && field.id().equals(relation.fieldId())) {
                        if ((fromRelation || retargetRelation)
                                && (RelationTypeEnum.REFERENCE.matches(relation.kind())
                                        || RelationTypeEnum.ONE_TO_ONE.matches(relation.kind()))
                                && (fromRelation
                                        ? current.relations().stream()
                                                .noneMatch(r -> field.id().equals(r.fieldId()))
                                        : currentRelation != null
                                                && relation.id().equals(currentRelation.id())
                                                && relation.kind()
                                                        .equals(currentRelation.kind()))) {
                            releasedRelations.add(relation.id());
                            continue;
                        }
                        impacts.add(
                                local(
                                        current,
                                        field,
                                        "对象关系 → " + relation.name(),
                                        "原列仍承担已发布关系，请先处理关系后再转换字段"));
                    }
                boolean masked =
                        !DataClassificationEnum.NORMAL.matches(fromOptions.classification());
                candidates.add(
                        new Candidate(
                                new FieldConversions.Change(
                                        field.id(),
                                        table.detailId(),
                                        field.name(),
                                        table.title(),
                                        from,
                                        to,
                                        before.type(),
                                        field.type(),
                                        stats.path("affected").asLong(),
                                        stats.path("deleted").asLong(),
                                        masked,
                                        stats.path("fingerprint").asText(),
                                        impacts.isEmpty(),
                                        List.copyOf(impacts),
                                        action,
                                        !dataChange
                                                ? "保持本列已有值；仅调整目标约束或配置。默认值只用于未来新增记录。"
                                                : rule == null ? null : rule.description(),
                                        failedRows,
                                        conflicts),
                                statement,
                                titleMasked,
                                List.copyOf(releasedRelations),
                                preserve ? rule : null,
                                assessment));
            }
        }
        Set<String> fields =
                candidates.stream().map(c -> c.change().fieldId()).collect(Collectors.toSet());
        if (fields.isEmpty()) return candidates;
        List<FieldConversionDependencyInspector> available = inspectors.orderedStream().toList();
        if (available.isEmpty()) throw invalid("字段依赖检查服务不可用，暂不能发布转换");
        Set<String> clearedFields =
                candidates.stream()
                        .filter(
                                candidate ->
                                        FieldConversionActionEnum.CLEAR_COLUMN.matches(
                                                candidate.change().action()))
                        .filter(candidate -> candidate.change().affectedRows() > 0)
                        .map(candidate -> candidate.change().fieldId())
                        .collect(Collectors.toSet());
        List<Impact> impacts =
                available.stream()
                        .flatMap(i -> i.inspect(previous, current, fields, clearedFields).stream())
                        .toList();
        return candidates.stream()
                .map(
                        candidate -> {
                            FieldConversions.Change change = candidate.change();
                            List<Impact> all = new ArrayList<>(change.impacts());
                            impacts.stream()
                                    .filter(i -> change.fieldId().equals(i.fieldId()))
                                    .forEach(all::add);
                            all.sort(
                                    Comparator.comparing(Impact::sourceKind)
                                            .thenComparing(Impact::sourceId)
                                            .thenComparing(Impact::location)
                                            .thenComparing(Impact::message));
                            return new Candidate(
                                    new FieldConversions.Change(
                                            change.fieldId(),
                                            change.detailId(),
                                            change.fieldName(),
                                            change.sourceName(),
                                            change.fromType(),
                                            change.toType(),
                                            change.fromFieldType(),
                                            change.toFieldType(),
                                            change.affectedRows(),
                                            change.deletedRows(),
                                            change.masked(),
                                            change.fingerprint(),
                                            all.stream().noneMatch(Impact::blocking),
                                            List.copyOf(all),
                                            change.action(),
                                            change.conversionRule(),
                                            change.failedRows(),
                                            change.conflicts()),
                                    candidate.statement(),
                                    candidate.titleMasked(),
                                    candidate.releasedRelations(),
                                    candidate.preservation(),
                                    candidate.assessment());
                        })
                .toList();
    }

    /** 原地切换只覆盖普通存储字段，计算、编号和系统字段继续走各自协议。 */
    public boolean ordinary(FieldDefinition field, FieldOptions options) {
        return STORED_TYPES.contains(FieldTypeEnum.fromCode(field.type()))
                && !Boolean.TRUE.equals(options.primaryKey())
                && !Boolean.TRUE.equals(options.generated())
                && options.expression() == null
                && options.calculation() == null
                && options.autoNumber() == null;
    }

    private Impact local(
            Definition current, FieldDefinition field, String location, String message) {
        return new Impact(
                field.id(),
                FieldConversionDependencyInspector.SourceKind.OBJECT.name(),
                current.objectId(),
                current.objectName(),
                location,
                message,
                "/nocode/object/editor?id=" + current.objectId(),
                true);
    }

    private record SelectionValidation(
            boolean check, boolean unavailable, boolean removedOnly, String validValues) {}

    private SelectionValidation selectionValidation(
            TableDesign table,
            FieldDefinition before,
            FieldOptions fromOptions,
            FieldDefinition target,
            FieldOptions options,
            FieldConversionMapper.Statement statement,
            boolean dataChange,
            boolean selectionConfigChanged) {
        if (!FieldTypeEnum.SELECT.matches(target.type())
                && !FieldTypeEnum.MULTI_SELECT.matches(target.type()))
            return new SelectionValidation(false, false, false, "[]");
        if (!dataChange && !selectionConfigChanged)
            return new SelectionValidation(false, false, false, "[]");
        SelectionFields.Source source = SelectionFields.source(target, options);
        if (source == null) return new SelectionValidation(false, false, false, "[]");
        if (SelectionSourceEnum.LOCAL_OPTIONS.matches(source.kind())) {
            // 停用只限制以后新选；保留的选项编码仍是历史值的有效身份。
            List<String> codes =
                    Objects.requireNonNullElse(options.options(), List.<Option>of()).stream()
                            .map(Option::code)
                            .toList();
            if (!dataChange) {
                Set<String> originalCodes =
                        Objects.requireNonNullElse(fromOptions.options(), List.<Option>of())
                                .stream()
                                .map(Option::code)
                                .collect(Collectors.toSet());
                if (codes.containsAll(originalCodes))
                    return new SelectionValidation(false, false, false, "[]");
                originalCodes.removeAll(codes);
                return new SelectionValidation(
                        true, false, true, json.valueToTree(originalCodes).toString());
            }
            return new SelectionValidation(true, false, false, json.valueToTree(codes).toString());
        }
        SelectionMigrationMapper.Statement selectionStatement =
                new SelectionMigrationMapper.Statement(
                        table.schema(),
                        table.name(),
                        statement.column(),
                        statement.type(),
                        FieldTypeEnum.MULTI_SELECT.matches(before.type()),
                        FieldTypeEnum.MULTI_SELECT.matches(target.type()),
                        Boolean.TRUE.equals(target.required()),
                        "{}",
                        "0",
                        false);
        List<String> existing = selectionMapper.values(selectionStatement);
        if (existing.size() > 1000) return new SelectionValidation(false, true, false, "[]");
        SelectionTargetValidator validator = selectionTargets.getIfAvailable();
        if (validator == null) throw invalid("选择来源核验服务不可用，暂不能发布转换");
        List<String> valid = new ArrayList<>();
        for (String value : existing) {
            if (!dataChange) {
                try {
                    validator.validateTargets(before, fromOptions, List.of(value));
                } catch (com.lingan.ucp.framework.common.exception.ServiceException ignored) {
                    // 旧来源本就无效的值不因本次纯配置变更新增阻断。
                    valid.add(value);
                    continue;
                }
            }
            try {
                validator.validateTargets(target, options, List.of(value));
                valid.add(value);
            } catch (com.lingan.ucp.framework.common.exception.ServiceException ignored) {
                // 已失效编码由同一全列预检定位，不能自动改写为其他业务值。
            }
        }
        return new SelectionValidation(true, false, false, json.valueToTree(valid).toString());
    }

    private List<FieldSwitchPreview.Conflict> conflicts(JsonNode assessed) {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("CONVERSION", "旧值无法无损转换为目标类型");
        reasons.put("REQUIRED", "转换后仍有空值，不满足必填约束");
        reasons.put("MINIMUM", "转换后低于目标最小值");
        reasons.put("MAXIMUM", "转换后高于目标最大值");
        reasons.put("PATTERN", "转换后不符合目标格式");
        reasons.put("SELECTION", "旧选项编码在目标来源中不可用");
        reasons.put("UNIQUE", "转换后的值重复，不满足唯一约束");
        List<FieldSwitchPreview.Conflict> found = new ArrayList<>();
        for (Map.Entry<String, String> reason : reasons.entrySet()) {
            String key = reason.getKey().toLowerCase(Locale.ROOT) + "Rows";
            long count = assessed.path(key).asLong();
            if (count > 0)
                found.add(
                        new FieldSwitchPreview.Conflict(reason.getKey(), count, reason.getValue()));
        }
        return List.copyOf(found);
    }

    private FieldConversionMapper.Assessment withAction(
            FieldConversionMapper.Assessment value, String action) {
        return new FieldConversionMapper.Assessment(
                value.statement(),
                value.rule(),
                action,
                value.targetInteger(),
                value.scale(),
                value.integerDigits(),
                value.limitLength(),
                value.required(),
                value.unique(),
                value.numericTarget(),
                value.textTarget(),
                value.checkSelection(),
                value.selectionRemovedOnly(),
                value.newMultiple(),
                value.validValues(),
                value.minimum(),
                value.maximum(),
                value.pattern());
    }

    public Set<String> ids(List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> !FieldConversionActionEnum.KEEP_COLUMN.matches(c.change().action()))
                .map(c -> c.change().fieldId())
                .collect(Collectors.toSet());
    }

    public List<Check> checks(List<Candidate> candidates) {
        return candidates.stream()
                .flatMap(c -> c.change().impacts().stream())
                .map(
                        i ->
                                new Check(
                                        PublishCheckEnum.UNSUPPORTED_CHANGE.getCode(),
                                        i.sourceName() + " → " + i.location() + "：" + i.message(),
                                        i.blocking()))
                .toList();
    }

    /** 必须在原发布表锁内调用。即使数量不变，只要记录身份或原值变化也使确认失效。 */
    public void verify(
            List<FieldConversions.Change> expected,
            List<Candidate> actual,
            List<String> confirmed) {
        List<FieldConversions.Change> views = actual.stream().map(Candidate::change).toList();
        if (!Objects.equals(expected, views)) throw invalid("待转换的列值或依赖已变化，请重新预览并确认；本次未清空数据");
        if (actual.stream().anyMatch(c -> !c.change().clearAllowed()))
            throw invalid("请先处理字段转换的依赖或约束影响");
        Set<String> required =
                views.stream()
                        .filter(c -> FieldConversionActionEnum.CLEAR_COLUMN.matches(c.action()))
                        .filter(c -> c.affectedRows() > 0)
                        .map(FieldConversions.Change::fieldId)
                        .collect(Collectors.toSet());
        if (confirmed.size() != new HashSet<>(confirmed).size()
                || !required.equals(new HashSet<>(confirmed)))
            throw invalid("请确认发布计划中全部待清空字段；不能清空计划之外的字段");
    }

    /** 只更新授权列及必要审计字段，不调用业务更新事件；与后续 DDL、版本发布处于同一事务。 */
    public void apply(List<Candidate> candidates, long actor) {
        for (Candidate candidate : candidates) {
            if (FieldConversionActionEnum.KEEP_COLUMN.matches(candidate.change().action()))
                continue;
            FieldConversionMapper.Statement old = candidate.statement();
            FieldConversionMapper.Statement statement =
                    new FieldConversionMapper.Statement(
                            old.schema(),
                            old.table(),
                            old.column(),
                            old.keyColumn(),
                            old.titleColumn(),
                            old.parentColumn(),
                            old.type(),
                            old.fieldId(),
                            Long.toString(actor),
                            0,
                            50);
            for (String relation : candidate.releasedRelations()) {
                mapper.releaseReference(statement, relation);
                mapper.releaseReferenceUnique(statement, relation);
            }
            mapper.prepare(statement);
            if (candidate.preservation() != null) {
                FieldConversionPreservation.Rule rule = candidate.preservation();
                if (mapper.preservationFailures(
                                statement,
                                rule.code(),
                                rule.targetInteger(),
                                rule.scale(),
                                rule.integerDigits(),
                                rule.limitLength())
                        != 0) throw invalid("本列已有值不再全部满足保留转换规则，请重新预览");
                mapper.convertPreserving(statement, rule.code());
            } else {
                int cleared = mapper.clear(statement);
                if (cleared != candidate.change().affectedRows()) throw invalid("本列影响记录已变化，请重新预览");
                mapper.convert(statement);
            }
        }
    }

    public FieldConversions.Page rows(Candidate candidate, int pageNo, int pageSize) {
        if (pageNo < 1
                || pageSize < 1
                || pageSize > 50
                || (long) (pageNo - 1) * pageSize > Integer.MAX_VALUE) throw invalid("影响记录分页范围无效");
        FieldConversionMapper.Statement old = candidate.statement();
        FieldConversionMapper.Statement statement =
                new FieldConversionMapper.Statement(
                        old.schema(),
                        old.table(),
                        old.column(),
                        old.keyColumn(),
                        old.titleColumn(),
                        old.parentColumn(),
                        old.type(),
                        old.fieldId(),
                        "0",
                        (pageNo - 1) * pageSize,
                        pageSize);
        FieldConversionMapper.Assessment assessment =
                new FieldConversionMapper.Assessment(
                        statement,
                        candidate.assessment().rule(),
                        candidate.assessment().action(),
                        candidate.assessment().targetInteger(),
                        candidate.assessment().scale(),
                        candidate.assessment().integerDigits(),
                        candidate.assessment().limitLength(),
                        candidate.assessment().required(),
                        candidate.assessment().unique(),
                        candidate.assessment().numericTarget(),
                        candidate.assessment().textTarget(),
                        candidate.assessment().checkSelection(),
                        candidate.assessment().selectionRemovedOnly(),
                        candidate.assessment().newMultiple(),
                        candidate.assessment().validValues(),
                        candidate.assessment().minimum(),
                        candidate.assessment().maximum(),
                        candidate.assessment().pattern());
        List<FieldConversions.Row> rows =
                mapper.assessedRows(assessment).stream()
                        .map(
                                value -> {
                                    JsonNode row = read(value);
                                    String oldValue =
                                            row.path("oldValue").isNull()
                                                    ? null
                                                    : row.path("oldValue").asText();
                                    String newValue =
                                            row.path("newValue").isNull()
                                                    ? null
                                                    : row.path("newValue").asText();
                                    List<String> failureCodes = new ArrayList<>();
                                    row.path("failureCodes")
                                            .forEach(code -> failureCodes.add(code.asText()));
                                    String failureReason =
                                            failureCodes.isEmpty()
                                                    ? FieldConversionActionEnum.CLEAR_COLUMN
                                                                    .matches(
                                                                            candidate
                                                                                    .change()
                                                                                    .action())
                                                            ? "本行可按目标规则保留，但确认整列清空时也会清空本行"
                                                            : null
                                                    : failureCodes.stream()
                                                            .map(this::failureReason)
                                                            .collect(Collectors.joining("；"));
                                    return new FieldConversions.Row(
                                            row.path("id").asText(),
                                            candidate.titleMasked()
                                                    ? "••••"
                                                    : row.path("title").asText(""),
                                            row.path("parentId").asText(null),
                                            candidate.change().masked() && oldValue != null
                                                    ? "••••"
                                                    : oldValue,
                                            row.path("deleted").asBoolean(),
                                            candidate.change().masked() && newValue != null
                                                    ? "••••"
                                                    : newValue,
                                            failureReason,
                                            List.copyOf(failureCodes));
                                })
                        .toList();
        long total = read(mapper.assess(assessment)).path("reviewRows").asLong();
        return new FieldConversions.Page(rows, total, pageNo, pageSize);
    }

    private String failureReason(String code) {
        return switch (code) {
            case "CONVERSION" -> "旧值无法无损转换";
            case "REQUIRED" -> "转换后为空，不满足必填";
            case "MINIMUM" -> "低于目标最小值";
            case "MAXIMUM" -> "高于目标最大值";
            case "PATTERN" -> "不符合目标格式";
            case "SELECTION" -> "目标选项不可用";
            case "UNIQUE" -> "转换后值重复";
            default -> "目标约束不满足";
        };
    }

    private String conflictName(String code) {
        return switch (code) {
            case "CONVERSION" -> "无损转换";
            case "REQUIRED" -> "必填";
            case "MINIMUM" -> "最小值";
            case "MAXIMUM" -> "最大值";
            case "PATTERN" -> "正则格式";
            case "SELECTION" -> "选项编码";
            case "UNIQUE" -> "唯一";
            default -> "目标规则";
        };
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("无法读取字段转换影响，请重新检查");
        }
    }
}
