package com.richuang.os.nocode.metadata.service.table;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.api.DataTables.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignService;
import com.richuang.os.nocode.metadata.service.object.ObjectDraftService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;

/** 当前实例的物理事实、受控预览和纳管预检；系统表不会通过纳管入口变成可写业务对象。 */
@Service
public class DataTableService {
    @Resource private PlatformTransactionManager manager;

    // 包含底座遗留模块的命名空间：它们可能未使用 BaseMapper，不能仅依赖 TableInfo 缓存。
    private static final Pattern SYSTEM =
            Pattern.compile(
                    "(?i)^(?:system_|sys_|infra_|bpm_|act_|flw_|gen_|qrtz_|powerjob_|ai_|agent(?:_|$)|knowledge_|generation_|project_|repo_|scaffold_|yudao_|dual$|nocode_(?!data_)).*");
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDraftMapper objects;
    @Resource private ObjectDesignService designs;
    @Resource private TableBindingService bindings;
    @Resource private ObjectDraftService drafts;
    @Resource private ObjectMapper json;
    private TransactionTemplate tx;

    /** 初始化表结构快照的 JSON 编解码与事务模板，不创建独立数据源。 */
    @PostConstruct
    void initialize() {
        this.json = json.copy().findAndRegisterModules();
        this.tx = new TransactionTemplate(manager);
    }

    public static boolean systemTable(String schema, String table) {
        return schema == null
                || schema.equals("information_schema")
                || schema.startsWith("pg_")
                || SYSTEM.matcher(table).matches()
                // 同时复用底座已注册的实体映射，保护不采用 system_ 前缀的智能体、知识库等模块表。
                || TableInfoHelper.getTableInfos().stream()
                        .anyMatch(
                                info -> {
                                    String mapped = info.getTableName().replace("\"", "");
                                    return mapped.equals(schema + "." + table)
                                            || "public".equals(schema) && mapped.equals(table);
                                });
    }

    public List<String> schemas() {
        return database.listSchemas();
    }

    public PageResult<TableRow> page(
            String schema,
            String name,
            String management,
            String role,
            String objectId,
            String structureState,
            boolean includeSystem,
            int pageNo,
            int pageSize) {
        if (pageNo < 1 || pageNo > 10000 || pageSize < 1 || pageSize > 100) throw invalid("目录分页无效");
        requireSchema(schema);
        Map<String, ObjectDraftHeadDO> owners = owners(schema);
        Map<Long, String> states = new HashMap<>();
        List<DatabaseMetadata.Relation> filtered = new ArrayList<>();
        for (DatabaseMetadata.Relation relation : database.listRelations(schema, name, 1000)) {
            if (!Set.of("TABLE", "PARTITIONED_TABLE").contains(relation.kind())
                    || !includeSystem && systemTable(schema, relation.name())) continue;
            ObjectDraftHeadDO owner = owners.get(relation.name());
            String state = management(owner, schema, relation.name());
            String tableRole =
                    owner == null
                            ? TableRoleEnum.UNMANAGED.getCode()
                            : owner.getTableName().equals(relation.name())
                                            && schema.equals(owner.getSchemaName())
                                    ? TableRoleEnum.MAIN.getCode()
                                    : relationTableName(relation.name())
                                            ? TableRoleEnum.RELATION.getCode()
                                            : TableRoleEnum.DETAIL.getCode();
            if (management != null && !management.isBlank() && !management.equals(state)
                    || role != null && !role.isBlank() && !role.equals(tableRole)
                    || objectId != null
                            && !objectId.isBlank()
                            && (owner == null || !owner.getId().toString().equals(objectId)))
                continue;
            if (structureState != null
                    && !structureState.isBlank()
                    && !structureState.equals(
                            owner == null
                                    ? StructureStateEnum.UNMANAGED.getCode()
                                    : states.computeIfAbsent(owner.getId(), this::structureState)))
                continue;
            filtered.add(relation);
        }
        long start = (long) (pageNo - 1) * pageSize;
        // 先分页再读取列、约束和容量；同一个对象的指纹在一次目录请求中只核验一次。
        return new PageResult<>(
                filtered.stream()
                        .skip(start)
                        .limit(pageSize)
                        .map(
                                r -> {
                                    ObjectDraftHeadDO owner = owners.get(r.name());
                                    return row(
                                            schema,
                                            r.name(),
                                            owner,
                                            owner == null
                                                    ? StructureStateEnum.UNMANAGED.getCode()
                                                    : states.computeIfAbsent(
                                                            owner.getId(), this::structureState));
                                })
                        .toList(),
                (long) filtered.size());
    }

    private String structureState(long id) {
        return store.deployment(id) == null
                ? StructureStateEnum.PENDING.getCode()
                : drift(Long.toString(id)).isEmpty()
                        ? StructureStateEnum.MATCHED.getCode()
                        : StructureStateEnum.DRIFTED.getCode();
    }

    private Map<String, ObjectDraftHeadDO> owners(String schema) {
        Map<String, ObjectDraftHeadDO> owners = new HashMap<>();
        for (ObjectDraftHeadDO h : store.allHeads()) {
            if (schema.equals(h.getSchemaName())) {
                owners.put(h.getTableName(), h);
                store.relations(h.getVersionId()).stream()
                        .filter(r -> RelationTypeEnum.MANY_TO_MANY.matches(r.kind()))
                        .forEach(
                                r ->
                                        owners.put(
                                                relationTable(
                                                        database,
                                                        schema,
                                                        h.getId().toString(),
                                                        r.id()),
                                                h));
            }
            for (com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Detail d :
                    store.details(h.getVersionId())) {
                TableBinding binding = bindings.read(d.getConfigJson());
                if (schema.equals(binding == null ? h.getSchemaName() : binding.schemaName()))
                    owners.put(d.getTableName(), h);
            }
            com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                    store.deployment(h.getId());
            if (deployment != null)
                for (String key : readMap(deployment.getStructureJson()).keySet()) {
                    ObjectTables.Ref ref = ObjectTables.fromKey(key, deployment.getSchemaName());
                    if (schema.equals(ref.schema())) owners.put(ref.name(), h);
                }
        }
        return owners;
    }

    private TableBinding binding(ObjectDraftHeadDO h, String schema, String name) {
        if (schema.equals(h.getSchemaName()) && name.equals(h.getTableName()))
            return bindings.main(h);
        for (com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Detail t :
                store.details(h.getVersionId())) {
            TableBinding b =
                    Optional.ofNullable(bindings.read(t.getConfigJson()))
                            .orElse(TableBinding.generated(h.getSchemaName(), true));
            if (schema.equals(b.schemaName()) && name.equals(t.getTableName())) return b;
        }
        // 关联表由平台生成；已停用成员的精确来源从已发布定义读取。
        DataCenter.Definition published = designs.published(h.getId().toString());
        if (published != null) {
            TableBinding known =
                    ObjectTables.bindings(published).get(new ObjectTables.Ref(schema, name));
            if (known != null) return known;
        }
        return TableBinding.generated(schema, true);
    }

    private void requireSchema(String schema) {
        if (schema == null || !database.listSchemas().contains(schema))
            throw invalid("Schema 不存在或不可访问");
    }

    public ObjectDraftHeadDO owner(String schema, String table) {
        return owners(schema).get(table);
    }

    private String management(ObjectDraftHeadDO h, String schema, String name) {
        if (h == null) return TableManagementEnum.UNMANAGED.getCode();
        if (ObjectStatusEnum.DISABLED.matches(h.getStatus()))
            return TableManagementEnum.DISABLED.getCode();
        if (h.getCurrentPublishedVersionNo() == null) return TableManagementEnum.PENDING.getCode();
        return binding(h, schema, name).adopted()
                ? TableManagementEnum.ADOPTED.getCode()
                : TableManagementEnum.GENERATED.getCode();
    }

    private TableRow row(String schema, String name, ObjectDraftHeadDO h) {
        return row(
                schema,
                name,
                h,
                h == null ? StructureStateEnum.UNMANAGED.getCode() : structureState(h.getId()));
    }

    private TableRow row(String schema, String name, ObjectDraftHeadDO h, String state) {
        DatabaseMetadata.Table actual =
                database.readTable(schema, name).orElseThrow(() -> invalid("物理表不存在"));
        DatabaseMetadata.Statistics statistics = actual.statistics();
        com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                h == null ? null : store.deployment(h.getId());
        return new TableRow(
                schema,
                name,
                actual.relation().comment(),
                actual.relation().kind(),
                management(h, schema, name),
                h == null
                        ? TableRoleEnum.UNMANAGED.getCode()
                        : h.getTableName().equals(name) && schema.equals(h.getSchemaName())
                                ? TableRoleEnum.MAIN.getCode()
                                : relationTableName(name)
                                        ? TableRoleEnum.RELATION.getCode()
                                        : TableRoleEnum.DETAIL.getCode(),
                systemTable(schema, name),
                h == null ? null : h.getId().toString(),
                h == null ? null : h.getObjectName(),
                h == null ? null : h.getCurrentPublishedVersionNo(),
                statistics == null ? 0 : statistics.estimatedRows(),
                statistics == null ? 0 : statistics.totalBytes(),
                state,
                deployment == null ? null : deployment.getVerifiedAt());
    }

    public TableDetail detail(String schema, String name, boolean canSeeSystem) {
        requireSchema(schema);
        if (systemTable(schema, name) && !canSeeSystem) throw invalid("没有查看系统表结构的权限");
        DatabaseMetadata.Table actual =
                database.readTable(schema, name).orElseThrow(() -> invalid("物理表不存在"));
        ObjectDraftHeadDO h = owner(schema, name);
        Map<String, String> mapping = new LinkedHashMap<>();
        if (h != null) {
            DataCenter.Design d = designs.get(h.getId().toString());
            Map<String, FieldOptions> opts =
                    name.equals(d.draft().tableName()) && schema.equals(d.schemaName())
                            ? d.fieldOptions()
                            : d.details().stream()
                                    .filter(
                                            t ->
                                                    t.tableName().equals(name)
                                                            && schema.equals(
                                                                    t.binding() == null
                                                                            ? d.schemaName()
                                                                            : t.binding()
                                                                                    .schemaName()))
                                    .findFirst()
                                    .map(Detail::fieldOptions)
                                    .orElse(Map.of());
            opts.forEach((id, o) -> mapping.put(o.columnName(), id));
        }
        return new TableDetail(
                row(schema, name, h),
                actual,
                fingerprint(actual),
                mapping,
                h == null ? List.of() : drift(h.getId().toString()));
    }

    /** 只比较结构，估算行数和容量变化不会使对象漂移。 */
    public String fingerprint(DatabaseMetadata.Table table) {
        return DigestUtil.sha256Hex(designs.write(structure(table)));
    }

    public static Map<String, Object> structure(DatabaseMetadata.Table t) {
        return new TreeMap<>(
                Map.of(
                        "relation",
                        t.relation(),
                        "columns",
                        t.columns(),
                        "constraints",
                        t.constraints(),
                        "indexes",
                        t.indexes(),
                        "triggers",
                        t.triggers(),
                        "security",
                        Map.of(
                                "rowSecurity",
                                t.statistics() != null && t.statistics().rowSecurity(),
                                "forceRowSecurity",
                                t.statistics() != null && t.statistics().forceRowSecurity(),
                                "canSelect",
                                t.statistics() != null && t.statistics().canSelect(),
                                "canWrite",
                                t.statistics() != null && t.statistics().canWrite())));
    }

    public String capture(Definition definition) {
        Map<String, Object> result = new TreeMap<>();
        Set<ObjectTables.Ref> selected = new LinkedHashSet<>();
        selected.add(
                new ObjectTables.Ref(
                        ObjectTables.main(definition).schemaName(), definition.tableName()));
        definition.details().stream()
                .filter(d -> MemberStateEnum.ACTIVE.matches(d.state()))
                .forEach(
                        d ->
                                selected.add(
                                        new ObjectTables.Ref(
                                                ObjectTables.detail(definition, d).schemaName(),
                                                d.tableName())));
        definition.relations().stream()
                .filter(r -> RelationTypeEnum.MANY_TO_MANY.matches(r.kind()))
                .forEach(
                        r ->
                                selected.add(
                                        new ObjectTables.Ref(
                                                definition.schemaName(),
                                                relationTable(
                                                        database,
                                                        definition.schemaName(),
                                                        definition.objectId(),
                                                        r.id()))));
        com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Deployment old =
                store.deployment(Long.parseLong(definition.objectId()));
        if (old != null)
            readMap(old.getStructureJson())
                    .keySet()
                    .forEach(key -> selected.add(ObjectTables.fromKey(key, old.getSchemaName())));
        for (ObjectTables.Ref ref : selected)
            result.put(
                    ref.key(definition.schemaName()),
                    database.readTable(ref.schema(), ref.name())
                            .map(DataTableService::structure)
                            .orElse(null));
        return designs.write(result);
    }

    public static String relationTable(String objectId, String relationId) {
        return "biz_r_" + objectId + "_" + relationId;
    }

    /** 新关系生成 biz_ 表；历史关系在运行、发布锁和漂移检查中继续使用原表。 */
    public static String relationTable(
            DatabaseMetadataReader database, String schema, String objectId, String relationId) {
        String legacy = "nocode_data_r_" + objectId + "_" + relationId;
        String current = relationTable(objectId, relationId);
        if (!database.relationExists(schema, legacy)) return current;
        if (database.relationExists(schema, current)) throw invalid("关联表存在新旧同名映射，请核对物理绑定");
        return legacy;
    }

    public static boolean relationTableName(String name) {
        return name.startsWith("biz_r_") || name.startsWith("nocode_data_r_");
    }

    /**
     * 统一单表快照的跨版本比较：PG18 将普通非空属性同时记录为 n 约束，旧版仅记录列 nullable。
     * 只消除已由同一列非空属性完整表达的约束；特殊定义和所有其他结构仍严格比较，不改写历史快照。
     */
    public static JsonNode comparableStructure(JsonNode snapshot) {
        JsonNode result = snapshot.deepCopy();
        if (!(result.path("constraints") instanceof ArrayNode constraints)) return result;
        Set<String> notNullDefinitions = new HashSet<>();
        for (JsonNode column : result.path("columns")) {
            if (column.path("nullable").isBoolean()
                    && !column.path("nullable").booleanValue()
                    && column.path("name").isTextual()) {
                String name = column.path("name").textValue();
                notNullDefinitions.add("NOT NULL " + name);
                notNullDefinitions.add("NOT NULL \"" + name.replace("\"", "\"\"") + "\"");
            }
        }
        for (int i = constraints.size() - 1; i >= 0; i--) {
            JsonNode constraint = constraints.get(i);
            if ("n".equals(constraint.path("kind").asText())
                    && notNullDefinitions.contains(constraint.path("definition").asText()))
                constraints.remove(i);
        }
        return result;
    }

    public List<Check> drift(String id) {
        com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                store.deployment(Long.parseLong(id));
        if (deployment == null) return List.of();
        Map<String, Object> baseline = readMap(deployment.getStructureJson());
        List<Check> checks = new ArrayList<>();
        for (Map.Entry<String, Object> entry : baseline.entrySet()) {
            ObjectTables.Ref ref = ObjectTables.fromKey(entry.getKey(), deployment.getSchemaName());
            Optional<DatabaseMetadata.Table> current = database.readTable(ref.schema(), ref.name());
            if (current.isEmpty())
                checks.add(
                        new Check(
                                PublishCheckEnum.TABLE_MISSING.getCode(),
                                "物理表不存在：" + entry.getKey(),
                                true));
            else if (!comparableStructure(json.valueToTree(entry.getValue()))
                    .equals(comparableStructure(json.valueToTree(structure(current.get())))))
                checks.add(
                        new Check(
                                PublishCheckEnum.STRUCTURE_DRIFT.getCode(),
                                "结构与发布基线不一致：" + entry.getKey(),
                                true));
        }
        return checks;
    }

    public List<Check> verify(String id, long actor) {
        return tx.execute(
                s -> {
                    ObjectDraftHeadDO h = designs.head(id, true);
                    List<DataCenter.Check> checks = drift(id);
                    store.verifyDeployment(h.getId());
                    designs.audit(
                            h.getId(),
                            actor,
                            AuditOperationEnum.OBJECT_STRUCTURE_VERIFY.getCode(),
                            checks);
                    return checks;
                });
    }

    public Preflight preflight(String schema, String name) {
        requireSchema(schema);
        if (systemTable(schema, name)) throw invalid("系统表不能纳管为业务对象");
        DatabaseMetadata.Table table =
                database.readTable(schema, name).orElseThrow(() -> invalid("物理表不存在"));
        List<Check> checks = new ArrayList<>();
        if (!"TABLE".equals(table.relation().kind()))
            checks.add(
                    new Check(PublishCheckEnum.TABLE_KIND.getCode(), "当前仅纳管 PostgreSQL 普通表", true));
        if (owner(schema, name) != null)
            checks.add(new Check(PublishCheckEnum.CLAIMED.getCode(), "该表已关联数据对象或纳管草稿", true));
        List<DatabaseMetadata.Column> keys =
                table.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
        if (keys.size() != 1 || !TableBindingService.keyType(keys.getFirst().nativeType()))
            checks.add(
                    new Check(
                            PublishCheckEnum.PRIMARY_KEY.getCode(),
                            "需要受支持的真实单列主键（整数、UUID 或文本），不能按列名猜测",
                            true));
        if (table.columns().isEmpty() || table.columns().size() > 200)
            checks.add(
                    new Check(PublishCheckEnum.COLUMN_COUNT.getCode(), "纳管表应包含 1–200 个字段", true));
        if (!table.statistics().canSelect())
            checks.add(
                    new Check(PublishCheckEnum.SELECT_PERMISSION.getCode(), "当前数据库账号没有读取权限", true));
        boolean readOnly =
                !table.statistics().canWrite()
                        || table.statistics().rowSecurity()
                        || !table.triggers().isEmpty();
        boolean restricted = readOnly;
        List<String> baseDifferences = BaseDOColumns.differences(table);
        if (!baseDifferences.isEmpty()) {
            readOnly = true;
            checks.add(
                    new Check(
                            PublishCheckEnum.BASE_FIELDS.getCode(),
                            "缺少或不符合底座公共字段规范：" + String.join("、", baseDifferences) + "；先只读纳管，原表保持不变",
                            false));
        }
        if (restricted)
            checks.add(
                    new Check(
                            PublishCheckEnum.READ_ONLY.getCode(),
                            "数据库权限、行安全或已有触发器限制，纳管后保持固有只读",
                            false));
        for (DatabaseMetadata.Column column : table.columns())
            if (!supported(column.nativeType()) || !column.generatedKind().isEmpty()) {
                readOnly = true;
                checks.add(
                        new Check(
                                PublishCheckEnum.COLUMN_READ_ONLY.getCode(),
                                column.name() + " 使用暂不支持写入的类型或生成方式，保留原结构并只读",
                                false));
            }
        return new Preflight(
                schema,
                name,
                fingerprint(table),
                checks.stream().noneMatch(Check::blocking),
                readOnly,
                checks,
                table.columns().stream().map(DatabaseMetadata.Column::name).toList(),
                table);
    }

    public static boolean supported(String type) {
        // PostgreSQL 目录保留显式时间精度；timestamp(6) 与默认 timestamp 均可写。
        return type.matches(
                "(?:smallint|integer|bigint|uuid|boolean|date|text|time without time"
                        + " zone|timestamp(?:\\([0-6]\\))? (?:with|without) time zone|character"
                        + " varying\\([1-9][0-9]{0,3}\\)|numeric\\([1-9][0-9]?,[0-9]{1,2}\\))");
    }

    public Design adopt(Adoption request, long actor) {
        return tx.execute(
                s -> {
                    if (request == null) throw invalid("纳管请求必填");
                    objects.lockTableName("nocode-design-write");
                    objects.lockTableName("nocode-table:" + request.tableName());
                    Preflight check = preflight(request.schemaName(), request.tableName());
                    if (!check.allowed()) throw invalid("兼容性预检未通过");
                    if (!check.fingerprint().equals(request.fingerprint()))
                        throw invalid("表结构已变化，请重新预检");
                    commands.execute(PostgreSqlCommands.lockTimeout());
                    commands.execute(
                            PostgreSqlCommands.lock(request.schemaName(), request.tableName()));
                    if (!fingerprint(
                                    database.readTable(request.schemaName(), request.tableName())
                                            .orElseThrow())
                            .equals(check.fingerprint())) throw invalid("表结构已变化，请重新预检");
                    ArrayList<FieldDefinition> fields = new ArrayList<FieldDefinition>();
                    LinkedHashMap<String, DatabaseMetadata.Column> columns =
                            new LinkedHashMap<String, DatabaseMetadata.Column>();
                    for (DatabaseMetadata.Column c : check.structure().columns()) {
                        String code = "field_" + c.ordinal();
                        String type = logical(c.nativeType());
                        Integer length =
                                type.equals(FieldTypeEnum.TEXT.getCode())
                                        ? Math.min(4000, integerPart(c.nativeType(), 200))
                                        : null;
                        Integer precision =
                                type.equals(FieldTypeEnum.DECIMAL.getCode())
                                        ? Math.min(38, integerPart(c.nativeType(), 18))
                                        : null;
                        Integer scale =
                                type.equals(FieldTypeEnum.DECIMAL.getCode())
                                        ? Math.min(precision, scale(c.nativeType()))
                                        : null;
                        fields.add(
                                new FieldDefinition(
                                        code,
                                        null,
                                        code,
                                        Objects.toString(c.comment(), c.name()),
                                        type,
                                        length,
                                        precision,
                                        scale,
                                        !c.nullable(),
                                        false,
                                        c.ordinal()));
                        columns.put(code, c);
                    }
                    String title =
                            columns.entrySet().stream()
                                    .filter(e -> e.getValue().name().equals(request.titleColumn()))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElseThrow(() -> invalid("请选择真实标题列"));
                    String temporary = "biz_adopt_" + UUID.randomUUID().toString().replace("-", "");
                    ObjectDraft draft =
                            drafts.create(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            request.objectCode(),
                                            request.objectName(),
                                            check.structure().relation().comment(),
                                            temporary,
                                            title,
                                            fields,
                                            List.of(),
                                            "{{" + title + "}}"),
                                    actor,
                                    UUID.randomUUID());
                    ObjectDraftHeadDO h = designs.head(draft.id(), true);
                    store.markAdopted(
                            h.getId(), request.schemaName(), check.readOnly(), check.fingerprint());
                    store.renameMainTable(h.getVersionId(), request.tableName());
                    store.settings(
                            h.getId(),
                            designs.write(new Settings(null, null, null, "{{" + title + "}}")),
                            "{{" + title + "}}");
                    for (var f : draft.fields()) {
                        var c = columns.get(f.code());
                        // 纳管列首次登记：不带选择来源、计算、编号与对象规则。
                        var o =
                                new FieldOptions(
                                        c.name(),
                                        DataClassificationEnum.SENSITIVE.getCode(),
                                        NativeDefaults.booleanConstant(
                                                c.nativeType(), c.defaultExpression()),
                                        c.comment(),
                                        null,
                                        null,
                                        null,
                                        MemberStateEnum.ACTIVE.getCode(),
                                        List.of(),
                                        null,
                                        null,
                                        DisplayResolverEnum.NONE.getCode(),
                                        c.nativeType(),
                                        c.primaryKeyPosition() > 0,
                                        !c.generatedKind().isEmpty()
                                                || !c.identityKind().isEmpty()
                                                || BaseDOColumns.NAMES.contains(c.name()),
                                        null,
                                        null,
                                        null,
                                        null);
                        store.updateFieldOptions(
                                h.getVersionId(),
                                Long.parseLong(f.id()),
                                designs.write(o),
                                MemberStateEnum.ACTIVE.getCode(),
                                DataClassificationEnum.SENSITIVE.getCode(),
                                c.name(),
                                false);
                    }
                    store.tableBinding(
                            h.getTableId(),
                            designs.write(
                                    new TableBinding(
                                            ObjectSourceEnum.ADOPTED.getCode(),
                                            request.schemaName(),
                                            TableBindingService.key(check.structure()).name(),
                                            null,
                                            StructureModeEnum.RETAIN.getCode(),
                                            check.readOnly(),
                                            false,
                                            check.fingerprint())));
                    designs.snapshot(draft.id());
                    designs.audit(
                            h.getId(),
                            actor,
                            AuditOperationEnum.OBJECT_ADOPTION_DRAFT.getCode(),
                            Map.of(
                                    "schema",
                                    request.schemaName(),
                                    "table",
                                    request.tableName(),
                                    "readOnly",
                                    check.readOnly()));
                    return designs.get(draft.id());
                });
    }

    public static String logical(String nativeType) {
        if (nativeType.matches("smallint|integer|bigint")) return FieldTypeEnum.INTEGER.getCode();
        if (nativeType.startsWith("numeric(")) return FieldTypeEnum.DECIMAL.getCode();
        if (nativeType.equals("boolean")) return FieldTypeEnum.BOOLEAN.getCode();
        if (nativeType.equals("date")) return FieldTypeEnum.DATE.getCode();
        if (nativeType.startsWith("timestamp")) return FieldTypeEnum.DATETIME.getCode();
        if (nativeType.startsWith("time ")) return FieldTypeEnum.TIME.getCode();
        if (nativeType.equals("uuid")) return FieldTypeEnum.UUID.getCode();
        if (nativeType.startsWith("character varying(")) return FieldTypeEnum.TEXT.getCode();
        return FieldTypeEnum.TEXTAREA.getCode();
    }

    public static int integerPart(String value, int fallback) {
        java.util.regex.Matcher m = Pattern.compile("\\(([0-9]+)").matcher(value);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    public static int scale(String value) {
        java.util.regex.Matcher m = Pattern.compile(",([0-9]+)\\)").matcher(value);
        return m.find() ? Integer.parseInt(m.group(1)) : 2;
    }

    public Preview preview(String schema, String name, int pageNo, int pageSize, long actor) {
        return tx.execute(
                s -> {
                    requireSchema(schema);
                    if (systemTable(schema, name)) throw invalid("系统表不提供业务数据预览");
                    if (pageNo < 1
                            || pageSize < 1
                            || pageSize > 50
                            || (long) pageNo * pageSize > 100000) throw invalid("预览分页范围无效");
                    DatabaseMetadata.Table table =
                            database.readTable(schema, name).orElseThrow(() -> invalid("物理表不存在"));
                    if (!"TABLE".equals(table.relation().kind())) throw invalid("仅支持普通表数据预览");
                    ObjectDraftHeadDO h = owner(schema, name);
                    if (h != null && ObjectStatusEnum.DISABLED.matches(h.getStatus()))
                        throw invalid("对象已停用");
                    Map<String, FieldOptions> fieldOptions = new HashMap<>();
                    if (h != null && h.getCurrentPublishedVersionNo() != null) {
                        DataCenter.Definition d = designs.published(h.getId().toString());
                        if (name.equals(d.tableName()) && schema.equals(d.schemaName()))
                            fieldOptions.putAll(d.fieldOptions());
                        else
                            d.details().stream()
                                    .filter(
                                            t ->
                                                    name.equals(t.tableName())
                                                            && schema.equals(
                                                                    ObjectTables.detail(d, t)
                                                                            .schemaName()))
                                    .forEach(t -> fieldOptions.putAll(t.fieldOptions()));
                    }
                    List<String> masked = new ArrayList<>();
                    for (DatabaseMetadata.Column c : table.columns()) {
                        Optional<DataCenter.FieldOptions> known =
                                fieldOptions.values().stream()
                                        .filter(o -> c.name().equals(o.columnName()))
                                        .findFirst();
                        if (known.isEmpty()
                                || !DataClassificationEnum.NORMAL.matches(
                                        known.get().classification())) masked.add(c.name());
                    }
                    commands.execute(PostgreSqlCommands.statementTimeout());
                    List<String> names =
                            table.columns().stream().map(DatabaseMetadata.Column::name).toList();
                    List<String> order =
                            table.columns().stream()
                                    .filter(c -> c.primaryKeyPosition() > 0)
                                    .sorted(
                                            Comparator.comparing(
                                                    DatabaseMetadata.Column::primaryKeyPosition))
                                    .map(DatabaseMetadata.Column::name)
                                    .toList();
                    List<String> raw =
                            commands.rows(
                                    PostgreSqlCommands.rows(
                                            schema,
                                            name,
                                            names,
                                            order,
                                            pageSize + 1,
                                            (long) (pageNo - 1) * pageSize,
                                            h != null && !binding(h, schema, name).adopted()));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (String value : raw.stream().limit(pageSize).toList()) {
                        Map<String, Object> row = readMap(value);
                        masked.forEach(
                                column -> {
                                    if (row.get(column) != null) row.put(column, "••••");
                                });
                        rows.add(row);
                    }
                    store.auditTable(
                            UUID.randomUUID().toString(),
                            actor,
                            schema + "." + name,
                            designs.write(
                                    Map.of(
                                            "pageNo",
                                            pageNo,
                                            "rows",
                                            rows.size(),
                                            "maskedColumns",
                                            masked)));
                    return new Preview(names, rows, raw.size() > pageSize, masked);
                });
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("结构快照读取失败", e);
        }
    }
}
