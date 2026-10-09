package com.lingan.ucp.nocode.report.service.dataset;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.FieldConversionCompatibility;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.ObjectTables;
import com.lingan.ucp.nocode.api.OrderedCalculations;
import com.lingan.ucp.nocode.api.ReportDatasets;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.RelationTypeEnum;
import com.lingan.ucp.nocode.enums.ReportDatasetFieldRoleEnum;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 先校验有界结构，再通过已有对象 API 读取精确版本；不创建数据源，不写共享授权。 */
@Service
public class ReportDatasetSourceServiceImpl implements ReportDatasetSourceService {
    @Resource private DataObjectApi objects;
    @Resource private OrderedCalculationStateService orderedStates;

    private record Node(DataObjectApi.PublishedObject object, List<String> relationPath) {}

    @Override
    public ReportDatasets.ResolvedSource resolve(ReportDatasets.Source input) {
        ReportDatasets.Source source = normalize(input);
        Map<String, DataObjectApi.PublishedObject> versions = new LinkedHashMap<>();
        Map<String, ReportDatasets.ObjectReference> references = new LinkedHashMap<>();
        Map<String, Set<String>> required = new LinkedHashMap<>();
        Map<String, Set<String>> relations = new LinkedHashMap<>();
        Map<String, Node> paths = new LinkedHashMap<>();
        paths.put("", new Node(load(source.root(), versions, references), List.of()));
        // 父节点先解析，允许客户端按任意展示顺序保存关系；深度有限，不递归遍历用户输入。
        for (ReportDatasets.Relation item :
                source.relations().stream()
                        .sorted(Comparator.comparingInt(r -> r.parentPath().size()))
                        .toList()) {
            Node parent = node(paths, item.parentPath());
            DataCenter.Definition owner = parent.object().definition();
            DataCenter.Relation relation =
                    owner.relations().stream()
                            .filter(r -> Objects.equals(r.id(), item.relationId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("数据集关联不存在：" + item.id()));
            if (relation.sourceDetailId() != null
                    || RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
                throw invalid("数据集首批只支持主表发出的单值关联：" + item.id());
            RelationTypeEnum.fromCode(relation.kind());
            if (!Objects.equals(relation.targetObjectId(), item.target().objectId()))
                throw invalid("数据集关联目标与对象定义不一致：" + item.id());
            relations
                    .computeIfAbsent(owner.objectId(), ignored -> new HashSet<>())
                    .add(relation.id());
            required.computeIfAbsent(owner.objectId(), ignored -> new HashSet<>())
                    .add(relation.fieldId());
            FieldDefinition reference = field(owner, relation.fieldId());
            DataCenter.FieldOptions referenceOptions =
                    owner.fieldOptions()
                            .getOrDefault(reference.id(), DataCenter.FieldOptions.defaults());
            // OS 自动关系列保留目标主键的原生逻辑类型；手工复用列由对象设计器规范为 REFERENCE。
            boolean generatedReference =
                    Boolean.TRUE.equals(referenceOptions.generated())
                            && Set.of(
                                            FieldTypeEnum.INTEGER,
                                            FieldTypeEnum.UUID,
                                            FieldTypeEnum.TEXT,
                                            FieldTypeEnum.TEXTAREA)
                                    .contains(FieldTypeEnum.fromCode(reference.type()));
            if (!FieldTypeEnum.REFERENCE.matches(reference.type()) && !generatedReference)
                throw invalid("数据集关联来源必须是对象引用字段：" + item.id());
            DataObjectApi.PublishedObject target = load(item.target(), versions, references);
            requirePrimaryTarget(relation, target.definition());
            if (relation.targetFieldId() != null && !relation.targetFieldId().isBlank())
                required.computeIfAbsent(target.objectId(), ignored -> new HashSet<>())
                        .add(relation.targetFieldId());
            List<String> relationPath = new ArrayList<>(parent.relationPath());
            if (relationPath.contains(relation.id()))
                throw invalid("数据集关联路径不能重复经过同一关系：" + item.id());
            relationPath.add(relation.id());
            List<String> aliasPath = new ArrayList<>(item.parentPath());
            aliasPath.add(item.id());
            paths.put(key(aliasPath), new Node(target, List.copyOf(relationPath)));
        }
        List<ReportDatasets.ResolvedField> fields = new ArrayList<>();
        for (ReportDatasets.Field item : source.fields()) {
            Node owner = node(paths, item.path());
            FieldDefinition field = field(owner.object().definition(), item.sourceFieldId());
            required.computeIfAbsent(owner.object().objectId(), ignored -> new HashSet<>())
                    .add(field.id());
            if (!FieldTypeEnum.fromCode(field.type()).supportsReportGrouping())
                throw invalid("数据集字段暂不支持分析：" + item.name());
            ReportDatasetFieldRoleEnum role = ReportDatasetFieldRoleEnum.fromCode(item.role());
            if (role == ReportDatasetFieldRoleEnum.MEASURE
                    && (!item.path().isEmpty()
                            || !FieldTypeEnum.fromCode(field.type()).isNumeric()
                            || owner.object().definition().relations().stream()
                                    .anyMatch(
                                            relation ->
                                                    Objects.equals(
                                                            relation.fieldId(), field.id()))))
                throw invalid("度量字段必须是根对象的数值字段：" + item.name());
            fields.add(
                    new ReportDatasets.ResolvedField(
                            item.id(),
                            item.name(),
                            role.getCode(),
                            field.type(),
                            owner.object().objectId(),
                            owner.object().versionNo(),
                            owner.relationPath(),
                            field.id()));
        }
        requireCompatible(versions, required, relations);
        return new ReportDatasets.ResolvedSource(
                source, List.copyOf(references.values()), List.copyOf(fields));
    }

    /** 历史 JSON 的校验和不代表当前业务表仍兼容；仅检查真正使用的字段及关联。 */
    private void requireCompatible(
            Map<String, DataObjectApi.PublishedObject> versions,
            Map<String, Set<String>> required,
            Map<String, Set<String>> usedRelations) {
        for (DataObjectApi.PublishedObject version : versions.values()) {
            DataObjectApi.PublishedObject latest = objects.getVersion(version.objectId(), null);
            if (latest == null || latest.definition() == null) throw invalid("数据集来源对象当前版本不可用");
            DataCenter.Definition before = version.definition(), after = latest.definition();
            if (!ReportReadCompatibility.table(before, after))
                throw invalid("数据集来源对象表绑定已不兼容，请更新数据集来源");
            Map<String, FieldConversionCompatibility.Field> oldFields =
                    FieldConversionCompatibility.fields(before);
            Map<String, FieldConversionCompatibility.Field> newFields =
                    FieldConversionCompatibility.fields(after);
            for (String id : required.getOrDefault(version.objectId(), Set.of()))
                if (!ReportReadCompatibility.field(oldFields.get(id), newFields.get(id)))
                    throw invalid("数据集来源字段已不兼容，请更新数据集来源：" + id);
            for (String id : usedRelations.getOrDefault(version.objectId(), Set.of())) {
                DataCenter.Relation old =
                        before.relations().stream()
                                .filter(relation -> relation.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                DataCenter.Relation next =
                        after.relations().stream()
                                .filter(relation -> relation.id().equals(id))
                                .findFirst()
                                .orElse(null);
                if (next == null
                        || !Objects.equals(old.kind(), next.kind())
                        || !Objects.equals(old.fieldId(), next.fieldId())
                        || !Objects.equals(old.targetObjectId(), next.targetObjectId())
                        || !Objects.equals(old.targetFieldId(), next.targetFieldId())
                        || !Objects.equals(old.sourceDetailId(), next.sourceDetailId()))
                    throw invalid("数据集来源关联已不兼容，请更新数据集来源：" + id);
            }
        }
    }

    private ReportDatasets.Source normalize(ReportDatasets.Source input) {
        if (input == null || input.schemaVersion() != ReportDatasets.SOURCE_SCHEMA_VERSION)
            throw invalid("数据集来源协议版本无效");
        reference(input.root());
        List<ReportDatasets.Relation> relations =
                input.relations() == null ? List.of() : input.relations();
        if (relations.size() > ReportDatasets.MAX_RELATIONS) throw invalid("数据集关联最多 50 个");
        if (input.fields() == null
                || input.fields().isEmpty()
                || input.fields().size() > ReportDatasets.MAX_FIELDS)
            throw invalid("数据集字段应为 1 到 200 个");
        Set<String> aliases = new HashSet<>();
        List<ReportDatasets.Relation> normalizedRelations = new ArrayList<>();
        for (ReportDatasets.Relation relation : relations) {
            if (relation == null) throw invalid("数据集关联不能为空");
            id(relation.id(), "关联别名");
            id(relation.relationId(), "关联");
            reference(relation.target());
            List<String> parent = path(relation.parentPath());
            if (!aliases.add(relation.id())
                    || parent.contains(relation.id())
                    || parent.size() >= ReportDatasets.MAX_RELATION_DEPTH)
                throw invalid("数据集关联别名重复、循环或超过两层");
            normalizedRelations.add(
                    new ReportDatasets.Relation(
                            relation.id(), parent, relation.relationId(), relation.target()));
        }
        Set<String> fieldIds = new HashSet<>();
        List<ReportDatasets.Field> normalizedFields = new ArrayList<>();
        for (ReportDatasets.Field field : input.fields()) {
            if (field == null) throw invalid("数据集字段不能为空");
            id(field.id(), "数据集字段");
            id(field.sourceFieldId(), "来源字段");
            if (!fieldIds.add(field.id())) throw invalid("数据集字段编码重复：" + field.id());
            if (field.name() == null || field.name().isBlank() || field.name().trim().length() > 80)
                throw invalid("数据集字段名称应为 1 到 80 个字符");
            normalizedFields.add(
                    new ReportDatasets.Field(
                            field.id(),
                            path(field.path()),
                            field.sourceFieldId(),
                            field.name().trim(),
                            ReportDatasetFieldRoleEnum.fromCode(field.role()).getCode()));
        }
        // 复制集合，避免后续调用者修改原请求导致已解析结构改变。
        return new ReportDatasets.Source(
                input.schemaVersion(),
                input.root(),
                List.copyOf(normalizedRelations),
                List.copyOf(normalizedFields));
    }

    private DataObjectApi.PublishedObject load(
            ReportDatasets.ObjectReference reference,
            Map<String, DataObjectApi.PublishedObject> versions,
            Map<String, ReportDatasets.ObjectReference> references) {
        ReportDatasets.ObjectReference previous =
                references.putIfAbsent(reference.objectId(), reference);
        if (previous != null && !previous.equals(reference))
            throw invalid("同一数据集不能混用同一对象的不同版本或校验和");
        DataObjectApi.PublishedObject found = versions.get(reference.objectId());
        if (found != null) return found;
        DataObjectApi.PublishedObject published =
                objects.getVersion(reference.objectId(), reference.versionNo());
        if (published == null
                || published.definition() == null
                || !Objects.equals(published.objectId(), reference.objectId())
                || !Objects.equals(published.definition().objectId(), reference.objectId())
                || published.versionNo() != reference.versionNo()
                || !Objects.equals(published.checksum(), reference.checksum()))
            throw invalid("数据集引用的对象版本或校验和已失效");
        versions.put(reference.objectId(), published);
        return published;
    }

    private void reference(ReportDatasets.ObjectReference reference) {
        if (reference == null
                || reference.objectId() == null
                || !reference.objectId().matches("[1-9][0-9]{0,18}")
                || reference.versionNo() < 1
                || reference.checksum() == null
                || reference.checksum().isBlank()
                || reference.checksum().length() > 128) throw invalid("数据集必须引用明确的对象 ID、正版本号和校验和");
        try {
            Long.parseLong(reference.objectId());
        } catch (NumberFormatException error) {
            throw invalid("数据集对象 ID 无效");
        }
    }

    private FieldDefinition field(DataCenter.Definition owner, String id) {
        FieldDefinition field =
                owner.fields().stream()
                        .filter(f -> Objects.equals(f.id(), id))
                        .findFirst()
                        .orElseThrow(() -> invalid("数据集来源字段不存在：" + id));
        DataCenter.FieldOptions options =
                owner.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults());
        if (MemberStateEnum.INACTIVE.matches(options.state()) || Calculations.live(options))
            throw invalid("数据集来源字段已停用或尚未落库：" + field.name());
        if (Calculations.orderedStored(options)) orderedStates.requireReady(owner, List.of(id));
        return OrderedCalculations.queryField(field, options);
    }

    private void requirePrimaryTarget(DataCenter.Relation relation, DataCenter.Definition target) {
        if (relation.targetFieldId() == null || relation.targetFieldId().isBlank()) return;
        FieldDefinition field = field(target, relation.targetFieldId());
        DataCenter.FieldOptions options =
                target.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
        String column = options.columnName() == null ? field.code() : options.columnName();
        if (!Objects.equals(column, ObjectTables.main(target).keyColumn()))
            throw invalid("数据集首批关联只能指向目标主键");
    }

    private List<String> path(List<String> value) {
        if (value == null) return List.of();
        if (value.size() > ReportDatasets.MAX_RELATION_DEPTH) throw invalid("数据集关联路径最多两层");
        Set<String> seen = new HashSet<>();
        for (String item : value) {
            id(item, "路径别名");
            if (!seen.add(item)) throw invalid("数据集关联路径不能循环");
        }
        return List.copyOf(value);
    }

    private Node node(Map<String, Node> paths, List<String> path) {
        Node node = paths.get(key(path));
        if (node == null) throw invalid("数据集关联路径不存在：" + key(path));
        return node;
    }

    private String key(List<String> path) {
        return String.join("/", path);
    }

    private void id(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) throw invalid(label + "编码无效");
    }
}
