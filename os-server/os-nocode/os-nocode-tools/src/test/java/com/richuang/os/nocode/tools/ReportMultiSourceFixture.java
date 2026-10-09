package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;

import java.math.BigDecimal;
import java.util.*;

/**
 * 「统计视图多个数据来源」集成测试的共用夹具（契约 P1-CONTRACT 14.1，虚构数据）：
 *
 * <ul>
 *   <li>物件：名称（标题）。P1「青山」、P2「白川」、P3「青山」（与 P1 同名、不同记录）。
 *   <li>入住记录：名称（标题）、物件 → 物件、入住日、退房日、当月金额、次月金额。S1–S5。
 *   <li>支出：名称（标题）、物件 → 物件、支出日期、金额。E1–E4（E4 没有物件）。
 *   <li>会计科目：科目名称（标题）。A1「現金」、A2「売上」、A3「普通預金」。
 *   <li>会计凭证：摘要（标题）、凭证日期；明细「分录」：借方科目 / 贷方科目 → 会计科目、借方金额、贷方金额。V1–V3、分录 L1–L5。
 * </ul>
 *
 * 手算的目标表写在各用例里（与契约 14.2–14.4 逐格相同）。
 */
final class ReportMultiSourceFixture {
    static final long OWNER = 10001L;
    static final long U1 = 20001L;
    static final long U2 = 20002L;

    final NocodeIntegrationSupport support = new NocodeIntegrationSupport();
    final ApplicationService apps = servicesContext.getBean(ApplicationService.class);
    final RecordService records = servicesContext.getBean(RecordService.class);
    final DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
    final ApplicationReportService reports =
            servicesContext.getBean(ApplicationReportService.class);

    DataCenter.Definition property, stay, expense, account, voucher;
    DataCenter.Detail lines;
    String propertyName;
    String stayName, stayProperty, stayPropertyRelation, checkIn, checkOut, curAmount, nextAmount;
    String expenseName, expenseProperty, expensePropertyRelation, paidOn, amount;
    String stayChannel, paidAt, category, expenseChannel;
    String accountName;
    String memo, booked, debit, credit, debitAmount, creditAmount;
    String app;
    final Map<String, String> ids = new LinkedHashMap<>();
    private final Map<String, Long> creators = new LinkedHashMap<>();
    private int serial;

    ReportMultiSourceFixture() {
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
                                .execute(new DataCenter.ExecutePlan(plan.id(), "多个数据来源"), OWNER)
                                .state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    private DataCenter.Definition object(
            String code,
            String name,
            String title,
            List<FieldDefinition> fields,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details) {
        return object(code, name, title, fields, null, relations, details);
    }

    private DataCenter.Definition object(
            String code,
            String name,
            String title,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details) {
        String prefix = support.prefix;
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        prefix + code,
                                        name,
                                        null,
                                        "biz_" + prefix + code,
                                        title,
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                relations,
                                List.of(),
                                details),
                        OWNER));
    }

    private static DataCenter.Relation reference(
            String code, String name, String target, String detail) {
        return new DataCenter.Relation(
                null, code, name, "REFERENCE", target, null, null, false, "RESTRICT", detail);
    }

    /** 建立并发布五个对象。 */
    ReportMultiSourceFixture objects() {
        property =
                object(
                        "property",
                        "物件",
                        "name",
                        List.of(field("name", "名称", "TEXT", 0)),
                        List.of(),
                        List.of());
        propertyName = id(property.fields(), "name");
        stay =
                object(
                        "stay",
                        "入住记录",
                        "name",
                        List.of(
                                field("name", "单号", "TEXT", 0),
                                field("check_in", "入住日", "DATE", 1),
                                field("check_out", "退房日", "DATE", 2),
                                field("cur_amount", "当月金额", "MONEY", 3),
                                field("next_amount", "次月金额", "MONEY", 4),
                                field("channel", "渠道", "SELECT", 5)),
                        Map.of(
                                "channel",
                                FieldRuleFixture.options(
                                        List.of(
                                                new DataCenter.Option("web", "网站", false),
                                                new DataCenter.Option("agent", "中介", false)))),
                        List.of(reference("property", "物件", property.objectId(), null)),
                        List.of());
        stayName = id(stay.fields(), "name");
        checkIn = id(stay.fields(), "check_in");
        checkOut = id(stay.fields(), "check_out");
        curAmount = id(stay.fields(), "cur_amount");
        nextAmount = id(stay.fields(), "next_amount");
        stayChannel = id(stay.fields(), "channel");
        stayPropertyRelation = relation(stay, "property").id();
        stayProperty = relation(stay, "property").fieldId();
        expense =
                object(
                        "expense",
                        "支出",
                        "name",
                        List.of(
                                field("name", "单号", "TEXT", 0),
                                field("paid_on", "支出日期", "DATE", 1),
                                field("amount", "金额", "MONEY", 2),
                                field("paid_at", "支付时间", "DATETIME", 3),
                                field("category", "类别", "SELECT", 4),
                                field("channel", "渠道", "SELECT", 5)),
                        Map.of(
                                "category",
                                        FieldRuleFixture.options(
                                                List.of(
                                                        new DataCenter.Option("clean", "清扫", false),
                                                        new DataCenter.Option(
                                                                "repair", "维修", false))),
                                "channel",
                                        FieldRuleFixture.options(
                                                List.of(
                                                        new DataCenter.Option("web", "网站", false),
                                                        new DataCenter.Option(
                                                                "agent", "中介", false)))),
                        List.of(reference("property", "物件", property.objectId(), null)),
                        List.of());
        expenseName = id(expense.fields(), "name");
        paidOn = id(expense.fields(), "paid_on");
        amount = id(expense.fields(), "amount");
        paidAt = id(expense.fields(), "paid_at");
        category = id(expense.fields(), "category");
        expenseChannel = id(expense.fields(), "channel");
        expensePropertyRelation = relation(expense, "property").id();
        expenseProperty = relation(expense, "property").fieldId();
        account =
                object(
                        "account",
                        "会计科目",
                        "name",
                        List.of(field("name", "科目名称", "TEXT", 0)),
                        List.of(),
                        List.of());
        accountName = id(account.fields(), "name");
        var entries =
                new DataCenter.Detail(
                        null,
                        "lines",
                        "分录",
                        "biz_" + support.prefix + "lines",
                        "ACTIVE",
                        List.of(
                                field("debit_amount", "借方金额", "MONEY", 0),
                                field("credit_amount", "贷方金额", "MONEY", 1)),
                        Map.of(),
                        List.of());
        voucher =
                object(
                        "voucher",
                        "会计凭证",
                        "memo",
                        List.of(field("memo", "摘要", "TEXT", 0), field("booked", "日期", "DATE", 1)),
                        List.of(
                                reference("debit", "借方科目", account.objectId(), "detail:lines"),
                                reference("credit", "贷方科目", account.objectId(), "detail:lines")),
                        List.of(entries));
        memo = id(voucher.fields(), "memo");
        booked = id(voucher.fields(), "booked");
        lines = voucher.details().getFirst();
        debitAmount = id(lines.fields(), "debit_amount");
        creditAmount = id(lines.fields(), "credit_amount");
        debit = relation(voucher, "debit").fieldId();
        credit = relation(voucher, "credit").fieldId();
        return this;
    }

    static String id(List<FieldDefinition> fields, String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow().id();
    }

    static DataCenter.Relation relation(DataCenter.Definition d, String code) {
        return d.relations().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- 应用与授权

    ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id,
                "多个数据来源 " + id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    List<DataCenter.Definition> referenced() {
        return List.of(stay, expense, property, voucher, account);
    }

    List<ApplicationCenter.ObjectReference> references(List<DataCenter.Definition> definitions) {
        List<ApplicationCenter.ObjectReference> refs = new ArrayList<>();
        for (var d : definitions) {
            var v = objects.getVersion(d.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            v.objectId(), v.versionNo(), v.checksum()));
        }
        return refs;
    }

    List<ApplicationCenter.ObjectReference> references() {
        return references(referenced());
    }

    /** 应用「民宿账务」显式引用五个对象，按夹具授予全部对象权限并发布。 */
    ReportMultiSourceFixture app(ApplicationCenter.Resource... resources) {
        return app(referenced(), resources);
    }

    ReportMultiSourceFixture app(
            List<DataCenter.Definition> definitions, ApplicationCenter.Resource... resources) {
        var saved =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                support.prefix + "app" + serial++,
                                "民宿账务",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        references(definitions), List.of(resources))),
                        OWNER);
        app = saved.application().id();
        grantApplicationObjects(app);
        apps.publish(new ApplicationCenter.Revision(app, 0, "多个数据来源"), OWNER);
        return this;
    }

    /** 把已发布应用的资源换成 resources（保存草稿并发布）。 */
    void republish(ApplicationCenter.Resource... resources) {
        ApplicationCenter.Detail head = apps.get(app);
        apps.save(
                new ApplicationCenter.Save(
                        app,
                        head.application().revision(),
                        head.application().code(),
                        head.application().name(),
                        null,
                        null,
                        new ApplicationCenter.Definition(
                                head.draft().objects(), List.of(resources))),
                OWNER);
        apps.publish(
                new ApplicationCenter.Revision(app, apps.get(app).application().revision(), "改统计"),
                OWNER);
    }

    /** 对象授予应用的上限（应用创建人按它取数）；permission 为空表示撤销。 */
    void ceiling(DataCenter.Definition d, ApplicationAuthorization.ObjectGrant permission) {
        var sharing =
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.sharing.ObjectSharingService
                                .class);
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(d.objectId()))
                        .findFirst()
                        .orElseThrow()
                        .revision();
        sharing.save(
                new ObjectSharing.Save(d.objectId(), app, revision, permission, "多个数据来源"), OWNER);
    }

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

    static Set<String> fieldIds(DataCenter.Definition d) {
        Set<String> ids = new LinkedHashSet<>();
        d.fields().forEach(f -> ids.add(f.id()));
        return ids;
    }

    /** 查看授权：scope 为 ALL 或 OWN，可读字段为对象全部字段；details 为可读明细。 */
    static ApplicationAuthorization.ObjectGrant read(
            DataCenter.Definition d, String scope, Set<String> details) {
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(), Set.of("READ"), scope, fieldIds(d), Set.of(), details, Set.of());
    }

    // ---------------------------------------------------------------- 数据

    private String save(DataCenter.Definition d, String name, Map<String, Object> data) {
        var row =
                records.save(
                                new ApplicationRecords.Save(
                                        app, d.objectId(), null, null, data, null),
                                OWNER)
                        .record();
        ids.put(name, row.id());
        return row.id();
    }

    private void property(String name, String label) {
        save(property, name, new LinkedHashMap<>(Map.of(propertyName, label)));
    }

    private void stay(
            String name, String p, String in, String out, int cur, int next, String channel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(stayChannel, channel);
        data.put(stayName, name);
        data.put(stayProperty, ids.get(p));
        data.put(checkIn, in);
        data.put(checkOut, out);
        data.put(curAmount, Integer.toString(cur));
        data.put(nextAmount, Integer.toString(next));
        save(stay, name, data);
    }

    private void expense(
            String name, String p, String date, int value, long creator, String channel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(expenseChannel, channel);
        data.put(expenseName, name);
        if (p != null) data.put(expenseProperty, ids.get(p));
        data.put(paidOn, date);
        data.put(amount, Integer.toString(value));
        save(expense, name, data);
        creators.put(name, creator);
    }

    private ApplicationRecords.Row line(
            String debitName, Integer dr, String creditName, Integer cr) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (debitName != null) data.put(debit, ids.get(debitName));
        if (dr != null) data.put(debitAmount, Integer.toString(dr));
        if (creditName != null) data.put(credit, ids.get(creditName));
        if (cr != null) data.put(creditAmount, Integer.toString(cr));
        return new ApplicationRecords.Row(null, null, data);
    }

    private void voucher(
            String name, String date, String firstLine, ApplicationRecords.Row... rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(memo, name);
        data.put(booked, date);
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
        ids.put(name, saved.record().id());
        var stored = saved.details().get(lines.id());
        int number = Integer.parseInt(firstLine.substring(1));
        for (int i = 0; i < stored.size(); i++) ids.put("L" + (number + i), stored.get(i).id());
    }

    /** 契约 14.1 的全部记录（另加「渠道」：S1/S3/S4、E1/E3 为网站，其余为中介，只用于筛选用例）；E2 的创建人是 U1（「支出只看本人」用），其余是应用创建人。 */
    ReportMultiSourceFixture seed() {
        property("P1", "青山");
        property("P2", "白川");
        property("P3", "青山");
        stay("S1", "P1", "2026-07-30", "2026-08-02", 20000, 10000, "web");
        stay("S2", "P1", "2026-08-05", "2026-08-07", 18000, 0, "agent");
        stay("S3", "P2", "2026-08-30", "2026-09-01", 15000, 0, "web");
        stay("S4", "P3", "2026-08-31", "2026-09-03", 8000, 16000, "web");
        stay("S5", "P2", "2026-09-10", "2026-09-12", 22000, 0, "agent");
        expense("E1", "P1", "2026-07-15", 5000, OWNER, "web");
        expense("E2", "P1", "2026-08-20", 7000, U1, "agent");
        expense("E3", "P2", "2026-09-05", 3000, OWNER, "web");
        expense("E4", null, "2026-08-01", 1000, OWNER, "agent");
        save(account, "A1", new LinkedHashMap<>(Map.of(accountName, "現金")));
        save(account, "A2", new LinkedHashMap<>(Map.of(accountName, "売上")));
        save(account, "A3", new LinkedHashMap<>(Map.of(accountName, "普通預金")));
        voucher("V1", "2026-08-01", "L1", line("A1", 1000, "A2", 1000), line("A3", 500, "A2", 500));
        voucher("V2", "2026-08-15", "L3", line("A2", 300, "A1", 300));
        voucher("V3", "2026-09-02", "L4", line("A1", 200, "A3", 200), line("A1", 50, null, null));
        creators.forEach(
                (name, user) ->
                        jdbc.update(
                                "UPDATE public.\""
                                        + expense.tableName()
                                        + "\" SET creator=? WHERE id=?",
                                Long.toString(user),
                                Long.parseLong(ids.get(name))));
        return this;
    }

    // ---------------------------------------------------------------- 统计配置（契约 14.5）

    static ApplicationReports.Dimension dim(String field, String path, String bucket) {
        return new ApplicationReports.Dimension(field, path, bucket);
    }

    static ApplicationReports.Metric metric(
            String id, String name, String op, String field, String source) {
        return new ApplicationReports.Metric(id, name, op, field, null, null, null, source);
    }

    static ApplicationReports.Metric formula(
            String id, String name, String op, String l, String r) {
        return new ApplicationReports.Metric(
                id, name, "FORMULA", null, null, new ApplicationReports.Formula(op, l, r), null);
    }

    static ApplicationReports.Config config(
            String objectId,
            String grain,
            String detailId,
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics,
            List<String> filters,
            String dateFieldId,
            String sortMetricId,
            boolean descending,
            String sortBy,
            Integer limit,
            String sourceName,
            List<ApplicationReports.Source> sources,
            List<String> rowLabels,
            List<String> columnLabels) {
        return new ApplicationReports.Config(
                objectId,
                rows,
                metrics,
                Map.of(),
                filters,
                dateFieldId,
                "Asia/Tokyo",
                display,
                sortMetricId,
                descending,
                limit,
                null,
                null,
                null,
                "PIVOT".equals(display) ? columns : null,
                null,
                null,
                sortBy,
                grain,
                detailId,
                sourceName,
                sources,
                rowLabels,
                columnLabels);
    }

    /** 例 2 的「次月」来源；byName = 行维度用「物件 / 名称」对齐（同一字段）。 */
    ApplicationReports.Source nextSource(boolean byName, String display) {
        return new ApplicationReports.Source(
                "next",
                "次月",
                stay.objectId(),
                null,
                null,
                List.of(propertyDim(stay, byName)),
                "PIVOT".equals(display) ? List.of(dim(checkOut, null, "MONTH")) : null,
                null,
                null,
                null);
    }

    ApplicationReports.Source expenseSource(String display) {
        return new ApplicationReports.Source(
                "expense",
                "支出",
                expense.objectId(),
                null,
                null,
                List.of(dim(expenseProperty, null, "VALUE")),
                "PIVOT".equals(display) ? List.of(dim(paidOn, null, "MONTH")) : null,
                null,
                null,
                null);
    }

    ApplicationReports.Dimension propertyDim(DataCenter.Definition d, boolean byName) {
        if (d == stay)
            return byName
                    ? dim(propertyName, stayPropertyRelation, "VALUE")
                    : dim(stayProperty, null, "VALUE");
        return byName
                ? dim(propertyName, expensePropertyRelation, "VALUE")
                : dim(expenseProperty, null, "VALUE");
    }

    /** 例 2（契约 14.2）：当月 / 次月 / 月收益。 */
    ApplicationReports.Config example2(boolean byName) {
        return config(
                stay.objectId(),
                null,
                null,
                "PIVOT",
                List.of(propertyDim(stay, byName)),
                List.of(dim(checkIn, null, "MONTH")),
                List.of(
                        metric("cur", "当月金额", "SUM", curAmount, null),
                        metric("nxt", "次月金额", "SUM", nextAmount, "next"),
                        formula("rev", "月收益", "ADD", "cur", "nxt")),
                List.of(),
                null,
                null,
                false,
                null,
                null,
                "当月",
                List.of(nextSource(byName, "PIVOT")),
                List.of("物件"),
                List.of("月份"));
    }

    /** 例 1（契约 14.3 / 14.5）：当月 / 次月 / 支出 / 收入 / 利润，行按利润降序。 */
    ApplicationReports.Config example1(String display, Integer limit) {
        return config(
                stay.objectId(),
                null,
                null,
                display,
                List.of(propertyDim(stay, false)),
                List.of(dim(checkIn, null, "MONTH")),
                List.of(
                        metric("cur", "当月金额", "SUM", curAmount, null),
                        metric("nxt", "次月金额", "SUM", nextAmount, "next"),
                        metric("exp", "支出", "SUM", amount, "expense"),
                        formula("inc", "收入", "ADD", "cur", "nxt"),
                        formula("pro", "利润", "SUBTRACT", "inc", "exp")),
                List.of(),
                null,
                "pro",
                true,
                "METRIC",
                limit,
                "当月",
                List.of(nextSource(false, display), expenseSource(display)),
                List.of("物件"),
                "PIVOT".equals(display) ? List.of("月份") : null);
    }

    ApplicationReports.Config example1() {
        return example1("PIVOT", null);
    }

    /** 例 3（契约 14.4）：借方 / 贷方 / 余额，两个来源都是明细粒度·分录。 */
    ApplicationReports.Config example3() {
        return config(
                voucher.objectId(),
                "DETAIL",
                lines.id(),
                "PIVOT",
                List.of(dim(debit, null, "VALUE")),
                List.of(dim(booked, null, "MONTH")),
                List.of(
                        metric("dr", "借方合计", "SUM", debitAmount, null),
                        metric("cr", "贷方合计", "SUM", creditAmount, "credit"),
                        formula("bal", "余额", "SUBTRACT", "dr", "cr")),
                List.of(),
                null,
                null,
                false,
                null,
                null,
                "借方",
                List.of(
                        new ApplicationReports.Source(
                                "credit",
                                "贷方",
                                voucher.objectId(),
                                "DETAIL",
                                lines.id(),
                                List.of(dim(credit, null, "VALUE")),
                                List.of(dim(booked, null, "MONTH")),
                                null,
                                null,
                                null)),
                List.of("科目"),
                null);
    }

    /** 在同一份配置上替换顶层可筛选字段、日期范围字段与附加来源。 */
    static ApplicationReports.Config with(
            ApplicationReports.Config c,
            List<String> filters,
            String dateFieldId,
            List<ApplicationReports.Source> sources) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                filters,
                dateFieldId,
                c.timeZone(),
                c.display(),
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                c.detailViewId(),
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                c.detailEditable(),
                c.sortBy(),
                c.grain(),
                c.detailId(),
                c.sourceName(),
                sources,
                c.dimensionLabels(),
                c.columnDimensionLabels());
    }

    /** 附加来源换几个分量（其余原样）。 */
    static ApplicationReports.Source source(
            ApplicationReports.Source s,
            String dateFieldId,
            Map<String, String> filterTargets,
            String detailViewId,
            Boolean detailEditable) {
        return new ApplicationReports.Source(
                s.id(),
                s.name(),
                s.objectId(),
                s.grain(),
                s.detailId(),
                s.dimensions(),
                s.columnDimensions(),
                s.conditions(),
                dateFieldId,
                filterTargets,
                detailViewId,
                detailEditable);
    }

    static DynamicConditionDTO condition(String field, String operator, Object value) {
        var item = new DynamicConditionDTO.Item();
        item.setType("condition");
        item.setField(field);
        item.setOperator(operator);
        item.setValue(value);
        var tree = new DynamicConditionDTO();
        tree.setLogic(DynamicConditionDTO.Logic.AND);
        tree.setItems(new ArrayList<>(List.of(item)));
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
            String report,
            List<String> group,
            List<String> columnGroup,
            String metric,
            String source) {
        return new ApplicationReports.Query(
                app,
                report,
                null,
                null,
                null,
                null,
                group,
                1,
                100,
                null,
                metric,
                columnGroup,
                null,
                source);
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

    /** 一格的若干指标逐个比对（一次报全）：expected 依次对应 metrics，null = 空（不是 0）。 */
    static void softCell(
            org.assertj.core.api.SoftAssertions soft,
            Map<String, String> values,
            String where,
            List<String> metrics,
            Integer... expected) {
        for (int i = 0; i < metrics.size(); i++) {
            String actual = values.get(metrics.get(i));
            if (expected[i] == null)
                soft.assertThat(actual).as(metrics.get(i) + " " + where + " 应为空").isNull();
            else
                soft.assertThat(actual == null ? null : new BigDecimal(actual))
                        .as(metrics.get(i) + " " + where)
                        .isNotNull()
                        .isEqualByComparingTo(Integer.toString(expected[i]));
        }
    }

    String key(String name) {
        return ids.get(name);
    }
}
