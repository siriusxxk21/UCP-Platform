package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlDatabaseMetadataMapper;
import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

/** 使用当前开发库和底座 MyBatis 插件验证实际 Mapper，不替换为模拟持久层。 */
class ObjectDraftIntegrationTest extends NocodeIntegrationSupport {
    @Test
    void toolsAndTestsShareTheOnlyManagedDataSource() {
        assertThat(toolContext.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(((DynamicRoutingDataSource) ds).getDataSources()).hasSize(1);
        assertThat(jdbc.getDataSource()).isSameAs(ds);
        assertThat(((DataSourceTransactionManager) manager).getDataSource()).isSameAs(ds);
        assertThat(databaseTool.flyway().getConfiguration().getDataSource()).isSameAs(ds);
        // 使用实际生效配置验证底座元数据适配装配，避免工具配置与正式应用分叉。
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.setEnvironment(toolContext.getEnvironment()))
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                com.lingan.ucp.framework.mybatis.config
                                        .OsDatabaseMetadataAutoConfiguration.class))
                .withBean(
                        PostgreSqlDatabaseMetadataMapper.class,
                        () -> org.mockito.Mockito.mock(PostgreSqlDatabaseMetadataMapper.class))
                .run(context -> assertThat(context).hasSingleBean(DatabaseMetadataReader.class));
    }

    @Test
    void persistsAllBasicTypesWithoutBusinessDdl() throws Exception {
        var tablesBefore =
                jdbc.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY"
                                + " tablename",
                        String.class);
        var r = createRequest("");
        var fields = new ArrayList<>(r.fields());
        int sort = 1;
        for (String type : List.of("TEXTAREA", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME"))
            fields.add(field("new-" + type, type.toLowerCase(), type, sort++));
        var saved =
                service.create(
                        new SaveObjectDraft(
                                null,
                                null,
                                r.objectCode(),
                                r.objectName(),
                                r.description(),
                                r.tableName(),
                                r.titleFieldKey(),
                                fields,
                                List.of()),
                        10001L,
                        UUID.randomUUID());
        assertThat(saved.fields()).hasSize(7);
        assertThat(service.get(saved.id())).isEqualTo(saved);
        assertThat(saved.titleFieldId()).matches("[1-9][0-9]*");
        assertThat(saved.lockVersion()).isZero();
        String schema =
                jdbc.queryForObject(
                        "SELECT schema_json::text FROM public.nocode_object_version WHERE"
                                + " object_id=?",
                        String.class,
                        Long.valueOf(saved.id()));
        assertThat(mapper.readTree(schema).path("fields").size()).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_published_version_no FROM public.nocode_object"
                                        + " WHERE id=?",
                                Integer.class,
                                Long.valueOf(saved.id())))
                .isNull();
        assertThat(
                        jdbc.queryForList(
                                "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY"
                                        + " tablename",
                                String.class))
                .isEqualTo(tablesBefore);
    }

    @Test
    void updatesKeepStableIdsAndOmittedFieldsAndExplicitDeletion() {
        var original = create("");
        var add =
                service.update(
                        edit(
                                original,
                                List.of(field("new-extra", "extra", "INTEGER", 1)),
                                List.of(),
                                original.titleFieldId()),
                        10001L,
                        UUID.randomUUID());
        assertThat(add.fields()).hasSize(2);
        assertThat(add.fields().getFirst().id()).isEqualTo(original.titleFieldId());
        var extraId = add.fields().get(1).id();
        var changed =
                service.update(
                        edit(add, List.of(), List.of(extraId), add.titleFieldId()),
                        10001L,
                        UUID.randomUUID());
        assertThat(changed.fields()).hasSize(1);
        assertThat(changed.lockVersion()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        edit(
                                                changed,
                                                List.of(),
                                                List.of(changed.titleFieldId()),
                                                changed.titleFieldId()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOf(ServiceException.class);
        assertThat(service.get(changed.id())).isEqualTo(changed);
    }

    @Test
    void newFieldCanReplaceTitleInSameSave() {
        var original = create("");
        var next =
                service.update(
                        edit(
                                original,
                                List.of(field("replacement", "title", "TEXT", 0)),
                                List.of(original.titleFieldId()),
                                "replacement"),
                        10001L,
                        UUID.randomUUID());
        assertThat(next.titleFieldId()).isNotEqualTo(original.titleFieldId());
        assertThat(next.fields()).hasSize(1);
    }

    @Test
    void rejectsForeignFieldAndStaleVersion() {
        var a = create("a");
        var b = create("b");
        assertThatThrownBy(
                        () ->
                                service.update(
                                        edit(a, b.fields(), List.of(), a.titleFieldId()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(NocodeErrorCodes.FOREIGN_FIELD));
        service.update(edit(a, List.of(), List.of(), a.titleFieldId()), 10001L, UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                service.update(
                                        edit(a, List.of(), List.of(), a.titleFieldId()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(NocodeErrorCodes.CONFLICT));
        assertThat(service.get(b.id())).isEqualTo(b);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE public.nocode_object SET",
                "UPDATE public.nocode_object_version SET",
                "INSERT INTO public.nocode_field",
                "INSERT INTO public.nocode_operation_log"
            })
    void failuresRollbackWholeObjectAndAudit(String failOn) {
        var before = create("");
        long audits =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.nocode_operation_log WHERE object_id=?",
                        Long.class,
                        Long.valueOf(before.id()));
        writeFailure.failAfter(failOn);
        try {
            assertThatThrownBy(
                            () ->
                                    service.update(
                                            edit(
                                                    before,
                                                    List.of(field("extra", "extra", "INTEGER", 1)),
                                                    List.of(),
                                                    before.titleFieldId()),
                                            10001L,
                                            UUID.randomUUID()))
                    .hasStackTraceContaining("B1 intentional failure after MyBatis database write");
        } finally {
            writeFailure.clear();
        }
        assertThat(service.get(before.id())).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_operation_log WHERE"
                                        + " object_id=?",
                                Long.class,
                                Long.valueOf(before.id())))
                .isEqualTo(audits);
    }

    @Test
    void concurrentUpdatesHaveExactlyOneWinner() throws Exception {
        var before = create("");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> update =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            service.update(
                                    edit(before, List.of(), List.of(), before.titleFieldId()),
                                    10001L,
                                    UUID.randomUUID());
                            return 0;
                        } catch (ServiceException ex) {
                            return ex.getCode();
                        }
                    };
            var a = executor.submit(update);
            var b = executor.submit(update);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, NocodeErrorCodes.CONFLICT);
        }
    }

    @Test
    void concurrentDuplicateCreationHasExactlyOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> create =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            create("");
                            return 0;
                        } catch (ServiceException ex) {
                            return ex.getCode();
                        }
                    };
            var a = executor.submit(create);
            var b = executor.submit(create);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, NocodeErrorCodes.DUPLICATE);
        }
    }

    @Test
    void namesCannotClaimExistingOrReservedTablesAndFields() {
        var r = createRequest("");
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                r.objectCode(),
                                                r.objectName(),
                                                null,
                                                "system_users",
                                                r.titleFieldKey(),
                                                r.fields(),
                                                List.of()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(NocodeErrorCodes.INVALID));
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                r.objectCode(),
                                                r.objectName(),
                                                null,
                                                r.tableName(),
                                                "id",
                                                List.of(field("id", "id", "TEXT", 0)),
                                                List.of()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void whitespaceCannotBypassCandidateTableReservation() {
        var first = create("");
        var second = createRequest("_other");
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                second.objectCode(),
                                                second.objectName(),
                                                null,
                                                "  " + first.tableName() + "  ",
                                                second.titleFieldKey(),
                                                second.fields(),
                                                List.of()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(NocodeErrorCodes.DUPLICATE));
        assertThat(service.page(1, 20, null, prefix).getTotal()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"b_company", "nocode_object", "biz_", "biz_1company", "nocode_data_company"})
    void rejectsTablesOutsideGeneratedNamespace(String tableName) {
        var r = createRequest("");
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                r.objectCode(),
                                                r.objectName(),
                                                null,
                                                tableName,
                                                r.titleFieldKey(),
                                                r.fields(),
                                                List.of()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(NocodeErrorCodes.INVALID));
        assertThat(service.page(1, 20, null, prefix).getTotal()).isZero();
    }

    @Test
    void legacyPhysicalNameIsRetainedButCannotBeUsedForAnotherNewName() {
        var created = create("legacy");
        String legacy = "nocode_data_" + prefix + "legacy";
        // 模拟升级前已保存的独立夹具，精确限定当前对象；不触及开发示例。
        jdbc.update(
                "UPDATE public.nocode_object_table SET table_name=? WHERE object_version_id=(SELECT"
                        + " id FROM public.nocode_object_version WHERE object_id=?)",
                legacy,
                Long.parseLong(created.id()));
        var loaded = service.get(created.id());
        var saved =
                service.update(
                        edit(loaded, List.of(), List.of(), loaded.titleFieldId()),
                        10001,
                        UUID.randomUUID());
        assertThat(saved.tableName()).isEqualTo(legacy);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        new SaveObjectDraft(
                                                saved.id(),
                                                saved.lockVersion(),
                                                saved.objectCode(),
                                                saved.objectName(),
                                                null,
                                                legacy + "_other",
                                                saved.titleFieldId(),
                                                List.of(),
                                                List.of()),
                                        10001,
                                        UUID.randomUUID()))
                .hasMessageContaining("biz_");
    }

    @Test
    void refusesExistingPhysicalTableInGeneratedNamespace() {
        var r = createRequest("");
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            // 名称仅来自测试生成的固定前缀和 UUID，不接受外部输入。
                            jdbc.execute("CREATE TABLE public." + r.tableName() + " (id bigint)");
                            assertThatThrownBy(() -> service.create(r, 10001L, UUID.randomUUID()))
                                    .isInstanceOfSatisfying(
                                            ServiceException.class,
                                            ex ->
                                                    assertThat(ex.getCode())
                                                            .isEqualTo(NocodeErrorCodes.DUPLICATE));
                            tx.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass(?) IS NULL",
                                Boolean.class,
                                "public." + r.tableName()))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCRIPT", "REFERENCE", "text"})
    void rejectsUnsupportedTypes(String type) {
        var r = createRequest("");
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                r.objectCode(),
                                                r.objectName(),
                                                null,
                                                r.tableName(),
                                                "t",
                                                List.of(field("t", "name", type, 0)),
                                                List.of()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void validatesBoundsAndPaginationAndLiteralFilters() {
        var before = create("");
        var invalid =
                new FieldDefinition(
                        "new-invalid", null, "bad", "bad", "DECIMAL", null, 2, 3, false, false, 1);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        edit(
                                                before,
                                                List.of(invalid),
                                                List.of(),
                                                before.titleFieldId()),
                                        10001L,
                                        UUID.randomUUID()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.page(0, 20, null, null))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.page(1, 101, null, null))
                .isInstanceOf(ServiceException.class);
        assertThat(service.page(1, 1, null, prefix).getTotal()).isEqualTo(1);
        assertThat(service.page(2, 1, null, prefix).getList()).isEmpty();
        assertThat(service.page(1, 20, null, prefix + "%").getTotal()).isZero();
        assertThatThrownBy(() -> service.get("0")).isInstanceOf(ServiceException.class);
    }

    @Test
    void deployedStateCannotBeEdited() {
        var before = create("");
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            jdbc.update(
                                    "UPDATE public.nocode_object SET current_published_version_no=1"
                                            + " WHERE id=?",
                                    Long.valueOf(before.id()));
                            assertThatThrownBy(
                                            () ->
                                                    service.update(
                                                            edit(
                                                                    before,
                                                                    List.of(),
                                                                    List.of(),
                                                                    before.titleFieldId()),
                                                            10001L,
                                                            UUID.randomUUID()))
                                    .isInstanceOfSatisfying(
                                            ServiceException.class,
                                            ex ->
                                                    assertThat(ex.getCode())
                                                            .isEqualTo(
                                                                    NocodeErrorCodes
                                                                            .UNSUPPORTED_STATE));
                            tx.setRollbackOnly();
                        });
    }

    @Test
    void metadataReadsRealKeysCommentsIndexesAndGeneratedColumns() {
        String table = "biz_" + prefix;
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            assertThat(databaseMetadata.relationExists("public", table)).isFalse();
                            jdbc.execute(
                                    "CREATE TABLE public."
                                            + table
                                            + " (id bigint, tenant_code text, item_code integer,"
                                            + " amount numeric(12,3) DEFAULT 0 NOT NULL CHECK"
                                            + " (amount >= 0), next_amount numeric GENERATED ALWAYS"
                                            + " AS (amount + 1) STORED, serial bigint GENERATED BY"
                                            + " DEFAULT AS IDENTITY, obsolete text, PRIMARY KEY"
                                            + " (tenant_code,item_code), FOREIGN KEY"
                                            + " (tenant_code,item_code) REFERENCES public."
                                            + table
                                            + " (tenant_code,item_code))");
                            jdbc.execute("COMMENT ON TABLE public." + table + " IS '元数据读取验证'");
                            jdbc.execute("COMMENT ON COLUMN public." + table + ".amount IS '金额说明'");
                            jdbc.execute("ALTER TABLE public." + table + " DROP COLUMN obsolete");
                            jdbc.execute(
                                    "CREATE UNIQUE INDEX "
                                            + table
                                            + "_part ON public."
                                            + table
                                            + " (tenant_code,id) WHERE id IS NOT NULL");
                            assertThat(databaseMetadata.relationExists("public", table)).isTrue();
                            var actual = databaseMetadata.readTable("public", table).orElseThrow();
                            assertThat(actual.relation().comment()).isEqualTo("元数据读取验证");
                            assertThat(actual.relation().kind()).isEqualTo("TABLE");
                            var columns =
                                    actual.columns().stream()
                                            .collect(
                                                    java.util.stream.Collectors.toMap(
                                                            com.lingan.ucp.framework.mybatis.core
                                                                            .metadata
                                                                            .DatabaseMetadata.Column
                                                                    ::name,
                                                            x -> x));
                            assertThat(columns).doesNotContainKey("obsolete");
                            assertThat(columns.get("id").primaryKeyPosition()).isZero();
                            assertThat(columns.get("tenant_code").primaryKeyPosition())
                                    .isEqualTo(1);
                            assertThat(columns.get("item_code").primaryKeyPosition()).isEqualTo(2);
                            assertThat(columns.get("amount").nativeType())
                                    .isEqualTo("numeric(12,3)");
                            assertThat(columns.get("amount").nullable()).isFalse();
                            assertThat(columns.get("amount").comment()).isEqualTo("金额说明");
                            assertThat(columns.get("amount").defaultExpression()).isNotBlank();
                            assertThat(columns.get("serial").identityKind()).isEqualTo("d");
                            assertThat(columns.get("next_amount").generatedKind()).isEqualTo("s");
                            assertThat(actual.constraints())
                                    .extracting(x -> x.kind())
                                    .contains("p", "f", "c");
                            assertThat(actual.indexes())
                                    .anySatisfy(
                                            index -> {
                                                assertThat(index.unique()).isTrue();
                                                assertThat(index.valid()).isTrue();
                                                assertThat(index.definition())
                                                        .contains("WHERE (id IS NOT NULL)");
                                            });
                            tx.setRollbackOnly();
                        });
        assertThat(databaseMetadata.relationExists("public", table)).isFalse();
    }

    @Test
    void metadataUsesExplicitSchemaAndLiteralNamesWithoutGuessing() {
        String table = "biz_" + prefix;
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            jdbc.execute("CREATE TABLE public." + table + " (id bigint)");
                            jdbc.execute(
                                    "CREATE VIEW public."
                                            + table
                                            + "_view AS SELECT id FROM public."
                                            + table);
                            assertThat(
                                            databaseMetadata
                                                    .readTable("public", table + "_view")
                                                    .orElseThrow()
                                                    .relation()
                                                    .kind())
                                    .isEqualTo("VIEW");
                            assertThat(databaseMetadata.readTable("pg_catalog", table)).isEmpty();
                            assertThat(databaseMetadata.readTable("public", table + "' OR '1'='1"))
                                    .isEmpty();
                            assertThat(databaseMetadata.listRelations("public", prefix, 10))
                                    .hasSize(2);
                            assertThat(databaseMetadata.listRelations("public", prefix + "%", 10))
                                    .isEmpty();
                            assertThat(databaseMetadata.listRelations("public", prefix, 1))
                                    .hasSize(1);
                            assertThat(
                                            databaseMetadata.relationExists(
                                                    "public", "nocode_stable_id_seq"))
                                    .isTrue();
                            assertThat(databaseMetadata.readTable("public", "nocode_stable_id_seq"))
                                    .isEmpty();
                            assertThat(databaseMetadata.isReservedIdentifier("SELECT")).isTrue();
                            assertThat(databaseMetadata.isReservedIdentifier("a".repeat(64)))
                                    .isFalse();
                            tx.setRollbackOnly();
                        });
    }

    @Test
    void metadataRejectsUnboundedOrInvalidReads() {
        assertThatThrownBy(() -> databaseMetadata.listRelations("public", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> databaseMetadata.listRelations("public", "", 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> databaseMetadata.readTable("", "nocode_object"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> databaseMetadata.readTable("public", "表".repeat(22)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataAutoConfigurationUsesDefaultOrExplicitPrimaryAndSkipsOtherDialects() {
        var runner =
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withConfiguration(
                                org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                        com.lingan.ucp.framework.mybatis.config
                                                .OsDatabaseMetadataAutoConfiguration.class))
                        .withBean(
                                PostgreSqlDatabaseMetadataMapper.class,
                                () ->
                                        org.mockito.Mockito.mock(
                                                PostgreSqlDatabaseMetadataMapper.class));
        runner.withPropertyValues(
                        "spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://localhost/test")
                .run(context -> assertThat(context).hasSingleBean(DatabaseMetadataReader.class));
        runner.withPropertyValues(
                        "spring.datasource.dynamic.primary=business",
                        "spring.datasource.dynamic.datasource.business.url=jdbc:postgresql://localhost/test")
                .run(context -> assertThat(context).hasSingleBean(DatabaseMetadataReader.class));
        runner.withPropertyValues(
                        "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://localhost/test")
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseMetadataReader.class));
    }

    @Test
    void migrationReplayIsNoopAndDdlFailureRollsBackInCurrentDatabase() throws Exception {
        assertThat(databaseTool.flyway().migrate().migrationsExecuted).isZero();
        String schema = "nocode_b1_" + UUID.randomUUID().toString().replace("-", "");
        String renameSql =
                new String(
                        Objects.requireNonNull(
                                        getClass()
                                                .getResourceAsStream(
                                                        "/db/nocode/V003__unify_nocode_namespace.sql"))
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        String sql =
                new String(
                        Objects.requireNonNull(
                                        getClass()
                                                .getResourceAsStream(
                                                        "/db/nocode/V001__object_drafts.sql"))
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                status -> {
                                                    jdbc.execute("CREATE SCHEMA " + schema);
                                                    jdbc.execute(
                                                            sql.replace("public.", schema + "."));
                                                    // 在当前开发库的回滚事务中重放 V001 -> V003，验证既有数据迁移。
                                                    jdbc.execute(
                                                            "INSERT INTO "
                                                                    + schema
                                                                    + ".lc_object"
                                                                    + " (id,object_code,object_name,physical_name_seed,title_field_stable_id,created_by,updated_by)"
                                                                    + " VALUES"
                                                                    + " (1,'legacy','迁移验证','seed',11,1,1)");
                                                    jdbc.execute(
                                                            "INSERT INTO "
                                                                    + schema
                                                                    + ".lc_object_version"
                                                                    + " (id,object_id,version_no,schema_json,schema_checksum,created_by)"
                                                                    + " VALUES"
                                                                    + " (2,1,1,'{\"tableName\":\"b_legacy\",\"fields\":[]}',repeat('a',64),1)");
                                                    jdbc.execute(
                                                            "INSERT INTO "
                                                                    + schema
                                                                    + ".lc_object_table"
                                                                    + " (id,object_version_id,stable_table_id,table_code,table_name,table_role)"
                                                                    + " VALUES"
                                                                    + " (3,2,12,'main','b_legacy','MAIN')");
                                                    jdbc.execute(
                                                            renameSql
                                                                    .replace(
                                                                            "public.", schema + ".")
                                                                    .replace(
                                                                            "'public'",
                                                                            "'" + schema + "'"));
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT table_name FROM"
                                                                                    + " "
                                                                                    + schema
                                                                                    + ".nocode_object_table"
                                                                                    + " WHERE"
                                                                                    + " stable_table_id=12",
                                                                            String.class))
                                                            .isEqualTo("nocode_data_legacy");
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT"
                                                                                + " schema_json->>'tableName'"
                                                                                + " FROM "
                                                                                    + schema
                                                                                    + ".nocode_object_version"
                                                                                    + " WHERE id=2",
                                                                            String.class))
                                                            .isEqualTo("nocode_data_legacy");
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT lock_version"
                                                                                    + " FROM "
                                                                                    + schema
                                                                                    + ".nocode_object"
                                                                                    + " WHERE id=1",
                                                                            Integer.class))
                                                            .isEqualTo(1);
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT to_regclass(?)"
                                                                                    + " IS NULL",
                                                                            Boolean.class,
                                                                            schema + ".lc_object"))
                                                            .isTrue();
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT count(*) FROM"
                                                                                + " information_schema.tables"
                                                                                + " WHERE"
                                                                                + " table_schema=?",
                                                                            Integer.class,
                                                                            schema))
                                                            .isEqualTo(5);
                                                    throw new IllegalStateException(
                                                            "Intentional migration rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)",
                                Boolean.class,
                                schema))
                .isFalse();
    }
}
