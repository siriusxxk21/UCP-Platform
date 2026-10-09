package com.lingan.ucp.nocode.runtime.service.taskcenter;

import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.Config;

/** 办理项范围优先于旧分类策略；只描述记录集合，不授予应用、字段或写操作能力。 */
public final class TaskEntryScopes {
    private TaskEntryScopes() {}

    public static DataAccessMode effective(Config config, DataPolicy legacy) {
        if (config != null && config.dataScope() != null) return config.dataScope();
        if (legacy != null)
            return config != null && TaskDataPolicies.BUSINESS.equals(config.key())
                    ? legacy.business()
                    : legacy.feedback();
        return config != null && config.allowAll() ? DataAccessMode.ALL : DataAccessMode.GROUP;
    }

    /** 总任务冻结目录按稳定入口身份解析，旧单业务入口没有 Config 时保留原 business 范围。 */
    public static DataAccessMode root(NodeInput root, String key) {
        Config config =
                root.entries() == null
                        ? null
                        : root.entries().stream()
                                .filter(c -> key.equals(c.key()))
                                .findFirst()
                                .orElse(null);
        if (config == null && TaskDataPolicies.BUSINESS.equals(key) && root.dataPolicy() != null)
            return root.dataPolicy().business();
        return effective(config, root.dataPolicy());
    }

    public static boolean allowsAll(Config config) {
        return effective(config, null) == DataAccessMode.ALL;
    }

    /** 非整组共享入口不能借显式 ALL 扩大来源范围；旧缺省仍保留 allowAll 的更窄交集。 */
    public static DataAccessMode shared(Config requested, Config source) {
        return allowsAll(requested) && allowsAll(source)
                ? DataAccessMode.ALL
                : DataAccessMode.GROUP;
    }
}
