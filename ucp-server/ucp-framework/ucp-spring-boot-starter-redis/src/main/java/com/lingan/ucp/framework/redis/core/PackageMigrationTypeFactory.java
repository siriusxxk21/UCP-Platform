package com.lingan.ucp.framework.redis.core;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LRUMap;

/** 只在 Redis 类型解析时兼容历史包名，避免包迁移要求清空用户的存量缓存。 */
public class PackageMigrationTypeFactory extends TypeFactory {

    // 历史序列化协议标识，必须保留；它不是源码包或组件扫描路径。
    private static final String LEGACY_PREFIX = "com.richuang.os.";
    private static final String CURRENT_PREFIX = "com.lingan.ucp.";

    public PackageMigrationTypeFactory() {
        super(new LRUMap<>(16, 200));
    }

    /** 同时处理普通类与对象数组；泛型中的各个类由 TypeFactory 分别解析。 */
    @Override
    public Class<?> findClass(String className) throws ClassNotFoundException {
        int start = 0;
        while (start < className.length() && className.charAt(start) == '[') {
            start++;
        }
        if (start > 0 && start < className.length() && className.charAt(start) == 'L') {
            start++;
        }
        if (className.startsWith(LEGACY_PREFIX, start)) {
            className =
                    className.substring(0, start)
                            + CURRENT_PREFIX
                            + className.substring(start + LEGACY_PREFIX.length());
        }
        return super.findClass(className);
    }
}
