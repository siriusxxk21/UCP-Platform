package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.lingan.ucp.nocode.runtime.service.rules.ScriptedFieldRuleEvaluator;

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * 路 C 保存时规则强制的集成夹具：真实对象、物理表、应用发布和公共保存流水线。
 *
 * <p>规则按设计稿 15.1 的 JSON 形状写进已发布对象版本快照（config 生效前的设计保存由路 A 负责）；求值器替换为脚本替身， 只验证保存时强制本身。凭证对象
 * voucher：name、company、bank、memo、price、qty、total(MONEY)、account(→口座)； 明细
 * lines：category、rate、note、unit、line_qty、amount(MONEY)、subject(→口座)。
 */
class FieldRuleEnforcementFixture {
    final NocodeIntegrationSupport support = new NocodeIntegrationSupport();
    final ScriptedFieldRuleEvaluator evaluator = new ScriptedFieldRuleEvaluator();
    final RecordService records = servicesContext.getBean(RecordService.class);
    final ApplicationService applications = servicesContext.getBean(ApplicationService.class);
    private final FieldRuleEnforcer enforcer = servicesContext.getBean(FieldRuleEnforcer.class);
    private final Object originalEvaluator;
    DataCenter.Definition accounts;
    DataCenter.Definition voucher;
    String app;
    PublishedResourceRef form;
    String accountName;
    String name, company, bank, memo, price, qty, total, account;
    String lines, category, rate, note, unit, lineQty, amount, subject;
    String accountA, accountB;

    FieldRuleEnforcementFixture() {
        support.name();
        originalEvaluator = ReflectionTestUtils.getField(enforcer, "evaluator");
        ReflectionTestUtils.setField(enforcer, "evaluator", evaluator);
    }

    /** 建立并发布口座与凭证两个对象。 */
    FieldRuleEnforcementFixture objects() {
        var target = support.createRequest("acct");
        accounts =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        target,
                                        DataCenter.Settings.defaults(),
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        accountName = field(accounts.fields(), "name");
        var base = support.createRequest("voucher");
        var fields = new ArrayList<>(base.fields());
        fields.add(support.field("company", "company", "TEXT", 1));
        fields.add(support.field("bank", "bank", "TEXT", 2));
        fields.add(support.field("memo", "memo", "TEXT", 3));
        fields.add(decimal("price", 4));
        fields.add(support.field("qty", "qty", "INTEGER", 5));
        fields.add(support.field("total", "total", "MONEY", 6));
        var detail =
                new DataCenter.Detail(
                        null,
                        "lines",
                        "分录",
                        "biz_" + support.prefix + "lines",
                        "ACTIVE",
                        List.of(
                                support.field("category", "category", "TEXT", 0),
                                support.field("rate", "rate", "TEXT", 1),
                                support.field("note", "note", "TEXT", 2),
                                decimal("unit", 3),
                                support.field("line_qty", "line_qty", "INTEGER", 4),
                                support.field("amount", "amount", "MONEY", 5)),
                        Map.of(),
                        List.of());
        voucher =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                base.objectCode(),
                                                base.objectName(),
                                                null,
                                                base.tableName(),
                                                base.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        null,
                                        List.of(
                                                new DataCenter.Relation(
                                                        null,
                                                        "account",
                                                        "口座",
                                                        "REFERENCE",
                                                        accounts.objectId(),
                                                        null,
                                                        null,
                                                        false,
                                                        "RESTRICT"),
                                                new DataCenter.Relation(
                                                        null,
                                                        "subject",
                                                        "科目",
                                                        "REFERENCE",
                                                        accounts.objectId(),
                                                        null,
                                                        null,
                                                        false,
                                                        "RESTRICT",
                                                        "detail:lines")),
                                        List.of(),
                                        List.of(detail)),
                                10001));
        name = field(voucher.fields(), "name");
        company = field(voucher.fields(), "company");
        bank = field(voucher.fields(), "bank");
        memo = field(voucher.fields(), "memo");
        price = field(voucher.fields(), "price");
        qty = field(voucher.fields(), "qty");
        total = field(voucher.fields(), "total");
        account = relation("account").fieldId();
        var entries = voucher.details().getFirst();
        lines = entries.id();
        category = field(entries.fields(), "category");
        rate = field(entries.fields(), "rate");
        note = field(entries.fields(), "note");
        unit = field(entries.fields(), "unit");
        lineQty = field(entries.fields(), "line_qty");
        amount = field(entries.fields(), "amount");
        subject = relation("subject").fieldId();
        return this;
    }

    /** 当前字段条件：来源为口座对象；求值由脚本替身决定，形状与设计稿 3.4 一致。 */
    FieldRules.Condition current(String formField) {
        return new FieldRules.Condition(accountName, "eq", "FORM_FIELD", null, formField);
    }

    FieldRules linkage(Boolean readOnly, String... formFields) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        accounts.objectId(),
                        Arrays.stream(formFields).map(this::current).toList(),
                        accountName,
                        "FIRST",
                        readOnly,
                        null,
                        null),
                null,
                null,
                null,
                null);
    }

    FieldRules formula(String expression, String rounding) {
        return new FieldRules(null, null, expression, rounding, null, null);
    }

    FieldRules filter(String... formFields) {
        return new FieldRules(
                new FieldRules.Reference(
                        accountName, Arrays.stream(formFields).map(this::current).toList()),
                null,
                null,
                null,
                null,
                null);
    }

    /** 把规则写进凭证对象已发布版本快照中的字段扩展属性（主表或明细）。 */
    FieldRuleEnforcementFixture rule(String fieldId, FieldRules rules) {
        try {
            long objectId = Long.parseLong(voucher.objectId());
            var row =
                    jdbc.queryForMap(
                            "SELECT version_no, schema_json::text AS schema FROM"
                                    + " public.nocode_object_version WHERE object_id=? AND"
                                    + " state='PUBLISHED' ORDER BY version_no DESC LIMIT 1",
                            objectId);
            var root = (ObjectNode) mapper.readTree((String) row.get("schema"));
            ObjectNode options = null;
            if (root.path("fieldOptions").has(fieldId))
                options = (ObjectNode) root.get("fieldOptions");
            for (var detail : root.path("details"))
                if (detail.path("fieldOptions").has(fieldId))
                    options = (ObjectNode) detail.get("fieldOptions");
            assertThat(options).as("已发布快照包含字段扩展属性：" + fieldId).isNotNull();
            ((ObjectNode) options.get(fieldId)).set("rules", mapper.valueToTree(rules));
            jdbc.update(
                    "UPDATE public.nocode_object_version SET schema_json=CAST(? AS jsonb) WHERE"
                            + " object_id=? AND version_no=?",
                    mapper.writeValueAsString(root),
                    objectId,
                    row.get("version_no"));
            voucher = servicesContext.getBean(DataObjectApi.class).getPublished(voucher.objectId());
            return this;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 发布应用：两个对象、凭证表单（含明细）以及可选的任务入口；创建两条口座。 */
    FieldRuleEnforcementFixture app(List<ApplicationCenter.Resource> extra) {
        var nodes =
                new ArrayList<ApplicationUi.Node>(
                        List.of(
                                node(name),
                                node(company),
                                node(bank),
                                node(memo),
                                node(price),
                                node(qty),
                                node(total),
                                node(account)));
        var formResource =
                resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(voucher.objectId(), nodes, List.of(lines)));
        var resources = new ArrayList<ApplicationCenter.Resource>(List.of(formResource));
        resources.addAll(extra);
        var refs =
                List.of(accounts, voucher).stream()
                        .map(
                                d ->
                                        servicesContext
                                                .getBean(DataObjectApi.class)
                                                .getVersion(d.objectId(), null))
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
                                support.prefix + "app",
                                "规则强制验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, resources)),
                        10001);
        app = saved.application().id();
        grantApplicationObjects(app);
        applications.publish(new ApplicationCenter.Revision(app, 0, "规则强制验证"), 10001);
        var published = applications.published(app);
        form =
                new PublishedResourceRef(
                        app, published.versionNo(), published.checksum(), "form", "FORM");
        accountA = createAccount("甲公司口座");
        accountB = createAccount("乙公司口座");
        return this;
    }

    FieldRuleEnforcementFixture app() {
        return app(List.of());
    }

    ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "rule_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    String createAccount(String label) {
        return records.save(
                        new ApplicationRecords.Save(
                                app,
                                accounts.objectId(),
                                null,
                                null,
                                Map.of(accountName, label),
                                null),
                        10001)
                .record()
                .id();
    }

    ApplicationRecords.Aggregate save(
            String id,
            String revision,
            Map<String, Object> values,
            Map<String, List<ApplicationRecords.Row>> details,
            long actor) {
        return records.save(
                new ApplicationRecords.Save(app, voucher.objectId(), id, revision, values, details),
                actor);
    }

    ApplicationRecords.Aggregate get(String id) {
        return records.get(app, voucher.objectId(), id, 10001);
    }

    long count() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.\"" + voucher.tableName() + "\" WHERE deleted=0",
                Long.class);
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static BigDecimal number(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    void close() {
        ReflectionTestUtils.setField(enforcer, "evaluator", originalEvaluator);
        writeFailure.clear();
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        support.prefix + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE application_id=?", id);
            jdbc.update(
                    "DELETE FROM public.nocode_work_submission WHERE draft_id IN (SELECT id FROM"
                            + " public.nocode_work_draft WHERE resource_json->>'applicationId'=?)",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_work_draft WHERE resource_json->>'applicationId'=?",
                    id.toString());
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
        support.clean();
    }

    private static ApplicationUi.Node node(String fieldId) {
        return new ApplicationUi.Node(
                "node_" + fieldId, "FIELD", fieldId, null, null, null, List.of());
    }

    private FieldDefinition decimal(String code, int sort) {
        return new FieldDefinition(
                code, null, code, "字段" + sort, "DECIMAL", null, 20, 4, false, false, sort);
    }

    private DataCenter.Relation relation(String code) {
        return voucher.relations().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static String field(List<FieldDefinition> fields, String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow().id();
    }

    private static DataCenter.Definition publish(DataCenter.Design design) {
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "规则强制验证"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return servicesContext.getBean(DataObjectApi.class).getPublished(design.draft().id());
    }
}
