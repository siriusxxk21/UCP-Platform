package com.richuang.os.nocode.metadata.service.table;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 表级来源、键与固有能力统一校验。依赖目录和 Mapper，不依赖对象聚合服务，避免服务循环。 */
@Service
public class TableBindingService {
    @Resource private DatabaseMetadataReader database;
    @Resource private DataCenterMapper store;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;

    @PostConstruct
    void initialize() {
        json = json.copy().findAndRegisterModules();
    }

    public TableBinding read(String config) {
        try {
            if (config == null) return null;
            com.fasterxml.jackson.databind.JsonNode node = json.readTree(config).path("binding");
            return node.isMissingNode() || node.isNull()
                    ? null
                    : json.treeToValue(node, TableBinding.class);
        } catch (Exception e) {
            throw new IllegalStateException("表绑定读取失败", e);
        }
    }

    public String fingerprint(DatabaseMetadata.Table actual) {
        try {
            return DigestUtil.sha256Hex(
                    json.writeValueAsString(DataTableService.structure(actual)));
        } catch (Exception e) {
            throw new IllegalStateException("物理结构摘要失败", e);
        }
    }

    public TableBinding main(ObjectDraftHeadDO h) {
        TableBinding binding = read(store.tableConfig(h.getTableId()));
        if (binding != null) return binding;
        if (ObjectSourceEnum.GENERATED.matches(h.getSourceType()))
            return TableBinding.generated(h.getSchemaName(), false);
        DatabaseMetadata.Table actual =
                database.readTable(h.getSchemaName(), h.getTableName())
                        .orElseThrow(() -> invalid("主表不存在"));
        return new TableBinding(
                h.getSourceType(),
                h.getSchemaName(),
                key(actual).name(),
                null,
                StructureModeEnum.RETAIN.getCode(),
                Boolean.TRUE.equals(h.getReadOnly()),
                false,
                h.getAdoptionHash());
    }

    /** 明确传入已有表指纹；新表不允许把已存在的表通过名称碰撞隐式纳管。 */
    public TableBinding normalize(
            ObjectDraftHeadDO h,
            Long tableId,
            String tableName,
            boolean detail,
            TableBinding requested,
            TableBinding previous) {
        TableBinding b =
                requested != null
                        ? requested
                        : previous != null
                                ? previous
                                : TableBinding.generated(h.getSchemaName(), detail);
        ObjectSourceEnum.fromCode(b.source());
        StructureModeEnum.fromCode(b.structureMode());
        if (b.schemaName() == null
                || !database.listSchemas().contains(b.schemaName())
                || DataTableService.systemTable(b.schemaName(), tableName))
            throw invalid("不能绑定系统表或不可访问的 Schema");
        PostgreSqlCommands.identifier(tableName);
        if (detail && (b.parentColumn() == null || b.parentColumn().isBlank()))
            throw invalid("明细必须指定关联主表的父键列");
        if (detail) {
            PostgreSqlCommands.identifier(b.parentColumn());
            if (BaseDOColumns.NAMES.contains(b.parentColumn())
                    || Objects.equals(b.keyColumn(), b.parentColumn()))
                throw invalid("父键不能覆盖主键或底座公共字段");
        }
        if (!detail && b.parentColumn() != null) throw invalid("主表不能设置父键");
        if (previous != null
                && (!Objects.equals(previous.source(), b.source())
                        || !Objects.equals(previous.schemaName(), b.schemaName())
                        || !Objects.equals(previous.keyColumn(), b.keyColumn())
                        || !Objects.equals(previous.parentColumn(), b.parentColumn())))
            throw invalid("已有表绑定的来源、Schema、主键和父键不可原地更换");
        assertAvailable(h, tableId, b.schemaName(), tableName);
        DatabaseMetadata.Table actual = database.readTable(b.schemaName(), tableName).orElse(null);
        if (!b.adopted()) {
            validator.generatedTableName(tableName, previous == null ? null : tableName);
            if (!"id".equals(b.keyColumn()) || !b.managed())
                throw invalid("新建表使用 biz_ 前缀、id 主键和平台结构管理");
            if (previous == null && actual != null) throw invalid("明细表名已被占用，请选择绑定已有表");
            if (Boolean.TRUE.equals(b.repairBaseFields())) throw invalid("新建表自动包含底座公共字段");
            return new TableBinding(
                    b.source(),
                    b.schemaName(),
                    "id",
                    b.parentColumn(),
                    b.structureMode(),
                    Boolean.TRUE.equals(b.readOnly()),
                    false,
                    null);
        }
        if (actual == null || !"TABLE".equals(actual.relation().kind()))
            throw invalid("绑定目标必须是当前数据库中的普通表");
        if (actual.statistics() == null || !Boolean.TRUE.equals(actual.statistics().canSelect()))
            throw invalid("当前数据库账号不能读取此表");
        if (actual.columns().isEmpty() || actual.columns().size() > 200)
            throw invalid("纳管表应包含 1–200 个字段");
        if (!key(actual).name().equals(b.keyColumn())) throw invalid("请选择真实的单列主键");
        if (previous == null && !fingerprint(actual).equals(b.fingerprint()))
            throw invalid("表结构已变化或缺少预检指纹，请重新选择已有表");
        boolean restricted =
                !Boolean.TRUE.equals(actual.statistics().canWrite())
                        || Boolean.TRUE.equals(actual.statistics().rowSecurity())
                        || !actual.triggers().isEmpty();
        if (restricted && b.managed()) throw invalid("数据库权限、RLS 或触发器限制，不允许接管结构");
        List<String> missing = BaseDOColumns.differences(actual);
        boolean repair = Boolean.TRUE.equals(b.repairBaseFields());
        if (repair && !b.managed()) throw invalid("补齐公共字段需要明确选择平台管理结构");
        if (repair
                && missing.stream()
                        .anyMatch(
                                name ->
                                        actual.columns().stream()
                                                .anyMatch(c -> c.name().equals(name))))
            throw invalid("已有公共列的类型或非空规范存在差异；本次只支持安全补齐缺失列，请先修正已有列并重新核验");
        boolean unsupported =
                actual.columns().stream()
                        .anyMatch(c -> !supported(c.nativeType()) || !c.generatedKind().isEmpty());
        boolean readOnly =
                Boolean.TRUE.equals(b.readOnly())
                        || restricted
                        || unsupported
                        || !missing.isEmpty() && !repair;
        return new TableBinding(
                b.source(),
                b.schemaName(),
                b.keyColumn(),
                b.parentColumn(),
                b.structureMode(),
                readOnly,
                repair,
                previous == null ? fingerprint(actual) : previous.fingerprint());
    }

    public static DatabaseMetadata.Column key(DatabaseMetadata.Table table) {
        List<DatabaseMetadata.Column> keys =
                table.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
        if (keys.size() != 1 || !keyType(keys.getFirst().nativeType()))
            throw invalid("需要受支持的单列稳定主键");
        return keys.getFirst();
    }

    public static boolean keyType(String type) {
        return type != null
                && (Set.of("bigint", "integer", "smallint", "uuid", "text").contains(type)
                        || type.startsWith("character varying("));
    }

    private static boolean supported(String type) {
        // 纳管字段能力与目录预检共用同一白名单，不能通过重新保存越过只读限制。
        return DataTableService.supported(type);
    }

    private void assertAvailable(
            ObjectDraftHeadDO current, Long tableId, String schema, String table) {
        for (ObjectDraftHeadDO h : store.allHeads()) {
            if (schema.equals(h.getSchemaName())
                    && table.equals(h.getTableName())
                    && !Objects.equals(tableId, h.getTableId())) throw invalid("该表已关联主对象或纳管草稿");
            for (com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Detail d :
                    store.details(h.getVersionId())) {
                TableBinding binding = read(d.getConfigJson());
                String detailSchema = binding == null ? h.getSchemaName() : binding.schemaName();
                if (schema.equals(detailSchema)
                        && table.equals(d.getTableName())
                        && !Objects.equals(tableId, d.getId())) throw invalid("该表已被其他明细绑定");
            }
            com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                    store.deployment(h.getId());
            if (deployment != null && !Objects.equals(current.getId(), h.getId())) {
                try {
                    Iterator<String> fields =
                            json.readTree(deployment.getStructureJson()).fieldNames();
                    while (fields.hasNext()) {
                        ObjectTables.Ref ref =
                                ObjectTables.fromKey(fields.next(), deployment.getSchemaName());
                        if (schema.equals(ref.schema()) && table.equals(ref.name()))
                            throw invalid("该表仍属于已发布对象的结构基线");
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public record Fields(List<FieldDefinition> fields, Map<String, FieldOptions> options) {}

    /** 从底座真实目录生成字段映射；别名避开系统保留名，原物理列名完整保留。 */
    public Fields fields(DatabaseMetadata.Table table) {
        ArrayList<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        Map<String, FieldOptions> options = new LinkedHashMap<>();
        for (DatabaseMetadata.Column c : table.columns()) {
            String code = "f_" + c.ordinal();
            String type = DataTableService.logical(c.nativeType());
            Integer length =
                    FieldTypeEnum.TEXT.matches(type)
                            ? Math.min(4000, DataTableService.integerPart(c.nativeType(), 200))
                            : null;
            Integer precision =
                    FieldTypeEnum.DECIMAL.matches(type)
                            ? Math.min(38, DataTableService.integerPart(c.nativeType(), 18))
                            : null;
            Integer scale =
                    precision == null
                            ? null
                            : Math.min(precision, DataTableService.scale(c.nativeType()));
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
            // 纳管列首次登记：不带选择来源、计算、编号与对象规则。
            options.put(
                    code,
                    new FieldOptions(
                            c.name(),
                            DataClassificationEnum.SENSITIVE.getCode(),
                            null,
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
                            null));
        }
        return new Fields(fields, options);
    }
}
