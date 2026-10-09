package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库的字段恢复回归；所有对象和物理行均由本测试前缀创建并清理。 */
class FieldRestoreIntegrationTest {
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

    private Design create(boolean constrained) {
        var base = fixture.createRequest("");
        var fields = new ArrayList<>(base.fields());
        fields.add(
                new FieldDefinition(
                        "memo",
                        null,
                        "memo",
                        "备注",
                        "TEXT",
                        100,
                        null,
                        null,
                        constrained,
                        constrained,
                        1));
        var input =
                new SaveObjectDraft(
                        null,
                        null,
                        base.objectCode(),
                        base.objectName(),
                        null,
                        base.tableName(),
                        base.titleFieldKey(),
                        fields,
                        List.of());
        var options =
                new FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        "保留原配置",
                        "^[a-z]+$",
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        var detail =
                new Detail(
                        null,
                        "items",
                        "条目",
                        base.tableName() + "_items",
                        "ACTIVE",
                        List.of(
                                fixture.field("item_name", "item_name", "TEXT", 0),
                                fixture.field("item_memo", "item_memo", "TEXT", 1)),
                        Map.of(),
                        List.of());
        return designs.save(
                new SaveDesign(
                        input,
                        Settings.defaults(),
                        Map.of("memo", options),
                        List.of(),
                        List.of(),
                        List.of(detail)),
                10001);
    }

    private Design editable(Design design) {
        var head = designs.get(design.draft().id());
        return designs.editPublished(
                new Revision(head.draft().id(), head.draft().lockVersion(), null), 10001);
    }

    private PublishPlan plan(Design design) {
        return publisher.plan(
                new Revision(design.draft().id(), design.draft().lockVersion(), null), 10001);
    }

    private void publish(Design design) {
        var plan = plan(design);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "字段恢复集成验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
    }

    private FieldDefinition memo(Design design) {
        return design.draft().fields().stream()
                .filter(f -> f.code().equals("memo"))
                .findFirst()
                .orElseThrow();
    }

    private Design deactivate(Design design) {
        var detail = design.details().getFirst();
        var changedDetail =
                new Detail(
                        detail.id(),
                        detail.code(),
                        detail.name(),
                        detail.tableName(),
                        detail.state(),
                        List.of(detail.fields().getFirst()),
                        Map.of(),
                        List.of(),
                        detail.binding());
        return designs.save(
                new SaveDesign(
                        fixture.edit(
                                design.draft(),
                                List.of(),
                                List.of(memo(design).id()),
                                design.draft().titleFieldId()),
                        design.settings(),
                        null,
                        design.relations(),
                        List.of(),
                        List.of(changedDetail)),
                10001);
    }

    private SaveDesign restoreRequest(Design design, List<String> restored) {
        var main = designs.inactiveFields(design.draft().id(), null).getFirst();
        var detail = design.details().getFirst();
        var inactive = designs.inactiveFields(design.draft().id(), detail.id()).getFirst();
        var fields = new ArrayList<>(detail.fields());
        fields.add(inactive.field());
        var changed =
                new Detail(
                        detail.id(),
                        detail.code(),
                        detail.name(),
                        detail.tableName(),
                        detail.state(),
                        fields,
                        null,
                        detail.indexes(),
                        detail.binding());
        return new SaveDesign(
                fixture.edit(
                        design.draft(),
                        List.of(main.field()),
                        List.of(),
                        design.draft().titleFieldId()),
                design.settings(),
                null,
                design.relations(),
                design.indexes(),
                List.of(changed),
                design.mainBinding(),
                restored);
    }

    private List<String> inactiveIds(Design d) {
        return List.of(
                designs.inactiveFields(d.draft().id(), null).getFirst().field().id(),
                designs.inactiveFields(d.draft().id(), d.details().getFirst().id())
                        .getFirst()
                        .field()
                        .id());
    }

    @Test
    void mainAndDetailRestoreAcrossPublishedVersionsPreservesDataIdentityAndOptions() {
        var created = create(false);
        publish(created);
        String mainId = memo(created).id(),
                detailId = created.details().getFirst().fields().getLast().id();
        var detail = created.details().getFirst();
        jdbc.update(
                "INSERT INTO public."
                        + created.draft().tableName()
                        + " (name,memo) VALUES ('existing','kept')");
        jdbc.update(
                "INSERT INTO public."
                        + detail.tableName()
                        + " (parent_id,item_name,item_memo) SELECT id,'line','detail-kept' FROM"
                        + " public."
                        + created.draft().tableName());
        for (int cycle = 0; cycle < 2; cycle++) {
            var disabled = deactivate(editable(created));
            assertThat(disabled.draft().fields()).noneMatch(f -> mainId.equals(f.id()));
            assertThat(designs.inactiveFields(disabled.draft().id(), null))
                    .singleElement()
                    .satisfies(
                            f -> {
                                assertThat(f.restorable()).isTrue();
                                assertThat(f.options().description()).isEqualTo("保留原配置");
                                assertThat(f.options().columnName()).isEqualTo("memo");
                            });
            publish(disabled);
            var draft = editable(disabled);
            var restored = designs.save(restoreRequest(draft, inactiveIds(draft)), 10001);
            assertThat(memo(restored).id()).isEqualTo(mainId);
            assertThat(restored.details().getFirst().fields())
                    .anyMatch(f -> f.id().equals(detailId));
            assertThat(restored.fieldOptions().get(mainId).description()).isEqualTo("保留原配置");
            assertThat(restored.fieldOptions().get(mainId).state()).isEqualTo("ACTIVE");
            assertThat(designs.published(restored.draft().id()).fields())
                    .noneMatch(f -> mainId.equals(f.id()));
            assertThat(plan(restored).changes())
                    .filteredOn(s -> s.kind().equals("RESTORE_FIELD"))
                    .hasSize(2);
            publish(restored);
            assertThat(
                            databaseMetadata
                                    .readTable("public", restored.draft().tableName())
                                    .orElseThrow()
                                    .constraints())
                    .anyMatch(c -> c.name().equals("nocode_c_" + mainId + "_2"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT memo FROM public." + created.draft().tableName(),
                                    String.class))
                    .isEqualTo("kept");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT item_memo FROM public." + detail.tableName(),
                                    String.class))
                    .isEqualTo("detail-kept");
            assertThat(designs.inactiveFields(restored.draft().id(), null)).isEmpty();
        }
    }

    @Test
    void restorationRejectsImplicitForeignAndConflictingIdsWithoutChangingDraft() {
        var disabled = deactivate(create(false));
        int revision = disabled.draft().lockVersion();
        var ids = inactiveIds(disabled);
        assertThatThrownBy(() -> designs.save(restoreRequest(disabled, List.of()), 10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("显式恢复");
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        restoreRequest(
                                                disabled, List.of(ids.getFirst(), "999999999999")),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("显式恢复");
        assertThatThrownBy(() -> designs.inactiveFields(disabled.draft().id(), "999999999999"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("明细不属于");
        var duplicate =
                new SaveDesign(
                        fixture.edit(
                                disabled.draft(),
                                List.of(fixture.field("replacement", "memo", "TEXT", 2)),
                                List.of(),
                                disabled.draft().titleFieldId()),
                        disabled.settings(),
                        null,
                        null,
                        null,
                        null);
        assertThatThrownBy(() -> designs.save(duplicate, 10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("编码已被停用字段");
        assertThat(designs.get(disabled.draft().id()).draft().lockVersion()).isEqualTo(revision);
        assertThat(designs.inactiveFields(disabled.draft().id(), null)).hasSize(1);
        var validRequest = restoreRequest(disabled, ids);
        var detail = validRequest.details().getFirst();
        var inactiveDetail =
                new Detail(
                        detail.id(),
                        detail.code(),
                        detail.name(),
                        detail.tableName(),
                        "INACTIVE",
                        detail.fields(),
                        detail.fieldOptions(),
                        detail.indexes(),
                        detail.binding());
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                validRequest.draft(),
                                                validRequest.settings(),
                                                validRequest.fieldOptions(),
                                                validRequest.relations(),
                                                validRequest.indexes(),
                                                List.of(inactiveDetail),
                                                validRequest.mainBinding(),
                                                ids),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("请先启用内部明细");
        var restored = designs.save(validRequest, 10001);
        assertThatThrownBy(() -> designs.save(validRequest, 10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("草稿已被");
        assertThat(restored.draft().lockVersion()).isEqualTo(revision + 1);
    }

    @Test
    void missingRetainedColumnIsBlockedInsteadOfRecreated() {
        var created = create(false);
        publish(created);
        var disabled = deactivate(editable(created));
        publish(disabled);
        var draft = editable(disabled);
        var restored = designs.save(restoreRequest(draft, inactiveIds(draft)), 10001);
        // 只破坏本测试自有列，用于验证外部结构漂移不能伪装成成功恢复。
        jdbc.execute("ALTER TABLE public." + restored.draft().tableName() + " DROP COLUMN memo");
        var compilation =
                servicesContext
                        .getBean(com.richuang.os.nocode.schema.service.compile.SchemaCompiler.class)
                        .compile(
                                designs.definition(restored.draft().id()),
                                designs.published(restored.draft().id()));
        assertThat(compilation.checks())
                .anyMatch(
                        c ->
                                c.blocking()
                                        && c.code().equals("COLUMN_IDENTITY")
                                        && c.message().contains("原物理列已丢失"));
        assertThat(compilation.changes()).noneMatch(c -> c.kind().equals("ADD_COLUMN"));
    }

    @Test
    void restoredRequiredAndUniqueConstraintsCheckRowsWrittenWhileInactive() {
        var created = create(true);
        publish(created);
        jdbc.update(
                "INSERT INTO public."
                        + created.draft().tableName()
                        + " (name,memo) VALUES ('first','same')");
        var disabled = deactivate(editable(created));
        publish(disabled);
        jdbc.update(
                "INSERT INTO public."
                        + created.draft().tableName()
                        + " (name,memo) VALUES ('second','same'),('third',NULL)");
        var draft = editable(disabled);
        var restored = designs.save(restoreRequest(draft, inactiveIds(draft)), 10001);
        assertThat(plan(restored).checks())
                .anyMatch(c -> c.blocking() && c.code().equals("NULL_VALUES"))
                .anyMatch(c -> c.blocking() && c.code().equals("DUPLICATES"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public." + created.draft().tableName(),
                                Integer.class))
                .isEqualTo(3);
    }
}
