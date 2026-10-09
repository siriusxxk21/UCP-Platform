package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 选项类目标的反方向相容（业务方 2026-10-01 场景）：资金流水「凭证状态」是单选·局部选项，凭证录入「凭证状态」是单选·挑取值并指向它；
 * 在资金流水「凭证状态」上配数据联动、来源取凭证录入「凭证状态」。
 *
 * <p>联动规则经对象设计正式保存、发布、应用同步进入运行期；来源侧的挑取值沿用既有夹具写法（发布后改写快照）。落库值直接读业务表列核对。
 */
class LinkageReversePickIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition flow;
    private DataCenter.Definition voucher;
    private String app;

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
                        List.of(field("status", "凭证状态", "SELECT"), field("kind", "类别", "SELECT")),
                        Map.of("status", states(false), "kind", states(false)),
                        List.of(),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(
                                field("flow_no", "流水名称", "TEXT"),
                                field("status", "凭证状态", "SELECT"),
                                field("level", "级别", "SELECT")),
                        Map.of("status", placeholder(), "level", placeholder()),
                        List.of(),
                        List.of());
        voucher = pick(voucher, "status", "status");
        voucher = pick(voucher, "level", "kind");
        app = f.app(flow, voucher);
        // 这一组用例走的是「对象发布 → 人工同步应用 → 重新发布」：把自动跟随单独关掉，保留人工同步这条路径的覆盖。
        NocodeIntegrationSupport.stopFollowing(app);
        f.save(
                app,
                voucher,
                values(
                        id(voucher, "name"),
                        "凭证一",
                        id(voucher, "flow_no"),
                        "F1",
                        id(voucher, "status"),
                        "DONE",
                        id(voucher, "level"),
                        "REG"));
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    /** 来源字段的挑取值指向当前字段：设计保存与发布通过，保存记录时选项编码原样带入并覆盖伪造值。 */
    @Test
    void optionTargetTakesCodeFromFieldPickedFromItself() {
        linkStatus("status", states(false));
        String status = id(flow, "status");
        var r =
                result(
                        f.evaluate(
                                app,
                                flow,
                                Map.of(id(flow, "name"), "F1"),
                                List.of(),
                                List.of(),
                                null,
                                10001),
                        status);
        assertThat(r.state()).isEqualTo("APPLIED");
        assertThat(r.value()).isEqualTo("DONE");

        var created = f.save(app, flow, values(id(flow, "name"), "F1", status, "REG"));
        assertThat(created.values()).containsEntry(status, "DONE");
        assertThat(stored(created.id())).isEqualTo("DONE");
        var unmatched = f.save(app, flow, values(id(flow, "name"), "F2", status, "REG"));
        assertThat(stored(unmatched.id())).isNull();
    }

    /** 带出的编码在当前字段里已停用：按现有选项校验口径不填值，只读联动的保存被拒绝，不把失效编码写进库。 */
    @Test
    void codeDisabledInTargetIsNotWritten() {
        linkStatus("status", states(true));
        String status = id(flow, "status");
        var r =
                result(
                        f.evaluate(
                                app,
                                flow,
                                Map.of(id(flow, "name"), "F1"),
                                List.of(),
                                List.of(),
                                null,
                                10001),
                        status);
        assertThat(r.state()).isEqualTo("VALUE_TYPE_MISMATCH");
        assertThat(r.value()).isNull();
        assertThat(r.message()).isEqualTo("结果「DONE」不符合字段「凭证状态」的类型，不填值");
        assertThatThrownBy(() -> f.save(app, flow, values(id(flow, "name"), "F1")))
                .hasMessageContaining("字段「凭证状态」的数据联动无法求值")
                .hasMessageContaining("结果「DONE」不符合字段「凭证状态」的类型，不填值");
        assertThat(count()).isZero();
    }

    /** 来源字段挑的是别的字段：与当前字段不共用选项集，设计保存时仍被拒绝。 */
    @Test
    void sourcePickedFromAnotherFieldIsRejectedOnDesignSave() {
        assertThatThrownBy(() -> linkStatus("level", states(false)))
                .hasMessageContaining("字段「凭证状态」的数据联动：来源「级别」（单选）不能带入到「凭证状态」（单选）");
    }

    private static DataCenter.FieldOptions states(boolean doneDisabled) {
        return options(
                List.of(
                        new DataCenter.Option("REG", "已登记", false),
                        new DataCenter.Option("DONE", "已完成", doneDisabled)));
    }

    /** 凭证录入的选项字段改为挑取值，指向资金流水的 sourceCode 字段。 */
    private DataCenter.Definition pick(DataCenter.Definition d, String code, String sourceCode) {
        var source =
                new SelectionFields.Source(
                        "OBJECT_FIELD_OPTIONS",
                        null,
                        null,
                        List.of(),
                        false,
                        List.of(),
                        "NONE",
                        null,
                        flow.objectId(),
                        id(flow, sourceCode));
        return patch(d, id(d, code), o -> withOptions(o, List.of()).withSelection(source));
    }

    /** 资金流水「凭证状态」配只读联动：按流水名称匹配凭证，取凭证的 valueCode 字段；正式保存、发布并同步应用。 */
    private void linkStatus(String valueCode, DataCenter.FieldOptions local) {
        String status = id(flow, "status");
        var current = flow.fieldOptions().get(status);
        assertThat(current).as("已发布快照包含字段扩展属性").isNotNull();
        var condition = formField(id(voucher, "flow_no"), "eq", id(flow, "name"));
        var linkage =
                new FieldRules.Linkage(
                        voucher.objectId(),
                        List.of(condition),
                        id(voucher, valueCode),
                        "FIRST",
                        true,
                        null,
                        null);
        var changes = new LinkedHashMap<String, DataCenter.FieldOptions>();
        changes.put(
                status,
                withOptions(current, local.options())
                        .withRules(new FieldRules(null, linkage, null, null, null, null)));
        flow = f.republish(flow, changes);
        syncApplication();
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

    private Object stored(String recordId) {
        String column = flow.fieldOptions().get(id(flow, "status")).columnName();
        String sql = "SELECT \"" + column + "\" FROM public.\"" + flow.tableName() + "\"";
        return jdbc.queryForObject(sql + " WHERE id=?", Object.class, Long.valueOf(recordId));
    }

    private int count() {
        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.\"" + flow.tableName() + "\"", Integer.class);
        return rows == null ? 0 : rows;
    }
}
