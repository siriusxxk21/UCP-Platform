package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.TaskCenter.AssignmentMode;
import com.richuang.os.nocode.api.TaskCenter.NodeInput;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskGraph;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskTemplateGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

/** 单总任务顺序编排的后端接参兼容回归，不模拟前端编辑算法、不启动服务或访问数据库。 图中的开始和结束为界面标记，以下输入只包含真实任务及真实任务之间的前置关系。 */
class TaskSequenceGraphTest {
    @Test
    void ordinaryDecompositionKeepsOneNamedRootWithoutInventingSequenceEdges() {
        List<NodeInput> normalized =
                TaskGraph.normalize(
                        List.of(
                                node("whole", null),
                                node("a", "whole"),
                                node("a1", "a"),
                                node("a11", "a1"),
                                node("b", "whole")),
                        101L);

        assertThat(normalized)
                .extracting(NodeInput::id)
                .containsExactly("whole", "a", "a1", "a11", "b");
        assertThat(normalized)
                .filteredOn(n -> n.parentId() == null)
                .extracting(NodeInput::id)
                .containsExactly("whole");
        assertThat(normalized).allSatisfy(n -> assertThat(n.predecessorIds()).isEmpty());
        assertThat(TaskGraph.index(normalized).get("a11").parentId()).isEqualTo("a1");
    }

    @Test
    void insertedNextStepCanReplaceAllDirectSuccessorEdgesAndRetainOtherJoinInputs() {
        Map<String, NodeInput> normalized =
                normalize(
                        node("whole", null),
                        node("a", "whole"),
                        node("other", "whole"),
                        node("x", "whole", "a"),
                        node("b", "whole", "x", "other"),
                        node("c", "whole", "x"),
                        node("later", "whole", "b", "c"));

        assertThat(normalized.get("x").parentId()).isEqualTo("whole");
        assertThat(normalized.get("x").predecessorIds()).containsExactly("a");
        assertThat(normalized.get("b").predecessorIds()).containsExactly("x", "other");
        assertThat(normalized.get("c").predecessorIds()).containsExactly("x");
        assertThat(normalized.get("later").predecessorIds()).containsExactly("b", "c");
    }

    @Test
    void parallelStepCanSharePredecessorsAndJoinEveryDirectSuccessor() {
        Map<String, NodeInput> normalized =
                normalize(
                        node("whole", null),
                        node("before", "whole"),
                        node("other", "whole"),
                        node("a", "whole", "before"),
                        node("parallel", "whole", "before"),
                        node("b", "whole", "a", "other", "parallel"),
                        node("c", "whole", "a", "parallel"));

        assertThat(normalized.get("parallel").parentId()).isEqualTo(normalized.get("a").parentId());
        assertThat(normalized.get("parallel").predecessorIds())
                .containsExactlyElementsOf(normalized.get("a").predecessorIds());
        assertThat(normalized.get("b").predecessorIds()).containsExactly("a", "other", "parallel");
        assertThat(normalized.get("c").predecessorIds()).containsExactly("a", "parallel");
        assertThat(normalized.get("parallel").predecessorIds()).doesNotContain("a");
    }

    @Test
    void sequentialGroupsRetainTheirOwnRecursiveDecomposition() {
        Map<String, NodeInput> normalized =
                normalize(
                        node("whole", null),
                        node("a", "whole"),
                        node("a1", "a"),
                        node("a11", "a1"),
                        node("x", "whole", "a"),
                        node("x1", "x"),
                        node("b", "whole", "x"),
                        node("b1", "b"));

        assertThat(normalized.get("a11").parentId()).isEqualTo("a1");
        assertThat(normalized.get("x1").parentId()).isEqualTo("x");
        assertThat(normalized.get("b1").parentId()).isEqualTo("b");
        assertThat(normalized.get("x").predecessorIds()).containsExactly("a");
        assertThat(normalized.get("b").predecessorIds()).containsExactly("x");
        assertThat(normalized.get("b1").predecessorIds()).isEmpty();
    }

    @Test
    void templateKeepsTheIndependentRootAndTheSameForkJoinGraph() {
        TaskTemplateGraph.Definition definition =
                TaskTemplateGraph.normalize(
                        node("whole", null),
                        List.of(
                                node("before", null),
                                node("a", null, "before"),
                                node("parallel", null, "before"),
                                node("a1", "a"),
                                node("b", null, "a", "parallel")),
                        101L);

        assertThat(definition.task().id()).isEqualTo("whole");
        assertThat(definition.task().predecessorIds()).isEmpty();
        assertThat(definition.nodes()).extracting(NodeInput::id).doesNotContain("whole");
        Map<String, NodeInput> nodes = TaskGraph.index(definition.nodes());
        assertThat(nodes.get("b").predecessorIds()).containsExactly("a", "parallel");
        assertThat(nodes.get("a1").parentId()).isEqualTo("a");
        assertThat(nodes.get("a").parentId()).isNull();
        assertThat(TaskGraph.index(definition.all()).get("a").parentId()).isEqualTo("whole");
    }

    @Test
    void backwardSequenceEdgeStillRejectsACycle() {
        assertThatThrownBy(
                        () ->
                                normalize(
                                        node("whole", null),
                                        node("a", "whole", "b"),
                                        node("x", "whole", "a"),
                                        node("b", "whole", "x")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("死锁");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentChildCompletionDeadlockCannotBeIntroducedByAnEdge(boolean parentWaitsForChild) {
        NodeInput parent = parentWaitsForChild ? node("a", "whole", "a1") : node("a", "whole");
        NodeInput child = parentWaitsForChild ? node("a1", "a") : node("a1", "a", "a");

        assertThatThrownBy(() -> normalize(node("whole", null), parent, child))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("死锁");
    }

    @ParameterizedTest
    @ValueSource(strings = {"visual-start", "visual-end"})
    void visualMarkersCannotBeReferencedAsRealPredecessors(String marker) {
        assertThatThrownBy(() -> normalize(node("whole", null), node("a", "whole", marker)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("前置任务必须为当前实例");
    }

    private Map<String, NodeInput> normalize(NodeInput... nodes) {
        return TaskGraph.index(TaskGraph.normalize(List.of(nodes), 101L));
    }

    private NodeInput node(String id, String parentId, String... predecessors) {
        return new NodeInput(
                id,
                parentId,
                "whole".equals(id) ? "整件工作的独立名称" : id,
                null,
                null,
                null,
                null,
                null,
                List.of(predecessors),
                null,
                null,
                null,
                AssignmentMode.UNASSIGNED,
                null);
    }
}
