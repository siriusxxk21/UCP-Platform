package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;

/** 开发库验证模板稳定来源、真实根分页与参与者权限；只清理登记的随机夹具。 */
class TaskTemplateInstancesIntegrationTest {
    private static final long OWNER = 10001L, FIRST = 21001L, SECOND = 21002L, ADMIN = 31001L;
    private TaskCenterService tasks;
    private final String marker = "template_instances_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>(), templates = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 此查询兼容当前 V061 开发库；不应用其他工作的待执行迁移，仍校验已应用脚本。
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
    }

    @AfterAll
    static void end() {
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
                            user.setNickname("实例测试" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(ADMIN, "nocode:task:manage-all"))
                .thenReturn(true);
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
            jdbc.update(
                    "delete from public.nocode_task_comment where task_id in (select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_plan where task_id in (select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        for (String id : templates) {
            jdbc.update("delete from public.nocode_task_template_version where template_id=?", id);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    id,
                    marker + "%");
        }
    }

    @Test
    void versionsRemainFrozenAndSameNameOtherTemplateDoesNotMatch() {
        Template template = publish(null, List.of(node("a", FIRST), node("b", SECOND)));
        Detail one = create(template, 1, "first", OWNER);
        template = publish(template, List.of(node("changed", FIRST)));
        Detail two = create(template, 2, "second", OWNER);
        Template another = publish(null, List.of(node("a", FIRST)));
        create(another, 1, "same-name-other-template", OWNER);
        PageResult<TemplateInstance> all = instances(template.id(), null, null, null, 1, 10, OWNER);
        assertThat(all.getTotal()).isEqualTo(2);
        assertThat(all.getList())
                .extracting(TemplateInstance::rootId)
                .containsExactly(two.task().id(), one.task().id());
        assertThat(all.getList())
                .extracting(TemplateInstance::templateVersion)
                .containsExactly(2, 1);
        assertThat(instances(template.id(), 1, null, null, 1, 10, OWNER).getList())
                .extracting(TemplateInstance::rootId)
                .containsExactly(one.task().id());
        assertThat(instances(template.id(), 99, null, null, 1, 10, OWNER).getTotal()).isZero();
        assertThat(tasks.detail(one.task().id(), OWNER).nodes())
                .extracting(Row::title)
                .contains(marker + "a")
                .doesNotContain(marker + "changed");
    }

    @Test
    void sqlPagesRealRootsOnceAndMatchesVisibleChildSearch() {
        Template template =
                publish(null, List.of(node("needle", FIRST), node("b", SECOND), node("c", FIRST)));
        Detail first = create(template, 1, "one", OWNER);
        Detail second = create(template, 1, "two", OWNER);
        PageResult<TemplateInstance> page =
                instances(template.id(), null, "needle", null, 1, 1, OWNER);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().getFirst().nodes()).hasSize(3);
        assertThat(instances(template.id(), null, "needle", null, 2, 1, OWNER).getList())
                .extracting(TemplateInstance::rootId)
                .containsExactly(first.task().id());
        assertThat(page.getList().getFirst().rootId()).isEqualTo(second.task().id());
    }

    @Test
    void templateOwnerDoesNotGainInstancesCreatedByOtherPeople() {
        Template template = publish(null, List.of(node("worker-only", FIRST)));
        Detail created = create(template, 1, "private-root", FIRST);
        assertThat(instances(template.id(), null, null, null, 1, 10, OWNER).getTotal()).isZero();
        assertThat(instances(template.id(), null, null, null, 1, 10, SECOND).getTotal()).isZero();
        assertThat(instances(template.id(), null, null, null, 1, 10, FIRST).getList())
                .extracting(TemplateInstance::rootId)
                .containsExactly(created.task().id());
        assertThat(instances(template.id(), null, null, null, 1, 10, ADMIN).getList()).hasSize(1);
    }

    @Test
    void childParticipantGetsOnlyOwnNodesAndSafeRootContext() {
        Template template = publish(null, List.of(node("mine", FIRST), node("secret", SECOND)));
        Detail created = create(template, 1, "root", OWNER);
        TemplateInstance group =
                instances(template.id(), null, null, null, 1, 10, FIRST).getList().getFirst();
        assertThat(group.root()).isNull();
        assertThat(group.rootId()).isEqualTo(created.task().id());
        assertThat(group.title()).isEqualTo(marker + "root");
        assertThat(group.nodes()).extracting(Row::title).containsExactly(marker + "mine");
        assertThat(instances(template.id(), null, "secret", null, 1, 10, FIRST).getTotal())
                .isZero();
        assertThatThrownBy(() -> tasks.detail(created.task().id(), FIRST))
                .hasMessageContaining("没有查看");
    }

    @Test
    void totalStatusFilterAndEmptyDraftAreReadOnly() {
        Template template = publish(null, List.of(node("a", FIRST)));
        Detail created = create(template, 1, "running", OWNER);
        tasks.transition(
                new Transition(
                        created.task().id(), created.task().revision(), Action.START, null, key()),
                OWNER);
        assertThat(instances(template.id(), null, null, State.RUNNING, 1, 10, OWNER).getTotal())
                .isEqualTo(1);
        assertThat(instances(template.id(), null, null, State.PENDING, 1, 10, OWNER).getTotal())
                .isZero();
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null, null, marker + "draft", null, List.of(node("x", FIRST))),
                        OWNER);
        templates.add(draft.id());
        assertThat(instances(draft.id(), null, null, null, 1, 10, OWNER).getTotal()).isZero();
        assertThatThrownBy(() -> instances(draft.id(), null, null, null, 1, 10, FIRST))
                .hasMessageContaining("没有查看");
        assertThatThrownBy(() -> instances(template.id(), 0, null, null, 1, 10, OWNER))
                .hasMessageContaining("正整数");
    }

    @Test
    void newlySplitChildrenBelongToOriginalInstanceEvenWithoutNodeTemplateMetadata() {
        Template template = publish(null, List.of(node("a", FIRST)));
        Detail created = create(template, 1, "root", OWNER);
        tasks.create(
                new Create(
                        node("added", OWNER),
                        created.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        key()),
                OWNER);
        PageResult<TemplateInstance> result =
                instances(template.id(), null, "added", null, 1, 10, OWNER);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList().getFirst().nodes())
                .extracting(Row::title)
                .containsExactlyInAnyOrder(marker + "a", marker + "added");
    }

    private Template publish(Template previous, List<NodeInput> nodes) {
        Template current =
                previous == null
                        ? null
                        : tasks.templates(OWNER).stream()
                                .filter(t -> t.id().equals(previous.id()))
                                .findFirst()
                                .orElseThrow();
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(
                                current == null ? null : current.id(),
                                current == null ? null : current.revision(),
                                marker + "same-template-name",
                                null,
                                nodes),
                        OWNER);
        templates.add(draft.id());
        tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        return draft;
    }

    private Detail create(Template template, int version, String title, long actor) {
        Detail result =
                tasks.create(
                        new Create(
                                node(title, actor),
                                null,
                                template.id(),
                                version,
                                null,
                                null,
                                null,
                                key()),
                        actor);
        roots.add(result.task().id());
        return result;
    }

    private NodeInput node(String title, Long actor) {
        return new NodeInput(
                title,
                null,
                marker + title,
                "私有说明",
                actor,
                null,
                null,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of());
    }

    private PageResult<TemplateInstance> instances(
            String template,
            Integer version,
            String search,
            State status,
            int page,
            int size,
            long actor) {
        return tasks.templateInstances(
                new TemplateInstances(template, version, search, status, page, size), actor);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
