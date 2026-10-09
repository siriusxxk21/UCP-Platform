package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.tools.ReferenceConstantMigrationReport.Row;

import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 引用字段条件固定值的存量转换工具（当前开发库真实读写）。夹具照业务方的两处：入住记录.物件名称、房间信息.物件名称 的引用筛选「管理状态 = 民宿管理」
 * （存的是名称）。另一个对象「活动」上有名称对上多条（AMBIGUOUS）与对不上（NO_MATCH，数据联动条件）各一处：它自己那条本可转换的值因此记 BLOCKED， 整个对象不动。已是记录
 * ID 的条件不进报告。
 */
class ReferenceConstantMigrationIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition status;
    private DataCenter.Definition property;
    private DataCenter.Definition stay;
    private DataCenter.Definition room;
    private DataCenter.Definition event;
    private String app;
    private String management;
    private ReferenceConstantMigrationTool tool;
    private final Map<String, String> ids = new LinkedHashMap<>();

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
                new ReferenceConstantMigrationTool(
                        ReferenceConstantMigrationTool.Services.of(servicesContext),
                        ObjectRuleMigrationTool.mapper());
        status = f.object("st", List.of(), Map.of(), List.of(), List.of());
        property = f.object("pp", List.of(), Map.of(), List.of(reference("gl", status)), List.of());
        stay = f.object("sy", List.of(), Map.of(), List.of(reference("wj", property)), List.of());
        room = f.object("rm", List.of(), Map.of(), List.of(reference("wj", property)), List.of());
        event =
                f.object(
                        "ev",
                        List.of(field("copied", "管理状态名", "TEXT")),
                        Map.of(),
                        List.of(reference("wj", property)),
                        List.of());
        management = relationField(property, "gl");
        app = f.app(status, property, stay, room, event);
        for (var name : List.of("民宿管理", "一般管理", "重复", "重复"))
            ids.put(name + ids.size(), f.save(app, status, values(id(status, "name"), name)).id());
        ids.put("民宿管理", ids.get("民宿管理0"));
        for (var row :
                List.of(
                        List.of("银座公寓", "民宿管理0"),
                        List.of("新宿大楼", "一般管理1"),
                        List.of("涩谷小屋", "民宿管理0")))
            ids.put(
                    row.get(0),
                    f.save(
                                    app,
                                    property,
                                    values(
                                            id(property, "name"),
                                            row.get(0),
                                            management,
                                            ids.get(row.get(1))))
                            .id());
        String shuku = ids.get("民宿管理"), ippan = ids.get("一般管理1"), dup = ids.get("重复2");
        stay =
                stored(
                        stay,
                        Map.of(
                                relationField(stay, "wj"),
                                filter(null, constant(management, "eq", shuku))),
                        Map.of(shuku, "民宿管理"));
        room =
                stored(
                        room,
                        Map.of(
                                relationField(room, "wj"),
                                filter(
                                        null,
                                        constant(management, "eq", shuku),
                                        constant(management, "neq", ippan))),
                        Map.of(shuku, "民宿管理"));
        event =
                stored(
                        event,
                        Map.of(
                                relationField(event, "wj"),
                                filter(
                                        null,
                                        constant(management, "eq", shuku),
                                        constant(management, "neq", dup)),
                                id(event, "copied"),
                                linkage(
                                        property,
                                        id(property, "name"),
                                        "FIRST",
                                        List.of(constant(management, "eq", ippan)))),
                        Map.of(shuku, "民宿管理", dup, "重复", ippan, "不存在的状态"));
    }

    /**
     * 造存量：先按正常设计路径把条件存成记录 ID 并发布（对象设计里与已发布快照里都有），再把这几个 ID 原地换成文本——模拟旧条件行自由文本框里填的名称。
     * 对象设计现在会拒绝文本固定值，所以只能这样造。
     */
    private DataCenter.Definition stored(
            DataCenter.Definition d, Map<String, FieldRules> rules, Map<String, String> texts) {
        var current = published(d.objectId());
        Map<String, DataCenter.FieldOptions> changes = new LinkedHashMap<>();
        rules.forEach(
                (fieldId, r) ->
                        changes.put(
                                fieldId,
                                current.fieldOptions()
                                        .getOrDefault(fieldId, DataCenter.FieldOptions.defaults())
                                        .withRules(r)));
        f.republish(current, changes);
        for (var e : texts.entrySet()) {
            String from = "\"value\": \"" + e.getKey() + "\"",
                    to = "\"value\": \"" + e.getValue() + "\"";
            jdbc.update(
                    "UPDATE public.nocode_field SET config_json = CAST(replace(config_json::text,"
                            + " ?, ?) AS jsonb) WHERE object_version_id IN (SELECT id FROM"
                            + " public.nocode_object_version WHERE object_id = ?)",
                    from,
                    to,
                    Long.parseLong(d.objectId()));
            jdbc.update(
                    "UPDATE public.nocode_object_version SET schema_json ="
                        + " CAST(replace(schema_json::text, ?, ?) AS jsonb) WHERE object_id = ?",
                    from,
                    to,
                    Long.parseLong(d.objectId()));
        }
        return published(d.objectId());
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private List<Row> ours(ReferenceConstantMigrationReport report) {
        var mine = Set.of(stay.objectId(), room.objectId(), event.objectId());
        return report.rows().stream().filter(r -> mine.contains(r.objectId())).toList();
    }

    private static Row row(
            List<Row> rows, String objectId, String fieldId, String rule, int index) {
        return rows.stream()
                .filter(
                        r ->
                                r.objectId().equals(objectId)
                                        && r.fieldId().equals(fieldId)
                                        && r.rule().equals(rule)
                                        && r.conditionIndex() == index)
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "报告里没有这一行：" + objectId + " " + fieldId + " " + index));
    }

    private ReferenceConstantMigrationReport onlyOurs(ReferenceConstantMigrationReport report) {
        var rows = ours(report);
        return new ReferenceConstantMigrationReport(
                report.tool(),
                report.formatVersion(),
                report.command(),
                OffsetDateTime.now().toString(),
                ReferenceConstantMigrationReport.summarize(rows),
                rows,
                report.objects());
    }

    private Object filterValue(DataCenter.Definition d, int index) {
        return published(d.objectId())
                .fieldOptions()
                .get(relationField(d, "wj"))
                .rules()
                .reference()
                .filter()
                .get(index)
                .value();
    }

    @Test
    void dryRunConvertsOnlyUniqueNames() {
        var rows = ours(tool.dryRun());
        assertThat(rows).hasSize(5);
        var stayRow = row(rows, stay.objectId(), relationField(stay, "wj"), "REFERENCE_FILTER", 0);
        assertThat(stayRow.status()).isEqualTo("CONVERT");
        assertThat(stayRow.before()).isEqualTo("民宿管理");
        assertThat(stayRow.after()).isEqualTo(ids.get("民宿管理"));
        assertThat(stayRow.targetObjectName()).isEqualTo(status.objectName());
        var roomRow = row(rows, room.objectId(), relationField(room, "wj"), "REFERENCE_FILTER", 0);
        assertThat(roomRow.status()).isEqualTo("CONVERT");
        assertThat(roomRow.after()).isEqualTo(ids.get("民宿管理"));
        var ambiguous =
                row(rows, event.objectId(), relationField(event, "wj"), "REFERENCE_FILTER", 1);
        assertThat(ambiguous.status()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.matches()).isEqualTo(2);
        assertThat(ambiguous.after()).isNull();
        var linkageRow = row(rows, event.objectId(), id(event, "copied"), "LINKAGE", 0);
        assertThat(linkageRow.status()).isEqualTo("NO_MATCH");
        assertThat(linkageRow.matches()).isZero();
        var blocked =
                row(rows, event.objectId(), relationField(event, "wj"), "REFERENCE_FILTER", 0);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.after()).isEqualTo(ids.get("民宿管理"));
        assertThat(blocked.note()).contains("「重复」").contains("「不存在的状态」");
    }

    @Test
    void applyPublishesConvertedValuesAndRollbackRestoresThem() {
        int stayBefore = versionOf(stay);
        int eventBefore = versionOf(event);
        var applied = tool.apply(onlyOurs(tool.dryRun()), 10001);
        var rows = ours(applied);
        assertThat(applied.objects())
                .as("发布结果")
                .allMatch(step -> "PUBLISHED".equals(step.action()), applied.objects().toString());
        assertThat(
                        row(rows, stay.objectId(), relationField(stay, "wj"), "REFERENCE_FILTER", 0)
                                .status())
                .isEqualTo("CONVERTED");
        assertThat(
                        row(rows, room.objectId(), relationField(room, "wj"), "REFERENCE_FILTER", 0)
                                .status())
                .isEqualTo("CONVERTED");
        assertThat(
                        row(
                                        rows,
                                        event.objectId(),
                                        relationField(event, "wj"),
                                        "REFERENCE_FILTER",
                                        0)
                                .status())
                .as("同一对象里还有不能转换的值：整个对象不动")
                .isEqualTo("BLOCKED");
        assertThat(filterValue(stay, 0)).isEqualTo(ids.get("民宿管理"));
        assertThat(filterValue(room, 0)).isEqualTo(ids.get("民宿管理"));
        assertThat(filterValue(room, 1)).as("已是记录 ID 的条件原样保留").isEqualTo(ids.get("一般管理1"));
        assertThat(filterValue(event, 0)).isEqualTo("民宿管理");
        assertThat(versionOf(stay)).isEqualTo(stayBefore + 1);
        assertThat(versionOf(event)).isEqualTo(eventBefore);
        assertThat(applied.objects()).noneMatch(s -> s.objectId().equals(event.objectId()));
        var step =
                applied.objects().stream()
                        .filter(s -> s.objectId().equals(stay.objectId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(step.action()).isEqualTo("PUBLISHED");
        // 应用默认开着自动跟随：对象发布时平台同步并发布了应用，不在「仍固定旧版本」里。
        assertThat(step.behind()).noneMatch(s -> s.contains(f.prefix()));
        var candidates =
                f.selection(
                        app,
                        published(stay.objectId()),
                        relationField(stay, "wj"),
                        Map.of(),
                        List.of());
        assertThat(candidates.ruleState()).isNull();
        assertThat(candidates.options().stream().map(SelectionFields.Option::label).sorted())
                .containsExactly("涩谷小屋", "银座公寓");

        // 再 dry-run 再 apply：值已是记录 ID，不再出现可转换的行，也不再发布。
        var again = ours(tool.apply(onlyOurs(tool.dryRun()), 10001));
        assertThat(again)
                .noneMatch(r -> "CONVERT".equals(r.status()) || "CONVERTED".equals(r.status()));

        var restored = ours(tool.rollback(onlyOurs(applied), 10001));
        assertThat(
                        row(
                                        restored,
                                        stay.objectId(),
                                        relationField(stay, "wj"),
                                        "REFERENCE_FILTER",
                                        0)
                                .status())
                .isEqualTo("RESTORED");
        assertThat(filterValue(stay, 0)).isEqualTo("民宿管理");
        assertThat(filterValue(room, 0)).isEqualTo("民宿管理");
    }

    private int versionOf(DataCenter.Definition d) {
        return servicesContext
                .getBean(DataObjectApi.class)
                .getVersion(d.objectId(), null)
                .versionNo();
    }

    @Test
    void rollbackSkipsRowsChangedAfterApply() {
        var applied = onlyOurs(tool.apply(onlyOurs(tool.dryRun()), 10001));
        // apply 之后有人把入住记录的条件改成了另一条记录：回滚不覆盖。
        var options =
                published(stay.objectId())
                        .fieldOptions()
                        .get(relationField(stay, "wj"))
                        .withRules(filter(null, constant(management, "eq", ids.get("一般管理1"))));
        f.republish(published(stay.objectId()), Map.of(relationField(stay, "wj"), options));
        var restored = ours(tool.rollback(applied, 10001));
        assertThat(
                        row(
                                        restored,
                                        stay.objectId(),
                                        relationField(stay, "wj"),
                                        "REFERENCE_FILTER",
                                        0)
                                .status())
                .isEqualTo("CONFLICT");
        assertThat(filterValue(stay, 0)).isEqualTo(ids.get("一般管理1"));
        assertThat(
                        row(
                                        restored,
                                        room.objectId(),
                                        relationField(room, "wj"),
                                        "REFERENCE_FILTER",
                                        0)
                                .status())
                .isEqualTo("RESTORED");
    }
}
