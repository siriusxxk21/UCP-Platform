package com.richuang.os.nocode.api;

import java.time.LocalDateTime;

/** 对象对应用的实时授权上限；复用应用现有操作、范围、字段、明细和关系授权契约。 */
public final class ObjectSharing {
    private ObjectSharing() {}

    public record Grant(
            String objectId,
            String applicationId,
            String applicationName,
            int revision,
            ApplicationAuthorization.ObjectGrant permission,
            String reason,
            String updater,
            LocalDateTime updateTime) {}

    /** permission=null 表示撤销；保留修订号和审计记录，避免旧请求重新覆盖。 */
    public record Save(
            String objectId,
            String applicationId,
            int expectedRevision,
            ApplicationAuthorization.ObjectGrant permission,
            String reason) {}

    public record Target(String id, String name) {}
}
