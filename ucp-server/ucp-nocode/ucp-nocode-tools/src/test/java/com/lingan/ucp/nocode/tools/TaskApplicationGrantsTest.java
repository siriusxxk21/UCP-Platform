package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.DataScope;
import com.lingan.ucp.nocode.api.TaskCenter.Binding;
import com.lingan.ucp.nocode.api.TaskCenter.BusinessRef;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.ResourceGrant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 任务内应用业务能力按固定版本和实时共享上限展开，不增加成员或应用管理授权。 */
class TaskApplicationGrantsTest {
    private final ApplicationService applications = mock(ApplicationService.class);
    private final ApplicationPublishedService published = mock(ApplicationPublishedService.class);
    private final ObjectSharingService sharing = mock(ObjectSharingService.class);
    private final DataObjectApi objects = mock(DataObjectApi.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final TaskDataPolicyCompiler compiler = new TaskDataPolicyCompiler();
    private final ApplicationCenter.ObjectReference orderRef = ref("order", 1);
    private final ApplicationCenter.ObjectReference receiptRef = ref("receipt", 1);
    private final ApplicationCenter.ObjectReference internalRef = ref("internal", 1);
    private final ApplicationCenter.Resource orderForm = resource("order-form", "FORM", "order");
    private final ApplicationCenter.Resource receiptView =
            resource("receipt-view", "VIEW", "receipt");
    private final ResourceGrant selected =
            new ResourceGrant(
                    "__business",
                    new Binding("app", "order-form", null),
                    new BusinessRef(
                            new PublishedResourceRef("app", 1, "app-v1", "order-form", "FORM"),
                            orderRef,
                            null,
                            null),
                    List.of());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(compiler, "applications", applications);
        ReflectionTestUtils.setField(compiler, "published", published);
        ReflectionTestUtils.setField(compiler, "sharing", sharing);
        ReflectionTestUtils.setField(compiler, "objects", objects);
        ReflectionTestUtils.setField(compiler, "validator", new ObjectGrantValidator());
        ReflectionTestUtils.setField(compiler, "users", users);
        AdminUserRespDTO grantor = new AdminUserRespDTO();
        grantor.setId(1L);
        grantor.setStatus(0);
        when(users.getUser(1L)).thenReturn(grantor);
        for (String object : List.of("order", "receipt", "internal")) {
            when(objects.getVersion(object, 1))
                    .thenReturn(
                            new DataObjectApi.PublishedObject(
                                    object, 1, object + "-v1", definition(object, false)));
            when(sharing.storedPermission(object, "app"))
                    .thenReturn(grant(object, Set.of("*"), Set.of("*")));
        }
        ApplicationCenter.Published frozen =
                release(
                        1,
                        List.of(orderRef, receiptRef, internalRef),
                        List.of(
                                orderForm,
                                receiptView,
                                resource("internal-rule", "AUTOMATION", "internal")));
        when(published.getVersion("app", 1)).thenReturn(frozen);
        when(published.getCurrent("app")).thenReturn(frozen);
    }

    @Test
    void fixedApplicationFormsAndViewsReceiveOnlySharedBusinessCrud() {
        Map<String, List<ObjectGrant>> result = compiler.applicationGrants(selected, 1L);
        assertThat(result).containsOnlyKeys("order", "receipt");
        for (String object : List.of("order", "receipt")) {
            ObjectGrant effective = result.get(object).getFirst();
            assertThat(effective.actions())
                    .containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
            assertThat(effective.readFields()).containsExactlyInAnyOrder("name", "amount");
            assertThat(effective.writeFields()).containsExactlyInAnyOrder("name", "amount");
            assertThat(effective.computeFields()).isEmpty();
        }
        verify(applications).requireDesigner("app", 1L);
        verifyNoMoreInteractions(applications);
        verify(sharing, never()).storedPermission("internal", "app");
    }

    @Test
    void currentSharingActionsFieldsAndRecordConditionsRemainTheCeiling() {
        DataScope condition =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition("name", "IS_NOT_NULL", null)),
                        List.of());
        when(sharing.storedPermission("receipt", "app"))
                .thenReturn(
                        new ObjectGrant(
                                "receipt",
                                Set.of("READ", "UPDATE", "EXPORT"),
                                "OWN",
                                Set.of("name"),
                                Set.of("name", "amount"),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Map.of("READ", condition, "UPDATE", condition, "EXPORT", condition),
                                Set.of("amount")));
        ObjectGrant effective = compiler.applicationGrants(selected, 1L).get("receipt").getFirst();
        assertThat(effective.actions()).containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(effective.scope()).isEqualTo("OWN");
        assertThat(effective.readFields()).containsExactly("name");
        assertThat(effective.writeFields()).containsExactly("name");
        assertThat(effective.actionScopes()).containsOnlyKeys("READ", "UPDATE");
        assertThat(effective.actionScopes().get("READ")).isEqualTo(condition);
        assertThat(effective.computeFields()).isEmpty();
    }

    @Test
    void laterApplicationObjectsAndObjectFieldsDoNotEnterTheOldTaskVersion() {
        when(objects.getVersion("receipt", 2))
                .thenReturn(
                        new DataObjectApi.PublishedObject(
                                "receipt", 2, "receipt-v2", definition("receipt", true)));
        when(published.getCurrent("app"))
                .thenReturn(
                        release(
                                2,
                                List.of(orderRef, ref("receipt", 2), ref("new-object", 1)),
                                List.of(
                                        orderForm,
                                        receiptView,
                                        resource("new-form", "FORM", "new-object"))));

        Map<String, List<ObjectGrant>> result = compiler.applicationGrants(selected, 1L);
        assertThat(result).containsOnlyKeys("order", "receipt");
        assertThat(result.get("receipt").getFirst().readFields())
                .containsExactlyInAnyOrder("name", "amount")
                .doesNotContain("new-secret", "*");
        assertThat(result.get("receipt").getFirst().writeFields())
                .doesNotContain("new-secret", "*");
        verify(objects).getVersion("receipt", 1);
        verify(objects, never()).getVersion("receipt", 2);
        verify(objects, never()).getPublished(anyString());
        verify(sharing, never()).storedPermission("new-object", "app");
    }

    @Test
    void withdrawnResourcesAndRevokedSharingImmediatelyDisappear() {
        when(published.getCurrent("app"))
                .thenReturn(release(2, List.of(orderRef, receiptRef), List.of(orderForm)));
        assertThat(compiler.applicationGrants(selected, 1L)).containsOnlyKeys("order");
        when(published.getCurrent("app"))
                .thenReturn(
                        release(3, List.of(orderRef, receiptRef), List.of(orderForm, receiptView)));
        when(sharing.storedPermission("receipt", "app")).thenReturn(null);
        assertThat(compiler.applicationGrants(selected, 1L)).containsOnlyKeys("order");
        when(sharing.storedPermission("order", "app")).thenReturn(null);
        assertThat(compiler.applicationGrants(selected, 1L)).isEmpty();
    }

    @Test
    void restoredReferenceWithoutCurrentReadPermissionCannotCreateAGrant() {
        when(sharing.storedPermission("receipt", "app"))
                .thenReturn(
                        new ObjectGrant(
                                "receipt",
                                Set.of("UPDATE"),
                                "ALL",
                                Set.of("name"),
                                Set.of("name"),
                                Set.of(),
                                Set.of()));
        assertThat(compiler.applicationGrants(selected, 1L)).containsOnlyKeys("order");
    }

    @Test
    void revokedGrantorCannotDelegateApplicationBusinessCapabilities() {
        doThrow(new AccessDeniedException("应用授权资格已收回"))
                .when(applications)
                .requireDesigner("app", 1L);
        assertThatThrownBy(() -> compiler.applicationGrants(selected, 1L))
                .hasMessageContaining("资格已收回");
        verifyNoInteractions(sharing);
        verifyNoInteractions(published);
    }

    private ObjectGrant grant(String object, Set<String> read, Set<String> write) {
        return new ObjectGrant(
                object,
                Set.of(
                        "READ",
                        "CREATE",
                        "UPDATE",
                        "DELETE",
                        "IMPORT",
                        "EXPORT",
                        "START_PROCESS",
                        "MANAGE"),
                "ALL",
                read,
                write,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                read);
    }

    private DataCenter.Definition definition(String object, boolean newer) {
        List<com.lingan.ucp.nocode.api.FieldDefinition> fields =
                new java.util.ArrayList<>(
                        List.of(
                                RuleFixtures.field("name", "name", "名称", "TEXT"),
                                RuleFixtures.field("amount", "amount", "数量", "DECIMAL")));
        if (newer) fields.add(RuleFixtures.field("new-secret", "new_secret", "新字段", "TEXT"));
        return RuleFixtures.object(object, object, fields, Map.of(), List.of(), List.of());
    }

    private static ApplicationCenter.ObjectReference ref(String object, int version) {
        return new ApplicationCenter.ObjectReference(object, version, object + "-v" + version);
    }

    private ApplicationCenter.Resource resource(String id, String kind, String object) {
        return new ApplicationCenter.Resource(id, kind, id, id, Map.of("objectId", object));
    }

    private ApplicationCenter.Published release(
            int version,
            List<ApplicationCenter.ObjectReference> refs,
            List<ApplicationCenter.Resource> resources) {
        return new ApplicationCenter.Published(
                null,
                version,
                "app-v" + version,
                new ApplicationCenter.Definition(refs, resources),
                List.of());
    }
}
