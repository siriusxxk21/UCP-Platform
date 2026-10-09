package com.lingan.ucp.nocode.schema.service.reconcile;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.ObjectReconciliation.*;
import com.lingan.ucp.nocode.api.ObjectTables;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDraftService;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** 将外部新增普通列、兼容扩容等差异映射成新版本。只更新对象元数据，不执行修复 DDL。 公共字段、主外键、索引、触发器和权限变化需要在原系统处理，不静默接受。 */
@Service
public class ObjectReconcileService {
    @Resource private PlatformTransactionManager manager;

    @Resource private ObjectDesignService designs;
    @Resource private ObjectDraftService drafts;
    @Resource private DataTableService tables;
    @Resource private SchemaCompiler compiler;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDraftMapper objects;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;
    private TransactionTemplate tx;

    /** 初始化结构核对使用的 JSON 与事务配置，核对结果应用到草稿时保持原子性。 */
    @PostConstruct
    void initialize() {
        this.tx = new TransactionTemplate(manager);
    }

    private record Change(String table, String fieldId, DatabaseMetadata.Column column) {}

    private record Analysis(Preview preview, List<Change> changes) {}

    public Preview preview(String id) {
        return tx.execute(s -> analyze(id).preview());
    }

    private Analysis analyze(String id) {
        com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO h = designs.head(id, false);
        DataCenter.Definition current = designs.definition(id);
        DataCenter.Definition published = designs.published(id);
        if (published == null) throw invalid("首次发布前没有结构基线，请重新纳管或修改草稿");
        ArrayList<DataCenter.Check> checks = new ArrayList<Check>();
        ArrayList<DataCenter.Step> steps = new ArrayList<Step>();
        ArrayList<ObjectReconcileService.Change> changes = new ArrayList<Change>();
        if (!ObjectStatusEnum.ACTIVE.matches(h.getStatus()))
            checks.add(new Check(PublishCheckEnum.DISABLED.getCode(), "请先启用对象", true));
        if (!compiler.physicalSignature(current).equals(compiler.physicalSignature(published)))
            checks.add(
                    new Check(
                            PublishCheckEnum.DRAFT_CHANGES.getCode(),
                            "当前草稿还有结构修改，请先处理这些修改再同步物理差异",
                            true));
        com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                store.deployment(h.getId());
        if (deployment == null) throw invalid("对象缺少发布基线");
        JsonNode baseline = tree(deployment.getStructureJson());
        for (Iterator<String> fields = baseline.fieldNames(); fields.hasNext(); ) {
            String name = fields.next();
            JsonNode old = DataTableService.comparableStructure(baseline.get(name));
            ObjectTables.Ref ref = ObjectTables.fromKey(name, h.getSchemaName());
            DatabaseMetadata.Table actual =
                    database.readTable(ref.schema(), ref.name()).orElse(null);
            TableBinding binding = ObjectTables.bindings(current).get(ref);
            if (actual == null) {
                checks.add(
                        new Check(PublishCheckEnum.TABLE_MISSING.getCode(), "物理表丢失：" + name, true));
                continue;
            }
            JsonNode now =
                    DataTableService.comparableStructure(tree(tables.capture(current)).path(name));
            for (String key : List.of("constraints", "indexes", "triggers", "security"))
                if (!old.path(key).equals(now.path(key)))
                    checks.add(
                            new Check(
                                    PublishCheckEnum.UNSUPPORTED_CHANGE.getCode(),
                                    name
                                            + " 的"
                                            + switch (key) {
                                                case "constraints" -> "约束";
                                                case "indexes" -> "索引";
                                                case "triggers" -> "触发器";
                                                default -> "数据库权限或行安全";
                                            }
                                            + "发生变化，不能自动纳入",
                                    true));
            if (!old.path("relation").path("kind").equals(now.path("relation").path("kind")))
                checks.add(
                        new Check(PublishCheckEnum.TABLE_KIND.getCode(), "表类型发生变化：" + name, true));
            Map<String, DatabaseMetadata.Column> oldColumns = new LinkedHashMap<>();
            old.path("columns")
                    .forEach(
                            c -> {
                                DatabaseMetadata.Column column =
                                        convert(c, DatabaseMetadata.Column.class);
                                oldColumns.put(column.name(), column);
                            });
            Map<String, String> fieldIds = fieldColumns(current, name);
            for (DatabaseMetadata.Column column : actual.columns()) {
                DatabaseMetadata.Column previous = oldColumns.remove(column.name());
                if (previous == null) {
                    if (fieldIds.containsKey(column.name())
                            || DataTableService.relationTableName(name)) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.UNMAPPED_COLUMN.getCode(),
                                        "无法识别新列归属：" + name + "." + column.name(),
                                        true));
                        continue;
                    }
                    try {
                        validator.code(column.name(), "新增列名", 63, true);
                        validateColumn(column);
                    } catch (RuntimeException e) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.NEW_COLUMN.getCode(),
                                        column.name() + "：" + e.getMessage(),
                                        true));
                        continue;
                    }
                    changes.add(new Change(name, null, column));
                    steps.add(
                            new Step(
                                    SchemaChangeEnum.IMPORT_COLUMN.getCode(),
                                    "新增字段映射：" + name + "." + column.name()));
                } else if (!previous.equals(column)) {
                    if (binding != null
                                    && (column.name().equals(binding.keyColumn())
                                            || column.name().equals(binding.parentColumn()))
                            || BaseDOColumns.NAMES.contains(column.name())
                            || column.name().equals("parent_id")
                            || !fieldIds.containsKey(column.name())) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.PROTECTED_COLUMN.getCode(),
                                        "系统列或未映射列变化：" + name + "." + column.name(),
                                        true));
                        continue;
                    }
                    boolean structural =
                            Objects.equals(previous.identityKind(), column.identityKind())
                                    && Objects.equals(
                                            previous.generatedKind(), column.generatedKind())
                                    && Objects.equals(
                                            previous.defaultExpression(),
                                            column.defaultExpression())
                                    && Objects.equals(
                                            previous.primaryKeyPosition(),
                                            column.primaryKeyPosition());
                    String before = normalize(previous.nativeType()),
                            after = normalize(column.nativeType());
                    if (!structural
                            || !before.equals(after) && !SchemaCompiler.widening(before, after)) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.INCOMPATIBLE_COLUMN.getCode(),
                                        "列结构变化不能自动映射：" + name + "." + column.name(),
                                        true));
                        continue;
                    }
                    changes.add(new Change(name, fieldIds.get(column.name()), column));
                    steps.add(
                            new Step(
                                    SchemaChangeEnum.SYNC_COLUMN.getCode(),
                                    "同步字段类型/必填/说明：" + name + "." + column.name()));
                }
            }
            oldColumns
                    .keySet()
                    .forEach(
                            column ->
                                    checks.add(
                                            new Check(
                                                    PublishCheckEnum.COLUMN_REMOVED.getCode(),
                                                    "原列被删除：" + name + "." + column,
                                                    true)));
        }
        if (steps.isEmpty() && checks.isEmpty())
            steps.add(new Step(SchemaChangeEnum.METADATA.getCode(), "仅同步表说明等兼容结构信息"));
        return new Analysis(
                new Preview(
                        id,
                        h.getLockVersion(),
                        DigestUtil.sha256Hex(tables.capture(current)),
                        checks.stream().noneMatch(Check::blocking),
                        steps,
                        checks),
                changes);
    }

    private String normalize(String type) {
        return type.replace("character varying", "varchar");
    }

    private void validateColumn(DatabaseMetadata.Column c) {
        if (c.defaultExpression() != null
                || !c.identityKind().isEmpty()
                || !c.generatedKind().isEmpty()
                || c.primaryKeyPosition() > 0) throw invalid("新列含默认表达式、主键或生成规则，需在原系统明确调整后再同步");
        field(c, "temporary", null, 0);
    }

    private Map<String, String> fieldColumns(Definition d, String table) {
        Map<String, FieldOptions> options =
                table.equals(
                                new ObjectTables.Ref(d.schemaName(), d.tableName())
                                        .key(d.schemaName()))
                        ? d.fieldOptions()
                        : d.details().stream()
                                .filter(
                                        t ->
                                                new ObjectTables.Ref(
                                                                ObjectTables.detail(d, t)
                                                                        .schemaName(),
                                                                t.tableName())
                                                        .key(d.schemaName())
                                                        .equals(table))
                                .findFirst()
                                .map(Detail::fieldOptions)
                                .orElse(Map.of());
        return options.entrySet().stream()
                .filter(e -> e.getValue().columnName() != null)
                .collect(Collectors.toMap(e -> e.getValue().columnName(), Map.Entry::getKey));
    }

    public Design apply(Apply request, long actor) {
        if (request == null
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() > 1000) throw invalid("同步原因必填，最多 1000 字符");
        return tx.execute(
                s -> {
                    com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO h =
                            designs.requireRevision(
                                    new Revision(
                                            request.id(),
                                            request.expectedLockVersion(),
                                            request.reason()));
                    commands.execute(lockTimeout());
                    commands.execute(statementTimeout());
                    DataCenter.Definition definition = designs.definition(request.id());
                    TreeSet<String> names = new TreeSet<String>();
                    tree(tables.capture(definition)).fieldNames().forEachRemaining(names::add);
                    for (String name : names) {
                        ObjectTables.Ref ref = ObjectTables.fromKey(name, h.getSchemaName());
                        if (database.relationExists(ref.schema(), ref.name()))
                            commands.execute(lock(ref.schema(), ref.name()));
                    }
                    ObjectReconcileService.Analysis analysis = analyze(request.id());
                    if (!analysis.preview().allowed()) throw invalid("存在不支持自动同步的结构变化，请先处理阻断项");
                    if (!Objects.equals(request.fingerprint(), analysis.preview().fingerprint()))
                        throw invalid("结构已变化，请重新预览差异");
                    DataCenter.Design design =
                            VersionStateEnum.PUBLISHED.matches(h.getVersionState())
                                    ? designs.editPublished(
                                            new Revision(
                                                    request.id(),
                                                    request.expectedLockVersion(),
                                                    null),
                                            actor)
                                    : designs.get(request.id());
                    ObjectDraft draft = design.draft();
                    List<FieldDefinition> main = new ArrayList<>(draft.fields());
                    for (ObjectReconcileService.Change change : analysis.changes())
                        if (change.table().equals(draft.tableName())) replace(main, change);
                    drafts.update(
                            new SaveObjectDraft(
                                    draft.id(),
                                    draft.lockVersion(),
                                    draft.objectCode(),
                                    draft.objectName(),
                                    draft.description(),
                                    draft.tableName(),
                                    draft.titleFieldId(),
                                    main,
                                    List.of(),
                                    design.settings().titleTemplate()),
                            actor,
                            UUID.randomUUID());
                    com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO head =
                            designs.head(request.id(), true);
                    for (com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows.Detail
                            detail : store.details(head.getVersionId())) {
                        DataCenter.Detail detailDesign =
                                design.details().stream()
                                        .filter(
                                                t ->
                                                        t.id().equals(
                                                                        detail.getStableTableId()
                                                                                .toString()))
                                        .findFirst()
                                        .orElseThrow();
                        String detailKey =
                                new ObjectTables.Ref(
                                                detailDesign.binding() == null
                                                        ? design.schemaName()
                                                        : detailDesign.binding().schemaName(),
                                                detail.getTableName())
                                        .key(design.schemaName());
                        ArrayList<FieldDefinition> fields =
                                new ArrayList<>(store.detailFields(detail.getId()));
                        for (ObjectReconcileService.Change change : analysis.changes())
                            if (change.table().equals(detailKey)) {
                                replace(fields, change);
                                FieldDefinition updated =
                                        fields.stream()
                                                .filter(
                                                        f ->
                                                                f.code()
                                                                                .equals(
                                                                                        change.column()
                                                                                                .name())
                                                                        || Objects.equals(
                                                                                f.id(),
                                                                                change.fieldId()))
                                                .findFirst()
                                                .orElseThrow();
                                if (updated.id() == null)
                                    updated =
                                            validator.field(
                                                    updated, Long.toString(objects.nextStableId()));
                                objects.upsertField(head.getVersionId(), detail.getId(), updated);
                                updateOptions(
                                        head.getVersionId(),
                                        updated,
                                        change.column(),
                                        detailKey,
                                        design);
                            }
                    }
                    DataCenter.Design saved = designs.get(request.id());
                    for (ObjectReconcileService.Change change : analysis.changes())
                        if (change.table().equals(draft.tableName())) {
                            FieldDefinition f =
                                    saved.draft().fields().stream()
                                            .filter(
                                                    v ->
                                                            change.fieldId() == null
                                                                    ? v.code()
                                                                            .equals(
                                                                                    change.column()
                                                                                            .name())
                                                                    : v.id().equals(
                                                                                    change
                                                                                            .fieldId()))
                                            .findFirst()
                                            .orElseThrow();
                            updateOptions(
                                    head.getVersionId(),
                                    f,
                                    change.column(),
                                    draft.tableName(),
                                    design);
                        }
                    designs.snapshot(request.id());
                    store.markReconciliation(
                            head.getId(), head.getVersionId(), analysis.preview().fingerprint());
                    designs.audit(
                            head.getId(),
                            actor,
                            AuditOperationEnum.OBJECT_RECONCILIATION_DRAFT.getCode(),
                            Map.of(
                                    "changes",
                                    analysis.preview().changes(),
                                    "reason",
                                    request.reason(),
                                    "fingerprint",
                                    request.fingerprint()));
                    return designs.get(request.id());
                });
    }

    private void replace(List<FieldDefinition> fields, Change change) {
        if (change.fieldId() == null)
            fields.add(field(change.column(), change.column().name(), null, fields.size()));
        else
            for (int i = 0; i < fields.size(); i++)
                if (fields.get(i).id().equals(change.fieldId())) {
                    FieldDefinition old = fields.get(i);
                    FieldDefinition converted =
                            field(change.column(), old.code(), old.id(), old.sort());
                    fields.set(
                            i,
                            new FieldDefinition(
                                    old.key(),
                                    old.id(),
                                    old.code(),
                                    old.name(),
                                    converted.type(),
                                    converted.length(),
                                    converted.precision(),
                                    converted.scale(),
                                    converted.required(),
                                    old.unique(),
                                    old.sort()));
                    break;
                }
    }

    private FieldDefinition field(DatabaseMetadata.Column c, String code, String id, int sort) {
        String nativeType = normalize(c.nativeType());
        String logical;
        Integer length = null, precision = null, scale = null;
        if (nativeType.startsWith("varchar(")) {
            logical = FieldTypeEnum.TEXT.getCode();
            length = Integer.parseInt(nativeType.replaceAll("[^0-9]", ""));
        } else if (nativeType.startsWith("numeric(")) {
            logical = FieldTypeEnum.DECIMAL.getCode();
            String[] values = nativeType.substring(8, nativeType.length() - 1).split(",");
            precision = Integer.parseInt(values[0]);
            scale = Integer.parseInt(values[1]);
        } else
            logical =
                    switch (nativeType) {
                        case "text" -> FieldTypeEnum.TEXTAREA.getCode();
                        case "bigint", "integer", "smallint" -> FieldTypeEnum.INTEGER.getCode();
                        case "date" -> FieldTypeEnum.DATE.getCode();
                        case "time without time zone" -> FieldTypeEnum.TIME.getCode();
                        case "timestamp without time zone", "timestamp with time zone" ->
                                FieldTypeEnum.DATETIME.getCode();
                        case "boolean" -> FieldTypeEnum.BOOLEAN.getCode();
                        case "uuid" -> FieldTypeEnum.UUID.getCode();
                        default -> throw invalid("类型暂不支持自动映射：" + nativeType);
                    };
        FieldDefinition f =
                new FieldDefinition(
                        id == null ? "sync-" + code : id,
                        id,
                        code,
                        c.comment() == null || c.comment().isBlank() ? c.name() : c.comment(),
                        logical,
                        length,
                        precision,
                        scale,
                        !c.nullable(),
                        false,
                        sort);
        validator.field(f, id == null ? "1" : id);
        return f;
    }

    private void updateOptions(
            long versionId,
            FieldDefinition f,
            DatabaseMetadata.Column column,
            String table,
            Design design) {
        Map<String, FieldOptions> old =
                table.equals(design.draft().tableName())
                        ? design.fieldOptions()
                        : design.details().stream()
                                .filter(
                                        d ->
                                                new ObjectTables.Ref(
                                                                d.binding() == null
                                                                        ? design.schemaName()
                                                                        : d.binding().schemaName(),
                                                                d.tableName())
                                                        .key(design.schemaName())
                                                        .equals(table))
                                .findFirst()
                                .map(Detail::fieldOptions)
                                .orElse(Map.of());
        var o = old.getOrDefault(f.id(), FieldOptions.defaults());
        var normalized =
                FieldOptions.copyOf(o)
                        .columnName(column.name())
                        .classification(
                                old.containsKey(f.id())
                                        ? o.classification()
                                        : DataClassificationEnum.SENSITIVE.getCode())
                        .description(column.comment())
                        .state(MemberStateEnum.ACTIVE.getCode())
                        .nativeType(column.nativeType())
                        .build();
        store.updateFieldOptions(
                versionId,
                Long.parseLong(f.id()),
                designs.write(normalized),
                MemberStateEnum.ACTIVE.getCode(),
                normalized.classification(),
                column.name(),
                Boolean.TRUE.equals(o.generated()));
    }

    private JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("结构基线读取失败", e);
        }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return json.treeToValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("结构列读取失败", e);
        }
    }
}
