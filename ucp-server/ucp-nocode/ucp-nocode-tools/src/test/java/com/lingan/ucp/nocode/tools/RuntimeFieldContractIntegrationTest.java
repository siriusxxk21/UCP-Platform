package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 使用当前开发 PostgreSQL 验证普通写入与发布锁互斥，夹具只创建并清理专用表。 */
class RuntimeFieldContractIntegrationTest extends NocodeIntegrationSupport {
    @Test
    void waitingWriterReadsLatestSelectionIdentityAfterPublicationCompletes() throws Exception {
        String name = "biz_" + prefix + "_contract";
        String quoted = PostgreSqlCommands.table("public", name);
        jdbc.execute("CREATE TABLE " + quoted + " (id bigint PRIMARY KEY, value varchar(200))");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        Definition old = definition(name, FieldOptions.defaults());
        Definition next =
                definition(
                        name,
                        FieldOptions.defaults()
                                .withSelection(
                                        new SelectionFields.Source(
                                                "SYSTEM_DICTIONARY",
                                                null,
                                                "status",
                                                List.of(),
                                                false,
                                                List.of(),
                                                "NONE")));
        AtomicReference<Definition> published = new AtomicReference<>(old);
        DataObjectApi objects = mock(DataObjectApi.class);
        when(objects.getPublished("1")).thenAnswer(call -> published.get());
        PostgreSqlCommandMapper observed = mock(PostgreSqlCommandMapper.class);
        doAnswer(
                        call -> {
                            writerEntered.countDown();
                            commandMapper.execute(call.getArgument(0));
                            return null;
                        })
                .when(observed)
                .execute(any());
        RuntimeSchema schemas = new RuntimeSchema();
        ReflectionTestUtils.setField(schemas, "commands", observed);
        ReflectionTestUtils.setField(schemas, "objects", objects);
        RuntimeSchema.Table table =
                new RuntimeSchema.Table(
                        "public",
                        name,
                        TableBinding.generated("public", false),
                        null,
                        old.fields(),
                        old.fieldOptions(),
                        Map.of("f", "value"),
                        null,
                        true,
                        old);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> publishing =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        commandMapper.execute(
                                                                PostgreSqlCommands.lock(
                                                                        "public", name));
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(8, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "发布锁夹具未释放");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        published.set(next);
                                                    }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> writing =
                        executor.submit(
                                () ->
                                        new TransactionTemplate(manager)
                                                .executeWithoutResult(
                                                        status ->
                                                                schemas.requireWriteCompatible(
                                                                        table, Set.of("value"))));
                assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> writing.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                verifyNoInteractions(objects);
                release.countDown();
                publishing.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> writing.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .satisfies(
                                error ->
                                        assertThat(error.getCause())
                                                .hasMessageContaining("同步对象版本"));
                verify(objects).getPublished("1");
            } finally {
                release.countDown();
            }
        } finally {
            jdbc.execute("DROP TABLE " + quoted);
        }
    }

    private Definition definition(String table, FieldOptions options) {
        FieldDefinition field =
                new FieldDefinition(
                        "f", "f", "value", "选择字段", "SELECT", null, null, null, false, false, 0);
        return new Definition(
                "1",
                "contract_test",
                "契约测试",
                null,
                "public",
                table,
                "GENERATED",
                false,
                "f",
                Settings.defaults(),
                List.of(field),
                Map.of("f", options),
                List.of(),
                List.of(),
                List.of());
    }
}
