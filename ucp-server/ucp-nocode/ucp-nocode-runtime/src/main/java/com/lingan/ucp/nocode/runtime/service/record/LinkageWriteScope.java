package com.lingan.ucp.nocode.runtime.service.record;

import com.lingan.ucp.nocode.api.ApplicationRecords.Save;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 系统写「来源变化时自动更新」的联动字段的唯一通道。线程上下文，只能由 {@link RecordLinkageSync} 打开，HTTP 无法设置。
 *
 * <p>在 Scope 内：应用入口与目标对象的授权按「系统写入」处理（不看操作者是不是运行成员、对目标有没有权限）；而流程保护的豁免只给 {@link #covers}
 * 判定为真的那一条保存命令——命令的应用、对象、记录与 Scope 一致，且只带声明过的联动字段、不带明细、关系、动作等任何别的输入。 值由服务端重算，命令里带的值不被信任。
 */
public final class LinkageWriteScope {
    private LinkageWriteScope() {}

    /** 嵌套层数上限：目标的变化又是别的自动更新的来源时会连锁，超过即回滚。 */
    public static final int MAX_DEPTH = 5;

    /**
     * @param fields 本次由系统写入的联动字段 ID（必须都是该应用版本登记过的自动更新字段），按字段定义顺序
     * @param fieldNames 这些字段的名称，顺序同 fields，写进变更历史
     * @param serverManaged 目标对象上另由服务端维护的字段（自动更新规则、留存动作的目标字段）：嵌套保存会顺带重算它们， 所以窄授权里也算可写；它们不在 fields
     *     里，命令里出现它们时 {@link #covers} 为假
     * @param sourceObjectId 触发这次更新的来源对象；回填时为 null
     * @param sourceRecordId 触发这次更新的来源记录；回填时为 null
     * @param depth 嵌套层数，最外层为 1
     */
    public record Context(
            String applicationId,
            int applicationVersion,
            String targetObjectId,
            String targetRecordId,
            List<String> fields,
            List<String> fieldNames,
            Set<String> serverManaged,
            String sourceObjectId,
            String sourceRecordId,
            boolean backfill,
            int depth) {
        public Context {
            fields = List.copyOf(fields);
            fieldNames = List.copyOf(fieldNames);
            serverManaged = Set.copyOf(serverManaged);
        }

        /** 窄授权的可写字段：声明的联动字段加上服务端维护的字段。 */
        public Set<String> writable() {
            Set<String> result = new HashSet<>(fields);
            result.addAll(serverManaged);
            return result;
        }
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    public static Context current() {
        return CURRENT.get();
    }

    /** 当前嵌套层数；不在 Scope 内为 0。 */
    public static int depth() {
        var c = CURRENT.get();
        return c == null ? 0 : c.depth();
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

    /** Scope 是否就是这个应用：应用入口放行用（回填的发起人是应用设计者，不一定是运行成员）。 */
    public static boolean permitsEntry(String applicationId) {
        var c = CURRENT.get();
        return c != null && c.applicationId().equals(applicationId);
    }

    /** Scope 是否就是这个应用的这个目标对象：是则返回上下文（窄授权用），否则 null。 */
    public static Context target(String applicationId, String objectId) {
        var c = CURRENT.get();
        return c != null
                        && c.applicationId().equals(applicationId)
                        && c.targetObjectId().equals(objectId)
                ? c
                : null;
    }

    /**
     * 这条保存命令是不是 Scope 声明的那一次系统写入。全部成立才为真：有 Scope；应用、对象、记录与 Scope 一致；提交的字段都在声明的联动字段内；
     * 不带明细、关系、页面上下文、表单、请求键、动作与关联记录。
     */
    public static boolean covers(Save command) {
        var c = CURRENT.get();
        return c != null
                && command != null
                && c.applicationId().equals(command.applicationId())
                && c.targetObjectId().equals(command.objectId())
                && c.targetRecordId().equals(command.id())
                && command.values() != null
                && c.fields().containsAll(command.values().keySet())
                && command.details() == null
                && command.relations() == null
                && command.context() == null
                && command.formId() == null
                && command.requestKey() == null
                && command.actionCode() == null
                && (command.relatedRecords() == null || command.relatedRecords().isEmpty());
    }

    /** 被记历史的记录正是 Scope 的目标记录时，变更来源记为数据联动自动更新；否则返回 null。 */
    public static Map<String, Object> historySource(
            String applicationId, String objectId, String recordId) {
        var c = CURRENT.get();
        if (c == null
                || !c.applicationId().equals(applicationId)
                || !c.targetObjectId().equals(objectId)
                || !c.targetRecordId().equals(recordId)) return null;
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "LINKAGE");
        source.put("applicationId", c.applicationId());
        source.put("version", c.applicationVersion());
        source.put("name", String.join("、", c.fieldNames()));
        source.put("fieldIds", c.fields());
        if (c.backfill()) source.put("backfill", true);
        else {
            source.put("sourceObjectId", c.sourceObjectId());
            source.put("sourceRecordId", c.sourceRecordId());
        }
        return source;
    }
}
