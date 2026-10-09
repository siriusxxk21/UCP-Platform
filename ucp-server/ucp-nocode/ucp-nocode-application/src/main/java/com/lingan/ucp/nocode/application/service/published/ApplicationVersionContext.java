package com.lingan.ucp.nocode.application.service.published;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 同步业务调用的应用版本作用域，只保存版本身份，不保存授权结论或业务数据。
 *
 * <p>嵌套调用保留外层范围，异常同样恢复；异步线程不继承，异步任务必须重新解析和授权。
 */
@Component
public class ApplicationVersionContext {
    private final ThreadLocal<Map<String, Integer>> versions = new ThreadLocal<>();

    public Integer version(String applicationId) {
        var current = versions.get();
        return current == null ? null : current.get(applicationId);
    }

    <T> T execute(String applicationId, int version, Supplier<T> action) {
        var previous = versions.get();
        var existing = version(applicationId);
        if (existing != null && existing != version) throw invalid("同一次业务调用不能切换已绑定的应用版本");
        Map<String, Integer> next = previous == null ? new HashMap<>() : new HashMap<>(previous);
        next.put(applicationId, version);
        versions.set(next);
        try {
            return action.get();
        } finally {
            if (previous == null) versions.remove();
            else versions.set(previous);
        }
    }
}
