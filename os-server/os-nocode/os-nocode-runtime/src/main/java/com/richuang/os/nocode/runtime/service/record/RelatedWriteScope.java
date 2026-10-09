package com.richuang.os.nocode.runtime.service.record;

import com.richuang.os.nocode.api.ApplicationRecords;

import java.util.Set;
import java.util.function.Supplier;

/** 仅联合保存编排器可设置：将发布关系生成的赋值限定到这一个命令实例。 */
final class RelatedWriteScope {
    private record Grant(ApplicationRecords.Save command, Set<String> fieldIds) {}

    private static final ThreadLocal<Grant> CURRENT = new ThreadLocal<>();

    private RelatedWriteScope() {}

    static <T> T execute(
            ApplicationRecords.Save command, Set<String> fieldIds, Supplier<T> action) {
        var previous = CURRENT.get();
        CURRENT.set(new Grant(command, Set.copyOf(fieldIds)));
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static Set<String> fields(ApplicationRecords.Save command) {
        var current = CURRENT.get();
        return current != null && current.command() == command ? current.fieldIds() : Set.of();
    }
}
