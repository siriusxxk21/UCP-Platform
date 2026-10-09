package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.util.*;

/** 删除实例节点保留已发生的业务痕迹；真实开发库夹具以随机根标识隔离并清理。 */
class TaskInstanceRemovalIntegrationTest {
    private static final long OWNER = 10001L;
    private static final String BLOCKED = "待删除任务已有业务记录、关联或提交材料，请改为取消任务以保留历史";
    private final String marker = "task_removal_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
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
            for (String table :
                    List.of(
                            "nocode_task_entry_record",
                            "nocode_task_entry_binding",
                            "nocode_task_record_link")) {
                jdbc.update(
                        "delete from public."
                                + table
                                + " where task_id in (select id from public.nocode_task_instance"
                                + " where root_id=?)",
                        root);
            }
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void rejectsRemovingPendingSubtreeWithBusinessEvidenceInDescendant(Evidence evidence) {
        Detail detail = create();
        String root = detail.task().id();
        String child =
                detail.nodes().stream()
                        .filter(n -> n.title().equals(marker + "下级"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        Adjust removal = removeBranch(detail);
        seedEvidence(evidence, root, child);
        session.clearCache();
        int eventsBefore =
                jdbc.queryForObject(
                        "select count(*) from public.nocode_task_event where root_id=?",
                        Integer.class,
                        root);

        assertThatThrownBy(() -> tasks.preview(removal, OWNER)).hasMessageContaining(BLOCKED);
        assertThatThrownBy(() -> tasks.adjust(removal, OWNER)).hasMessageContaining(BLOCKED);

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and deleted=0",
                                Integer.class,
                                root))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "select lock_version from public.nocode_task_instance where id=?",
                                Integer.class,
                                root))
                .isEqualTo(detail.task().instanceRevision());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_event where root_id=?",
                                Integer.class,
                                root))
                .isEqualTo(eventsBefore);
    }

    @Test
    void cleanPendingSubtreeRemainsRemovableAndAdjustmentIsAudited() {
        Detail detail = create();
        Adjust removal = removeBranch(detail);
        String child =
                detail.nodes().stream()
                        .filter(n -> n.title().equals(marker + "下级"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        // 人员安排和个人计划也复用 material_json 保存差异，不应误判为业务提交材料。
        for (EventType type : List.of(EventType.ASSIGNED, EventType.PLANNED)) {
            jdbc.update(
                    "insert into"
                        + " public.nocode_task_event(id,task_id,root_id,event_type,material_json,creator,updater)"
                        + " values(?,?,?,?,'{}',?,?)",
                    UUID.randomUUID().toString(),
                    child,
                    detail.task().id(),
                    type.name(),
                    Long.toString(OWNER),
                    Long.toString(OWNER));
        }
        assertThat(tasks.preview(removal, OWNER).removedIds()).hasSize(2);
        Detail saved = tasks.adjust(removal, OWNER);
        assertThat(saved.nodes())
                .extracting(Row::title)
                .containsExactly(marker + "总任务", marker + "保留");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and deleted=1",
                                Integer.class,
                                detail.task().id()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_event where root_id=? and"
                                        + " event_type='ADJUSTED'",
                                Integer.class,
                                detail.task().id()))
                .isEqualTo(1);
    }

    private enum Evidence {
        BUSINESS_RECORD,
        BUSINESS_REQUEST,
        RECORD_LINK,
        ENTRY_RECORD,
        ENTRY_SUBMISSION,
        CONTRIBUTION,
        MATERIAL
    }

    private void seedEvidence(Evidence evidence, String root, String task) {
        String id = UUID.randomUUID().toString();
        switch (evidence) {
            case BUSINESS_RECORD, BUSINESS_REQUEST ->
                    jdbc.update(
                            "update public.nocode_task_instance set business_json=? where id=?",
                            evidence == Evidence.BUSINESS_RECORD
                                    ? "{\"recordId\":\"fixture\"}"
                                    : "{\"requestId\":\"fixture\"}",
                            task);
            case RECORD_LINK ->
                    jdbc.update(
                            "insert into"
                                + " public.nocode_task_record_link(id,task_id,application_id,object_id,record_id,creator,updater)"
                                + " values(?,?,?,?,'fixture',?,?)",
                            id,
                            task,
                            id,
                            id,
                            Long.toString(OWNER),
                            Long.toString(OWNER));
            case ENTRY_RECORD, ENTRY_SUBMISSION ->
                    jdbc.update(
                            "insert into"
                                + " public.nocode_task_entry_binding(id,task_id,entry_key,dataset_id,config_json,business_json,submitted_json,creator,updater)"
                                + " values(?,?,'fixture',?,'{}',?,?,?,?)",
                            id,
                            task,
                            id,
                            evidence == Evidence.ENTRY_RECORD ? "{\"recordId\":\"fixture\"}" : "{}",
                            evidence == Evidence.ENTRY_SUBMISSION ? "{}" : null,
                            Long.toString(OWNER),
                            Long.toString(OWNER));
            case CONTRIBUTION ->
                    jdbc.update(
                            "insert into"
                                + " public.nocode_task_entry_record(id,task_id,entry_key,dataset_id,business_json,operation,request_key,request_hash,creator,updater)"
                                + " values(?,?,'fixture',?,'{}','LINKED',?,'fixture',?,?)",
                            id,
                            task,
                            id,
                            id,
                            Long.toString(OWNER),
                            Long.toString(OWNER));
            case MATERIAL ->
                    jdbc.update(
                            "insert into"
                                + " public.nocode_task_event(id,task_id,root_id,event_type,material_json,creator,updater)"
                                + " values(?,?,?,'SUBMITTED_FOR_ACCEPTANCE','{}',?,?)",
                            id,
                            task,
                            root,
                            Long.toString(OWNER),
                            Long.toString(OWNER));
        }
    }

    private Detail create() {
        Detail detail =
                tasks.create(
                        new Create(
                                node("root", null, "总任务"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                marker + UUID.randomUUID(),
                                List.of(
                                        node("branch", null, "分支"),
                                        node("child", "branch", "下级"),
                                        node("keep", null, "保留"))),
                        OWNER);
        roots.add(detail.task().id());
        return detail;
    }

    private NodeInput node(String id, String parentId, String title) {
        return new NodeInput(
                id,
                parentId,
                marker + title,
                null,
                null,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.OPEN,
                null,
                null);
    }

    private Adjust removeBranch(Detail detail) {
        List<NodeInput> nodes =
                jdbc
                        .queryForList(
                                "select config_json from public.nocode_task_instance where"
                                        + " root_id=? and title in (?,?) order by"
                                        + " (config_json::jsonb->>'displayOrder')::integer",
                                String.class,
                                detail.task().id(),
                                marker + "总任务",
                                marker + "保留")
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
        return new Adjust(detail.task().id(), detail.task().instanceRevision(), nodes, "验证删除边界");
    }
}
