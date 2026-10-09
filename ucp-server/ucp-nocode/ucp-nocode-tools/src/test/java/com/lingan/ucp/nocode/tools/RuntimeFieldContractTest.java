package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.runtime.service.record.RecordPersistence;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeFieldContracts;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 旧应用仅在实际提交不兼容字段时拒绝，不能把相同 SQL 类型误判为相同业务契约。 */
class RuntimeFieldContractTest {
    @Test
    void integerCannotWriteReferenceAlthoughBothUseBigint() {
        Definition old = definition("INTEGER", FieldOptions.defaults(), List.of(), List.of());
        Definition current =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("2", "关联")),
                        List.of());
        assertThat(FieldStorage.sqlType(old.fields().getFirst(), old.fieldOptions().get("f")))
                .isEqualTo(
                        FieldStorage.sqlType(
                                current.fields().getFirst(), current.fieldOptions().get("f")));
        assertThatThrownBy(() -> RuntimeFieldContracts.requireCompatible(old, current, Set.of("f")))
                .hasMessageContaining("原字段")
                .hasMessageContaining("同步对象版本");
    }

    @Test
    void selectionSourceAndDictionaryIdentityCannotBeReusedByOldApplication() {
        Definition local = definition("SELECT", FieldOptions.defaults(), List.of(), List.of());
        Definition dictionary = definition("SELECT", dictionary("status"), List.of(), List.of());
        Definition another = definition("SELECT", dictionary("category"), List.of(), List.of());
        assertThatThrownBy(
                        () ->
                                RuntimeFieldContracts.requireCompatible(
                                        local, dictionary, Set.of("f")))
                .hasMessageContaining("同步对象版本");
        assertThatThrownBy(
                        () ->
                                RuntimeFieldContracts.requireCompatible(
                                        dictionary, another, Set.of("f")))
                .hasMessageContaining("同步对象版本");
    }

    @Test
    void referenceTargetChangeBlocksButRenamingDoesNot() {
        Definition old =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("2", "旧名称")),
                        List.of());
        Definition renamed =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("2", "新名称")),
                        List.of());
        Definition another =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("3", "新名称")),
                        List.of());
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(old, renamed, Set.of("f")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RuntimeFieldContracts.requireCompatible(old, another, Set.of("f")))
                .hasMessageContaining("同步对象版本");
    }

    @Test
    void unrelatedFieldsAndEmptyWritesRemainAvailableAfterConversion() {
        Definition old = definition("INTEGER", FieldOptions.defaults(), List.of(), List.of());
        Definition current =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("2", "关联")),
                        List.of());
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(old, current, Set.of("other")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(old, current, Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void compatibleTypeWideningDoesNotRequireApplicationVersionSynchronization() {
        Definition old = definition("TEXT", FieldOptions.defaults(), List.of(), List.of());
        Definition current = definition("TEXTAREA", FieldOptions.defaults(), List.of(), List.of());
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(old, current, Set.of("f")))
                .doesNotThrowAnyException();
        Definition integer = definition("INTEGER", FieldOptions.defaults(), List.of(), List.of());
        Definition decimal = definition("DECIMAL", FieldOptions.defaults(), List.of(), List.of());
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(integer, decimal, Set.of("f")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                RuntimeFieldContracts.requireCompatible(
                                        decimal, integer, Set.of("f")))
                .hasMessageContaining("同步对象版本");
    }

    @Test
    void detailConversionIsCheckedByStableFieldIdentityWithoutBlockingMainFields() {
        Definition old =
                definition("TEXT", FieldOptions.defaults(), List.of(), List.of(detail("INTEGER")));
        Definition current =
                definition(
                        "TEXT", FieldOptions.defaults(), List.of(), List.of(detail("REFERENCE")));
        assertThatCode(() -> RuntimeFieldContracts.requireCompatible(old, current, Set.of("f")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                RuntimeFieldContracts.requireCompatible(
                                        old, current, Set.of("detail_field")))
                .hasMessageContaining("明细字段")
                .hasMessageContaining("同步对象版本");
    }

    @Test
    void latestDefinitionIsReadOnlyAfterCompatibleWriteLockAndOnlyForSubmittedColumns() {
        PostgreSqlCommandMapper commands = mock(PostgreSqlCommandMapper.class);
        DataObjectApi objects = mock(DataObjectApi.class);
        RuntimeSchema schemas = new RuntimeSchema();
        ReflectionTestUtils.setField(schemas, "commands", commands);
        ReflectionTestUtils.setField(schemas, "objects", objects);
        Definition old = definition("INTEGER", FieldOptions.defaults(), List.of(), List.of());
        Definition current =
                definition(
                        "REFERENCE",
                        FieldOptions.defaults(),
                        List.of(relation("2", "关联")),
                        List.of());
        when(objects.getPublished("1")).thenReturn(current);
        RuntimeSchema.Table table = table(old);
        schemas.requireWriteCompatible(table, Set.of("unrelated_value"));
        InOrder ordered = inOrder(commands, objects);
        ordered.verify(commands)
                .execute(
                        argThat(
                                command ->
                                        command.getOperation()
                                                        == PostgreSqlCommands.Operation.WRITE_LOCK
                                                && command.getParameters()
                                                        .get("table")
                                                        .equals(
                                                                "\"public\".\"biz_contract_test\"")));
        ordered.verify(objects).getPublished("1");
        assertThatThrownBy(() -> schemas.requireWriteCompatible(table, Set.of("stored_value")))
                .hasMessageContaining("同步对象版本");
        clearInvocations(commands, objects);
        schemas.requireWriteCompatible(table, Set.of());
        verifyNoInteractions(commands, objects);
    }

    @Test
    void finalPersistenceEntryCannotBypassRuntimeContractCheck() {
        RuntimeSchema schemas = mock(RuntimeSchema.class);
        RecordPersistence persistence = new RecordPersistence();
        ReflectionTestUtils.setField(persistence, "schemas", schemas);
        RuntimeSchema.Table table =
                table(definition("INTEGER", FieldOptions.defaults(), List.of(), List.of()));
        doThrow(NocodeErrorCodes.invalid("先同步对象版本"))
                .when(schemas)
                .requireWriteCompatible(table, Set.of("stored_value"));
        assertThatThrownBy(
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        persistence,
                                        "writeStatement",
                                        table,
                                        "1",
                                        null,
                                        Map.of("stored_value", 10),
                                        10001L))
                .hasMessageContaining("同步对象版本");
        verify(schemas).requireWriteCompatible(table, Set.of("stored_value"));
    }

    @Test
    void oldApplicationReadProjectionDoesNotCheckLatestBusinessContract() {
        DatabaseMetadataReader database = mock(DatabaseMetadataReader.class);
        PostgreSqlCommandMapper commands = mock(PostgreSqlCommandMapper.class);
        DataObjectApi objects = mock(DataObjectApi.class);
        RuntimeSchema schemas = new RuntimeSchema();
        ReflectionTestUtils.setField(schemas, "database", database);
        ReflectionTestUtils.setField(schemas, "commands", commands);
        ReflectionTestUtils.setField(schemas, "objects", objects);
        DatabaseMetadata.Table physical =
                new DatabaseMetadata.Table(
                        new DatabaseMetadata.Relation("public", "biz_contract_test", "TABLE", null),
                        List.of(
                                new DatabaseMetadata.Column(
                                        "id", 1, "bigint", false, null, null, null, null, 1),
                                new DatabaseMetadata.Column(
                                        "value", 2, "bigint", true, null, null, null, null, 0),
                                new DatabaseMetadata.Column(
                                        "other",
                                        3,
                                        "character varying(200)",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0)),
                        List.of(),
                        List.of());
        when(database.readTable("public", "biz_contract_test")).thenReturn(Optional.of(physical));
        Definition old = definition("INTEGER", FieldOptions.defaults(), List.of(), List.of());
        assertThat(schemas.main(old).columns()).containsEntry("f", "value");
        verifyNoInteractions(commands, objects);
    }

    private RuntimeSchema.Table table(Definition definition) {
        return new RuntimeSchema.Table(
                "public",
                "biz_contract_test",
                TableBinding.generated("public", false),
                null,
                definition.fields(),
                definition.fieldOptions(),
                Map.of("f", "stored_value", "other", "unrelated_value"),
                null,
                true,
                definition);
    }

    private FieldOptions dictionary(String code) {
        return FieldOptions.defaults()
                .withSelection(
                        new SelectionFields.Source(
                                "SYSTEM_DICTIONARY",
                                null,
                                code,
                                List.of(),
                                false,
                                List.of(),
                                "NONE"));
    }

    private Relation relation(String target, String name) {
        return new Relation("r", "target", name, "REFERENCE", target, "f", null, false, "RESTRICT");
    }

    private Detail detail(String type) {
        return new Detail(
                "detail",
                "items",
                "明细",
                "biz_contract_details",
                "ACTIVE",
                List.of(field("detail_field", "item", "明细字段", type)),
                Map.of(),
                List.of());
    }

    private FieldDefinition field(String id, String code, String name, String type) {
        return new FieldDefinition(
                id,
                id,
                code,
                name,
                type,
                "TEXT".equals(type) ? 200 : null,
                "DECIMAL".equals(type) ? 20 : null,
                "DECIMAL".equals(type) ? 1 : null,
                false,
                false,
                0);
    }

    private Definition definition(
            String type, FieldOptions options, List<Relation> relations, List<Detail> details) {
        return new Definition(
                "1",
                "contract_test",
                "契约测试",
                null,
                "public",
                "biz_contract_test",
                "GENERATED",
                false,
                "other",
                Settings.defaults(),
                List.of(field("f", "value", "原字段", type), field("other", "other", "其他字段", "TEXT")),
                Map.of("f", options),
                relations,
                List.of(),
                details);
    }
}
