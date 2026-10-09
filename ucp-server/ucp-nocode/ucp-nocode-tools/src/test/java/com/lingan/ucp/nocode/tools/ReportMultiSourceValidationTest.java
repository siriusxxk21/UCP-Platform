package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationReportValidator;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 多个数据来源的保存校验文案（契约第 6 章逐字 + 契约变更 C2）：L1–L16、L23 各一例，L8 的 R1–R6 各一例，附加来源复用现有文案时带前缀，L18 在页面保存时。
 * 每例整体回滚。
 */
class ReportMultiSourceValidationTest {
    private ReportMultiSourceFixture f;
    private ApplicationReportValidator validator;
    private Map<String, DataCenter.Definition> definitions;

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
        f = new ReportMultiSourceFixture();
        validator = servicesContext.getBean(ApplicationReportValidator.class);
    }

    @AfterEach
    void cleanup() {
        f.support.clean();
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                f.objects();
                                definitions = new LinkedHashMap<>();
                                for (DataCenter.Definition d : f.referenced())
                                    definitions.put(d.objectId(), d);
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private void rejects(ApplicationReports.Config c, String message) {
        assertThatThrownBy(() -> validator.normalize(c, definitions))
                .as(message)
                .hasMessage(message);
    }

    private ApplicationReports.Config next(
            ApplicationReports.Config c, UnaryOperator<ApplicationReports.Source> change) {
        List<ApplicationReports.Source> sources = new ArrayList<>(c.extraSources());
        sources.set(0, change.apply(sources.get(0)));
        return with(c, c.filterFieldIds(), c.dateFieldId(), sources);
    }

    private ApplicationReports.Config expense(
            ApplicationReports.Config c, UnaryOperator<ApplicationReports.Source> change) {
        List<ApplicationReports.Source> sources = new ArrayList<>(c.extraSources());
        sources.set(1, change.apply(sources.get(1)));
        return with(c, c.filterFieldIds(), c.dateFieldId(), sources);
    }

    private static ApplicationReports.Source dims(
            ApplicationReports.Source s,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns) {
        return new ApplicationReports.Source(
                s.id(),
                s.name(),
                s.objectId(),
                s.grain(),
                s.detailId(),
                rows,
                columns,
                s.conditions(),
                s.dateFieldId(),
                s.filterTargets(),
                s.detailViewId(),
                s.detailEditable());
    }

    private static ApplicationReports.Source named(
            ApplicationReports.Source s, String id, String name) {
        return new ApplicationReports.Source(
                id,
                name,
                s.objectId(),
                s.grain(),
                s.detailId(),
                s.dimensions(),
                s.columnDimensions(),
                s.conditions(),
                s.dateFieldId(),
                s.filterTargets(),
                s.detailViewId(),
                s.detailEditable());
    }

    private static ApplicationReports.Config display(ApplicationReports.Config c, String display) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
                c.timeZone(),
                display,
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
                c.extraSources(),
                c.dimensionLabels(),
                c.columnDimensionLabels());
    }

    private static ApplicationReports.Config names(
            ApplicationReports.Config c, String sourceName, List<String> rowLabels) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
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
                sourceName,
                c.extraSources(),
                rowLabels,
                c.columnDimensionLabels());
    }

    /** 例 1 是能保存的；保存后的规范形态带四个新分量，指标带 sourceId。 */
    @Test
    void example1IsValid() {
        rollback(
                () -> {
                    ApplicationReports.Config n = validator.normalize(f.example1(), definitions);
                    assertThat(n.extraSources()).hasSize(2);
                    assertThat(n.metrics())
                            .extracting(ApplicationReports.Metric::sourceId)
                            .containsExactly(null, "next", "expense", null, null);
                    assertThat(n.metrics().get(2).format().financial())
                            .as("支出金额按支出对象判财务格式")
                            .isTrue();
                    assertThat(n.pivot()).isNotNull();
                    assertThat(
                                    validator
                                            .normalize(f.example3(), definitions)
                                            .extraSources()
                                            .getFirst()
                                            .grain())
                            .isEqualTo("DETAIL");
                });
    }

    @Test
    void structureMessages() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    rejects(display(c, "BAR"), "多个数据来源目前只用于透视表和汇总表");
                    List<ApplicationReports.Source> four = new ArrayList<>(c.extraSources());
                    four.add(named(c.extraSources().get(0), "n2", "次月2"));
                    four.add(named(c.extraSources().get(0), "n3", "次月3"));
                    rejects(with(c, c.filterFieldIds(), null, four), "数据来源最多 4 个");
                    rejects(next(c, s -> named(s, "main", "次月")), "数据来源编码无效或重复");
                    rejects(next(c, s -> named(s, "Next", "次月")), "数据来源编码无效或重复");
                    rejects(next(c, s -> named(s, "expense", "次月")), "数据来源编码无效或重复");
                    rejects(next(c, s -> named(s, "next", " ")), "请填写数据来源名称（最多 30 字）");
                    rejects(names(c, "x".repeat(31), c.dimensionLabels()), "请填写数据来源名称（最多 30 字）");
                    rejects(names(c, "当月", List.of("物件", "多余")), "维度显示名应与维度一一对应，每个最多 30 字");
                    rejects(names(c, "当月", List.of("y".repeat(31))), "维度显示名应与维度一一对应，每个最多 30 字");
                    rejects(
                            next(
                                    c,
                                    s ->
                                            dims(
                                                    s,
                                                    List.of(
                                                            s.dimensions().get(0),
                                                            dim(f.checkIn, null, "VALUE")),
                                                    s.columnDimensions())),
                            "来源「次月」要为每个行维度、列维度各指定一个对应字段");
                    rejects(
                            next(c, s -> dims(s, s.dimensions(), null)),
                            "来源「次月」要为每个行维度、列维度各指定一个对应字段");
                });
    }

    @Test
    void metricMessages() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    List<ApplicationReports.Metric> m = new ArrayList<>(c.metrics());
                    m.set(2, metric("exp", "支出", "SUM", f.amount, "nope"));
                    rejects(c.withMetrics(m), "指标「支出」的数据来源不存在");
                    m = new ArrayList<>(c.metrics());
                    m.set(
                            3,
                            new ApplicationReports.Metric(
                                    "inc",
                                    "收入",
                                    "FORMULA",
                                    null,
                                    null,
                                    new ApplicationReports.Formula("ADD", "cur", "nxt"),
                                    null,
                                    "next"));
                    rejects(c.withMetrics(m), "计算指标不属于某个来源，不用选择数据来源");
                    m = new ArrayList<>(c.metrics());
                    m.remove(2);
                    m.set(3, formula("pro", "利润", "SUBTRACT", "inc", "cur"));
                    rejects(c.withMetrics(m), "来源「支出」还没有指标，请为它加一个指标或删除这个来源");
                    m = new ArrayList<>(c.metrics());
                    for (int i = 0; i < 6; i++)
                        m.add(formula("x" + i, "额外" + i, "ADD", "cur", "nxt"));
                    rejects(c.withMetrics(m), "多个来源的统计最多 10 个指标");
                    // 10 个可以；单来源仍是 5 个（原文案）。
                    m = new ArrayList<>(c.metrics());
                    for (int i = 0; i < 5; i++)
                        m.add(formula("x" + i, "额外" + i, "ADD", "cur", "nxt"));
                    assertThat(validator.normalize(c.withMetrics(m), definitions).metrics())
                            .hasSize(10);
                    // 单来源却带了来源。
                    ApplicationReports.Config single =
                            with(f.example2(false), List.of(), null, null);
                    rejects(names(single, null, null), "指标「次月金额」的数据来源不存在");
                    rejects(
                            names(with(single, List.of(), null, List.of()), "当月", null),
                            "只有一个数据来源时不用填写来源名称");
                });
    }

    @Test
    void alignmentMessages() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    rejects(
                            next(
                                    c,
                                    s ->
                                            dims(
                                                    s,
                                                    s.dimensions(),
                                                    List.of(dim(f.checkOut, null, "DAY")))),
                            "来源「次月」的「退房日」要与「月份」用同一种分组方式（按月）");
                    rejects(
                            next(
                                    c,
                                    s ->
                                            dims(
                                                    s,
                                                    List.of(dim(f.stayName, null, "VALUE")),
                                                    s.columnDimensions())),
                            "来源「次月」的「单号」不能与「物件」对齐：一个是引用其它对象的字段，另一个不是");
                    // R2：引用指向不同对象（物件 / 会计科目）。
                    ApplicationReports.Source debit =
                            new ApplicationReports.Source(
                                    "lines",
                                    "分录",
                                    f.voucher.objectId(),
                                    "DETAIL",
                                    f.lines.id(),
                                    List.of(dim(f.debit, null, "VALUE")),
                                    List.of(dim(f.booked, null, "MONTH")),
                                    null,
                                    null,
                                    null);
                    List<ApplicationReports.Metric> m = new ArrayList<>(c.metrics());
                    m.add(metric("dr", "借方", "SUM", f.debitAmount, "lines"));
                    List<ApplicationReports.Source> withDebit = new ArrayList<>(c.extraSources());
                    withDebit.add(debit);
                    rejects(
                            with(c.withMetrics(m), List.of(), null, withDebit),
                            "来源「分录」的「借方科目」不能与「物件」对齐：两者引用的不是同一个对象（「物件」与「会计科目」）");
                    // R3：按值对齐的日期，一边含时间。
                    ApplicationReports.Config byDate =
                            config(
                                    f.stay.objectId(),
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(dim(f.checkIn, null, "VALUE")),
                                    List.of(),
                                    List.of(
                                            metric("cur", "当月金额", "SUM", f.curAmount, null),
                                            metric("exp", "支出", "SUM", f.amount, "expense")),
                                    List.of(),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    "入住",
                                    List.of(
                                            new ApplicationReports.Source(
                                                    "expense",
                                                    "支出",
                                                    f.expense.objectId(),
                                                    null,
                                                    null,
                                                    List.of(dim(f.paidAt, null, "VALUE")),
                                                    null,
                                                    null,
                                                    null,
                                                    null)),
                                    null,
                                    null);
                    rejects(
                            byDate,
                            "来源「支出」的「支付时间」不能与「入住日」对齐：日期按值对齐时两边都须是日期字段（不含时间）；含时间的请按日、按月或按年分组");
                    // 同样两个字段按日分组就能对齐（DATE 与 DATETIME 按日）。
                    ApplicationReports.Config byDay =
                            with(
                                    new ApplicationReports.Config(
                                            byDate.objectId(),
                                            List.of(dim(f.checkIn, null, "DAY")),
                                            byDate.metrics(),
                                            Map.of(),
                                            List.of(),
                                            null,
                                            "Asia/Tokyo",
                                            "PIVOT",
                                            null,
                                            false,
                                            null,
                                            null,
                                            null,
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            "入住",
                                            null,
                                            null,
                                            null),
                                    List.of(),
                                    null,
                                    List.of(
                                            new ApplicationReports.Source(
                                                    "expense",
                                                    "支出",
                                                    f.expense.objectId(),
                                                    null,
                                                    null,
                                                    List.of(dim(f.paidAt, null, "DAY")),
                                                    null,
                                                    null,
                                                    null,
                                                    null)));
                    assertThat(validator.normalize(byDay, definitions).extraSources()).hasSize(1);
                    // R4：不是同一套选项（渠道 / 类别）；只有一边有选项也是 R4；同一套选项（两个对象各自的「渠道」）能对齐。
                    rejects(
                            rowAligned(f.stayChannel, f.category),
                            "来源「支出」的「类别」不能与「渠道」对齐：两者不是同一套选项");
                    rejects(
                            rowAligned(f.stayChannel, f.expenseName),
                            "来源「支出」的「单号」不能与「渠道」对齐：两者不是同一套选项");
                    assertThat(
                                    validator
                                            .normalize(
                                                    rowAligned(f.stayChannel, f.expenseChannel),
                                                    definitions)
                                            .extraSources())
                            .hasSize(1);
                    // R5：金额与另一个金额字段。
                    rejects(
                            rowAligned(f.curAmount, f.amount),
                            "来源「支出」的「金额」不能与「当月金额」对齐：小数、金额、百分比字段只能与同一个字段对齐");
                    // R6：文本与日期。
                    rejects(rowAligned(f.stayName, f.paidOn), "来源「支出」的「支出日期」不能与「单号」对齐：字段类型不同");
                    // 同类原值：两个文本字段能对齐。
                    assertThat(
                                    validator
                                            .normalize(
                                                    rowAligned(f.stayName, f.expenseName),
                                                    definitions)
                                            .extraSources())
                            .hasSize(1);
                    // 关系路径维度名：「物件 / 名称」。
                    rejects(
                            config(
                                    f.stay.objectId(),
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(dim(f.propertyName, f.stayPropertyRelation, "VALUE")),
                                    List.of(),
                                    List.of(
                                            metric("cur", "当月金额", "SUM", f.curAmount, null),
                                            metric("exp", "支出", "SUM", f.amount, "expense")),
                                    List.of(),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    "入住",
                                    List.of(
                                            new ApplicationReports.Source(
                                                    "expense",
                                                    "支出",
                                                    f.expense.objectId(),
                                                    null,
                                                    null,
                                                    List.of(dim(f.paidOn, null, "VALUE")),
                                                    null,
                                                    null,
                                                    null,
                                                    null)),
                                    null,
                                    null),
                            "来源「支出」的「支出日期」不能与「物件 / 名称」对齐：字段类型不同");
                });
    }

    /** 行维度各一个：来源 1 用 mainField（入住记录），来源「支出」用 otherField。 */
    private ApplicationReports.Config rowAligned(String mainField, String otherField) {
        return config(
                f.stay.objectId(),
                null,
                null,
                "PIVOT",
                List.of(dim(mainField, null, "VALUE")),
                List.of(),
                List.of(
                        metric("cur", "当月金额", "SUM", f.curAmount, null),
                        metric("exp", "支出", "SUM", f.amount, "expense")),
                List.of(),
                null,
                null,
                false,
                null,
                null,
                "入住",
                List.of(
                        new ApplicationReports.Source(
                                "expense",
                                "支出",
                                f.expense.objectId(),
                                null,
                                null,
                                List.of(dim(otherField, null, "VALUE")),
                                null,
                                null,
                                null,
                                null)),
                null,
                null);
    }

    @Test
    void dateAndFilterMessages() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    rejects(
                            with(c, List.of(), f.checkIn, c.extraSources()),
                            "统计开放了日期范围，请为来源「次月」指定日期范围字段");
                    rejects(
                            next(c, s -> source(s, f.checkOut, null, null, null)),
                            "统计没有开放日期范围，来源「次月」不用指定日期范围字段");
                    // 日期范围字段按本来源校验（复用原文案、加前缀）。
                    rejects(
                            with(
                                    c,
                                    List.of(),
                                    f.checkIn,
                                    List.of(
                                            source(
                                                    c.extraSources().get(0),
                                                    f.checkOut,
                                                    null,
                                                    null,
                                                    null),
                                            source(
                                                    c.extraSources().get(1),
                                                    f.amount,
                                                    null,
                                                    null,
                                                    null))),
                            "来源「支出」：日期范围需要日期字段");
                    rejects(
                            with(c, List.of(f.stayName), null, c.extraSources()),
                            "用户可筛选字段「单号」在来源「支出」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉");
                    rejects(
                            expense(
                                    c,
                                    s ->
                                            source(
                                                    s,
                                                    null,
                                                    Map.of(f.stayName, f.expenseName),
                                                    null,
                                                    null)),
                            "来源「支出」的筛选对应用到了没有开放的筛选字段");
                    rejects(
                            with(
                                    c,
                                    List.of(f.stayName),
                                    null,
                                    List.of(
                                            c.extraSources().get(0),
                                            source(
                                                    c.extraSources().get(1),
                                                    null,
                                                    Map.of(f.stayName, f.paidOn),
                                                    null,
                                                    null))),
                            "来源「支出」里与「单号」对应的「支出日期」不能用于同一个筛选：字段类型不同");
                    // 显式指定相容的筛选对应就能保存；同对象同粒度的「次月」不用指定（同一个键）。
                    ApplicationReports.Config ok =
                            with(
                                    c,
                                    List.of(f.stayName),
                                    null,
                                    List.of(
                                            c.extraSources().get(0),
                                            source(
                                                    c.extraSources().get(1),
                                                    null,
                                                    Map.of(f.stayName, f.expenseName),
                                                    null,
                                                    null)));
                    assertThat(
                                    validator
                                            .normalize(ok, definitions)
                                            .extraSources()
                                            .get(1)
                                            .filterTargets())
                            .containsEntry(f.stayName, f.expenseName);
                    // 维度键自动映射：「物件」筛选映射到各来源的物件。
                    assertThat(
                                    validator
                                            .normalize(
                                                    with(
                                                            c,
                                                            List.of(f.stayProperty),
                                                            null,
                                                            c.extraSources()),
                                                    definitions)
                                            .filterFieldIds())
                            .containsExactly(f.stayProperty);
                });
    }

    /** 附加来源上复用现有文案：加「来源「…」：」前缀；来源 1 原文。 */
    @Test
    void reusedMessagesArePrefixed() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    rejects(
                            expense(
                                    c,
                                    s ->
                                            dims(
                                                    s,
                                                    List.of(dim("missing_field", null, "VALUE")),
                                                    s.columnDimensions())),
                            "来源「支出」：统计字段不存在或已停用");
                    rejects(
                            c.withMetrics(
                                    List.of(
                                            metric("cur", "当月金额", "SUM", "missing_field", null),
                                            metric("nxt", "次月金额", "SUM", f.nextAmount, "next"),
                                            metric("exp", "支出", "SUM", f.amount, "expense"),
                                            formula("pro", "利润", "SUBTRACT", "cur", "exp"))),
                            "统计字段不存在或已停用");
                    rejects(
                            c.withMetrics(
                                    List.of(
                                            metric("cur", "当月金额", "SUM", f.curAmount, null),
                                            metric("nxt", "次月金额", "SUM", f.stayName, "next"),
                                            metric("exp", "支出", "SUM", f.amount, "expense"),
                                            formula("pro", "利润", "SUBTRACT", "cur", "exp"))),
                            "来源「次月」：求和、平均、极值需要业务数值字段，不能使用对象引用");
                    // 按明细行统计的来源：主表字段不能求和（laneT M6 原文加前缀）。
                    ApplicationReports.Config ex3 = f.example3();
                    rejects(
                            ex3.withMetrics(
                                    List.of(
                                            metric("dr", "借方合计", "SUM", f.debitAmount, null),
                                            metric("cr", "贷方合计", "COUNT_ROOT", null, "credit"),
                                            metric(
                                                    "x",
                                                    "日期计数",
                                                    "COUNT_FIELD",
                                                    f.booked,
                                                    "credit"))),
                            "来源「贷方」：按明细行统计时，主表字段「日期」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「分录」里的字段，或把「统计粒度」改回「按主记录」");
                    // 来源对象不在应用引用里（隐式只读或根本没引用）：原句加前缀。
                    definitions.remove(f.expense.objectId());
                    rejects(c, "来源「支出」：统计对象未被应用引用");
                });
    }

    /** 契约变更 C2：每个来源各自的下钻明细视图与允许编辑，规则与单来源相同、附加来源加前缀；L17 不再抛。 */
    @Test
    void perSourceDetailViewRules() {
        rollback(
                () -> {
                    ApplicationReports.Config c = f.example1();
                    rejects(
                            expense(c, s -> source(s, null, null, null, true)),
                            "来源「支出」：明细允许编辑需要先选择下钻明细视图");
                    ApplicationReports.Config ex3 = f.example3();
                    List<ApplicationReports.Source> credit = new ArrayList<>(ex3.extraSources());
                    credit.set(0, source(credit.get(0), null, null, "lines_view", null));
                    // R6 衔接（laneDV 放开明细粒度挂下钻视图）：按明细行统计的来源同样能挂视图，不再报 M8；
                    // 视图须按同一明细逐行显示，在资源保存时判（ReportMultiSourceDetailDrillViewTest）。
                    assertThat(
                                    validator
                                            .normalize(
                                                    with(ex3, List.of(), null, credit), definitions)
                                            .extraSources()
                                            .getFirst()
                                            .detailViewId())
                            .isEqualTo("lines_view");
                    ApplicationReports.Config withView =
                            expense(c, s -> source(s, null, null, "expense_view", true));
                    ApplicationReports.Config n = validator.normalize(withView, definitions);
                    assertThat(n.extraSources().get(1).detailViewId()).isEqualTo("expense_view");
                    assertThat(n.extraSources().get(1).detailEditable()).isTrue();
                    // 保存时视图须绑定该来源的对象。
                    ApplicationUi.View stayView = view(f.stay);
                    ApplicationUi.View expenseView = view(f.expense);
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    f.resource(
                                                            "report",
                                                            "REPORT",
                                                            expense(
                                                                    c,
                                                                    s ->
                                                                            source(
                                                                                    s,
                                                                                    null,
                                                                                    null,
                                                                                    "stay_view",
                                                                                    null))),
                                                    f.resource("stay_view", "VIEW", stayView)))
                            .hasMessage("来源「支出」：统计明细视图必须绑定同一对象");
                    f.app(
                            f.resource("report", "REPORT", withView),
                            f.resource("expense_view", "VIEW", expenseView));
                });
    }

    static ApplicationUi.View view(DataCenter.Definition d) {
        return new ApplicationUi.View(
                d.objectId(),
                d.fields().stream().map(FieldDefinition::id).toList(),
                Map.of(),
                null,
                false,
                20,
                null);
    }

    /** L18：多来源统计放进记录详情页（节点带上下文绑定）⇒ 页面保存时拦下。 */
    @Test
    void multiSourceReportCannotSitOnRecordPage() {
        rollback(
                () -> {
                    ApplicationUi.Page page =
                            mapper.convertValue(
                                    Map.of(
                                            "contextObjectId", f.property.objectId(),
                                            "nodes",
                                                    List.of(
                                                            Map.of(
                                                                    "id",
                                                                    "n1",
                                                                    "type",
                                                                    "REPORT",
                                                                    "resourceId",
                                                                    "report",
                                                                    "binding",
                                                                    Map.of(
                                                                            "relationId",
                                                                            f.stayPropertyRelation,
                                                                            "direction",
                                                                            "INCOMING")))),
                                    ApplicationUi.Page.class);
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    f.resource("report", "REPORT", f.example1()),
                                                    f.resource("detail_page", "PAGE", page)))
                            .hasMessage("多个来源的统计暂不能放在记录详情页里");
                });
    }
}
