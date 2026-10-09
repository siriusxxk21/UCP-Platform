package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/** 在当前库的会话临时表中验证迁移，不能触碰现有应用或发布版本。 */
class PaginationMigrationTest {
    @Test
    void normalizesDraftAndPublishedSeparatelyWithoutChangingHistoryAndIsIdempotent()
            throws Exception {
        var json = new ObjectMapper();
        var draft =
                json.readTree(
                        """
{"objects":[{"objectId":"fixture-object","versionNo":2}],"resources":[
  {"id":"list","kind":"VIEW","name":"未发布名称","config":{"pageSize":50,"fieldIds":["title"],"equal":{"active":true}}},
  {"id":"report","kind":"REPORT","config":{"limit":30}}
]}
""");
        var snapshot =
                json.readTree(
                        """
{"code":"fixture","name":"已发布名称","definition":{"objects":[{"objectId":"fixture-object","versionNo":1}],"resources":[
  {"id":"list","kind":"VIEW","name":"发布列表","config":{"pageSize":20,"fieldIds":["title"],"equal":{"active":false}}},
  {"id":"report","kind":"REPORT","config":{"limit":30}}
]}}
""");
        var expectedDraft = draft.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedDraft.at("/resources/0/config"))
                .put("pageSize", 10);
        var expectedSnapshot = snapshot.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        expectedSnapshot.at("/definition/resources/0/config"))
                .put("pageSize", 10);
        String script =
                new ClassPathResource("db/nocode/V013__default_view_page_size.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.nocode_application_version", "pg_temp.pagination_versions")
                        .replace("public.nocode_application", "pg_temp.pagination_apps");
        try (var context = NocodeToolContext.open()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(
                            status -> {
                                status.setRollbackOnly();
                                jdbc.execute(
                                        "CREATE TEMP TABLE pagination_apps (LIKE"
                                            + " public.nocode_application INCLUDING ALL) ON COMMIT"
                                            + " DROP");
                                jdbc.execute(
                                        "CREATE TEMP TABLE pagination_versions (LIKE"
                                            + " public.nocode_application_version INCLUDING ALL) ON"
                                            + " COMMIT DROP");
                                jdbc.update(
                                        "INSERT INTO"
                                            + " pagination_apps(id,app_code,app_name,design_json,published_version)"
                                            + " VALUES"
                                            + " (1,'pagination_fixture','fixture',?::jsonb,1),(2,'pagination_unpublished','fixture',?::jsonb,NULL),(3,'pagination_unchanged','fixture',?::jsonb,NULL)",
                                        draft.toString(),
                                        draft.toString(),
                                        expectedDraft.toString());
                                jdbc.update(
                                        "INSERT INTO"
                                            + " pagination_versions(application_id,version_no,definition_json,checksum,reason)"
                                            + " VALUES (1,1,?::jsonb,?,'original')",
                                        snapshot.toString(),
                                        "a".repeat(64));
                                jdbc.execute(script);
                                assertThat(
                                                read(
                                                        jdbc,
                                                        json,
                                                        "SELECT design_json::text FROM"
                                                                + " pagination_apps WHERE id=1"))
                                        .isEqualTo(expectedDraft);
                                assertThat(
                                                read(
                                                        jdbc,
                                                        json,
                                                        "SELECT definition_json::text FROM"
                                                            + " pagination_versions WHERE"
                                                            + " application_id=1 AND version_no=2"))
                                        .isEqualTo(expectedSnapshot);
                                assertThat(
                                                read(
                                                        jdbc,
                                                        json,
                                                        "SELECT definition_json::text FROM"
                                                            + " pagination_versions WHERE"
                                                            + " application_id=1 AND version_no=1"))
                                        .isEqualTo(snapshot);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT checksum FROM pagination_versions"
                                                                + " WHERE version_no=1",
                                                        String.class))
                                        .isEqualTo("a".repeat(64));
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT"
                                                            + " checksum=encode(sha256(convert_to(definition_json::text,'UTF8')),'hex')"
                                                            + " FROM pagination_versions WHERE"
                                                            + " version_no=2",
                                                        Boolean.class))
                                        .isTrue();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT published_version FROM"
                                                                + " pagination_apps WHERE id=1",
                                                        Integer.class))
                                        .isEqualTo(2);
                                assertThat(
                                                read(
                                                        jdbc,
                                                        json,
                                                        "SELECT design_json::text FROM"
                                                                + " pagination_apps WHERE id=2"))
                                        .isEqualTo(expectedDraft);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT published_version FROM"
                                                                + " pagination_apps WHERE id=2",
                                                        Integer.class))
                                        .isNull();
                                assertThat(
                                                jdbc.queryForList(
                                                        "SELECT lock_version FROM pagination_apps"
                                                                + " ORDER BY id",
                                                        Integer.class))
                                        .containsExactly(1, 1, 0);
                                jdbc.execute(script);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM pagination_versions",
                                                        Integer.class))
                                        .isEqualTo(2);
                                assertThat(
                                                jdbc.queryForList(
                                                        "SELECT lock_version FROM pagination_apps"
                                                                + " ORDER BY id",
                                                        Integer.class))
                                        .containsExactly(1, 1, 0);
                            });
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode read(
            JdbcTemplate jdbc, ObjectMapper json, String sql) {
        try {
            return json.readTree(jdbc.queryForObject(sql, String.class));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
