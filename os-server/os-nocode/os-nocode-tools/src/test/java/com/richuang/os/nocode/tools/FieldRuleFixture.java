package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 字段规则集成测试夹具：在当前开发库建自有前缀的对象、应用与记录。
 *
 * <p>规则写入方式：对象正常保存并发布后，把 rules 直接写进该已发布版本的 schema_json（校验和不变，应用固定版本照常解析）。这是路 A
 * 的保存链合并前的等价写法；运行期只从固定版本读取规则，与规则如何进入快照无关。
 */
final class FieldRuleFixture {
    final NocodeIntegrationSupport support = new NocodeIntegrationSupport();
    final RecordService runtime;
    final ApplicationService applications;
    private int serial;

    FieldRuleFixture() {
        support.name();
        runtime = servicesContext.getBean(RecordService.class);
        applications = servicesContext.getBean(ApplicationService.class);
    }

    String prefix() {
        return support.prefix;
    }

    static FieldDefinition field(String code, String name, String type) {
        return field(code, name, type, null, null);
    }

    static FieldDefinition field(
            String code, String name, String type, Integer precision, Integer scale) {
        return new FieldDefinition(
                code,
                null,
                code,
                name,
                type,
                "TEXT".equals(type) ? 100 : null,
                precision,
                scale,
                false,
                false,
                10);
    }

    static DataCenter.FieldOptions options(List<DataCenter.Option> options) {
        return new DataCenter.FieldOptions(
                null, "NORMAL", null, null, null, null, null, "ACTIVE", options, null, null, "NONE",
                null, false, false);
    }

    /** 保留原扩展属性（含列名、来源与规则），只替换局部选项。 */
    static DataCenter.FieldOptions withOptions(
            DataCenter.FieldOptions o, List<DataCenter.Option> options) {
        return new DataCenter.FieldOptions(
                o.columnName(),
                o.classification(),
                o.defaultValue(),
                o.description(),
                o.pattern(),
                o.minimum(),
                o.maximum(),
                o.state(),
                options,
                o.expression(),
                o.resultType(),
                o.resolver(),
                o.nativeType(),
                o.primaryKey(),
                o.generated(),
                o.selection(),
                o.calculation(),
                o.autoNumber(),
                o.rules());
    }

    /** 选项字段保存时至少要有一个局部选项；需要其它来源的用例发布后再改写快照。 */
    static DataCenter.FieldOptions placeholder() {
        return options(List.of(new DataCenter.Option("X", "占位", false)));
    }

    static DataCenter.FieldOptions formula(String expression, String result) {
        return new DataCenter.FieldOptions(
                null,
                "NORMAL",
                null,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(),
                expression,
                result,
                "NONE",
                null,
                false,
                false,
                null,
                new CalculationOptions(
                        "LOCAL", "LIVE", null, null, null, null, null, List.of(), false, List.of(),
                        null));
    }

    static DataCenter.Relation reference(String code, DataCenter.Definition target) {
        return reference(code, target, null);
    }

    static DataCenter.Relation reference(
            String code, DataCenter.Definition target, String detailCode) {
        return new DataCenter.Relation(
                null,
                code,
                "引用" + code,
                "REFERENCE",
                target.objectId(),
                null,
                null,
                false,
                "RESTRICT",
                detailCode == null ? null : "detail:" + detailCode);
    }

    DataCenter.Detail detail(String code, List<FieldDefinition> fields) {
        return detail(code, fields, Map.of());
    }

    DataCenter.Detail detail(
            String code,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options) {
        return new DataCenter.Detail(
                null,
                code,
                "明细" + code,
                "biz_" + support.prefix + code + serial++,
                "ACTIVE",
                fields,
                options,
                List.of());
    }

    /** 保存并发布对象；标题字段 name 固定在第一列。 */
    DataCenter.Definition object(
            String suffix,
            List<FieldDefinition> extra,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details) {
        String code = support.prefix + suffix + serial++;
        var fields = new ArrayList<FieldDefinition>();
        fields.add(
                new FieldDefinition(
                        "name", null, "name", "名称", "TEXT", 100, null, null, false, false, 0));
        fields.addAll(extra);
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        code,
                                        "对象" + suffix,
                                        null,
                                        "biz_" + code,
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                relations,
                                List.of(),
                                details),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "规则验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return published(design.draft().id());
    }

    /** 打开新草稿、替换指定字段的扩展属性并发布为新版本；已发布应用仍固定在旧版本，直到同步。 */
    DataCenter.Definition republish(
            DataCenter.Definition d, Map<String, DataCenter.FieldOptions> changes) {
        var current = designs.get(d.objectId());
        var design =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(), current.draft().lockVersion(), "规则验证"),
                        10001);
        var options = new LinkedHashMap<>(design.fieldOptions());
        options.putAll(changes);
        var changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                support.edit(
                                        design.draft(),
                                        design.draft().fields(),
                                        List.of(),
                                        design.draft().titleFieldId()),
                                design.settings(),
                                options,
                                design.relations(),
                                design.indexes(),
                                design.details(),
                                design.mainBinding()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                d.objectId(), changed.draft().lockVersion(), "规则验证新版本"),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        // 改动会打断仍固定旧版本的应用时（例如停用了旧版本里可选的选项），对象发布要求确认暂停这些应用；
        // 夹具一并确认，随后由用例同步应用并重新发布启用。
        var affected =
                plan.applicationUpgrades().stream()
                        .map(ObjectApplicationUpgrade.Impact::applicationId)
                        .toList();
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(
                                                plan.id(), "规则验证", List.of(), affected),
                                        10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return published(d.objectId());
    }

    static DataCenter.Definition published(String objectId) {
        return servicesContext.getBean(DataObjectApi.class).getPublished(objectId);
    }

    /** 应用引用对象的当前发布版本，按夹具授予全部对象权限并发布。 */
    String app(List<ApplicationCenter.Resource> resources, DataCenter.Definition... definitions) {
        var api = servicesContext.getBean(DataObjectApi.class);
        var refs =
                Arrays.stream(definitions)
                        .map(d -> api.getVersion(d.objectId(), null))
                        .map(
                                v ->
                                        new ApplicationCenter.ObjectReference(
                                                v.objectId(), v.versionNo(), v.checksum()))
                        .toList();
        var saved =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                support.prefix + "app" + serial++,
                                "规则验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, resources)),
                        10001);
        grantApplicationObjects(saved.application().id());
        applications.publish(
                new ApplicationCenter.Revision(saved.application().id(), 0, "规则验证"), 10001);
        return saved.application().id();
    }

    String app(DataCenter.Definition... definitions) {
        return app(List.of(), definitions);
    }

    /** 发布已同步到对象最新版本的应用草稿。对象发布打断旧版本应用时该应用已被暂停（见 {@link #republish}），此时走「发布启用」； 否则照常发布。 */
    void publishSynced(ApplicationCenter.Row saved) {
        var request = new ApplicationCenter.Revision(saved.id(), saved.revision(), "规则同步");
        if ("DISABLED".equals(saved.status())) applications.publishAndEnable(request, 10001);
        else applications.publish(request, 10001);
    }

    /** 把一个字段的扩展属性改写进已发布版本快照（主表或明细字段均可），返回改写后的定义。 */
    static DataCenter.Definition patch(
            DataCenter.Definition d,
            String fieldId,
            UnaryOperator<DataCenter.FieldOptions> change) {
        var main = new LinkedHashMap<>(d.fieldOptions());
        List<DataCenter.Detail> details = new ArrayList<>();
        boolean found = d.fields().stream().anyMatch(f -> f.id().equals(fieldId));
        if (found)
            main.put(
                    fieldId,
                    change.apply(main.getOrDefault(fieldId, DataCenter.FieldOptions.defaults())));
        for (var t : d.details()) {
            if (t.fields().stream().anyMatch(f -> f.id().equals(fieldId))) {
                var o = new LinkedHashMap<>(t.fieldOptions());
                o.put(
                        fieldId,
                        change.apply(o.getOrDefault(fieldId, DataCenter.FieldOptions.defaults())));
                t =
                        new DataCenter.Detail(
                                t.id(),
                                t.code(),
                                t.name(),
                                t.tableName(),
                                t.state(),
                                t.fields(),
                                o,
                                t.indexes(),
                                t.binding());
                found = true;
            }
            details.add(t);
        }
        assertThat(found).as("字段必须属于对象：" + fieldId).isTrue();
        var changed =
                new DataCenter.Definition(
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
                        d.fields(),
                        main,
                        d.relations(),
                        d.indexes(),
                        details,
                        d.mainBinding());
        var version = servicesContext.getBean(DataObjectApi.class).getVersion(d.objectId(), null);
        int updated =
                jdbc.update(
                        "UPDATE public.nocode_object_version SET schema_json = CAST(? AS jsonb)"
                                + " WHERE object_id = ? AND version_no = ?",
                        designs.write(changed),
                        Long.parseLong(d.objectId()),
                        version.versionNo());
        assertThat(updated).isEqualTo(1);
        return published(d.objectId());
    }

    static DataCenter.Definition rules(DataCenter.Definition d, String fieldId, FieldRules rules) {
        return patch(d, fieldId, o -> o.withRules(rules));
    }

    static FieldRules.Condition formField(String sourceField, String operator, String formField) {
        return new FieldRules.Condition(sourceField, operator, "FORM_FIELD", null, formField);
    }

    static FieldRules.Condition constant(String sourceField, String operator, Object value) {
        return new FieldRules.Condition(sourceField, operator, "CONSTANT", value, null);
    }

    static FieldRules linkage(
            DataCenter.Definition source,
            String valueField,
            String multiRow,
            List<FieldRules.Condition> conditions) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        source.objectId(), conditions, valueField, multiRow, false, null, null),
                null,
                null,
                null,
                null);
    }

    static FieldRules filter(String labelFieldId, FieldRules.Condition... conditions) {
        return new FieldRules(
                new FieldRules.Reference(labelFieldId, List.of(conditions)),
                null,
                null,
                null,
                null,
                null);
    }

    static String id(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    static String detailId(DataCenter.Definition d, String code) {
        return d.details().stream()
                .filter(t -> t.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    static String detailField(DataCenter.Definition d, String detail, String code) {
        return d.details().stream()
                .filter(t -> t.code().equals(detail))
                .findFirst()
                .orElseThrow()
                .fields()
                .stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    static String relationField(DataCenter.Definition d, String code) {
        return d.relations().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow()
                .fieldId();
    }

    Row save(String app, DataCenter.Definition d, Map<String, Object> values) {
        return runtime.save(new Save(app, d.objectId(), null, null, values, null), 10001).record();
    }

    Row save(String app, DataCenter.Definition d, Map<String, Object> values, long actor) {
        return runtime.save(new Save(app, d.objectId(), null, null, values, null), actor).record();
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    FieldRules.Evaluation evaluate(
            String app,
            DataCenter.Definition d,
            Map<String, Object> values,
            List<String> changed,
            List<String> overridable,
            List<FieldRules.DetailRows> details,
            long actor) {
        return runtime.evaluateRules(
                new FieldRules.EvaluateQuery(
                        app, d.objectId(), null, null, true, values, changed, overridable, details),
                actor);
    }

    static FieldRules.Result result(FieldRules.Evaluation evaluation, String fieldId) {
        return evaluation.results().stream()
                .filter(r -> r.fieldId().equals(fieldId) && r.rowKey() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有字段的求值结果：" + fieldId));
    }

    SelectionFields.Result selection(
            String app,
            DataCenter.Definition d,
            String fieldId,
            Map<String, Object> formValues,
            List<String> selected) {
        return selection(app, d, null, fieldId, formValues, selected, null);
    }

    SelectionFields.Result selection(
            String app,
            DataCenter.Definition d,
            String detailId,
            String fieldId,
            Map<String, Object> formValues,
            List<String> selected,
            String formId) {
        return runtime.selection(
                new SelectionFields.Query(
                        app,
                        d.objectId(),
                        detailId,
                        fieldId,
                        null,
                        1,
                        100,
                        selected,
                        null,
                        formId,
                        formValues,
                        true,
                        null),
                10001);
    }

    /** 只清理本夹具前缀拥有的应用与对象。 */
    void cleanup() {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        support.prefix + "%");
        for (Long id : ids) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_record_process WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        jdbc.update(
                "DELETE FROM public.nocode_business_counter WHERE object_id IN (SELECT id FROM"
                        + " public.nocode_object WHERE object_code LIKE ?)",
                support.prefix + "%");
        support.clean();
    }
}
