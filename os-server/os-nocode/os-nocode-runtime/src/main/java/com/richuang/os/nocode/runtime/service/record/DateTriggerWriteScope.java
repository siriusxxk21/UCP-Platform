package com.richuang.os.nocode.runtime.service.record;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 「按日期自动执行」以系统身份写目标记录的唯一通道。线程上下文，只能由 {@link RecordDateTriggers} 打开，HTTP 无法设置。
 *
 * <p>在 Scope 内：应用入口按「系统写入」放行（不看操作者是不是运行成员）；Scope 的应用 × 目标对象只给一条窄授权——
 * 读全部、改只能改本规则赋值的字段与服务端维护的字段，范围为全部数据。 不豁免流程保护（目标在审批中时这一条失败、记原因）。操作者 ID 只用于留痕（取应用创建人），不参与权限判断。
 */
public final class DateTriggerWriteScope {
    private DateTriggerWriteScope() {}

    /**
     * @param writable 窄授权的可写字段：本规则赋值的字段加上目标对象上由服务端维护的字段（嵌套保存会顺带重算它们）
     * @param businessDate 本次执行对应的业务日
     * @param manual 是否由「立即按今天执行」触发
     */
    public record Context(
            String applicationId,
            int applicationVersion,
            String ruleId,
            String ruleName,
            String targetObjectId,
            String targetRecordId,
            Set<String> writable,
            String sourceObjectId,
            String sourceRecordId,
            LocalDate businessDate,
            boolean manual) {
        public Context {
            writable = Set.copyOf(writable);
        }
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

    /** Scope 是否就是这个应用：应用入口放行用。 */
    public static boolean permitsEntry(String applicationId) {
        var c = CURRENT.get();
        return c != null && c.applicationId().equals(applicationId);
    }

    /** Scope 是否就是这个应用的这个目标对象：是则返回上下文（窄授权用），否则 null。 */
    public static Context target(String applicationId, String objectId) {
        var c = CURRENT.get();
        return c != null
                        && applicationId != null
                        && c.applicationId().equals(applicationId)
                        && c.targetObjectId().equals(objectId)
                ? c
                : null;
    }

    /** 被记历史的记录正是 Scope 的目标记录时，变更来源记为按日期自动执行；否则返回 null。 */
    public static Map<String, Object> historySource(
            String applicationId, String objectId, String recordId) {
        var c = CURRENT.get();
        if (c == null
                || !c.applicationId().equals(applicationId)
                || !c.targetObjectId().equals(objectId)
                || !c.targetRecordId().equals(recordId)) return null;
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "DATE_TRIGGER");
        source.put("applicationId", c.applicationId());
        source.put("version", c.applicationVersion());
        source.put("ruleId", c.ruleId());
        source.put("name", c.ruleName());
        source.put("sourceObjectId", c.sourceObjectId());
        source.put("sourceRecordId", c.sourceRecordId());
        source.put("businessDate", c.businessDate().toString());
        if (c.manual()) source.put("manual", true);
        return source;
    }
}
