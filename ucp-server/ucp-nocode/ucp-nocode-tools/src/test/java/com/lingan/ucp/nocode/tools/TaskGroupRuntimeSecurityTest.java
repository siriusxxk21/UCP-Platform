package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.DataScope;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskWorkRecordDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.lingan.ucp.nocode.runtime.service.task.TaskGroupRuntime;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicies;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicies.FrozenRoot;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.Frozen;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.ResourceGrant;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkflowProtection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 纯内存验证总任务委托边界，不借用应用成员身份，也不接触运行数据库和真实任务。 */
class TaskGroupRuntimeSecurityTest {
    private static final String BUSINESS = TaskDataPolicies.BUSINESS;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskCenterMapper tasks = mock(TaskCenterMapper.class);
    private final TaskWorkEntryMapper entries = mock(TaskWorkEntryMapper.class);
    private final TaskDataPolicies policies = mock(TaskDataPolicies.class);
    private final TaskDataPolicyCompiler compiler = mock(TaskDataPolicyCompiler.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final PermissionCommonApi permissions = mock(PermissionCommonApi.class);
    private final TaskEntryRuntimeScope scope = new TaskEntryRuntimeScope();
    private final TaskGroupRuntime runtime = new TaskGroupRuntime();
    private TaskInstanceDO root;
    private TaskInstanceDO child;
    private ResourceGrant business;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(runtime, "tasks", tasks);
        ReflectionTestUtils.setField(runtime, "entries", entries);
        ReflectionTestUtils.setField(runtime, "policies", policies);
        ReflectionTestUtils.setField(runtime, "compiler", compiler);
        ReflectionTestUtils.setField(runtime, "scope", scope);
        ReflectionTestUtils.setField(runtime, "users", users);
        ReflectionTestUtils.setField(runtime, "permissions", permissions);
        ReflectionTestUtils.setField(runtime, "json", json);
        ReflectionTestUtils.setField(
                runtime, "workflowProtection", mock(TaskWorkflowProtection.class));
        root = task("root", "root", null, "PENDING");
        root.setAuthorizationJson("approved");
        child = task("child", "root", 2L, "RUNNING");
        when(tasks.get("root", false)).thenReturn(root);
        when(tasks.get("root", true)).thenReturn(root);
        when(tasks.get("child", false)).thenReturn(child);
        when(users.getUser(anyLong()))
                .thenAnswer(invocation -> activeUser(invocation.getArgument(0)));
        when(entries.records(anyString())).thenReturn(List.of());
        when(compiler.revalidate(any(ResourceGrant.class), eq(1L)))
                .thenAnswer(invocation -> ((ResourceGrant) invocation.getArgument(0)).grants());
        business = resource(BUSINESS, "app", "form", "object", Set.of("name"));
        approve(DataAccessMode.GROUP, DataAccessMode.GROUP, business);
    }

    @Test
    void assignedPendingNodeMayReadButOnlyExplicitlyStartedNodeMayWrite() {
        child.setStatus("PENDING");
        assertThat(runtime.execute("child", BUSINESS, 2L, false, () -> scope.current().actor()))
                .isEqualTo(2L);
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 2L, true, () -> "unexpected"))
                .hasMessageContaining("进行中");
        child.setStatus("RUNNING");
        assertThat(
                        runtime.execute(
                                "child",
                                BUSINESS,
                                2L,
                                true,
                                () -> {
                                    assertThat(scope.current().data().rootId()).isEqualTo("root");
                                    assertThat(scope.current().data().taskId()).isEqualTo("child");
                                    assertThat(scope.current().data().grantorId()).isEqualTo(1L);
                                    return "saved-by-assignee";
                                }))
                .isEqualTo("saved-by-assignee");
        assertThat(root.getStatus()).isEqualTo("PENDING");
        assertThat(root.getAssigneeId()).isNull();
        verify(tasks, atLeastOnce()).get("root", true);
        assertScopeCleared();
    }

    @Test
    void rootOwnerAndTaskManagerMayReadButCannotWriteAsAnotherAssignee() {
        when(permissions.hasAnyPermissions(4L, "nocode:task:manage-all")).thenReturn(true);
        for (long actor : List.of(1L, 4L)) {
            assertThat(runtime.execute("child", BUSINESS, actor, false, () -> "read"))
                    .isEqualTo("read");
            assertThatThrownBy(
                            () ->
                                    runtime.execute(
                                            "child", BUSINESS, actor, true, () -> "unexpected"))
                    .hasMessageContaining("负责人");
        }
        assertScopeCleared();
    }

    @Test
    void sameGroupMembershipCannotBorrowSiblingNodeIdentity() {
        TaskInstanceDO sibling = task("sibling", "root", 3L, "RUNNING");
        when(tasks.get("sibling", false)).thenReturn(sibling);
        assertThat(runtime.execute("sibling", BUSINESS, 3L, false, () -> "own-node"))
                .isEqualTo("own-node");
        for (boolean write : List.of(false, true)) {
            assertThatThrownBy(
                            () -> runtime.execute("child", BUSINESS, 3L, write, () -> "unexpected"))
                    .hasMessageContaining("当前任务执行人");
        }
        assertScopeCleared();
    }

    @Test
    void revokedAuxiliaryResourceCannotHideValidEntryOrKeepOldReadGrant() {
        ResourceGrant other =
                resource("other", "app", "other-form", "other-object", Set.of("name"));
        approve(DataAccessMode.GROUP, DataAccessMode.GROUP, business, other);
        when(compiler.revalidate(other, 1L))
                .thenThrow(com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid("辅助业务视图已撤下"));
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                false,
                () -> {
                    assertThat(scope.current().grants())
                            .containsKey("object")
                            .doesNotContainKey("other-object");
                    return null;
                });
        assertThatThrownBy(() -> runtime.execute("child", "other", 2L, false, () -> "unexpected"))
                .hasMessageContaining("已撤下");
        assertScopeCleared();
    }

    @Test
    void namedAcceptorReadsTheApprovedGroupWithoutAcquiringWritePermission() throws Exception {
        root.setConfigJson(
                json.writeValueAsString(
                        new NodeInput(
                                "root",
                                null,
                                "验收总任务",
                                null,
                                2L,
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                null,
                                AssignmentMode.ASSIGNED,
                                List.of(),
                                null,
                                3L)));
        root.setStatus("PENDING_ACCEPTANCE");
        runtime.execute(
                "child",
                BUSINESS,
                3L,
                false,
                () -> {
                    assertThat(scope.current().data().writable()).isFalse();
                    assertThat(scope.recordIds("object")).isEmpty();
                    assertThatThrownBy(scope::requireWrite).hasMessageContaining("读取");
                    return null;
                });
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 3L, true, () -> "unexpected"))
                .hasMessageContaining("进行中");
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 2L, true, () -> "unexpected"))
                .hasMessageContaining("进行中");
        assertScopeCleared();
    }

    @Test
    void closedNodeOrClosedRootImmediatelyRejectsWrites() {
        for (String status : List.of("PENDING", "COMPLETED", "CANCELLED")) {
            child.setStatus(status);
            assertThatThrownBy(
                            () -> runtime.execute("child", BUSINESS, 2L, true, () -> "unexpected"))
                    .hasMessageContaining("进行中");
        }
        child.setStatus("RUNNING");
        for (String status : List.of("COMPLETED", "CANCELLED")) {
            root.setStatus(status);
            assertThatThrownBy(
                            () -> runtime.execute("child", BUSINESS, 2L, true, () -> "unexpected"))
                    .hasMessageContaining("进行中");
            assertThat(runtime.execute("child", BUSINESS, 2L, false, () -> "historical-read"))
                    .isEqualTo("historical-read");
        }
        assertScopeCleared();
    }

    @Test
    void missingOrDisabledActorCannotUseAnAlreadyApprovedGrant() {
        AdminUserRespDTO disabled = activeUser(2L);
        disabled.setStatus(1);
        when(users.getUser(2L)).thenReturn(disabled);
        for (boolean write : List.of(false, true)) {
            assertThatThrownBy(
                            () -> runtime.execute("child", BUSINESS, 2L, write, () -> "unexpected"))
                    .hasMessageContaining("已停用");
        }
        when(users.getUser(2L)).thenReturn(null);
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 2L, false, () -> "unexpected"))
                .hasMessageContaining("已停用");
        verifyNoInteractions(compiler);
        assertScopeCleared();
    }

    @Test
    void groupIncludesOnlyApprovedCurrentResourceRecordsFromItsOwnDataset() throws Exception {
        TaskWorkRecordDO current = contribution("current", business.ref(), "own", null);
        TaskWorkRecordDO superseded = contribution("superseded", business.ref(), "old", null);
        superseded.setSupersededBy("current");
        ResourceGrant wrongResource =
                resource("other", "app", "other-form", "object", Set.of("name"));
        ResourceGrant wrongObject =
                resource("other", "app", "form", "other-object", Set.of("name"));
        TaskWorkRecordDO approved =
                contribution("approved", business.ref(), null, "request-approved");
        TaskWorkRecordDO pending = contribution("pending", business.ref(), null, "request-pending");
        when(entries.approvedRecord("approved")).thenReturn("approved-record");
        when(entries.records("root:" + BUSINESS))
                .thenReturn(
                        List.of(
                                current,
                                superseded,
                                contribution(
                                        "wrong-resource",
                                        wrongResource.ref(),
                                        "foreign-resource",
                                        null),
                                contribution(
                                        "wrong-object", wrongObject.ref(), "foreign-object", null),
                                approved,
                                pending));
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                false,
                () -> {
                    assertThat(scope.recordIds("object"))
                            .containsExactlyInAnyOrder("own", "approved-record");
                    scope.requireRecord("object", "own");
                    assertThatThrownBy(() -> scope.requireRecord("object", "another-root-record"))
                            .hasMessageContaining("本组任务");
                    return null;
                });
        verify(entries, never()).records(startsWith("another-root:"));
        assertScopeCleared();
    }

    @Test
    void newlyCreatedGroupRecordIsReadableOnlyDuringTheCurrentTrustedInvocation() {
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    assertThat(scope.recordIds("object")).isEmpty();
                    scope.created("object", "new-record");
                    scope.requireRecord("object", "new-record");
                    assertThat(scope.recordIds("object")).containsExactly("new-record");
                    return null;
                });
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                false,
                () -> {
                    assertThat(scope.recordIds("object")).isEmpty();
                    assertThatThrownBy(() -> scope.requireRecord("object", "new-record"))
                            .hasMessageContaining("本组任务");
                    return null;
                });
        assertScopeCleared();
    }

    @Test
    void sameObjectAuxiliaryAllResourceCannotWidenSelectedGroupRecordsOrFields() throws Exception {
        ResourceGrant broadFeedback =
                resource("feedback", "app", "broad-form", "object", Set.of("secret"));
        approve(DataAccessMode.GROUP, DataAccessMode.ALL, business, broadFeedback);
        when(entries.records("root:" + BUSINESS))
                .thenReturn(List.of(contribution("one", business.ref(), "own", null)));
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    assertThat(scope.recordIds("object")).containsExactly("own");
                    assertThat(scope.current().grants().get("object")).hasSize(1);
                    assertThat(scope.current().grants().get("object").getFirst().writeFields())
                            .containsExactly("name");
                    assertThatThrownBy(() -> scope.requireRecord("object", "foreign"))
                            .hasMessageContaining("本组任务");
                    return null;
                });
        verify(compiler, never()).revalidate(broadFeedback, 1L);
        // 反向切换到正式批准的 ALL 入口不应受到同对象辅助 GROUP 入口的误收紧。
        runtime.execute(
                "child",
                "feedback",
                2L,
                true,
                () -> {
                    assertThat(scope.recordIds("object")).isNull();
                    assertThat(scope.current().grants().get("object").getFirst().writeFields())
                            .containsExactly("secret");
                    return null;
                });
        assertScopeCleared();
    }

    @Test
    void relatedObjectHasBusinessCapabilitiesButKeepsItsOwnGroupScopeAndApplication() {
        ResourceGrant helper =
                resource("feedback", "app", "feedback-form", "feedback-object", Set.of("detail"));
        ResourceGrant anotherApp =
                resource("external", "other-app", "form", "external-object", Set.of("secret"));
        approve(DataAccessMode.ALL, DataAccessMode.GROUP, business, helper, anotherApp);
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    assertThat(scope.current().grants())
                            .containsOnlyKeys("object", "feedback-object");
                    ObjectGrant related =
                            scope.current().grants().get("feedback-object").getFirst();
                    assertThat(related.actions())
                            .containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
                    assertThat(related.readFields()).containsExactly("detail");
                    assertThat(related.writeFields()).containsExactly("detail");
                    assertThat(related.actionScopes()).containsOnlyKeys("READ", "UPDATE");
                    assertThat(scope.recordIds("feedback-object")).isEmpty();
                    assertThat(scope.recordIds("object")).isNull();
                    return null;
                });
        verify(compiler, never()).revalidate(anotherApp, 1L);
        assertScopeCleared();
    }

    @Test
    void implicitApplicationObjectsInheritGroupDenyByDefaultAndCannotWidenSelectedObject() {
        ResourceGrant extra =
                resource("extra", "app", "extra-form", "extra-object", Set.of("name"));
        when(compiler.applicationGrants(business, 1L))
                .thenReturn(
                        Map.of(
                                "object",
                                        resource("wide", "app", "form", "object", Set.of("secret"))
                                                .grants(),
                                "extra-object", extra.grants()));
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    assertThat(scope.current().grants().get("object").getFirst().writeFields())
                            .containsExactly("name");
                    assertThat(scope.current().grants().get("extra-object").getFirst().actions())
                            .contains("CREATE", "UPDATE", "DELETE");
                    assertThat(scope.recordIds("extra-object")).isEmpty();
                    assertThatThrownBy(() -> scope.requireRecord("extra-object", "foreign"))
                            .hasMessageContaining("本组任务");
                    return null;
                });
        approve(DataAccessMode.ALL, DataAccessMode.GROUP, business);
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    assertThat(scope.recordIds("extra-object")).isNull();
                    return null;
                });
        assertScopeCleared();
    }

    @Test
    void perEntryScopesOverrideOldCategoryPolicyWithoutLeakingAcrossSameObject() throws Exception {
        ResourceGrant open = resource("open", "app", "open-form", "object", Set.of("name"));
        com.lingan.ucp.nocode.api.TaskWorkEntries.Config closedConfig =
                new com.lingan.ucp.nocode.api.TaskWorkEntries.Config(
                        BUSINESS,
                        "本组项",
                        business.binding(),
                        com.lingan.ucp.nocode.api.TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true,
                        null,
                        DataAccessMode.GROUP);
        com.lingan.ucp.nocode.api.TaskWorkEntries.Config openConfig =
                new com.lingan.ucp.nocode.api.TaskWorkEntries.Config(
                        "open",
                        "全部项",
                        open.binding(),
                        com.lingan.ucp.nocode.api.TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        DataAccessMode.ALL);
        approveEntries(
                DataAccessMode.ALL,
                DataAccessMode.GROUP,
                List.of(closedConfig, openConfig),
                business,
                open);
        when(entries.records("root:" + BUSINESS))
                .thenReturn(List.of(contribution("one", business.ref(), "own", null)));
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                false,
                () -> {
                    assertThat(scope.recordIds("object")).containsExactly("own");
                    assertThatThrownBy(() -> scope.requireRecord("object", "foreign"))
                            .hasMessageContaining("本组任务");
                    return null;
                });
        runtime.execute(
                "child",
                "open",
                2L,
                false,
                () -> {
                    assertThat(scope.recordIds("object")).isNull();
                    assertThat(scope.current().grants().get("object")).hasSize(1);
                    assertThat(scope.current().grants().get("object").getFirst().actionScopes())
                            .isNotEmpty();
                    return null;
                });
        assertScopeCleared();
    }

    @Test
    void scopeIsCleanedAfterSuccessFailureAndIsNeverInheritedByAnotherThread() {
        IllegalStateException failure = new IllegalStateException("业务保存失败");
        assertThatThrownBy(
                        () ->
                                runtime.execute(
                                        "child",
                                        BUSINESS,
                                        2L,
                                        true,
                                        () -> {
                                            assertThat(runtime.active()).isTrue();
                                            assertThat(
                                                            CompletableFuture.supplyAsync(
                                                                            () -> scope.current())
                                                                    .join())
                                                    .isNull();
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertScopeCleared();
        assertThat(runtime.execute("child", BUSINESS, 2L, false, () -> "next-request"))
                .isEqualTo("next-request");
        assertScopeCleared();
    }

    @Test
    void nestedInvocationRetainsSameIdentityAndRejectsSwitchingTaskEntryOrActor() {
        TaskInstanceDO sibling = task("sibling", "root", 2L, "RUNNING");
        when(tasks.get("sibling", false)).thenReturn(sibling);
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                true,
                () -> {
                    TaskEntryRuntimeScope.Invocation original = scope.current();
                    assertThat(runtime.execute("child", BUSINESS, 2L, false, scope::current))
                            .isSameAs(original);
                    assertThatThrownBy(
                                    () ->
                                            runtime.execute(
                                                    "sibling",
                                                    BUSINESS,
                                                    2L,
                                                    false,
                                                    () -> "unexpected"))
                            .hasMessageContaining("切换");
                    assertThatThrownBy(
                                    () ->
                                            runtime.execute(
                                                    "child",
                                                    "feedback",
                                                    2L,
                                                    false,
                                                    () -> "unexpected"))
                            .hasMessageContaining("切换");
                    assertThatThrownBy(
                                    () ->
                                            runtime.execute(
                                                    "child",
                                                    BUSINESS,
                                                    1L,
                                                    false,
                                                    () -> "unexpected"))
                            .hasMessageContaining("切换");
                    assertThat(scope.current()).isSameAs(original);
                    return null;
                });
        assertScopeCleared();
    }

    @Test
    void nestedReadCannotEscalateToWriteBeforeTaskStart() {
        child.setStatus("PENDING");
        AtomicBoolean wrote = new AtomicBoolean(false);
        runtime.execute(
                "child",
                BUSINESS,
                2L,
                false,
                () -> {
                    assertThatThrownBy(
                                    () ->
                                            runtime.execute(
                                                    "child",
                                                    BUSINESS,
                                                    2L,
                                                    true,
                                                    () -> {
                                                        wrote.set(true);
                                                        return null;
                                                    }))
                            .hasMessageContaining("只允许读取");
                    return null;
                });
        assertThat(wrote).isFalse();
        assertScopeCleared();
    }

    @Test
    void liveGrantRevocationAndUnknownEntryFailWithoutLeakingScope() {
        assertThatThrownBy(
                        () -> runtime.execute("child", "unapproved", 2L, false, () -> "unexpected"))
                .hasMessageContaining("没有配置");
        when(compiler.revalidate(business, 1L)).thenThrow(new IllegalStateException("共享已撤回"));
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 2L, true, () -> "unexpected"))
                .hasMessageContaining("共享已撤回");
        assertScopeCleared();
    }

    @Test
    void malformedGroupCatalogFailsClosedAndLegacyTaskDoesNotGainDelegation() {
        TaskWorkRecordDO broken = new TaskWorkRecordDO();
        broken.setBusinessJson("not-json");
        when(entries.records("root:" + BUSINESS)).thenReturn(List.of(broken));
        assertThatThrownBy(() -> runtime.execute("child", BUSINESS, 2L, false, () -> "unexpected"))
                .hasMessageContaining("无法读取");
        assertScopeCleared();
        root.setAuthorizationJson(null);
        clearInvocations(users, compiler);
        assertThat(
                        runtime.execute(
                                "child",
                                BUSINESS,
                                2L,
                                true,
                                () -> {
                                    assertThat(runtime.active()).isFalse();
                                    return "legacy-action";
                                }))
                .isEqualTo("legacy-action");
        verifyNoInteractions(users, compiler);
        assertScopeCleared();
    }

    private void approve(
            DataAccessMode businessMode, DataAccessMode feedbackMode, ResourceGrant... resources) {
        approveEntries(businessMode, feedbackMode, List.of(), resources);
    }

    private void approveEntries(
            DataAccessMode businessMode,
            DataAccessMode feedbackMode,
            List<com.lingan.ucp.nocode.api.TaskWorkEntries.Config> configured,
            ResourceGrant... resources) {
        NodeInput config =
                new NodeInput(
                        "root",
                        null,
                        "总任务",
                        null,
                        null,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        business.binding(),
                        new Sharing(DataMode.INDEPENDENT, null, List.of()),
                        configured,
                        AssignmentMode.UNASSIGNED,
                        List.of(),
                        new DataPolicy(1, businessMode, feedbackMode));
        when(policies.decode("approved"))
                .thenReturn(new FrozenRoot(config, new Frozen(1L, List.of(resources))));
    }

    private ResourceGrant resource(
            String key, String app, String form, String object, Set<String> fields) {
        DataScope readScope =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition("name", "IS_NOT_NULL", null)),
                        List.of());
        ObjectGrant grant =
                new ObjectGrant(
                        object,
                        Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                        "ALL",
                        fields,
                        fields,
                        Set.of("lines"),
                        Set.of("lines"),
                        Set.of("relation"),
                        Set.of("relation"),
                        Map.of("READ", readScope, "UPDATE", readScope),
                        Set.of("computed"));
        BusinessRef ref =
                new BusinessRef(
                        new PublishedResourceRef(app, 1, "app-hash", form, "FORM"),
                        new ApplicationCenter.ObjectReference(object, 1, "object-hash"),
                        null,
                        null);
        return new ResourceGrant(key, new Binding(app, form, null), ref, List.of(grant));
    }

    private TaskWorkRecordDO contribution(
            String id, BusinessRef ref, String recordId, String requestId) throws Exception {
        TaskWorkRecordDO row = new TaskWorkRecordDO();
        row.setId(id);
        row.setBusinessJson(
                json.writeValueAsString(
                        new BusinessRef(ref.resource(), ref.object(), recordId, requestId)));
        return row;
    }

    private TaskInstanceDO task(String id, String rootId, Long assignee, String status) {
        TaskInstanceDO row = new TaskInstanceDO();
        row.setId(id);
        row.setRootId(rootId);
        row.setTitle(id);
        row.setCreator("1");
        row.setAssigneeId(assignee);
        row.setStatus(status);
        return row;
    }

    private AdminUserRespDTO activeUser(long actor) {
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(actor);
        user.setStatus(0);
        return user;
    }

    private void assertScopeCleared() {
        assertThat(scope.current()).isNull();
        assertThat(scope.delegated()).isFalse();
        assertThat(runtime.active()).isFalse();
        assertThat(scope.recordIds("object")).isNull();
    }
}
