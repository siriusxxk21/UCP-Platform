package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 文本目标的数据联动来源（业务方 2026-10-01 裁定）：单行文本收文本、自动编号、链接、结果为文本的公式；多行文本另收多行文本。
 *
 * <p>规则经对象设计正式保存、发布、应用同步进入运行期，不用 SQL 写规则快照；落库值直接读业务表列核对。
 */
class LinkageTextSourcesIntegrationTest {
    private static final String SITE = "https://example.com/";
    private FieldRuleFixture f;
    private DataCenter.Definition flow;
    private DataCenter.Definition voucher;
    private String app;
    private final List<ApplicationRecords.Row> flowRows = new ArrayList<>();

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
        flow =
                f.object(
                        "flow",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("serial", "流水编号", "AUTO_NUMBER"),
                                field("legacy", "原生编号", "AUTO_NUMBER"),
                                field("site", "网址", "URL"),
                                field("memo", "备注", "TEXTAREA"),
                                field("tag", "标签", "TEXT"),
                                field("label", "大写标签", "FORMULA")),
                        Map.of(
                                "serial",
                                DataCenter.FieldOptions.defaults()
                                        .withAutoNumber(
                                                new AutoNumberOptions("LS-", "", 4, 1L, "NONE")),
                                "label",
                                formula("upper(tag)", "TEXT")),
                        List.of(),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(
                                field("company", "公司", "TEXT"),
                                text("serial_no", "流水编号", 200),
                                field("legacy_no", "原生编号文本", "TEXT"),
                                field("site_text", "网址文本", "TEXT"),
                                field("label_text", "标签文本", "TEXT"),
                                field("all_serials", "全部流水编号", "TEXT"),
                                text("short_no", "短编号", 8),
                                field("memo_area", "备注多行", "TEXTAREA"),
                                field("serial_area", "编号多行", "TEXTAREA"),
                                field("tag_area", "标签多行", "TEXTAREA"),
                                field("memo_line", "备注单行", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        app = f.app(flow, voucher);
        // 这一组用例走的是「对象发布 → 人工同步应用 → 重新发布」：把自动跟随单独关掉，保留人工同步这条路径的覆盖。
        NocodeIntegrationSupport.stopFollowing(app);
        flowRows.clear();
        flowRow("甲", "a1");
        flowRow("甲", "a2");
        flowRow("甲", "a3");
        flowRow("乙", "b1");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    /** 只读联动：自动编号（规则编号与数据库自增）、链接、文本公式带入单行文本；多行文本收多行文本、自动编号与文本；伪造值被重算值覆盖。 */
    @Test
    void textTargetsTakeAutoNumberUrlAndTextFormula() {
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        rule(changes, "serial_no", "serial", "FIRST");
        rule(changes, "legacy_no", "legacy", "FIRST");
        rule(changes, "site_text", "site", "FIRST");
        rule(changes, "label_text", "label", "FIRST");
        rule(changes, "all_serials", "serial", null);
        rule(changes, "memo_area", "memo", "FIRST");
        rule(changes, "serial_area", "serial", "CONCAT");
        rule(changes, "tag_area", "tag", "CONCAT");
        voucher = f.republish(voucher, changes);
        syncApplication();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("serial_no", serial(0));
        expected.put("legacy_no", legacy(0));
        expected.put("site_text", SITE + "a1");
        expected.put("label_text", "A1");
        expected.put("all_serials", String.join(",", serial(0), serial(1), serial(2)));
        expected.put("memo_area", "a1 第一行\na1 第二行");
        expected.put("serial_area", String.join(",", serial(0), serial(1), serial(2)));
        expected.put("tag_area", "a1,a2,a3");
        assertThat(serial(0)).isEqualTo("LS-0001");
        assertThat(expected.get("all_serials")).isEqualTo("LS-0001,LS-0002,LS-0003");

        String company = id(voucher, "company");
        var evaluation =
                f.evaluate(app, voucher, Map.of(company, "甲"), List.of(), List.of(), null, 10001);
        expected.forEach(
                (code, value) -> {
                    var r = result(evaluation, id(voucher, code));
                    assertThat(r.state()).as(code).isEqualTo("APPLIED");
                    assertThat(r.readOnly()).as(code).isTrue();
                    // 带出的值一律是文本：数据库自增编号不以数字、链接不以「地址加文字」的形态落到文本字段。
                    assertThat(r.value()).as(code).isInstanceOf(String.class).isEqualTo(value);
                });

        var input = values(id(voucher, "name"), "甲凭证", company, "甲");
        expected.keySet().forEach(code -> input.put(id(voucher, code), "伪造"));
        var created = f.save(app, voucher, input);
        expected.forEach(
                (code, value) -> {
                    assertThat(created.values()).as(code).containsEntry(id(voucher, code), value);
                    assertThat(stored(created.id(), code)).as(code).isEqualTo(value);
                });

        var moved =
                f.runtime
                        .save(
                                new ApplicationRecords.Save(
                                        app,
                                        voucher.objectId(),
                                        created.id(),
                                        created.revision(),
                                        values(company, "乙"),
                                        null),
                                10001)
                        .record();
        assertThat(moved.values()).containsEntry(id(voucher, "serial_no"), serial(3));
        assertThat(stored(created.id(), "serial_no")).isEqualTo("LS-0004");
        assertThat(stored(created.id(), "legacy_no")).isEqualTo(legacy(3));
        assertThat(stored(created.id(), "site_text")).isEqualTo(SITE + "b1");
        assertThat(stored(created.id(), "label_text")).isEqualTo("B1");
        assertThat(stored(created.id(), "all_serials")).isEqualTo("LS-0004");
        assertThat(stored(created.id(), "serial_area")).isEqualTo("LS-0004");
    }

    /** 带出的文本超过目标长度上限：不截断、不落值，求值与保存都给出点名字段、实际长度与上限的原因；不超长时照常带出。 */
    @Test
    void overLongTextIsRejectedWithReasonNotTruncated() {
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        rule(changes, "short_no", "serial", "CONCAT");
        voucher = f.republish(voucher, changes);
        syncApplication();
        String company = id(voucher, "company");
        String target = id(voucher, "short_no");
        String reason = "共 23 个字符，超过字段「短编号」的长度上限 8，不填值（不截断）";

        var r =
                result(
                        f.evaluate(
                                app,
                                voucher,
                                Map.of(company, "甲"),
                                List.of(),
                                List.of(),
                                null,
                                10001),
                        target);
        assertThat(r.state()).isEqualTo("VALUE_TYPE_MISMATCH");
        assertThat(r.value()).isNull();
        assertThat(r.matchedRows()).isEqualTo(3);
        assertThat(r.message()).isEqualTo("结果「LS-0001,LS-0002,LS-0…」" + reason);

        assertThatThrownBy(
                        () -> f.save(app, voucher, values(id(voucher, "name"), "超长", company, "甲")))
                .hasMessageContaining("字段「短编号」的数据联动无法求值")
                .hasMessageContaining(reason);
        assertThat(count()).isZero();

        var fits = f.save(app, voucher, values(id(voucher, "name"), "不超长", company, "乙"));
        assertThat(stored(fits.id(), "short_no")).isEqualTo("LS-0004");
        assertThat(count()).isEqualTo(1);
    }

    /** 多行文本不能带入单行文本：对象设计保存时即被拒绝，文案点名两侧字段与类型。 */
    @Test
    void singleLineTextRejectsTextareaOnDesignSave() {
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        rule(changes, "memo_line", "memo", "FIRST");
        assertThatThrownBy(() -> f.republish(voucher, changes))
                .hasMessageContaining("字段「备注单行」的数据联动：来源「备注」（多行文本）不能带入到「备注单行」（文本）");
    }

    private static FieldDefinition text(String code, String name, int length) {
        return new FieldDefinition(
                code, null, code, name, "TEXT", length, null, null, false, false, 10);
    }

    private void flowRow(String company, String tag) {
        var input =
                values(
                        id(flow, "name"),
                        tag,
                        id(flow, "company"),
                        company,
                        id(flow, "tag"),
                        tag,
                        id(flow, "site"),
                        Map.of("link", SITE + tag, "text", "官网" + tag),
                        id(flow, "memo"),
                        tag + " 第一行\n" + tag + " 第二行");
        flowRows.add(f.save(app, flow, input));
    }

    private String serial(int row) {
        return (String) flowRows.get(row).values().get(id(flow, "serial"));
    }

    /** 数据库自增编号（未配编号规则）：带入文本后应是它的十进制字面量。 */
    private String legacy(int row) {
        Object value = flowRows.get(row).values().get(id(flow, "legacy"));
        assertThat(value).isNotNull();
        assertThat(value.toString()).matches("\\d+");
        return value.toString();
    }

    /** 只读联动：按公司匹配来源行，取来源字段 valueCode。 */
    private void rule(
            Map<String, DataCenter.FieldOptions> changes,
            String targetCode,
            String valueCode,
            String multiRow) {
        String target = id(voucher, targetCode);
        var options = voucher.fieldOptions().get(target);
        assertThat(options).as("已发布快照包含字段扩展属性：" + targetCode).isNotNull();
        var condition = formField(id(flow, "company"), "eq", id(voucher, "company"));
        var linkage =
                new FieldRules.Linkage(
                        flow.objectId(),
                        List.of(condition),
                        id(flow, valueCode),
                        multiRow,
                        true,
                        null,
                        null);
        changes.put(
                target, options.withRules(new FieldRules(null, linkage, null, null, null, null)));
    }

    /** 应用同步：引用的对象改为各自最新发布版本，保存应用草稿后重新发布。 */
    private void syncApplication() {
        var api = servicesContext.getBean(DataObjectApi.class);
        var before = f.applications.get(app);
        var refs = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var r : before.draft().objects()) {
            var v = api.getVersion(r.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            v.objectId(), v.versionNo(), v.checksum()));
        }
        assertThat(refs).isNotEqualTo(before.draft().objects());
        var definition = new ApplicationCenter.Definition(refs, before.draft().resources());
        var request =
                new ApplicationCenter.Save(
                        app,
                        before.application().revision(),
                        before.application().code(),
                        before.application().name(),
                        null,
                        null,
                        definition);
        var saved = f.applications.save(request, 10001);
        grantApplicationObjects(app);
        f.publishSynced(saved.application());
    }

    private Object stored(String recordId, String code) {
        String column = voucher.fieldOptions().get(id(voucher, code)).columnName();
        String sql = "SELECT \"" + column + "\" FROM public.\"" + voucher.tableName() + "\"";
        return jdbc.queryForObject(sql + " WHERE id=?", Object.class, Long.valueOf(recordId));
    }

    private int count() {
        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.\"" + voucher.tableName() + "\"",
                        Integer.class);
        return rows == null ? 0 : rows;
    }
}
