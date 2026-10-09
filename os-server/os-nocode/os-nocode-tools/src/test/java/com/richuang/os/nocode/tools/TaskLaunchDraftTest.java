package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskLaunchDraftDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskLaunchDraftServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 不连接数据库的草稿契约回归；保存与发布的业务边界、原始内容及事务失败路径保持独立。 */
class TaskLaunchDraftTest {
    private static final long OWNER = 10001L;
    private static final String OWNER_TEXT = Long.toString(OWNER);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskLaunchDraftMapper drafts = mock(TaskLaunchDraftMapper.class);
    private final TaskCenterService tasks = mock(TaskCenterService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private TaskLaunchDraftServiceImpl service;

    @BeforeEach
    void setup() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new TaskLaunchDraftServiceImpl();
        ReflectionTestUtils.setField(service, "drafts", drafts);
        ReflectionTestUtils.setField(service, "tasks", tasks);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.invokeMethod(service, "initialize");
    }

    @Test
    void incompleteContentSavesAndFirstSaveRetryDoesNotCreateTasks() throws Exception {
        String id = UUID.randomUUID().toString();
        ApplicationRecords.Save business =
                new ApplicationRecords.Save(
                        "application", null, null, null, Map.of("pending", "尚未填完"), null);
        Create content =
                new Create(null, null, null, null, null, business, null, null, List.of(), null);
        TaskLaunchDraftDO row = row(id, content, 1);
        when(drafts.getOwned(id, OWNER_TEXT, true)).thenReturn(null, row);
        when(drafts.getOwned(id, OWNER_TEXT, false)).thenReturn(row);
        when(drafts.createDraft(any(), eq(OWNER_TEXT))).thenReturn(1);
        DraftSave command = new DraftSave(id, 0, content);

        Draft created = service.save(command, OWNER);
        assertThat(created.content()).isEqualTo(content);
        assertThat(service.save(command, OWNER)).isEqualTo(created);
        verify(drafts, times(2)).lockCreate(id);
        verify(drafts, times(1)).createDraft(any(), eq(OWNER_TEXT));
        verify(drafts, never()).saveDraft(any(), anyString(), anyInt());
        verifyNoInteractions(tasks);

        Create changed = new Create(null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.save(new DraftSave(id, 0, changed), OWNER))
                .hasMessageContaining("已被修改");
        row.setLockVersion(2);
        assertThatThrownBy(() -> service.save(command, OWNER)).hasMessageContaining("已被修改");
        assertThatThrownBy(() -> service.publish(new DraftPublish(id, 1, "publish"), OWNER))
                .hasMessageContaining("已被修改");
        assertThatThrownBy(() -> service.delete(new DraftRef(id, 1), OWNER))
                .hasMessageContaining("已被修改");
        verify(drafts, never()).markPublished(any(), anyString(), anyInt());
        verify(drafts, never()).deleteDraft(anyString(), anyString(), anyInt());
        verifyNoInteractions(tasks);
    }

    @Test
    void ownerChecksApplyToReadsSavesDeletesAndPublishing() throws Exception {
        String id = UUID.randomUUID().toString();
        Create content = new Create(null, null, null, null, null, null, null, null);
        TaskLaunchDraftDO row = row(id, content, 1);
        when(drafts.getOwned(id, OWNER_TEXT, false)).thenReturn(row);
        assertThat(service.get(id, OWNER).content()).isEqualTo(content);
        assertThatThrownBy(() -> service.get(id, OWNER + 1)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.save(new DraftSave(id, 1, content), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.delete(new DraftRef(id, 1), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.publish(new DraftPublish(id, 1, "publish"), OWNER + 1))
                .hasMessageContaining("无权访问");
        // 首次保存碰到他人的全局 ID，不能覆盖它或把内容作为幂等回执泄露。
        assertThatThrownBy(() -> service.save(new DraftSave(id, 0, content), OWNER + 1))
                .hasMessageContaining("无权访问");
        verifyNoInteractions(tasks);
        verify(drafts, never()).saveDraft(any(), anyString(), anyInt());
        verify(drafts, never()).deleteDraft(anyString(), anyString(), anyInt());
        verify(drafts, never()).markPublished(any(), anyString(), anyInt());
    }

    @Test
    void publishPreservesSavedContentAndSameKeyOnlyRecoversTheExistingTask() throws Exception {
        String id = UUID.randomUUID().toString();
        Create content = contentWithContext();
        TaskLaunchDraftDO row = row(id, content, 4);
        when(drafts.getOwned(id, OWNER_TEXT, true)).thenReturn(row);
        Detail result = result("created-task");
        when(tasks.create(any(), eq(OWNER))).thenReturn(result);
        when(tasks.detail("created-task", OWNER)).thenReturn(result);
        when(drafts.markPublished(row, OWNER_TEXT, 4)).thenReturn(1);

        DraftPublish command = new DraftPublish(id, 4, "publish-key");
        assertThat(service.publish(command, OWNER)).isSameAs(result);
        assertThat(service.publish(command, OWNER)).isSameAs(result);
        ArgumentCaptor<Create> created = ArgumentCaptor.forClass(Create.class);
        verify(tasks, times(1)).create(created.capture(), eq(OWNER));
        Create expected =
                new Create(
                        content.task(),
                        content.parentId(),
                        content.templateId(),
                        content.templateVersion(),
                        content.project(),
                        content.business(),
                        content.existingRecord(),
                        "publish-key",
                        content.nodes(),
                        content.kind(),
                        content.applicationId(),
                        content.plannedStart());
        assertThat(created.getValue()).isEqualTo(expected);
        assertThat(row.getContentJson()).isEqualTo(json.writeValueAsString(content));
        assertThat(row.getPublishedTaskId()).isEqualTo("created-task");
        assertThat(row.getPublishKey()).isEqualTo("publish-key");
        org.mockito.InOrder order = inOrder(drafts, tasks);
        order.verify(drafts).getOwned(id, OWNER_TEXT, true);
        order.verify(tasks).create(expected, OWNER);
        order.verify(drafts).markPublished(row, OWNER_TEXT, 4);
        assertThatThrownBy(() -> service.publish(new DraftPublish(id, 4, "another-key"), OWNER))
                .hasMessageContaining("不能更换请求标识");
        assertThatThrownBy(() -> service.save(new DraftSave(id, 4, content), OWNER))
                .hasMessageContaining("不能修改或删除");
        assertThatThrownBy(() -> service.delete(new DraftRef(id, 4), OWNER))
                .hasMessageContaining("不能修改或删除");
        verify(drafts, times(1)).markPublished(row, OWNER_TEXT, 4);
    }

    @Test
    void publishMarkerFailureRollsBackTheOuterTransaction() throws Exception {
        String id = UUID.randomUUID().toString();
        TaskLaunchDraftDO row = row(id, contentWithContext(), 1);
        Detail created = result("created-task");
        when(drafts.getOwned(id, OWNER_TEXT, true)).thenReturn(row);
        when(tasks.create(any(), eq(OWNER))).thenReturn(created);
        when(drafts.markPublished(any(), eq(OWNER_TEXT), eq(1))).thenReturn(0);

        assertThatThrownBy(() -> service.publish(new DraftPublish(id, 1, "publish-key"), OWNER))
                .hasMessageContaining("已被修改");
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void oversizedUtf8AndDeepDynamicValuesStopBeforePersistence() {
        ApplicationRecords.Save oversized =
                new ApplicationRecords.Save(
                        null, null, null, null, Map.of("value", "中".repeat(700_000)), null);
        Create content = new Create(null, null, null, null, null, oversized, null, null);
        assertThatThrownBy(() -> service.save(new DraftSave(null, null, content), OWNER))
                .hasMessageContaining("大小限制");

        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> current = nested;
        for (int index = 0; index < 70; index++) {
            Map<String, Object> child = new LinkedHashMap<>();
            current.put("nested", child);
            current = child;
        }
        ApplicationRecords.Save deep =
                new ApplicationRecords.Save(null, null, null, null, nested, null);
        Create deepContent = new Create(null, null, null, null, null, deep, null, null);
        assertThatThrownBy(() -> service.save(new DraftSave(null, null, deepContent), OWNER))
                .hasMessageContaining("结构超过限制");

        for (String unsafe : List.of("\u0000", "\uD800", "\uDC00")) {
            ApplicationRecords.Save invalidText =
                    new ApplicationRecords.Save(
                            null, null, null, null, Map.of("value", unsafe), null);
            Create invalidContent =
                    new Create(null, null, null, null, null, invalidText, null, null);
            assertThatThrownBy(() -> service.save(new DraftSave(null, null, invalidContent), OWNER))
                    .hasMessageContaining("无法保存的字符");
        }
        verifyNoInteractions(drafts, tasks, transactions);
    }

    private TaskLaunchDraftDO row(String id, Create content, int revision) throws Exception {
        TaskLaunchDraftDO row = new TaskLaunchDraftDO();
        row.setId(id);
        row.setCreator(OWNER_TEXT);
        row.setLockVersion(revision);
        row.setContentJson(json.writeValueAsString(content));
        row.setUpdateTime(LocalDateTime.of(2026, 10, 2, 14, 0));
        return row;
    }

    private Detail result(String id) {
        Row row = mock(Row.class);
        when(row.id()).thenReturn(id);
        return new Detail(row, List.of(row), List.of(), List.of());
    }

    private Create contentWithContext() {
        NodeInput root =
                new NodeInput("root", null, "编排", null, OWNER, null, null, null, null, null, null);
        NodeInput child =
                new NodeInput(
                        "child", "root", "子任务", null, null, null, null, null, null, null, null);
        ApplicationRecords.Save business =
                new ApplicationRecords.Save(
                        "application",
                        "object",
                        null,
                        null,
                        Map.of("field", "业务值"),
                        Map.of(),
                        Map.of(),
                        new ApplicationRecords.Context("page", "block", "record"),
                        "form",
                        "business-key",
                        null);
        return new Create(
                root,
                null,
                null,
                null,
                new RecordRef("application", "object", "record", "来源"),
                business,
                null,
                "old-key",
                List.of(child),
                Kind.PROCESS,
                "application",
                LocalDateTime.of(2026, 10, 3, 9, 0));
    }
}
