package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.table.TableBindingService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 同一设计事务内保存明细、索引和对象关系，保持检查顺序及稳定身份。 */
@Component
public class ObjectDesignPartsWriter {
    @Resource private ObjectDesignCodec designCodec;
    @Resource private ObjectDesignFields designFields;
    @Resource private ObjectDesignReader designReader;
    @Resource private ObjectDraftMapper objects;
    @Resource private DataCenterMapper store;
    @Resource private DraftValidator validator;
    @Resource private DatabaseMetadataReader database;
    @Resource private TableBindingService bindings;
    @Resource private ObjectMemberDeployment memberDeployment;

    /** 内部明细独立绑定来源；纳管字段只从真实目录初始化，后续保存不能伪造物理能力。 */
    Map<String, String> saveDetails(
            ObjectDraftHeadDO h, List<Detail> requested, long actor, Design previousDesign) {
        Map<String, String> savedKeys = new HashMap<>();
        if (requested.size() > 20) throw invalid("内部明细最多 20 张");
        Map<String, DataCenterRows.Detail> existing =
                store.details(h.getVersionId()).stream()
                        .collect(Collectors.toMap(t -> t.getStableTableId().toString(), t -> t));
        Set<String> ids = new HashSet<>(), codes = new HashSet<>(), names = new HashSet<>();
        Map<String, FieldOptions> oldOptions = designReader.options(h.getVersionId());
        for (Detail d : requested) {
            String code = validator.code(d.code(), "明细编码", 63, false);
            if ("main".equals(code) || !codes.add(code)) throw invalid("明细编码重复或保留");
            validator.text(d.name(), "明细名称", 128);
            String tableName = d.tableName();
            PostgreSqlCommands.identifier(tableName);
            DataCenterRows.Detail table = d.id() == null ? null : existing.get(d.id());
            if (d.id() != null && (table == null || !ids.add(d.id())))
                throw invalid("明细 ID 不属于当前对象或重复");
            TableBinding previousBinding =
                    table == null
                            ? null
                            : Optional.ofNullable(bindings.read(table.getConfigJson()))
                                    .orElse(TableBinding.generated(h.getSchemaName(), true));
            TableBinding binding =
                    bindings.normalize(
                            h,
                            table == null ? null : table.getId(),
                            tableName,
                            true,
                            d.binding(),
                            previousBinding);
            // 对象曾发布不等于新增明细已部署；只保护该明细的发布身份和真实物理表。
            boolean mutableCandidate =
                    table != null
                            && !previousBinding.adopted()
                            && !binding.adopted()
                            && !memberDeployment.detailLocked(
                                    h,
                                    table.getStableTableId().toString(),
                                    previousBinding.schemaName(),
                                    table.getTableName(),
                                    false);
            if (table != null && !mutableCandidate && !table.getTableCode().equals(code))
                throw invalid("已发布或纳管明细的编码不可修改");
            if (table != null && !mutableCandidate && !table.getTableName().equals(tableName))
                throw invalid("已发布或纳管明细的物理表名不可修改");
            if (!names.add(
                    new ObjectTables.Ref(binding.schemaName(), tableName).key(h.getSchemaName())))
                throw invalid("明细物理表重复");
            String state = Objects.toString(d.state(), MemberStateEnum.ACTIVE.getCode());
            if (!MemberStateEnum.containsCode(state)) throw invalid("明细状态无效");
            TableBindingService.Fields imported =
                    binding.adopted() && table == null
                            ? bindings.fields(
                                    database.readTable(binding.schemaName(), tableName)
                                            .orElseThrow())
                            : null;
            List<FieldDefinition> inputFields = imported == null ? d.fields() : imported.fields();
            Map<String, DataCenter.FieldOptions> inputOptions =
                    imported == null ? d.fieldOptions() : imported.options();
            String config =
                    designCodec.write(Map.of("name", d.name(), "state", state, "binding", binding));
            if (table == null) {
                table = new DataCenterRows.Detail();
                table.setStableTableId(objects.nextStableId());
                table.setTableCode(code);
                table.setTableName(tableName);
                table.setConfigJson(config);
                store.insertDetail(h.getVersionId(), table);
            } else store.updateDetail(table.getId(), code, tableName, config);
            if (inputFields == null || inputFields.isEmpty() || inputFields.size() > 200)
                throw invalid("明细必须包含 1–200 个字段");
            Map<String, FieldDefinition> previous =
                    store.detailFields(table.getId()).stream()
                            .collect(Collectors.toMap(FieldDefinition::id, f -> f));
            if (binding.adopted() && imported == null)
                designFields.validateAdoptedFields(inputFields, previous);
            Set<String> fieldCodes = new HashSet<>(), fieldIds = new HashSet<>();
            List<FieldDefinition> fields = new ArrayList<>();
            for (FieldDefinition f : inputFields) {
                if (f.id() != null && (!previous.containsKey(f.id()) || !fieldIds.add(f.id())))
                    throw invalid("明细字段 ID 不属于当前明细或重复");
                FieldDefinition normalized =
                        validator.field(
                                f, f.id() == null ? Long.toString(objects.nextStableId()) : f.id());
                if (!fieldCodes.add(normalized.code())) throw invalid("明细字段编码重复");
                fields.add(normalized);
                if (imported != null)
                    oldOptions.put(normalized.id(), imported.options().get(f.key()));
            }
            store.deactivateDetailFields(table.getId());
            for (FieldDefinition f : fields)
                objects.upsertField(h.getVersionId(), table.getId(), f);
            savedKeys.put("detail:" + code, table.getStableTableId().toString());
            savedKeys.put(table.getStableTableId().toString(), table.getStableTableId().toString());
            Map<String, String> detailKeys = designFields.remap(inputFields, fields);
            savedKeys.putAll(detailKeys);
            designFields.saveOptions(
                    h,
                    fields,
                    inputOptions,
                    detailKeys,
                    oldOptions,
                    binding.adopted(),
                    previousDesign);
            if (d.indexes() != null && !d.indexes().isEmpty())
                throw invalid("明细组合索引请在对象索引页选择明细字段配置");
        }
        return savedKeys;
    }

    void saveIndexes(ObjectDraftHeadDO h, List<Index> requested, Map<String, String> remap) {
        if (requested.size() > 32) throw invalid("最多配置 32 个索引");
        Map<String, Index> known =
                store.indexes(h.getVersionId()).stream()
                        .collect(Collectors.toMap(Index::id, index -> index));
        Set<String> allFields = designReader.options(h.getVersionId()).keySet(),
                ids = new HashSet<>(),
                codes = new HashSet<>();
        List<Index> normalized = new ArrayList<>();
        for (DataCenter.Index i : requested) {
            String id = i.id() == null ? Long.toString(objects.nextStableId()) : i.id();
            if (i.id() != null && !known.containsKey(id) || !ids.add(id)) throw invalid("索引 ID 无效");
            String code = validator.code(i.code(), "索引编码", 63, false);
            if (!codes.add(code)) throw invalid("索引编码重复");
            if (i.id() != null
                    && !known.get(id).code().equals(code)
                    && memberDeployment.indexLocked(h, known.get(id)))
                throw invalid("已发布或已部署索引的编码不可修改，请新建索引");
            validator.text(i.name(), "索引名称", 128);
            if (i.fieldIds() == null || i.fieldIds().isEmpty() || i.fieldIds().size() > 8)
                throw invalid("组合索引应选择 1–8 个字段");
            List<String> fieldIds =
                    i.fieldIds().stream().map(f -> remap.getOrDefault(f, f)).toList();
            if (!allFields.containsAll(fieldIds)
                    || new HashSet<>(fieldIds).size() != fieldIds.size())
                throw invalid("索引字段无效或重复");
            normalized.add(
                    new Index(
                            id,
                            code,
                            i.name(),
                            Boolean.TRUE.equals(i.unique()),
                            fieldIds,
                            Boolean.TRUE.equals(i.parentScoped())));
        }
        store.deleteIndexes(h.getVersionId());
        // 与关系相同：删掉的索引再用同一编码新建时，旧的逻辑删除行不能挡住新索引。
        normalized.forEach(i -> store.purgeDeletedIndexCode(h.getVersionId(), i.code(), i.id()));
        normalized.forEach(
                i -> store.insertIndex(h.getVersionId(), i, designCodec.write(i.fieldIds())));
    }

    void saveRelations(ObjectDraftHeadDO h, List<Relation> requested, long actor) {
        if (requested.size() > 50) throw invalid("对象关系最多 50 个");
        Map<String, Relation> previous =
                store.relations(h.getVersionId()).stream()
                        .collect(Collectors.toMap(Relation::id, r -> r));
        Set<String> ids = new HashSet<>(), codes = new HashSet<>();
        List<Relation> normalized = new ArrayList<>();
        Set<String> boundFields = new HashSet<>();
        Set<String> generatedIds =
                store.fieldOptions(h.getVersionId()).stream()
                        .filter(f -> Boolean.TRUE.equals(f.getRelationGenerated()))
                        .map(f -> f.getStableFieldId().toString())
                        .collect(Collectors.toSet());
        for (var r : requested) {
            // 复用列的已发布关系删后同编码重建：沿用原稳定 ID，等同于在原关系上更换目标。
            Relation publishedMatch =
                    new ObjectRelationColumns(store, designReader)
                            .publishedIdentity(h, r.code(), r, requested);
            String id =
                    r.id() == null
                            ? publishedMatch != null
                                    ? publishedMatch.id()
                                    : Long.toString(objects.nextStableId())
                            : r.id();
            if (r.id() != null && !previous.containsKey(id) || !ids.add(id))
                throw invalid("关系 ID 无效");
            String code = validator.code(r.code(), "关系编码", 50, true);
            if (!codes.add(code)) throw invalid("关系编码重复");
            validator.text(r.name(), "关系名称", 128);
            if (!RelationTypeEnum.containsCode(r.kind())) throw invalid("关系类型无效");
            DataCenterRows.Detail sourceTable =
                    r.sourceDetailId() == null
                            ? null
                            : store.details(h.getVersionId()).stream()
                                    .filter(
                                            t ->
                                                    t.getStableTableId()
                                                            .toString()
                                                            .equals(r.sourceDetailId()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("引用来源明细不属于当前对象"));
            if (sourceTable != null && !RelationTypeEnum.REFERENCE.matches(r.kind()))
                throw invalid("内部明细仅支持单值对象引用");
            if (sourceTable != null
                    && !MemberStateEnum.ACTIVE.matches(
                            Objects.toString(
                                    designCodec
                                            .read(sourceTable.getConfigJson(), Map.class)
                                            .get("state"),
                                    MemberStateEnum.ACTIVE.getCode()))) throw invalid("引用来源明细已停用");
            List<FieldDefinition> sourceFields =
                    sourceTable == null
                            ? objects.selectFields(h.getVersionId())
                            : store.detailFields(sourceTable.getId());
            TableBinding sourceBinding =
                    sourceTable == null
                            ? bindings.main(h)
                            : Optional.ofNullable(bindings.read(sourceTable.getConfigJson()))
                                    .orElse(TableBinding.generated(h.getSchemaName(), true));
            long sourceTableId = sourceTable == null ? h.getTableId() : sourceTable.getId();
            ObjectDraftHeadDO target = designReader.head(r.targetObjectId(), false);
            if (!ObjectStatusEnum.ACTIVE.matches(target.getStatus())
                    || target.getCurrentPublishedVersionNo() == null) throw invalid("关系目标必须已发布且启用");
            DatabaseMetadata.Table actual =
                    database.readTable(target.getSchemaName(), target.getTableName())
                            .orElseThrow(() -> invalid("目标物理表不存在"));
            List<DatabaseMetadata.Column> keys =
                    actual.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
            if (keys.size() != 1 || !TableBindingService.keyType(keys.getFirst().nativeType()))
                throw invalid("引用目标需要受支持的单列稳定主键");
            if (RelationTypeEnum.MASTER_DETAIL.matches(r.kind())
                    && target.getId().equals(h.getId())) throw invalid("主从关系不能指向自身");
            String onDelete = Objects.toString(r.onDelete(), DeletePolicyEnum.RESTRICT.getCode());
            if (!DeletePolicyEnum.containsCode(onDelete)) throw invalid("删除策略无效");
            if (sourceTable != null && !DeletePolicyEnum.RESTRICT.matches(onDelete))
                throw invalid("内部明细引用使用限制删除，请通过所属单据解除引用");
            if (DeletePolicyEnum.CASCADE.matches(onDelete)
                    && !RelationTypeEnum.MASTER_DETAIL.matches(r.kind()))
                throw invalid("普通引用不允许级联删除");
            boolean required =
                    RelationTypeEnum.MASTER_DETAIL.matches(r.kind())
                            || Boolean.TRUE.equals(r.required());
            if (required && DeletePolicyEnum.SET_NULL.matches(onDelete))
                throw invalid("必填引用不能在删除时设空");
            Relation old = previous.get(id);
            boolean identityLocked = old != null && memberDeployment.relationLocked(h, old);
            boolean targetChanged = old != null && !old.targetObjectId().equals(r.targetObjectId());
            boolean explicitSingleRetarget =
                    targetChanged
                            && !sourceBinding.adopted()
                            && !Boolean.TRUE.equals(sourceBinding.readOnly())
                            && !generatedIds.contains(old.fieldId())
                            && (RelationTypeEnum.REFERENCE.matches(old.kind())
                                    || RelationTypeEnum.ONE_TO_ONE.matches(old.kind()));
            if (identityLocked
                    && (!old.code().equals(code)
                            || !old.kind().equals(r.kind())
                            || targetChanged && !explicitSingleRetarget
                            || !Objects.equals(old.sourceDetailId(), r.sourceDetailId())))
                throw invalid("已发布或已部署关系的编码、类型和来源不可原地更换；仅显式单值引用可更换目标并在发布时清空旧引用值");
            // 删后重建沿用了已发布关系的身份：更换目标须满足与原地更换目标相同的条件。
            if (old == null
                    && publishedMatch != null
                    && !publishedMatch.targetObjectId().equals(r.targetObjectId())
                    && (sourceBinding.adopted()
                            || Boolean.TRUE.equals(sourceBinding.readOnly())
                            || generatedIds.contains(publishedMatch.fieldId())
                            || !(RelationTypeEnum.REFERENCE.matches(r.kind())
                                    || RelationTypeEnum.ONE_TO_ONE.matches(r.kind()))))
                throw invalid(
                        "关系“"
                                + r.name()
                                + "”（"
                                + code
                                + "）使用的列仍保存着已发布版本指向原目标对象的数据；纳管、只读或非单值引用不能更换目标，请换一个关系编码新建关系");
            // 删后同编码重建：沿用同一来源表里停用的原生成列，不能再造一个同名列撞唯一约束。
            String retiredFieldId =
                    new ObjectRelationColumns(store, designReader)
                            .retiredGeneratedField(
                                    h,
                                    sourceTableId,
                                    code,
                                    r,
                                    sourceFields,
                                    previous.values(),
                                    requested,
                                    keys.getFirst().nativeType());
            String fieldId =
                    RelationTypeEnum.MANY_TO_MANY.matches(r.kind())
                            ? null
                            : r.fieldId() == null
                                    ? Objects.requireNonNullElseGet(
                                            retiredFieldId,
                                            () -> Long.toString(objects.nextStableId()))
                                    : old != null
                                                    && generatedIds.contains(r.fieldId())
                                                    && !Objects.equals(
                                                            old.sourceDetailId(),
                                                            r.sourceDetailId())
                                            ? Long.toString(objects.nextStableId())
                                            : r.fieldId();
            if (identityLocked && !Objects.equals(old.fieldId(), r.fieldId()))
                throw invalid("已发布或已部署关系不能更换引用列，请新建关系");
            boolean reuse =
                    fieldId != null
                            && r.fieldId() != null
                            && fieldId.equals(r.fieldId())
                            && (old == null
                                    || !Objects.equals(old.fieldId(), fieldId)
                                    || !generatedIds.contains(fieldId));
            if (fieldId != null && !boundFields.add(fieldId)) throw invalid("同一个字段不能重复绑定多个关系");
            if (reuse) {
                FieldDefinition field =
                        sourceFields.stream()
                                .filter(f -> fieldId.equals(f.id()))
                                .findFirst()
                                .orElseThrow(() -> invalid("引用列必须是当前来源表已有字段"));
                DataCenter.FieldOptions option =
                        designReader
                                .options(h.getVersionId())
                                .getOrDefault(fieldId, FieldOptions.defaults());
                if (Boolean.TRUE.equals(option.primaryKey())
                        || Boolean.TRUE.equals(option.generated())
                        || BaseDOColumns.NAMES.contains(option.columnName()))
                    throw invalid("主键、公共字段或生成字段不能作为引用列");
                // REFERENCE 是显式转换意图；只保存草稿定义，历史值由发布确认事务处理。
                // 普通 INTEGER/TEXT 列映射仍沿用原类型校验，不能被隐式当成清空转换。
                if (FieldTypeEnum.REFERENCE.matches(field.type()) && !sourceBinding.adopted()) {
                    option =
                            normalizeReferenceField(
                                    h,
                                    sourceTableId,
                                    field,
                                    option,
                                    keys.getFirst().nativeType(),
                                    required,
                                    RelationTypeEnum.ONE_TO_ONE.matches(r.kind()));
                }
                String nativeType =
                        option.nativeType() != null
                                ? PostgreSqlCommands.type(option.nativeType())
                                : switch (FieldTypeEnum.fromCode(field.type())) {
                                    case UUID -> "uuid";
                                    case INTEGER, REFERENCE -> "bigint";
                                    case TEXT -> "varchar(" + field.length() + ")";
                                    case TEXTAREA -> "text";
                                    default -> throw invalid("引用列类型不支持");
                                };
                if (!nativeType
                        .replace("character varying", "varchar")
                        .equals(
                                PostgreSqlCommands.type(keys.getFirst().nativeType())
                                        .replace("character varying", "varchar")))
                    throw invalid("引用列类型必须与目标真实主键一致");
            }
            if (fieldId != null && !reuse) {
                if (!sourceBinding.managed()) throw invalid("保留结构的来源表请映射已有引用列；新增列需启用平台管理结构");
                String nativeType = keys.getFirst().nativeType();
                String logical =
                        nativeType.equals("uuid")
                                ? FieldTypeEnum.UUID.getCode()
                                : nativeType.equals("text")
                                        ? FieldTypeEnum.TEXTAREA.getCode()
                                        : nativeType.startsWith("character varying")
                                                ? FieldTypeEnum.TEXT.getCode()
                                                : FieldTypeEnum.INTEGER.getCode();
                Integer length =
                        logical.equals(FieldTypeEnum.TEXT.getCode())
                                ? Math.min(
                                        4000, Integer.parseInt(nativeType.replaceAll("[^0-9]", "")))
                                : null;
                String column = code + "_id";
                if (sourceFields.stream()
                        .anyMatch(f -> f.code().equals(column) && !fieldId.equals(f.id())))
                    throw invalid("关系字段编码已存在");
                FieldDefinition field =
                        new FieldDefinition(
                                fieldId,
                                fieldId,
                                column,
                                r.name(),
                                logical,
                                length,
                                null,
                                null,
                                required,
                                RelationTypeEnum.ONE_TO_ONE.matches(r.kind()),
                                9000 + normalized.size());
                objects.upsertField(h.getVersionId(), sourceTableId, field);
                // 生成引用列的存储配置由关系维护；已有关系在本次设计中写入的对象规则需保留。
                var kept = old == null ? null : designReader.options(h.getVersionId()).get(fieldId);
                var option =
                        new FieldOptions(
                                column,
                                DataClassificationEnum.NORMAL.getCode(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                MemberStateEnum.ACTIVE.getCode(),
                                List.of(),
                                null,
                                null,
                                DisplayResolverEnum.RECORD_TITLE.getCode(),
                                nativeType,
                                false,
                                true,
                                null,
                                null,
                                null,
                                kept == null ? null : kept.rules());
                store.updateFieldOptions(
                        h.getVersionId(),
                        Long.parseLong(fieldId),
                        designCodec.write(option),
                        MemberStateEnum.ACTIVE.getCode(),
                        DataClassificationEnum.NORMAL.getCode(),
                        column,
                        true);
            }
            String targetFieldId =
                    designReader.options(target.getVersionId()).entrySet().stream()
                            .filter(e -> keys.getFirst().name().equals(e.getValue().columnName()))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
            normalized.add(
                    new Relation(
                            id,
                            code,
                            r.name(),
                            r.kind(),
                            r.targetObjectId(),
                            fieldId,
                            targetFieldId,
                            required,
                            onDelete,
                            r.sourceDetailId()));
        }
        if (normalized.stream()
                        .filter(r -> RelationTypeEnum.MASTER_DETAIL.matches(r.kind()))
                        .count()
                > 1) throw invalid("依附对象只能有一个主归属");
        for (DataCenter.Relation old : previous.values())
            if (old.fieldId() != null
                    && generatedIds.contains(old.fieldId())
                    && normalized.stream()
                            .noneMatch(r -> Objects.equals(old.fieldId(), r.fieldId())))
                objects.deleteField(h.getVersionId(), Long.parseLong(old.fieldId()));
        store.deleteRelations(h.getVersionId());
        // 同一草稿里已逻辑删除的同编码旧行让出 (版本, 编码) 唯一约束；只作用于草稿版本，已发布版本不变。
        normalized.forEach(r -> store.purgeDeletedRelationCode(h.getVersionId(), r.code(), r.id()));
        normalized.forEach(r -> store.insertRelation(h.getVersionId(), r));
        checkMasterCycle(h.getId(), new HashSet<>());
    }

    private FieldOptions normalizeReferenceField(
            ObjectDraftHeadDO head,
            long tableId,
            FieldDefinition field,
            FieldOptions previous,
            String nativeType,
            boolean required,
            boolean unique) {
        FieldDefinition reference =
                new FieldDefinition(
                        field.key(),
                        field.id(),
                        field.code(),
                        field.name(),
                        FieldTypeEnum.REFERENCE.getCode(),
                        null,
                        null,
                        null,
                        required,
                        unique,
                        field.sort());
        String column = previous.columnName() == null ? field.code() : previous.columnName();
        // 复用列的单值关系每次保存对象都会走到这里：只重置存储相关配置，字段上的对象规则
        // （显示名字段、引用筛选、数据联动）原样保留，不能在保存时被清掉。
        FieldOptions option =
                FieldOptions.copyOf(previous)
                        .columnName(column)
                        .defaultValue(null)
                        .pattern(null)
                        .minimum(null)
                        .maximum(null)
                        .options(List.of())
                        .expression(null)
                        .resultType(null)
                        .resolver(DisplayResolverEnum.RECORD_TITLE.getCode())
                        .nativeType(nativeType)
                        .primaryKey(false)
                        .generated(false)
                        .selection(null)
                        .calculation(null)
                        .autoNumber(null)
                        .build();
        objects.upsertField(head.getVersionId(), tableId, reference);
        store.updateFieldOptions(
                head.getVersionId(),
                Long.parseLong(field.id()),
                designCodec.write(option),
                option.state(),
                option.classification(),
                column,
                false);
        return option;
    }

    void checkMasterCycle(long id, Set<Long> visited) {
        if (!visited.add(id)) throw invalid("主从关系不能形成循环");
        ObjectDraftHeadDO h = designReader.head(Long.toString(id), false);
        for (DataCenter.Relation r : store.relations(h.getVersionId()))
            if (RelationTypeEnum.MASTER_DETAIL.matches(r.kind()))
                checkMasterCycle(Long.parseLong(r.targetObjectId()), new HashSet<>(visited));
        if (visited.size() > 8) throw invalid("主从关系层级过深");
    }
}
