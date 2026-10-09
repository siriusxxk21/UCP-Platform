package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 草稿里删掉带编码的成员后，再用同一编码新建：同一草稿版本里被逻辑删除的旧行不能挡住新成员， 也不能以「系统异常」透给界面；发布仍按现有发布检查给出说人话的结论。仅清理本测试前缀的夹具。 */
class DraftRecreateSameCodeIntegrationTest {
    private static final String CODE = "c_rzfj";
    private NocodeIntegrationSupport fixture;

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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    // ---------- 关系：复用已有单值引用列（线上「入住记录 · 入住房间」的形态） ----------

    @Test
    void reusedColumnRelationDeletedThenRecreatedWithSameCodeInNextSave() {
        var oldTarget = published(create("old", List.of(), Map.of(), List.of(), List.of()));
        var newTarget = published(create("new", List.of(), Map.of(), List.of(), List.of()));
        var source = publishedReference("src", oldTarget);
        String oldRelationId = source.relations().getFirst().id();
        String roomId = field(source, "room").id();

        var draft = edit(source);
        // 第一次自动保存：界面移除关系时把原引用字段改回普通字段。
        var removed = retype(draft, "room", "INTEGER", List.of());
        assertThat(removed.relations()).isEmpty();
        assertThat(liveRelationRows(removed, CODE)).isZero();
        assertThat(deadRelationRows(removed, CODE)).isEqualTo(1);

        // 第二次自动保存：从字段抽屉重新配置同名、同编码的关系，改指向另一个对象。
        var recreated =
                retype(removed, "room", "REFERENCE", List.of(relation(null, newTarget, roomId)));

        assertThat(recreated.relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.code()).isEqualTo(CODE);
                            assertThat(r.targetObjectId()).isEqualTo(newTarget.draft().id());
                            assertThat(r.fieldId()).isEqualTo(roomId);
                            // 同一列上的同编码关系沿用已发布关系的稳定 ID：等同于在原关系上换目标。
                            assertThat(r.id()).isEqualTo(oldRelationId);
                        });
        assertThat(liveRelationRows(recreated, CODE)).isEqualTo(1);
        assertThat(deadRelationRows(recreated, CODE)).isZero();
        // 已发布版本里的旧关系原样保留，不被草稿保存改写。
        assertThat(designs.published(source.draft().id()).relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.id()).isEqualTo(oldRelationId);
                            assertThat(r.targetObjectId()).isEqualTo(oldTarget.draft().id());
                        });
    }

    @Test
    void reusedColumnRelationReplacedWithSameCodeInOneSave() {
        var oldTarget = published(create("old", List.of(), Map.of(), List.of(), List.of()));
        var newTarget = published(create("new", List.of(), Map.of(), List.of(), List.of()));
        var source = publishedReference("src", oldTarget);
        String oldRelationId = source.relations().getFirst().id();
        String roomId = field(source, "room").id();
        var draft = edit(source);

        var saved = save(draft, null, null, List.of(relation(null, newTarget, roomId)), null);

        assertThat(saved.relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.code()).isEqualTo(CODE);
                            assertThat(r.id()).isEqualTo(oldRelationId);
                            assertThat(r.targetObjectId()).isEqualTo(newTarget.draft().id());
                        });
        assertThat(liveRelationRows(saved, CODE)).isEqualTo(1);
        assertThat(deadRelationRows(saved, CODE)).isZero();
    }

    @Test
    void recreatedReusedColumnRelationPublishesOnlyAfterConfirmingOldValuesAreCleared() {
        var oldTarget = published(create("old", List.of(), Map.of(), List.of(), List.of()));
        var newTarget = published(create("new", List.of(), Map.of(), List.of(), List.of()));
        var source = publishedReference("src", oldTarget);
        String relationId = source.relations().getFirst().id();
        String roomId = field(source, "room").id();
        // 两个目标表的记录 ID 各自从 1 起：旧值恰好也是新目标里存在的 ID，不能被悄悄当成新目标的记录。
        Long oldRecord = insertName(oldTarget, "旧目标记录");
        insertName(newTarget, "新目标记录");
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,room) VALUES (?,?)",
                "入住记录",
                oldRecord);

        var draft = edit(source);
        var removed = retype(draft, "room", "INTEGER", List.of());
        var recreated =
                retype(removed, "room", "REFERENCE", List.of(relation(null, newTarget, roomId)));

        var plan = plan(recreated);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.fieldId()).isEqualTo(roomId);
                            assertThat(c.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(c.affectedRows()).isEqualTo(1);
                        });
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(plan.id(), "缺少清空确认"), 10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("待清空字段");
        var confirmed = plan(designs.get(source.draft().id()));
        assertThat(
                        publisher
                                .execute(
                                        new ExecutePlan(
                                                confirmed.id(), "删后同编码重建并清空旧引用", List.of(roomId)),
                                        10001)
                                .state())
                .isEqualTo("SUCCEEDED");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(room) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isZero();
        assertThat(
                        databaseMetadata
                                .readTable("public", source.draft().tableName())
                                .orElseThrow()
                                .constraints())
                .anyMatch(
                        c ->
                                c.name().equals("nocode_fk_r_" + relationId)
                                        && c.definition().contains(newTarget.draft().tableName()));
        assertThat(designs.published(source.draft().id()).relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.id()).isEqualTo(relationId);
                            assertThat(r.code()).isEqualTo(CODE);
                            assertThat(r.targetObjectId()).isEqualTo(newTarget.draft().id());
                        });
        // 再开草稿仍可正常保存（复制出的新草稿只带活动关系）。
        var next = edit(designs.get(source.draft().id()));
        assertThat(save(next, null, null, null, null).relations()).hasSize(1);
    }

    @Test
    void reusedColumnRelationRecreatedWithSameTargetRestoresTheOriginal() {
        var target = published(create("tgt", List.of(), Map.of(), List.of(), List.of()));
        var source = publishedReference("src", target);
        String relationId = source.relations().getFirst().id();
        String roomId = field(source, "room").id();
        Long record = insertName(target, "目标记录");
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,room) VALUES (?,?)",
                "入住记录",
                record);

        var removed = retype(edit(source), "room", "INTEGER", List.of());
        var restored =
                retype(removed, "room", "REFERENCE", List.of(relation(null, target, roomId)));
        assertThat(restored.relations().getFirst().id()).isEqualTo(relationId);

        var plan = plan(restored);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions()).isEmpty();
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "原关系恢复"), 10001).state())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT room FROM public.\"" + source.draft().tableName() + "\"",
                                Long.class))
                .isEqualTo(record);
    }

    // ---------- 关系：由关系生成引用列（c_rzfj_id） ----------

    @Test
    void generatedColumnRelationDeletedThenRecreatedWithSameTargetReusesItsRetiredColumn() {
        var target = published(create("tgt", List.of(), Map.of(), List.of(), List.of()));
        var source =
                published(
                        create(
                                "src",
                                List.of(),
                                Map.of(),
                                List.of(relation(null, target, null)),
                                List.of()));
        String oldRelationId = source.relations().getFirst().id();
        String generatedId = source.relations().getFirst().fieldId();
        assertThat(field(source, CODE + "_id").id()).isEqualTo(generatedId);

        var draft = edit(source);
        var removed = save(draft, null, null, List.of(), null);
        assertThat(removed.draft().fields()).noneMatch(f -> f.code().equals(CODE + "_id"));

        var recreated = save(removed, null, null, List.of(relation(null, target, null)), null);
        assertThat(recreated.relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.targetObjectId()).isEqualTo(target.draft().id());
                            // 同一张表里同编码的物理列只能有一个成员：沿用停用的原生成列。
                            assertThat(r.fieldId()).isEqualTo(generatedId);
                        });
        assertThat(field(recreated, CODE + "_id").id()).isEqualTo(generatedId);
        assertThat(fieldRows(recreated, CODE + "_id")).isEqualTo(1);
        assertThat(liveRelationRows(recreated, CODE)).isEqualTo(1);
        assertThat(deadRelationRows(recreated, CODE)).isZero();

        var plan = plan(recreated);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "生成列删后重建"), 10001).state())
                .isEqualTo("SUCCEEDED");
        String newRelationId = recreated.relations().getFirst().id();
        var constraints =
                databaseMetadata
                        .readTable("public", source.draft().tableName())
                        .orElseThrow()
                        .constraints();
        assertThat(constraints).anyMatch(c -> c.name().equals("nocode_fk_r_" + newRelationId));
        if (!newRelationId.equals(oldRelationId))
            assertThat(constraints).noneMatch(c -> c.name().equals("nocode_fk_r_" + oldRelationId));
    }

    @Test
    void generatedColumnRelationCannotSilentlyPointItsOldValuesAtAnotherObject() {
        var oldTarget = published(create("old", List.of(), Map.of(), List.of(), List.of()));
        var newTarget = published(create("new", List.of(), Map.of(), List.of(), List.of()));
        var source =
                published(
                        create(
                                "src",
                                List.of(),
                                Map.of(),
                                List.of(relation(null, oldTarget, null)),
                                List.of()));
        var removed = save(edit(source), null, null, List.of(), null);

        assertThatThrownBy(
                        () ->
                                save(
                                        removed,
                                        null,
                                        null,
                                        List.of(relation(null, newTarget, null)),
                                        null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(CODE + "_id")
                .hasMessageContaining("原目标对象");
        // 换一个关系编码即可新建指向新对象的关系。
        var renamed =
                save(
                        designs.get(source.draft().id()),
                        null,
                        null,
                        List.of(
                                new Relation(
                                        null,
                                        "c_rzfj2",
                                        "入住房间",
                                        "REFERENCE",
                                        newTarget.draft().id(),
                                        null,
                                        null,
                                        false,
                                        "RESTRICT")),
                        null);
        assertThat(renamed.relations())
                .singleElement()
                .satisfies(r -> assertThat(r.code()).isEqualTo("c_rzfj2"));
    }

    @Test
    void unpublishedGeneratedRelationReplacedWithSameCodeInOneSave() {
        var oldTarget = published(create("old", List.of(), Map.of(), List.of(), List.of()));
        var newTarget = published(create("new", List.of(), Map.of(), List.of(), List.of()));
        var source =
                create(
                        "src",
                        List.of(),
                        Map.of(),
                        List.of(relation(null, oldTarget, null)),
                        List.of());
        String generatedId = source.relations().getFirst().fieldId();

        var replaced = save(source, null, null, List.of(relation(null, newTarget, null)), null);

        assertThat(replaced.relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.targetObjectId()).isEqualTo(newTarget.draft().id());
                            assertThat(r.fieldId()).isEqualTo(generatedId);
                        });
        assertThat(fieldRows(replaced, CODE + "_id")).isEqualTo(1);
        assertThat(liveRelationRows(replaced, CODE)).isEqualTo(1);
        assertThat(deadRelationRows(replaced, CODE)).isZero();
        published(replaced);
    }

    @Test
    void generatedRelationCodeTakenByRetiredOrdinaryFieldIsReadable() {
        var target = published(create("tgt", List.of(), Map.of(), List.of(), List.of()));
        var source =
                published(
                        create(
                                "src",
                                List.of(fixture.field("legacy", CODE + "_id", "INTEGER", 1)),
                                Map.of(),
                                List.of(),
                                List.of()));
        var draft = edit(source);
        String legacyId = field(draft, CODE + "_id").id();
        var removed = save(draft, without(draft, legacyId), List.of(legacyId), null, null);

        assertThatThrownBy(
                        () ->
                                save(
                                        removed,
                                        null,
                                        null,
                                        List.of(relation(null, target, null)),
                                        null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(CODE + "_id")
                .hasMessageContaining("停用字段");
    }

    // ---------- 索引 ----------

    @Test
    void indexDeletedThenRecreatedWithSameCodeInNextSave() {
        var source =
                create(
                        "idx",
                        List.of(fixture.field("serial", "serial", "TEXT", 1)),
                        Map.of(),
                        List.of(),
                        List.of(new Index(null, "idx_serial", "编号索引", true, List.of("serial"))));
        String oldIndexId = source.indexes().getFirst().id();
        String serialId = field(source, "serial").id();

        var removed = save(source, null, null, null, List.of());
        assertThat(removed.indexes()).isEmpty();
        var recreated =
                save(
                        removed,
                        null,
                        null,
                        null,
                        List.of(new Index(null, "idx_serial", "编号索引", false, List.of(serialId))));
        assertThat(recreated.indexes())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.code()).isEqualTo("idx_serial");
                            assertThat(i.id()).isNotEqualTo(oldIndexId);
                            assertThat(i.unique()).isFalse();
                        });
        assertThat(indexRows(recreated, "idx_serial", 0)).isEqualTo(1);
        assertThat(indexRows(recreated, "idx_serial", 1)).isZero();
        published(recreated);
    }

    @Test
    void indexReplacedWithSameCodeInOneSaveAndPublished() {
        var source =
                published(
                        create(
                                "idx",
                                List.of(fixture.field("serial", "serial", "TEXT", 1)),
                                Map.of(),
                                List.of(),
                                List.of(
                                        new Index(
                                                null,
                                                "idx_serial",
                                                "编号索引",
                                                false,
                                                List.of("serial")))));
        String oldIndexId = source.indexes().getFirst().id();
        String serialId = field(source, "serial").id();
        var draft = edit(source);

        var replaced =
                save(
                        draft,
                        null,
                        null,
                        null,
                        List.of(new Index(null, "idx_serial", "编号索引", true, List.of(serialId))));
        String newIndexId = replaced.indexes().getFirst().id();
        assertThat(newIndexId).isNotEqualTo(oldIndexId);
        assertThat(indexRows(replaced, "idx_serial", 0)).isEqualTo(1);
        assertThat(indexRows(replaced, "idx_serial", 1)).isZero();
        published(replaced);
        var indexes =
                databaseMetadata
                        .readTable("public", source.draft().tableName())
                        .orElseThrow()
                        .indexes();
        assertThat(indexes).anyMatch(i -> i.name().equals("nocode_i_" + newIndexId));
        assertThat(indexes).noneMatch(i -> i.name().equals("nocode_i_" + oldIndexId));
    }

    // ---------- 字段与内部明细：既有设计已用业务报错拦住，这里钉住「不是系统异常」 ----------

    @Test
    void mainFieldDeletedThenRecreatedWithSameCodeIsReadable() {
        var source =
                published(
                        create(
                                "fld",
                                List.of(fixture.field("serial", "serial", "TEXT", 1)),
                                Map.of(),
                                List.of(),
                                List.of()));
        var draft = edit(source);
        String serialId = field(draft, "serial").id();
        var removed = save(draft, without(draft, serialId), List.of(serialId), null, null);
        var again = new ArrayList<>(removed.draft().fields());
        again.add(fixture.field("serial-again", "serial", "TEXT", 5));

        assertThatThrownBy(() -> save(removed, again, List.of(), null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("serial")
                .hasMessageContaining("停用字段");
    }

    @Test
    void detailFieldDeletedThenRecreatedWithSameCodeIsReadable() {
        var detail =
                new Detail(
                        null,
                        "entries",
                        "分录",
                        "biz_" + fixture.prefix + "_entries",
                        "ACTIVE",
                        List.of(
                                fixture.field("summary", "summary", "TEXT", 0),
                                fixture.field("memo", "memo", "TEXT", 1)),
                        Map.of(),
                        List.of());
        var source = create("dtl", List.of(), Map.of(), List.of(), List.of(), List.of(detail));
        var saved = source.details().getFirst();
        var kept = saved.fields().stream().filter(f -> !f.code().equals("memo")).toList();
        var removed =
                saveDetails(
                        source,
                        List.of(
                                new Detail(
                                        saved.id(),
                                        saved.code(),
                                        saved.name(),
                                        saved.tableName(),
                                        saved.state(),
                                        kept,
                                        optionsOf(saved.fieldOptions(), kept),
                                        List.of(),
                                        saved.binding())));
        var current = removed.details().getFirst();
        var again = new ArrayList<>(current.fields());
        again.add(fixture.field("memo-again", "memo", "TEXT", 5));

        assertThatThrownBy(
                        () ->
                                saveDetails(
                                        removed,
                                        List.of(
                                                new Detail(
                                                        current.id(),
                                                        current.code(),
                                                        current.name(),
                                                        current.tableName(),
                                                        current.state(),
                                                        again,
                                                        current.fieldOptions(),
                                                        List.of(),
                                                        current.binding()))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("memo")
                .hasMessageContaining("停用字段");
    }

    // ---------- 兜底：残余的唯一约束冲突说人话 ----------

    @Test
    void residualColumnCollisionIsReportedAsBusinessError() {
        var source =
                published(
                        create(
                                "col",
                                List.of(fixture.field("serial", "serial", "TEXT", 1)),
                                Map.of(),
                                List.of(),
                                List.of()));
        var draft = edit(source);
        var serial = field(draft, "serial");
        var fields =
                new ArrayList<>(
                        draft.draft().fields().stream()
                                .map(
                                        f ->
                                                f.id().equals(serial.id())
                                                        ? new FieldDefinition(
                                                                f.key(),
                                                                f.id(),
                                                                "serial_no",
                                                                f.name(),
                                                                f.type(),
                                                                f.length(),
                                                                f.precision(),
                                                                f.scale(),
                                                                f.required(),
                                                                f.unique(),
                                                                f.sort())
                                                        : f)
                                .toList());
        // 已发布字段改编码后仍占用原物理列 serial；再用 serial 新建字段会撞物理列唯一约束。
        fields.add(fixture.field("serial-new", "serial", "TEXT", 6));

        assertThatThrownBy(() -> save(draft, fields, List.of(), null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("serial")
                .hasMessageNotContaining("duplicate key")
                .hasMessageNotContaining("系统异常");
    }

    // ---------- 夹具 ----------

    private Relation relation(String id, Design target, String fieldId) {
        return new Relation(
                id,
                CODE,
                "入住房间",
                "REFERENCE",
                target.draft().id(),
                fieldId,
                null,
                false,
                "RESTRICT");
    }

    private Design create(
            String suffix,
            List<FieldDefinition> extra,
            Map<String, FieldOptions> options,
            List<Relation> relations,
            List<Index> indexes) {
        return create(suffix, extra, options, relations, indexes, List.of());
    }

    private Design create(
            String suffix,
            List<FieldDefinition> extra,
            Map<String, FieldOptions> options,
            List<Relation> relations,
            List<Index> indexes,
            List<Detail> details) {
        var request = fixture.createRequest(suffix);
        var fields = new ArrayList<>(request.fields());
        fields.addAll(extra);
        return designs.save(
                new SaveDesign(
                        new SaveObjectDraft(
                                null,
                                null,
                                request.objectCode(),
                                request.objectName(),
                                null,
                                request.tableName(),
                                request.titleFieldKey(),
                                fields,
                                List.of()),
                        Settings.defaults(),
                        options,
                        relations,
                        indexes,
                        details),
                10001);
    }

    /** 主表 room 字段先以整数发布，再按界面流程改为单值引用并建立关系 c_rzfj 后发布。 */
    private Design publishedReference(String suffix, Design target) {
        var source =
                published(
                        create(
                                suffix,
                                List.of(fixture.field("room", "room", "INTEGER", 1)),
                                Map.of(),
                                List.of(),
                                List.of()));
        String roomId = field(source, "room").id();
        var withRelation =
                retype(edit(source), "room", "REFERENCE", List.of(relation(null, target, roomId)));
        return published(withRelation);
    }

    private Design retype(Design current, String code, String type, List<Relation> relations) {
        var target = field(current, code);
        var fields =
                current.draft().fields().stream()
                        .map(
                                f ->
                                        f.id().equals(target.id())
                                                ? new FieldDefinition(
                                                        f.key(), f.id(), f.code(), f.name(), type,
                                                        null, null, null, false, false, f.sort())
                                                : f)
                        .toList();
        var options = new HashMap<>(current.fieldOptions());
        var before = options.get(target.id());
        options.put(
                target.id(),
                FieldOptions.copyOf(before)
                        .defaultValue(null)
                        .resolver(null)
                        .generated(false)
                        .build());
        return designs.save(
                new SaveDesign(
                        draftOf(current, fields, List.of()),
                        current.settings(),
                        options,
                        relations,
                        current.indexes(),
                        current.details()),
                10001);
    }

    private Design save(
            Design current,
            List<FieldDefinition> fields,
            List<String> removed,
            List<Relation> relations,
            List<Index> indexes) {
        return designs.save(
                new SaveDesign(
                        draftOf(
                                current,
                                fields == null ? current.draft().fields() : fields,
                                removed == null ? List.of() : removed),
                        current.settings(),
                        optionsOf(
                                current.fieldOptions(),
                                fields == null ? current.draft().fields() : fields),
                        relations == null ? current.relations() : relations,
                        indexes == null ? current.indexes() : indexes,
                        current.details()),
                10001);
    }

    private Design saveDetails(Design current, List<Detail> details) {
        return designs.save(
                new SaveDesign(
                        draftOf(current, current.draft().fields(), List.of()),
                        current.settings(),
                        current.fieldOptions(),
                        current.relations(),
                        current.indexes(),
                        details),
                10001);
    }

    private SaveObjectDraft draftOf(
            Design current, List<FieldDefinition> fields, List<String> removed) {
        var d = current.draft();
        return new SaveObjectDraft(
                d.id(),
                d.lockVersion(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.tableName(),
                d.titleFieldId(),
                fields,
                removed);
    }

    /** 只带仍在字段列表里的扩展配置；被删除字段的配置不再随请求提交。 */
    private Map<String, FieldOptions> optionsOf(
            Map<String, FieldOptions> options, List<FieldDefinition> fields) {
        var keys = new HashSet<String>();
        fields.forEach(
                f -> {
                    keys.add(f.key());
                    if (f.id() != null) keys.add(f.id());
                });
        var result = new HashMap<String, FieldOptions>();
        options.forEach(
                (k, v) -> {
                    if (keys.contains(k)) result.put(k, v);
                });
        return result;
    }

    private List<FieldDefinition> without(Design current, String id) {
        return current.draft().fields().stream().filter(f -> !f.id().equals(id)).toList();
    }

    private Design edit(Design published) {
        return designs.editPublished(
                new Revision(
                        published.draft().id(),
                        designs.get(published.draft().id()).draft().lockVersion(),
                        null),
                10001);
    }

    private PublishPlan plan(Design d) {
        return publisher.plan(new Revision(d.draft().id(), d.draft().lockVersion(), null), 10001);
    }

    private Design published(Design d) {
        var p = plan(d);
        assertThat(p.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(p.id(), "删后同编码重建验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return designs.get(d.draft().id());
    }

    private FieldDefinition field(Design d, String code) {
        return d.draft().fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("字段不存在：" + code));
    }

    private Long insertName(Design target, String name) {
        return jdbc.queryForObject(
                "INSERT INTO public.\""
                        + target.draft().tableName()
                        + "\" (name) VALUES (?) RETURNING id",
                Long.class,
                name);
    }

    private long versionId(Design d) {
        return jdbc.queryForObject(
                "SELECT v.id FROM public.nocode_object_version v JOIN public.nocode_object o ON"
                        + " o.id=v.object_id AND o.latest_version_no=v.version_no WHERE o.id=?",
                Long.class,
                Long.parseLong(d.draft().id()));
    }

    private int liveRelationRows(Design d, String code) {
        return relationRows(d, code, 0);
    }

    private int deadRelationRows(Design d, String code) {
        return relationRows(d, code, 1);
    }

    private int relationRows(Design d, String code, int deleted) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_relation WHERE object_version_id=? AND"
                        + " relation_code=? AND deleted=?",
                Integer.class,
                versionId(d),
                code,
                deleted);
    }

    private int indexRows(Design d, String code, int deleted) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_index_definition WHERE object_version_id=? AND"
                        + " index_code=? AND deleted=?",
                Integer.class,
                versionId(d),
                code,
                deleted);
    }

    private int fieldRows(Design d, String code) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_field WHERE object_version_id=? AND"
                        + " field_code=?",
                Integer.class,
                versionId(d),
                code);
    }
}
