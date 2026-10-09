package com.lingan.ucp.nocode.runtime.service.record;

import java.util.Map;
import java.util.function.Supplier;

/** 仅服务端已发布留存动作可建立此上下文；复用记录历史保存来源，不接受客户端传入。 */
public final class CaptureWriteScope {
    private CaptureWriteScope() {}

    public record Context(
            String applicationId,
            int version,
            String actionId,
            String name,
            String objectId,
            int objectVersion,
            String recordId,
            Map<String, String> captures) {}

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    static Context current() {
        return CURRENT.get();
    }

    static <T> T run(Context context, Supplier<T> work) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Map<String, Object> historySource() {
        Context context = CURRENT.get();
        return context == null
                ? null
                : Map.of(
                        "kind",
                        "CAPTURE_VALUES",
                        "applicationId",
                        context.applicationId(),
                        "version",
                        context.version(),
                        "actionId",
                        context.actionId(),
                        "name",
                        context.name(),
                        "objectVersion",
                        context.objectVersion(),
                        "captures",
                        context.captures());
    }
}
