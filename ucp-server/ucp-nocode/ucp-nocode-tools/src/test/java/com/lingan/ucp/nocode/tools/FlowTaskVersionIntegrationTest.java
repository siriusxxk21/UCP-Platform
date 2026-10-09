package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FlowTaskIntegrationTest.engine;
import static com.lingan.ucp.nocode.tools.FlowTaskIntegrationTest.flows;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.mapper;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.bpm.api.task.BpmBusinessTaskBinding;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.api.work.WorkDrafts;
import com.lingan.ucp.nocode.api.workflow.FlowTasks;

import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 已部署 CREATE 固定配置的版本与任务隔离回归，使用真实应用发布、Flowable、业务和材料事务。
 *
 * <p>部署夹具表示设计发布后的有效配置，不替代 SIMPLE/BPMN 三类来源继承解析与模型发布验收。 复用共享装配，不继承已有测试；仅清理本次 UUID 夹具。
 */
class FlowTaskVersionIntegrationTest {
    private static final long ACTOR = 10001;
    private FlowTaskIntegrationTest fixture;
    private WorkDraftIntegrationTest business;

    @BeforeAll
    static void open() throws Exception {
        FlowTaskIntegrationTest.open();
    }

    @AfterAll
    static void close() {
        FlowTaskIntegrationTest.shutdown();
    }

    @BeforeEach
    void setup() {
        fixture = new FlowTaskIntegrationTest();
        fixture.setup();
        business = fixture.business;
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void newApplicationAndDefinitionVersionsDoNotRebindInFlightOrUnopenedTasks() throws Exception {
        var key = processKey();
        var original = business.resource;
        deploy(key, original, true);
        var oldTask = start(key);
        var unopenedTask = start(key);
        var originalDraft = save(oldTask, "V1 在途填写");

        var replacement = publishNextFormVersion();
        assertThat(replacement.applicationVersion()).isEqualTo(original.applicationVersion() + 1);
        assertThat(replacement.applicationChecksum()).isNotEqualTo(original.applicationChecksum());
        deploy(key, replacement, true);
        var newTask = start(key);
        assertThat(definitionVersion(oldTask)).isEqualTo(1);
        assertThat(definitionVersion(newTask)).isEqualTo(2);
        assertThat(newTask.getProcessDefinitionId()).isNotEqualTo(oldTask.getProcessDefinitionId());

        var restored = flows.open(oldTask.getId(), ACTOR).work();
        assertThat(restored.draft().id()).isEqualTo(originalDraft.id());
        assertThat(restored.draft().resource()).isEqualTo(original);
        assertThat(restored.draft().values()).containsEntry(business.nameField, "V1 在途填写");
        assertThat(restored.formName()).isEqualTo("工作表单");
        assertThat(flows.context(oldTask.getId(), ACTOR).configuration().resource())
                .isEqualTo(original);

        // 新发布后才首次打开的旧任务，也必须按自身部署定义创建草稿。
        var lateOpened = flows.open(unopenedTask.getId(), ACTOR).work();
        assertThat(lateOpened.draft().resource()).isEqualTo(original);
        assertThat(lateOpened.formName()).isEqualTo("工作表单");
        assertThat(lateOpened.draft().values()).isEmpty();
        var newOpened = flows.open(newTask.getId(), ACTOR).work();
        assertThat(newOpened.draft().resource()).isEqualTo(replacement);
        assertThat(newOpened.formName()).isEqualTo("新版工作表单");
        assertThat(newOpened.draft().values()).isEmpty();
        assertThat(flows.context(newTask.getId(), ACTOR).configuration().resource())
                .isEqualTo(replacement);

        var oldMaterial = flows.submit(command(oldTask, originalDraft), ACTOR);
        assertThat(oldMaterial.resource()).isEqualTo(original);
        assertThat(oldMaterial.values()).containsEntry(business.nameField, "V1 在途填写");
        var oldNext = activeTask(oldTask.getProcessInstanceId());
        assertThat(oldNext.getTaskDefinitionKey()).isEqualTo("second");
        assertThat(oldNext.getProcessDefinitionId()).isEqualTo(oldTask.getProcessDefinitionId());
        assertThat(flows.open(oldNext.getId(), ACTOR).work().draft().resource())
                .isEqualTo(original);

        var newDraft = save(newTask, "V2 独立填写");
        var newMaterial = flows.submit(command(newTask, newDraft), ACTOR);
        assertThat(newMaterial.resource()).isEqualTo(replacement);
        assertThat(newMaterial.values()).containsEntry(business.nameField, "V2 独立填写");
        assertThat(newMaterial.recordId()).isNotEqualTo(oldMaterial.recordId());
        var newNext = activeTask(newTask.getProcessInstanceId());
        assertThat(flows.open(newNext.getId(), ACTOR).work().draft().resource())
                .isEqualTo(replacement);
        assertThat(flows.open(oldTask.getId(), ACTOR).work().submission().resource())
                .isEqualTo(original);
        assertThat(flows.open(newTask.getId(), ACTOR).work().submission().resource())
                .isEqualTo(replacement);
        assertThat(business.recordCount()).isEqualTo(2);
    }

    @Test
    void sequentialNodesWithSameFormRequireSeparateDraftsAndExplicitSubmissions() throws Exception {
        var key = processKey();
        deploy(key, business.resource, true);
        var first = start(key);
        var firstDraft = save(first, "第一节点成果");
        var firstMaterial = flows.submit(command(first, firstDraft), ACTOR);
        var second = activeTask(first.getProcessInstanceId());
        var secondWork = flows.open(second.getId(), ACTOR).work();

        assertThat(second.getTaskDefinitionKey()).isEqualTo("second");
        assertThat(secondWork.draft().id()).isNotEqualTo(firstDraft.id());
        assertThat(secondWork.draft().resource()).isEqualTo(firstDraft.resource());
        assertThat(secondWork.draft().values()).isEmpty();
        assertThat(secondWork.submission()).isNull();
        assertThat(secondWork.currentRecord()).isNull();
        assertThat(secondWork.writable()).isTrue();
        assertThat(business.recordCount()).isEqualTo(1);
        assertThatThrownBy(() -> flows.submit(command(second, secondWork.draft()), ACTOR))
                .hasMessageContaining("必填");
        assertThat(engine.getTaskService().createTaskQuery().taskId(second.getId()).count())
                .isEqualTo(1);
        assertThat(business.recordCount()).isEqualTo(1);

        var secondDraft = save(second, "第二节点成果");
        var secondMaterial = flows.submit(command(second, secondDraft), ACTOR);
        assertThat(secondMaterial.id()).isNotEqualTo(firstMaterial.id());
        assertThat(secondMaterial.draftId()).isEqualTo(secondDraft.id());
        assertThat(secondMaterial.recordId()).isNotEqualTo(firstMaterial.recordId());
        assertThat(secondMaterial.values()).containsEntry(business.nameField, "第二节点成果");
        assertThat(flows.getSubmission(first.getId(), ACTOR).values())
                .containsEntry(business.nameField, "第一节点成果");
        assertThat(flows.open(first.getId(), ACTOR).work().writable()).isFalse();
        assertThat(flows.open(second.getId(), ACTOR).work().writable()).isFalse();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isZero();
        assertThat(business.recordCount()).isEqualTo(2);
    }

    @Test
    void concurrentInstancesWithSameFormCannotExchangeDraftsOrBusinessResults() throws Exception {
        var key = processKey();
        deploy(key, business.resource, false);
        var first = start(key);
        var second = start(key);
        var firstDraft = save(first, "甲实例输入");
        var secondDraft = save(second, "乙实例输入");
        assertThat(firstDraft.id()).isNotEqualTo(secondDraft.id());
        assertThat(firstDraft.resource()).isEqualTo(secondDraft.resource());
        assertThatThrownBy(() -> flows.submit(command(second, firstDraft), ACTOR))
                .isInstanceOf(RuntimeException.class);
        assertThat(business.recordCount()).isZero();

        var firstMaterial = flows.submit(command(first, firstDraft), ACTOR);
        var secondRestored = flows.open(second.getId(), ACTOR).work();
        assertThat(secondRestored.draft().id()).isEqualTo(secondDraft.id());
        assertThat(secondRestored.draft().values()).containsEntry(business.nameField, "乙实例输入");
        assertThat(secondRestored.submission()).isNull();
        assertThat(secondRestored.writable()).isTrue();
        var secondMaterial = flows.submit(command(second, secondDraft), ACTOR);
        assertThat(secondMaterial.id()).isNotEqualTo(firstMaterial.id());
        assertThat(secondMaterial.recordId()).isNotEqualTo(firstMaterial.recordId());
        assertThat(flows.getSubmission(first.getId(), ACTOR).values())
                .containsEntry(business.nameField, "甲实例输入");
        assertThat(flows.getSubmission(second.getId(), ACTOR).values())
                .containsEntry(business.nameField, "乙实例输入");
        assertThat(business.recordCount()).isEqualTo(2);
    }

    private PublishedResourceRef publishNextFormVersion() {
        var current = business.applications.get(business.resource.applicationId());
        var form = current.draft().resources().getFirst();
        var replacement =
                new ApplicationCenter.Resource(
                        form.id(), form.kind(), form.code(), "新版工作表单", form.config());
        var app = current.application();
        var saved =
                business.applications.save(
                        new ApplicationCenter.Save(
                                app.id(),
                                app.revision(),
                                app.code(),
                                app.name(),
                                app.description(),
                                app.icon(),
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), List.of(replacement))),
                        ACTOR);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        app.id(), saved.application().revision(), "固定版本与在途任务隔离回归"),
                ACTOR);
        var published = business.applications.published(app.id());
        return new PublishedResourceRef(
                app.id(), published.versionNo(), published.checksum(), form.id(), form.kind());
    }

    private WorkDrafts.Draft save(Task task, String value) {
        var draft = flows.open(task.getId(), ACTOR).work().draft();
        return flows.saveDraft(
                new FlowTasks.Save(
                        task.getId(),
                        draft.id(),
                        draft.revision(),
                        Map.of(business.nameField, value)),
                ACTOR);
    }

    private FlowTasks.Submit command(Task task, WorkDrafts.Draft draft) {
        return new FlowTasks.Submit(
                task.getId(),
                draft.id(),
                draft.revision(),
                UUID.randomUUID().toString(),
                "独立任务材料提交");
    }

    private String processKey() {
        return "flow_version_" + UUID.randomUUID().toString().replace("-", "");
    }

    private int definitionVersion(Task task) {
        return engine.getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionId(task.getProcessDefinitionId())
                .singleResult()
                .getVersion();
    }

    private Task start(String key) {
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        return activeTask(instance.getId());
    }

    private Task activeTask(String processInstanceId) {
        var task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult();
        assertThat(task).isNotNull();
        fixture.taskIds.add(task.getId());
        return task;
    }

    private void deploy(String key, PublishedResourceRef resource, boolean twoNodes)
            throws Exception {
        var configuration =
                mapper.writeValueAsString(
                        new FlowTasks.Configuration(
                                resource, business.object.objectId(), "CREATE"));
        var escaped =
                configuration
                        .replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        var second =
                twoNodes
                        ? """
<sequenceFlow id="b" sourceRef="first" targetRef="second"/>
<userTask id="second" name="第二节点" flowable:assignee="%s" os:handler="nocode" os:configuration="%s"/>
<sequenceFlow id="c" sourceRef="second" targetRef="end"/>
"""
                                .formatted(ACTOR, escaped)
                        : "<sequenceFlow id=\"b\" sourceRef=\"first\" targetRef=\"end\"/>";
        var xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:os="%s" targetNamespace="flow_version">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="first"/>
    <userTask id="first" name="第一节点" flowable:assignee="%s" os:handler="nocode" os:configuration="%s"/>
    %s<endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(BpmBusinessTaskBinding.NAMESPACE, key, ACTOR, escaped, second);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        fixture.deployments.add(deployment.getId());
    }
}
