package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

/** 同步调用栈内的可信办理上下文；不读取请求头，退出和异常均清理，禁止串到其他请求。 */
@Component
public class TaskEntryRuntimeScope {
    public record Invocation(
            String applicationId,
            String entryId,
            int version,
            String name,
            String objectId,
            long actor,
            Map<String, List<ApplicationAuthorization.ObjectGrant>> grants,
            TaskData data) {
        public Invocation(
                String applicationId,
                String entryId,
                int version,
                String name,
                String objectId,
                long actor,
                Map<String, List<ApplicationAuthorization.ObjectGrant>> grants) {
            this(applicationId, entryId, version, name, objectId, actor, grants, null);
        }
    }

    /** 仅可信编排器创建；集合在一次调用内包含新插入记录，防止回读时误拒并保持事务回滚边界。 */
    public record TaskData(
            String rootId,
            String taskId,
            String entryKey,
            long grantorId,
            Map<String, Set<String>> recordIds,
            boolean writable,
            Set<String> configuredObjects) {
        public TaskData {
            configuredObjects = Set.copyOf(configuredObjects);
        }

        public TaskData(
                String rootId,
                String taskId,
                String entryKey,
                long grantorId,
                Map<String, Set<String>> recordIds,
                boolean writable) {
            // 旧调用未区分依赖与显式资源时，保守沿用已知记录范围，不能因升级自动扩权。
            this(rootId, taskId, entryKey, grantorId, recordIds, writable, recordIds.keySet());
        }
    }

    public boolean delegated() {
        return current.get() != null && current.get().data() != null;
    }

    public Set<String> recordIds(String objectId) {
        return recordIds(objectId, false);
    }

    /** 自动补齐对象仅在名称引用中使用依赖候选；显式业务／反馈资源始终保留批准的 GROUP 集合。 */
    public Set<String> recordIds(String objectId, boolean reference) {
        Invocation invocation = current.get();
        return invocation == null
                        || invocation.data() == null
                        || reference && !invocation.data().configuredObjects().contains(objectId)
                ? null
                : invocation.data().recordIds().get(objectId);
    }

    public void requireRecord(String objectId, String recordId) {
        requireRecord(objectId, recordId, false);
    }

    public void requireRecord(String objectId, String recordId, boolean reference) {
        Set<String> ids = recordIds(objectId, reference);
        if (ids != null && !ids.contains(recordId)) throw invalid("此记录不在本组任务的数据范围内");
    }

    public void created(String objectId, String recordId) {
        Set<String> ids = recordIds(objectId);
        if (ids != null) ids.add(recordId);
    }

    public void requireWrite() {
        Invocation invocation = current.get();
        if (invocation != null && invocation.data() != null && !invocation.data().writable())
            throw invalid("当前调用只允许读取任务数据");
    }

    private final ThreadLocal<Invocation> current = new ThreadLocal<>();

    public Invocation current() {
        return current.get();
    }

    // 仅运行编排器可以开启；Controller 不能构造任意授权对象传入。
    <T> T execute(Invocation invocation, Supplier<T> action) {
        if (current.get() != null) throw invalid("不允许嵌套切换任务入口");
        current.set(invocation);
        try {
            return action.get();
        } finally {
            current.remove();
        }
    }
}
