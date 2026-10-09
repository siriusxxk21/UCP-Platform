package com.lingan.ucp.nocode.api;

import java.util.List;
import java.util.Set;

/** 三层授权独立修订；资源操作、对象上限和成员数据策略不能互相替代。 */
public final class ReportAuthorization {
    private ReportAuthorization() {}

    public record ResourceMember(String principalKind, String principalId, Set<String> actions) {}

    public record ResourcePolicy(int revision, List<ResourceMember> members) {}

    public record SaveResource(
            String datasetId, int expectedRevision, List<ResourceMember> members, String reason) {}

    public record SaveDashboardResource(
            String id, int expectedRevision, List<ResourceMember> members, String reason) {}

    /** permission=null 明确撤销，保留行与修订号，避免旧请求重新放权。 */
    public record ObjectCeiling(
            String objectId,
            int revision,
            ApplicationAuthorization.ObjectGrant permission,
            String reason) {}

    public record SaveCeiling(
            String datasetId,
            String objectId,
            int expectedRevision,
            ApplicationAuthorization.ObjectGrant permission,
            String reason) {}

    public record DataPolicy(int revision, List<ApplicationAuthorization.Member> members) {}

    public record SaveDataPolicy(
            String datasetId,
            int expectedRevision,
            List<ApplicationAuthorization.Member> members,
            String reason) {}
}
