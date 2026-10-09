package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskClaims;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;

/** 实例编排顺序在不同查询入口保持一致；夹具带随机标识并逐根清理。 */
class TaskInstanceOrderingIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L;
    private TaskCenterService tasks;
    private final String marker = "task_order_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>(), templates = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 显示序号复用现有 config_json，无需迁移；仍校验已应用脚本，不执行其他功能迁移。
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
                            user.setNickname("排序验证" + user.getId());
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
        for (String template : templates) {
            jdbc.update(
                    "delete from public.nocode_task_template_version where template_id=?",
                    template);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    template,
                    marker + "%");
        }
    }

    @Test
    void directCreationRetainsParallelOrderWithoutUsingNamesOrUuid() {
        Detail created =
                create(
                        null,
                        List.of(
                                node("z", "Z", List.of()),
                                node("a", "A", List.of()),
                                node("m", "M", List.of())));
        assertThat(titles(tasks.detail(created.task().id(), OWNER).nodes()))
                .containsExactly("总任务", "Z", "A", "M");
        assertThat(
                        jdbc.queryForList(
                                "select (config_json::jsonb->>'displayOrder')::integer as position"
                                    + " from public.nocode_task_instance where root_id=? order by"
                                    + " position",
                                Integer.class,
                                created.task().id()))
                .containsExactly(0, 1, 2, 3);
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(created.task().id(), true), WORKER);
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(
                        created.task().id(), preview.instanceRevision(), key(), true),
                WORKER);
        assertThat(titles(tasks.detail(created.task().id(), WORKER).nodes()))
                .containsExactly("总任务", "Z", "A", "M");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and config_json::jsonb->>'displayOrder' is not null",
                                Integer.class,
                                created.task().id()))
                .isEqualTo(4);
    }

    @Test
    void dependencyOrderIsConsistentInPoolDetailAndPersonalChildrenAfterClaim() {
        Detail created =
                create(
                        null,
                        List.of(
                                node("b", "B", List.of("a")),
                                node("a", "A", List.of()),
                                node("c", "C", List.of("b"))));
        String root = created.task().id();
        assertThat(titles(created.nodes())).containsExactly("总任务", "A", "B", "C");
        assertThat(tasks.claimableChildren(new TaskClaims.Root(root), WORKER))
                .extracting(TaskClaims.Item::title)
                .containsExactly(marker + "A", marker + "B", marker + "C");
        TaskClaims.Preview preview = tasks.claimPreview(new TaskClaims.Root(root, true), WORKER);
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(root, preview.instanceRevision(), key(), true), WORKER);
        Query query = mine();
        assertThat(tasks.personalTreeChildren(new PersonalTreeChildren(query, root), WORKER))
                .extracting(n -> n.task().title())
                .containsExactly(marker + "A", marker + "B", marker + "C");
        assertThat(titles(tasks.detail(root, OWNER).nodes())).containsExactly("总任务", "A", "B", "C");
    }

    @Test
    void legacyTemplateNodesRecoverFixedPublishedOrderNotUuidOrder() {
        TemplateVersion version =
                template(List.of(node("z", "Z", List.of()), node("a", "A", List.of())));
        Detail created = create(version, null);
        jdbc.update(
                "update public.nocode_task_instance set config_json=(config_json::jsonb -"
                        + " 'displayOrder')::text where root_id=?",
                created.task().id());
        session.clearCache();
        assertThat(titles(tasks.detail(created.task().id(), OWNER).nodes()))
                .containsExactly("总任务", "Z", "A");
        assertThat(
                        tasks.templateInstances(
                                        new TemplateInstances(version.id(), 1, marker, null, 1, 10),
                                        OWNER)
                                .getList()
                                .getFirst()
                                .nodes())
                .extracting(Row::title)
                .containsExactly(marker + "Z", marker + "A");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and config_json::jsonb->>'displayOrder' is not null",
                                Integer.class,
                                created.task().id()))
                .isZero();
    }

    @Test
    void currentInstanceParallelOverrideWinsOverPublishedTemplateOrder() {
        TemplateVersion version =
                template(List.of(node("a", "A", List.of()), node("z", "Z", List.of())));
        Detail created = create(version, List.of(version.nodes().get(1), version.nodes().get(0)));
        assertThat(titles(tasks.detail(created.task().id(), OWNER).nodes()))
                .containsExactly("总任务", "Z", "A");
        assertThat(version.nodes())
                .extracting(NodeInput::title)
                .containsExactly(marker + "A", marker + "Z");
    }

    @Test
    void arrangementReorderPersistsEvenWhenNoTaskConfigurationChanges() {
        Detail created =
                create(null, List.of(node("z", "Z", List.of()), node("a", "A", List.of())));
        List<NodeInput> input = inputs(created);
        List<NodeInput> reordered = List.of(input.get(0), input.get(2), input.get(1));
        tasks.adjust(
                new Adjust(
                        created.task().id(),
                        created.task().instanceRevision(),
                        reordered,
                        "调整并行展示顺序"),
                OWNER);
        assertThat(titles(tasks.detail(created.task().id(), OWNER).nodes()))
                .containsExactly("总任务", "A", "Z");
    }

    @Test
    void splittingAppendsToExistingSiblingsAndPreservesRootIdentity() {
        Detail created =
                create(null, List.of(node("z", "Z", List.of()), node("a", "A", List.of())));
        tasks.create(
                new Create(
                        node("n", "新增", List.of()),
                        created.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        key(),
                        null),
                OWNER);
        Detail current = tasks.detail(created.task().id(), OWNER);
        assertThat(titles(current.nodes())).containsExactly("总任务", "Z", "A", "新增");
        assertThat(current.nodes()).allMatch(n -> n.rootId().equals(created.task().id()));
    }

    @Test
    void legacyDirectFlowUsesDependenciesWithoutRewritingExistingRecords() {
        Detail created =
                create(
                        null,
                        List.of(
                                node("b", "B", List.of("a")),
                                node("a", "A", List.of()),
                                node("c", "C", List.of("b"))));
        jdbc.update(
                "update public.nocode_task_instance set config_json=(config_json::jsonb -"
                        + " 'displayOrder')::text where root_id=?",
                created.task().id());
        session.clearCache();
        assertThat(titles(tasks.detail(created.task().id(), OWNER).nodes()))
                .containsExactly("总任务", "A", "B", "C");
    }

    private Detail create(TemplateVersion version, List<NodeInput> children) {
        Detail result =
                tasks.create(
                        new Create(
                                version == null ? node("root", "总任务", List.of()) : version.task(),
                                null,
                                version == null ? null : version.id(),
                                version == null ? null : version.version(),
                                null,
                                null,
                                null,
                                key(),
                                children),
                        OWNER);
        roots.add(result.task().id());
        return result;
    }

    private TemplateVersion template(List<NodeInput> children) {
        Template saved =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker + "模板",
                                null,
                                children,
                                Kind.ORDINARY,
                                node("root", "总任务", List.of())),
                        OWNER);
        templates.add(saved.id());
        return tasks.publish(new PublishTemplate(saved.id(), saved.revision()), OWNER);
    }

    private NodeInput node(String id, String title, List<String> predecessors) {
        return new NodeInput(
                id,
                null,
                marker + title,
                null,
                null,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                predecessors,
                null,
                null,
                null,
                AssignmentMode.OPEN,
                null,
                null);
    }

    private List<NodeInput> inputs(Detail detail) {
        return jdbc
                .queryForList(
                        "select config_json from public.nocode_task_instance where root_id=? order"
                                + " by (config_json::jsonb->>'displayOrder')::integer",
                        String.class,
                        detail.task().id())
                .stream()
                .map(
                        value -> {
                            try {
                                return mapper.readValue(value, NodeInput.class);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        })
                .toList();
    }

    private Query mine() {
        return new Query(
                "MINE", "TODO", null, null, null, null, null, null, null, null, null, null, 1, 10);
    }

    private List<String> titles(List<Row> rows) {
        return rows.stream().map(n -> n.title().substring(marker.length())).toList();
    }

    private String key() {
        return marker + UUID.randomUUID();
    }
}
