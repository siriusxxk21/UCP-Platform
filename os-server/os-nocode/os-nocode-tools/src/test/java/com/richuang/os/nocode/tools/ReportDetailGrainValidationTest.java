package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportDetailGrainFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.resource.ApplicationReportValidator;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 按明细行统计：保存 / 发布 / 预览时的校验与文案。期望的句子在这里逐字写出，不引用产品代码里的常量——文案是前后端共用的契约。
 *
 * <p>「字段在明细里、当前粒度用不了」的几句都不出现「未授权」三个字：字段其实授权了，搭建的人照着去改权限是改不通的。
 */
class ReportDetailGrainValidationTest {
    private static final String M1_CREDIT =
            "「贷方科目」是明细「分录」里的字段，当前统计按主记录汇总，不能用它分组、筛选或计算。请把「统计粒度」改为「按明细行 · 分录」";
    private static final String M1_CREDIT_AMOUNT =
            "「贷方金额」是明细「分录」里的字段，当前统计按主记录汇总，不能用它分组、筛选或计算。请把「统计粒度」改为「按明细行 · 分录」";
    private static final String M2_NOTE_AMOUNT = "「附注金额」属于明细「附注」，当前统计按明细「分录」的行汇总，一张统计只能用一个明细里的字段";
    private static final String M2_REMARK = "「备注」属于明细「附注」，当前统计按明细「分录」的行汇总，一张统计只能用一个明细里的字段";
    private static final String M6_INCOME =
            "按明细行统计时，主表字段「入金」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「分录」里的字段，或把「统计粒度」改回「按主记录」";
    private static final String M7 = "按明细行统计时，日期范围字段仍须是主表的日期字段";
    private static final String M9 = "「主记录数」只用于按明细行统计；按主记录统计时请用「记录计数」";
    private static final String M10 = "「主记录数」无需指定字段";

    private ReportDetailGrainFixture f;
    private ApplicationReportValidator validator;

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
        f = new ReportDetailGrainFixture();
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
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private Map<String, DataCenter.Definition> definitions() {
        return Map.of(f.voucher.objectId(), f.voucher, f.account.objectId(), f.account);
    }

    /** 整句相等，且不含「未授权」。 */
    private void assertRejected(ApplicationReports.Config config, String message) {
        assertThatThrownBy(() -> validator.normalize(config, definitions()))
                .as(message)
                .isInstanceOf(com.richuang.os.framework.common.exception.ServiceException.class)
                .hasMessage(message)
                .hasMessageNotContaining("未授权");
    }

    /** 主记录粒度的一张汇总表：行 = 日期按月，指标 = 入金合计、记录计数。 */
    private ApplicationReports.Config root(
            List<ApplicationReports.Dimension> rows, List<ApplicationReports.Metric> metrics) {
        return f.config(null, null, "TABLE", rows, List.of(), metrics);
    }

    private List<ApplicationReports.Metric> rootMetrics() {
        return List.of(
                new ApplicationReports.Metric("income", "入金合计", "SUM", f.income),
                new ApplicationReports.Metric("count", "张数", "COUNT", null));
    }

    private List<ApplicationReports.Dimension> month() {
        return List.of(f.main(f.booked, "MONTH"));
    }

    private static ApplicationReports.Config dated(
            ApplicationReports.Config c, String dateFieldId) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
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
                c.detailId());
    }

    private static ApplicationReports.Config drillView(
            ApplicationReports.Config c, String detailViewId, Boolean editable) {
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
                detailViewId,
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                editable,
                c.sortBy(),
                c.grain(),
                c.detailId());
    }

    private ApplicationReports.Metric metric(String operation, String fieldId) {
        return new ApplicationReports.Metric("m", "指标", operation, fieldId);
    }

    private ApplicationReports.Metric conditional(DynamicConditionDTO conditions) {
        return new ApplicationReports.Metric("m", "指标", "COUNT", null, conditions, null, null);
    }

    /** 业务方遇到的那一张：主记录粒度 + 维度「贷方科目 / 科目名称」。保存与预览都报粒度那一句，不是「未授权」。 */
    @Test
    void rootGrainRejectsDetailRelationPathWithGrainMessage() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var config =
                            f.config(
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(f.creditName()),
                                    List.of(f.main(f.booked, "MONTH")),
                                    rootMetrics());
                    assertRejected(config, M1_CREDIT);
                    assertThatThrownBy(() -> f.preview(config))
                            .hasMessage(M1_CREDIT)
                            .hasMessageNotContaining("未授权");
                    // 显式写 ROOT 同样；路径写在条件、筛选里也一样。
                    assertRejected(
                            f.config(
                                    "ROOT",
                                    null,
                                    "TABLE",
                                    List.of(f.creditName()),
                                    List.of(),
                                    rootMetrics()),
                            M1_CREDIT);
                    String key = f.creditRelation + ":" + f.accountName;
                    assertRejected(
                            with(root(month(), rootMetrics()), Map.of(), List.of(key), null),
                            M1_CREDIT);
                    assertRejected(
                            with(
                                    root(month(), rootMetrics()),
                                    Map.of(),
                                    List.of(),
                                    condition(key, "eq", "销售收入")),
                            M1_CREDIT);
                });
    }

    /** 明细字段 ID 直接出现在主记录粒度统计的任何位置：都是粒度那一句。 */
    @Test
    void rootGrainRejectsDetailFieldIdsEverywhere() {
        rollback(
                () -> {
                    f.objects().app();
                    var base = root(month(), rootMetrics());
                    // 维度
                    assertRejected(
                            root(List.of(f.main(f.creditAmount, "VALUE")), rootMetrics()),
                            M1_CREDIT_AMOUNT);
                    assertRejected(
                            root(List.of(f.main(f.credit, "VALUE")), rootMetrics()), M1_CREDIT);
                    // 指标字段：数值类、计数类、引用字段
                    for (String operation :
                            List.of("SUM", "AVG", "MIN", "MAX", "COUNT_FIELD", "COUNT_DISTINCT"))
                        assertRejected(
                                root(month(), List.of(metric(operation, f.creditAmount))),
                                M1_CREDIT_AMOUNT);
                    assertRejected(root(month(), List.of(metric("SUM", f.credit))), M1_CREDIT);
                    assertRejected(
                            root(month(), List.of(metric("COUNT_DISTINCT", f.credit))), M1_CREDIT);
                    // 固定等值、用户筛选
                    assertRejected(
                            with(base, Map.of(f.creditAmount, "300"), List.of(), null),
                            M1_CREDIT_AMOUNT);
                    assertRejected(
                            with(base, Map.of(), List.of(f.creditAmount), null), M1_CREDIT_AMOUNT);
                    // 固定条件、指标条件
                    assertRejected(
                            with(base, Map.of(), List.of(), condition(f.creditAmount, "gt", "300")),
                            M1_CREDIT_AMOUNT);
                    assertRejected(
                            root(
                                    month(),
                                    List.of(conditional(condition(f.creditAmount, "gt", "300")))),
                            M1_CREDIT_AMOUNT);
                    // 日期范围字段
                    assertRejected(dated(base, f.creditAmount), M1_CREDIT_AMOUNT);
                    // 真不存在的字段仍是原句。
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    root(
                                                            List.of(f.main("missing", "VALUE")),
                                                            rootMetrics()),
                                                    definitions()))
                            .hasMessage("统计字段不存在或已停用");
                });
    }

    /** 明细粒度 = 分录时用了「附注」的字段。 */
    @Test
    void detailGrainRejectsFieldsOfAnotherDetail() {
        rollback(
                () -> {
                    f.objects().app();
                    var base = f.creditPivot();
                    assertRejected(
                            f.detailConfig(
                                    "TABLE",
                                    List.of(f.main(f.remark, "VALUE")),
                                    List.of(),
                                    f.creditMetrics()),
                            M2_REMARK);
                    for (String operation : List.of("SUM", "MAX", "COUNT_FIELD", "COUNT_DISTINCT"))
                        assertRejected(
                                f.detailConfig(
                                        "TABLE",
                                        List.of(f.creditName()),
                                        List.of(),
                                        List.of(metric(operation, f.noteAmount))),
                                M2_NOTE_AMOUNT);
                    assertRejected(with(base, Map.of(f.remark, "x"), List.of(), null), M2_REMARK);
                    assertRejected(
                            with(base, Map.of(), List.of(f.noteAmount), null), M2_NOTE_AMOUNT);
                    assertRejected(
                            with(base, Map.of(), List.of(), condition(f.noteAmount, "gt", "1")),
                            M2_NOTE_AMOUNT);
                    assertRejected(dated(base, f.noteAmount), M2_NOTE_AMOUNT);
                });
    }

    /** 一张凭证有几条分录，主表金额就被算几遍：求和、平均、非空计数不让选；极值与去重计数可以。 */
    @Test
    void detailGrainRejectsSumAvgCountFieldOnMainFields() {
        rollback(
                () -> {
                    f.objects().app();
                    for (String operation : List.of("SUM", "AVG", "COUNT_FIELD"))
                        assertRejected(
                                f.detailConfig(
                                        "TABLE",
                                        List.of(f.creditName()),
                                        List.of(),
                                        List.of(metric(operation, f.income))),
                                M6_INCOME);
                    for (String operation : List.of("MIN", "MAX", "COUNT_DISTINCT"))
                        assertThatCode(
                                        () ->
                                                validator.normalize(
                                                        f.detailConfig(
                                                                "TABLE",
                                                                List.of(f.creditName()),
                                                                List.of(),
                                                                List.of(
                                                                        metric(
                                                                                operation,
                                                                                f.income))),
                                                        definitions()))
                                .doesNotThrowAnyException();
                    // 同一个指标放回主记录粒度是可以求和的。
                    assertThatCode(
                                    () ->
                                            validator.normalize(
                                                    root(month(), rootMetrics()), definitions()))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void detailGrainRejectsDetailDateFieldDrillViewAndEditable() {
        rollback(
                () -> {
                    f.objects().app();
                    var base = f.creditPivot();
                    assertRejected(dated(base, f.creditAmount), M7);
                    // 明细粒度可以挂下钻明细视图并允许编辑（laneDV）；视图须按同一明细逐行显示，这在保存应用时按资源判
                    // （见 ReportDetailDrillViewIntegrationTest），这里只认配置本身。
                    assertThat(
                                    validator
                                            .normalize(
                                                    drillView(base, "voucher_list", Boolean.TRUE),
                                                    definitions())
                                            .detailEditable())
                            .isTrue();
                    assertThat(
                                    validator
                                            .normalize(
                                                    drillView(base, "voucher_list", null),
                                                    definitions())
                                            .detailViewId())
                            .isEqualTo("voucher_list");
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    drillView(base, null, Boolean.TRUE),
                                                    definitions()))
                            .hasMessage("明细允许编辑需要先选择下钻明细视图");
                    // 主表日期字段在明细粒度下照常可用。
                    assertThat(validator.normalize(base, definitions()).dateFieldId())
                            .isEqualTo(f.booked);
                });
    }

    @Test
    void countRootOnlyAtDetailGrainAndWithoutField() {
        rollback(
                () -> {
                    f.objects().app();
                    assertRejected(root(month(), List.of(metric("COUNT_ROOT", null))), M9);
                    assertRejected(
                            f.detailConfig(
                                    "TABLE",
                                    List.of(f.creditName()),
                                    List.of(),
                                    List.of(metric("COUNT_ROOT", f.creditAmount))),
                            M10);
                    assertRejected(
                            f.detailConfig(
                                    "TABLE",
                                    List.of(f.creditName()),
                                    List.of(),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "amount", "贷方", "SUM", f.creditAmount),
                                            new ApplicationReports.Metric(
                                                    "m",
                                                    "张数",
                                                    "COUNT_ROOT",
                                                    null,
                                                    null,
                                                    new ApplicationReports.Formula(
                                                            "ADD", "amount", "amount"),
                                                    null))),
                            M10);
                    // 可以带指标条件，也可以被计算指标引用。
                    var normalized =
                            validator.normalize(
                                    f.detailConfig(
                                            "TABLE",
                                            List.of(f.creditName()),
                                            List.of(),
                                            List.of(
                                                    new ApplicationReports.Metric(
                                                            "amount", "贷方", "SUM", f.creditAmount),
                                                    new ApplicationReports.Metric(
                                                            "vouchers",
                                                            "张数",
                                                            "COUNT_ROOT",
                                                            null,
                                                            condition(f.creditAmount, "gt", "300"),
                                                            null,
                                                            null),
                                                    new ApplicationReports.Metric(
                                                            "per",
                                                            "每张贷方",
                                                            "FORMULA",
                                                            null,
                                                            null,
                                                            new ApplicationReports.Formula(
                                                                    "DIVIDE", "amount", "vouchers"),
                                                            null))),
                                    definitions());
                    assertThat(normalized.metrics().get(1).operation()).isEqualTo("COUNT_ROOT");
                });
    }

    @Test
    void grainAndDetailIdValidation() {
        rollback(
                () -> {
                    f.objects().app();
                    var rows = List.of(f.creditName());
                    assertRejected(
                            f.config(
                                    "ROW",
                                    f.lines.id(),
                                    "TABLE",
                                    month(),
                                    List.of(),
                                    rootMetrics()),
                            "统计粒度无效");
                    assertRejected(
                            f.config("DETAIL", null, "TABLE", rows, List.of(), f.creditMetrics()),
                            "按明细行统计需要选择一个内部明细");
                    assertRejected(
                            f.config("DETAIL", " ", "TABLE", rows, List.of(), f.creditMetrics()),
                            "按明细行统计需要选择一个内部明细");
                    assertRejected(
                            f.config("DETAIL", "404", "TABLE", rows, List.of(), f.creditMetrics()),
                            "统计所选的内部明细不存在");
                    assertRejected(
                            f.config(
                                    "ROOT",
                                    f.lines.id(),
                                    "TABLE",
                                    month(),
                                    List.of(),
                                    rootMetrics()),
                            "按主记录统计不能指定内部明细");
                    assertRejected(
                            f.config(
                                    null, f.lines.id(), "TABLE", month(), List.of(), rootMetrics()),
                            "按主记录统计不能指定内部明细");
                    // 明细已停用：对象定义里把「分录」标成停用。
                    var details =
                            f.voucher.details().stream()
                                    .map(
                                            t ->
                                                    t.id().equals(f.lines.id())
                                                            ? new DataCenter.Detail(
                                                                    t.id(),
                                                                    t.code(),
                                                                    t.name(),
                                                                    t.tableName(),
                                                                    "INACTIVE",
                                                                    t.fields(),
                                                                    t.fieldOptions(),
                                                                    t.indexes(),
                                                                    t.binding())
                                                            : t)
                                    .toList();
                    var v = f.voucher;
                    var stopped =
                            new DataCenter.Definition(
                                    v.objectId(),
                                    v.objectCode(),
                                    v.objectName(),
                                    v.description(),
                                    v.schemaName(),
                                    v.tableName(),
                                    v.source(),
                                    v.readOnly(),
                                    v.titleFieldId(),
                                    v.settings(),
                                    v.fields(),
                                    v.fieldOptions(),
                                    v.relations(),
                                    v.indexes(),
                                    details,
                                    v.mainBinding());
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    f.creditPivot(),
                                                    Map.of(
                                                            v.objectId(),
                                                            stopped,
                                                            f.account.objectId(),
                                                            f.account)))
                            .hasMessage("统计所选的内部明细「分录」已停用");
                    // 空串按没有粒度：主记录粒度，保存出来不带这两个分量。
                    var blank =
                            validator.normalize(
                                    f.config("", null, "TABLE", month(), List.of(), rootMetrics()),
                                    definitions());
                    assertThat(blank.grain()).isNull();
                    assertThat(blank.detailId()).isNull();
                });
    }

    /** 路径第二段走进了目标对象的明细：回单 →（主表关系）凭证 →（分录上的关系）科目。 */
    @Test
    void secondHopThroughTargetDetailRelationIsRejected() {
        rollback(
                () -> {
                    f.objects().app();
                    var receipt = f.receipt();
                    var toVoucher = receipt.relations().getFirst();
                    var config =
                            new ApplicationReports.Config(
                                    receipt.objectId(),
                                    List.of(
                                            new ApplicationReports.Dimension(
                                                    f.accountName,
                                                    toVoucher.id() + "/" + f.creditRelation,
                                                    "VALUE")),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "count", "张数", "COUNT", null)),
                                    Map.of(),
                                    List.of(),
                                    null,
                                    "Asia/Shanghai",
                                    "TABLE",
                                    null,
                                    false,
                                    30,
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    config,
                                                    Map.of(
                                                            receipt.objectId(),
                                                            receipt,
                                                            f.voucher.objectId(),
                                                            f.voucher,
                                                            f.account.objectId(),
                                                            f.account)))
                            .hasMessage("「贷方科目」是「凭证」的明细里的关系，统计路径只能沿主表上的单值关系")
                            .hasMessageNotContaining("未授权");
                });
    }

    /**
     * 应用保存 / 发布时对固定等值的那一遍解析也按统计的粒度：明细粒度的统计可以用所选明细的字段（引用字段、金额）与主表的单选字段作固定等值，
     * 保存出来的配置带着粒度；同样的键放在主记录粒度的统计里报粒度那一句。
     */
    @Test
    void savedReportResolvesFixedEqualKeysByGrain() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var resources = servicesContext.getBean(ApplicationResourceValidator.class);
                    Map<String, Object> equal = new LinkedHashMap<>();
                    equal.put(f.credit, f.accounts.get("销售收入"));
                    equal.put(f.creditAmount, "600");
                    equal.put(f.company, COMPANY_A);
                    var saved =
                            resources.normalize(
                                    f.references(),
                                    List.of(
                                            f.resource(
                                                    "report",
                                                    "REPORT",
                                                    with(
                                                            f.creditPivot(),
                                                            equal,
                                                            List.of(),
                                                            null))));
                    assertThat(saved.getFirst().config())
                            .containsEntry("grain", "DETAIL")
                            .containsEntry("detailId", f.lines.id())
                            .containsEntry("equal", equal);
                    // 三个条件同时成立的只有 L1（甲公司、贷方 销售收入 600）。
                    var r = f.preview(with(f.creditPivot(), equal, List.of(), null));
                    assertCell(r, keys(), keys(), 600, 1, 1);
                    // 单选字段的固定等值仍按选项校验。
                    Map<String, Object> unknown = new LinkedHashMap<>(equal);
                    unknown.put(f.company, "bing");
                    assertThatThrownBy(
                                    () ->
                                            resources.normalize(
                                                    f.references(),
                                                    List.of(
                                                            f.resource(
                                                                    "report",
                                                                    "REPORT",
                                                                    with(
                                                                            f.creditPivot(),
                                                                            unknown,
                                                                            List.of(),
                                                                            null)))))
                            .hasMessage("统计固定筛选值不存在、已停用或超出范围：公司");
                    assertThatThrownBy(
                                    () ->
                                            resources.normalize(
                                                    f.references(),
                                                    List.of(
                                                            f.resource(
                                                                    "report",
                                                                    "REPORT",
                                                                    with(
                                                                            root(
                                                                                    month(),
                                                                                    rootMetrics()),
                                                                            Map.of(
                                                                                    f.creditAmount,
                                                                                    "600"),
                                                                            List.of(),
                                                                            null)))))
                            .hasMessage(M1_CREDIT_AMOUNT);
                });
    }

    /** 页面公共筛选的目标字段按那张统计的粒度解析。 */
    @Test
    void pageFilterCanBindToDetailFieldOfDetailGrainReport() {
        rollback(
                () -> {
                    f.objects().app();
                    var resources = servicesContext.getBean(ApplicationResourceValidator.class);
                    var detailReport =
                            f.resource(
                                    "report",
                                    "REPORT",
                                    with(f.creditPivot(), Map.of(), List.of(f.creditAmount), null));
                    var rootReport =
                            f.resource(
                                    "root",
                                    "REPORT",
                                    with(
                                            root(month(), rootMetrics()),
                                            Map.of(),
                                            List.of(f.income),
                                            null));
                    // 筛选自己的字段是主表的「入金」，联动到明细粒度统计开放的明细筛选字段「贷方金额」（同为金额）。
                    assertThatCode(
                                    () ->
                                            resources.normalize(
                                                    f.references(),
                                                    List.of(
                                                            detailReport,
                                                            page(
                                                                    "report",
                                                                    f.creditAmount,
                                                                    "report"))))
                            .doesNotThrowAnyException();
                    // 同一个目标字段绑到主记录粒度的统计：按粒度那一句拒绝。
                    assertThatThrownBy(
                                    () ->
                                            resources.normalize(
                                                    f.references(),
                                                    List.of(
                                                            rootReport,
                                                            page("root", f.creditAmount, "root"))))
                            .hasMessage(M1_CREDIT_AMOUNT);
                    // 主记录粒度统计开放的主表筛选字段照旧可以绑。
                    assertThatCode(
                                    () ->
                                            resources.normalize(
                                                    f.references(),
                                                    List.of(
                                                            rootReport,
                                                            page("root", f.income, "root"))))
                            .doesNotThrowAnyException();
                });
    }

    private ApplicationCenter.Resource page(String report, String target, String node) {
        return f.resource(
                "page",
                "PAGE",
                new ApplicationUi.Page(
                        List.of(
                                new ApplicationUi.Node(
                                        node + "_node",
                                        "REPORT",
                                        null,
                                        report,
                                        null,
                                        null,
                                        List.of())),
                        null,
                        2,
                        List.of(
                                new ApplicationReports.Filter(
                                        "amount",
                                        "金额",
                                        f.voucher.objectId(),
                                        f.income,
                                        false,
                                        Map.of(report, target)))));
    }

    /** 现行校验上线之前发布的旧配置：主记录粒度 + 明细上的关系路径（当时保存放行）。运行期读已发布快照、不重新校验， 编译时遇到它报粒度那一句，不再是「未授权」。 */
    @Test
    void publishedLegacyConfigGetsGrainMessageAtRuntime() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("legacy", "REPORT", root(month(), rootMetrics()))).seed();
                    assertThat(f.reports.query(f.query("legacy"), OWNER).recordCount())
                            .isEqualTo(5);
                    rewritePublishedSnapshot(
                            f.app,
                            tree -> {
                                var config =
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                tree.path("definition")
                                                        .path("resources")
                                                        .get(0)
                                                        .path("config");
                                var dimension =
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                config.path("dimensions").get(0);
                                dimension.put("fieldId", f.accountName);
                                dimension.put("relationPath", f.creditRelation);
                                dimension.put("bucket", "VALUE");
                            });
                    assertThatThrownBy(() -> f.reports.query(f.query("legacy"), OWNER))
                            .hasMessage(M1_CREDIT)
                            .hasMessageNotContaining("未授权");
                    assertThatThrownBy(() -> f.reports.details(f.query("legacy"), OWNER))
                            .hasMessage(M1_CREDIT);
                    assertThat(f.reports.available(f.app, "legacy", OWNER)).isFalse();
                });
    }
}
