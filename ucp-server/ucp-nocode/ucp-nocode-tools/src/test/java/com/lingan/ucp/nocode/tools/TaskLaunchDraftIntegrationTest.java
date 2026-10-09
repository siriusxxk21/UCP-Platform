package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftServiceImpl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 当前开发库上的草稿私有边界、并发首次保存、发布幂等和任务共同回滚；只清理本类自有标识。 */
class TaskLaunchDraftIntegrationTest {
    private static final long OWNER = 10001L;
    private static AnnotationConfigApplicationContext draftContext;
    private static TaskLaunchDraftService drafts;
    private final Set<String> draftIds = new LinkedHashSet<>();
    private final Set<String> requestKeys = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
        draftContext = new AnnotationConfigApplicationContext();
        draftContext.setParent(servicesContext);
        draftContext.registerBean(
                TaskLaunchDraftMapper.class, () -> session.getMapper(TaskLaunchDraftMapper.class));
        draftContext.register(TaskLaunchDraftServiceImpl.class);
        draftContext.refresh();
        drafts = draftContext.getBean(TaskLaunchDraftService.class);
    }

    @AfterAll
    static void end() {
        if (draftContext != null) draftContext.close();
        close();
    }

    @BeforeEach
    void users() {
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        when(users.getUser(anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("草稿验证成员" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        for (String key : requestKeys) {
            List<String> roots =
                    jdbc.queryForList(
                            "select root_id from public.nocode_task_instance where creator=? and"
                                    + " request_key=?",
                            String.class,
                            Long.toString(OWNER),
                            key);
            for (String root : roots) {
                jdbc.update("delete from public.nocode_task_event where root_id=?", root);
                jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
            }
        }
        for (String id : draftIds)
            jdbc.update(
                    "delete from public.nocode_task_launch_draft where id=? and creator=?",
                    id,
                    Long.toString(OWNER));
    }

    @Test
    void incompleteDraftIsPrivateEditableAndSoftDeletedWithoutTaskWrites() {
        String id = draftId();
        String key = requestKey();
        ApplicationRecords.Save unfinishedBusiness =
                new ApplicationRecords.Save(
                        "not-yet-selected", null, null, null, Map.of("unfinished", "尚未填写完整"), null);
        Create incomplete = new Create(null, null, null, null, null, unfinishedBusiness, null, key);
        Draft initial = drafts.save(new DraftSave(id, 0, incomplete), OWNER);
        assertThat(initial.revision()).isEqualTo(1);
        assertThat(initial.content()).isEqualTo(incomplete);
        assertThat(drafts.list(OWNER)).extracting(DraftSummary::id).contains(id);
        assertThat(drafts.list(OWNER + 1)).extracting(DraftSummary::id).doesNotContain(id);
        assertThatThrownBy(() -> drafts.get(id, OWNER + 1)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> drafts.save(new DraftSave(id, 0, incomplete), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> drafts.save(new DraftSave(id, 1, incomplete), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> drafts.publish(new DraftPublish(id, 1, key), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> drafts.delete(new DraftRef(id, 1), OWNER + 1))
                .hasMessageContaining("无权访问");
        assertThat(taskCount(key)).isZero();

        Draft changed = drafts.save(new DraftSave(id, 1, runnable("保存后的标题", key)), OWNER);
        assertThat(changed.revision()).isEqualTo(2);
        assertThat(changed.content().task().title()).isEqualTo("保存后的标题");
        assertThatThrownBy(() -> drafts.save(new DraftSave(id, 1, incomplete), OWNER))
                .hasMessageContaining("已被修改");
        assertThatThrownBy(() -> drafts.delete(new DraftRef(id, 1), OWNER))
                .hasMessageContaining("已被修改");
        drafts.delete(new DraftRef(id, changed.revision()), OWNER);
        assertThat(drafts.list(OWNER)).extracting(DraftSummary::id).doesNotContain(id);
        assertThatThrownBy(() -> drafts.get(id, OWNER)).hasMessageContaining("无权访问");
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_launch_draft where id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(taskCount(key)).isZero();
    }

    @Test
    void concurrentFirstSaveAndPublishHaveOnePersistentResult() throws Exception {
        String id = draftId();
        String key = requestKey();
        DraftSave saving = new DraftSave(id, 0, runnable("并发草稿", key));
        List<Draft> saved = concurrent(() -> drafts.save(saving, OWNER));
        assertThat(saved).extracting(Draft::id).containsOnly(id);
        assertThat(saved).extracting(Draft::revision).containsOnly(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_launch_draft where id=?",
                                Long.class,
                                id))
                .isEqualTo(1L);
        assertThatThrownBy(() -> drafts.save(new DraftSave(id, 0, runnable("不同内容", key)), OWNER))
                .hasMessageContaining("已被修改");

        DraftPublish command = new DraftPublish(id, 1, key);
        List<Detail> published = concurrent(() -> drafts.publish(command, OWNER));
        assertThat(published.get(1).task().id()).isEqualTo(published.get(0).task().id());
        assertThat(taskCount(key)).isEqualTo(1L);
        Draft receipt = drafts.get(id, OWNER);
        assertThat(receipt.publishedTaskId()).isEqualTo(published.get(0).task().id());
        assertThat(receipt.revision()).isEqualTo(2);
        assertThat(drafts.list(OWNER)).extracting(DraftSummary::id).doesNotContain(id);
        assertThatThrownBy(() -> drafts.publish(new DraftPublish(id, 1, requestKey()), OWNER))
                .hasMessageContaining("不能更换请求标识");
        assertThatThrownBy(() -> drafts.save(new DraftSave(id, 2, saving.content()), OWNER))
                .hasMessageContaining("不能修改或删除");
    }

    @Test
    void failureAfterPublishMarkerRollsBackCreatedTaskAndDraftRevision() {
        String id = draftId();
        String key = requestKey();
        Draft original = drafts.save(new DraftSave(id, 0, runnable("原子发布", key)), OWNER);
        DraftPublish command = new DraftPublish(id, original.revision(), key);
        writeFailure.failAfter("UPDATE public.nocode_task_launch_draft SET published_task_id");
        try {
            assertThatThrownBy(() -> drafts.publish(command, OWNER))
                    .hasStackTraceContaining("intentional failure");
        } finally {
            writeFailure.clear();
        }
        assertThat(taskCount(key)).isZero();
        Draft stillDraft = drafts.get(id, OWNER);
        assertThat(stillDraft).isEqualTo(original);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_event where request_key=?",
                                Long.class,
                                key))
                .isZero();
        assertThat(drafts.publish(command, OWNER).task().id()).isNotBlank();
        assertThat(taskCount(key)).isEqualTo(1L);
    }

    private String draftId() {
        String id = UUID.randomUUID().toString();
        draftIds.add(id);
        return id;
    }

    private String requestKey() {
        String key = "task-draft-test-" + UUID.randomUUID();
        requestKeys.add(key);
        return key;
    }

    private long taskCount(String key) {
        return jdbc.queryForObject(
                "select count(*) from public.nocode_task_instance where creator=? and"
                        + " request_key=?",
                Long.class,
                Long.toString(OWNER),
                key);
    }

    private Create runnable(String title, String key) {
        NodeInput root =
                new NodeInput(
                        "root",
                        null,
                        title,
                        null,
                        OWNER,
                        null,
                        null,
                        new Schedule(TimeMode.T0, null, 0, 0),
                        List.of(),
                        null,
                        null);
        return new Create(root, null, null, null, null, null, null, key);
    }

    private <T> List<T> concurrent(Callable<T> action) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = executor.invokeAll(List.of(action, action));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        }
    }
}
