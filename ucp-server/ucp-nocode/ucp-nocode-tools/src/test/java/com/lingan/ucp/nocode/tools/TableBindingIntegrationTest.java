package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.schema.service.reconcile.ObjectReconcileService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 当前开发库中的混合表来源契约回归；每例只清理自己命名并登记的表。 */
class TableBindingIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private final List<ObjectTables.Ref> owned = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();

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
        for (var ref : owned.reversed()) {
            if (!ref.name().startsWith("biz_" + fixture.prefix))
                throw new IllegalStateException("Fixture ownership mismatch");
            jdbc.execute(
                    "DROP TABLE IF EXISTS "
                            + PostgreSqlCommands.table(ref.schema(), ref.name())
                            + " CASCADE");
        }
        fixture.clean();
        for (String schema : schemas) {
            if (!schema.startsWith(fixture.prefix))
                throw new IllegalStateException("Fixture schema mismatch");
            jdbc.execute("DROP SCHEMA " + PostgreSqlCommands.identifier(schema));
        }
    }

    private String table(String schema, String suffix, String columns, boolean base) {
        String name = "biz_" + fixture.prefix + suffix;
        owned.add(new ObjectTables.Ref(schema, name));
        jdbc.execute(
                "CREATE TABLE "
                        + PostgreSqlCommands.table(schema, name)
                        + " ("
                        + columns
                        + (base
                                ? ",creator varchar(64) DEFAULT '',create_time timestamp NOT NULL"
                                        + " DEFAULT CURRENT_TIMESTAMP,updater varchar(64) DEFAULT"
                                        + " '',update_time timestamp NOT NULL DEFAULT"
                                        + " CURRENT_TIMESTAMP,deleted smallint NOT NULL DEFAULT 0"
                                        + " CHECK(deleted IN(0,1))"
                                : "")
                        + ")");
        return name;
    }

    private TableBinding adopted(
            String schema,
            String name,
            String parent,
            boolean managed,
            boolean readonly,
            boolean repair) {
        var actual = databaseMetadata.readTable(schema, name).orElseThrow();
        return new TableBinding(
                ObjectSourceEnum.ADOPTED.getCode(),
                schema,
                actual.columns().stream()
                        .filter(c -> c.primaryKeyPosition() > 0)
                        .findFirst()
                        .orElseThrow()
                        .name(),
                parent,
                (managed ? StructureModeEnum.MANAGED : StructureModeEnum.RETAIN).getCode(),
                readonly,
                repair,
                tables.fingerprint(actual));
    }

    private Detail detail(
            String schema,
            String name,
            String parent,
            boolean managed,
            boolean readonly,
            boolean repair) {
        return new Detail(
                null,
                "items",
                "明细",
                name,
                MemberStateEnum.ACTIVE.getCode(),
                List.of(),
                Map.of(),
                List.of(),
                adopted(schema, name, parent, managed, readonly, repair));
    }

    private Design create(List<Detail> details) {
        return designs.save(
                new SaveDesign(
                        fixture.createRequest(""),
                        Settings.defaults(),
                        null,
                        List.of(),
                        List.of(),
                        details),
                10001);
    }

    private Design save(
            Design d,
            List<Detail> details,
            List<Relation> relations,
            List<Index> indexes,
            TableBinding main) {
        return designs.save(
                new SaveDesign(
                        fixture.edit(d.draft(), List.of(), List.of(), d.draft().titleFieldId()),
                        d.settings(),
                        d.fieldOptions(),
                        relations,
                        indexes,
                        details,
                        main),
                10001);
    }

    private Design adopt(String table) {
        var check = tables.preflight("public", table);
        assertThat(check.allowed()).as(check.checks().toString()).isTrue();
        return tables.adopt(
                new Adoption(
                        "public",
                        table,
                        fixture.prefix + "adopt",
                        "已有主表",
                        "name",
                        check.fingerprint()),
                10001);
    }

    private PublishPlan plan(Design d) {
        return publisher.plan(new Revision(d.draft().id(), d.draft().lockVersion(), null), 10001);
    }

    private void publish(Design d) {
        var p = plan(d);
        assertThat(p.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(p.id(), "表级绑定回归"), 10001).state())
                .isEqualTo(PublishStateEnum.SUCCEEDED.getCode());
    }

    private Design edit(Design d) {
        var latest = designs.get(d.draft().id());
        return designs.editPublished(
                new Revision(latest.draft().id(), latest.draft().lockVersion(), "后续版本"), 10001);
    }

    private Design referenceTarget() {
        var target =
                designs.save(
                        new SaveDesign(
                                fixture.createRequest("target"),
                                Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        publish(target);
        return target;
    }

    /** 关系施加的非空可以解除，原表自身的必填约束不得被隐式放宽。 */
    @Test
    void reusedReferenceRequiredCanBeRelaxedAndSetNullWorks() {
        var target = referenceTarget();
        String source =
                table(
                        "public",
                        "source",
                        "id bigint PRIMARY KEY,name varchar(80),customer_id bigint",
                        true);
        var d = adopt(source);
        String field =
                d.fieldOptions().entrySet().stream()
                        .filter(e -> e.getValue().columnName().equals("customer_id"))
                        .findFirst()
                        .orElseThrow()
                        .getKey();
        var b = d.mainBinding();
        var managed =
                new TableBinding(
                        b.source(),
                        b.schemaName(),
                        b.keyColumn(),
                        null,
                        StructureModeEnum.MANAGED.getCode(),
                        false,
                        false,
                        b.fingerprint());
        var relation =
                new Relation(
                        null,
                        "customer",
                        "客户",
                        RelationTypeEnum.REFERENCE.getCode(),
                        target.draft().id(),
                        field,
                        null,
                        true,
                        DeletePolicyEnum.RESTRICT.getCode());
        d = save(d, List.of(), List.of(relation), List.of(), managed);
        publish(d);
        assertThat(databaseMetadata.readTable("public", source).orElseThrow().columns())
                .anyMatch(c -> c.name().equals("customer_id") && !c.nullable());
        jdbc.update(
                "INSERT INTO public."
                        + target.draft().tableName()
                        + "(id,name) VALUES(7,'target')");
        jdbc.update("INSERT INTO public." + source + "(id,name,customer_id) VALUES(1,'source',7)");
        var next = edit(d);
        var old = next.relations().getFirst();
        var optional =
                new Relation(
                        old.id(),
                        old.code(),
                        old.name(),
                        old.kind(),
                        old.targetObjectId(),
                        old.fieldId(),
                        old.targetFieldId(),
                        false,
                        DeletePolicyEnum.SET_NULL.getCode());
        next = save(next, next.details(), List.of(optional), next.indexes(), next.mainBinding());
        publish(next);
        assertThat(databaseMetadata.readTable("public", source).orElseThrow().columns())
                .anyMatch(c -> c.name().equals("customer_id") && c.nullable());
        jdbc.update("DELETE FROM public." + target.draft().tableName() + " WHERE id=7");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_id FROM public." + source + " WHERE id=1",
                                Long.class))
                .isNull();
    }

    @Test
    void originalRequiredColumnCannotBePublishedAsOptionalReference() {
        var target = referenceTarget();
        String source =
                table(
                        "public",
                        "source",
                        "id bigint PRIMARY KEY,name varchar(80),customer_id bigint NOT NULL",
                        true);
        var d = adopt(source);
        String field =
                d.fieldOptions().entrySet().stream()
                        .filter(e -> e.getValue().columnName().equals("customer_id"))
                        .findFirst()
                        .orElseThrow()
                        .getKey();
        var relation =
                new Relation(
                        null,
                        "customer",
                        "客户",
                        RelationTypeEnum.REFERENCE.getCode(),
                        target.draft().id(),
                        field,
                        null,
                        false,
                        DeletePolicyEnum.SET_NULL.getCode());
        d = save(d, List.of(), List.of(relation), List.of(), d.mainBinding());
        assertThat(plan(d).checks())
                .anyMatch(c -> c.blocking() && c.message().contains("引用列本身为必填"));
        assertThat(databaseMetadata.readTable("public", source).orElseThrow().columns())
                .anyMatch(c -> c.name().equals("customer_id") && !c.nullable());
    }

    @Test
    void crossSchemaDetailDataPreventsObjectDeletion() {
        String schema = fixture.prefix + "schema";
        jdbc.execute("CREATE SCHEMA " + PostgreSqlCommands.identifier(schema));
        schemas.add(schema);
        String child =
                table(
                        schema,
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_name varchar(80)",
                        true);
        jdbc.update(
                "INSERT INTO "
                        + PostgreSqlCommands.table(schema, child)
                        + "(line_key,item_name) VALUES(1,'history')");
        var d = create(List.of(detail(schema, child, "order_ref", false, true, false)));
        assertThatThrownBy(
                        () ->
                                designs.lifecycle(
                                        new Revision(
                                                d.draft().id(), d.draft().lockVersion(), "验证删除保护"),
                                        LifecycleActionEnum.DELETE.getCode(),
                                        10001))
                .hasMessageContaining("包含业务数据");
        assertThat(designs.get(d.draft().id()).status())
                .isEqualTo(ObjectStatusEnum.DRAFT.getCode());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM " + PostgreSqlCommands.table(schema, child),
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void copyPreservesReusedReferenceAndIndexesOnGeneratedReference() {
        var target = referenceTarget();
        var request = fixture.createRequest("source");
        var fields = new ArrayList<>(request.fields());
        fields.add(
                fixture.field("customer-key", "customer_id", FieldTypeEnum.INTEGER.getCode(), 1));
        var input =
                new SaveObjectDraft(
                        null,
                        null,
                        request.objectCode(),
                        request.objectName(),
                        null,
                        request.tableName(),
                        request.titleFieldKey(),
                        fields,
                        List.of());
        var source =
                designs.save(
                        new SaveDesign(
                                input,
                                Settings.defaults(),
                                null,
                                List.of(
                                        new Relation(
                                                null,
                                                "customer",
                                                "客户",
                                                RelationTypeEnum.REFERENCE.getCode(),
                                                target.draft().id(),
                                                "customer-key",
                                                null,
                                                true,
                                                DeletePolicyEnum.RESTRICT.getCode()),
                                        new Relation(
                                                null,
                                                "billing",
                                                "结算客户",
                                                RelationTypeEnum.REFERENCE.getCode(),
                                                target.draft().id(),
                                                null,
                                                null,
                                                false,
                                                DeletePolicyEnum.RESTRICT.getCode())),
                                List.of(),
                                List.of()),
                        10001);
        source =
                save(
                        source,
                        source.details(),
                        source.relations(),
                        List.of(
                                new Index(
                                        null,
                                        "reference_pair",
                                        "关系组合索引",
                                        false,
                                        source.relations().stream()
                                                .map(Relation::fieldId)
                                                .toList())),
                        source.mainBinding());
        var copied =
                designs.copy(
                        new Copy(
                                source.draft().id(),
                                fixture.prefix + "copy",
                                "复制对象",
                                "biz_" + fixture.prefix + "copy"),
                        10001);
        assertThat(copied.draft().fields()).hasSize(3);
        var relation =
                copied.relations().stream()
                        .filter(r -> r.code().equals("customer"))
                        .findFirst()
                        .orElseThrow();
        var copiedField =
                copied.draft().fields().stream()
                        .filter(f -> f.code().equals("customer_id"))
                        .findFirst()
                        .orElseThrow();
        assertThat(relation.fieldId()).isEqualTo(copiedField.id());
        assertThat(copied.indexes()).hasSize(1);
        assertThat(copied.indexes().getFirst().fieldIds())
                .containsExactlyInAnyOrderElementsOf(
                        copied.relations().stream().map(Relation::fieldId).toList());
        publish(copied);
        assertThat(
                        databaseMetadata
                                .readTable("public", copied.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("customer_id") && !c.nullable());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 6})
    void timestampPrecisionCanBeAdoptedWritableWithoutChangingData(int precision) {
        String main =
                table(
                        "public",
                        "temporal",
                        "id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,name varchar(200)"
                                + " NOT NULL,occurred_at timestamp("
                                + precision
                                + "),zoned_at timestamp("
                                + precision
                                + ") with time zone,creator varchar(64) DEFAULT '',"
                                + "create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                                + "updater varchar(64) DEFAULT '',update_time timestamp(6) NOT NULL"
                                + " DEFAULT CURRENT_TIMESTAMP,deleted smallint NOT NULL DEFAULT 0"
                                + " CHECK(deleted IN(0,1))",
                        false);
        String qualified = PostgreSqlCommands.table("public", main);
        jdbc.update(
                "INSERT INTO "
                        + qualified
                        + "(name,occurred_at,zoned_at) VALUES"
                        + " ('精度测试','2026-09-06 12:34:56.123456','2026-09-06 12:34:56.123456+08')");
        var before = jdbc.queryForList("SELECT * FROM " + qualified);
        var check = tables.preflight("public", main);
        assertThat(check.allowed()).as(check.checks().toString()).isTrue();
        assertThat(check.readOnly()).as(check.checks().toString()).isFalse();

        var d = adopt(main);
        assertThat(d.mainBinding().readOnly()).isFalse();
        for (var field : d.draft().fields()) {
            var option = d.fieldOptions().get(field.id());
            if (FieldTypeEnum.DATETIME.matches(field.type()))
                assertThat(
                                com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler.sqlType(
                                        field, option))
                        .isEqualTo(option.nativeType());
        }
        d = save(d, List.of(), List.of(), List.of(), d.mainBinding());
        assertThat(d.mainBinding().readOnly()).isFalse();
        publish(d);

        var actual = databaseMetadata.readTable("public", main).orElseThrow();
        assertThat(tables.fingerprint(actual)).isEqualTo(check.fingerprint());
        assertThat(jdbc.queryForList("SELECT * FROM " + qualified)).isEqualTo(before);
        assertThat(tables.drift(d.draft().id())).isEmpty();
        assertThat(designs.published(d.draft().id()).mainBinding().readOnly()).isFalse();
    }

    @Test
    void unsupportedTypeAndGeneratedColumnStillRequireReadonlyAdoption() {
        String main =
                table(
                        "public",
                        "unsupported",
                        "id bigint PRIMARY KEY,name varchar(200),duration interval,"
                                + "computed bigint GENERATED ALWAYS AS (id + 1) STORED",
                        true);
        var check = tables.preflight("public", main);
        assertThat(check.allowed()).isTrue();
        assertThat(check.readOnly()).isTrue();
        assertThat(check.checks())
                .filteredOn(c -> PublishCheckEnum.COLUMN_READ_ONLY.matches(c.code()))
                .extracting(Check::message)
                .containsExactly(
                        "duration 使用暂不支持写入的类型或生成方式，保留原结构并只读", "computed 使用暂不支持写入的类型或生成方式，保留原结构并只读");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "timestamp(7) without time zone",
                "timestamp(-1) without time zone",
                "timestamp(6) without time zone; SELECT 1"
            })
    void timestampTypeValidationRejectsInvalidPrecisionAndSql(String type) {
        assertThat(com.lingan.ucp.nocode.metadata.service.table.DataTableService.supported(type))
                .isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PostgreSqlCommands.type(type));
    }

    @Test
    void newMainCanBindExistingEmptyDetailAndRepairMissingBaseFields() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_name varchar(80)",
                        false);
        assertThat(tables.preflight("public", child).checks())
                .noneMatch(c -> PublishCheckEnum.READ_ONLY.matches(c.code()));
        var d = create(List.of(detail("public", child, "order_ref", true, false, true)));
        assertThat(d.details().getFirst().binding().readOnly()).isFalse();
        assertThat(plan(d).changes())
                .anyMatch(c -> SchemaChangeEnum.REPAIR_BASE_FIELDS.matches(c.kind()));
        publish(d);
        var actual = databaseMetadata.readTable("public", child).orElseThrow();
        assertThat(BaseDOColumns.differences(actual)).isEmpty();
        assertThat(actual.constraints())
                .anyMatch(c -> c.kind().equals("f") && c.definition().contains("order_ref"));
        assertThat(actual.columns())
                .filteredOn(c -> c.name().equals("order_ref"))
                .allMatch(c -> !c.nullable());
        var frozen = designs.published(d.draft().id());
        assertThat(frozen.details().getFirst().binding().keyColumn()).isEqualTo("line_key");
        assertThat(tables.detail("public", child, false).table().management())
                .isEqualTo(TableManagementEnum.ADOPTED.getCode());
        assertThat(tables.drift(d.draft().id())).isEmpty();
        // 后续名称调整不重复补列或重建父键。
        var next = edit(d);
        publish(save(next, next.details(), next.relations(), next.indexes(), next.mainBinding()));
        assertThat(tables.drift(d.draft().id())).isEmpty();
    }

    @Test
    void orphanRowsBlockWritableButCanPublishExplicitReadonlyMapping() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_name varchar(80)",
                        true);
        jdbc.update(
                "INSERT INTO public."
                        + child
                        + "(line_key,order_ref,item_name) VALUES(1,999,'历史明细')");
        var d = create(List.of(detail("public", child, "order_ref", false, false, false)));
        var blocked = plan(d);
        assertThat(blocked.checks())
                .anyMatch(c -> PublishCheckEnum.UNLINKED_ROWS.matches(c.code()) && c.blocking());
        assertThat(databaseMetadata.relationExists("public", d.draft().tableName())).isFalse();
        var old = d.details().getFirst();
        var b = old.binding();
        var readonly =
                new TableBinding(
                        b.source(),
                        b.schemaName(),
                        b.keyColumn(),
                        b.parentColumn(),
                        b.structureMode(),
                        true,
                        false,
                        b.fingerprint());
        var changed =
                new Detail(
                        old.id(),
                        old.code(),
                        old.name(),
                        old.tableName(),
                        old.state(),
                        old.fields(),
                        old.fieldOptions(),
                        old.indexes(),
                        readonly);
        var next = save(d, List.of(changed), d.relations(), d.indexes(), d.mainBinding());
        publish(next);
        assertThat(designs.published(d.draft().id()).details().getFirst().binding().readOnly())
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT order_ref FROM public." + child, Long.class))
                .isEqualTo(999L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uuid", "integer", "varchar(36)"})
    void adoptedMainUsesItsActualPrimaryKeyForNewDetails(String keyType) {
        String main =
                table(
                        "public",
                        "parent",
                        "business_key " + keyType + " PRIMARY KEY,name varchar(80)",
                        true);
        var d = adopt(main);
        String child = "biz_" + fixture.prefix + "newitems";
        owned.add(new ObjectTables.Ref("public", child));
        var detail =
                new Detail(
                        null,
                        "items",
                        "新明细",
                        child,
                        "ACTIVE",
                        List.of(fixture.field("line", "item_name", "TEXT", 0)),
                        Map.of(),
                        List.of());
        d = save(d, List.of(detail), List.of(), List.of(), d.mainBinding());
        publish(d);
        var parentColumn =
                databaseMetadata.readTable("public", child).orElseThrow().columns().stream()
                        .filter(c -> c.name().equals("parent_id"))
                        .findFirst()
                        .orElseThrow();
        assertThat(parentColumn.nativeType().replace("character varying", "varchar"))
                .isEqualTo(keyType);
        assertThat(tables.drift(d.draft().id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + main, Long.class)).isZero();
    }

    @Test
    void existingMainAndDetailRetainDataAndPublishScopedUniqueIndex() {
        String main =
                table(
                        "public",
                        "parent",
                        "business_key integer PRIMARY KEY,name varchar(80)",
                        true);
        String child =
                table(
                        "public",
                        "items",
                        "line_key integer PRIMARY KEY,owner_key integer,item_code varchar(80)",
                        true);
        jdbc.update("INSERT INTO public." + main + "(business_key,name) VALUES(1,'一'),(2,'二')");
        jdbc.update(
                "INSERT INTO public."
                        + child
                        + "(line_key,owner_key,item_code) VALUES(1,1,'A'),(2,2,'A')");
        var d = adopt(main);
        d =
                save(
                        d,
                        List.of(detail("public", child, "owner_key", true, false, false)),
                        List.of(),
                        List.of(),
                        d.mainBinding());
        var item = d.details().getFirst();
        String field =
                item.fieldOptions().entrySet().stream()
                        .filter(e -> e.getValue().columnName().equals("item_code"))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow();
        d =
                save(
                        d,
                        d.details(),
                        List.of(),
                        List.of(
                                new Index(
                                        null, "item_code", "同订单明细编码", true, List.of(field), true)),
                        d.mainBinding());
        publish(d);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + child, Long.class))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO public."
                                                + child
                                                + "(line_key,owner_key,item_code) VALUES(3,1,'A')"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        var copied =
                designs.copy(
                        new Copy(
                                d.draft().id(),
                                fixture.prefix + "copy",
                                "复制对象",
                                "biz_" + fixture.prefix + "copy"),
                        10001);
        assertThat(copied.mainBinding().source()).isEqualTo(ObjectSourceEnum.GENERATED.getCode());
        assertThat(copied.details().getFirst().binding().source())
                .isEqualTo(ObjectSourceEnum.GENERATED.getCode());
        assertThat(copied.indexes().getFirst().parentScoped()).isTrue();
    }

    @Test
    void retainedTableCannotGainIndexesOrSilentlyChangeItsColumnMapping() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_code varchar(80)",
                        true);
        var d = create(List.of(detail("public", child, "order_ref", false, false, false)));
        var item = d.details().getFirst();
        String key = item.fields().getFirst().id();
        var indexed =
                save(
                        d,
                        d.details(),
                        List.of(),
                        List.of(new Index(null, "line_index", "索引", false, List.of(key))),
                        d.mainBinding());
        assertThat(plan(indexed).checks())
                .anyMatch(
                        c -> PublishCheckEnum.STRUCTURE_RETAINED.matches(c.code()) && c.blocking());
        var wrong =
                new TableBinding(
                        item.binding().source(),
                        "public",
                        "line_key",
                        "changed_parent",
                        item.binding().structureMode(),
                        false,
                        false,
                        item.binding().fingerprint());
        var changed =
                new Detail(
                        item.id(),
                        item.code(),
                        item.name(),
                        item.tableName(),
                        item.state(),
                        item.fields(),
                        item.fieldOptions(),
                        List.of(),
                        wrong);
        assertThatThrownBy(
                        () ->
                                save(
                                        indexed,
                                        List.of(changed),
                                        List.of(),
                                        List.of(),
                                        indexed.mainBinding()))
                .hasMessageContaining("不可原地更换");
    }

    @Test
    void bindingFingerprintAndExclusiveOwnershipAreEnforced() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_code varchar(80)",
                        true);
        var candidate = detail("public", child, "order_ref", true, false, false);
        jdbc.execute("COMMENT ON TABLE public." + child + " IS 'changed'");
        assertThatThrownBy(() -> create(List.of(candidate))).hasMessageContaining("指纹");
        var first = create(List.of(detail("public", child, "order_ref", true, false, false)));
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                fixture.createRequest("other"),
                                                Settings.defaults(),
                                                null,
                                                List.of(),
                                                List.of(),
                                                List.of(
                                                        detail(
                                                                "public",
                                                                child,
                                                                "order_ref",
                                                                true,
                                                                false,
                                                                false))),
                                        10001))
                .hasMessageContaining("已被其他明细绑定");
        var p = plan(first);
        jdbc.execute("COMMENT ON TABLE public." + child + " IS 'changed again'");
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(p.id(), "过期选表预检"), 10001))
                .hasMessageContaining("变化");
        assertThat(databaseMetadata.relationExists("public", first.draft().tableName())).isFalse();
    }

    @Test
    void crossSchemaBindingsRetainOwnershipAndParticipateInDriftAndReconciliation() {
        String schema = fixture.prefix + "schema";
        jdbc.execute("CREATE SCHEMA " + PostgreSqlCommands.identifier(schema));
        schemas.add(schema);
        String child =
                table(
                        schema,
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_code varchar(80)",
                        true);
        var d = create(List.of(detail(schema, child, "order_ref", true, false, false)));
        publish(d);
        assertThat(tables.owner(schema, child).getId().toString()).isEqualTo(d.draft().id());
        assertThat(tables.drift(d.draft().id())).isEmpty();
        jdbc.execute(
                "ALTER TABLE "
                        + PostgreSqlCommands.table(schema, child)
                        + " ADD COLUMN external_note varchar(80)");
        assertThat(tables.drift(d.draft().id())).anyMatch(Check::blocking);
        var reconcile = servicesContext.getBean(ObjectReconcileService.class);
        var preview = reconcile.preview(d.draft().id());
        assertThat(preview.allowed()).as(preview.checks().toString()).isTrue();
        var current = designs.get(d.draft().id());
        var draft =
                reconcile.apply(
                        new com.lingan.ucp.nocode.api.ObjectReconciliation.Apply(
                                d.draft().id(),
                                current.draft().lockVersion(),
                                preview.fingerprint(),
                                "同步明细兼容新增列"),
                        10001);
        publish(draft);
        assertThat(tables.drift(d.draft().id())).isEmpty();
        assertThat(designs.get(d.draft().id()).details().getFirst().fieldOptions().values())
                .anyMatch(o -> o.columnName().equals("external_note"));
    }

    @Test
    void existingReferenceColumnIsReusedAndRemovingRelationshipPreservesIt() {
        String target =
                table("public", "target", "business_key bigint PRIMARY KEY,name varchar(80)", true);
        jdbc.update("INSERT INTO public." + target + "(business_key,name) VALUES(7,'客户')");
        var t = adopt(target);
        publish(t);
        String source =
                table(
                        "public",
                        "source",
                        "source_key bigint PRIMARY KEY,name varchar(80),customer_key bigint",
                        true);
        jdbc.update(
                "INSERT INTO public." + source + "(source_key,name,customer_key) VALUES(1,'订单',7)");
        var pre = tables.preflight("public", source);
        var d =
                tables.adopt(
                        new Adoption(
                                "public",
                                source,
                                fixture.prefix + "source",
                                "订单",
                                "name",
                                pre.fingerprint()),
                        10001);
        String field =
                d.fieldOptions().entrySet().stream()
                        .filter(e -> "customer_key".equals(e.getValue().columnName()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow();
        var r =
                new Relation(
                        null,
                        "customer",
                        "客户",
                        RelationTypeEnum.REFERENCE.getCode(),
                        t.draft().id(),
                        field,
                        null,
                        false,
                        DeletePolicyEnum.RESTRICT.getCode());
        d = save(d, List.of(), List.of(r), List.of(), d.mainBinding());
        publish(d);
        assertThat(d.relations().getFirst().fieldId()).isEqualTo(field);
        assertThat(databaseMetadata.readTable("public", source).orElseThrow().columns())
                .noneMatch(c -> c.name().equals("customer_id"));
        var next = edit(d);
        next = save(next, List.of(), List.of(), List.of(), next.mainBinding());
        publish(next);
        assertThat(designs.get(d.draft().id()).fieldOptions()).containsKey(field);
        assertThat(jdbc.queryForObject("SELECT customer_key FROM public." + source, Long.class))
                .isEqualTo(7L);
    }

    @Test
    void mixedPublicationRollsBackBothNewTableAndExistingTableRepair() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,item_name varchar(80)",
                        false);
        var d = create(List.of(detail("public", child, "order_ref", true, false, true)));
        var p = plan(d);
        assertThat(p.checks()).noneMatch(Check::blocking);
        writeFailure.failAfter("ALTER TABLE");
        try {
            assertThatThrownBy(() -> publisher.execute(new ExecutePlan(p.id(), "验证混合表事务回滚"), 10001))
                    .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        } finally {
            writeFailure.clear();
        }
        assertThat(databaseMetadata.relationExists("public", d.draft().tableName())).isFalse();
        assertThat(databaseMetadata.readTable("public", child).orElseThrow().columns())
                .extracting(DatabaseMetadata.Column::name)
                .containsExactly("line_key", "item_name");
        publish(d);
        assertThat(databaseMetadata.readTable("public", child).orElseThrow().columns())
                .anyMatch(c -> c.name().equals("order_ref"));
        assertThat(tables.drift(d.draft().id())).isEmpty();
    }

    @Test
    void rowSecurityCannotBeBypassedByRequestingWritableManagedBinding() {
        String child =
                table(
                        "public",
                        "items",
                        "line_key bigint PRIMARY KEY,order_ref bigint,item_name varchar(80)",
                        true);
        jdbc.execute("ALTER TABLE public." + child + " ENABLE ROW LEVEL SECURITY");
        assertThatThrownBy(
                        () ->
                                create(
                                        List.of(
                                                detail(
                                                        "public",
                                                        child,
                                                        "order_ref",
                                                        true,
                                                        false,
                                                        false))))
                .hasMessageContaining("RLS");
        var d = create(List.of(detail("public", child, "order_ref", false, false, false)));
        assertThat(d.details().getFirst().binding().readOnly()).isTrue();
        publish(d);
    }

    @Test
    void oldSnapshotDefaultsDoNotChangePhysicalSignatures() throws Exception {
        var d =
                create(
                        List.of(
                                new Detail(
                                        null,
                                        "items",
                                        "明细",
                                        "biz_" + fixture.prefix + "items",
                                        "ACTIVE",
                                        List.of(fixture.field("item", "name", "TEXT", 0)),
                                        Map.of(),
                                        List.of())));
        var definition = designs.definition(d.draft().id());
        var tree = mapper.valueToTree(definition);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("mainBinding");
        tree.path("details")
                .forEach(
                        node ->
                                ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                                        .remove("binding"));
        var legacy = mapper.treeToValue(tree, Definition.class);
        var compiler =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler.class);
        assertThat(compiler.physicalSignature(legacy))
                .isEqualTo(compiler.physicalSignature(definition));
        assertThat(
                        ObjectTables.fromKey(
                                        new ObjectTables.Ref("public", "@table:ambiguous")
                                                .key("public"),
                                        "public")
                                .name())
                .isEqualTo("@table:ambiguous");
    }
}
