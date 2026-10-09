package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 选项类默认值清理的 DDL 落地（设计稿 6.1 末条、B33 的 ddlDropsDefault，配合路 A）：历史版本在选项列上留有数据库 DEFAULT，
 * 新版本清掉默认值并发布后，物理列上的 DEFAULT 被删除。历史状态用 SQL 构造，不依赖设计保存是否仍允许选项默认值。
 */
class OptionDefaultDdlIntegrationTest {
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

    private static DataCenter.FieldOptions options(String defaultValue) {
        return new DataCenter.FieldOptions(
                null,
                null,
                defaultValue,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(
                        new DataCenter.Option("a", "甲", false),
                        new DataCenter.Option("b", "乙", false)),
                null,
                null,
                "NONE",
                null,
                false,
                false);
    }

    private String columnDefault(String table, String column) {
        return jdbc.queryForList(
                        "SELECT column_default FROM information_schema.columns WHERE"
                                + " table_schema='public' AND table_name=? AND column_name=?",
                        String.class,
                        table,
                        column)
                .getFirst();
    }

    private void publish(DataCenter.Design design) {
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "选项默认值清理"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void ddlDropsDefault() throws Exception {
        var request = fixture.createRequest("option");
        var fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("status", "status", "SELECT", 1));
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
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
                                DataCenter.Settings.defaults(),
                                Map.of("status", options(null)),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        publish(design);
        long objectId = Long.parseLong(design.draft().id());
        var v1 = servicesContext.getBean(DataObjectApi.class).getPublished(design.draft().id());
        var status =
                v1.fields().stream()
                        .filter(f -> f.code().equals("status"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        String column = v1.fieldOptions().get(status).columnName();
        String table = v1.tableName();
        // 构造历史状态：已发布版本快照、字段扩展属性与物理列都带选项默认值 'a'。
        jdbc.execute(
                "ALTER TABLE public.\""
                        + table
                        + "\" ALTER COLUMN \""
                        + column
                        + "\" SET DEFAULT 'a'");
        jdbc.update(
                "UPDATE public.nocode_object_version SET schema_json=jsonb_set(schema_json,"
                        + " ARRAY['fieldOptions',?,'defaultValue'], to_jsonb('a'::text)) WHERE"
                        + " object_id=? AND version_no=1",
                status,
                objectId);
        jdbc.update(
                "UPDATE public.nocode_field SET config_json=jsonb_set(config_json,"
                        + " '{options,defaultValue}', to_jsonb('a'::text)) WHERE object_version_id="
                        + "(SELECT id FROM public.nocode_object_version WHERE object_id=? AND"
                        + " version_no=1) AND stable_field_id=?",
                objectId,
                Long.parseLong(status));
        assertThat(columnDefault(table, column)).isNotNull();
        // 历史版本发布时就带 DEFAULT：发布基线快照同步为当前物理结构，否则会被当作外部漂移拦截。
        var structure =
                com.lingan.ucp.nocode.metadata.service.table.DataTableService.structure(
                        databaseMetadata.readTable("public", table).orElseThrow());
        var baseline =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT structure_json::text FROM public.nocode_deployment"
                                                + " WHERE object_id=? AND version_no=1",
                                        String.class,
                                        objectId));
        List<String> keys = new ArrayList<>();
        baseline.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).anyMatch(key -> key.endsWith(table));
        for (String key : keys)
            if (key.endsWith(table)) baseline.set(key, mapper.valueToTree(structure));
        jdbc.update(
                "UPDATE public.nocode_deployment SET structure_json=CAST(? AS jsonb) WHERE"
                        + " object_id=? AND version_no=1",
                mapper.writeValueAsString(baseline),
                objectId);
        var current = designs.get(design.draft().id());
        var draft =
                designs.editPublished(
                        new DataCenter.Revision(
                                current.draft().id(), current.draft().lockVersion(), null),
                        10001);
        var cleared =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        draft.draft(),
                                        List.of(),
                                        List.of(),
                                        draft.draft().titleFieldId()),
                                null,
                                Map.of(status, options(null)),
                                null,
                                null,
                                null),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                cleared.draft().id(), cleared.draft().lockVersion(), null),
                        10001);
        assertThat(plan.changes()).anyMatch(step -> "DEFAULT".equals(step.kind()));
        publish(cleared);
        assertThat(columnDefault(table, column)).isNull();
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(design.draft().id())
                                .fieldOptions()
                                .get(status)
                                .defaultValue())
                .isNull();
    }
}
