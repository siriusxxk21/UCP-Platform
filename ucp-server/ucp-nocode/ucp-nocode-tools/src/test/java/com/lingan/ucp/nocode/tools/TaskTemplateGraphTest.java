package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskTemplateGraph;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

/** 总任务单独存储的模板协议回归，防止旧模板首节点误当总任务或丢失时间／授权。 */
class TaskTemplateGraphTest {
    @Test
    void aRootOnlyTemplateIsValidAndPreservesItsExpectedSchedule() {
        NodeInput root = node("root", null, List.of(), policy());
        TaskTemplateGraph.Definition definition = TaskTemplateGraph.normalize(root, List.of(), 7);
        assertThat(definition.nodes()).isEmpty();
        assertThat(definition.all()).hasSize(1);
        assertThat(definition.task().dataPolicy()).isEqualTo(policy());
        assertThat(definition.task().schedule()).isEqualTo(root.schedule());
        assertThat(definition.task().assigneeId()).isNull();
    }

    @Test
    void rootAndChildrenAreValidatedTogetherButStoredSeparately() {
        TaskTemplateGraph.Definition definition =
                TaskTemplateGraph.normalize(
                        node("root", null, List.of(), policy()),
                        List.of(
                                node("first", null, List.of(), null),
                                node("second", null, List.of("first"), null),
                                node("nested", "second", List.of(), null)),
                        7);
        assertThat(definition.all().get(1).parentId()).isEqualTo("root");
        assertThat(definition.nodes())
                .extracting(NodeInput::id)
                .containsExactly("first", "second", "nested");
        assertThat(definition.nodes().getFirst().parentId()).isNull();
        assertThat(definition.nodes().get(1).predecessorIds()).containsExactly("first");
        assertThat(definition.nodes().get(2).parentId()).isEqualTo("second");
    }

    @Test
    void anOldTemplateDoesNotGainARootOrDataAuthorization() {
        TaskTemplateGraph.Definition definition =
                TaskTemplateGraph.normalize(null, List.of(node("first", null, List.of(), null)), 7);
        assertThat(definition.task()).isNull();
        assertThat(definition.nodes()).hasSize(1);
        assertThat(definition.nodes().getFirst().dataPolicy()).isNull();
        assertThatThrownBy(() -> TaskTemplateGraph.normalize(null, List.of(), 7))
                .hasMessageContaining("1 至 200");
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        null, List.of(node("first", null, List.of(), policy())), 7))
                .hasMessageContaining("独立的模板总任务");
    }

    @Test
    void childrenCannotOverrideTheRootPolicyAndRootCannotBeDuplicated() {
        NodeInput root = node("root", null, List.of(), policy());
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        root, List.of(node("child", null, List.of(), policy())), 7))
                .hasMessageContaining("统一继承");
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        root, List.of(node("root", null, List.of(), null)), 7))
                .hasMessageContaining("重复");
    }

    @Test
    void aChildCannotWaitForItsRootToFinish() {
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        node("root", null, List.of(), policy()),
                                        List.of(node("child", null, List.of("root"), null)),
                                        7))
                .hasMessageContaining("死锁");
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        node("root", null, List.of("child"), policy()),
                                        List.of(node("child", null, List.of(), null)),
                                        7))
                .hasMessageContaining("前置任务");
    }

    @Test
    void childResourcesCannotBypassAUnifiedRootsResourceSelection() {
        NodeInput child = node("child", null, List.of(), null);
        NodeInput withBinding =
                new NodeInput(
                        child.id(),
                        child.parentId(),
                        child.title(),
                        null,
                        null,
                        null,
                        null,
                        child.schedule(),
                        child.predecessorIds(),
                        new Binding("other-app", "form", null),
                        null,
                        null,
                        AssignmentMode.OPEN,
                        List.of(),
                        null);
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        node("root", null, List.of(), policy()),
                                        List.of(withBinding),
                                        7))
                .hasMessageContaining("统一使用总任务");
        assertThat(TaskTemplateGraph.normalize(null, List.of(withBinding), 7).nodes()).hasSize(1);
    }

    private DataPolicy policy() {
        return new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.ALL);
    }

    private NodeInput node(String id, String parent, List<String> predecessors, DataPolicy policy) {
        return new NodeInput(
                id,
                parent,
                id,
                null,
                null,
                null,
                null,
                new Schedule(
                        TimeMode.FIXED,
                        LocalDateTime.of(2026, 10, 5, 9, 0),
                        0,
                        1,
                        LocalDateTime.of(2026, 10, 6, 18, 0)),
                predecessors,
                null,
                null,
                null,
                AssignmentMode.OPEN,
                List.of(),
                policy);
    }
}
