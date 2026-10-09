package com.lingan.ucp.nocode.runtime.service.handling;

import com.lingan.ucp.nocode.api.ApplicationRecords;

import java.util.Objects;
import java.util.function.Supplier;

/** 只有审批生效编排器能开启，限定一次申请的原操作者和原命令，退出必清理。 */
public final class HandlingWriteScope {
    private record Invocation(
            String requestId,
            ApplicationRecords.Save command,
            com.lingan.ucp.nocode.api.work.WorkDrafts.Submission material,
            long actor) {}

    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    private HandlingWriteScope() {}

    static <T> T execute(
            String id,
            ApplicationRecords.Save command,
            com.lingan.ucp.nocode.api.work.WorkDrafts.Submission material,
            long actor,
            Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("不能嵌套审批写入");
        CURRENT.set(new Invocation(id, command, material, actor));
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static boolean permits(ApplicationRecords.Save command, long actor) {
        var current = CURRENT.get();
        return current != null
                && current.actor() == actor
                && Objects.equals(current.command(), command);
    }

    public static String requestId() {
        return CURRENT.get() == null ? null : CURRENT.get().requestId();
    }

    public static void verify(
            com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies.Input document) {
        var current = CURRENT.get();
        if (current == null) return;
        var details = new java.util.LinkedHashMap<String, java.util.List<ApplicationRecords.Row>>();
        document.details()
                .forEach(
                        (key, rows) ->
                                details.put(
                                        key,
                                        rows.stream()
                                                .map(
                                                        row ->
                                                                new ApplicationRecords.Row(
                                                                        row.id(),
                                                                        null,
                                                                        row.values(),
                                                                        null))
                                                .toList()));
        BusinessHandlingServiceImpl.assertUnchanged(
                new ApplicationRecords.Aggregate(
                        new ApplicationRecords.Row(null, null, document.values(), null), details),
                current.material());
    }
}
