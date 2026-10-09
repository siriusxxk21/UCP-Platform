package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.SelectionAllMigrationPlanner.*;
import static com.lingan.ucp.nocode.tools.SelectionAllMigrationReport.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Member;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Save;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationUi;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.ObjectSharing;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator.Universe;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存量授权清单转「全部」的全流程（连测试库，只动本夹具前缀的应用）：dry-run → apply → rollback，以及 expand-all。
 *
 * <p>夹具按真实的时间顺序造：对象 V1（名称、金额、备注）发布 → 应用「后加」「故意少选」各自把四个存放处存成显式清单 → 对象发布 V2（新增字段 extra）→ 应用「恰好」按 V2
 * 存显式全量清单。自动跟随关着，应用不会自己提版。
 *
 * <ul>
 *   <li>「恰好」：清单恰好等于全集 ⇒ EXACT
 *   <li>「后加」：清单只差保存之后才发布的 extra ⇒ LATER_ONLY，留底清单点名 extra
 *   <li>「故意少选」：可查看字段少了早就有的备注、可修改字段另外少了金额 ⇒ KEPT
 * </ul>
 */
class SelectionAllMigrationToolTest {
    private static final long ACTOR = 10001L;
    private static final long MEMBER = 23101L;

    private final ObjectMapper json = ObjectRuleMigrationTool.mapper();
    private final ObjectGrantValidator validator = new ObjectGrantValidator();
    private FollowFixture f;
    private SelectionAllMigrationTool tool;
    private String objectId, exact, later, kept, extra;

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
        f = new FollowFixture();
        f.configure(false, "strict");
        tool =
                new SelectionAllMigrationTool(
                        SelectionAllMigrationTool.Services.of(servicesContext), json);
        objectId = f.object("m", "迁移对象");
        later = application("later", false);
        kept = application("kept", true);
        f.publishNewField(objectId, "extra", "新增字段");
        extra = f.fieldId(objectId, "extra");
        exact = application("exact", false);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    // ── 夹具 ──

    private Set<String> fields(
            DataCenter.Definition d, Set<String> withoutCodes, boolean writable) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : Universe.of(d).fields()) {
            String code =
                    d.fields().stream()
                            .filter(field -> field.id().equals(id))
                            .findFirst()
                            .orElseThrow()
                            .code();
            if (withoutCodes.contains(code)) continue;
            if (writable && !ObjectGrantValidator.writable(d, id)) continue;
            ids.add(id);
        }
        return ids;
    }

    private ObjectGrant grant(Set<String> actions, Set<String> read, Set<String> write) {
        return new ObjectGrant(
                objectId, actions, "ALL", read, write, Set.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * 建应用（表单、视图、任务入口）并发布，然后把四个存放处都存成对着对象当前最新版的显式清单。
     *
     * <p>narrowed=false：四处都是全量清单。narrowed=true（故意少选）：应用上限与入口范围的可查看字段少了备注、可修改字段另外少了金额；
     * 两处成员授权的可查看字段在上限之内又少了金额、不给修改。
     */
    private String application(String suffix, boolean narrowed) {
        DataCenter.Definition d = f.latest(objectId);
        Set<String> read = fields(d, narrowed ? Set.of("memo") : Set.of(), false);
        Set<String> write = fields(d, narrowed ? Set.of("memo", "amount") : Set.of(), true);
        ObjectGrant member =
                narrowed
                        ? grant(
                                Set.of("READ"),
                                fields(d, Set.of("memo", "amount"), false),
                                Set.of())
                        : grant(Set.of("READ", "UPDATE"), read, write);
        String name = f.fieldId(objectId, "name");
        ApplicationCenter.Resource form =
                f.resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                objectId,
                                List.of(
                                        new ApplicationUi.Node(
                                                "name_node",
                                                "FIELD",
                                                name,
                                                null,
                                                null,
                                                null,
                                                List.of())),
                                List.of()));
        ApplicationCenter.Resource view =
                f.resource(
                        "view",
                        "VIEW",
                        new ApplicationUi.View(
                                objectId, List.of(name), Map.of(), null, false, 10, "form"));
        // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增），入口成员授权（④）与入口允许范围（③）两处存放随之不再造夹具；本类只核对 ① 应用上限与 ② 应用成员授权。
        String app = f.app(suffix, "迁移" + suffix, ACTOR, List.of(form, view), objectId);
        int revision =
                f.sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(objectId))
                        .findFirst()
                        .map(ObjectSharing.Grant::revision)
                        .orElse(0);
        f.sharing.save(
                new ObjectSharing.Save(
                        objectId,
                        app,
                        revision,
                        grant(Set.of("READ", "CREATE", "UPDATE", "DELETE"), read, write),
                        "迁移夹具"),
                ACTOR);
        f.authorization.save(
                new Save(
                        app,
                        f.authorization.get(app).revision(),
                        List.of(new Member("USER", Long.toString(MEMBER), List.of(member)))),
                ACTOR);
        return app;
    }

    /** 四个存放处与已发布快照的原文（jsonb 文本 + 保存时间），用来做逐字节比对。 */
    private Map<String, String> snapshot(String app) {
        Map<String, String> result = new LinkedHashMap<>();
        Long id = Long.valueOf(app);
        result.put(
                "①",
                jdbc.queryForObject(
                        "SELECT grant_json::text || ' @ ' || update_time::text FROM"
                                + " public.nocode_object_application_grant WHERE application_id=?",
                        String.class,
                        id));
        result.put(
                "②",
                jdbc.queryForObject(
                        "SELECT policy_json::text || ' @ ' || update_time::text FROM"
                                + " public.nocode_application_access WHERE application_id=?",
                        String.class,
                        id));
        result.put(
                "③",
                jdbc.queryForObject(
                        "SELECT design_json::text || ' @ ' || update_time::text FROM"
                                + " public.nocode_application WHERE id=?",
                        String.class,
                        id));
        return result;
    }

    /** 已发布快照的字节、校验和、版本数与发布指针。 */
    private String published(String app) {
        return jdbc.queryForObject(
                "SELECT string_agg(v.version_no || ':' || v.checksum || ':' ||"
                        + " md5(v.definition_json::text), ',' ORDER BY v.version_no) || ' → ' ||"
                        + " max(a.published_version) FROM public.nocode_application_version v JOIN"
                        + " public.nocode_application a ON a.id=v.application_id WHERE"
                        + " v.application_id=?",
                String.class,
                Long.valueOf(app));
    }

    private JsonNode stored(String sql, Object... args) {
        try {
            return json.readTree(jdbc.queryForObject(sql, String.class, args));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode ceiling(String app) {
        return stored(
                "SELECT grant_json::text FROM public.nocode_object_application_grant WHERE"
                        + " application_id=?",
                Long.valueOf(app));
    }

    private JsonNode member(String app) {
        return stored(
                        "SELECT policy_json::text FROM public.nocode_application_access WHERE"
                                + " application_id=?",
                        Long.valueOf(app))
                .get(0)
                .path("objects")
                .get(0);
    }

    private ObjectGrant parsed(JsonNode node) {
        try {
            return json.treeToValue(node, ObjectGrant.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Row row(SelectionAllMigrationReport report, String store, String app) {
        return report.rows().stream()
                .filter(r -> r.store().equals(store) && r.applicationId().equals(app))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有 " + store + " / " + app));
    }

    private static Change change(Row row, String dimension) {
        return row.changes().stream()
                .filter(c -> c.dimension().equals(dimension))
                .findFirst()
                .orElseThrow(() -> new AssertionError(row.describe() + " 没有 " + dimension));
    }

    private static List<String> outcomes(Row row) {
        return row.changes().stream().map(c -> c.dimension() + "=" + c.outcome()).toList();
    }

    private SelectionAllMigrationReport dryRun() {
        return tool.dryRun(f.prefix());
    }

    // ── 用例 ──

    /** 契约第 13 章第 14 步：三种清单分别判为 EXACT、LATER_ONLY、KEPT，不变式通过；dry-run 不写库。 */
    @Test
    void dryRunClassifiesTheThreeKindsOfListsInEveryStoreWithoutWriting() {
        List<Map<String, String>> before =
                List.of(snapshot(exact), snapshot(later), snapshot(kept));
        SelectionAllMigrationReport report = dryRun();
        assertThat(report.violations()).as("不变式").isEmpty();
        assertThat(report.rows()).allMatch(r -> PLANNED.equals(r.status()));
        assertThat(report.sessionTimeZone()).isNotBlank();
        for (String store : List.of(OBJECT_GRANT, APPLICATION_ACCESS))
            assertThat(outcomes(row(report, store, exact)))
                    .as("恰好 / " + store)
                    .containsExactly("readFields=EXACT", "writeFields=EXACT");
        for (String store : List.of(OBJECT_GRANT, APPLICATION_ACCESS)) {
            Row row = row(report, store, later);
            assertThat(outcomes(row))
                    .as("后加 / " + store)
                    .containsExactly("readFields=LATER_ONLY", "writeFields=LATER_ONLY");
            assertThat(change(row, "readFields").gained())
                    .containsExactly(new Item(extra, "新字段extra"));
            assertThat(change(row, "writeFields").gained())
                    .containsExactly(new Item(extra, "新字段extra"));
        }
        for (String store : List.of(OBJECT_GRANT, APPLICATION_ACCESS)) {
            Row row = row(report, store, kept);
            assertThat(outcomes(row))
                    .as("故意少选 / " + store)
                    .isEqualTo(
                            OBJECT_GRANT.equals(store)
                                    ? List.of("readFields=KEPT", "writeFields=KEPT")
                                    : List.of("readFields=KEPT"));
            assertThat(row.after()).as("保留的行不改").isEqualTo(row.before());
        }
        assertThat(List.of(snapshot(exact), snapshot(later), snapshot(kept)))
                .as("dry-run 不写库")
                .isEqualTo(before);
    }

    /** apply 写入四个存放处、不碰已发布快照；留底清单点名后加的字段；rollback 后逐字节回到转换前，再跑 dry-run 结果与第一次相同。 */
    @Test
    void applyWritesAllStoresKeepsPublishedSnapshotsAndRollbackRestoresEveryByte() {
        List<String> apps = List.of(exact, later, kept);
        List<Map<String, String>> before = apps.stream().map(this::snapshot).toList();
        List<String> snapshots = apps.stream().map(this::published).toList();
        int logs = grantLogs();
        SelectionAllMigrationReport plan = dryRun();

        SelectionAllMigrationReport applied = tool.apply(plan, ACTOR);
        assertThat(applied.command()).isEqualTo("apply");
        for (String app : List.of(exact, later))
            for (String store : List.of(OBJECT_GRANT, APPLICATION_ACCESS))
                assertThat(row(applied, store, app).status())
                        .as(store + " / " + app)
                        .isEqualTo(WRITTEN);
        for (String store : List.of(OBJECT_GRANT, APPLICATION_ACCESS))
            assertThat(row(applied, store, kept).status()).as("保留的行不写").isEqualTo(PLANNED);
        assertThat(applied.summary().conflicts()).isZero();
        for (String app : List.of(exact, later))
            for (JsonNode node : List.of(ceiling(app), member(app))) {
                assertThat(strings(node.path("readFields"))).containsExactly("*");
                assertThat(strings(node.path("writeFields"))).containsExactly("*");
                assertThat(strings(node.path("readDetails"))).as("空清单不转").isEmpty();
            }
        assertThat(ceiling(exact).path("actions")).hasSize(4);
        assertThat(snapshot(kept)).as("故意少选的应用四处都不动").isEqualTo(before.get(2));
        assertThat(apps.stream().map(this::published).toList())
                .as("已发布快照的字节、校验和、版本数与发布指针都不变")
                .isEqualTo(snapshots);
        assertThat(grantLogs()).as("对象→应用授权每写一行记一条日志").isEqualTo(logs + 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM public.nocode_object_application_grant_log"
                                        + " WHERE application_id=? ORDER BY id DESC LIMIT 1",
                                String.class,
                                Long.valueOf(exact)))
                .isEqualTo("存量转换：授权清单转「全部」");

        String retained = applied.retained();
        assertThat(retained)
                .contains("| 迁移later | 应用上限 | 迁移对象 | 可查看字段 | 只差后加的项 | 新字段extra |")
                .contains("| 迁移later | 应用上限 | 迁移对象 | 可填写和修改字段 | 只差后加的项 | 新字段extra |")
                .contains("| 迁移exact | 应用上限 | 迁移对象 | 可查看字段 | 恰好等于全部 | （无） |")
                .contains("用户「编号 " + MEMBER + "」");
        assertThat(retained).as("保留的清单不进留底").doesNotContain("迁移kept");

        SelectionAllMigrationReport second = tool.apply(plan, ACTOR);
        assertThat(second.rows().stream().filter(r -> !r.before().equals(r.after())))
                .as("同一份报告再执行一次：库里已不是转换前，全部记冲突，不重复写")
                .allMatch(r -> CONFLICT.equals(r.status()));

        SelectionAllMigrationReport rolled = tool.rollback(applied, ACTOR);
        assertThat(rolled.summary().conflicts()).isZero();
        assertThat(rolled.summary().written()).isEqualTo(4);
        assertThat(apps.stream().map(this::snapshot).toList())
                .as("回滚后四个存放处的原文与保存时间逐字节回到转换前")
                .isEqualTo(before);
        assertThat(apps.stream().map(this::published).toList()).isEqualTo(snapshots);
        SelectionAllMigrationReport again = dryRun();
        assertThat(again.rows()).as("回滚后再演练，计划与第一次相同").isEqualTo(plan.rows());
    }

    private int grantLogs() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_object_application_grant_log WHERE"
                        + " application_id IN (?,?,?)",
                Integer.class,
                Long.valueOf(exact),
                Long.valueOf(later),
                Long.valueOf(kept));
    }

    /** 报告经文件往返后照样能执行（命令行就是这么用的）。 */
    @Test
    void reportSurvivesAFileRoundTrip() throws Exception {
        SelectionAllMigrationReport plan = dryRun();
        SelectionAllMigrationReport reloaded =
                json.readValue(json.writeValueAsBytes(plan), SelectionAllMigrationReport.class);
        assertThat(reloaded.rows()).isEqualTo(plan.rows());
        SelectionAllMigrationReport applied = tool.apply(reloaded, ACTOR);
        assertThat(applied.summary().conflicts()).isZero();
        assertThat(applied.summary().written()).isEqualTo(4);
    }

    /** 演练之后有人改了其中一行：apply 跳过它并记 CONFLICT，那一行保持别人改后的样子；同一应用的成员授权也不写。 */
    @Test
    void rowChangedAfterTheDryRunIsSkippedAsConflict() {
        SelectionAllMigrationReport plan = dryRun();
        DataCenter.Definition d = f.latest(objectId);
        int revision =
                f.sharing.forApplication(exact).stream().findFirst().orElseThrow().revision();
        f.sharing.save(
                new ObjectSharing.Save(
                        objectId,
                        exact,
                        revision,
                        grant(
                                Set.of("READ", "CREATE", "UPDATE"),
                                fields(d, Set.of(), false),
                                fields(d, Set.of(), true)),
                        "演练之后管理员收回了删除"),
                ACTOR);
        Map<String, String> narrowed = snapshot(exact);

        SelectionAllMigrationReport applied = tool.apply(plan, ACTOR);

        assertThat(row(applied, OBJECT_GRANT, exact).status()).isEqualTo(CONFLICT);
        assertThat(row(applied, APPLICATION_ACCESS, exact).status())
                .as("它的全集是按「上限转成全部之后」算的，上限没写，成员也不写")
                .isEqualTo(CONFLICT);
        assertThat(snapshot(exact).get("①")).as("别人改后的那一行原样保留").isEqualTo(narrowed.get("①"));
        assertThat(snapshot(exact).get("②")).isEqualTo(narrowed.get("②"));
        assertThat(row(applied, OBJECT_GRANT, later).status()).as("别的应用照常写入").isEqualTo(WRITTEN);
        assertThat(strings(ceiling(later).path("readFields"))).containsExactly("*");
    }

    /**
     * 不变式被故意破坏：做判定用的全集漏了字段 extra（于是「后加」应用的清单看起来恰好等于全集、没有记下新获得的项），
     * 而核对用的全集直接取自对象定义。演练要把它报出来，执行要整体中止、库不变。
     */
    @Test
    void brokenInvariantIsReportedByDryRunAndAbortsApplyLeavingTheDatabaseUntouched() {
        SelectionAllMigrationReport plan = dryRun();
        List<String> apps = List.of(exact, later, kept);
        List<Map<String, String>> before = apps.stream().map(this::snapshot).toList();
        SelectionAllMigrationTool broken =
                new SelectionAllMigrationTool(
                        SelectionAllMigrationTool.Services.of(servicesContext),
                        json,
                        d -> {
                            Universe universe = Universe.of(d);
                            return new Universe(
                                    universe.fields().stream()
                                            .filter(id -> !id.equals(extra))
                                            .toList(),
                                    universe.details(),
                                    universe.relations());
                        });

        SelectionAllMigrationReport wrong = broken.dryRun(f.prefix());
        assertThat(wrong.violations())
                .contains(
                        "应用上限 / 迁移对象 / 可查看字段：新获得的项 [" + extra + "] 与记录的 [] 不符",
                        "应用上限 / 迁移对象 / 可填写和修改字段：新获得的项 [" + extra + "] 与记录的 [] 不符");
        assertThatThrownBy(() -> broken.apply(wrong, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("报告里有不变式不成立的清单，不能执行：");
        assertThatThrownBy(() -> broken.apply(plan, ACTOR))
                .as("报告是好的，但执行时重新算出的计划不变式不成立")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("不变式不成立，执行中止，库未改动：");
        assertThat(apps.stream().map(this::snapshot).toList()).as("库不变").isEqualTo(before);
    }

    /** expand-all：库里不再有「全部」，且每个清单展开后的结果与之前相同；带 --dry-run 时只出计划。 */
    @Test
    void expandAllRemovesEveryStarWithoutChangingWhatAnyoneCanDo() {
        tool.apply(dryRun(), ACTOR);
        DataCenter.Definition latest = f.latest(objectId);
        List<String> apps = List.of(exact, later, kept);
        List<List<ObjectGrant>> before = apps.stream().map(app -> effective(app, latest)).toList();
        List<Map<String, String>> raw = apps.stream().map(this::snapshot).toList();
        assertThat(raw.toString()).contains("\"*\"");
        List<String> snapshots = apps.stream().map(this::published).toList();

        SelectionAllMigrationReport planned = tool.expandAll(f.prefix(), ACTOR, true);
        assertThat(planned.rows()).isNotEmpty().allMatch(r -> PLANNED.equals(r.status()));
        assertThat(apps.stream().map(this::snapshot).toList()).as("只出计划，不写库").isEqualTo(raw);

        SelectionAllMigrationReport expanded = tool.expandAll(f.prefix(), ACTOR, false);
        assertThat(expanded.rows()).allMatch(r -> WRITTEN.equals(r.status()));
        assertThat(expanded.summary().expanded()).isPositive();
        for (String app : apps)
            for (JsonNode node : List.of(ceiling(app), member(app)))
                assertThat(node.toString()).as("库里不再有「全部」").doesNotContain("*");
        assertThat(apps.stream().map(app -> effective(app, latest)).toList())
                .as("展开(之后) == 展开(之前)")
                .isEqualTo(before);
        assertThat(apps.stream().map(this::published).toList()).isEqualTo(snapshots);
        assertThat(tool.expandAll(f.prefix(), ACTOR, true).rows()).as("再跑一次已无可展开").isEmpty();

        SelectionAllMigrationReport rolled = tool.rollback(expanded, ACTOR);
        assertThat(rolled.summary().conflicts()).isZero();
        assertThat(apps.stream().map(this::snapshot).toList())
                .as("expand-all 的结果也能按报告回滚")
                .isEqualTo(raw);
    }

    /** 两个存放处各自展开后的有效授权：上限对着最新发布版展开；成员在上限之内展开（入口两处已随 dev 退役）。 */
    private List<ObjectGrant> effective(String app, DataCenter.Definition latest) {
        ObjectGrant ceiling = parsed(ceiling(app));
        return List.of(
                validator.resolve(ceiling, latest),
                validator.resolve(validator.intersect(parsed(member(app)), ceiling), latest));
    }

    @Test
    void wrongReportsAndArgumentsAreRejectedBeforeAnythingIsWritten() {
        SelectionAllMigrationReport plan = dryRun();
        assertThatThrownBy(() -> tool.apply(plan, 0)).hasMessage("--actor 必须是正整数");
        assertThatThrownBy(() -> tool.rollback(plan, ACTOR))
                .hasMessageContaining("产出的报告")
                .hasMessageContaining("apply");
        SelectionAllMigrationReport applied = tool.apply(plan, ACTOR);
        assertThatThrownBy(() -> tool.apply(applied, ACTOR)).hasMessage("需要本工具 dry-run 产出的报告");
        assertThat(
                        SelectionAllMigrationTool.Command.parse(
                                        new String[] {"dry-run", "--prefix", "abc_"}, json)
                                .prefix())
                .isEqualTo("abc_");
        assertThat(
                        SelectionAllMigrationTool.Command.parse(
                                        new String[] {"expand-all", "--actor", "7", "--dry-run"},
                                        json)
                                .dryRun())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                SelectionAllMigrationTool.Command.parse(
                                        new String[] {"dry-run", "--actor", "7"}, json))
                .hasMessage("dry-run 不接受参数 --actor");
        assertThatThrownBy(
                        () ->
                                SelectionAllMigrationTool.Command.parse(
                                        new String[] {"apply", "--report", "x.json"}, json))
                .hasMessage("缺少 --actor");
        assertThatThrownBy(
                        () ->
                                SelectionAllMigrationTool.Command.parse(
                                        new String[] {"convert"}, json))
                .hasMessage("子命令只能是 dry-run、apply、rollback、expand-all、follow-behind");
        assertThat(SelectionAllMigrationTool.run(new String[] {"apply", "--actor", "x"}))
                .as("参数错误在连库之前返回")
                .isEqualTo(SelectionAllMigrationTool.EXIT_USAGE);
    }
}
