package com.lingan.ucp.nocode.tools;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDraftService;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.metadata.service.table.TableBindingService;
import com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler;
import com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService;
import com.lingan.ucp.nocode.schema.service.selection.SelectionMigrationService;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

import javax.sql.DataSource;

/**
 * 会计基础数据初始化工具。复用唯一开发库配置、正式对象服务和发布服务。
 *
 * <p>对象逐个发布以满足引用依赖；记录在同一事务中写入并逐条核验。重复运行只验证已有完整批次， 不覆盖用户修改。仅接受随工程维护的会计定义，不是任意表导入入口。
 */
public class AccountingDataTool {
    private static final String BATCH = "ACCOUNTING_20260911_V1";
    private static final String CATEGORY = "accounting_category";
    private static final String ACCOUNT = "accounting_account";
    private static final List<String> CODES = List.of(CATEGORY, ACCOUNT);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** 参数：inspect|seed|verify definitions/accounting-objects.json output.json。 */
    public static void main(String[] args) throws Exception {
        check(
                args.length == 3 && Set.of("inspect", "seed", "verify").contains(args[0]),
                "inspect|seed|verify definition.json output.json");
        var definition = JSON.readTree(Path.of(args[1]).toFile());
        validateInput(definition);
        try (var database = NocodeToolContext.open();
                var services = services(database)) {
            var jdbc = database.getBean(JdbcTemplate.class);
            var designs = services.getBean(ObjectDesignService.class);
            var publisher = services.getBean(SchemaPublishService.class);
            var tables = services.getBean(DataTableService.class);
            var report = new LinkedHashMap<String, Object>();
            report.put("batch", BATCH);
            report.put("mode", args[0]);
            report.put("database", jdbc.queryForObject("SELECT current_database()", String.class));
            report.put("before", existing(jdbc));
            preflight(jdbc);
            if (!args[0].equals("inspect")) {
                var actor =
                        jdbc.queryForObject(
                                "SELECT id FROM public.system_users WHERE username=? AND deleted=0"
                                        + " AND status=0",
                                Long.class,
                                "admin");
                check(actor != null, "缺少有效的既有管理员");
                var ids = new LinkedHashMap<String, String>();
                for (var object : definition.path("objects")) {
                    String code = object.path("code").asText();
                    var found =
                            jdbc.queryForList(
                                    "SELECT id FROM public.nocode_object WHERE object_code=? AND"
                                            + " deleted=0",
                                    Long.class,
                                    code);
                    if (args[0].equals("seed")) {
                        Design saved =
                                found.isEmpty()
                                        ? designs.save(request(object, ids), actor)
                                        : designs.get(found.getFirst().toString());
                        if (saved.publishedVersion() == null) {
                            verifyDefinition(object, designs.definition(saved.draft().id()), ids);
                            var plan =
                                    publisher.plan(
                                            new Revision(
                                                    saved.draft().id(),
                                                    saved.draft().lockVersion(),
                                                    null),
                                            actor);
                            check(
                                    plan.checks().stream().noneMatch(Check::blocking),
                                    "发布检查未通过：" + plan.checks());
                            var executed =
                                    publisher.execute(
                                            new ExecutePlan(plan.id(), BATCH + " 会计基础对象初始化"),
                                            actor);
                            check(
                                    PublishStateEnum.SUCCEEDED.matches(executed.state()),
                                    "发布失败：" + code);
                        }
                        ids.put(code, saved.draft().id());
                    } else {
                        check(found.size() == 1, "对象尚未初始化：" + code);
                        ids.put(code, found.getFirst().toString());
                    }
                    verifyDefinition(object, designs.published(ids.get(code)), ids);
                    check(tables.drift(ids.get(code)).isEmpty(), "对象结构存在差异：" + code);
                }
                var tx =
                        new TransactionTemplate(database.getBean(PlatformTransactionManager.class));
                tx.setTimeout(120);
                if (args[0].equals("seed")) {
                    report.put(
                            "createdRecords",
                            tx.execute(
                                    status -> {
                                        jdbc.execute("SET LOCAL lock_timeout='5s'");
                                        jdbc.execute(
                                                "LOCK TABLE public.biz_accounting_category,"
                                                    + " public.biz_accounting_account IN SHARE ROW"
                                                    + " EXCLUSIVE MODE");
                                        long count =
                                                jdbc.queryForObject(
                                                        "SELECT (SELECT count(*) FROM"
                                                            + " public.biz_accounting_category) +"
                                                            + " (SELECT count(*) FROM"
                                                            + " public.biz_accounting_account)",
                                                        Long.class);
                                        if (count != 0) {
                                            verifyRecords(jdbc, definition);
                                            return false;
                                        }
                                        insertRecords(jdbc, definition, actor.toString());
                                        verifyRecords(jdbc, definition);
                                        return true;
                                    }));
                }
                report.put("verification", tx.execute(status -> verifyRecords(jdbc, definition)));
                report.put("objectIds", ids);
                report.put(
                        "publishedDefinitions",
                        CODES.stream().map(c -> designs.published(ids.get(c))).toList());
                report.put("structureChecks", "passed");
                report.put("actorId", actor);
            }
            report.put("after", existing(jdbc));
            report.put("completedAt", OffsetDateTime.now().toString());
            var output = Path.of(args[2]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            report.remove("publishedDefinitions");
            System.out.println(JSON.writeValueAsString(report));
        }
    }

    private static List<Map<String, Object>> existing(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT"
                    + " o.id,o.object_code,o.object_name,t.table_name,o.current_published_version_no"
                    + " FROM public.nocode_object o JOIN public.nocode_object_version v ON"
                    + " v.object_id=o.id AND v.version_no=o.latest_version_no JOIN"
                    + " public.nocode_object_table t ON t.object_version_id=v.id AND"
                    + " t.table_role='MAIN' WHERE o.object_code IN (?,?) AND o.deleted=0 ORDER BY"
                    + " o.id",
                CATEGORY,
                ACCOUNT);
    }

    private static void preflight(JdbcTemplate jdbc) {
        for (String code : CODES) {
            String name = code.equals(CATEGORY) ? "会计科目分类" : "会计科目";
            var found =
                    jdbc.queryForList(
                            "SELECT o.object_code,o.object_name,t.table_name,o.description FROM"
                                + " public.nocode_object o JOIN public.nocode_object_version v ON"
                                + " v.object_id=o.id AND v.version_no=o.latest_version_no JOIN"
                                + " public.nocode_object_table t ON t.object_version_id=v.id AND"
                                + " t.table_role='MAIN' WHERE o.deleted=0 AND (o.object_code=? OR"
                                + " o.object_name=? OR t.table_name=?)",
                            code,
                            name,
                            "biz_" + code);
            check(found.size() <= 1, "存在重复或同名对象：" + name);
            if (!found.isEmpty()) {
                var row = found.getFirst();
                check(
                        code.equals(row.get("object_code"))
                                && name.equals(row.get("object_name"))
                                && ("biz_" + code).equals(row.get("table_name"))
                                && Objects.toString(row.get("description"), "").startsWith(BATCH),
                        "对象或表名已被其他配置占用：" + name);
            } else {
                check(
                        !Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT to_regclass(?) IS NOT NULL",
                                        Boolean.class,
                                        "public.biz_" + code)),
                        "未登记的物理表已存在：" + code);
            }
        }
    }

    private static SaveDesign request(JsonNode object, Map<String, String> ids) throws Exception {
        var fields = new ArrayList<FieldDefinition>();
        var options = new LinkedHashMap<String, FieldOptions>();
        for (var field : object.path("fields")) {
            String code = field.path("code").asText();
            fields.add(
                    new FieldDefinition(
                            code,
                            null,
                            code,
                            field.path("name").asText(),
                            field.path("type").asText(),
                            field.has("length") ? field.get("length").asInt() : null,
                            null,
                            null,
                            field.path("required").asBoolean(),
                            field.path("unique").asBoolean(),
                            fields.size()));
            ObjectNode option = JSON.createObjectNode();
            option.put("classification", DataClassificationEnum.NORMAL.getCode());
            option.put("state", MemberStateEnum.ACTIVE.getCode());
            option.put(
                    "resolver",
                    field.has("options")
                            ? DisplayResolverEnum.LOCAL_OPTIONS.getCode()
                            : DisplayResolverEnum.NONE.getCode());
            option.putArray("options");
            for (String key :
                    List.of("options", "resolver", "minimum", "maximum", "description", "pattern"))
                if (field.has(key)) option.set(key, field.get(key));
            options.put(code, JSON.treeToValue(option, FieldOptions.class));
        }
        var relations = new ArrayList<Relation>();
        for (var relation : object.path("relations")) {
            var target = ids.get(relation.path("targetObjectCode").asText());
            check(target != null, "引用目标必须先发布");
            relations.add(
                    new Relation(
                            null,
                            relation.path("code").asText(),
                            relation.path("name").asText(),
                            relation.path("kind").asText(),
                            target,
                            relation.path("fieldId").asText(),
                            null,
                            relation.path("required").asBoolean(),
                            relation.path("onDelete").asText()));
        }
        return new SaveDesign(
                new SaveObjectDraft(
                        null,
                        null,
                        object.path("code").asText(),
                        object.path("name").asText(),
                        BATCH + " 根据用户提供的会计科目清单初始化。",
                        object.path("tableName").asText(),
                        object.path("titleField").asText(),
                        fields,
                        List.of()),
                Settings.defaults(),
                options,
                relations,
                List.of(),
                List.of());
    }

    private static void verifyDefinition(
            JsonNode expected, Definition actual, Map<String, String> ids) {
        check(
                actual != null && actual.objectCode().equals(expected.path("code").asText()),
                "已发布对象缺失");
        check(
                actual.objectName().equals(expected.path("name").asText())
                        && actual.tableName().equals(expected.path("tableName").asText()),
                "对象名称或表名不一致");
        check(actual.fields().size() == expected.path("fields").size(), "字段数不一致");
        int sort = 0;
        for (var field : expected.path("fields")) {
            var value =
                    actual.fields().stream()
                            .filter(f -> f.code().equals(field.path("code").asText()))
                            .findFirst()
                            .orElseThrow();
            check(
                    value.name().equals(field.path("name").asText())
                            && value.type().equals(field.path("type").asText())
                            && value.required() == field.path("required").asBoolean()
                            && value.unique() == field.path("unique").asBoolean()
                            && value.sort() == sort++,
                    "字段定义不一致：" + value.code());
            check(
                    Objects.equals(
                            value.length(),
                            field.has("length") ? field.get("length").asInt() : null),
                    "文本长度不一致");
            var options = actual.fieldOptions().get(value.id());
            if (field.has("options"))
                check(JSON.valueToTree(options.options()).equals(field.get("options")), "单选选项不一致");
            if (field.has("minimum"))
                check(field.get("minimum").asText().equals(options.minimum()), "最小值不一致");
            if (field.has("resolver"))
                check(field.get("resolver").asText().equals(options.resolver()), "引用显示配置不一致");
            if (value.code().equals(expected.path("titleField").asText()))
                check(value.id().equals(actual.titleFieldId()), "记录标题不一致");
        }
        check(actual.relations().size() == expected.path("relations").size(), "引用数量不一致");
        for (var relation : expected.path("relations")) {
            var value = actual.relations().getFirst();
            String fieldId =
                    actual.fields().stream()
                            .filter(f -> f.code().equals(relation.path("fieldId").asText()))
                            .findFirst()
                            .orElseThrow()
                            .id();
            check(
                    value.fieldId().equals(fieldId)
                            && value.kind().equals(relation.path("kind").asText())
                            && value.targetObjectId()
                                    .equals(ids.get(relation.path("targetObjectCode").asText()))
                            && value.required()
                            && value.onDelete().equals(relation.path("onDelete").asText()),
                    "引用约束不一致");
        }
    }

    private static void insertRecords(JdbcTemplate jdbc, JsonNode definition, String actor) {
        var categories = new HashMap<String, Long>();
        for (var record : definition.path("objects").get(0).path("records")) {
            Long id =
                    jdbc.queryForObject(
                            "INSERT INTO"
                                + " public.biz_accounting_category(category_code,category_name,creator,updater)"
                                + " VALUES (?,?,?,?) RETURNING id",
                            Long.class,
                            record.path("category_code").asText(),
                            record.path("category_name").asText(),
                            actor,
                            actor);
            categories.put(record.path("category_code").asText(), id);
        }
        var rows = new ArrayList<Object[]>();
        for (var record : definition.path("objects").get(1).path("records"))
            rows.add(
                    new Object[] {
                        record.path("account_code").asText(),
                        record.path("account_name").asText(),
                        categories.get(record.path("category_code").asText()),
                        record.path("accounting_element").asText(),
                        record.path("normal_balance").asText(),
                        record.path("sort_order").asInt(),
                        actor,
                        actor
                    });
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " public.biz_accounting_account(account_code,account_name,category_id,accounting_element,normal_balance,sort_order,creator,updater)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                rows);
    }

    private static Map<String, Object> verifyRecords(JdbcTemplate jdbc, JsonNode definition) {
        var categories =
                jdbc.queryForList(
                        "SELECT category_code,category_name FROM public.biz_accounting_category"
                                + " WHERE deleted=0 ORDER BY category_code");
        var accounts =
                jdbc.queryForList(
                        "SELECT"
                            + " a.account_code,a.account_name,c.category_code,a.accounting_element,a.normal_balance,a.sort_order"
                            + " FROM public.biz_accounting_account a JOIN"
                            + " public.biz_accounting_category c ON c.id=a.category_id AND"
                            + " c.deleted=0 WHERE a.deleted=0 ORDER BY a.account_code");
        check(categories.size() == 13 && accounts.size() == 90, "批次数量不完整，终止以保留当前数据");
        for (int i = 0; i < 2; i++) {
            var expected = new TreeMap<String, JsonNode>();
            var actual = new TreeMap<String, JsonNode>();
            String key = i == 0 ? "category_code" : "account_code";
            for (var row : definition.path("objects").get(i).path("records"))
                expected.put(row.path(key).asText(), row);
            for (var row : i == 0 ? categories : accounts) {
                JsonNode normalized = JSON.valueToTree(row);
                if (i == 1)
                    ((ObjectNode) normalized)
                            .put("sort_order", normalized.path("sort_order").asInt());
                actual.put(normalized.path(key).asText(), normalized);
            }
            check(expected.equals(actual), "记录与初始化源不一致，不自动覆盖");
        }
        for (String code : CODES) {
            check(
                    jdbc.queryForObject("SELECT count(*) FROM public.biz_" + code, Long.class)
                            == (code.equals(CATEGORY) ? 13L : 90L),
                    "存在额外或软删除记录");
            check(
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM public.biz_"
                                            + code
                                            + " WHERE creator IS NULL OR creator='' OR updater IS"
                                            + " NULL OR updater='' OR create_time IS NULL OR"
                                            + " update_time IS NULL",
                                    Long.class)
                            == 0,
                    "审计字段不完整");
        }
        var groups =
                jdbc.queryForList(
                        "SELECT c.category_code,c.category_name,count(a.id) account_count FROM"
                                + " public.biz_accounting_category c LEFT JOIN"
                                + " public.biz_accounting_account a ON a.category_id=c.id AND"
                                + " a.deleted=0 WHERE c.deleted=0 GROUP BY"
                                + " c.id,c.category_code,c.category_name ORDER BY c.category_code");
        return Map.of(
                "categories",
                categories.size(),
                "accounts",
                accounts.size(),
                "exactSourceMatch",
                true,
                "auditFields",
                "passed",
                "groups",
                groups);
    }

    private static void validateInput(JsonNode definition) {
        check(
                BATCH.equals(definition.path("batch").asText())
                        && definition.path("objects").size() == 2,
                "不是本工具支持的会计初始化定义");
        for (int i = 0; i < 2; i++) {
            var object = definition.path("objects").get(i);
            check(
                    CODES.get(i).equals(object.path("code").asText())
                            && ("biz_" + CODES.get(i)).equals(object.path("tableName").asText()),
                    "对象或表名不受支持");
            check(object.path("records").size() == (i == 0 ? 13 : 90), "输入记录数不正确");
        }
        Set<String> categories = new HashSet<>(), accounts = new HashSet<>();
        for (var row : definition.path("objects").get(0).path("records"))
            check(categories.add(row.path("category_code").asText()), "分类编码重复");
        for (var row : definition.path("objects").get(1).path("records")) {
            check(accounts.add(row.path("account_code").asText()), "科目编码重复");
            check(categories.contains(row.path("category_code").asText()), "分类引用不存在");
            check(
                    row.path("sort_order").isInt() && row.path("sort_order").asInt() >= 0,
                    "顺序必须是非负整数");
            for (String code : List.of("accounting_element", "normal_balance")) {
                var field = definition.path("objects").get(1).path("fields");
                Set<String> choices = new HashSet<>();
                for (var f : field)
                    if (f.path("code").asText().equals(code))
                        for (var option : f.path("options"))
                            choices.add(option.path("code").asText());
                check(choices.contains(row.path(code).asText()), "非法单选值：" + code);
            }
        }
    }

    /** 只装配对象设计所需的正式 MyBatis 和 Spring 组件，不启动 Web、调度或迁移。 */
    private static AnnotationConfigApplicationContext services(
            ConfigurableApplicationContext parent) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global =
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(
                                new OsMybatisAutoConfiguration().defaultMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        List<Class<?>> mappers =
                List.of(
                        ObjectDraftMapper.class,
                        DataCenterMapper.class,
                        SelectionMigrationMapper.class,
                        com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper.class,
                        com.lingan.ucp.nocode.metadata.dal.mapper.FieldConstraintCheckMapper.class,
                        PostgreSqlCommandMapper.class,
                        PostgreSqlDatabaseMetadataMapper.class);
        for (var mapper : mappers) configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(parent.getBean(DataSource.class));
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        factory.setPlugins(new OsMybatisAutoConfiguration().mybatisPlusInterceptor());
        var resources = new PathMatchingResourcePatternResolver();
        var mappings = new ArrayList<Resource>();
        mappings.addAll(List.of(resources.getResources("classpath*:mapper/nocode/*.xml")));
        mappings.addAll(List.of(resources.getResources("classpath*:mapper/database/*.xml")));
        factory.setMapperLocations(mappings.toArray(Resource[]::new));
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var context = new AnnotationConfigApplicationContext();
        context.setParent(parent);
        context.registerBean(ObjectMapper.class, () -> JSON);
        for (var mapper : mappers) registerMapper(context, session, mapper);
        context.registerBean(
                DatabaseMetadataReader.class,
                () ->
                        new PostgreSqlDatabaseMetadataReader(
                                session.getMapper(PostgreSqlDatabaseMetadataMapper.class)));
        context.register(
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignCodec.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignReader.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignFields.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignPartsWriter.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectMemberDeployment.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignReferences.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaColumnChanges.class,
                com.lingan.ucp.nocode.schema.service.compile.FieldConversionPlanner.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaFieldConstraintChecks.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaIndexChanges.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaRelationChanges.class,
                com.lingan.ucp.nocode.schema.service.publish.SchemaPublishContext.class,
                DraftValidator.class,
                ObjectDraftService.class,
                TableBindingService.class,
                ObjectDesignService.class,
                DataTableService.class,
                SelectionMigrationService.class,
                SchemaCompiler.class,
                SchemaPublishService.class);
        context.refresh();
        return context;
    }

    private static <T> void registerMapper(
            AnnotationConfigApplicationContext context, SqlSessionTemplate session, Class<T> type) {
        context.registerBean(type, () -> session.getMapper(type));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
