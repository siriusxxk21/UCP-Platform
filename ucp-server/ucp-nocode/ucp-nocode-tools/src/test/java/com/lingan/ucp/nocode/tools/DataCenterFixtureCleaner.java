package com.lingan.ucp.nocode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.*;

/**
 * 数据中心浏览器验收专用清理器。默认只检查；execute 仅清理清单逐项核验的资源，禁止前缀删除与级联扩大。 复用正式 Spring 数据源装配，任一身份、外部依赖或物理所有权不符时整批停止。
 */
public final class DataCenterFixtureCleaner {
    private DataCenterFixtureCleaner() {}

    public record OwnedObject(String id, String code, String name, String tableName) {}

    public record OwnedApplication(String id, String code, String name) {}

    public record Manifest(
            String batchPrefix, List<OwnedObject> objects, List<OwnedApplication> applications) {}

    /** 参数：manifest.json [inspect|execute|verify-conversion]；省略第二参数始终只读检查。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 1
                || args.length > 2
                || args.length == 2
                        && !Set.of("inspect", "execute", "verify-conversion").contains(args[1]))
            throw new IllegalArgumentException("manifest.json [inspect|execute|verify-conversion]");
        Manifest manifest = new ObjectMapper().readValue(Path.of(args[0]).toFile(), Manifest.class);
        validateManifest(manifest);
        boolean execute = args.length == 2 && "execute".equals(args[1]);
        boolean verifyConversion = args.length == 2 && "verify-conversion".equals(args[1]);
        Map<String, Object> evidence = new LinkedHashMap<>();
        try (ConfigurableApplicationContext context = NocodeToolContext.open()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.setReadOnly(verifyConversion);
            transaction.executeWithoutResult(
                    status -> {
                        jdbc.execute("SET LOCAL lock_timeout='5s'");
                        Map<String, Object> parameters = parameters(manifest);
                        verifyIdentities(jdbc, manifest, !verifyConversion);
                        if (verifyConversion) {
                            evidence.putAll(verifyConversion(jdbc, manifest));
                            return;
                        }
                        Set<String> tables = verifyTables(named, parameters, manifest);
                        verifyReferences(named, parameters);
                        verifyPhysicalDependencies(jdbc, tables);
                        System.out.println(
                                "已核验批次 "
                                        + manifest.batchPrefix()
                                        + "：对象 "
                                        + parameters.get("objects")
                                        + "，应用 "
                                        + parameters.get("applications")
                                        + "，专属表 "
                                        + tables);
                        if (execute) {
                            remove(named, jdbc, parameters, tables);
                        } else {
                            status.setRollbackOnly();
                            System.out.println("inspect：未删除数据。执行须明确追加 execute。");
                        }
                    });
            if (execute) System.out.println("本清单资源已提交清理；未扩大清理范围。");
            if (verifyConversion) {
                Path output =
                        Path.of(args[0])
                                .toAbsolutePath()
                                .getParent()
                                .resolve("conversion-database-verification.json");
                new ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValue(output.toFile(), evidence);
                System.out.println("转换只读核验通过：记录 ID 1/2 保留，仅 choice 清空，其他列保持预期；证据 " + output);
            }
        }
    }

    /** 已核验清单身份后仅取自有测试行；证据包含真实主键，避免表格预览的系统列遮蔽。 */
    private static Map<String, Object> verifyConversion(JdbcTemplate jdbc, Manifest manifest) {
        OwnedObject source =
                manifest.objects().stream()
                        .filter(o -> o.code().equals(manifest.batchPrefix() + "source"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("清单缺少转换来源对象"));
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id,name,choice,keep_value,empty_value FROM "
                                + PostgreSqlCommands.table("public", source.tableName())
                                + " ORDER BY id");
        require(rows.size() == 2, "转换后记录数量不等于 2");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            require(((Number) row.get("id")).longValue() == i + 1, "转换改变了记录 ID");
            require(row.get("choice") == null, "待转换列仍有历史值");
            require((i == 0 ? "保留甲" : "保留乙").equals(row.get("keep_value")), "其他列值被改变");
            require(row.get("empty_value") == null, "原空列数据被改变");
            require(
                    row.get("name") != null
                            && row.get("name").toString().contains(i == 0 ? "第一条" : "第二条"),
                    "名称与原记录不匹配");
        }
        String logicalType =
                jdbc.queryForObject(
                        "SELECT f.data_type FROM public.nocode_field f JOIN"
                                + " public.nocode_object_version v ON v.id=f.object_version_id JOIN"
                                + " public.nocode_object o ON o.id=v.object_id AND"
                                + " o.current_published_version_no=v.version_no WHERE o.id=? AND"
                                + " f.column_name='choice' AND f.deleted=0",
                        String.class,
                        Long.valueOf(source.id()));
        require("REFERENCE".equals(logicalType), "发布字段类型并非 REFERENCE");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verifiedAt", java.time.Instant.now().toString());
        result.put("mode", "READ_ONLY");
        result.put("manifest", manifest);
        result.put("sourceObjectId", source.id());
        result.put("sourceTable", "public." + source.tableName());
        result.put("publishedChoiceType", logicalType);
        result.put("rowCount", rows.size());
        result.put("rows", rows);
        result.put(
                "checks",
                List.of(
                        "ids_1_2_preserved",
                        "choice_only_cleared",
                        "keep_value_preserved",
                        "empty_value_stays_null",
                        "names_preserved"));
        return result;
    }

    static void validateManifest(Manifest manifest) {
        require(
                manifest != null
                        && manifest.batchPrefix() != null
                        && manifest.batchPrefix().matches("e2e(?:fc|fsm|od)[a-z0-9]{8,16}_"),
                "不是数据中心专用验收批次前缀");
        require(manifest.objects() != null && manifest.applications() != null, "清单列表必须显式提供");
        require(!manifest.objects().isEmpty(), "清单必须包含本批次对象");
        String batch = manifest.batchPrefix().substring(0, manifest.batchPrefix().length() - 1);
        boolean modal = batch.startsWith("e2efsm");
        boolean grid = batch.startsWith("e2eod");
        Set<String> ids = new HashSet<>(), codes = new HashSet<>();
        for (OwnedObject object : manifest.objects()) {
            validId(object.id());
            require(ids.add(object.id()) && codes.add(object.code()), "对象身份重复");
            boolean target =
                    (manifest.batchPrefix() + (grid ? "supplier" : "target")).equals(object.code());
            boolean source =
                    (manifest.batchPrefix() + (grid ? "orders" : "source")).equals(object.code());
            require(target || source, "对象编码不属于该批次的 source/target");
            require(("biz_" + object.code()).equals(object.tableName()), "对象表名与清单编码不一致");
            String expectedName =
                    modal
                            ? (target ? "表单验收引用目标" : "表单验收字段弹窗")
                            : ((grid
                                            ? (target ? "表单验收数据维护供应商 " : "表单验收数据维护订单 ")
                                            : (target ? "表单验收转换目标 " : "表单验收字段转换 "))
                                    + batch);
            require(expectedName.equals(object.name()), "对象名称不属于专用验收夹具");
        }
        require(!(modal || grid) || manifest.applications().isEmpty(), "对象维护与弹窗夹具不能含应用");
        boolean upgrade =
                manifest.applications().stream()
                        .anyMatch(
                                app ->
                                        app.code().equals(manifest.batchPrefix() + "app_a")
                                                || app.code()
                                                        .equals(manifest.batchPrefix() + "app_b"));
        require(manifest.applications().size() <= (upgrade ? 2 : 1), "专用夹具应用数量超限");
        Set<String> appIds = new HashSet<>(), appCodes = new HashSet<>();
        for (OwnedApplication application : manifest.applications()) {
            validId(application.id());
            require(appIds.add(application.id()) && appCodes.add(application.code()), "应用身份重复");
            boolean owned =
                    upgrade
                            ? (manifest.batchPrefix() + "app_a").equals(application.code())
                                            && ("字段升级应用 A " + batch).equals(application.name())
                                    || (manifest.batchPrefix() + "app_b").equals(application.code())
                                            && ("字段升级应用 B " + batch).equals(application.name())
                            : (manifest.batchPrefix() + "seed").equals(application.code())
                                    && ("字段转换写入夹具 " + batch).equals(application.name());
            require(owned, "应用不属于专用验收夹具");
        }
    }

    private static void validId(String id) {
        require(id != null && id.matches("[1-9][0-9]{0,18}"), "清单 ID 必须是正整数字符串");
        Long.parseLong(id);
    }

    private static Map<String, Object> parameters(Manifest manifest) {
        List<Long> objects = manifest.objects().stream().map(o -> Long.valueOf(o.id())).toList();
        List<Long> applications =
                manifest.applications().stream().map(a -> Long.valueOf(a.id())).toList();
        List<String> keys =
                manifest.applications().stream()
                        .flatMap(
                                a ->
                                        java.util.stream.Stream.of(
                                                "application:" + a.id() + ":draft",
                                                "application:" + a.id() + ":published"))
                        .toList();
        return new HashMap<>(
                Map.of(
                        "objects",
                        objects,
                        "applications",
                        applications.isEmpty() ? List.of(-1L) : applications,
                        "dependencyKeys",
                        keys.isEmpty() ? List.of("__no_fixture_application__") : keys,
                        "objectStrings",
                        manifest.objects().stream().map(OwnedObject::id).toList(),
                        "applicationStrings",
                        manifest.applications().isEmpty()
                                ? List.of("-1")
                                : manifest.applications().stream()
                                        .map(OwnedApplication::id)
                                        .toList()));
    }

    private static void verifyIdentities(JdbcTemplate jdbc, Manifest manifest, boolean lock) {
        for (OwnedObject object : manifest.objects()) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT"
                                + " o.object_code,o.object_name,o.source_type,o.schema_name,t.table_name"
                                + " FROM public.nocode_object o JOIN public.nocode_object_version v"
                                + " ON v.object_id=o.id AND v.version_no=o.latest_version_no JOIN"
                                + " public.nocode_object_table t ON t.object_version_id=v.id AND"
                                + " t.table_role='MAIN' AND t.deleted=0 WHERE o.id=?"
                                    + (lock ? " FOR UPDATE OF o" : ""),
                            Long.valueOf(object.id()));
            require(rows.size() == 1, "对象不存在或主表身份不唯一：" + object.id());
            Map<String, Object> row = rows.getFirst();
            require(
                    object.code().equals(row.get("object_code"))
                            && object.name().equals(row.get("object_name"))
                            && object.tableName().equals(row.get("table_name"))
                            && "GENERATED".equals(row.get("source_type"))
                            && "public".equals(row.get("schema_name")),
                    "对象 ID/编码/名称/物理表/来源不匹配：" + object.id());
        }
        for (OwnedApplication application : manifest.applications()) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT app_code,app_name FROM public.nocode_application WHERE id=?"
                                    + (lock ? " FOR UPDATE" : ""),
                            Long.valueOf(application.id()));
            require(
                    rows.size() == 1
                            && application.code().equals(rows.getFirst().get("app_code"))
                            && application.name().equals(rows.getFirst().get("app_name")),
                    "应用 ID/编码/名称不匹配：" + application.id());
        }
    }

    private static Set<String> verifyTables(
            NamedParameterJdbcTemplate jdbc, Map<String, Object> p, Manifest manifest) {
        Set<String> tables = new LinkedHashSet<>();
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT DISTINCT"
                            + " t.table_name,COALESCE(t.config_json->'binding'->>'source','GENERATED')"
                            + " AS source,COALESCE(t.config_json->'binding'->>'schemaName','public')"
                            + " AS schema_name FROM public.nocode_object_table t JOIN"
                            + " public.nocode_object_version v ON v.id=t.object_version_id WHERE"
                            + " v.object_id IN (:objects)",
                        p);
        for (Map<String, Object> row : rows) {
            String table = row.get("table_name").toString();
            require(
                    table.startsWith("biz_" + manifest.batchPrefix())
                            && "GENERATED".equals(row.get("source"))
                            && "public".equals(row.get("schema_name")),
                    "版本中含非本批次专属生成表：" + table);
            tables.add(table);
        }
        for (Map<String, Object> relation :
                jdbc.queryForList(
                        "SELECT DISTINCT v.object_id,r.stable_relation_id FROM"
                                + " public.nocode_relation r JOIN public.nocode_object_version v ON"
                                + " v.id=r.object_version_id WHERE v.object_id IN (:objects) AND"
                                + " r.relation_kind='MANY_TO_MANY'",
                        p))
            tables.add(
                    "biz_r_"
                            + relation.get("object_id")
                            + "_"
                            + relation.get("stable_relation_id"));
        p.put("tables", List.copyOf(tables));
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_object_table t JOIN"
                        + " public.nocode_object_version v ON v.id=t.object_version_id WHERE"
                        + " t.table_name IN (:tables) AND v.object_id NOT IN (:objects)",
                p,
                "物理表还被其他对象版本纳管");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_deployment WHERE table_name IN (:tables) AND"
                        + " object_id NOT IN (:objects)",
                p,
                "物理表还有其他对象部署记录");
        return tables;
    }

    private static void verifyReferences(NamedParameterJdbcTemplate jdbc, Map<String, Object> p) {
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_application a CROSS JOIN LATERAL"
                        + " jsonb_path_query(a.design_json,'$.**.objectId') ref WHERE a.id IN"
                        + " (:applications) AND ref #>> '{}' NOT IN (:objectStrings)",
                p,
                "本批次应用草稿引用了清单外对象");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_application_version v CROSS JOIN LATERAL"
                        + " jsonb_path_query(v.definition_json,'$.**.objectId') ref WHERE"
                        + " v.application_id IN (:applications) AND ref #>> '{}' NOT IN"
                        + " (:objectStrings)",
                p,
                "本批次应用历史版本引用了清单外对象");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_relation r JOIN public.nocode_object_version v"
                        + " ON v.id=r.object_version_id WHERE (r.target_object_id IN (:objects) AND"
                        + " v.object_id NOT IN (:objects)) OR (v.object_id IN (:objects) AND"
                        + " r.target_object_id NOT IN (:objects))",
                p,
                "存在清单外对象关系");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_resource_dependency WHERE (target_object_id IN"
                    + " (:objects) AND NOT(source_kind='APP' AND source_key IN (:dependencyKeys)))"
                    + " OR (source_key IN (:dependencyKeys) AND target_object_id NOT IN"
                    + " (:objects))",
                p,
                "存在清单外资源引用");
        for (String table :
                List.of("nocode_object_application_grant", "nocode_object_application_grant_log"))
            absent(
                    jdbc,
                    "SELECT count(*) FROM public."
                            + table
                            + " WHERE (object_id IN (:objects) AND application_id NOT IN"
                            + " (:applications)) OR (application_id IN (:applications) AND"
                            + " object_id NOT IN (:objects))",
                    p,
                    "存在清单外对象或应用授权");
        // JSON 引用可能存在于历史应用版本；不只依赖当前引用登记。
        for (Object id : (List<?>) p.get("objectStrings")) {
            p.put("reference", id);
            absent(
                    jdbc,
                    "SELECT count(*) FROM public.nocode_application WHERE id NOT IN (:applications)"
                            + " AND jsonb_path_exists(design_json,'$.**.objectId ? (@ =="
                            + " $id)',jsonb_build_object('id',CAST(:reference AS text)))",
                    p,
                    "其他应用草稿仍引用本批次对象");
            absent(
                    jdbc,
                    "SELECT count(*) FROM public.nocode_application_version WHERE application_id"
                            + " NOT IN (:applications) AND"
                            + " jsonb_path_exists(definition_json,'$.**.objectId ? (@ =="
                            + " $id)',jsonb_build_object('id',CAST(:reference AS text)))",
                    p,
                    "其他应用历史版本仍引用本批次对象");
            absent(
                    jdbc,
                    "SELECT count(*) FROM public.nocode_object_version WHERE object_id NOT IN"
                        + " (:objects) AND (jsonb_path_exists(schema_json,'$.**.targetObjectId ? (@"
                        + " == $id)',jsonb_build_object('id',CAST(:reference AS text))) OR"
                        + " jsonb_path_exists(schema_json,'$.**.sourceObjectId ? (@ =="
                        + " $id)',jsonb_build_object('id',CAST(:reference AS text))))",
                    p,
                    "其他对象历史版本仍引用本批次对象");
        }
        for (String table : List.of("nocode_record_process", "nocode_handling_request"))
            absent(
                    jdbc,
                    "SELECT count(*) FROM public."
                            + table
                            + " WHERE object_id IN (:objects) OR application_id IN (:applications)",
                    p,
                    "存在流程或办理链路，需专项核验后处理");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_work_draft WHERE object_id IN (:objectStrings)"
                        + " OR resource_json->>'applicationId' IN (:applicationStrings)",
                p,
                "存在工作草稿或任务提交链路，需专项核验后处理");
        for (String table : List.of("nocode_record_history", "nocode_document_receipt"))
            absent(
                    jdbc,
                    "SELECT count(*) FROM public."
                            + table
                            + " WHERE (object_id IN (:objects) AND application_id IS NOT NULL AND"
                            + " application_id NOT IN (:applications)) OR (application_id IN"
                            + " (:applications) AND object_id NOT IN (:objects))",
                    p,
                    "业务记录来自清单外应用或对象");
        absent(
                jdbc,
                "SELECT count(*) FROM public.nocode_operation_log WHERE (object_id IN (:objects)"
                    + " AND app_id IS NOT NULL AND app_id NOT IN (:applications)) OR (app_id IN"
                    + " (:applications) AND object_id IS NOT NULL AND object_id NOT IN (:objects))",
                p,
                "审计记录涉及清单外对象或应用");
    }

    private static void verifyPhysicalDependencies(JdbcTemplate jdbc, Set<String> tables) {
        Set<Long> ids = new HashSet<>();
        for (String table : tables) {
            List<Map<String, Object>> relations =
                    jdbc.queryForList(
                            "SELECT c.oid::bigint AS oid,c.relkind::text AS kind FROM pg_class c"
                                    + " JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                                    + " n.nspname='public' AND c.relname=?",
                            table);
            if (relations.isEmpty()) continue;
            require(
                    relations.size() == 1 && "r".equals(relations.getFirst().get("kind")),
                    "夹具表不是普通物理表：" + table);
            long id = ((Number) relations.getFirst().get("oid")).longValue();
            ids.add(id);
            jdbc.execute(
                    "LOCK TABLE "
                            + PostgreSqlCommands.table("public", table)
                            + " IN ACCESS EXCLUSIVE MODE");
            require(
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_inherits WHERE inhrelid=? OR"
                                            + " inhparent=?",
                                    Long.class,
                                    id,
                                    id)
                            == 0,
                    "夹具表存在继承或分区");
            require(
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_depend d WHERE d.refobjid=? AND"
                                            + " d.classid='pg_rewrite'::regclass",
                                    Long.class,
                                    id)
                            == 0,
                    "夹具表被视图引用");
        }
        for (long id : ids)
            for (Long source :
                    jdbc.queryForList(
                            "SELECT conrelid::bigint FROM pg_constraint WHERE confrelid=? AND"
                                    + " contype='f'",
                            Long.class,
                            id)) require(ids.contains(source), "夹具表被清单外物理表的外键引用");
    }

    private static void remove(
            NamedParameterJdbcTemplate jdbc,
            JdbcTemplate raw,
            Map<String, Object> p,
            Set<String> tables) {
        // 一条 RESTRICT DDL 可同时删除清单内互相引用的表；任何未知外部依赖仍令整个事务回滚。
        if (!tables.isEmpty())
            raw.execute(
                    "DROP TABLE IF EXISTS "
                            + tables.stream()
                                    .map(t -> PostgreSqlCommands.table("public", t))
                                    .collect(java.util.stream.Collectors.joining(","))
                            + " RESTRICT");
        jdbc.update(
                "DELETE FROM public.nocode_resource_dependency WHERE target_object_id IN"
                        + " (:objects)",
                p);
        for (String table :
                List.of("nocode_object_application_grant_log", "nocode_object_application_grant"))
            jdbc.update(
                    "DELETE FROM public."
                            + table
                            + " WHERE object_id IN (:objects) AND application_id IN"
                            + " (:applications)",
                    p);
        for (String table :
                List.of(
                        "nocode_task_entry_access",
                        "nocode_application_access",
                        "nocode_application_version"))
            jdbc.update(
                    "DELETE FROM public." + table + " WHERE application_id IN (:applications)", p);
        jdbc.update(
                "DELETE FROM public.nocode_operation_log WHERE object_id IN (:objects) OR app_id IN"
                        + " (:applications)",
                p);
        for (String table : tables)
            raw.update(
                    "DELETE FROM public.nocode_operation_log WHERE resource_type='DATA_TABLE' AND"
                            + " resource_id=?",
                    "public." + table);
        jdbc.update("DELETE FROM public.nocode_application WHERE id IN (:applications)", p);
        for (String table :
                List.of(
                        "nocode_publish_plan",
                        "nocode_deployment",
                        "nocode_record_history",
                        "nocode_record_history_head",
                        "nocode_document_receipt",
                        "nocode_detail_position",
                        "nocode_business_counter"))
            jdbc.update("DELETE FROM public." + table + " WHERE object_id IN (:objects)", p);
        jdbc.update(
                "UPDATE public.nocode_object SET"
                        + " reconciliation_hash=NULL,reconciliation_version_id=NULL WHERE id IN"
                        + " (:objects)",
                p);
        for (String table :
                List.of(
                        "nocode_relation",
                        "nocode_index_definition",
                        "nocode_field",
                        "nocode_object_table"))
            jdbc.update(
                    "DELETE FROM public."
                            + table
                            + " WHERE object_version_id IN (SELECT id FROM"
                            + " public.nocode_object_version WHERE object_id IN (:objects))",
                    p);
        jdbc.update("DELETE FROM public.nocode_object_version WHERE object_id IN (:objects)", p);
        jdbc.update("DELETE FROM public.nocode_object WHERE id IN (:objects)", p);
    }

    private static void absent(
            NamedParameterJdbcTemplate jdbc, String sql, Map<String, Object> p, String message) {
        require(jdbc.queryForObject(sql, p, Long.class) == 0, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
