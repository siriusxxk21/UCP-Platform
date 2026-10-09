package com.richuang.os.nocode.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.api.ApplicationRecords.Save;
import com.richuang.os.nocode.api.DataScope;
import com.richuang.os.nocode.api.RelatedForms;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 任务规则授权仅存在于可信同步调用栈，且精确覆盖目标记录和规则字段。 */
class AutomationWriteScopeTest {
    @Test
    void ruleFieldsDoNotWidenTargetViewScopeOrReadonlyCapabilities() {
        ApplicationRuntimePolicy policy = new ApplicationRuntimePolicy();
        TaskEntryRuntimeScope tasks = mock(TaskEntryRuntimeScope.class);
        ObjectSharingService sharing = mock(ObjectSharingService.class);
        ReflectionTestUtils.setField(policy, "taskScope", tasks);
        ReflectionTestUtils.setField(policy, "sharing", sharing);
        ReflectionTestUtils.setField(policy, "validator", new ObjectGrantValidator());
        DataScope view =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition("name", "EQ", "allowed")),
                        List.of());
        ObjectGrant selected =
                new ObjectGrant(
                        "target",
                        Set.of("READ", "UPDATE"),
                        "OWN",
                        Set.of("name"),
                        Set.of("name"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("READ", view, "UPDATE", view),
                        Set.of());
        ObjectGrant ceiling =
                new ObjectGrant(
                        "target",
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of("name", "total", "count"),
                        Set.of("name", "total", "count"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        when(sharing.storedPermission("target", "app")).thenReturn(ceiling);
        when(tasks.current())
                .thenReturn(
                        new TaskEntryRuntimeScope.Invocation(
                                "app",
                                "entry",
                                1,
                                "entry",
                                "target",
                                2,
                                Map.of("target", List.of(selected)),
                                new TaskEntryRuntimeScope.TaskData(
                                        "root", "task", "entry", 1, Map.of(), true)));
        AutomationWriteScope.run(
                context("record"),
                () -> {
                    ObjectGrant rule = policy.scopeGrants("app", "target", 2).getFirst();
                    assertThat(rule.writeFields()).containsExactlyInAnyOrder("total", "count");
                    assertThat(rule.scope()).isEqualTo("OWN");
                    assertThat(rule.actionScopes())
                            .containsEntry("UPDATE", view)
                            .containsEntry("READ", view);
                    assertThat(policy.scopeGrants("app", "target", 3)).isEmpty();
                    return null;
                });
        assertThat(policy.scopeGrants("app", "target", 2).getFirst().writeFields())
                .containsExactly("name");
        ObjectGrant readonly =
                new ObjectGrant(
                        "target",
                        Set.of("READ"),
                        "ALL",
                        Set.of("name"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        when(tasks.current())
                .thenReturn(
                        new TaskEntryRuntimeScope.Invocation(
                                "app",
                                "entry",
                                1,
                                "entry",
                                "target",
                                2,
                                Map.of("target", List.of(readonly)),
                                new TaskEntryRuntimeScope.TaskData(
                                        "root", "task", "entry", 1, Map.of(), true)));
        AutomationWriteScope.run(
                context("record"),
                () -> {
                    assertThat(policy.scopeGrants("app", "target", 2)).isEmpty();
                    return null;
                });
    }

    @AfterEach
    void scopeNeverSurvivesTheInvocation() {
        assertThat(AutomationWriteScope.current()).isNull();
    }

    @Test
    void taskTargetOnlyRecognizesDelegatedApplicationAndObject() {
        assertThat(AutomationWriteScope.taskTarget("app", "target")).isNull();
        AutomationWriteScope.Context context = context("record");
        AutomationWriteScope.run(
                context,
                () -> {
                    assertThat(AutomationWriteScope.taskTarget("app", "target")).isSameAs(context);
                    assertThat(AutomationWriteScope.taskTarget("other-app", "target")).isNull();
                    assertThat(AutomationWriteScope.taskTarget("app", "other-object")).isNull();
                    assertThat(AutomationWriteScope.taskTarget("app", "source")).isNull();
                    return null;
                });
        AutomationWriteScope.Context ordinary =
                new AutomationWriteScope.Context(
                        "app",
                        1,
                        "rule",
                        "自动更新",
                        "source",
                        "source-record",
                        "target",
                        Set.of("total"));
        AutomationWriteScope.run(
                ordinary,
                () -> {
                    assertThat(AutomationWriteScope.taskTarget("app", "target")).isNull();
                    assertThat(
                                    AutomationWriteScope.covers(
                                            save("app", "target", "record", Map.of("total", "1"))))
                            .isFalse();
                    return null;
                });
    }

    @Test
    void coversOnlyTheExactTargetRecordAndDeclaredFields() {
        Save valid = save("app", "target", "record", Map.of("total", "1"));
        assertThat(AutomationWriteScope.covers(valid)).isFalse();
        AutomationWriteScope.run(
                context("record"),
                () -> {
                    assertThat(AutomationWriteScope.covers(valid)).isTrue();
                    assertThat(
                                    AutomationWriteScope.covers(
                                            save(
                                                    "app",
                                                    "target",
                                                    "record",
                                                    Map.of("total", "1", "count", "2"))))
                            .isTrue();
                    for (Save rejected :
                            List.of(
                                    save("other-app", "target", "record", Map.of("total", "1")),
                                    save("app", "other-object", "record", Map.of("total", "1")),
                                    save("app", "target", "other-record", Map.of("total", "1")),
                                    save("app", "target", null, Map.of("total", "1")),
                                    save(
                                            "app",
                                            "target",
                                            "record",
                                            Map.of("total", "1", "name", "篡改")),
                                    save("app", "target", "record", null))) {
                        assertThat(AutomationWriteScope.covers(rejected))
                                .as("%s", rejected)
                                .isFalse();
                    }
                    return null;
                });
    }

    @Test
    void narrowSaveCannotCarryDetailsRelationsFormsOrBusinessActions() {
        AutomationWriteScope.run(
                context("record"),
                () -> {
                    List<Save> rejected =
                            List.of(
                                    extended(Map.of(), null, null, null, null, null, null),
                                    extended(null, Map.of(), null, null, null, null, null),
                                    extended(
                                            null,
                                            null,
                                            new ApplicationRecords.Context(
                                                    "page", "node", "parent"),
                                            null,
                                            null,
                                            null,
                                            null),
                                    extended(null, null, null, "form", null, null, null),
                                    extended(null, null, null, null, "request", null, null),
                                    extended(null, null, null, null, null, "APPROVE", null),
                                    extended(
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Map.of("related", List.of())));
                    for (Save command : rejected) {
                        assertThat(AutomationWriteScope.covers(command))
                                .as("%s", command)
                                .isFalse();
                    }
                    assertThat(
                                    AutomationWriteScope.covers(
                                            extended(null, null, null, null, null, null, Map.of())))
                            .isTrue();
                    return null;
                });
    }

    @Test
    void nestedInvocationsRestoreTheOuterScopeAfterSuccessAndFailure() {
        AutomationWriteScope.Context outer = context("outer");
        AutomationWriteScope.Context inner = context("inner");
        AutomationWriteScope.run(
                outer,
                () -> {
                    AutomationWriteScope.run(
                            inner,
                            () -> {
                                assertThat(AutomationWriteScope.current()).isSameAs(inner);
                                return null;
                            });
                    assertThat(AutomationWriteScope.current()).isSameAs(outer);
                    assertThatThrownBy(
                                    () ->
                                            AutomationWriteScope.run(
                                                    inner,
                                                    () -> {
                                                        throw new IllegalStateException("规则失败");
                                                    }))
                            .hasMessage("规则失败");
                    assertThat(AutomationWriteScope.current()).isSameAs(outer);
                    assertThat(
                                    AutomationWriteScope.covers(
                                            save("app", "target", "inner", Map.of("total", "1"))))
                            .isFalse();
                    return null;
                });
        assertThatThrownBy(
                        () ->
                                AutomationWriteScope.run(
                                        outer,
                                        () -> {
                                            throw new IllegalStateException("保存失败");
                                        }))
                .hasMessage("保存失败");
        assertThat(AutomationWriteScope.current()).isNull();
    }

    @Test
    void contextCopiesItsFieldWhitelistAndDoesNotPropagateToAnotherThread() {
        Set<String> fields = new HashSet<>(Set.of("total"));
        AutomationWriteScope.Context context =
                new AutomationWriteScope.Context(
                        "app",
                        1,
                        "rule",
                        "自动更新",
                        "source",
                        "source-record",
                        "target",
                        fields,
                        "record",
                        true);
        fields.add("secret");
        AutomationWriteScope.run(
                context,
                () -> {
                    assertThat(AutomationWriteScope.current().fields()).containsExactly("total");
                    assertThat(
                                    AutomationWriteScope.covers(
                                            save("app", "target", "record", Map.of("secret", "1"))))
                            .isFalse();
                    assertThat(CompletableFuture.supplyAsync(AutomationWriteScope::current).join())
                            .isNull();
                    return null;
                });
    }

    private AutomationWriteScope.Context context(String record) {
        return new AutomationWriteScope.Context(
                "app",
                1,
                "rule",
                "自动更新",
                "source",
                "source-record",
                "target",
                Set.of("total", "count"),
                record,
                true);
    }

    private Save save(String app, String object, String record, Map<String, Object> values) {
        return new Save(app, object, record, "revision", values, null);
    }

    private Save extended(
            Map<String, List<ApplicationRecords.Row>> details,
            Map<String, List<String>> relations,
            ApplicationRecords.Context page,
            String form,
            String request,
            String action,
            Map<String, List<RelatedForms.Row>> related) {
        return new Save(
                "app",
                "target",
                "record",
                "revision",
                Map.of("total", "1"),
                details,
                relations,
                page,
                form,
                request,
                action,
                related);
    }
}
