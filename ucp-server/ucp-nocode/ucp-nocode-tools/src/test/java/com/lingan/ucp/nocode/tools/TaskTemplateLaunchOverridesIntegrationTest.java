package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftServiceImpl;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.*;

/** 模板发起的完整子图调整、固定来源及草稿发布权限；只清理本类登记的随机夹具。 */
class TaskTemplateLaunchOverridesIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L;
    private static AnnotationConfigApplicationContext draftContext;
    private static TaskLaunchDraftService drafts;
    private TaskCenterService tasks;
    private final String marker = "launch_overrides_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>(),
            templates = new LinkedHashSet<>(),
            draftIds = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 本改动不需要迁移，不应用其他工作待执行脚本，仍验证当前开发库已执行版本。
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
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
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        Mockito.when(servicesContext.getBean(IMsgSendService.class).send(Mockito.any()))
                .thenReturn(999L);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("发起调整验证" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_instance where id=?"
                                            + " and title like ?",
                                    Integer.class,
                                    root,
                                    marker + "%"))
                    .isEqualTo(1);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        for (String id : draftIds)
            jdbc.update(
                    "delete from public.nocode_task_launch_draft where id=? and creator=?",
                    id,
                    Long.toString(OWNER));
        for (String id : templates) {
            jdbc.update("delete from public.nocode_task_template_version where template_id=?", id);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    id,
                    marker + "%");
        }
    }

    @Test
    void omittedNodesUseTemplateWhileExplicitEmptyRemovesAllChildren() {
        TemplateVersion template = template(null);
        Detail defaults = create(command(template, null));
        assertThat(defaults.nodes()).hasSize(3);
        Detail onlyRoot = create(command(template, List.of()));
        assertThat(onlyRoot.nodes()).hasSize(1);
        assertThat(onlyRoot.task().templateId()).isEqualTo(template.id());
        assertThat(onlyRoot.task().templateVersion()).isEqualTo(1);
        assertThat(tasks.version(template.id(), 1, OWNER).nodes()).hasSize(2);
    }

    @Test
    void overridesChangeOnlyThisInstanceAndKeepOriginIdsForRetainedNodes() {
        TemplateVersion template = template(null);
        LocalDateTime begin = LocalDateTime.of(2026, 10, 12, 9, 0);
        NodeInput retained =
                node(
                        "a",
                        "本次调整",
                        WORKER,
                        new Schedule(TimeMode.FIXED, begin, 0, 0, begin.plusDays(2)),
                        List.of(),
                        null,
                        null);
        NodeInput added =
                node(
                        "new",
                        "新增后续",
                        OWNER,
                        new Schedule(TimeMode.PREDECESSOR, null, 0, 1),
                        List.of("a"),
                        null,
                        null);
        Create command = command(template, List.of(retained, added));
        Detail created = create(command);
        assertThat(created.nodes())
                .extracting(Row::title)
                .contains(marker + "本次调整", marker + "新增后续")
                .doesNotContain(marker + "原子任务B");
        Row first =
                created.nodes().stream()
                        .filter(n -> n.title().equals(marker + "本次调整"))
                        .findFirst()
                        .orElseThrow();
        Row next =
                created.nodes().stream()
                        .filter(n -> n.title().equals(marker + "新增后续"))
                        .findFirst()
                        .orElseThrow();
        assertThat(first.assigneeId()).isEqualTo(WORKER);
        assertThat(first.expectedStart()).isEqualTo(begin);
        assertThat(next.predecessorIds()).containsExactly(first.id());
        assertThat(
                        jdbc.queryForObject(
                                "select template_node_id from public.nocode_task_instance where"
                                        + " id=?",
                                String.class,
                                first.id()))
                .isEqualTo("a");
        assertThat(
                        jdbc.queryForObject(
                                "select template_node_id from public.nocode_task_instance where"
                                        + " id=?",
                                String.class,
                                next.id()))
                .isNull();
        assertThat(created.nodes())
                .allMatch(
                        n ->
                                n.status().equals("PENDING")
                                        && n.actualStart() == null
                                        && n.templateVersion() == 1);
        assertThat(tasks.create(command, OWNER).task().id()).isEqualTo(created.task().id());
        assertThat(tasks.version(template.id(), 1, OWNER).nodes())
                .extracting(NodeInput::title)
                .containsExactly(marker + "原子任务A", marker + "原子任务B");
    }

    @Test
    void currentPublishedVersionDoesNotReplaceExplicitOlderSource() {
        TemplateVersion original = template(null);
        Template current =
                tasks.templates(OWNER).stream()
                        .filter(t -> t.id().equals(original.id()))
                        .findFirst()
                        .orElseThrow();
        Template changed =
                tasks.saveTemplate(
                        new SaveTemplate(
                                current.id(),
                                current.revision(),
                                marker + "模板v2",
                                null,
                                List.of(node("v2", "版本二", OWNER, null, List.of(), null, null)),
                                Kind.ORDINARY,
                                original.task()),
                        OWNER);
        tasks.publish(new PublishTemplate(changed.id(), changed.revision()), OWNER);
        Detail created = create(command(original, List.of(original.nodes().getFirst())));
        assertThat(created.task().templateVersion()).isEqualTo(1);
        assertThat(created.nodes())
                .extracting(Row::title)
                .contains(marker + "原子任务A")
                .doesNotContain(marker + "版本二");
    }

    @Test
    void bindingAndNewResourceCannotBypassPublishedTemplate() {
        TemplateVersion template = template(null);
        Binding injected = new Binding("other-app", "other-form", null);
        NodeInput altered = node("a", "篡改资源", OWNER, null, List.of(), injected, null);
        assertThatThrownBy(() -> tasks.create(command(template, List.of(altered)), OWNER))
                .hasMessageContaining("业务资源和数据权限已经固定");
        NodeInput added = node("extra", "新增越权资源", OWNER, null, List.of(), injected, null);
        assertThatThrownBy(() -> tasks.create(command(template, List.of(added)), OWNER))
                .hasMessageContaining("新增子任务只能继承");
        NodeInput changedRoot = node("root", "越权根任务", OWNER, null, List.of(), injected, null);
        Create alteredRoot =
                new Create(changedRoot, null, template.id(), 1, null, null, null, key(), List.of());
        assertThatThrownBy(() -> tasks.create(alteredRoot, OWNER))
                .hasMessageContaining("业务资源和数据权限已经固定");
    }

    @Test
    void unifiedAuthorizationCannotBeChangedOrCopiedOntoChildren() {
        DataPolicy policy = new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP);
        TemplateVersion template = template(policy);
        NodeInput changedRoot =
                node(
                        "root",
                        "越权总任务",
                        OWNER,
                        null,
                        List.of(),
                        null,
                        new DataPolicy(1, DataAccessMode.ALL, DataAccessMode.GROUP));
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                changedRoot,
                                                null,
                                                template.id(),
                                                1,
                                                null,
                                                null,
                                                null,
                                                key(),
                                                List.of()),
                                        OWNER))
                .hasMessageContaining("数据权限和资源已经固定");
        NodeInput changedChild = node("a", "越权子任务", OWNER, null, List.of(), null, policy);
        assertThatThrownBy(() -> tasks.create(command(template, List.of(changedChild)), OWNER))
                .hasMessageContaining("子任务统一继承");
        Detail created =
                create(
                        command(
                                template,
                                List.of(node("new", "继承数据", OWNER, null, List.of(), null, null))));
        assertThat(created.task().dataPolicy()).isEqualTo(policy);
    }

    @Test
    void missingPredecessorAndDependencyCycleRejectWithoutPartialCreation() {
        TemplateVersion template = template(null);
        Create missing =
                command(
                        template,
                        List.of(node("b", "引用已删除节点", OWNER, null, List.of("a"), null, null)));
        assertThatThrownBy(() -> tasks.create(missing, OWNER)).hasMessageContaining("前置任务");
        Create cycle =
                command(
                        template,
                        List.of(
                                node("a", "循环A", OWNER, null, List.of("b"), null, null),
                                node("b", "循环B", OWNER, null, List.of("a"), null, null)));
        assertThatThrownBy(() -> tasks.create(cycle, OWNER)).hasMessageContaining("环");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where request_key"
                                        + " in (?, ?)",
                                Integer.class,
                                missing.requestKey(),
                                cycle.requestKey()))
                .isZero();
    }

    @Test
    void draftRetainsTemplateGraphAndPublishUsesSameValidationAtomically() {
        TemplateVersion template = template(null);
        Create invalid =
                command(
                        template,
                        List.of(
                                node(
                                        "a",
                                        "非法草稿资源",
                                        OWNER,
                                        null,
                                        List.of(),
                                        new Binding("other", "other", null),
                                        null)));
        Draft saved = drafts.save(new DraftSave(null, null, invalid), OWNER);
        draftIds.add(saved.id());
        assertThat(drafts.get(saved.id(), OWNER).content()).isEqualTo(invalid);
        assertThatThrownBy(
                        () ->
                                drafts.publish(
                                        new DraftPublish(saved.id(), saved.revision(), key()),
                                        OWNER))
                .hasMessageContaining("业务资源和数据权限已经固定");
        assertThat(drafts.get(saved.id(), OWNER).publishedTaskId()).isNull();
        assertThat(drafts.get(saved.id(), OWNER).revision()).isEqualTo(saved.revision());
        Create valid =
                command(template, List.of(node("new", "草稿新增", OWNER, null, List.of(), null, null)));
        Draft edited = drafts.save(new DraftSave(saved.id(), saved.revision(), valid), OWNER);
        String publishKey = key();
        Detail created =
                drafts.publish(new DraftPublish(edited.id(), edited.revision(), publishKey), OWNER);
        roots.add(created.task().id());
        assertThat(created.nodes())
                .extracting(Row::title)
                .contains(marker + "草稿新增")
                .doesNotContain(marker + "原子任务A");
        assertThat(created.task().templateId()).isEqualTo(template.id());
        assertThat(
                        drafts.publish(
                                        new DraftPublish(
                                                edited.id(), edited.revision(), publishKey),
                                        OWNER)
                                .task()
                                .id())
                .isEqualTo(created.task().id());
    }

    private TemplateVersion template(DataPolicy policy) {
        NodeInput root = node("root", "总任务", OWNER, null, List.of(), null, policy);
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker + "模板",
                                null,
                                List.of(
                                        node("a", "原子任务A", OWNER, null, List.of(), null, null),
                                        node("b", "原子任务B", OWNER, null, List.of(), null, null)),
                                Kind.PROCESS,
                                root),
                        OWNER);
        templates.add(draft.id());
        return tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
    }

    private Create command(TemplateVersion template, List<NodeInput> nodes) {
        return new Create(
                template.task(),
                null,
                template.id(),
                template.version(),
                null,
                null,
                null,
                key(),
                nodes);
    }

    private Detail create(Create command) {
        Detail result = tasks.create(command, OWNER);
        roots.add(result.task().id());
        return result;
    }

    private NodeInput node(
            String id,
            String title,
            long owner,
            Schedule schedule,
            List<String> predecessors,
            Binding binding,
            DataPolicy policy) {
        return new NodeInput(
                id,
                null,
                marker + title,
                null,
                owner,
                Urgency.NORMAL,
                Priority.MEDIUM,
                schedule == null ? new Schedule(TimeMode.UNSCHEDULED, null, 0, 0) : schedule,
                predecessors,
                binding,
                null,
                null,
                AssignmentMode.ASSIGNED,
                null,
                policy);
    }

    private String key() {
        return marker + UUID.randomUUID();
    }
}
