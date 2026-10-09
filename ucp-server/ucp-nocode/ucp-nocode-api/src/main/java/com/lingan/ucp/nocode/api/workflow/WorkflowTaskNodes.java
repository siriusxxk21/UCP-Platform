package com.lingan.ucp.nocode.api.workflow;

import com.lingan.ucp.nocode.api.TaskCenter;

import java.time.LocalDateTime;
import java.util.List;

/** 工作流任务节点只保存编排快照及来源，不传递业务记录或复制任务状态机。 */
public final class WorkflowTaskNodes {
    private WorkflowTaskNodes() {}

    public enum Source {
        TEMPLATE,
        CUSTOM
    }

    public enum Role {
        ASSIGNEE,
        ACCEPTOR
    }

    public enum PersonSource {
        INITIATOR,
        FORM_FIELD
    }

    public enum State {
        CREATING,
        WAITING,
        COMPLETED,
        INVALIDATED
    }

    public record Person(String nodeId, Role role, PersonSource source, String field) {}

    /** publisherId 和 definition 均由发布服务重算，绝不接受客户端提供的授权快照。 */
    public record Configuration(
            int version,
            Source source,
            String templateId,
            Integer templateVersion,
            TaskCenter.NodeInput task,
            List<TaskCenter.NodeInput> nodes,
            List<Person> people,
            Long publisherId,
            Definition definition) {}

    /** 仅内部发布/激活通道使用；授权与资源来自任务中心既有编译器。 */
    public record Definition(
            TaskCenter.NodeInput task,
            List<TaskCenter.NodeInput> nodes,
            String templateId,
            Integer templateVersion,
            String authorizationJson) {}

    public record View(
            String executionId,
            String processInstanceId,
            String nodeId,
            String nodeName,
            String taskId,
            State state,
            TaskCenter.State taskState,
            String error,
            boolean canViewTask,
            boolean canViewProcess,
            boolean canRetry,
            LocalDateTime createdAt,
            String readOnlyReason) {}

    public record Retry(String executionId) {}
}
