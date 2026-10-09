package com.lingan.ucp.nocode.runtime.service.maintenance;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 后端管理入口的短生命周期上下文；不创建应用，不改变嵌套自动更新使用的应用授权。 */
public final class ObjectMaintenanceScope {
    private static final ThreadLocal<Long> ACTOR = new ThreadLocal<>();

    private ObjectMaintenanceScope() {}

    static <T> T run(long actor, Supplier<T> work) {
        Long previous = ACTOR.get();
        ACTOR.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) ACTOR.remove();
            else ACTOR.set(previous);
        }
    }

    public static boolean permits(String applicationId, long actor) {
        return applicationId == null && Objects.equals(ACTOR.get(), actor);
    }

    public static boolean active(String applicationId) {
        return applicationId == null && ACTOR.get() != null;
    }

    public static Map<String, Object> historySource(String applicationId) {
        return active(applicationId)
                ? Map.of("kind", "OBJECT_MAINTENANCE", "name", "数据对象维护")
                : null;
    }

    /** 只有完成管理权限验证后才进入；可写性仍由模型、状态和公共写入规则限制。 */
    public static ApplicationAuthorization.ObjectGrant grant(DataCenter.Definition definition) {
        Set<String> fields =
                BusinessFields.fields(definition).stream()
                        .map(FieldDefinition::id)
                        .collect(Collectors.toSet());
        Set<String> details =
                definition.details().stream()
                        .filter(d -> MemberStateEnum.ACTIVE.matches(d.state()))
                        .map(DataCenter.Detail::id)
                        .collect(Collectors.toSet());
        definition.details().stream()
                .filter(d -> details.contains(d.id()))
                .forEach(d -> d.fields().forEach(f -> fields.add(f.id())));
        Set<String> relations =
                definition.relations().stream()
                        .map(DataCenter.Relation::id)
                        .collect(Collectors.toSet());
        return new ApplicationAuthorization.ObjectGrant(
                definition.objectId(),
                Set.of(
                        ApplicationActionEnum.READ.getCode(),
                        ApplicationActionEnum.CREATE.getCode(),
                        ApplicationActionEnum.UPDATE.getCode(),
                        ApplicationActionEnum.DELETE.getCode()),
                ApplicationScopeEnum.ALL.getCode(),
                fields,
                fields,
                details,
                details,
                relations,
                relations,
                Map.of(),
                fields);
    }
}
