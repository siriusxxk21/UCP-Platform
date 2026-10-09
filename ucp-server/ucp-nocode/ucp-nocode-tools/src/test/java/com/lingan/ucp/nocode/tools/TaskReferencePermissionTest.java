package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.dal.query.RecordStatement;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.access.ScopeConditions;
import com.lingan.ucp.nocode.runtime.service.record.RecordPersistence;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.function.Supplier;

/** 任务反馈引用复用真实授权与记录校验；不需要数据库，也不把任务用户加入应用成员。 */
class TaskReferencePermissionTest {
    private static final long ACTOR = 20002L;
    private final ApplicationRuntimePolicy policy = new ApplicationRuntimePolicy();
    private final TaskEntryRuntimeScope taskScope = new TaskEntryRuntimeScope();
    private final ObjectSharingService sharing = mock(ObjectSharingService.class);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final ApplicationAuthorizationService authorization =
            mock(ApplicationAuthorizationService.class);
    private final RuntimeConditionSql sql = mock(RuntimeConditionSql.class);
    private final ScopeConditions conditions = mock(ScopeConditions.class);
    private final RuntimeSchema.Table table = mock(RuntimeSchema.Table.class);
    private final RecordMapper records = mock(RecordMapper.class);
    private final RecordPersistence persistence = new RecordPersistence();
    private DataCenter.Definition project;
    private DataCenter.Definition feedback;
    private DataCenter.Relation relation;
    private ObjectGrant shared;

    @BeforeEach
    void setup() {
        project =
                RuleFixtures.object(
                        "project",
                        "项目",
                        List.of(
                                RuleFixtures.field("name", "name", "项目名称", "TEXT"),
                                RuleFixtures.field("area", "area", "项目地区", "TEXT"),
                                RuleFixtures.field("secret", "secret", "保密预算", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        relation =
                new DataCenter.Relation(
                        "project-ref",
                        "project",
                        "所属项目",
                        "REFERENCE",
                        "project",
                        "project-id",
                        null,
                        true,
                        "RESTRICT");
        feedback =
                RuleFixtures.object(
                        "feedback",
                        "施工日志",
                        List.of(
                                RuleFixtures.field("name", "name", "日志标题", "TEXT"),
                                RuleFixtures.field("project-id", "project", "所属项目", "REFERENCE")),
                        Map.of(),
                        List.of(relation),
                        List.of());
        shared = grant("ALL", Set.of("name", "area", "secret"), Map.of());
        when(sharing.storedPermission("project", "app")).thenAnswer(call -> shared);
        when(sharing.ceiling(eq("app"), eq(project))).thenAnswer(call -> shared);
        ReflectionTestUtils.setField(policy, "sharing", sharing);
        ReflectionTestUtils.setField(policy, "applications", applications);
        ReflectionTestUtils.setField(policy, "authorization", authorization);
        ReflectionTestUtils.setField(policy, "validator", new ObjectGrantValidator());
        ReflectionTestUtils.setField(policy, "taskScope", taskScope);
        ReflectionTestUtils.setField(policy, "sqlFragments", sql);
        ReflectionTestUtils.setField(policy, "scopes", conditions);
        when(sql.alwaysFalse()).thenReturn("1=0");
        when(sql.alwaysTrue()).thenReturn("1=1");
        when(sql.column("t", "id", true)).thenReturn("t.id");
        DatabaseMetadata.Column key = mock(DatabaseMetadata.Column.class);
        when(key.name()).thenReturn("id");
        when(table.key()).thenReturn(key);
        when(table.statement(anyString(), isNull(), anyString(), anyBoolean()))
                .thenReturn(mock(RecordStatement.class));
        ReflectionTestUtils.setField(persistence, "records", records);
        ReflectionTestUtils.setField(persistence, "json", new ObjectMapper());
        ReflectionTestUtils.setField(persistence, "taskScope", taskScope);
        when(records.rows(any()))
                .thenReturn(
                        List.of(
                                "{\"id\":\"project-1\",\"revision\":\"1\",\"recordCreator\":\"10001\","
                                    + "\"values\":{\"name\":\"幸福小区\",\"area\":\"东区\",\"secret\":\"内部预算\"}}"));
    }

    @Test
    void nonMemberCanSelectReferenceNamesBeforeAnyFeedbackWasSubmitted() {
        inTask(
                List.of(shared),
                () -> {
                    policy.requireEntry("app", ACTOR);
                    ApplicationRuntimePolicy.Access reference = reference(Set.of());
                    assertThat(reference.taskReference()).isTrue();
                    assertThat(reference.queryFields()).containsExactly("name");
                    assertThat(query(reference)).doesNotContain("1=0");
                    ApplicationAuthorization.Capabilities caps =
                            reference.require("10001", Map.of(), ApplicationActionEnum.READ);
                    assertThat(caps.actions()).containsExactly("READ");
                    assertThat(caps.writeFields()).isEmpty();
                    assertThat(caps.readDetails()).isEmpty();
                    assertThat(caps.readRelations()).isEmpty();
                    verifyNoInteractions(applications, authorization);
                    return null;
                });
        assertThat(taskScope.current()).isNull();
        assertThatThrownBy(() -> policy.requireEntry("app", ACTOR)).hasMessageContaining("没有此应用");
    }

    @Test
    void saveValidationUsesTheSameReferenceReadAndDoesNotReturnUnrelatedFields() {
        inTask(
                List.of(shared),
                () -> {
                    ApplicationRecords.Row selected =
                            read(reference(Set.of()), ApplicationActionEnum.READ);
                    assertThat(selected.id()).isEqualTo("project-1");
                    assertThat(selected.values()).containsOnly(entry("name", "幸福小区"));
                    verify(records).rows(any());
                    return null;
                });
    }

    @Test
    void normalListsDetailsAndWritesStillRequireGroupMembership() {
        inTask(
                List.of(shared),
                () -> {
                    ApplicationRuntimePolicy.Access normal = policy.access("app", project, ACTOR);
                    assertThat(normal.taskReference()).isFalse();
                    assertThat(query(normal)).contains("1=0");
                    assertThatThrownBy(() -> read(normal, ApplicationActionEnum.READ))
                            .hasMessageContaining("本组任务");
                    assertThatThrownBy(
                                    () -> read(reference(Set.of()), ApplicationActionEnum.UPDATE))
                            .hasMessageContaining("本组任务");
                    assertThatThrownBy(
                                    () ->
                                            reference(Set.of())
                                                    .require("10001", ApplicationActionEnum.CREATE))
                            .hasMessageContaining("CREATE");
                    verifyNoInteractions(records);
                    return null;
                });
    }

    @Test
    void publishedRelationMayUseImplicitNamesWithoutAnyTargetObjectGrant() {
        inTask(
                List.of(),
                () -> {
                    ApplicationRuntimePolicy.Access reference = reference(Set.of());
                    assertThat(reference.queryFields()).containsExactly("name");
                    assertThat(read(reference, ApplicationActionEnum.READ).values())
                            .containsOnly(entry("name", "幸福小区"));
                    assertThatThrownBy(() -> policy.access("app", project, ACTOR))
                            .hasMessageContaining("没有此对象");
                    return null;
                });
    }

    @Test
    void taskTargetViewAndSharingConditionsCannotBeWidenedByImplicitPermission() {
        DataScope east =
                new DataScope(
                        "AND", List.of(new DataScope.Condition("area", "eq", "东区")), List.of());
        ObjectGrant limited = grant("ALL", shared.readFields(), Map.of("READ", east));
        inTask(
                List.of(limited),
                () -> {
                    ApplicationRuntimePolicy.Access reference = reference(Set.of());
                    assertThat(reference.grants()).hasSize(1);
                    assertThat(
                                    reference
                                            .require(
                                                    "10001",
                                                    Map.of("area", "东区"),
                                                    ApplicationActionEnum.READ)
                                            .readFields())
                            .containsExactly("name");
                    assertThatThrownBy(
                                    () ->
                                            reference.require(
                                                    "10001",
                                                    Map.of("area", "西区"),
                                                    ApplicationActionEnum.READ))
                            .hasMessageContaining("READ");
                    query(reference);
                    verify(conditions)
                            .append(any(), eq(east), eq(project), eq(table), anyMap(), eq("t"));
                    return null;
                });
    }

    @Test
    void matchingFieldsAreNarrowAndLiveSharedFieldRevocationStillApplies() {
        inTask(
                List.of(shared),
                () -> {
                    assertThat(reference(Set.of("area")).queryFields())
                            .containsExactlyInAnyOrder("name", "area");
                    shared = grant("OWN", Set.of("area"), Map.of());
                    ApplicationRuntimePolicy.Access reference = reference(Set.of("area"));
                    assertThat(reference.queryFields()).containsExactly("area");
                    assertThatThrownBy(() -> read(reference, ApplicationActionEnum.READ))
                            .hasMessageContaining("READ");
                    shared = null;
                    assertThat(reference(Set.of()).any(ApplicationActionEnum.READ)).isFalse();
                    return null;
                });
    }

    @Test
    void explicitlyConfiguredGroupTargetKeepsItsRecordSetForReferences() {
        inTask(
                List.of(shared),
                Set.of("project"),
                () -> {
                    ApplicationRuntimePolicy.Access reference = reference(Set.of());
                    assertThat(query(reference)).contains("1=0");
                    assertThatThrownBy(() -> read(reference, ApplicationActionEnum.READ))
                            .hasMessageContaining("本组任务");
                    taskScope.created("project", "project-1");
                    assertThat(query(reference)).contains("t.id IN").doesNotContain("1=0");
                    assertThat(read(reference, ApplicationActionEnum.READ).values())
                            .containsOnly(entry("name", "幸福小区"));
                    assertThatThrownBy(
                                    () -> taskScope.requireRecord("project", "other-project", true))
                            .hasMessageContaining("本组任务");
                    return null;
                });
    }

    @Test
    void revokedExplicitTaskResourceCannotFallBackToImplicitNames() {
        inTask(
                List.of(),
                Set.of("project"),
                () -> {
                    assertThat(reference(Set.of()).any(ApplicationActionEnum.READ)).isFalse();
                    assertThat(query(reference(Set.of()))).contains("1=0");
                    return null;
                });
    }

    @Test
    void legacyTaskDataConstructorKeepsGroupRestrictionsForReferenceReads() {
        TaskEntryRuntimeScope.TaskData legacy =
                new TaskEntryRuntimeScope.TaskData(
                        "root",
                        "task",
                        "feedback",
                        10001L,
                        Map.of("project", new HashSet<>(), "feedback", new HashSet<>()),
                        true);
        assertThat(legacy.configuredObjects()).containsExactlyInAnyOrder("project", "feedback");
        TaskEntryRuntimeScope.Invocation invocation =
                new TaskEntryRuntimeScope.Invocation(
                        "app",
                        "feedback",
                        1,
                        "施工日志",
                        "feedback",
                        ACTOR,
                        Map.of("project", List.of(shared)),
                        legacy);
        Supplier<Void> verify =
                () -> {
                    assertThat(taskScope.recordIds("project", true)).isEmpty();
                    assertThat(query(reference(Set.of()))).contains("1=0");
                    assertThatThrownBy(() -> read(reference(Set.of()), ApplicationActionEnum.READ))
                            .hasMessageContaining("本组任务");
                    return null;
                };
        ReflectionTestUtils.invokeMethod(taskScope, "execute", invocation, verify);
        assertThat(taskScope.current()).isNull();
    }

    @Test
    void taskReferenceCannotChangeActorApplicationOrRelationTarget() {
        inTask(
                List.of(shared),
                () -> {
                    assertThatThrownBy(
                                    () ->
                                            policy.referenceAccess(
                                                    "other-app",
                                                    feedback,
                                                    relation,
                                                    project,
                                                    ACTOR,
                                                    Set.of(),
                                                    null))
                            .hasMessageContaining("当前任务入口");
                    assertThatThrownBy(
                                    () ->
                                            policy.referenceAccess(
                                                    "app", feedback, relation, project, 30003L,
                                                    Set.of(), null))
                            .hasMessageContaining("当前任务入口");
                    assertThatThrownBy(
                                    () ->
                                            policy.referenceAccess(
                                                    "app", feedback, relation, feedback, ACTOR,
                                                    Set.of(), null))
                            .hasMessageContaining("引用来源");
                    return null;
                });
    }

    private ApplicationRuntimePolicy.Access reference(Set<String> matches) {
        return policy.referenceAccess("app", feedback, relation, project, ACTOR, matches, null);
    }

    private String query(ApplicationRuntimePolicy.Access access) {
        return policy.conditions(access, table, null, ApplicationActionEnum.READ, "t")
                .getSqlSegment();
    }

    private ApplicationRecords.Row read(
            ApplicationRuntimePolicy.Access access, ApplicationActionEnum action) {
        return ReflectionTestUtils.invokeMethod(
                persistence, "authorizedRead", table, "project-1", ACTOR, true, access, action);
    }

    private <T> T inTask(List<ObjectGrant> grants, Supplier<T> action) {
        return inTask(grants, Set.of(), action);
    }

    private <T> T inTask(List<ObjectGrant> grants, Set<String> configured, Supplier<T> action) {
        TaskEntryRuntimeScope.Invocation invocation =
                new TaskEntryRuntimeScope.Invocation(
                        "app",
                        "feedback",
                        1,
                        "施工日志",
                        "feedback",
                        ACTOR,
                        Map.of("project", grants),
                        new TaskEntryRuntimeScope.TaskData(
                                "root",
                                "task",
                                "feedback",
                                10001L,
                                Map.of("project", new HashSet<>(), "feedback", new HashSet<>()),
                                true,
                                configured));
        return ReflectionTestUtils.invokeMethod(taskScope, "execute", invocation, action);
    }

    private ObjectGrant grant(String scope, Set<String> fields, Map<String, DataScope> scopes) {
        return new ObjectGrant(
                "project",
                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                scope,
                fields,
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                scopes,
                Set.of());
    }
}
