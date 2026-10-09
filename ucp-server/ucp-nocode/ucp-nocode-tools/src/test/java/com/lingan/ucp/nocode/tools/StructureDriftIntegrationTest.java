package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.PublishCheckEnum;
import com.lingan.ucp.nocode.enums.PublishStateEnum;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.schema.service.reconcile.ObjectReconcileService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 用当前开发库的专属夹具验证跨 PostgreSQL 版本快照兼容及真实漂移保护。 */
class StructureDriftIntegrationTest {
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

    private PublishPlan plan(Design design) {
        return publisher.plan(
                new Revision(design.draft().id(), design.draft().lockVersion(), null), 10001);
    }

    private void publish(Design design) {
        var plan = plan(design);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "结构漂移回归验证"), 10001).state())
                .isEqualTo(PublishStateEnum.SUCCEEDED.getCode());
    }

    /** 模拟 PG18 导出的部署快照；只调整本测试创建对象的快照，不更改其实际表。 */
    private Design restoredObject() throws Exception {
        var created =
                designs.save(
                        new SaveDesign(fixture.createRequest(""), null, null, null, null, null),
                        10001);
        publish(created);
        var deployment = centerMapper.deployment(Long.parseLong(created.draft().id()));
        var snapshot = mapper.readTree(deployment.getStructureJson());
        addNotNullCatalog((ObjectNode) snapshot.path(created.draft().tableName()));
        String encoded = mapper.writeValueAsString(snapshot);
        jdbc.update(
                "UPDATE public.nocode_deployment SET structure_json=?::jsonb, structure_hash=?"
                        + " WHERE object_id=? AND version_no=?",
                encoded,
                cn.hutool.crypto.digest.DigestUtil.sha256Hex(encoded),
                deployment.getObjectId(),
                deployment.getVersionNo());
        return designs.get(created.draft().id());
    }

    private void addNotNullCatalog(ObjectNode table) {
        var constraints = (ArrayNode) table.path("constraints");
        for (var column : table.path("columns")) {
            if (column.path("nullable").asBoolean()) continue;
            String definition = "NOT NULL " + column.path("name").asText();
            boolean exists = false;
            for (var constraint : constraints)
                if (definition.equals(constraint.path("definition").asText())) exists = true;
            if (!exists)
                constraints
                        .addObject()
                        .put("name", "restored_" + column.path("name").asText() + "_not_null")
                        .put("kind", "n")
                        .put("definition", definition);
        }
    }

    private Design addOrganization(Design published) {
        var draft =
                designs.editPublished(
                        new Revision(
                                published.draft().id(),
                                published.draft().lockVersion(),
                                "验证新增所属组织"),
                        10001);
        return designs.save(
                new SaveDesign(
                        fixture.edit(
                                draft.draft(),
                                List.of(
                                        fixture.field(
                                                "new-org",
                                                "belong_org",
                                                FieldTypeEnum.ORGANIZATION.getCode(),
                                                1)),
                                List.of(),
                                draft.draft().titleFieldId()),
                        null,
                        null,
                        null,
                        null,
                        null),
                10001);
    }

    @Test
    void restoredBaselineAllowsDraftAndOrganizationPublishWithoutLosingRows() throws Exception {
        var restored = restoredObject();
        String table = restored.draft().tableName();
        jdbc.update("INSERT INTO public." + table + " (name) VALUES (?)", "保留的记录");
        assertThat(tables.drift(restored.draft().id())).isEmpty();
        assertThat(
                        servicesContext
                                .getBean(ObjectReconcileService.class)
                                .preview(restored.draft().id())
                                .checks())
                .noneMatch(Check::blocking);
        String baseline =
                centerMapper.deployment(Long.parseLong(restored.draft().id())).getStructureJson();
        var draft = addOrganization(restored);
        assertThat(databaseMetadata.readTable("public", table).orElseThrow().columns())
                .noneMatch(column -> column.name().equals("belong_org"));
        assertThat(
                        centerMapper
                                .deployment(Long.parseLong(restored.draft().id()))
                                .getStructureJson())
                .isEqualTo(baseline);
        publish(draft);
        assertThat(databaseMetadata.readTable("public", table).orElseThrow().columns())
                .anyMatch(column -> column.name().equals("belong_org") && column.nullable());
        assertThat(jdbc.queryForObject("SELECT name FROM public." + table, String.class))
                .isEqualTo("保留的记录");
        assertThat(tables.drift(restored.draft().id())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nullable", "check", "index"})
    void realChangesStillBlockRestoredBaseline(String change) throws Exception {
        var restored = restoredObject();
        String table = restored.draft().tableName();
        switch (change) {
            case "nullable" ->
                    jdbc.execute(
                            "ALTER TABLE public."
                                    + table
                                    + " ALTER COLUMN create_time DROP NOT NULL");
            case "check" ->
                    jdbc.execute(
                            "ALTER TABLE public."
                                    + table
                                    + " ADD CONSTRAINT drift_check CHECK (length(name) < 30)");
            case "index" ->
                    jdbc.execute("CREATE INDEX " + table + "_extra ON public." + table + " (name)");
            default -> throw new AssertionError(change);
        }
        assertThat(tables.drift(restored.draft().id()))
                .anyMatch(
                        check ->
                                check.blocking()
                                        && PublishCheckEnum.STRUCTURE_DRIFT.matches(check.code()));
        assertThat(
                        servicesContext
                                .getBean(ObjectReconcileService.class)
                                .preview(restored.draft().id())
                                .checks())
                .anyMatch(Check::blocking);
        assertThat(plan(addOrganization(restored)).state())
                .isEqualTo(PublishStateEnum.BLOCKED.getCode());
    }

    @Test
    void comparisonPreservesSpecialNotNullDefinitionsAndOriginalSnapshot() throws Exception {
        JsonNode original =
                mapper.readTree(
                        """
                        {"columns":[{"name":"Display Name","nullable":false}],"constraints":[]}
                        """);
        ObjectNode restored = original.deepCopy();
        ArrayNode constraints = (ArrayNode) restored.path("constraints");
        constraints
                .addObject()
                .put("kind", "n")
                .put("name", "named_constraint")
                .put("definition", "NOT NULL \"Display Name\"");
        assertThat(DataTableService.comparableStructure(restored))
                .isEqualTo(DataTableService.comparableStructure(original));
        assertThat(constraints.size()).isEqualTo(1);
        ((ObjectNode) constraints.get(0)).put("definition", "NOT NULL \"Display Name\" NOT VALID");
        assertThat(DataTableService.comparableStructure(restored))
                .isNotEqualTo(DataTableService.comparableStructure(original));
    }
}
