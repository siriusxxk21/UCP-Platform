package com.lingan.ucp.nocode.runtime.service.record;

import java.util.function.Supplier;

/** 测试入口：正式代码里只有 RecordLinkageSync 能打开 LinkageWriteScope（run 是包内可见的），集成测试经这里进入同一个入口。 */
public final class LinkageWriteScopeTestAccess {
    private LinkageWriteScopeTestAccess() {}

    public static <T> T run(LinkageWriteScope.Context context, Supplier<T> work) {
        return LinkageWriteScope.run(context, work);
    }
}
