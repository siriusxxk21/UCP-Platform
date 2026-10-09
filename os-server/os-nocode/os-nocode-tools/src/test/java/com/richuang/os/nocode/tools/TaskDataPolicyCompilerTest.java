package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.api.TaskCenter.Binding;
import com.richuang.os.nocode.api.TaskCenter.BusinessRef;
import com.richuang.os.nocode.api.TaskEntries;
import com.richuang.os.nocode.api.work.PublishedResourceRef;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.application.service.task.TaskEntryPolicyService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.ResourceGrant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验证委托资格、固定上限及实时收紧；不依赖运行数据库或操作者的个人应用成员权限。 */
class TaskDataPolicyCompilerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ApplicationService applications = mock(ApplicationService.class);
    private final ApplicationPublishedService published = mock(ApplicationPublishedService.class);
    private final ObjectSharingService sharing = mock(ObjectSharingService.class);
    private final DataObjectApi objects = mock(DataObjectApi.class);
    private final TaskEntryPolicyService entryPolicies = mock(TaskEntryPolicyService.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final Binding binding = new Binding("app", "form", null);
    private final BusinessRef ref =
            new BusinessRef(
                    new PublishedResourceRef("app", 1, "app-hash", "form", "FORM"),
                    new ApplicationCenter.ObjectReference("object", 1, "object-hash"),
                    null,
                    null);
    private TaskDataPolicyCompiler compiler;

    @BeforeEach
    void setup() {
        compiler = new TaskDataPolicyCompiler();
        ReflectionTestUtils.setField(compiler, "applications", applications);
        ReflectionTestUtils.setField(compiler, "published", published);
        ReflectionTestUtils.setField(compiler, "sharing", sharing);
        ReflectionTestUtils.setField(compiler, "objects", objects);
        com.richuang.os.nocode.runtime.service.taskcenter.TaskBoundViews boundViews =
                new com.richuang.os.nocode.runtime.service.taskcenter.TaskBoundViews();
        ReflectionTestUtils.setField(boundViews, "published", published);
        ReflectionTestUtils.setField(boundViews, "json", json);
        ReflectionTestUtils.setField(compiler, "boundViews", boundViews);
        DataCenter.Definition definition =
                RuleFixtures.object(
                        "object",
                        "任务业务",
                        List.of(
                                RuleFixtures.field("id", "id", "编号", "INTEGER"),
                                RuleFixtures.field("name", "name", "名称", "TEXT"),
                                RuleFixtures.field("budget", "budget", "预算", "MONEY")),
                        Map.of(),
                        List.of(),
                        List.of());
        when(objects.getVersion("object", 1))
                .thenReturn(
                        new DataObjectApi.PublishedObject("object", 1, "object-hash", definition));
        ReflectionTestUtils.setField(compiler, "validator", new ObjectGrantValidator());
        ReflectionTestUtils.setField(compiler, "entryPolicies", entryPolicies);
        ReflectionTestUtils.setField(compiler, "users", users);
        ReflectionTestUtils.setField(compiler, "json", json);
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(1L);
        user.setStatus(0);
        when(users.getUser(1L)).thenReturn(user);
        when(published.resolve(ref.resource())).thenReturn(form("object"));
        when(published.getVersion("app", 1)).thenReturn(release(List.of(form("object"))));
        when(published.getCurrent("app")).thenReturn(release(List.of(form("object"))));
        when(sharing.storedPermission("object", "app")).thenReturn(grant(Set.of("name", "budget")));
    }

    @Test
    void onlyApprovedCrudIsCompiledWithoutOtherPlatformActions() {
        ResourceGrant frozen = compiler.compile("__business", binding, ref, 1L);
        assertThat(frozen.grants()).hasSize(1);
        assertThat(frozen.grants().getFirst().actions())
                .containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
        assertThat(frozen.grants().getFirst().computeFields()).isEmpty();
        assertThat(frozen.ref()).isEqualTo(ref);
        verify(applications).requireDesigner("app", 1L);
    }

    @Test
    void allSharingIsExpandedBeforeFreezingAndNeverWidensTheTask() {
        when(sharing.storedPermission("object", "app")).thenReturn(grant(Set.of("*")));
        ResourceGrant frozen = compiler.compile("__business", binding, ref, 1L);
        ObjectGrant snapshot = frozen.grants().getFirst();
        assertThat(snapshot.readFields()).contains("name", "budget").doesNotContain("*");
        assertThat(snapshot.writeFields()).contains("name", "budget").doesNotContain("*");
        when(sharing.storedPermission("object", "app")).thenReturn(grant(Set.of("name")));
        assertThat(compiler.revalidate(frozen, 1L).getFirst().writeFields())
                .containsExactly("name");
        verify(objects, atLeastOnce()).getVersion("object", 1);
        verify(objects, never()).getPublished(anyString());
    }

    @Test
    void taskCreationAloneCannotDelegateAndDisabledGrantorIsRejected() {
        doThrow(new AccessDeniedException("只能管理自己创建的应用"))
                .when(applications)
                .requireDesigner("app", 1L);
        assertThatThrownBy(() -> compiler.compile("__business", binding, ref, 1L))
                .isInstanceOf(AccessDeniedException.class);
        reset(applications);
        users.getUser(1L).setStatus(1);
        assertThatThrownBy(() -> compiler.compile("__business", binding, ref, 1L))
                .hasMessageContaining("已停用");
        verifyNoInteractions(applications);
    }

    @Test
    void snapshotNeverGrowsAndLiveRevocationImmediatelyTightensIt() {
        when(sharing.storedPermission("object", "app")).thenReturn(grant(Set.of("name")));
        ResourceGrant frozen = compiler.compile("__business", binding, ref, 1L);
        when(sharing.storedPermission("object", "app")).thenReturn(grant(Set.of("name", "budget")));
        assertThat(compiler.revalidate(frozen, 1L).getFirst().writeFields())
                .containsExactly("name");
        ObjectGrant readOnly =
                new ObjectGrant(
                        "object",
                        Set.of("READ"),
                        "ALL",
                        Set.of("name"),
                        Set.of(),
                        Set.of(),
                        Set.of());
        when(sharing.storedPermission("object", "app")).thenReturn(readOnly);
        ObjectGrant effective = compiler.revalidate(frozen, 1L).getFirst();
        assertThat(effective.actions()).containsExactly("READ");
        assertThat(effective.writeFields()).isEmpty();
        when(sharing.storedPermission("object", "app")).thenReturn(null);
        assertThatThrownBy(() -> compiler.revalidate(frozen, 1L)).hasMessageContaining("共享已撤销");
    }

    @Test
    void entryLimitsAreFrozenAndCannotDelegateAnUnrelatedObject() {
        Binding entryBinding = new Binding("app", "form", "entry");
        TaskEntries.Config config =
                new TaskEntries.Config(
                        "object",
                        "view",
                        "form",
                        "LIST",
                        "",
                        "",
                        "",
                        0,
                        List.of(
                                grant(Set.of("name")),
                                new ObjectGrant(
                                        "other",
                                        Set.of("READ"),
                                        "ALL",
                                        Set.of("secret"),
                                        Set.of(),
                                        Set.of(),
                                        Set.of())));
        ApplicationCenter.Resource entry =
                new ApplicationCenter.Resource(
                        "entry",
                        "TASK_ENTRY",
                        "entry",
                        "入口",
                        json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        when(published.getVersion("app", 1)).thenReturn(release(List.of(form("object"), entry)));
        when(published.getCurrent("app")).thenReturn(release(List.of(form("object"), entry)));
        when(entryPolicies.get("app", "entry"))
                .thenReturn(new TaskEntries.Policy(1, true, List.of()));
        ResourceGrant frozen = compiler.compile("feedback", entryBinding, ref, 1L);
        assertThat(frozen.grants()).hasSize(1);
        assertThat(frozen.grants().getFirst().objectId()).isEqualTo("object");
        assertThat(frozen.grants().getFirst().readFields()).containsExactly("name");
        when(entryPolicies.get("app", "entry"))
                .thenReturn(new TaskEntries.Policy(2, false, List.of()));
        assertThatThrownBy(() -> compiler.revalidate(frozen, 1L)).hasMessageContaining("已停用");
    }

    @Test
    void removedOrRetargetedFormCannotKeepAnOldDelegationAlive() {
        ResourceGrant frozen = compiler.compile("__business", binding, ref, 1L);
        when(published.getCurrent("app")).thenReturn(release(List.of()));
        assertThatThrownBy(() -> compiler.revalidate(frozen, 1L)).hasMessageContaining("已撤下");
        when(published.getCurrent("app")).thenReturn(release(List.of(form("other"))));
        assertThatThrownBy(() -> compiler.revalidate(frozen, 1L)).hasMessageContaining("不匹配");
    }

    @Test
    void mismatchedBindingFailsBeforeDelegationAndGrantorMustStayAuthorized() {
        assertThatThrownBy(
                        () ->
                                compiler.compile(
                                        "__business",
                                        new Binding("other-app", "form", null),
                                        ref,
                                        1L))
                .hasMessageContaining("明确的已发布");
        verifyNoInteractions(applications);
        ResourceGrant frozen = compiler.compile("__business", binding, ref, 1L);
        doThrow(new AccessDeniedException("授权资格已收回")).when(applications).requireDesigner("app", 1L);
        assertThatThrownBy(() -> compiler.revalidate(frozen, 1L)).hasMessageContaining("授权资格已收回");
    }

    private ObjectGrant grant(Set<String> fields) {
        return new ObjectGrant(
                "object",
                Set.of("READ", "CREATE", "UPDATE", "DELETE", "EXPORT", "IMPORT", "START_PROCESS"),
                "ALL",
                fields,
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                fields);
    }

    private ApplicationCenter.Resource form(String object) {
        return new ApplicationCenter.Resource(
                "form", "FORM", "form", "业务表单", Map.of("objectId", object));
    }

    private ApplicationCenter.Published release(List<ApplicationCenter.Resource> resources) {
        return new ApplicationCenter.Published(
                null,
                1,
                "app-hash",
                new ApplicationCenter.Definition(List.of(ref.object()), resources),
                List.of());
    }
}
