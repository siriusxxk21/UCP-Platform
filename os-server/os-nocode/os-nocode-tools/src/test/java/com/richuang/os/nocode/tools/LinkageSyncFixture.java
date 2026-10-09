package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.runtime.service.maintenance.LinkageSyncService;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import java.util.*;

/**
 * 数据联动「来源变化时自动更新」集成测试夹具。对象是与标杆场景同形状的虚构对象（不含任何真实数据）：
 *
 * <ul>
 *   <li>流水 flow（目标）：name、status（单选：未登记 wdj / 已录入 ylr / 已审核 ysh）、memo、total（金额）、note
 *   <li>凭证 voucher（来源）：name、status（同一套选项）、amount（金额）、kind、flow（引用流水）
 * </ul>
 *
 * 规则直接写进已发布版本快照（同 FieldRuleFixture），随后发布应用——发布时按固定版本重跑发布校验并登记反向索引。
 */
final class LinkageSyncFixture {
    static final List<DataCenter.Option> STATES =
            List.of(
                    new DataCenter.Option("wdj", "未登记", false),
                    new DataCenter.Option("ylr", "已录入", false),
                    new DataCenter.Option("ysh", "已审核", false));

    final FieldRuleFixture f = new FieldRuleFixture();
    final RecordService runtime = f.runtime;
    final LinkageSyncService sync = servicesContext.getBean(LinkageSyncService.class);
    private int serial;

    DataCenter.Definition flow;
    DataCenter.Definition voucher;
    String app;

    /** 保存并发布对象（可带对象设置，例如办理策略）；标题字段 name 固定在第一列。 */
    DataCenter.Definition object(
            String suffix,
            List<FieldDefinition> extra,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Relation> relations,
            DataCenter.Settings settings) {
        String code = f.prefix() + "ls" + suffix + serial++;
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
                                settings,
                                options,
                                relations,
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "联动同步验证"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return FieldRuleFixture.published(design.draft().id());
    }

    DataCenter.Definition flowObject(DataCenter.Settings settings) {
        return object(
                "flow",
                List.of(
                        FieldRuleFixture.field("status", "状态", "SELECT"),
                        FieldRuleFixture.field("memo", "备注", "TEXT"),
                        FieldRuleFixture.field("total", "合计", "MONEY", 18, 0),
                        FieldRuleFixture.field("note", "说明", "TEXT")),
                Map.of("status", FieldRuleFixture.options(STATES)),
                List.of(),
                settings);
    }

    DataCenter.Definition voucherObject(
            DataCenter.Definition target, String onDelete, DataCenter.Settings settings) {
        return object(
                "voucher",
                List.of(
                        FieldRuleFixture.field("status", "凭证状态", "SELECT"),
                        FieldRuleFixture.field("amount", "金额", "MONEY", 18, 0),
                        FieldRuleFixture.field("kind", "类别", "TEXT")),
                Map.of("status", FieldRuleFixture.options(STATES)),
                List.of(
                        new DataCenter.Relation(
                                null,
                                "flow",
                                "流水",
                                // 级联删除只允许用在主从关系上，普通引用只有限制 / 置空。
                                "CASCADE".equals(onDelete) ? "MASTER_DETAIL" : "REFERENCE",
                                target.objectId(),
                                null,
                                null,
                                false,
                                onDelete,
                                null)),
                settings);
    }

    /** 带一条整单规则「合计不能为负」的对象设置：有单据策略的对象写入时走整单复核。 */
    static DataCenter.Settings nonNegativeTotal() {
        return new DataCenter.Settings(
                null,
                null,
                null,
                null,
                new DocumentPolicy(
                        List.of(
                                new DocumentPolicy.Rule(
                                        "nonnegative",
                                        "合计非负",
                                        "DOCUMENT",
                                        null,
                                        null,
                                        new DocumentPolicy.Expression(
                                                "GE",
                                                null,
                                                null,
                                                null,
                                                List.of(
                                                        new DocumentPolicy.Expression(
                                                                "FIELD", "total", null, null,
                                                                List.of()),
                                                        new DocumentPolicy.Expression(
                                                                "VALUE", null, null, 0,
                                                                List.of()))),
                                        "total",
                                        "合计不能为负")),
                        null));
    }

    static FieldRules.Condition currentRecord(String sourceReferenceField) {
        return new FieldRules.Condition(sourceReferenceField, "eq", "CURRENT_RECORD", null, null);
    }

    /** 一条只读、开启自动更新的联动。 */
    static FieldRules auto(
            DataCenter.Definition source,
            String valueField,
            String multiRow,
            String emptyValue,
            FieldRules.Condition... conditions) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        source.objectId(),
                        List.of(conditions),
                        valueField,
                        multiRow,
                        true,
                        true,
                        emptyValue),
                null,
                null,
                null,
                null);
    }

    /** 标杆配置：流水.状态 ← 凭证.凭证状态，条件「凭证的流水 等于 当前记录」，没有凭证时填「未登记」。 */
    FieldRules benchmarkRule(String multiRow, FieldRules.Condition... extra) {
        var conditions = new ArrayList<FieldRules.Condition>();
        conditions.add(currentRecord(FieldRuleFixture.relationField(voucher, "flow")));
        conditions.addAll(List.of(extra));
        return auto(
                voucher,
                FieldRuleFixture.id(voucher, "status"),
                multiRow,
                "wdj",
                conditions.toArray(FieldRules.Condition[]::new));
    }

    /** 建两个对象、写入标杆规则并发布应用。 */
    void benchmark(String multiRow, String onDelete, ApplicationCenter.Resource... resources) {
        flow = flowObject(DataCenter.Settings.defaults());
        voucher = voucherObject(flow, onDelete, DataCenter.Settings.defaults());
        flow =
                FieldRuleFixture.rules(
                        flow, FieldRuleFixture.id(flow, "status"), benchmarkRule(multiRow));
        app = f.app(List.of(resources), flow, voucher);
    }

    void benchmark() {
        benchmark("FIRST", "RESTRICT");
    }

    // ── 记录读写 ──

    Row createFlow(String name) {
        return createFlow(name, 10001);
    }

    Row createFlow(String name, long actor) {
        return f.save(
                app, flow, FieldRuleFixture.values(FieldRuleFixture.id(flow, "name"), name), actor);
    }

    Row createFlowWithTotal(String name, String total) {
        return f.save(
                app,
                flow,
                FieldRuleFixture.values(
                        FieldRuleFixture.id(flow, "name"),
                        name,
                        FieldRuleFixture.id(flow, "total"),
                        total));
    }

    Row createVoucher(String name, String flowId, String status) {
        return createVoucher(app, name, flowId, status, 10001);
    }

    Row createVoucher(String application, String name, String flowId, String status, long actor) {
        var values = FieldRuleFixture.values(FieldRuleFixture.id(voucher, "name"), name);
        if (flowId != null) values.put(FieldRuleFixture.relationField(voucher, "flow"), flowId);
        if (status != null) values.put(FieldRuleFixture.id(voucher, "status"), status);
        return f.save(application, voucher, values, actor);
    }

    Row update(DataCenter.Definition d, String id, Map<String, Object> values) {
        return update(app, d, id, values, 10001);
    }

    Row update(
            String application,
            DataCenter.Definition d,
            String id,
            Map<String, Object> values,
            long actor) {
        var current = runtime.get(application, d.objectId(), id, actor).record();
        return runtime.save(
                        new Save(application, d.objectId(), id, current.revision(), values, null),
                        actor)
                .record();
    }

    void delete(DataCenter.Definition d, String id) {
        var current = runtime.get(app, d.objectId(), id, 10001).record();
        runtime.delete(new Delete(app, d.objectId(), id, current.revision()), 10001);
    }

    /** 业务表里的列值（不经任何服务，直接读库）。 */
    String column(DataCenter.Definition d, String id, String column) {
        return jdbc.queryForObject(
                "SELECT \""
                        + column
                        + "\"::text FROM public.\""
                        + d.tableName()
                        + "\" WHERE id::text = ?",
                String.class,
                id);
    }

    /** 单值引用关系的物理列名（引用列的编码由平台生成，不等于关系编码）。 */
    static String refColumn(DataCenter.Definition d, String relationCode) {
        String fieldId = FieldRuleFixture.relationField(d, relationCode);
        return d.fields().stream()
                .filter(field -> field.id().equals(fieldId))
                .findFirst()
                .orElseThrow()
                .code();
    }

    String flowStatus(String flowId) {
        return column(flow, flowId, "status");
    }

    /** 行的修订依据：整行内容的摘要与更新时间。 */
    String rowDigest(DataCenter.Definition d, String id) {
        return jdbc.queryForObject(
                "SELECT md5(to_jsonb(t)::text) || '|' || t.update_time::text FROM public.\""
                        + d.tableName()
                        + "\" t WHERE id::text = ?",
                String.class,
                id);
    }

    /** 一条记录的变更历史来源（按发生先后）；没有来源的为 null 节点。 */
    List<JsonNode> historySources(DataCenter.Definition d, String id) {
        var raw =
                jdbc.queryForList(
                        "SELECT COALESCE(source_json::text, 'null') FROM"
                            + " public.nocode_record_history WHERE object_id = ? AND record_id = ?"
                            + " ORDER BY id",
                        String.class,
                        Long.valueOf(d.objectId()),
                        id);
        List<JsonNode> result = new ArrayList<>();
        for (String text : raw) {
            try {
                result.add(mapper.readTree(text));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return result;
    }

    long historyCount(DataCenter.Definition d, String id) {
        return historySources(d, id).size();
    }

    List<JsonNode> linkageHistory(DataCenter.Definition d, String id) {
        return historySources(d, id).stream()
                .filter(node -> "LINKAGE".equals(node.path("kind").asText()))
                .toList();
    }

    /** 索引表里某应用的行数。 */
    int triggerRows(String application) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_linkage_trigger WHERE application_id = ? AND"
                        + " deleted = 0",
                Integer.class,
                Long.valueOf(application));
    }

    /** 把预告翻到底，返回各页累加的「将更新」条数（由空变有值 + 清空 + 值变化）。 */
    int pending(String application, String basis, DataCenter.Definition target, String fieldId) {
        int total = 0;
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            var preview =
                    sync.preview(
                            new LinkageSync.PreviewRequest(
                                    application, basis, target.objectId(), fieldId, cursor, 100),
                            10001);
            assertThat(preview.failedCount()).as("预告不应有求值失败：%s", preview.failed()).isZero();
            total += preview.willFill() + preview.willClear() + preview.willChange();
            if (preview.done()) return total;
            cursor = preview.nextCursor();
        }
        throw new AssertionError("预告没有翻到底");
    }

    int pending() {
        return pending(app, LinkageSync.BASIS_PUBLISHED, flow, FieldRuleFixture.id(flow, "status"));
    }

    /** 给成员 20002 授权（对象 → 范围、动作、可读字段、可写字段）。 */
    void authorize(ApplicationAuthorization.ObjectGrant... grants) {
        var authorization =
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.authorization
                                .ApplicationAuthorizationService.class);
        authorization.save(
                new ApplicationAuthorization.Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", "20002", List.of(grants)))),
                10001);
    }

    static ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d,
            String scope,
            Set<String> actions,
            Set<String> readFields,
            Set<String> writeFields) {
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(), actions, scope, readFields, writeFields, Set.of(), Set.of());
    }

    static Set<String> allFields(DataCenter.Definition d) {
        Set<String> ids = new HashSet<>();
        d.fields().forEach(field -> ids.add(field.id()));
        return ids;
    }

    /** 应用同步：引用的对象改为各自最新发布版本，保存应用草稿后重新发布。 */
    void syncApplication(String application) {
        var api = servicesContext.getBean(DataObjectApi.class);
        var before = f.applications.get(application);
        var refs = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var r : before.draft().objects()) {
            var v = api.getVersion(r.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            v.objectId(), v.versionNo(), v.checksum()));
        }
        var saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                application,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, before.draft().resources())),
                        10001);
        grantApplicationObjects(application);
        f.publishSynced(saved.application());
    }

    void cleanup() {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        f.prefix() + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM public.nocode_linkage_trigger WHERE application_id = ?", id);
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE application_id = ?", id);
            jdbc.update(
                    "DELETE FROM public.nocode_work_submission WHERE draft_id IN (SELECT id FROM"
                            + " public.nocode_work_draft WHERE resource_json->>'applicationId'=?)",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_work_draft WHERE resource_json->>'applicationId'=?",
                    id.toString());
        }
        f.cleanup();
    }
}
