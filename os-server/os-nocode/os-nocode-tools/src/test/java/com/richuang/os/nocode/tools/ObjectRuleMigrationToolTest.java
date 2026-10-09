package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ObjectRuleMigrationReport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.tools.ObjectRuleMigrationTool.Step;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 对象规则迁移工具（设计稿 9、12.1 迁移演练、15.5、15.9）。夹具全部虚构：与线上同构的「资金流水 → 凭证」8 条带入 （含 2 个 MONEY
 * 目标），公式来源（15.5.2），选项类默认值，同一对象两张表单的带入冲突。
 */
class ObjectRuleMigrationToolTest {
    private static final long ACTOR = 10001;
    private static final List<String> TARGETS =
            List.of(
                    "bank",
                    "account",
                    "memo",
                    "trade_date",
                    "amount_in",
                    "amount_out",
                    "rate",
                    "cnt");

    private final ObjectMapper json = ObjectRuleMigrationTool.mapper();
    private FieldRuleFixture f;
    private ObjectRuleMigrationTool tool;

    /** 资金流水 → 凭证：凭证表单 8 条带入，另一张凭证表单不带入（影响面），凭证有内部明细（无带入）。 */
    private DataCenter.Definition flow, voucher;

    private String cwApp, relation;
    private final List<String> flowRecords = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new FieldRuleFixture();
        tool =
                new ObjectRuleMigrationTool(
                        ObjectRuleMigrationTool.Services.of(servicesContext), json);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    // ── 夹具 ──

    private static List<FieldDefinition> ledgerFields() {
        return List.of(
                field("bank", "银行", "TEXT"),
                field("account", "口座番号", "TEXT"),
                field("memo", "摘要", "TEXTAREA"),
                field("trade_date", "取引日", "DATE"),
                field("amount_in", "入金", "MONEY", 18, 0),
                field("amount_out", "出金", "MONEY", 18, 0),
                field("rate", "利率", "DECIMAL", 10, 4),
                field("cnt", "件数", "INTEGER"));
    }

    private void voucherWorld() {
        flow = f.object("flow", ledgerFields(), Map.of(), List.of(), List.of());
        voucher =
                f.object(
                        "voucher",
                        ledgerFields(),
                        Map.of(),
                        List.of(reference("flow", flow)),
                        List.of(
                                f.detail(
                                        "items",
                                        List.of(
                                                field("subject", "科目", "TEXT"),
                                                field("line_amount", "明细金额", "MONEY", 18, 0)))));
        relation = relationField(voucher, "flow");
        var detail = voucher.details().getFirst();
        var fills = new ArrayList<ApplicationUi.Node>();
        fills.add(node("name", id(voucher, "name"), null));
        fills.add(node("flow", relation, null));
        for (var code : TARGETS)
            fills.add(
                    node(
                            code,
                            id(voucher, code),
                            new FormFills.Binding(relation, id(flow, code), "DEFAULT")));
        var rows =
                detail.fields().stream().map(x -> node("row_" + x.code(), x.id(), null)).toList();
        var main =
                new ApplicationUi.Form(
                        voucher.objectId(),
                        fills,
                        List.of(detail.id()),
                        null,
                        Map.of(detail.id(), rows));
        var plain =
                new ApplicationUi.Form(
                        voucher.objectId(),
                        List.of(
                                node("name", id(voucher, "name"), null),
                                node("flow", relation, null),
                                node("bank", id(voucher, "bank"), null),
                                node("amount_in", id(voucher, "amount_in"), null)),
                        List.of());
        cwApp =
                f.app(
                        List.of(
                                form("voucher_form", "记账凭证", main),
                                form("plain_form", "凭证速记", plain)),
                        flow,
                        voucher);
        // 迁移工具自己负责「发布对象 → 同步并发布应用」：把这个应用的自动跟随单独关掉，验证的才是工具这条路径。
        NocodeIntegrationSupport.stopFollowing(cwApp);
        flowRecords.add(flowRow("三菱", "1234567", "振込手数料", "2026-09-01", 120000, 0, "0.1250", 3));
        flowRecords.add(flowRow("みずほ", "7654321", null, "2026-09-02", 88, null, "1.5000", 1));
        flowRecords.add(flowRow("三井住友", "1112223", "給与", null, 0, 450000, null, null));
    }

    private String flowRow(
            String bank,
            String account,
            String memo,
            String date,
            Object in,
            Object out,
            String rate,
            Integer count) {
        var values = new LinkedHashMap<String, Object>();
        values.put(id(flow, "name"), bank + account);
        values.put(id(flow, "bank"), bank);
        values.put(id(flow, "account"), account);
        values.put(id(flow, "memo"), memo);
        values.put(id(flow, "trade_date"), date);
        values.put(id(flow, "amount_in"), in);
        values.put(id(flow, "amount_out"), out);
        values.put(id(flow, "rate"), rate);
        values.put(id(flow, "cnt"), count);
        return f.save(cwApp, flow, values).id();
    }

    /** 15.5.2：来源值字段是 LIVE 公式字段（总价预算 = 数量 × 单价）。 */
    private String formulaWorld() {
        var budget =
                f.object(
                        "budget",
                        List.of(
                                field("qty", "数量", "INTEGER"),
                                field("price", "单价", "DECIMAL", 20, 4),
                                field("total", "总价预算", "FORMULA")),
                        Map.of("total", formula("qty * price", "DECIMAL")),
                        List.of(),
                        List.of());
        var work =
                f.object(
                        "work",
                        List.of(field("planned", "预算金额", "DECIMAL", 20, 4)),
                        Map.of(),
                        List.of(reference("budget", budget)),
                        List.of());
        String rel = relationField(work, "budget");
        var workForm =
                new ApplicationUi.Form(
                        work.objectId(),
                        List.of(
                                node("name", id(work, "name"), null),
                                node("budget", rel, null),
                                node(
                                        "planned",
                                        id(work, "planned"),
                                        new FormFills.Binding(
                                                rel, id(budget, "total"), "SOURCE_CHANGE"))),
                        List.of());
        String app = f.app(List.of(form("work_form", "施工费", workForm)), budget, work);
        f.save(
                app,
                budget,
                values(id(budget, "name"), "外構", id(budget, "qty"), 3, id(budget, "price"), "2.5"));
        return app;
    }

    /** 同一对象两张表单：fee 的带入来源不同（冲突），note 相同（照常迁移）。 */
    private DataCenter.Definition conflictWorld() {
        var vendor =
                f.object(
                        "vendor",
                        List.of(
                                field("fee_a", "手数料A", "TEXT"),
                                field("fee_b", "手数料B", "TEXT"),
                                field("note", "備考", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        var order =
                f.object(
                        "order",
                        List.of(field("fee", "手数料", "TEXT"), field("note", "備考", "TEXT")),
                        Map.of(),
                        List.of(reference("vendor", vendor)),
                        List.of());
        String rel = relationField(order, "vendor");
        java.util.function.Function<String, ApplicationUi.Form> orderForm =
                fee ->
                        new ApplicationUi.Form(
                                order.objectId(),
                                List.of(
                                        node("vendor", rel, null),
                                        node(
                                                "fee",
                                                id(order, "fee"),
                                                new FormFills.Binding(
                                                        rel, id(vendor, fee), "DEFAULT")),
                                        node(
                                                "note",
                                                id(order, "note"),
                                                new FormFills.Binding(
                                                        rel, id(vendor, "note"), "DEFAULT"))),
                                List.of());
        f.app(
                List.of(
                        form("order_a", "発注A", orderForm.apply("fee_a")),
                        form("order_b", "発注B", orderForm.apply("fee_b"))),
                vendor,
                order);
        return order;
    }

    /** 选项类默认值（存量）：对象字段默认值与表单「本表单默认值」。新校验已禁止写入，这里直接改已发布快照模拟存量。 */
    private record OptionWorld(DataCenter.Definition object, String app, String field) {}

    private OptionWorld optionWorld() throws Exception {
        var object =
                f.object(
                        "survey",
                        List.of(field("status", "状态", "SELECT")),
                        Map.of(
                                "status",
                                options(
                                        List.of(
                                                new DataCenter.Option("A", "甲", false),
                                                new DataCenter.Option("B", "乙", false)))),
                        List.of(),
                        List.of());
        String status = id(object, "status");
        var surveyForm =
                new ApplicationUi.Form(
                        object.objectId(),
                        List.of(
                                node("name", id(object, "name"), null),
                                new ApplicationUi.Node(
                                        "status",
                                        "FIELD",
                                        status,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                "状态",
                                                null,
                                                null,
                                                false,
                                                new SelectionFields.Presentation(
                                                        "AUTO", List.of(), false, null, null, null),
                                                null,
                                                null))),
                        List.of());
        String app = f.app(List.of(form("survey_form", "調査", surveyForm)), object);
        int version = f.applications.published(app).versionNo();
        int objectVersion =
                servicesContext
                        .getBean(DataObjectApi.class)
                        .getVersion(object.objectId(), null)
                        .versionNo();
        jdbc.update(
                "UPDATE public.nocode_object_version SET schema_json=jsonb_set(schema_json,"
                        + " CAST(? AS text[]), '\"A\"'::jsonb) WHERE object_id=? AND version_no=?",
                "{fieldOptions," + status + ",defaultValue}",
                Long.parseLong(object.objectId()),
                objectVersion);
        jdbc.update(
                "UPDATE public.nocode_field SET config_json=jsonb_set(config_json,"
                        + " '{options,defaultValue}', '\"A\"'::jsonb) WHERE stable_field_id=? AND"
                        + " object_version_id=(SELECT id FROM public.nocode_object_version WHERE"
                        + " object_id=? AND version_no=?)",
                Long.parseLong(status),
                Long.parseLong(object.objectId()),
                objectVersion);
        for (var column : List.of("definition_json", "design_json")) {
            String table =
                    column.equals("design_json")
                            ? "nocode_application"
                            : "nocode_application_version";
            String where =
                    column.equals("design_json")
                            ? "id=?"
                            : "application_id=? AND version_no=" + version;
            var text =
                    jdbc.queryForObject(
                            "SELECT " + column + "::text FROM public." + table + " WHERE " + where,
                            String.class,
                            Long.parseLong(app));
            var tree = json.readTree(text);
            var resources =
                    (column.equals("design_json") ? tree : tree.get("definition")).get("resources");
            var node =
                    ObjectRuleMigrationForms.fieldNodes(resources.get(0).get("config"))
                            .get("main:status");
            ((ObjectNode) node.get("presentation").get("selection")).put("defaultValue", "A");
            jdbc.update(
                    "UPDATE public."
                            + table
                            + " SET "
                            + column
                            + "=CAST(? AS jsonb) WHERE "
                            + where,
                    json.writeValueAsString(tree),
                    Long.parseLong(app));
        }
        // 发布表里的快照是直接改写的：同步校验和，否则之后的对象发布会判定快照被篡改。
        rewritePublishedSnapshot(app, tree -> {});
        return new OptionWorld(published(object.objectId()), app, status);
    }

    private static ApplicationUi.Node node(String id, String fieldId, FormFills.Binding fill) {
        return new ApplicationUi.Node(
                id,
                "FIELD",
                fieldId,
                null,
                null,
                null,
                List.of(),
                null,
                new ApplicationUi.FieldPresentation(null, null, null, false, null, null, fill));
    }

    private static ApplicationCenter.Resource form(
            String id, String name, ApplicationUi.Form form) {
        return new ApplicationCenter.Resource(
                id,
                "FORM",
                id,
                name,
                mapper.convertValue(form, new TypeReference<Map<String, Object>>() {}));
    }

    // ── 读取与断言工具 ──

    /** 报告经 JSON 往返，和命令行 apply --report 读取的形态一致。 */
    private ObjectRuleMigrationReport dryRun() throws Exception {
        var report = tool.dryRun(f.prefix());
        return json.readValue(json.writeValueAsString(report), ObjectRuleMigrationReport.class);
    }

    private long objectVersions() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_object_version v JOIN public.nocode_object o ON"
                        + " o.id=v.object_id WHERE o.object_code LIKE ?",
                Long.class,
                f.prefix() + "%");
    }

    private long applicationVersions() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_application_version v JOIN"
                    + " public.nocode_application a ON a.id=v.application_id WHERE a.app_code LIKE"
                    + " ?",
                Long.class,
                f.prefix() + "%");
    }

    private Map<String, Object> publishedForm(String app, String formId) {
        return f.applications.published(app).definition().resources().stream()
                .filter(r -> r.id().equals(formId))
                .findFirst()
                .orElseThrow()
                .config();
    }

    private Map<String, Object> draftForm(String app, String formId) {
        return f.applications.get(app).draft().resources().stream()
                .filter(r -> r.id().equals(formId))
                .findFirst()
                .orElseThrow()
                .config();
    }

    private long fills(Map<String, Object> config) {
        return ObjectRuleMigrationForms.fieldNodes(json.valueToTree(config)).values().stream()
                .filter(n -> ObjectRuleMigrationForms.fill(n) != null)
                .count();
    }

    private FieldRules.Linkage expected(String code) {
        return new FieldRules.Linkage(
                flow.objectId(),
                List.of(new FieldRules.Condition("$record", "eq", "FORM_FIELD", null, relation)),
                id(flow, code),
                "ERROR",
                false,
                null,
                null);
    }

    private ApplicationCenter.ObjectReference pin(String app, String objectId) {
        return f.applications.published(app).definition().objects().stream()
                .filter(r -> r.objectId().equals(objectId))
                .findFirst()
                .orElseThrow();
    }

    // ── 用例 ──

    /** dry-run 只读：冲突、影响面、来源字段类型与计算模式、选项类默认值、明细无迁移项都进报告。 */
    @Test
    void dryRunReportsPlanConflictsImpactAndSourceTypes() throws Exception {
        voucherWorld();
        formulaWorld();
        var order = conflictWorld();
        var option = optionWorld();
        long objects = objectVersions(), apps = applicationVersions();

        var report = dryRun();

        assertThat(objectVersions()).isEqualTo(objects);
        assertThat(applicationVersions()).isEqualTo(apps);
        assertThat(report.blockers()).isEmpty();
        var voucherFills =
                report.fills().stream()
                        .filter(x -> x.objectId().equals(voucher.objectId()))
                        .toList();
        assertThat(voucherFills).hasSize(8).allMatch(x -> PLANNED.equals(x.status()));
        for (var code : TARGETS)
            assertThat(voucherFills)
                    .filteredOn(x -> x.targetFieldId().equals(id(voucher, code)))
                    .singleElement()
                    .satisfies(x -> assertThat(x.linkage()).isEqualTo(expected(code)));
        assertThat(voucherFills)
                .filteredOn(x -> "MONEY".equals(x.targetFieldType()))
                .extracting(Fill::targetFieldName)
                .containsExactlyInAnyOrder("入金", "出金");
        // 15.5.1：内部明细表单没有任何带入，明细侧迁移项为 0。
        assertThat(report.fills()).noneMatch(x -> x.detailId() != null);
        assertThat(report.summary().detailFills()).isZero();
        // 15.5.2：公式来源列出类型与计算模式。
        assertThat(report.fills())
                .filteredOn(x -> "施工费".equals(x.formName()))
                .singleElement()
                .satisfies(
                        x -> {
                            assertThat(x.valueFieldType()).isEqualTo("FORMULA");
                            assertThat(x.valueResultType()).isEqualTo("DECIMAL");
                            assertThat(x.valueCalculationMode()).isEqualTo("LOCAL");
                            assertThat(x.valueCalculationUpdateMode()).isEqualTo("LIVE");
                            assertThat(x.valueStorage()).isEqualTo("CALCULATION");
                            assertThat(x.status()).isEqualTo(PLANNED);
                        });
        // 冲突：fee 在两张表单上来源不同，整条跳过并列出两张表单；note 相同，照常迁移。
        assertThat(report.conflicts())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.targetFieldId()).isEqualTo(id(order, "fee"));
                            assertThat(c.forms()).hasSize(2);
                            assertThat(c.bindings()).hasSize(2);
                        });
        assertThat(report.fills())
                .filteredOn(x -> x.targetFieldId().equals(id(order, "note")))
                .hasSize(2)
                .allMatch(x -> PLANNED.equals(x.status()));
        // 影响面：凭证速记表单没有带入，但含「银行」「入金」，迁移后也会带出建议值。
        assertThat(report.impacts())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.formId()).isEqualTo("plain_form");
                            assertThat(i.targetFieldNames()).containsExactlyInAnyOrder("银行", "入金");
                        });
        // 选项类默认值：对象与表单各一处。
        assertThat(report.objects())
                .filteredOn(o -> o.objectId().equals(option.object().objectId()))
                .singleElement()
                .satisfies(
                        o ->
                                assertThat(o.optionDefaults())
                                        .extracting(OptionDefault::fieldId)
                                        .containsExactly(option.field()));
        assertThat(report.applications())
                .filteredOn(a -> a.applicationId().equals(option.app()))
                .flatExtracting(ApplicationChange::forms)
                .flatExtracting(Form::optionDefaults)
                .extracting(FormOptionDefault::defaultValue)
                .containsExactly("A");
        assertThat(report.summary().conflicts()).isEqualTo(2);
    }

    /** apply 生成正确的 rules.linkage、升级并发布应用、去掉表单带入；二次 apply 版本数不变；compare 对三类夹具差异为 0。 */
    @Test
    void applyWritesLinkagesIsIdempotentAndComparesClean() throws Exception {
        voucherWorld();
        String workApp = formulaWorld();
        conflictWorld();
        var report = dryRun();
        var baseline = tool.compare(report, ACTOR, 50);
        assertThat(baseline.comparisons()).allMatch(c -> "PLANNED".equals(c.mode()));
        assertThat(baseline.diffs()).isEmpty();
        int voucherVersion = pin(cwApp, voucher.objectId()).versionNo();

        var run = tool.apply(report, ACTOR);

        assertThat(run.steps()).anyMatch(s -> "PUBLISHED".equals(s.action()));
        var migrated = published(voucher.objectId());
        for (var code : TARGETS) {
            var rules = migrated.fieldOptions().get(id(voucher, code)).rules();
            assertThat(rules.linkage()).isEqualTo(expected(code));
            assertThat(rules.rounding()).isNull();
            assertThat(rules.defaultFormula()).isNull();
        }
        assertThat(pin(cwApp, voucher.objectId()).versionNo()).isEqualTo(voucherVersion + 1);
        assertThat(fills(publishedForm(cwApp, "voucher_form"))).isZero();
        assertThat(fills(draftForm(cwApp, "voucher_form"))).isZero();
        assertThat(fills(publishedForm(workApp, "work_form"))).isZero();
        // 冲突字段不迁移，带入原样保留；无冲突的 note 已迁移。
        var orders =
                report.applications().stream()
                        .filter(a -> a.forms().stream().anyMatch(x -> x.formId().equals("order_a")))
                        .findFirst()
                        .orElseThrow();
        assertThat(fills(publishedForm(orders.applicationId(), "order_a"))).isEqualTo(1);

        var after = tool.compare(report, ACTOR, 50);
        assertThat(after.comparisons()).allMatch(c -> "RUNTIME".equals(c.mode()));
        assertThat(after.comparisons())
                .filteredOn(c -> Set.of("voucher_form", "work_form").contains(c.formId()))
                .extracting(ObjectRuleMigrationTool.Comparison::records)
                .containsExactlyInAnyOrder(3, 1);
        assertThat(after.diffs()).isEmpty();
        // MONEY 目标：新规则带出的值与来源一致（缺省 FLOOR 对整数来源是空操作）。
        var evaluation =
                f.runtime.evaluateRules(
                        new FieldRules.EvaluateQuery(
                                cwApp,
                                voucher.objectId(),
                                "voucher_form",
                                null,
                                true,
                                Map.of(relation, flowRecords.getFirst()),
                                List.of(relation),
                                List.of(),
                                null),
                        ACTOR);
        assertThat(new BigDecimal(result(evaluation, id(voucher, "amount_in")).value().toString()))
                .isEqualByComparingTo("120000");

        long objects = objectVersions(), apps = applicationVersions();
        var again = tool.apply(report, ACTOR);
        assertThat(again.steps()).allMatch(s -> "UNCHANGED".equals(s.action()));
        assertThat(objectVersions()).isEqualTo(objects);
        assertThat(applicationVersions()).isEqualTo(apps);
        var rerun = dryRun();
        assertThat(rerun.fills())
                .filteredOn(x -> x.objectId().equals(voucher.objectId()))
                .isEmpty();
    }

    /** rollback：表单 JSON 与迁移前快照逐字一致、固定版本恢复、旧带入行为恢复；再 apply 对象版本不增长。 */
    @Test
    void rollbackRestoresFormsVerbatimAndOldFill() throws Exception {
        voucherWorld();
        var report = dryRun();
        var before =
                report.applications().stream()
                        .filter(a -> a.applicationId().equals(cwApp))
                        .findFirst()
                        .orElseThrow();
        var snapshot =
                before.forms().stream()
                        .filter(x -> x.formId().equals("voucher_form"))
                        .findFirst()
                        .orElseThrow();
        var pinned = pin(cwApp, voucher.objectId());
        tool.apply(report, ACTOR);
        assertThatThrownBy(
                        () ->
                                f.runtime.formFill(
                                        new FormFills.Query(
                                                cwApp,
                                                voucher.objectId(),
                                                "voucher_form",
                                                relation,
                                                flowRecords.getFirst()),
                                        ACTOR))
                .hasMessageContaining("未配置此关联填充");

        var run = tool.rollback(report, ACTOR);

        assertThat(run.steps())
                .singleElement()
                .satisfies(s -> assertThat(s.action()).isEqualTo("PUBLISHED"));
        assertThat(json.writeValueAsString(publishedForm(cwApp, "voucher_form")))
                .isEqualTo(json.writeValueAsString(snapshot.configBefore()));
        assertThat(json.writeValueAsString(draftForm(cwApp, "voucher_form")))
                .isEqualTo(json.writeValueAsString(snapshot.configBefore()));
        assertThat(pin(cwApp, voucher.objectId())).isEqualTo(pinned);
        var old =
                f.runtime.formFill(
                        new FormFills.Query(
                                cwApp,
                                voucher.objectId(),
                                "voucher_form",
                                relation,
                                flowRecords.getFirst()),
                        ACTOR);
        assertThat(old)
                .containsKeys(
                        TARGETS.stream().map(code -> id(voucher, code)).toArray(String[]::new));
        assertThat(old.get(id(voucher, "bank"))).isEqualTo("三菱");
        assertThat(tool.rollback(report, ACTOR).steps())
                .allMatch(s -> "UNCHANGED".equals(s.action()));

        long objects = objectVersions();
        tool.apply(report, ACTOR);
        assertThat(objectVersions()).isEqualTo(objects);
        assertThat(fills(publishedForm(cwApp, "voucher_form"))).isZero();
        assertThat(tool.compare(report, ACTOR, 50).diffs()).isEmpty();
    }

    /** 选项类默认值：对象字段默认值与表单本表单默认值都被清理；回滚不恢复（发布校验已禁止）。 */
    @Test
    void optionDefaultsAreCleared() throws Exception {
        var option = optionWorld();
        assertThat(option.object().fieldOptions().get(option.field()).defaultValue())
                .isEqualTo("A");
        var report = dryRun();

        assertThat(report.warnings()).anyMatch(w -> w.contains("不可回滚"));
        var applied = tool.apply(report, ACTOR);

        // 清默认值在平台契约里是不兼容变更：对象发布时应用被暂停，工具同步后发布启用，结束时应用照常运行。
        assertThat(applied.steps())
                .filteredOn(s -> "OBJECT".equals(s.kind()))
                .singleElement()
                .satisfies(s -> assertThat(s.details()).anyMatch(d -> d.contains("暂停仍固定旧版本的应用")));
        assertThat(applied.steps())
                .filteredOn(s -> "APPLICATION".equals(s.kind()))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.action()).isEqualTo("PUBLISHED");
                            assertThat(s.details()).anyMatch(d -> d.contains("发布启用"));
                        });
        assertThat(f.applications.get(option.app()).application().status()).isEqualTo("ACTIVE");
        assertThat(
                        published(option.object().objectId())
                                .fieldOptions()
                                .get(option.field())
                                .defaultValue())
                .isNull();
        var upgraded = pin(option.app(), option.object().objectId());
        for (var config :
                List.of(
                        publishedForm(option.app(), "survey_form"),
                        draftForm(option.app(), "survey_form")))
            assertThat(
                            ObjectRuleMigrationForms.selectionDefault(
                                    ObjectRuleMigrationForms.fieldNodes(json.valueToTree(config))
                                            .get("main:status")))
                    .isNull();
        tool.rollback(report, ACTOR);
        assertThat(
                        ObjectRuleMigrationForms.selectionDefault(
                                ObjectRuleMigrationForms.fieldNodes(
                                                json.valueToTree(
                                                        publishedForm(option.app(), "survey_form")))
                                        .get("main:status")))
                .isNull();
        assertThat(pin(option.app(), option.object().objectId())).isEqualTo(upgraded);
        long objects = objectVersions();
        assertThat(tool.apply(report, ACTOR).steps())
                .filteredOn(s -> "OBJECT".equals(s.kind()))
                .allMatch(s -> "UNCHANGED".equals(s.action()));
        assertThat(objectVersions()).isEqualTo(objects);
    }

    /** 清默认值的对象发布会暂停仍固定旧版本的应用：报告没有把该应用列入同步范围时中止，不停掉应用，也不发布对象。 */
    @Test
    void optionDefaultCleanupRefusesToPauseApplicationsOutsideTheReport() throws Exception {
        var option = optionWorld();
        ObjectNode root = json.valueToTree(dryRun());
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("applications")).removeAll();
        var withoutApplications = json.treeToValue(root, ObjectRuleMigrationReport.class);
        var api = servicesContext.getBean(DataObjectApi.class);
        int version = api.getVersion(option.object().objectId(), null).versionNo();

        assertThatThrownBy(() -> tool.apply(withoutApplications, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在本次迁移同步范围内");

        assertThat(f.applications.get(option.app()).application().status()).isEqualTo("ACTIVE");
        assertThat(api.getVersion(option.object().objectId(), null).versionNo())
                .as("对象没有发布新版本")
                .isEqualTo(version);
        assertThat(
                        published(option.object().objectId())
                                .fieldOptions()
                                .get(option.field())
                                .defaultValue())
                .isEqualTo("A");
    }

    /** compare 能发现迁移错误：把「银行」的来源字段故意迁成「口座番号」，compare 必须逐条报出且只报这一字段。 */
    @Test
    void compareReportsWrongMigration() throws Exception {
        voucherWorld();
        var report = tamper(dryRun(), id(voucher, "bank"), id(flow, "account"));

        tool.apply(report, ACTOR);
        var compared = tool.compare(report, ACTOR, 50);

        assertThat(compared.differences()).isEqualTo(3);
        assertThat(compared.diffs())
                .allSatisfy(
                        d -> {
                            assertThat(d.targetFieldId()).isEqualTo(id(voucher, "bank"));
                            assertThat(d.newState()).isEqualTo("APPLIED");
                            assertThat(d.newValue()).isNotEqualTo(d.oldValue());
                        });
    }

    private ObjectRuleMigrationReport tamper(
            ObjectRuleMigrationReport report, String target, String wrongSource) throws Exception {
        JsonNode root = json.valueToTree(report);
        for (var fill : root.get("fills"))
            if (target.equals(fill.path("targetFieldId").asText()))
                ((ObjectNode) fill.get("linkage")).put("valueFieldId", wrongSource);
        for (var object : root.get("objects"))
            for (var linkage : object.get("linkages"))
                if (target.equals(linkage.path("fieldId").asText()))
                    ((ObjectNode) linkage.get("linkage")).put("valueFieldId", wrongSource);
        return json.treeToValue(root, ObjectRuleMigrationReport.class);
    }

    // ── 空草稿（设计稿 9.2：对象上有与已发布版本一致的未发布草稿） ──

    private static final String DRAFT_BLOCKER = "有未发布草稿，请先发布或放弃草稿后重新 dry-run";

    /** 按系统「编辑已发布对象」开草稿（不做任何修改）。 */
    private static DataCenter.Design openDraft(DataCenter.Definition d) {
        var current = designs.get(d.objectId());
        return designs.editPublished(
                new DataCenter.Revision(d.objectId(), current.draft().lockVersion(), "空草稿用例"),
                ACTOR);
    }

    /** 以草稿当前内容为底保存（与 apply 保存草稿的写法相同），字段经 change 改写。 */
    private static DataCenter.Design saveDraft(
            DataCenter.Design design, UnaryOperator<List<FieldDefinition>> change) {
        var draft = design.draft();
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                change.apply(draft.fields()),
                                List.of(),
                                null,
                                draft.category()),
                        design.settings(),
                        design.fieldOptions(),
                        design.relations(),
                        design.indexes(),
                        design.details(),
                        design.mainBinding()),
                ACTOR);
    }

    private static UnaryOperator<List<FieldDefinition>> renamed(String code, String name) {
        return fields ->
                fields.stream()
                        .map(
                                x ->
                                        x.code().equals(code)
                                                ? new FieldDefinition(
                                                        x.key(),
                                                        x.id(),
                                                        x.code(),
                                                        name,
                                                        x.type(),
                                                        x.length(),
                                                        x.precision(),
                                                        x.scale(),
                                                        x.required(),
                                                        x.unique(),
                                                        x.sort())
                                                : x)
                        .toList();
    }

    private static int draftVersion(DataCenter.Definition d) {
        return designs.head(d.objectId(), false).getLatestVersionNo();
    }

    private static ObjectChange objectChange(
            ObjectRuleMigrationReport report, DataCenter.Definition d) {
        return report.objects().stream()
                .filter(o -> o.objectId().equals(d.objectId()))
                .findFirst()
                .orElseThrow();
    }

    /** 空草稿（开了没改，或改过又原样保存）：dry-run 不阻断、记一条 info 与草稿版本号；apply 沿用这份草稿写规则并发布， 不另开版本；compare 差异为 0。 */
    @Test
    void emptyDraftIsReusedByApply() throws Exception {
        voucherWorld();
        int publishedVersion = pin(cwApp, voucher.objectId()).versionNo();
        var opened = openDraft(voucher);
        int draft = draftVersion(voucher);
        assertThat(draft).isEqualTo(publishedVersion + 1);

        var report = dryRun();

        assertThat(report.blockers()).isEmpty();
        assertThat(objectChange(report, voucher))
                .satisfies(
                        o -> {
                            assertThat(o.blocker()).isNull();
                            assertThat(o.reusedDraftVersion()).isEqualTo(draft);
                        });
        assertThat(report.summary().reusedDrafts()).isEqualTo(1);
        assertThat(report.infos())
                .singleElement()
                .asString()
                .contains("「" + voucher.objectName() + "」")
                .contains("已发布版本 v" + publishedVersion)
                .contains("空草稿 v" + draft);
        // 原样保存一次（草稿快照按当前代码重算）仍是空草稿。
        saveDraft(opened, fields -> fields);
        assertThat(dryRun().blockers()).isEmpty();

        assertThat(tool.compare(report, ACTOR, 50).diffs()).isEmpty();
        long objects = objectVersions();
        var run = tool.apply(report, ACTOR);

        assertThat(run.steps())
                .filteredOn(s -> s.id().equals(voucher.objectId()))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.action()).isEqualTo("PUBLISHED");
                            assertThat(s.versionBefore()).isEqualTo(publishedVersion);
                            assertThat(s.versionAfter()).isEqualTo(draft);
                            assertThat(s.details().getFirst())
                                    .isEqualTo(
                                            "沿用与已发布版本 v" + publishedVersion + " 一致的空草稿 v" + draft);
                        });
        assertThat(objectVersions()).as("沿用草稿，不另开版本").isEqualTo(objects);
        var migrated = published(voucher.objectId());
        for (var code : TARGETS)
            assertThat(migrated.fieldOptions().get(id(voucher, code)).rules().linkage())
                    .isEqualTo(expected(code));
        assertThat(pin(cwApp, voucher.objectId()).versionNo()).isEqualTo(draft);
        assertThat(tool.compare(report, ACTOR, 50).diffs()).isEmpty();
    }

    /** 草稿有真实改动（改了一个字段名）：仍然阻断，文案不变；apply 拒绝执行。 */
    @Test
    void changedDraftStillBlocks() throws Exception {
        voucherWorld();
        saveDraft(openDraft(voucher), renamed("memo", "摘要（草稿改名）"));

        var report = dryRun();

        assertThat(report.blockers())
                .containsExactly("对象「" + voucher.objectName() + "」" + DRAFT_BLOCKER);
        assertThat(objectChange(report, voucher).reusedDraftVersion()).isNull();
        assertThat(report.infos()).isEmpty();
        assertThat(report.summary().reusedDrafts()).isZero();
        assertThatThrownBy(() -> tool.apply(report, ACTOR)).hasMessageContaining("报告含阻断项");
    }

    /** dry-run 时是空草稿，apply 前被改成有改动：apply 再判一次并拒绝，不写对象、不发布应用，草稿上的改动原样保留。 */
    @Test
    void draftChangedAfterDryRunIsRejected() throws Exception {
        voucherWorld();
        var opened = openDraft(voucher);
        var report = dryRun();
        assertThat(report.blockers()).isEmpty();
        assertThat(objectChange(report, voucher).reusedDraftVersion()).isNotNull();
        saveDraft(opened, renamed("memo", "摘要（dry-run 之后改名）"));
        long objects = objectVersions(), apps = applicationVersions();
        var pinned = pin(cwApp, voucher.objectId());

        assertThatThrownBy(() -> tool.apply(report, ACTOR))
                .hasMessageContaining("「" + voucher.objectName() + "」有未发布草稿")
                .hasMessageContaining("与已发布版本不一致");

        assertThat(objectVersions()).isEqualTo(objects);
        assertThat(applicationVersions()).isEqualTo(apps);
        assertThat(pin(cwApp, voucher.objectId())).isEqualTo(pinned);
        assertThat(
                        Optional.ofNullable(
                                        published(voucher.objectId())
                                                .fieldOptions()
                                                .get(id(voucher, "bank"))
                                                .rules())
                                .map(FieldRules::linkage))
                .isEmpty();
        assertThat(designs.get(voucher.objectId()).draft().fields())
                .anyMatch(x -> x.name().equals("摘要（dry-run 之后改名）"));
        assertThat(fills(publishedForm(cwApp, "voucher_form"))).isEqualTo(TARGETS.size());
    }

    // ── linkage-readonly（2026-09-29 只读口径） ──

    private List<Step> steps(ObjectRuleMigrationTool.Run run, String id) {
        return run.steps().stream().filter(x -> x.id().equals(id)).toList();
    }

    /**
     * linkage-readonly：dry-run 不写任何版本、列出将改为只读的 8 个字段与将同步的应用；执行后对象上的联动全部只读（其余配置不变）、应用固定版本升到对象最新版并发布，
     * 运行期结果只读；二次执行全部 UNCHANGED、不产生新版本；之后重跑 apply 不把只读改回、compare 仍为 0 差异。
     */
    @Test
    void linkageReadOnlyMakesMigratedLinkagesReadOnlyAndSyncsApplications() throws Exception {
        voucherWorld();
        var report = dryRun();
        tool.apply(report, ACTOR);
        int pinned = pin(cwApp, voucher.objectId()).versionNo();
        var codes = List.of(voucher.objectCode());
        long objects = objectVersions(), apps = applicationVersions();

        var plan = tool.linkageReadOnly(codes, ACTOR, true);

        assertThat(objectVersions()).isEqualTo(objects);
        assertThat(applicationVersions()).isEqualTo(apps);
        assertThat(steps(plan, voucher.objectId()))
                .singleElement()
                .satisfies(
                        x -> {
                            assertThat(x.action()).isEqualTo("PLANNED");
                            assertThat(x.details())
                                    .hasSize(TARGETS.size())
                                    .allMatch(d -> d.endsWith("：数据联动改为只读"));
                        });
        assertThat(steps(plan, cwApp))
                .singleElement()
                .extracting(Step::action)
                .isEqualTo("PLANNED");
        for (var code : TARGETS)
            assertThat(
                            published(voucher.objectId())
                                    .fieldOptions()
                                    .get(id(voucher, code))
                                    .rules()
                                    .linkage()
                                    .readOnly())
                    .isFalse();

        var run = tool.linkageReadOnly(codes, ACTOR, false);

        // 先断言副作用（对象规则、应用固定版本），再断言步骤摘要。
        var migrated = published(voucher.objectId());
        for (var code : TARGETS) {
            var rules = migrated.fieldOptions().get(id(voucher, code)).rules();
            assertThat(rules.linkage().readOnly()).isTrue();
            assertThat(
                            ObjectRuleMigrationPlanner.sameIgnoringReadOnly(
                                    rules.linkage(), expected(code)))
                    .isTrue();
            assertThat(rules.readOnly()).as("投影字段不进存储快照").isNull();
        }
        var latest =
                servicesContext.getBean(DataObjectApi.class).getVersion(voucher.objectId(), null);
        assertThat(latest.versionNo()).isEqualTo(pinned + 1);
        assertThat(pin(cwApp, voucher.objectId()).versionNo()).isEqualTo(latest.versionNo());
        assertThat(
                        f.applications.get(cwApp).draft().objects().stream()
                                .filter(r -> r.objectId().equals(voucher.objectId()))
                                .findFirst()
                                .orElseThrow()
                                .versionNo())
                .isEqualTo(latest.versionNo());
        assertThat(steps(run, voucher.objectId()))
                .singleElement()
                .extracting(Step::action)
                .isEqualTo("PUBLISHED");
        assertThat(steps(run, cwApp))
                .singleElement()
                .extracting(Step::action)
                .isEqualTo("PUBLISHED");
        var evaluation =
                f.runtime.evaluateRules(
                        new FieldRules.EvaluateQuery(
                                cwApp,
                                voucher.objectId(),
                                "voucher_form",
                                null,
                                true,
                                Map.of(relation, flowRecords.getFirst()),
                                List.of(relation),
                                List.of(),
                                null),
                        ACTOR);
        assertThat(result(evaluation, id(voucher, "bank")).readOnly()).isTrue();

        long objectsAfter = objectVersions(), appsAfter = applicationVersions();
        var again = tool.linkageReadOnly(codes, ACTOR, false);
        assertThat(again.steps()).allMatch(x -> "UNCHANGED".equals(x.action()));
        assertThat(objectVersions()).isEqualTo(objectsAfter);
        assertThat(applicationVersions()).isEqualTo(appsAfter);
        // 迁移工具其余子命令与只读开关解耦：重跑 apply 不改回可编辑，compare 仍一致。
        assertThat(tool.apply(report, ACTOR).steps()).allMatch(x -> "UNCHANGED".equals(x.action()));
        assertThat(
                        published(voucher.objectId())
                                .fieldOptions()
                                .get(id(voucher, "bank"))
                                .rules()
                                .linkage()
                                .readOnly())
                .isTrue();
        assertThat(tool.compare(report, ACTOR, 50).diffs()).isEmpty();
    }

    /** 未列出的对象不动；找不到的编码在改动前整体中止；有改动的草稿时 dry-run 提示、执行拒绝。 */
    @Test
    void linkageReadOnlyOnlyTouchesListedObjectsAndRespectsDrafts() throws Exception {
        voucherWorld();
        formulaWorld();
        var report = dryRun();
        tool.apply(report, ACTOR);
        var work =
                report.objects().stream()
                        .filter(o -> !o.objectId().equals(voucher.objectId()))
                        .filter(o -> !o.linkages().isEmpty())
                        .findFirst()
                        .orElseThrow();
        long objects = objectVersions(), apps = applicationVersions();

        assertThatThrownBy(
                        () ->
                                tool.linkageReadOnly(
                                        List.of(voucher.objectCode(), "zz_not_exists"),
                                        ACTOR,
                                        false))
                .hasMessageContaining("zz_not_exists");
        assertThat(objectVersions()).isEqualTo(objects);

        saveDraft(openDraft(voucher), renamed("memo", "摘要（草稿改名）"));
        var plan = tool.linkageReadOnly(List.of(voucher.objectCode()), ACTOR, true);
        assertThat(steps(plan, voucher.objectId()).getFirst().details().getFirst())
                .contains("执行时将拒绝");
        assertThatThrownBy(() -> tool.linkageReadOnly(List.of(voucher.objectCode()), ACTOR, false))
                .hasMessageContaining("有未发布草稿")
                .hasMessageContaining("与已发布版本不一致");
        assertThat(applicationVersions()).isEqualTo(apps);
        // 未列出的对象（施工费）上的联动保持可编辑。
        assertThat(
                        published(work.objectId()).fieldOptions().values().stream()
                                .filter(o -> o.rules() != null && o.rules().linkage() != null)
                                .map(o -> o.rules().linkage().readOnly()))
                .containsOnly(false);
    }

    /** 命令行参数：首个裸参数是子命令，其余为 --键 值。 */
    @Test
    void parsesCommandLine() {
        assertThat(
                        ObjectRuleMigrationTool.arguments(
                                new String[] {"apply", "--report", "r.json", "--actor", "1"}))
                .containsEntry("", "apply")
                .containsEntry("report", "r.json")
                .containsEntry("actor", "1");
        assertThatThrownBy(() -> ObjectRuleMigrationTool.arguments(new String[] {"--report"}))
                .hasMessageContaining("缺少取值");
        var readonly =
                ObjectRuleMigrationTool.Command.parse(
                        new String[] {
                            "linkage-readonly",
                            "--object-codes",
                            "OBJECT_KJPZLR, object_sgfz,object_kjpzlr",
                            "--actor",
                            "7",
                            "--dry-run"
                        },
                        json);
        assertThat(readonly.objectCodes()).containsExactly("object_kjpzlr", "object_sgfz");
        assertThat(readonly.actor()).isEqualTo(7);
        assertThat(readonly.dryRun()).isTrue();
        assertThat(readonly.out()).isEqualTo("linkage-readonly.json");
        assertThat(
                        ObjectRuleMigrationTool.Command.parse(
                                        new String[] {
                                            "linkage-readonly",
                                            "--object-codes",
                                            "a",
                                            "--actor",
                                            "1"
                                        },
                                        json)
                                .dryRun())
                .isFalse();
        assertThat(ObjectRuleMigrationTool.same(new BigDecimal("120000"), "120000.00")).isTrue();
        assertThat(ObjectRuleMigrationTool.same(null, null)).isTrue();
        assertThat(ObjectRuleMigrationTool.same("三菱", "みずほ")).isFalse();
    }

    /** 编码前缀按小写比较（设计稿 9.5）：大写前缀与小写前缀得到同一份报告，不再静默匹配 0 条。 */
    @Test
    void prefixIsComparedInLowerCase() throws Exception {
        voucherWorld();
        var lower = dryRun();
        var upper = tool.dryRun(f.prefix().toUpperCase(Locale.ROOT));

        assertThat(lower.summary().fills()).isEqualTo(TARGETS.size());
        assertThat(upper.scopePrefix()).isEqualTo(f.prefix());
        assertThat(upper.summary()).isEqualTo(lower.summary());
        assertThat(ObjectRuleMigrationTool.normalizePrefix("SMK_M_")).isEqualTo("smk_m_");
        assertThat(ObjectRuleMigrationTool.normalizePrefix(null)).isEmpty();
        assertThat(ObjectRuleMigrationTool.normalizePrefix("")).isEmpty();
        for (var invalid : List.of("smk-", "smk%", "1smk", "_smk", "smk m"))
            assertThatThrownBy(() -> ObjectRuleMigrationTool.normalizePrefix(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--prefix");
    }

    /** 参数错误在连库之前拦下，退出码 2（设计稿 9.5 退出码）。 */
    @Test
    void rejectsBadArgumentsBeforeConnecting() {
        for (var args :
                List.of(
                        new String[] {"migrate"},
                        new String[] {"dry-run", "--prefix", "smk-%"},
                        new String[] {"dry-run", "--prefx", "smk_"},
                        new String[] {"apply", "--actor", "1"},
                        new String[] {"apply", "--report", "missing.json", "--actor", "x"},
                        new String[] {"compare", "--report", "missing.json", "--actor", "1"},
                        new String[] {
                            "rollback", "--report", "r.json", "--actor", "1", "--sample", "5"
                        },
                        new String[] {"linkage-readonly", "--actor", "1"},
                        new String[] {"linkage-readonly", "--object-codes", "a", "--dry-run"},
                        new String[] {
                            "linkage-readonly", "--object-codes", "object-kjpzlr", "--actor", "1"
                        },
                        new String[] {"linkage-readonly", "--object-codes", "a,,b", "--actor", "1"},
                        new String[] {
                            "linkage-readonly",
                            "--object-codes",
                            "a",
                            "--actor",
                            "1",
                            "--report",
                            "r.json"
                        },
                        new String[] {"apply", "--report", "r.json", "--actor", "1", "--dry-run"}))
            assertThat(ObjectRuleMigrationTool.run(args))
                    .as(String.join(" ", args))
                    .isEqualTo(ObjectRuleMigrationTool.EXIT_USAGE);
    }
}
