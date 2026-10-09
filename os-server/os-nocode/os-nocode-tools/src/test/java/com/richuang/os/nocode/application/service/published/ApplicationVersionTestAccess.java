package com.richuang.os.nocode.application.service.published;

import java.util.function.Supplier;

/** 测试入口：把一次业务调用绑在指定的应用发布版本上（正式代码里由任务办理经 withVersion 绑定）。 */
public final class ApplicationVersionTestAccess {
    private ApplicationVersionTestAccess() {}

    public static <T> T bind(
            ApplicationVersionContext context,
            String applicationId,
            int version,
            Supplier<T> work) {
        return context.execute(applicationId, version, work);
    }
}
