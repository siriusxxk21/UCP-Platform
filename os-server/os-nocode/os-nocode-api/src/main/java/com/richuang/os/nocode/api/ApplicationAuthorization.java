package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 应用业务授权绑定底座用户或角色；权限独立于设计发布，撤销无需重新发布应用。 */
public final class ApplicationAuthorization {
    private ApplicationAuthorization() {}

    public record ObjectGrant(
            String objectId,
            Set<String> actions,
            String scope,
            Set<String> readFields,
            Set<String> writeFields,
            Set<String> readDetails,
            Set<String> writeDetails,
            Set<String> readRelations,
            Set<String> writeRelations,
            Map<String, DataScope> actionScopes,
            Set<String> computeFields) {
        public ObjectGrant {
            // 旧授权未配置关系时默认不授予，新增关系不能自动扩大成员权限。
            readRelations = readRelations == null ? Set.of() : Set.copyOf(readRelations);
            writeRelations = writeRelations == null ? Set.of() : Set.copyOf(writeRelations);
            actionScopes = actionScopes == null ? Map.of() : Map.copyOf(actionScopes);
            computeFields = computeFields == null ? Set.of() : Set.copyOf(computeFields);
        }

        public ObjectGrant(
                String objectId,
                Set<String> actions,
                String scope,
                Set<String> readFields,
                Set<String> writeFields,
                Set<String> readDetails,
                Set<String> writeDetails,
                Set<String> readRelations,
                Set<String> writeRelations) {
            this(
                    objectId,
                    actions,
                    scope,
                    readFields,
                    writeFields,
                    readDetails,
                    writeDetails,
                    readRelations,
                    writeRelations,
                    Map.of(),
                    Set.of());
        }

        public ObjectGrant(
                String objectId,
                Set<String> actions,
                String scope,
                Set<String> readFields,
                Set<String> writeFields,
                Set<String> readDetails,
                Set<String> writeDetails) {
            this(
                    objectId,
                    actions,
                    scope,
                    readFields,
                    writeFields,
                    readDetails,
                    writeDetails,
                    Set.of(),
                    Set.of());
        }
    }

    public record Member(String principalKind, String principalId, List<ObjectGrant> objects) {}

    public record Policy(int revision, List<Member> members) {}

    public record Save(String applicationId, int expectedRevision, List<Member> members) {}

    /** 具体记录上的有效权限；前端用来展示，后端仍在每次请求重新计算。 */
    public record Capabilities(
            Set<String> actions,
            Set<String> readFields,
            Set<String> writeFields,
            Set<String> readDetails,
            Set<String> writeDetails,
            Set<String> readRelations,
            Set<String> writeRelations) {
        public Capabilities(
                Set<String> actions,
                Set<String> readFields,
                Set<String> writeFields,
                Set<String> readDetails,
                Set<String> writeDetails) {
            this(actions, readFields, writeFields, readDetails, writeDetails, Set.of(), Set.of());
        }
    }
}
