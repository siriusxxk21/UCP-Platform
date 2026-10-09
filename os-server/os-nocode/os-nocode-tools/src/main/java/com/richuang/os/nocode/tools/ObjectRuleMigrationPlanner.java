package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.ObjectRuleMigrationForms.*;
import static com.richuang.os.nocode.tools.ObjectRuleMigrationReport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.FormFillModeEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;
import com.richuang.os.nocode.enums.RuleValueSourceEnum;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * dry-run：只读扫描已发布应用的表单关联带入与选项类默认值，生成迁移报告（设计稿 9.1、9.2、15.5）。
 *
 * <p>读取走 {@link Source}（JDBC 只读查询）；只有「要写的对象上的未发布草稿是否与已发布版本一致」经 {@link Drafts} 交给对象设计服务判断（{@link
 * ObjectRuleMigrationDrafts}，单独的只回滚事务）。
 */
final class ObjectRuleMigrationPlanner {
    /** 迁移生成的多行档位：主键相等最多一行，ERROR 让数据损坏时明确报错（9.1）。 */
    static final String MULTI_ROW = "ERROR";

    private final Source source;
    private final Drafts drafts;
    private final ObjectMapper json;

    ObjectRuleMigrationPlanner(Source source, Drafts drafts, ObjectMapper json) {
        this.source = source;
        this.drafts = drafts;
        this.json = json;
    }

    /** 对象上未发布草稿与已发布版本的比较；对象没有草稿时返回 null。 */
    interface Drafts {
        ObjectRuleMigrationDrafts.Verdict judge(String objectId);
    }

    /** 已发布应用：published 为当前发布快照的 definition，draft 为设计草稿。 */
    record AppState(
            String id,
            String code,
            String name,
            int publishedVersion,
            ApplicationCenter.Definition published,
            JsonNode draft) {}

    /** 对象当前发布版本；draftOpen 表示对象上有未发布草稿。 */
    record ObjectState(
            String id,
            String code,
            String name,
            int publishedVersion,
            DataCenter.Definition definition,
            boolean draftOpen) {}

    interface Source {
        List<AppState> applications(String prefix);

        List<String> objectIds(String prefix);

        ObjectState object(String objectId);

        DataCenter.Definition version(String objectId, int versionNo);
    }

    /** 以 dry-run 口径计算报告；不写任何数据。 */
    ObjectRuleMigrationReport plan(String prefix) {
        var warnings = new ArrayList<String>();
        var fills = new ArrayList<Fill>();
        var forms = new LinkedHashMap<String, FormScan>();
        var apps = source.applications(prefix);
        var objectCache = new HashMap<String, ObjectState>();
        for (var app : apps) scanApplication(app, fills, forms, warnings);
        var classified = classify(fills, objectCache, warnings);
        var conflicts = conflicts(classified);
        var objects = objectChanges(prefix, classified, objectCache);
        var applications = applicationChanges(apps, classified, forms, objects, objectCache);
        var impacts = impacts(apps, classified, forms, applications);
        var blockers = new ArrayList<String>();
        var infos = new ArrayList<String>();
        for (var o : objects) {
            if (o.blocker() != null) blockers.add(o.blocker());
            if (o.reusedDraftVersion() != null)
                infos.add(
                        "对象「"
                                + o.objectName()
                                + "」存在与已发布版本 v"
                                + o.publishedVersion()
                                + " 一致的空草稿 v"
                                + o.reusedDraftVersion()
                                + "，apply 时沿用该草稿写入并发布（apply 前会再比较一次，不一致即拒绝）");
            if (!o.optionDefaults().isEmpty())
                warnings.add(
                        "对象「"
                                + o.objectName()
                                + "」要清理选项类默认值（发布时删除列 DEFAULT）：不可回滚，rollback 不回退该对象的固定版本");
        }
        var summary =
                new Summary(
                        classified.size(),
                        count(classified, PLANNED),
                        count(classified, ALREADY_APPLIED),
                        count(classified, CONFLICT),
                        count(classified, BLOCKED),
                        count(classified, DETAIL_NOT_MIGRATED),
                        objects.stream().mapToInt(o -> o.optionDefaults().size()).sum(),
                        applications.stream()
                                .flatMap(a -> a.forms().stream())
                                .mapToInt(f -> f.optionDefaults().size())
                                .sum(),
                        objects.size(),
                        applications.size(),
                        impacts.size(),
                        blockers.size(),
                        infos.size());
        return new ObjectRuleMigrationReport(
                TOOL,
                FORMAT_VERSION,
                OffsetDateTime.now().toString(),
                prefix,
                summary,
                classified,
                conflicts,
                objects,
                applications,
                impacts,
                blockers,
                warnings,
                infos);
    }

    // ── 扫描表单 ──

    /** 一张表单的扫描结果；nodeKeys 记录每个节点绑定的字段，供影响面判断。 */
    private record FormScan(
            AppState app,
            ApplicationCenter.Resource resource,
            String objectId,
            Map<String, String> nodeFields,
            List<FormOptionDefault> optionDefaults) {}

    private void scanApplication(
            AppState app, List<Fill> fills, Map<String, FormScan> forms, List<String> warnings) {
        var pins = new HashMap<String, ApplicationCenter.ObjectReference>();
        for (var ref : app.published().objects()) pins.put(ref.objectId(), ref);
        for (var resource : app.published().resources()) {
            if (!FORM.equals(resource.kind())) continue;
            JsonNode form = json.valueToTree(resource.config());
            String objectId = text(form, "objectId");
            var pin = objectId == null ? null : pins.get(objectId);
            if (pin == null) {
                warnings.add(where(app, resource) + "：表单对象未在应用中固定版本，已跳过");
                continue;
            }
            var d = source.version(objectId, pin.versionNo());
            var nodeFields = new LinkedHashMap<String, String>();
            var optionDefaults = new ArrayList<FormOptionDefault>();
            for (var entry : fieldNodes(form).entrySet()) {
                String key = entry.getKey(), detailId = detailOf(key);
                var node = entry.getValue();
                String fieldId = text(node, "fieldId");
                nodeFields.put(key, fieldId);
                var owner = detailId == null ? d : detailDefinition(d, detailId);
                var field = owner == null ? null : field(owner, fieldId);
                var fill = fill(node);
                if (fill != null)
                    fills.add(
                            detailId == null
                                    ? fillItem(app, resource, key, d, field, fill, pins)
                                    : detailFill(app, resource, key, detailId, d, field, fill));
                var defaultValue = selectionDefault(node);
                if (defaultValue != null
                        && field != null
                        && BusinessFields.relation(owner, fieldId) == null
                        && FieldRuleMatrix.optionType(field))
                    optionDefaults.add(
                            new FormOptionDefault(
                                    key,
                                    detailId,
                                    fieldId,
                                    field.name(),
                                    field.type(),
                                    json.convertValue(defaultValue, Object.class)));
            }
            forms.put(
                    formKey(app.id(), resource.id()),
                    new FormScan(app, resource, objectId, nodeFields, optionDefaults));
        }
    }

    private Fill fillItem(
            AppState app,
            ApplicationCenter.Resource resource,
            String key,
            DataCenter.Definition d,
            FieldDefinition target,
            JsonNode fill,
            Map<String, ApplicationCenter.ObjectReference> pins) {
        String sourceFieldId = text(fill, "sourceFieldId"),
                valueFieldId = text(fill, "valueFieldId"),
                mode = text(fill, "mode");
        var relation =
                d.relations().stream()
                        .filter(
                                r ->
                                        Objects.equals(r.fieldId(), sourceFieldId)
                                                && r.sourceDetailId() == null
                                                && !BusinessFields.multiple(r))
                        .findFirst()
                        .orElse(null);
        var sourcePin = relation == null ? null : pins.get(relation.targetObjectId());
        var sourceDefinition =
                sourcePin == null
                        ? null
                        : source.version(relation.targetObjectId(), sourcePin.versionNo());
        var valueField = sourceDefinition == null ? null : field(sourceDefinition, valueFieldId);
        var valueOptions =
                valueField == null ? null : sourceDefinition.fieldOptions().get(valueField.id());
        var calculation = valueOptions == null ? null : valueOptions.calculation();
        String reason = null;
        if (target == null) reason = "目标字段在表单固定的对象版本中不存在";
        else if (relation == null) reason = "来源不是主表单的单值关联字段";
        else if (sourceDefinition == null) reason = "来源对象未在应用中固定版本";
        else if (valueField == null) reason = "来源值字段在来源对象固定版本中不存在";
        var linkage =
                relation == null
                        ? null
                        : new FieldRules.Linkage(
                                relation.targetObjectId(),
                                List.of(
                                        new FieldRules.Condition(
                                                FieldRules.RECORD_KEY,
                                                "eq",
                                                RuleValueSourceEnum.FORM_FIELD.getCode(),
                                                null,
                                                sourceFieldId)),
                                valueFieldId,
                                MULTI_ROW,
                                false,
                                null,
                                null);
        return new Fill(
                app.id(),
                app.code(),
                resource.id(),
                resource.name(),
                key,
                null,
                d.objectId(),
                d.objectName(),
                target == null ? text(fill, "targetFieldId") : target.id(),
                target == null ? null : target.name(),
                target == null ? null : target.type(),
                sourceFieldId,
                relation == null ? null : relation.name(),
                relation == null ? null : relation.targetObjectId(),
                sourceDefinition == null ? null : sourceDefinition.objectName(),
                valueFieldId,
                valueField == null ? null : valueField.name(),
                valueField == null ? null : valueField.type(),
                valueOptions == null ? null : valueOptions.resultType(),
                calculation == null ? null : calculation.mode(),
                calculation == null ? null : calculation.updateMode(),
                storage(valueField, valueOptions),
                mode,
                modeNote(mode),
                linkage,
                reason == null ? PLANNED : BLOCKED,
                reason);
    }

    private Fill detailFill(
            AppState app,
            ApplicationCenter.Resource resource,
            String key,
            String detailId,
            DataCenter.Definition d,
            FieldDefinition target,
            JsonNode fill) {
        return new Fill(
                app.id(),
                app.code(),
                resource.id(),
                resource.name(),
                key,
                detailId,
                d.objectId(),
                d.objectName(),
                target == null ? null : target.id(),
                target == null ? null : target.name(),
                target == null ? null : target.type(),
                text(fill, "sourceFieldId"),
                null,
                null,
                null,
                text(fill, "valueFieldId"),
                null,
                null,
                null,
                null,
                null,
                null,
                text(fill, "mode"),
                modeNote(text(fill, "mode")),
                null,
                DETAIL_NOT_MIGRATED,
                "明细表单上的关联带入本工具不迁移，表单配置原样保留；请人工改配到对象明细字段的数据联动");
    }

    /** 来源值字段的取值方式：公式、汇总字段要先补全计算值（15.5.2）。 */
    private static String storage(FieldDefinition field, DataCenter.FieldOptions options) {
        if (field == null || !FieldTypeEnum.containsCode(field.type())) return null;
        if (!FieldTypeEnum.fromCode(field.type()).isComputed()) return "COLUMN";
        if (options == null || options.calculation() == null) return "GENERATED_COLUMN";
        return "CALCULATION";
    }

    private static String modeNote(String mode) {
        if (FormFillModeEnum.SOURCE_CHANGE.matches(mode)) return "原：编辑打开时首次不触发；新：编辑打开也不求值，两者一致";
        return "原：仅目标为空时填；新：为空或未被用户改动时填，差异可忽略";
    }

    // ── 分类：冲突、已迁移、阻断 ──

    private List<Fill> classify(
            List<Fill> fills, Map<String, ObjectState> objects, List<String> warnings) {
        Map<String, Set<String>> bindings = new HashMap<>();
        for (var f : fills)
            if (PLANNED.equals(f.status()))
                bindings.computeIfAbsent(target(f), k -> new TreeSet<>()).add(binding(f));
        var result = new ArrayList<Fill>();
        for (var f : fills) {
            if (!PLANNED.equals(f.status())) {
                result.add(f);
                continue;
            }
            if (bindings.get(target(f)).size() > 1) {
                result.add(with(f, CONFLICT, "同一对象同一目标字段在不同表单上配置了不同的关联带入，整条跳过"));
                continue;
            }
            var latest = objects.computeIfAbsent(f.objectId(), source::object);
            var options = latest.definition().fieldOptions().get(f.targetFieldId());
            var field = field(latest.definition(), f.targetFieldId());
            if (field == null
                    || options != null && MemberStateEnum.INACTIVE.matches(options.state())) {
                result.add(with(f, BLOCKED, "目标字段在对象当前发布版本中已不存在或已停用"));
                continue;
            }
            var rules = options == null ? null : options.rules();
            if (rules != null && sameIgnoringReadOnly(rules.linkage(), f.linkage())) {
                result.add(with(f, ALREADY_APPLIED, null));
                continue;
            }
            String existing = existingSource(options);
            if (existing != null) {
                result.add(with(f, BLOCKED, "目标字段在对象上已配置" + existing + "，只能保留一种值来源"));
                continue;
            }
            result.add(f);
        }
        return result;
    }

    /**
     * 两条数据联动除只读开关外是否相同。迁移只决定来源、条件、取值字段与多行档位；只读由 linkage-readonly 另行调整（2026-09-29），
     * 已改成只读的联动仍算「已迁移」，apply 不会把它改回可编辑。
     */
    static boolean sameIgnoringReadOnly(FieldRules.Linkage a, FieldRules.Linkage b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.sourceObjectId(), b.sourceObjectId())
                && Objects.equals(a.conditions(), b.conditions())
                && Objects.equals(a.valueFieldId(), b.valueFieldId())
                && Objects.equals(a.multiRow(), b.multiRow());
    }

    private static String existingSource(DataCenter.FieldOptions options) {
        if (options == null) return null;
        var rules = options.rules();
        if (rules != null && rules.linkage() != null) return "其它数据联动";
        if (rules != null && rules.defaultFormula() != null) return "公式默认值";
        if (options.defaultValue() != null) return "自定义默认值";
        return null;
    }

    private static List<Conflict> conflicts(List<Fill> fills) {
        Map<String, List<Fill>> grouped = new LinkedHashMap<>();
        for (var f : fills)
            if (CONFLICT.equals(f.status()))
                grouped.computeIfAbsent(target(f), k -> new ArrayList<>()).add(f);
        return grouped.values().stream()
                .map(
                        list -> {
                            var first = list.getFirst();
                            return new Conflict(
                                    first.objectId(),
                                    first.objectName(),
                                    first.targetFieldId(),
                                    first.targetFieldName(),
                                    list.stream()
                                            .map(ObjectRuleMigrationPlanner::describe)
                                            .distinct()
                                            .toList(),
                                    list.stream()
                                            .map(f -> f.applicationCode() + "/" + f.formName())
                                            .distinct()
                                            .toList());
                        })
                .toList();
    }

    // ── 对象：数据联动与选项类默认值 ──

    private List<ObjectChange> objectChanges(
            String prefix, List<Fill> fills, Map<String, ObjectState> cache) {
        Map<String, Map<String, Linkage>> linkages = new LinkedHashMap<>();
        for (var f : fills)
            if (PLANNED.equals(f.status()) || ALREADY_APPLIED.equals(f.status()))
                linkages.computeIfAbsent(f.objectId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(
                                f.targetFieldId(),
                                new Linkage(
                                        f.targetFieldId(),
                                        f.targetFieldName(),
                                        f.targetFieldType(),
                                        f.linkage(),
                                        f.status()));
        var ids = new LinkedHashSet<>(linkages.keySet());
        ids.addAll(source.objectIds(prefix));
        var result = new ArrayList<ObjectChange>();
        for (var id : ids) {
            var state = cache.computeIfAbsent(id, source::object);
            var planned = new ArrayList<>(linkages.getOrDefault(id, Map.of()).values());
            var defaults = optionDefaults(state.definition());
            if (planned.isEmpty() && defaults.isEmpty()) continue;
            boolean writes =
                    !defaults.isEmpty()
                            || planned.stream().anyMatch(l -> PLANNED.equals(l.status()));
            // 要写的对象上有草稿：与已发布版本语义一致（空草稿）则 apply 沿用，否则阻断。
            var verdict = writes && state.draftOpen() ? drafts.judge(id) : null;
            String blocker =
                    verdict != null && !verdict.identical()
                            ? "对象「" + state.name() + "」有未发布草稿，请先发布或放弃草稿后重新 dry-run"
                            : null;
            result.add(
                    new ObjectChange(
                            id,
                            state.code(),
                            state.name(),
                            state.publishedVersion(),
                            planned,
                            defaults,
                            blocker,
                            verdict != null && verdict.identical()
                                    ? verdict.draftVersion()
                                    : null));
        }
        return result;
    }

    private static List<OptionDefault> optionDefaults(DataCenter.Definition d) {
        var result = new ArrayList<OptionDefault>();
        collectOptionDefaults(null, d.fields(), d.fieldOptions(), d, result);
        for (var t : d.details())
            if (MemberStateEnum.ACTIVE.matches(t.state()))
                collectOptionDefaults(t.id(), t.fields(), t.fieldOptions(), d, result);
        return result;
    }

    private static void collectOptionDefaults(
            String detailId,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            DataCenter.Definition d,
            List<OptionDefault> result) {
        for (var f : fields) {
            var o = options.get(f.id());
            if (o == null || o.defaultValue() == null || !FieldRuleMatrix.optionType(f)) continue;
            if (detailId == null && BusinessFields.relation(d, f.id()) != null) continue;
            result.add(new OptionDefault(detailId, f.id(), f.name(), f.type(), o.defaultValue()));
        }
    }

    // ── 应用：升级对象固定版本，去掉表单上的带入与选项类默认值 ──

    private List<ApplicationChange> applicationChanges(
            List<AppState> apps,
            List<Fill> fills,
            Map<String, FormScan> forms,
            List<ObjectChange> objects,
            Map<String, ObjectState> cache) {
        var changing = objects.stream().map(ObjectChange::objectId).collect(Collectors.toSet());
        Map<String, Set<String>> strip = new HashMap<>();
        for (var f : fills)
            if (PLANNED.equals(f.status()) || ALREADY_APPLIED.equals(f.status()))
                strip.computeIfAbsent(formKey(f.applicationId(), f.formId()), k -> new TreeSet<>())
                        .add(f.nodeKey());
        var result = new ArrayList<ApplicationChange>();
        for (var app : apps) {
            var formChanges = new ArrayList<Form>();
            for (var resource : app.published().resources()) {
                var scan = forms.get(formKey(app.id(), resource.id()));
                if (scan == null) continue;
                var nodes = strip.getOrDefault(formKey(app.id(), resource.id()), Set.of());
                if (nodes.isEmpty() && scan.optionDefaults().isEmpty()) continue;
                JsonNode tree = json.valueToTree(resource.config());
                var all = fieldNodes(tree);
                Map<String, Map<String, Object>> before = new LinkedHashMap<>();
                var touched = new TreeSet<>(nodes);
                scan.optionDefaults().forEach(o -> touched.add(o.nodeKey()));
                for (var key : touched) before.put(key, map(all.get(key)));
                formChanges.add(
                        new Form(
                                resource.id(),
                                resource.name(),
                                scan.objectId(),
                                resource.config(),
                                List.copyOf(nodes),
                                scan.optionDefaults(),
                                before));
            }
            var upgrades = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            for (var ref : app.published().objects()) {
                if (!changing.contains(ref.objectId())) continue;
                upgrades.add(ref.objectId());
                var latest = cache.computeIfAbsent(ref.objectId(), source::object);
                if (ref.versionNo() != latest.publishedVersion())
                    warnings.add(
                            "对象「"
                                    + latest.name()
                                    + "」在本应用固定 V"
                                    + ref.versionNo()
                                    + "，对象当前发布 V"
                                    + latest.publishedVersion()
                                    + "：apply 升级固定版本时会一并带入中间版本的变更");
            }
            if (formChanges.isEmpty() && upgrades.isEmpty()) continue;
            if (!upgrades.isEmpty())
                warnings.add("升级固定版本会触发平台「引用即授权」覆盖共享授权；apply 发布后恢复迁移前的授权，执行人需有共享授权管理权限");
            boolean draftDiffers = !json.valueToTree(app.published()).equals(app.draft());
            if (draftDiffers) warnings.add("应用草稿有未发布修改：apply 只在草稿上做同样的节点修改，其余草稿修改保留、不发布");
            result.add(
                    new ApplicationChange(
                            app.id(),
                            app.code(),
                            app.name(),
                            app.publishedVersion(),
                            draftDiffers,
                            app.published().objects(),
                            upgrades,
                            formChanges,
                            warnings));
        }
        return result;
    }

    /** 影响面扩大：升级后的应用里，同一对象下含目标字段但没有该带入的表单（U3）。 */
    private List<Impact> impacts(
            List<AppState> apps,
            List<Fill> fills,
            Map<String, FormScan> forms,
            List<ApplicationChange> applications) {
        Map<String, Map<String, String>> targets = new LinkedHashMap<>();
        Set<String> filled = new HashSet<>();
        for (var f : fills)
            if (PLANNED.equals(f.status()) || ALREADY_APPLIED.equals(f.status())) {
                targets.computeIfAbsent(f.objectId(), k -> new LinkedHashMap<>())
                        .put(f.targetFieldId(), f.targetFieldName());
                filled.add(formKey(f.applicationId(), f.formId()) + "|" + f.targetFieldId());
            }
        var upgraded =
                applications.stream()
                        .map(ApplicationChange::applicationId)
                        .collect(Collectors.toSet());
        var result = new ArrayList<Impact>();
        for (var scan : forms.values()) {
            var migrated = targets.get(scan.objectId());
            if (migrated == null || !upgraded.contains(scan.app().id())) continue;
            String formKey = formKey(scan.app().id(), scan.resource().id());
            var names =
                    scan.nodeFields().entrySet().stream()
                            .filter(e -> detailOf(e.getKey()) == null)
                            .map(Map.Entry::getValue)
                            .filter(migrated::containsKey)
                            .filter(id -> !filled.contains(formKey + "|" + id))
                            .map(migrated::get)
                            .distinct()
                            .toList();
            if (names.isEmpty()) continue;
            result.add(
                    new Impact(
                            scan.app().id(),
                            scan.app().code(),
                            scan.objectId(),
                            fills.stream()
                                    .filter(f -> f.objectId().equals(scan.objectId()))
                                    .map(Fill::objectName)
                                    .findFirst()
                                    .orElse(null),
                            scan.resource().id(),
                            scan.resource().name(),
                            names));
        }
        return result;
    }

    // ── 工具函数 ──

    static String formKey(String applicationId, String formId) {
        return applicationId + "/" + formId;
    }

    private static String target(Fill f) {
        return f.objectId() + "|" + f.targetFieldId();
    }

    private static String binding(Fill f) {
        return f.sourceFieldId() + "→" + f.valueFieldId();
    }

    private static String describe(Fill f) {
        return f.applicationCode()
                + "/"
                + f.formName()
                + "："
                + f.sourceFieldName()
                + " → "
                + f.sourceObjectName()
                + "."
                + f.valueFieldName();
    }

    private static Fill with(Fill f, String status, String reason) {
        return new Fill(
                f.applicationId(),
                f.applicationCode(),
                f.formId(),
                f.formName(),
                f.nodeKey(),
                f.detailId(),
                f.objectId(),
                f.objectName(),
                f.targetFieldId(),
                f.targetFieldName(),
                f.targetFieldType(),
                f.sourceFieldId(),
                f.sourceFieldName(),
                f.sourceObjectId(),
                f.sourceObjectName(),
                f.valueFieldId(),
                f.valueFieldName(),
                f.valueFieldType(),
                f.valueResultType(),
                f.valueCalculationMode(),
                f.valueCalculationUpdateMode(),
                f.valueStorage(),
                f.mode(),
                f.modeNote(),
                f.linkage(),
                status,
                reason);
    }

    private static int count(List<Fill> fills, String status) {
        return (int) fills.stream().filter(f -> status.equals(f.status())).count();
    }

    private static String where(AppState app, ApplicationCenter.Resource resource) {
        return "应用「" + app.name() + "」表单「" + resource.name() + "」";
    }

    static FieldDefinition field(DataCenter.Definition d, String fieldId) {
        if (fieldId == null) return null;
        return BusinessFields.fields(d).stream()
                .filter(f -> fieldId.equals(f.id()))
                .findFirst()
                .orElse(null);
    }

    /** 明细当成独立定义（字段、扩展属性、以本明细为来源的关系），用于查字段与选项类判断。 */
    static DataCenter.Definition detailDefinition(DataCenter.Definition d, String detailId) {
        var detail = d.details().stream().filter(t -> t.id().equals(detailId)).findFirst();
        if (detail.isEmpty()) return null;
        var t = detail.get();
        return new DataCenter.Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                t.tableName(),
                d.source(),
                d.readOnly(),
                null,
                d.settings(),
                t.fields(),
                t.fieldOptions(),
                d.relations().stream().filter(r -> detailId.equals(r.sourceDetailId())).toList(),
                List.of(),
                List.of(),
                t.binding());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) {
        return json.convertValue(node, Map.class);
    }

    // ── JDBC 只读来源 ──

    /** 只读 JDBC 来源；prefix 为空表示全库，非空时只看编码以该前缀开头的应用与对象。 */
    static final class JdbcSource implements Source {
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;

        JdbcSource(JdbcTemplate jdbc, ObjectMapper json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override
        public List<AppState> applications(String prefix) {
            return jdbc.query(
                    """
SELECT a.id::text id,a.app_code,a.app_name,a.published_version,a.design_json::text draft,
       v.definition_json::text published
FROM public.nocode_application a
JOIN public.nocode_application_version v
  ON v.application_id=a.id AND v.version_no=a.published_version AND v.deleted=0
WHERE a.deleted=0 AND a.status='ACTIVE' AND a.app_code LIKE ? ESCAPE '\\'
ORDER BY a.id
""",
                    (rs, i) -> {
                        try {
                            var snapshot =
                                    json.readValue(
                                            rs.getString("published"),
                                            ApplicationCenter.Snapshot.class);
                            return new AppState(
                                    rs.getString("id"),
                                    rs.getString("app_code"),
                                    rs.getString("app_name"),
                                    rs.getInt("published_version"),
                                    snapshot.definition(),
                                    json.readTree(rs.getString("draft")));
                        } catch (Exception e) {
                            throw new IllegalStateException(
                                    "应用发布快照无法解析：" + rs.getString("app_code"), e);
                        }
                    },
                    like(prefix));
        }

        @Override
        public List<String> objectIds(String prefix) {
            return jdbc.queryForList(
                    """
SELECT o.id::text FROM public.nocode_object o
WHERE o.deleted=0 AND o.status='ACTIVE' AND o.current_published_version_no IS NOT NULL
  AND o.object_code LIKE ? ESCAPE '\\'
ORDER BY o.id
""",
                    String.class,
                    like(prefix));
        }

        @Override
        public ObjectState object(String objectId) {
            var row =
                    jdbc.queryForMap(
                            """
SELECT o.object_code,o.object_name,o.current_published_version_no version_no,v.schema_json::text schema,
       EXISTS(SELECT 1 FROM public.nocode_object_version d
              WHERE d.object_id=o.id AND d.state='DRAFT' AND d.deleted=0) draft_open
FROM public.nocode_object o
JOIN public.nocode_object_version v ON v.object_id=o.id AND v.version_no=o.current_published_version_no
WHERE o.id=? AND o.deleted=0
""",
                            Long.parseLong(objectId));
            return new ObjectState(
                    objectId,
                    (String) row.get("object_code"),
                    (String) row.get("object_name"),
                    ((Number) row.get("version_no")).intValue(),
                    definition((String) row.get("schema")),
                    Boolean.TRUE.equals(row.get("draft_open")));
        }

        @Override
        public DataCenter.Definition version(String objectId, int versionNo) {
            return definition(
                    jdbc.queryForObject(
                            "SELECT schema_json::text FROM public.nocode_object_version"
                                    + " WHERE object_id=? AND version_no=? AND deleted=0",
                            String.class,
                            Long.parseLong(objectId),
                            versionNo));
        }

        private DataCenter.Definition definition(String schema) {
            try {
                return ObjectTables.normalize(json.readValue(schema, DataCenter.Definition.class));
            } catch (Exception e) {
                throw new IllegalStateException("对象发布快照无法解析", e);
            }
        }

        private static String like(String prefix) {
            if (prefix == null || prefix.isEmpty()) return "%";
            return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
    }
}
