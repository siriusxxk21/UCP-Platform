package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;

import java.math.BigDecimal;
import java.util.*;

/**
 * 「按明细行统计」集成测试的共用夹具：虚构的科目与凭证（不含任何真实数据），形状与财务的凭证录入相同。
 *
 * <ul>
 *   <li>科目：科目编码、科目名称（标题）。记录：1001 现金、1002 银行存款、6001 销售收入、6401 采购成本。
 *   <li>凭证：摘要（标题）、日期、入金、出金、公司（单选：甲公司 / 乙公司）。
 *   <li>明细「分录」：借方科目 → 科目、借方金额、贷方科目 → 科目、贷方金额。
 *   <li>明细「附注」：备注、附注金额（只用来测「另一个明细」）。
 * </ul>
 *
 * 六张凭证见 {@link #seed}；手算的目标表写在各用例里。
 */
final class ReportDetailGrainFixture {
    static final long OWNER = 10001L;
    static final long U1 = 20001L;
    static final long U2 = 20002L;
    static final String COMPANY_A = "jia";
    static final String COMPANY_B = "yi";

    final NocodeIntegrationSupport support = new NocodeIntegrationSupport();
    final ApplicationService apps = servicesContext.getBean(ApplicationService.class);
    final RecordService records = servicesContext.getBean(RecordService.class);
    final DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
    final ApplicationReportService reports =
            servicesContext.getBean(ApplicationReportService.class);

    DataCenter.Definition account;
    DataCenter.Definition voucher;
    DataCenter.Detail lines;
    DataCenter.Detail notes;
    String accountCode, accountName;
    String memo, booked, income, expense, company;
    String debit, debitAmount, credit, creditAmount, debitRelation, creditRelation;
    String remark, noteAmount;
    String app;
    final Map<String, String> accounts = new LinkedHashMap<>();
    final Map<String, String> vouchers = new LinkedHashMap<>();
    final Map<String, String> lineIds = new LinkedHashMap<>();
    private final Map<String, Long> creators = new LinkedHashMap<>();
    private int serial;

    ReportDetailGrainFixture() {
        support.name();
    }

    // ---------------------------------------------------------------- 对象

    private static FieldDefinition field(String code, String name, String type, int sort) {
        return new FieldDefinition(
                code,
                null,
                code,
                name,
                type,
                "TEXT".equals(type) ? 100 : null,
                null,
                null,
                false,
                false,
                sort);
    }

    private DataCenter.Definition publish(DataCenter.Design design) {
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        OWNER);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "按明细行统计"), OWNER)
                                .state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    /** 建立并发布科目与凭证两个对象。 */
    ReportDetailGrainFixture objects() {
        String prefix = support.prefix;
        account =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                prefix + "account",
                                                "科目",
                                                null,
                                                "biz_" + prefix + "account",
                                                "name",
                                                List.of(
                                                        field("name", "科目名称", "TEXT", 0),
                                                        field("code", "科目编码", "TEXT", 1)),
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                OWNER));
        accountName = id(account.fields(), "name");
        accountCode = id(account.fields(), "code");
        var entries =
                new DataCenter.Detail(
                        null,
                        "lines",
                        "分录",
                        "biz_" + prefix + "lines",
                        "ACTIVE",
                        List.of(
                                field("debit_amount", "借方金额", "MONEY", 0),
                                field("credit_amount", "贷方金额", "MONEY", 1)),
                        Map.of(),
                        List.of());
        var remarks =
                new DataCenter.Detail(
                        null,
                        "notes",
                        "附注",
                        "biz_" + prefix + "notes",
                        "ACTIVE",
                        List.of(
                                field("remark", "备注", "TEXT", 0),
                                field("note_amount", "附注金额", "MONEY", 1)),
                        Map.of(),
                        List.of());
        voucher =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                prefix + "voucher",
                                                "凭证",
                                                null,
                                                "biz_" + prefix + "voucher",
                                                "memo",
                                                List.of(
                                                        field("memo", "摘要", "TEXT", 0),
                                                        field("booked", "日期", "DATE", 1),
                                                        field("income", "入金", "MONEY", 2),
                                                        field("expense", "出金", "MONEY", 3),
                                                        field("company", "公司", "SELECT", 4)),
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(
                                                "company",
                                                FieldRuleFixture.options(
                                                        List.of(
                                                                new DataCenter.Option(
                                                                        COMPANY_A, "甲公司", false),
                                                                new DataCenter.Option(
                                                                        COMPANY_B, "乙公司", false)))),
                                        List.of(
                                                reference("debit", "借方科目"),
                                                reference("credit", "贷方科目")),
                                        List.of(),
                                        List.of(entries, remarks)),
                                OWNER));
        memo = id(voucher.fields(), "memo");
        booked = id(voucher.fields(), "booked");
        income = id(voucher.fields(), "income");
        expense = id(voucher.fields(), "expense");
        company = id(voucher.fields(), "company");
        lines = detail("lines");
        notes = detail("notes");
        debitAmount = id(lines.fields(), "debit_amount");
        creditAmount = id(lines.fields(), "credit_amount");
        debitRelation = relation("debit").id();
        creditRelation = relation("credit").id();
        debit = relation("debit").fieldId();
        credit = relation("credit").fieldId();
        remark = id(notes.fields(), "remark");
        noteAmount = id(notes.fields(), "note_amount");
        return this;
    }

    /** 「回单」：主表上有一个指向凭证的单值关系，用来测「路径第二段走进目标对象的明细」。 */
    DataCenter.Definition receipt() {
        String prefix = support.prefix;
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        prefix + "receipt",
                                        "回单",
                                        null,
                                        "biz_" + prefix + "receipt",
                                        "name",
                                        List.of(field("name", "回单号", "TEXT", 0)),
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "voucher",
                                                "所属凭证",
                                                "REFERENCE",
                                                voucher.objectId(),
                                                null,
                                                null,
                                                false,
                                                "RESTRICT")),
                                List.of(),
                                List.of()),
                        OWNER));
    }

    private DataCenter.Relation reference(String code, String name) {
        return new DataCenter.Relation(
                null,
                code,
                name,
                "REFERENCE",
                account.objectId(),
                null,
                null,
                false,
                "RESTRICT",
                "detail:lines");
    }

    static String id(List<FieldDefinition> fields, String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow().id();
    }

    DataCenter.Detail detail(String code) {
        return voucher.details().stream()
                .filter(t -> t.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    DataCenter.Relation relation(String code) {
        return voucher.relations().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    // ---------------------------------------------------------------- 应用与授权

    ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id,
                "按明细行统计 " + id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    List<ApplicationCenter.ObjectReference> references() {
        List<ApplicationCenter.ObjectReference> refs = new ArrayList<>();
        for (var d : List.of(voucher, account)) {
            var v = objects.getVersion(d.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            v.objectId(), v.versionNo(), v.checksum()));
        }
        return refs;
    }

    /** 应用「账务」显式引用凭证与科目，按夹具授予全部对象权限并发布。 */
    ReportDetailGrainFixture app(ApplicationCenter.Resource... resources) {
        var saved =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                support.prefix + "app" + serial++,
                                "账务",
                                null,
                                null,
                                new ApplicationCenter.Definition(references(), List.of(resources))),
                        OWNER);
        app = saved.application().id();
        grantApplicationObjects(app);
        apps.publish(new ApplicationCenter.Revision(app, 0, "按明细行统计"), OWNER);
        return this;
    }

    /** 对象授予应用的上限（应用创建人按它取数）；permission 为空表示撤销。 */
    void ceiling(DataCenter.Definition d, ApplicationAuthorization.ObjectGrant permission) {
        var sharing = servicesContext.getBean(ObjectSharingService.class);
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(d.objectId()))
                        .findFirst()
                        .orElseThrow()
                        .revision();
        sharing.save(
                new ObjectSharing.Save(d.objectId(), app, revision, permission, "按明细行统计"), OWNER);
    }

    /** 成员授权：一个成员一组对象授权；应用授权修订从 0 开始，每次整体替换。 */
    void members(ApplicationAuthorization.Member... members) {
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(app, auth.get(app).revision(), List.of(members)),
                OWNER);
    }

    static ApplicationAuthorization.Member member(
            long user, ApplicationAuthorization.ObjectGrant... grants) {
        return new ApplicationAuthorization.Member("USER", Long.toString(user), List.of(grants));
    }

    Set<String> mainFields() {
        return ids(voucher.fields());
    }

    static Set<String> ids(List<FieldDefinition> fields) {
        Set<String> ids = new LinkedHashSet<>();
        fields.forEach(f -> ids.add(f.id()));
        return ids;
    }

    /** 凭证的查看（可加导出）授权：scope 为 ALL 或 OWN，可读字段与可读明细由调用方给。 */
    ApplicationAuthorization.ObjectGrant voucherGrant(
            Set<String> actions, String scope, Set<String> fields, Set<String> details) {
        return new ApplicationAuthorization.ObjectGrant(
                voucher.objectId(), actions, scope, fields, Set.of(), details, Set.of());
    }

    ApplicationAuthorization.ObjectGrant accountGrant(
            Set<String> actions, String scope, Set<String> fields) {
        return new ApplicationAuthorization.ObjectGrant(
                account.objectId(), actions, scope, fields, Set.of(), Set.of(), Set.of());
    }

    /**
     * 把凭证主表的一个字段在「应用固定的那个已发布对象版本」里标成停用（直接改写版本快照，校验和不变，应用照常解析）。
     * 模拟「统计发布之后字段被停用、已发布快照仍引用它」，又不让应用因对象发了新版本而被暂停。
     */
    void deactivate(String fieldId) {
        try {
            long objectId = Long.parseLong(voucher.objectId());
            var row =
                    jdbc.queryForMap(
                            "SELECT version_no, schema_json::text AS schema FROM"
                                    + " public.nocode_object_version WHERE object_id=? AND"
                                    + " state='PUBLISHED' ORDER BY version_no DESC LIMIT 1",
                            objectId);
            var root =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.readTree((String) row.get("schema"));
            var options = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("fieldOptions");
            if (!options.has(fieldId))
                options.set(fieldId, mapper.valueToTree(DataCenter.FieldOptions.defaults()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) options.get(fieldId))
                    .put("state", "INACTIVE");
            jdbc.update(
                    "UPDATE public.nocode_object_version SET schema_json=CAST(? AS jsonb) WHERE"
                            + " object_id=? AND version_no=?",
                    mapper.writeValueAsString(root),
                    objectId,
                    row.get("version_no"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 数据

    private String account(String code, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(accountCode, code);
        data.put(accountName, name);
        var row =
                records.save(
                                new ApplicationRecords.Save(
                                        app, account.objectId(), null, null, data, null),
                                OWNER)
                        .record();
        accounts.put(name, row.id());
        return row.id();
    }

    /** 一条分录：科目传名称，空表示不填；金额为整数日元。 */
    ApplicationRecords.Row line(
            String debitName, int debitValue, String creditName, int creditValue) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (debitName != null) data.put(debit, accounts.get(debitName));
        data.put(debitAmount, Integer.toString(debitValue));
        if (creditName != null) data.put(credit, accounts.get(creditName));
        data.put(creditAmount, Integer.toString(creditValue));
        return new ApplicationRecords.Row(null, null, data);
    }

    private ApplicationRecords.Aggregate voucher(
            String name,
            String date,
            String companyCode,
            int in,
            int out,
            long creator,
            String firstLine,
            ApplicationRecords.Row... rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(memo, name);
        data.put(booked, date);
        data.put(company, companyCode);
        data.put(income, Integer.toString(in));
        data.put(expense, Integer.toString(out));
        var saved =
                records.save(
                        new ApplicationRecords.Save(
                                app,
                                voucher.objectId(),
                                null,
                                null,
                                data,
                                Map.of(lines.id(), List.of(rows))),
                        OWNER);
        vouchers.put(name, saved.record().id());
        var stored = saved.details().get(lines.id());
        int number = Integer.parseInt(firstLine.substring(1));
        for (int i = 0; i < stored.size(); i++) lineIds.put("L" + (number + i), stored.get(i).id());
        creators.put(name, creator);
        return saved;
    }

    /** 把凭证的创建人改成指定成员（夹具统一由应用创建人录入）；会改变记录修订，所以放在全部保存与删除之后。 */
    void creator(String name, long user) {
        jdbc.update(
                "UPDATE public.\"" + voucher.tableName() + "\" SET creator=? WHERE id=?",
                Long.toString(user),
                Long.parseLong(vouchers.get(name)));
    }

    /**
     * 四个科目与六张凭证：
     *
     * <pre>
     * V1 2026-07-05 甲 入金1000 U1：L1 银行存款600/销售收入600；L2 银行存款400/销售收入400；L7（保存后去掉）银行存款7777/销售收入7777
     * V2 2026-07-20 甲 出金300  U2：L3 采购成本300/银行存款300
     * V3 2026-08-03 乙 入金500  U1：L4 现金200/销售收入200；L5 现金300/（空）300
     * V4 2026-08-15 乙 出金250  U2：L6 采购成本250/银行存款250
     * V5 2026-08-20 甲          U1：没有分录
     * V6 2026-07-09 甲 入金9999 U1：L8 银行存款9999/销售收入9999 —— 整张凭证已删除
     * </pre>
     */
    ReportDetailGrainFixture seed() {
        account("1001", "现金");
        account("1002", "银行存款");
        account("6001", "销售收入");
        account("6401", "采购成本");
        var v1 =
                voucher(
                        "V1",
                        "2026-07-05",
                        COMPANY_A,
                        1000,
                        0,
                        U1,
                        "L1",
                        line("银行存款", 600, "销售收入", 600),
                        line("银行存款", 400, "销售收入", 400));
        voucher("V2", "2026-07-20", COMPANY_A, 0, 300, U2, "L3", line("采购成本", 300, "银行存款", 300));
        voucher(
                "V3",
                "2026-08-03",
                COMPANY_B,
                500,
                0,
                U1,
                "L4",
                line("现金", 200, "销售收入", 200),
                line("现金", 300, null, 300));
        voucher("V4", "2026-08-15", COMPANY_B, 0, 250, U2, "L6", line("采购成本", 250, "银行存款", 250));
        voucher("V5", "2026-08-20", COMPANY_A, 0, 0, U1, "L9");
        // L7：先加进 V1，再整组保存时去掉这一行（明细行是逻辑删除，物理行还在）。
        var kept = v1.details().get(lines.id());
        var withExtra = new ArrayList<>(kept);
        withExtra.add(line("银行存款", 7777, "销售收入", 7777));
        var grown =
                records.save(
                        new ApplicationRecords.Save(
                                app,
                                voucher.objectId(),
                                v1.record().id(),
                                v1.record().revision(),
                                Map.of(),
                                Map.of(lines.id(), withExtra)),
                        OWNER);
        var after = grown.details().get(lines.id());
        assertThat(after).hasSize(3);
        lineIds.put("L7", after.get(2).id());
        records.save(
                new ApplicationRecords.Save(
                        app,
                        voucher.objectId(),
                        grown.record().id(),
                        grown.record().revision(),
                        Map.of(),
                        Map.of(lines.id(), after.subList(0, 2))),
                OWNER);
        var v6 =
                voucher(
                        "V6",
                        "2026-07-09",
                        COMPANY_A,
                        9999,
                        0,
                        U1,
                        "L8",
                        line("银行存款", 9999, "销售收入", 9999));
        records.delete(
                new ApplicationRecords.Delete(
                        app, voucher.objectId(), v6.record().id(), v6.record().revision()),
                OWNER);
        creators.forEach(this::creator);
        return this;
    }

    // ---------------------------------------------------------------- 统计配置

    ApplicationReports.Dimension creditName() {
        return new ApplicationReports.Dimension(accountName, creditRelation, "VALUE");
    }

    ApplicationReports.Dimension debitName() {
        return new ApplicationReports.Dimension(accountName, debitRelation, "VALUE");
    }

    ApplicationReports.Dimension main(String fieldId, String bucket) {
        return new ApplicationReports.Dimension(fieldId, null, bucket);
    }

    /** 目标表 T 的三个指标：贷方金额合计、行数（明细行数）、张数（主记录数）。 */
    List<ApplicationReports.Metric> creditMetrics() {
        return List.of(
                new ApplicationReports.Metric("amount", "贷方金额合计", "SUM", creditAmount),
                new ApplicationReports.Metric("lines", "行数", "COUNT", null),
                new ApplicationReports.Metric("vouchers", "张数", "COUNT_ROOT", null));
    }

    /** 明细粒度·分录的配置：排序指标为第一个指标、降序；日期范围字段为主表日期。 */
    ApplicationReports.Config detailConfig(
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics) {
        return config("DETAIL", lines.id(), display, rows, columns, metrics);
    }

    ApplicationReports.Config config(
            String grain,
            String detailId,
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics) {
        boolean pivot = "PIVOT".equals(display);
        return new ApplicationReports.Config(
                voucher.objectId(),
                rows,
                metrics,
                Map.of(),
                List.of(),
                booked,
                "Asia/Shanghai",
                display,
                metrics.getFirst().id(),
                true,
                30,
                null,
                null,
                null,
                pivot ? columns : null,
                null,
                null,
                null,
                grain,
                detailId);
    }

    /** 目标表 T：行 = 贷方科目 / 科目名称，列 = 日期按月。 */
    ApplicationReports.Config creditPivot() {
        return detailConfig(
                "PIVOT", List.of(creditName()), List.of(main(booked, "MONTH")), creditMetrics());
    }

    /** 在同一份配置上替换固定等值、用户筛选字段与固定条件。 */
    static ApplicationReports.Config with(
            ApplicationReports.Config c,
            Map<String, Object> equal,
            List<String> filters,
            DynamicConditionDTO conditions) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                equal,
                filters,
                c.dateFieldId(),
                c.timeZone(),
                c.display(),
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                c.detailViewId(),
                conditions,
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                c.detailEditable(),
                c.sortBy(),
                c.grain(),
                c.detailId());
    }

    static DynamicConditionDTO condition(String field, String operator, Object value) {
        var item = new DynamicConditionDTO.Item();
        item.setType("condition");
        item.setField(field);
        item.setOperator(operator);
        item.setValue(value);
        var tree = new DynamicConditionDTO();
        tree.setLogic(DynamicConditionDTO.Logic.AND);
        tree.setItems(List.of(item));
        return tree;
    }

    // ---------------------------------------------------------------- 查询与读数

    ApplicationReports.Result preview(ApplicationReports.Config config) {
        return reports.preview(
                new ApplicationReports.Preview(app, references(), config, List.of()), OWNER);
    }

    ApplicationReports.Query query(String report) {
        return new ApplicationReports.Query(app, report, null, null, null, null, null, 1, 100);
    }

    ApplicationReports.Query drill(
            String report, List<String> group, List<String> columnGroup, String metric) {
        return new ApplicationReports.Query(
                app, report, null, null, null, null, group, 1, 100, null, metric, columnGroup);
    }

    static List<String> keys(String... values) {
        return Arrays.asList(values);
    }

    static ApplicationReports.PivotCell cell(
            ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return r.pivot().cells().stream()
                .filter(c -> c.rowKeys().equals(rows) && c.columnKeys().equals(columns))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少透视格 " + rows + " × " + columns));
    }

    static boolean has(ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return r.pivot().cells().stream()
                .anyMatch(c -> c.rowKeys().equals(rows) && c.columnKeys().equals(columns));
    }

    static BigDecimal number(Map<String, String> values, String metric) {
        var value = values.get(metric);
        return value == null ? null : new BigDecimal(value);
    }

    /** 一个格的「贷方金额合计 / 行数 / 张数」。 */
    static void assertCell(
            ApplicationReports.Result r,
            List<String> rows,
            List<String> columns,
            int amount,
            int lineCount,
            int voucherCount) {
        var values = cell(r, rows, columns).values();
        assertThat(number(values, "amount"))
                .as("贷方金额合计 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(amount));
        assertThat(number(values, "lines"))
                .as("行数 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(lineCount));
        assertThat(number(values, "vouchers"))
                .as("张数 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(voucherCount));
    }

    /** 同 {@link #assertCell}，但不在第一处不符时停下：一次把全部不符的格都报出来。 */
    static void softCell(
            org.assertj.core.api.SoftAssertions soft,
            ApplicationReports.Result r,
            List<String> rows,
            List<String> columns,
            int amount,
            int lineCount,
            int voucherCount) {
        var values = cell(r, rows, columns).values();
        soft.assertThat(number(values, "amount"))
                .as("贷方金额合计 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(amount));
        soft.assertThat(number(values, "lines"))
                .as("行数 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(lineCount));
        soft.assertThat(number(values, "vouchers"))
                .as("张数 " + rows + " × " + columns)
                .isEqualByComparingTo(Integer.toString(voucherCount));
    }

    /** 下钻行的身份：主记录ID:明细行ID。 */
    String rowId(String voucherName, String lineName) {
        return vouchers.get(voucherName) + ":" + lineIds.get(lineName);
    }
}
