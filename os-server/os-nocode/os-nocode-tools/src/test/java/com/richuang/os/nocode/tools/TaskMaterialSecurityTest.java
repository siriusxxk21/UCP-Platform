package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.work.PublishedResourceRef;
import com.richuang.os.nocode.runtime.dal.dataobject.*;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.service.handling.HandlingMaterials;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 历史材料不可作为撤权后的后门；纯函数覆盖字段裁剪，服务入口覆盖先授权再读取。 */
class TaskMaterialSecurityTest {
    @Test
    void rejectedInputRestorationRemovesRevokedAndInactiveMaterialButKeepsWritableFields() {
        ApplicationAuthorization.Capabilities rights =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ", "CREATE", "UPDATE"),
                        Set.of("name", "inactive", "removed", "hidden"),
                        Set.of("name", "secret", "inactive", "removed", "hidden"),
                        Set.of("items", "inactive-detail"),
                        Set.of("items", "inactive-detail"),
                        Set.of("allowed", "removed-relation"),
                        Set.of("allowed", "removed-relation"));
        ApplicationRecords.Row row =
                new ApplicationRecords.Row(
                        null,
                        null,
                        Map.of(
                                "name",
                                "保留输入",
                                "secret",
                                "撤权值",
                                "inactive",
                                "停用值",
                                "removed",
                                "删除值",
                                "hidden",
                                "隐藏值"),
                        rights,
                        Map.of(
                                "name",
                                "保留标签",
                                "secret",
                                "撤权标签",
                                "inactive",
                                "停用标签",
                                "removed",
                                "删除标签",
                                "hidden",
                                "隐藏标签"));
        ApplicationRecords.Row item =
                new ApplicationRecords.Row(
                        "temporary-item",
                        null,
                        Map.of(
                                "item",
                                "可编辑明细",
                                "child-secret",
                                "明细秘密",
                                "child-inactive",
                                "停用明细",
                                "unknown",
                                "删除列"),
                        null,
                        Map.of(
                                "item",
                                "保留明细标签",
                                "child-secret",
                                "明细秘密标签",
                                "child-inactive",
                                "停用标签"));
        ApplicationRecords.Aggregate initial =
                new ApplicationRecords.Aggregate(
                        row,
                        Map.of(
                                "items",
                                List.of(item),
                                "revoked-detail",
                                List.of(item),
                                "inactive-detail",
                                List.of(item)),
                        List.of(),
                        Map.of(
                                "allowed",
                                List.of("visible-record"),
                                "revoked-relation",
                                List.of("secret-record"),
                                "removed-relation",
                                List.of("removed-record")));
        DataCenter.FieldOptions inactive =
                new ObjectMapper()
                        .convertValue(Map.of("state", "INACTIVE"), DataCenter.FieldOptions.class);
        DataCenter.Detail visibleItems =
                materialDetail(
                        "items",
                        "ACTIVE",
                        List.of(field("item"), field("child-inactive")),
                        Map.of());
        DataCenter.Detail currentItems =
                materialDetail(
                        "items",
                        "ACTIVE",
                        List.of(field("item"), field("child-secret"), field("child-inactive")),
                        Map.of("child-inactive", inactive));
        DataCenter.Detail revoked =
                materialDetail("revoked-detail", "ACTIVE", List.of(field("item")), Map.of());
        DataCenter.Detail stopped =
                materialDetail("inactive-detail", "INACTIVE", List.of(field("item")), Map.of());
        DataCenter.Definition visible =
                materialDefinition(
                        List.of(
                                field("name"),
                                field("secret"),
                                field("inactive"),
                                field("removed")),
                        Map.of(),
                        List.of(visibleItems, revoked, stopped),
                        List.of(
                                materialRelation("allowed"),
                                materialRelation("revoked-relation"),
                                materialRelation("removed-relation")));
        DataCenter.Definition current =
                materialDefinition(
                        List.of(field("name"), field("secret"), field("inactive"), field("hidden")),
                        Map.of("inactive", inactive),
                        List.of(currentItems, revoked, stopped),
                        List.of(materialRelation("allowed"), materialRelation("revoked-relation")));

        ApplicationRecords.Aggregate restored =
                HandlingMaterials.restore(initial, visible, current);
        assertThat(restored.record().values()).containsExactlyEntriesOf(Map.of("name", "保留输入"));
        assertThat(restored.record().displayValues())
                .containsExactlyEntriesOf(Map.of("name", "保留标签"));
        assertThat(restored.record().permissions().actions()).contains("CREATE", "UPDATE");
        assertThat(restored.record().permissions().writeFields()).containsExactly("name");
        assertThat(restored.record().permissions().writeDetails()).containsExactly("items");
        assertThat(restored.record().permissions().writeRelations()).containsExactly("allowed");
        assertThat(restored.details()).containsOnlyKeys("items");
        ApplicationRecords.Row restoredItem = restored.details().get("items").getFirst();
        assertThat(restoredItem.values()).containsExactlyEntriesOf(Map.of("item", "可编辑明细"));
        assertThat(restoredItem.displayValues()).containsExactlyEntriesOf(Map.of("item", "保留明细标签"));
        assertThat(restoredItem.permissions().writeFields()).containsExactly("item");
        assertThat(restored.relations())
                .containsExactlyEntriesOf(Map.of("allowed", List.of("visible-record")));
        assertThat(initial.record().values()).containsEntry("secret", "撤权值");
        assertThat(initial.details()).containsKey("revoked-detail");
    }

    @Test
    void historicalValuesAreImmutableButUnknownAndRevokedFieldsAreRemoved() {
        ApplicationAuthorization.Capabilities rights =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ", "UPDATE"),
                        Set.of("name", "removed"),
                        Set.of("name"),
                        Set.of(),
                        Set.of());
        ApplicationRecords.Aggregate frozen =
                new ApplicationRecords.Aggregate(
                        new ApplicationRecords.Row(
                                "r",
                                "old",
                                Map.of("name", "完成时内容", "secret", "秘密", "removed", "废弃字段")),
                        Map.of());
        ApplicationRecords.Aggregate current =
                new ApplicationRecords.Aggregate(
                        new ApplicationRecords.Row(
                                "r", "new", Map.of("name", "之后改动", "removed", "不可识别"), rights),
                        Map.of());
        ApplicationRecords.Aggregate result = TaskMaterials.project(frozen, current, definition());
        assertThat(result.record().values()).containsExactlyEntriesOf(Map.of("name", "完成时内容"));
        assertThat(result.record().revision()).isEqualTo("old");
        assertThat(result.record().permissions().actions()).containsExactly("READ");
        assertThat(result.record().permissions().writeFields()).isEmpty();
    }

    @Test
    void revokedCurrentRecordAccessPreventsReadingSavedMaterial() throws Exception {
        TaskCenterMapper store = Mockito.mock(TaskCenterMapper.class);
        TaskBusiness business = Mockito.mock(TaskBusiness.class);
        TaskCenterServiceImpl service = service(store, business);
        Mockito.when(business.read(Mockito.any(), Mockito.any(), Mockito.eq(1L)))
                .thenThrow(NocodeErrorCodes.invalid("没有此记录的查看权限"));
        assertThatThrownBy(() -> service.material(new MaterialRef("task", "event"), 1L))
                .hasMessageContaining("查看权限");
        Mockito.verify(business, Mockito.never())
                .model(Mockito.any(), Mockito.any(), Mockito.anyLong());
    }

    @Test
    void anotherTasksEventAndAdjustmentPayloadCannotBeReadAsCompletionMaterial() throws Exception {
        TaskCenterMapper store = Mockito.mock(TaskCenterMapper.class);
        TaskBusiness business = Mockito.mock(TaskBusiness.class);
        TaskCenterServiceImpl service = service(store, business);
        assertThatThrownBy(() -> service.material(new MaterialRef("task", "wrong"), 1L))
                .hasMessageContaining("不存在");
        Mockito.verifyNoInteractions(business);
    }

    private TaskCenterServiceImpl service(TaskCenterMapper store, TaskBusiness business)
            throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        TaskCenterServiceImpl service = new TaskCenterServiceImpl();
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "business", business);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(
                service, "permissions", Mockito.mock(PermissionCommonApi.class));
        TaskInstanceDO task = new TaskInstanceDO();
        task.setId("task");
        task.setRootId("task");
        task.setCreator("1");
        task.setAssigneeId(1L);
        NodeInput node =
                new NodeInput(
                        "task",
                        null,
                        "材料测试",
                        null,
                        1L,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        new Schedule(TimeMode.T0, null, 0, 0),
                        List.of(),
                        new Binding("app", "form", null),
                        new Sharing(DataMode.INDEPENDENT, null, List.of()));
        task.setConfigJson(json.writeValueAsString(node));
        Mockito.when(store.get("task", false)).thenReturn(task);
        Mockito.when(store.instance("task")).thenReturn(List.of(task));
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId("event");
        event.setTaskId("task");
        event.setEventType("COMPLETED");
        BusinessRef ref =
                new BusinessRef(
                        new PublishedResourceRef("app", 1, "checksum", "form", "FORM"),
                        new ApplicationCenter.ObjectReference("object", 1, "checksum"),
                        "r",
                        null);
        event.setMaterialJson(
                json.writeValueAsString(
                        Map.of(
                                "binding",
                                ref,
                                "record",
                                new ApplicationRecords.Aggregate(
                                        new ApplicationRecords.Row(
                                                "r", "old", Map.of("name", "完成内容")),
                                        Map.of()))));
        Mockito.when(store.events("task")).thenReturn(List.of(event));
        return service;
    }

    private DataCenter.Definition definition() {
        FieldDefinition field =
                new FieldDefinition(
                        "name", "name", "name", "名称", "TEXT", 200, null, null, false, false, 0);
        return new DataCenter.Definition(
                "object",
                "business",
                "业务对象",
                null,
                "public",
                "biz_test",
                "GENERATED",
                false,
                "name",
                DataCenter.Settings.defaults(),
                List.of(field),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private FieldDefinition field(String id) {
        return new FieldDefinition(id, id, id, id, "TEXT", 200, null, null, false, false, 0);
    }

    private DataCenter.Detail materialDetail(
            String id,
            String state,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options) {
        return new DataCenter.Detail(
                id, id, id, "biz_" + id.replace('-', '_'), state, fields, options, List.of());
    }

    private DataCenter.Relation materialRelation(String id) {
        return new DataCenter.Relation(
                id, id, id, "REFERENCE", "target", null, null, false, "RESTRICT");
    }

    private DataCenter.Definition materialDefinition(
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Detail> details,
            List<DataCenter.Relation> relations) {
        return new DataCenter.Definition(
                "object",
                "business",
                "业务",
                null,
                "public",
                "biz_test",
                "GENERATED",
                false,
                "name",
                DataCenter.Settings.defaults(),
                fields,
                options,
                relations,
                List.of(),
                details);
    }
}
