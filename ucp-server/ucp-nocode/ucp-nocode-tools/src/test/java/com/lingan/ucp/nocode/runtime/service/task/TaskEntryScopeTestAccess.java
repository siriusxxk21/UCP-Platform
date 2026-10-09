package com.lingan.ucp.nocode.runtime.service.task;

import java.util.function.Supplier;

/** 测试入口：在任务入口的办理上下文里执行（正式代码里只有运行编排器能开启）。 */
public final class TaskEntryScopeTestAccess {
    private TaskEntryScopeTestAccess() {}

    public static <T> T run(
            TaskEntryRuntimeScope scope,
            TaskEntryRuntimeScope.Invocation invocation,
            Supplier<T> work) {
        return scope.execute(invocation, work);
    }
}
