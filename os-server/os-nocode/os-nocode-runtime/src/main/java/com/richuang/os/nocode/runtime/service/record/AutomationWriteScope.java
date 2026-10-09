package com.richuang.os.nocode.runtime.service.record;

import com.richuang.os.nocode.api.ApplicationRecords.Save;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 服务端规则执行上下文；HTTP 不能设置，历史记录和维护字段保护共用同一身份。 */
public final class AutomationWriteScope {
    private AutomationWriteScope() {}

    public record Context(
            String applicationId,
            int version,
            String ruleId,
            String name,
            String sourceObjectId,
            String sourceRecordId,
            String targetObjectId,
            Set<String> fields,
            String targetRecordId,
            boolean taskDelegated) {
        public Context {
            fields = Set.copyOf(fields);
        }

        /** 普通应用规则保持原来的操作者授权方式。 */
        public Context(
                String applicationId,
                int version,
                String ruleId,
                String name,
                String sourceObjectId,
                String sourceRecordId,
                String targetObjectId,
                Set<String> fields) {
            this(
                    applicationId,
                    version,
                    ruleId,
                    name,
                    sourceObjectId,
                    sourceRecordId,
                    targetObjectId,
                    fields,
                    null,
                    false);
        }
    }

    /** 只识别服务端进入的任务规则目标，不授权其他对象或应用。 */
    public static Context taskTarget(String applicationId, String objectId) {
        Context context = current();
        return context != null
                        && context.taskDelegated()
                        && Objects.equals(applicationId, context.applicationId())
                        && Objects.equals(objectId, context.targetObjectId())
                ? context
                : null;
    }

    /** 窄写入不能夹带明细、关系、表单、动作或其他记录；不豁免业务及审批保护。 */
    public static boolean covers(Save command) {
        Context context = taskTarget(command.applicationId(), command.objectId());
        return context != null
                && Objects.equals(context.targetRecordId(), command.id())
                && command.values() != null
                && context.fields().containsAll(command.values().keySet())
                && command.details() == null
                && command.relations() == null
                && command.context() == null
                && command.formId() == null
                && command.requestKey() == null
                && command.actionCode() == null
                && (command.relatedRecords() == null || command.relatedRecords().isEmpty());
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    public static Context current() {
        return CURRENT.get();
    }

    static <T> T run(Context context, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Map<String, Object> historySource() {
        var c = current();
        return c == null
                ? null
                : Map.of(
                        "kind",
                        "AUTOMATION",
                        "applicationId",
                        c.applicationId(),
                        "version",
                        c.version(),
                        "ruleId",
                        c.ruleId(),
                        "name",
                        c.name(),
                        "sourceObjectId",
                        c.sourceObjectId(),
                        "sourceRecordId",
                        c.sourceRecordId());
    }
}
