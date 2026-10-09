package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.metadata.service.table.TableBindingService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 完整对象设计聚合：主表、内部明细与对象关系共同保存，发布版本永不原地修改。 */
@Service
public class ObjectDesignService {
    private static final Pattern SUMMARY_DETAIL =
            Pattern.compile("(count|sum|avg|min|max)\\(([a-z][a-z0-9_]*)");
    @Resource private ObjectDesignCodec designCodec;
    @Resource private ObjectDesignFields designFields;
    @Resource private ObjectDesignPartsWriter partsWriter;
    @Resource private ObjectDesignReader designReader;
    @Resource private ObjectDesignReferences designReferences;
    @Resource private ObjectInactiveFields inactiveFields;
    @Resource private ObjectLifecycleChecks lifecycleChecks;
    @Resource private PlatformTransactionManager manager;
    @Resource private ObjectDraftService drafts;
    @Resource private ObjectDraftMapper objects;
    @Resource private DataCenterMapper store;
    @Resource private DraftValidator validator;
    @Resource private DatabaseMetadataReader database;
    @Resource private TableBindingService bindings;
    @Resource private PostgreSqlCommandMapper commands;
    @Resource private ObjectMapper json;
    private TransactionTemplate tx;
    @Resource private ObjectProvider<ObjectSettingsValidator> settingsValidator;

    /** 切换预检沿用草稿保存的字段配置规则，不写入草稿或业务数据。 */
    public void validateFieldOptions(FieldDefinition field, FieldOptions options) {
        designFields.validateOptions(field, options);
    }

    /** 初始化设计定义编解码和保存事务；主表、明细、关系与修订共用同一事务。 */
    @PostConstruct
    void initialize() {
        this.json = json.copy().findAndRegisterModules();
        this.tx = new TransactionTemplate(manager);
    }

    /** 保存命令为完整设计，基础字段仍支持显式补丁；缺少高级配置时保持原值。 */
    public Design save(SaveDesign request, long actor) {
        // 唯一约束冲突（含提交时检查的延迟约束）说人话，不以系统异常透给界面。
        return ObjectDesignConstraintErrors.translate(() -> saveDesign(request, actor));
    }

    private Design saveDesign(SaveDesign request, long actor) {
        if (request == null || request.draft() == null) throw invalid("对象设计必填");
        return tx.execute(
                s -> {
                    objects.lockTableName("nocode-design-write");
                    SaveObjectDraft input = request.draft();
                    ObjectDraftHeadDO before =
                            input.id() == null ? null : designReader.head(input.id(), true);
                    Design previous = before == null ? null : designReader.load(before);
                    SaveDesign normalized = remapDraftDetailCodes(request, previous, before);
                    input = normalized.draft();
                    inactiveFields.prepare(before, previous, normalized, actor);
                    if (before != null
                            && normalized.restoredFieldIds() != null
                            && !normalized.restoredFieldIds().isEmpty())
                        previous = designReader.load(before);
                    designFields.protectGenerated(previous, input);
                    if (previous != null && ObjectSourceEnum.ADOPTED.matches(previous.source())) {
                        if (input.removedFieldIds() != null && !input.removedFieldIds().isEmpty())
                            throw invalid("纳管字段不能直接删除，请通过物理差异同步维护映射");
                        for (FieldDefinition field : input.fields()) {
                            FieldDefinition original =
                                    previous.draft().fields().stream()
                                            .filter(f -> Objects.equals(f.id(), field.id()))
                                            .findFirst()
                                            .orElseThrow(() -> invalid("纳管对象不能直接新增映射列"));
                            if (!Boolean.TRUE.equals(
                                            previous.fieldOptions()
                                                    .getOrDefault(
                                                            original.id(), FieldOptions.defaults())
                                                    .generated())
                                    && (!original.code().equals(field.code())
                                            || !original.type().equals(field.type())
                                            || !Objects.equals(original.length(), field.length())
                                            || !Objects.equals(
                                                    original.precision(), field.precision())
                                            || !Objects.equals(original.scale(), field.scale())
                                            || !Objects.equals(
                                                    original.required(), field.required())
                                            || !Objects.equals(original.unique(), field.unique())))
                                throw invalid("纳管字段的物理属性由原表决定，请通过差异同步更新");
                        }
                    }
                    Settings settings =
                            normalized.settings() == null
                                    ? (previous == null ? Settings.defaults() : previous.settings())
                                    : normalized.settings();
                    validateSettings(settings);
                    input =
                            new SaveObjectDraft(
                                    input.id(),
                                    input.expectedLockVersion(),
                                    input.objectCode(),
                                    input.objectName(),
                                    input.description(),
                                    input.tableName(),
                                    input.titleFieldKey(),
                                    input.fields(),
                                    input.removedFieldIds(),
                                    settings.titleTemplate(),
                                    input.category());
                    ObjectDraft saved =
                            input.id() == null
                                    ? drafts.create(input, actor, UUID.randomUUID())
                                    : drafts.update(input, actor, UUID.randomUUID());
                    ObjectDraftHeadDO h = designReader.head(saved.id(), true);
                    store.settings(
                            h.getId(), designCodec.write(settings), settings.titleTemplate());
                    TableBinding mainBinding =
                            bindings.normalize(
                                    h,
                                    h.getTableId(),
                                    h.getTableName(),
                                    false,
                                    normalized.mainBinding(),
                                    previous == null
                                            ? TableBinding.generated(h.getSchemaName(), false)
                                            : previous.mainBinding());
                    store.tableBinding(h.getTableId(), designCodec.write(mainBinding));
                    Map<String, String> remap = designFields.remap(input.fields(), saved.fields());
                    designFields.saveOptions(
                            h,
                            saved.fields(),
                            normalized.fieldOptions(),
                            remap,
                            previous == null ? Map.of() : previous.fieldOptions(),
                            mainBinding.adopted(),
                            previous);
                    if (normalized.details() != null)
                        remap.putAll(
                                partsWriter.saveDetails(h, normalized.details(), actor, previous));
                    if (normalized.relations() != null)
                        partsWriter.saveRelations(
                                h,
                                normalized.relations().stream()
                                        .map(
                                                r ->
                                                        new Relation(
                                                                r.id(),
                                                                r.code(),
                                                                r.name(),
                                                                r.kind(),
                                                                r.targetObjectId(),
                                                                r.fieldId() == null
                                                                        ? null
                                                                        : remap.getOrDefault(
                                                                                r.fieldId(),
                                                                                r.fieldId()),
                                                                r.targetFieldId(),
                                                                r.required(),
                                                                r.onDelete(),
                                                                r.sourceDetailId() == null
                                                                        ? null
                                                                        : remap.getOrDefault(
                                                                                r.sourceDetailId(),
                                                                                r
                                                                                        .sourceDetailId())))
                                        .toList(),
                                actor);
                    if (normalized.indexes() != null)
                        partsWriter.saveIndexes(h, normalized.indexes(), remap);
                    settings =
                            new Settings(
                                    settings.icon(),
                                    settings.ownerId(),
                                    settings.organizationId(),
                                    settings.titleTemplate(),
                                    DocumentPolicies.remap(settings.documentPolicy(), remap),
                                    BusinessFilePolicies.remap(
                                            settings.businessFilePolicy(), remap));
                    store.settings(
                            h.getId(), designCodec.write(settings), settings.titleTemplate());
                    remapRuleFields(h, remap);
                    designReferences.validateReferences(designReader.head(saved.id(), false));
                    snapshot(saved.id());
                    return designReader.load(designReader.head(saved.id(), false));
                });
    }

    /** 未部署明细允许调整候选标识；最终身份保护由明细保存校验。汇总中的受控明细编码同步更新。 */
    private SaveDesign remapDraftDetailCodes(
            SaveDesign request, Design previous, ObjectDraftHeadDO before) {
        if (previous == null
                || before == null
                || request.details() == null
                || request.fieldOptions() == null) return request;
        Map<String, Detail> old =
                previous.details().stream().collect(Collectors.toMap(Detail::id, detail -> detail));
        Map<String, String> changed = new HashMap<>();
        for (DataCenter.Detail detail : request.details()) {
            if (detail.id() == null) continue;
            DataCenter.Detail original = old.get(detail.id());
            if (original != null
                    && !original.binding().adopted()
                    && !Objects.equals(original.code(), detail.code()))
                changed.put(original.code(), detail.code());
        }
        if (changed.isEmpty()) return request;
        Map<String, FieldDefinition> fields =
                request.draft().fields().stream()
                        .collect(Collectors.toMap(FieldDefinition::key, field -> field));
        Map<String, FieldOptions> options = new HashMap<>(request.fieldOptions());
        options.replaceAll(
                (key, option) -> {
                    FieldDefinition field = fields.get(key);
                    if (field == null || !FieldTypeEnum.SUMMARY.matches(field.type()))
                        return option;
                    String expression = rewriteSummaryDetailCode(option.expression(), changed);
                    if (Objects.equals(expression, option.expression())) return option;
                    return FieldOptions.copyOf(option).expression(expression).build();
                });
        return new SaveDesign(
                request.draft(),
                request.settings(),
                options,
                request.relations(),
                request.indexes(),
                request.details(),
                request.mainBinding(),
                request.restoredFieldIds());
    }

    private String rewriteSummaryDetailCode(String expression, Map<String, String> changed) {
        if (expression == null || expression.isBlank()) return expression;
        Matcher matcher = SUMMARY_DETAIL.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String code = changed.get(matcher.group(2));
            if (code == null) continue;
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(matcher.group(1) + "(" + code));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 只读停用成员目录；恢复需随保存命令提交并通过原更新权限。 */
    public List<InactiveField> inactiveFields(String id, String detailId) {
        return tx.execute(s -> inactiveFields.list(designReader.head(id, false), detailId));
    }

    private void validateSettings(Settings s) {
        if (s.icon() != null && s.icon().length() > 80) throw invalid("图标名称过长");
        if (s.ownerId() != null && !s.ownerId().isBlank()) validator.id(s.ownerId(), "负责人 ID");
        if (s.organizationId() != null && !s.organizationId().isBlank())
            validator.id(s.organizationId(), "组织 ID");
        if ((s.ownerId() != null && !s.ownerId().isBlank())
                || (s.organizationId() != null && !s.organizationId().isBlank()))
            settingsValidator
                    .getIfAvailable(
                            () -> {
                                throw invalid("底座用户/组织接口未接入");
                            })
                    .validate(s.ownerId(), s.organizationId());
        if (s.titleTemplate() != null && s.titleTemplate().length() > 512)
            throw invalid("标题模板最多 512 字符");
    }

    /** 保持已发布快照，复制稳定成员到一个新 DRAFT 后再移动对象头。 */
    public Design editPublished(Revision request, long actor) {
        return tx.execute(
                s -> {
                    ObjectDraftHeadDO h = designReader.requireRevision(request);
                    if (VersionStateEnum.DRAFT.matches(h.getVersionState()))
                        return designReader.load(h);
                    if (!ObjectStatusEnum.ACTIVE.matches(h.getStatus())
                            || store.cloneVersion(h.getId(), actor) != 1)
                        throw invalid("当前对象不能创建新草稿");
                    store.cloneTables(h.getId());
                    store.cloneFields(h.getId());
                    store.cloneRelations(h.getId());
                    store.cloneIndexes(h.getId());
                    store.advanceVersion(h.getId(), actor);
                    audit(
                            h.getId(),
                            actor,
                            AuditOperationEnum.OBJECT_DRAFT_OPEN.getCode(),
                            Map.of("baseVersion", h.getLatestVersionNo()));
                    return designReader.load(designReader.head(request.id(), false));
                });
    }

    public void snapshot(String id) {
        ObjectDraftHeadDO h = designReader.head(id, true);
        if (!VersionStateEnum.DRAFT.matches(h.getVersionState())) throw invalid("已发布版本不可修改");
        designReferences.validateReferences(h);
        String schema = designCodec.write(designReader.definition(id));
        objects.updateVersion(
                h.getVersionId(), schema, DigestUtil.sha256Hex(schema), h.getLockVersion());
    }

    public Design copy(Copy request, long actor) {
        return tx.execute(
                s -> {
                    objects.lockTableName("nocode-design-write");
                    Design source = designReader.get(request.id());
                    List<FieldDefinition> fields =
                            source.draft().fields().stream()
                                    .filter(
                                            f ->
                                                    !Boolean.TRUE.equals(
                                                            source.fieldOptions()
                                                                    .getOrDefault(
                                                                            f.id(),
                                                                            FieldOptions.defaults())
                                                                    .generated()))
                                    .map(
                                            f ->
                                                    new FieldDefinition(
                                                            "copy-" + f.id(),
                                                            null,
                                                            f.code(),
                                                            f.name(),
                                                            f.type(),
                                                            f.length(),
                                                            f.precision(),
                                                            f.scale(),
                                                            f.required(),
                                                            f.unique(),
                                                            f.sort()))
                                    .toList();
                    Map<String, FieldOptions> options = new HashMap<>();
                    fields.forEach(
                            f -> {
                                var o = source.fieldOptions().get(f.key().substring(5));
                                // 规则中的字段 ID 在全部新身份生成后统一重映射，首轮保存不带规则。
                                options.put(f.key(), copiedOptions(o));
                            });
                    String title = "copy-" + source.draft().titleFieldId();
                    if (fields.stream().noneMatch(f -> f.key().equals(title)))
                        throw invalid("原标题为关系字段，请先选择普通标题字段再复制");
                    List<Detail> details =
                            source.details().stream()
                                    .filter(d -> MemberStateEnum.ACTIVE.matches(d.state()))
                                    .map(
                                            d -> {
                                                List<FieldDefinition> fs =
                                                        d.fields().stream()
                                                                .map(
                                                                        f ->
                                                                                new FieldDefinition(
                                                                                        "copy-"
                                                                                                + f
                                                                                                        .id(),
                                                                                        null,
                                                                                        f.code(),
                                                                                        f.name(),
                                                                                        f.type(),
                                                                                        f.length(),
                                                                                        f
                                                                                                .precision(),
                                                                                        f.scale(),
                                                                                        f
                                                                                                .required(),
                                                                                        f.unique(),
                                                                                        f.sort()))
                                                                .toList();
                                                HashMap<String, DataCenter.FieldOptions>
                                                        copiedOptions =
                                                                new HashMap<String, FieldOptions>();
                                                for (FieldDefinition f : d.fields()) {
                                                    DataCenter.FieldOptions o =
                                                            d.fieldOptions()
                                                                    .getOrDefault(
                                                                            f.id(),
                                                                            FieldOptions
                                                                                    .defaults());
                                                    copiedOptions.put(
                                                            "copy-" + f.id(), copiedOptions(o));
                                                }
                                                return new Detail(
                                                        null,
                                                        d.code(),
                                                        d.name(),
                                                        request.tableName() + "_" + d.code(),
                                                        MemberStateEnum.ACTIVE.getCode(),
                                                        fs,
                                                        copiedOptions,
                                                        List.of());
                                            })
                                    .toList();
                    Set<String> copiedKeys =
                            fields.stream().map(FieldDefinition::key).collect(Collectors.toSet());
                    details.forEach(d -> d.fields().forEach(f -> copiedKeys.add(f.key())));
                    List<DataCenter.Relation> relations =
                            source.relations().stream()
                                    .map(
                                            r ->
                                                    new Relation(
                                                            null,
                                                            r.code(),
                                                            r.name(),
                                                            r.kind(),
                                                            r.targetObjectId(),
                                                            r.fieldId() != null
                                                                            && copiedKeys.contains(
                                                                                    "copy-"
                                                                                            + r
                                                                                                    .fieldId())
                                                                    ? "copy-" + r.fieldId()
                                                                    : null,
                                                            null,
                                                            r.required(),
                                                            r.onDelete(),
                                                            r.sourceDetailId() == null
                                                                    ? null
                                                                    : "detail:"
                                                                            + source
                                                                                    .details()
                                                                                    .stream()
                                                                                    .filter(
                                                                                            detail ->
                                                                                                    detail.id()
                                                                                                            .equals(
                                                                                                                    r
                                                                                                                            .sourceDetailId()))
                                                                                    .findFirst()
                                                                                    .orElseThrow()
                                                                                    .code()))
                                    .toList();
                    // 先生成新字段和关系身份，再映射全部索引，避免丢失关系自动列上的索引。
                    List<DataCenter.Index> indexes =
                            source.indexes().stream()
                                    .map(
                                            i ->
                                                    new Index(
                                                            null,
                                                            i.code(),
                                                            i.name(),
                                                            i.unique(),
                                                            i.fieldIds().stream()
                                                                    .map(fid -> "copy-" + fid)
                                                                    .toList(),
                                                            Boolean.TRUE.equals(i.parentScoped())))
                                    .toList();
                    SaveObjectDraft input =
                            new SaveObjectDraft(
                                    null,
                                    null,
                                    request.objectCode(),
                                    request.objectName(),
                                    source.draft().description(),
                                    request.tableName(),
                                    title,
                                    fields,
                                    List.of(),
                                    source.settings().titleTemplate(),
                                    source.draft().category());
                    Design result =
                            save(
                                    new SaveDesign(
                                            input,
                                            new Settings(
                                                    source.settings().icon(),
                                                    source.settings().ownerId(),
                                                    source.settings().organizationId(),
                                                    source.settings().titleTemplate()),
                                            options,
                                            relations,
                                            List.of(),
                                            details),
                                    actor);
                    DataCenter.Design copied = result;
                    Map<String, String> copiedIds =
                            designFields.remap(fields, copied.draft().fields());
                    for (DataCenter.Detail detail : details) {
                        DataCenter.Detail savedDetail =
                                copied.details().stream()
                                        .filter(d -> d.code().equals(detail.code()))
                                        .findFirst()
                                        .orElseThrow();
                        copiedIds.putAll(designFields.remap(detail.fields(), savedDetail.fields()));
                    }
                    for (DataCenter.Relation relation : source.relations()) {
                        if (relation.fieldId() == null) continue;
                        DataCenter.Relation savedRelation =
                                copied.relations().stream()
                                        .filter(r -> r.code().equals(relation.code()))
                                        .findFirst()
                                        .orElseThrow();
                        copiedIds.put("copy-" + relation.fieldId(), savedRelation.fieldId());
                    }
                    partsWriter.saveIndexes(
                            designReader.head(copied.draft().id(), true), indexes, copiedIds);
                    Map<String, String> policyIds = new HashMap<>();
                    copiedIds.forEach(
                            (key, value) -> {
                                if (key.startsWith("copy-")) policyIds.put(key.substring(5), value);
                            });
                    source.details()
                            .forEach(
                                    detail ->
                                            copied.details().stream()
                                                    .filter(t -> t.code().equals(detail.code()))
                                                    .findFirst()
                                                    .ifPresent(
                                                            t ->
                                                                    policyIds.put(
                                                                            detail.id(), t.id())));
                    DataCenter.Settings originalSettings = source.settings();
                    DataCenter.Settings copiedSettings =
                            new Settings(
                                    originalSettings.icon(),
                                    originalSettings.ownerId(),
                                    originalSettings.organizationId(),
                                    originalSettings.titleTemplate(),
                                    DocumentPolicies.remap(
                                            originalSettings.documentPolicy(), policyIds),
                                    BusinessFilePolicies.remap(
                                            originalSettings.businessFilePolicy(), policyIds));
                    store.settings(
                            Long.parseLong(copied.draft().id()),
                            designCodec.write(copiedSettings),
                            copiedSettings.titleTemplate());
                    copyRules(source, copied.draft().id(), policyIds);
                    snapshot(copied.draft().id());
                    result = designReader.load(designReader.head(copied.draft().id(), false));
                    audit(
                            Long.parseLong(result.draft().id()),
                            actor,
                            AuditOperationEnum.OBJECT_COPY.getCode(),
                            Map.of("source", request.id()));
                    return result;
                });
    }

    /** 复制对象的扩展配置：重置物理身份并暂不携带规则，规则待新身份生成后由 copyRules 写回。 */
    private static FieldOptions copiedOptions(FieldOptions o) {
        return FieldOptions.copyOf(o)
                .columnName(null)
                .state(MemberStateEnum.ACTIVE.getCode())
                .nativeType(null)
                .primaryKey(false)
                .generated(false)
                .rules(null)
                .build();
    }

    /**
     * 复制品的字段规则：当前字段（FORM_FIELD）改指复制品字段；来源为原对象自身的数据联动改为复制品自身。 引用筛选的显示名与条件字段属于关系目标对象，复制后关系目标不变，
     * 因而保持原值。复制品发布前仍按完整规则校验兜底。
     */
    private void copyRules(Design source, String copiedId, Map<String, String> ids) {
        Map<String, FieldOptions> originals = new HashMap<>(source.fieldOptions());
        source.details().forEach(detail -> originals.putAll(detail.fieldOptions()));
        var h = designReader.head(copiedId, true);
        var current = designReader.options(h.getVersionId());
        String sourceId = source.draft().id();
        for (var entry : originals.entrySet()) {
            var rules = entry.getValue().rules();
            String copiedField = ids.get(entry.getKey());
            if (rules == null || copiedField == null || !current.containsKey(copiedField)) continue;
            var updated =
                    current.get(copiedField).withRules(remapRules(rules, ids, sourceId, copiedId));
            store.updateFieldOptions(
                    h.getVersionId(),
                    Long.parseLong(copiedField),
                    designCodec.write(updated),
                    updated.state(),
                    updated.classification(),
                    updated.columnName(),
                    Boolean.TRUE.equals(updated.generated()));
        }
    }

    /** 新建字段保存前只有临时 key；规则中的当前字段（FORM_FIELD）按本次保存生成的稳定 ID 改写。 */
    private void remapRuleFields(ObjectDraftHeadDO h, Map<String, String> keys) {
        for (var entry : designReader.options(h.getVersionId()).entrySet()) {
            var o = entry.getValue();
            if (o.rules() == null) continue;
            var rules = remapRules(o.rules(), keys, null, null);
            if (rules.equals(o.rules())) continue;
            var updated = o.withRules(rules);
            store.updateFieldOptions(
                    h.getVersionId(),
                    Long.parseLong(entry.getKey()),
                    designCodec.write(updated),
                    updated.state(),
                    updated.classification(),
                    updated.columnName(),
                    Boolean.TRUE.equals(updated.generated()));
        }
    }

    static FieldRules remapRules(
            FieldRules rules, Map<String, String> ids, String sourceId, String copiedId) {
        var reference = rules.reference();
        var linkage = rules.linkage();
        boolean self =
                sourceId != null
                        && linkage != null
                        && Objects.equals(linkage.sourceObjectId(), sourceId);
        return new FieldRules(
                reference == null
                        ? null
                        : new FieldRules.Reference(
                                reference.labelFieldId(),
                                remapConditions(reference.filter(), ids, false)),
                linkage == null
                        ? null
                        : new FieldRules.Linkage(
                                self ? copiedId : linkage.sourceObjectId(),
                                remapConditions(linkage.conditions(), ids, self),
                                self
                                        ? ids.getOrDefault(
                                                linkage.valueFieldId(), linkage.valueFieldId())
                                        : linkage.valueFieldId(),
                                linkage.multiRow(),
                                linkage.readOnly(),
                                // 自动更新开关与空值填入原样带上：这里每次设计保存与对象复制都会重建联动，漏带即静默丢失。
                                linkage.autoUpdate(),
                                linkage.emptyValue()),
                rules.defaultFormula(),
                rules.rounding(),
                null,
                null);
    }

    /** 当前字段总是本对象字段；条件字段只在来源为原对象自身时随复制改指。 */
    private static List<FieldRules.Condition> remapConditions(
            List<FieldRules.Condition> conditions, Map<String, String> ids, boolean self) {
        if (conditions == null) return null;
        List<FieldRules.Condition> result = new ArrayList<>();
        for (var c : conditions) {
            if (c == null) {
                result.add(null);
                continue;
            }
            String field =
                    self && !FieldRules.RECORD_KEY.equals(c.fieldId())
                            ? ids.getOrDefault(c.fieldId(), c.fieldId())
                            : c.fieldId();
            String formField =
                    c.formFieldId() == null
                            ? null
                            : ids.getOrDefault(c.formFieldId(), c.formFieldId());
            result.add(
                    new FieldRules.Condition(
                            field, c.operator(), c.valueSource(), c.value(), formField));
        }
        return result;
    }

    public Design lifecycle(Revision request, String action, long actor) {
        return tx.execute(
                s -> {
                    ObjectDraftHeadDO h = designReader.requireRevision(request);
                    String next =
                            switch (LifecycleActionEnum.fromCode(action)) {
                                case ENABLE ->
                                        h.getCurrentPublishedVersionNo() == null
                                                ? ObjectStatusEnum.DRAFT.getCode()
                                                : ObjectStatusEnum.ACTIVE.getCode();
                                case DISABLE -> ObjectStatusEnum.DISABLED.getCode();
                                case DELETE -> ObjectStatusEnum.DELETED.getCode();
                                default -> throw invalid("对象操作无效");
                            };
                    validator.text(request.reason(), "操作原因", 1000);
                    lifecycleChecks.requireAllowed(h, LifecycleActionEnum.fromCode(action));
                    Design result = designReader.load(h);
                    store.setLifecycle(h.getId(), next, actor);
                    // 删除的对象不再以字段规则引用其它对象，释放对方字段的停用限制。
                    if (LifecycleActionEnum.DELETE.matches(action))
                        store.deleteDependencies(
                                DependencyKindEnum.OBJECT_RULE.getCode(),
                                h.getId().toString(),
                                Long.toString(actor));
                    audit(
                            h.getId(),
                            actor,
                            switch (LifecycleActionEnum.fromCode(action)) {
                                case ENABLE -> AuditOperationEnum.OBJECT_ENABLE.getCode();
                                case DISABLE -> AuditOperationEnum.OBJECT_DISABLE.getCode();
                                case DELETE -> AuditOperationEnum.OBJECT_DELETE.getCode();
                            },
                            Map.of("reason", request.reason()));
                    return LifecycleActionEnum.DELETE.matches(action)
                            ? result
                            : designReader.load(designReader.head(request.id(), false));
                });
    }

    public void audit(long id, long actor, String operation, Object detail) {
        objects.insertAudit(
                UUID.randomUUID().toString(), actor, operation, id, designCodec.write(detail));
    }

    public ObjectDraftHeadDO head(String id, boolean lock) {
        return designReader.head(id, lock);
    }

    public Design get(String id) {
        return designReader.get(id);
    }

    public com.richuang.os.framework.common.pojo.PageResult<ObjectRow> page(
            int pageNo,
            int pageSize,
            String name,
            String code,
            String status,
            String source,
            String owner) {
        return page(pageNo, pageSize, name, code, status, source, owner, null);
    }

    public com.richuang.os.framework.common.pojo.PageResult<ObjectRow> page(
            int pageNo,
            int pageSize,
            String name,
            String code,
            String status,
            String source,
            String owner,
            String category) {
        return designReader.page(pageNo, pageSize, name, code, status, source, owner, category);
    }

    /** 分类目录不受当前分页限制。 */
    public List<String> categories() {
        return designReader.categories();
    }

    public ObjectDraftHeadDO requireRevision(Revision request) {
        return designReader.requireRevision(request);
    }

    public Definition definition(String id) {
        return designReader.definition(id);
    }

    public Definition published(String id) {
        return designReader.published(id);
    }

    public String version(String id, int versionNo) {
        return designReader.version(id, versionNo);
    }

    public String write(Object value) {
        return designCodec.write(value);
    }

    public <T> T read(String value, Class<T> type) {
        return designCodec.read(value, type);
    }
}
