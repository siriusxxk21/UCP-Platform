package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 给前端（laneMF）的接口样例：契约 14 章三个例子的统计配置（请求原文 → 保存后的规范形态 → 运行端下发）、取数结果、下钻请求与返回。 全部是服务层真实调用的输入与返回经同一个
 * ObjectMapper 序列化的原文（HTTP 层在外面再包一层 CommonResult），打印到标准输出， 由 laneMB 摘进 api-samples.md。每例整体回滚。
 */
class ReportMultiSourceApiSamplesTest {
    private ReportMultiSourceFixture f;

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

    private static void print(String title, Object value) {
        try {
            String text =
                    mapper.copy()
                            .enable(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(value);
            System.out.println("=== SAMPLE " + title + "\n" + text + "\n=== END");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 夹具 ID → 名称的对照（样例里的 ID 是本次运行生成的）。 */
    private void legend() {
        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("app", f.app);
        legend.put(
                "objects",
                Map.of(
                        "物件",
                        f.property.objectId(),
                        "入住记录",
                        f.stay.objectId(),
                        "支出",
                        f.expense.objectId(),
                        "会计科目",
                        f.account.objectId(),
                        "会计凭证",
                        f.voucher.objectId()));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("入住记录.物件（引用字段）", f.stayProperty);
        fields.put("入住记录.物件（关系 ID）", f.stayPropertyRelation);
        fields.put("入住记录.入住日", f.checkIn);
        fields.put("入住记录.退房日", f.checkOut);
        fields.put("入住记录.当月金额", f.curAmount);
        fields.put("入住记录.次月金额", f.nextAmount);
        fields.put("支出.物件（引用字段）", f.expenseProperty);
        fields.put("支出.支出日期", f.paidOn);
        fields.put("支出.金额", f.amount);
        fields.put("物件.名称", f.propertyName);
        fields.put("会计凭证.日期", f.booked);
        fields.put("分录（明细 ID）", f.lines.id());
        fields.put("分录.借方科目", f.debit);
        fields.put("分录.贷方科目", f.credit);
        fields.put("分录.借方金额", f.debitAmount);
        fields.put("分录.贷方金额", f.creditAmount);
        legend.put("fields", fields);
        legend.put("records", f.ids);
        print("legend", legend);
    }

    private ApplicationReports.Config saved(String report) {
        var resource =
                f.apps.get(f.app).draft().resources().stream()
                        .filter(r -> r.id().equals(report))
                        .findFirst()
                        .orElseThrow();
        return mapper.convertValue(resource.config(), ApplicationReports.Config.class);
    }

    private Object delivered(String report) {
        return servicesContext
                .getBean(ApplicationRuntimeService.class)
                .application(f.app, OWNER)
                .definition()
                .resources()
                .stream()
                .filter(r -> r.id().equals(report))
                .findFirst()
                .orElseThrow()
                .config();
    }

    @Test
    void samples() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config ex1 = f.example1();
                    ApplicationReports.Config ex2 = f.example2(false);
                    ApplicationReports.Config ex3 = f.example3();
                    f.app(
                                    f.resource("profit", "REPORT", ex1),
                                    f.resource("revenue", "REPORT", ex2),
                                    f.resource("trial", "REPORT", ex3))
                            .seed();
                    legend();
                    print("example1.request-config", ex1);
                    print("example1.saved-config", saved("profit"));
                    print("example1.runtime-config", delivered("profit"));
                    ApplicationReports.Query q1 = f.query("profit");
                    print("example1.query.request", q1);
                    print("example1.query.response", f.reports.query(q1, OWNER));
                    ApplicationReports.Query sorted =
                            new ApplicationReports.Query(
                                    f.app,
                                    "profit",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    1,
                                    100,
                                    null,
                                    null,
                                    null,
                                    new ApplicationReports.Sort(
                                            "exp", null, List.of("2026-08"), true));
                    print("example1.query-sorted.request", sorted);
                    print(
                            "example1.query-sorted.response.rows",
                            f.reports.query(sorted, OWNER).pivot().rows());
                    ApplicationReports.Query d1 =
                            f.drill("profit", keys(f.key("P1")), keys("2026-08"), "exp", null);
                    print("example1.details.request", d1);
                    print("example1.details.response", f.reports.details(d1, OWNER));
                    print("example2.request-config", ex2);
                    print("example2.saved-config", saved("revenue"));
                    ApplicationReports.Query q2 = f.query("revenue");
                    print("example2.query.request", q2);
                    print("example2.query.response", f.reports.query(q2, OWNER));
                    print("example3.request-config", ex3);
                    print("example3.saved-config", saved("trial"));
                    ApplicationReports.Query q3 = f.query("trial");
                    print("example3.query.request", q3);
                    print("example3.query.response", f.reports.query(q3, OWNER));
                    ApplicationReports.Query d3 =
                            f.drill("trial", keys(f.key("A1")), keys("2026-08"), "cr", null);
                    print("example3.details.request", d3);
                    print("example3.details.response", f.reports.details(d3, OWNER));
                    print(
                            "example1.preview.request",
                            new ApplicationReports.Preview(f.app, f.references(), ex1, List.of()));
                    print("example1.preview.response.sources", f.preview(ex1).sources());
                    assertThat(saved("profit").extraSources()).hasSize(2);
                });
    }
}
