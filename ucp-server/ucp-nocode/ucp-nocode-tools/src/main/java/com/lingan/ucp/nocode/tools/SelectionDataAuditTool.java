package com.lingan.ucp.nocode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** 只读巡检当前发布对象中的必填空选择与单选字面量 []；不迁移或修改业务数据。 */
public class SelectionDataAuditTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("output.json");
        try (var context = NocodeToolContext.open()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var json = new ObjectMapper();
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.setReadOnly(true);
            tx.setTimeout(60);
            var report =
                    tx.execute(
                            status -> {
                                jdbc.execute("SET TRANSACTION READ ONLY");
                                var checks = new ArrayList<Map<String, Object>>();
                                var versions =
                                        jdbc.queryForList(
                                                """
SELECT o.id::text object_id,o.object_code,v.version_no,v.schema_json::text definition
FROM public.nocode_object o JOIN public.nocode_object_version v
  ON v.object_id=o.id AND v.version_no=o.current_published_version_no
WHERE o.deleted=0 AND v.deleted=0 ORDER BY o.id
""");
                                for (var version : versions) {
                                    JsonNode definition;
                                    try {
                                        definition =
                                                json.readTree((String) version.get("definition"));
                                    } catch (Exception e) {
                                        throw new IllegalStateException("发布快照无法解析", e);
                                    }
                                    var identity = new LinkedHashMap<String, Object>();
                                    identity.put("objectId", version.get("object_id"));
                                    identity.put("objectCode", version.get("object_code"));
                                    identity.put("versionNo", version.get("version_no"));
                                    scan(
                                            jdbc,
                                            definition,
                                            definition.path("mainBinding"),
                                            identity,
                                            checks);
                                    for (var detail : definition.path("details")) {
                                        var detailIdentity = new LinkedHashMap<>(identity);
                                        detailIdentity.put("detailId", detail.path("id").asText());
                                        scan(
                                                jdbc,
                                                detail,
                                                detail.path("binding"),
                                                detailIdentity,
                                                checks);
                                    }
                                }
                                return Map.of(
                                        "executedAt",
                                        OffsetDateTime.now().toString(),
                                        "readOnly",
                                        true,
                                        "publishedObjects",
                                        versions.size(),
                                        "checks",
                                        checks);
                            });
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
        }
    }

    private static void scan(
            JdbcTemplate jdbc,
            JsonNode definition,
            JsonNode binding,
            Map<String, Object> identity,
            List<Map<String, Object>> checks) {
        String schema =
                binding.path("schemaName").asText(definition.path("schemaName").asText("public"));
        String table = definition.path("tableName").asText();
        String key = binding.path("keyColumn").asText("id");
        var columns =
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema=?"
                                + " AND table_name=?",
                        String.class,
                        schema,
                        table);
        for (var field : definition.path("fields")) {
            String type = field.path("type").asText();
            if (!Set.of(
                            "SELECT",
                            "MULTI_SELECT",
                            "ORGANIZATION",
                            "DEPARTMENT",
                            "USER",
                            "POST",
                            "USER_GROUP",
                            "REFERENCE")
                    .contains(type)) continue;
            var option = definition.path("fieldOptions").path(field.path("id").asText());
            String column = option.path("columnName").asText(field.path("code").asText());
            var check = new LinkedHashMap<>(identity);
            check.put("table", schema + "." + table);
            check.put("fieldId", field.path("id").asText());
            check.put("fieldName", field.path("name").asText());
            check.put("column", column);
            check.put("required", field.path("required").asBoolean());
            if (!columns.contains(column) || !columns.contains(key)) {
                check.put("status", "physical_column_missing");
                checks.add(check);
                continue;
            }
            String from =
                    " FROM "
                            + quote(schema)
                            + "."
                            + quote(table)
                            + " t WHERE "
                            + (columns.contains("deleted") ? "deleted=0" : "TRUE");
            String value = "to_jsonb(t)->?";
            String invalid =
                    "MULTI_SELECT".equals(type)
                            ? (field.path("required").asBoolean()
                                    ? "("
                                            + value
                                            + " IS NULL OR "
                                            + value
                                            + "='null'::jsonb OR "
                                            + value
                                            + "='[]'::jsonb)"
                                    : "FALSE")
                            : value + "='\"[]\"'::jsonb";
            Object[] parameters =
                    "MULTI_SELECT".equals(type)
                            ? (field.path("required").asBoolean()
                                    ? new Object[] {column, column, column}
                                    : new Object[0])
                            : new Object[] {column};
            check.put("rows", jdbc.queryForObject("SELECT count(*)" + from, Long.class));
            check.put(
                    "affectedRecords",
                    jdbc.queryForList(
                            "SELECT "
                                    + quote(key)
                                    + "::text"
                                    + from
                                    + " AND "
                                    + invalid
                                    + " ORDER BY "
                                    + quote(key),
                            String.class,
                            parameters));
            check.put("status", "checked");
            checks.add(check);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
