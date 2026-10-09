package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;

/** 开发库真实验证分钟工时随模板版本冻结，且人员安排和实例调整不丢失该基线。 */
class TaskEffectiveWorkMinutesIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L;
    private final String marker = "effective_minutes_" + UUID.randomUUID() + "_";
    private final Set<String> templates = new LinkedHashSet<>(), roots = new LinkedHashSet<>();
    private TaskCenterService tasks;

    @BeforeAll
    static void open() throws Exception {
        connect();
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
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("工时验证" + user.getId());
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
        for (String id : templates) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_template where id=?"
                                            + " and name like ?",
                                    Integer.class,
                                    id,
                                    marker + "%"))
                    .isEqualTo(1);
            jdbc.update("delete from public.nocode_task_template_version where template_id=?", id);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    id,
                    marker + "%");
        }
    }

    @Test
    void draftVersionsPrimarySwitchAndExistingInstancesKeepIndependentMinutes() {
        Template draft = save(null, 95);
        assertThat(draft.task().effectiveWorkMinutes()).isEqualTo(95);
        tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        Detail first = create(draft.id(), 1, 999, false);
        assertMinutes(first, 95);
        Template newer = save(head(draft.id()), 140);
        assertThat(tasks.version(draft.id(), 1, OWNER).task().effectiveWorkMinutes()).isEqualTo(95);
        tasks.publish(new PublishTemplate(newer.id(), newer.revision()), OWNER);
        Template current = head(draft.id());
        assertMinutes(create(draft.id(), 2, 888, true), 140);
        assertThat(tasks.detail(first.task().id(), OWNER)).isEqualTo(first);
        tasks.setPrimaryVersion(
                new SetPrimaryTemplateVersion(current.id(), 1, current.revision()), OWNER);
        assertMinutes(create(draft.id(), null, null, false), 95);
        assertMinutes(create(draft.id(), 2, null, false), 140);
        assertThat(tasks.version(draft.id(), 2, OWNER).task().effectiveWorkMinutes())
                .isEqualTo(140);
    }

    @Test
    void reassignmentLegacyAdjustmentAndEmployeeSplitPreserveRootSnapshot() throws Exception {
        Template draft = save(null, 125);
        tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        Detail created = create(draft.id(), 1, null, false);
        tasks.assign(
                new Assign(
                        created.task().id(),
                        created.task().revision(),
                        AssignmentMode.ASSIGNED,
                        WORKER,
                        List.of(),
                        key(),
                        "工时测试分工"),
                OWNER);
        Detail assigned = tasks.detail(created.task().id(), OWNER);
        assertMinutes(assigned, 125);
        List<NodeInput> input = configs(assigned.task().rootId());
        List<NodeInput> legacy =
                input.stream().map(n -> n.parentId() == null ? withMinutes(n, null) : n).toList();
        Detail adjusted =
                tasks.adjust(
                        new Adjust(
                                assigned.task().rootId(),
                                assigned.task().instanceRevision(),
                                legacy,
                                "兼容旧客户端保存"),
                        OWNER);
        assertMinutes(adjusted, 125);
        List<NodeInput> tampered =
                configs(adjusted.task().rootId()).stream()
                        .map(n -> n.parentId() == null ? withMinutes(n, 60) : n)
                        .toList();
        assertThatThrownBy(
                        () ->
                                tasks.adjust(
                                        new Adjust(
                                                adjusted.task().rootId(),
                                                adjusted.task().instanceRevision(),
                                                tampered,
                                                "不可覆盖工时基线"),
                                        OWNER))
                .hasMessageContaining("有效工作时长已按发起时");
        tasks.split(
                new Create(
                        new NodeInput(
                                "split",
                                null,
                                marker + "employee-child",
                                null,
                                WORKER,
                                Urgency.NORMAL,
                                Priority.MEDIUM,
                                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                                List.of(),
                                null,
                                null,
                                null,
                                AssignmentMode.ASSIGNED,
                                List.of()),
                        created.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        key()),
                WORKER);
        Detail split = tasks.detail(created.task().id(), OWNER);
        assertMinutes(split, 125);
        assertThat(split.nodes())
                .filteredOn(n -> !n.id().equals(split.task().rootId()))
                .allSatisfy(n -> assertThat(n.effectiveWorkMinutes()).isNull());
    }

    @Test
    void emptyLegacyAndZeroDurationDoNotInventWorkEstimates() {
        Template draft = save(null, 0);
        assertThat(draft.task().effectiveWorkMinutes()).isNull();
        tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        assertMinutes(create(draft.id(), 1, 90, false), null);
        Template legacy =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker + "legacy",
                                null,
                                List.of(node("legacy-child", null)),
                                Kind.ORDINARY),
                        OWNER);
        templates.add(legacy.id());
        tasks.publish(new PublishTemplate(legacy.id(), legacy.revision()), OWNER);
        assertMinutes(create(legacy.id(), 1, 90, false), null);
    }

    private List<NodeInput> configs(String root) throws Exception {
        ObjectMapper json = servicesContext.getBean(ObjectMapper.class);
        List<NodeInput> result = new ArrayList<>();
        for (String config :
                jdbc.queryForList(
                        "select config_json::text from public.nocode_task_instance where root_id=?"
                                + " order by parent_id nulls first, id",
                        String.class,
                        root)) {
            result.add(json.readValue(config, NodeInput.class));
        }
        return result;
    }

    private void assertMinutes(Detail detail, Integer expected) {
        assertThat(detail.task().effectiveWorkMinutes()).isEqualTo(expected);
        assertThat(detail.nodes())
                .filteredOn(n -> !n.id().equals(detail.task().rootId()))
                .allSatisfy(n -> assertThat(n.effectiveWorkMinutes()).isNull());
    }

    private Template save(Template previous, Integer minutes) {
        Template saved =
                tasks.saveTemplate(
                        new SaveTemplate(
                                previous == null ? null : previous.id(),
                                previous == null ? null : previous.revision(),
                                marker + "template",
                                null,
                                List.of(node("child", null)),
                                Kind.ORDINARY,
                                node("root", minutes)),
                        OWNER);
        templates.add(saved.id());
        return saved;
    }

    private Template head(String id) {
        return tasks.templates(OWNER).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private Detail create(
            String template, Integer version, Integer requested, boolean explicitNodes) {
        Detail created =
                tasks.create(
                        new Create(
                                node("instance", requested),
                                null,
                                template,
                                version,
                                null,
                                null,
                                null,
                                key(),
                                explicitNodes ? List.of(node("child", null)) : null),
                        OWNER);
        roots.add(created.task().id());
        return created;
    }

    private NodeInput node(String id, Integer minutes) {
        return new NodeInput(
                id,
                null,
                marker + id,
                null,
                OWNER,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of(),
                null,
                null,
                minutes);
    }

    private NodeInput withMinutes(NodeInput n, Integer minutes) {
        return new NodeInput(
                n.id(),
                n.parentId(),
                n.title(),
                n.description(),
                n.assigneeId(),
                n.urgency(),
                n.priority(),
                n.schedule(),
                n.predecessorIds(),
                n.binding(),
                n.sharing(),
                n.entries(),
                n.assignmentMode(),
                n.candidateUserIds(),
                n.dataPolicy(),
                n.acceptorId(),
                minutes);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
