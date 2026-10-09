package com.richuang.os.nocode.report.service.authorization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.nocode.api.ReportAuthorization;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.ReportAuthorizationMapper;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.*;

/** ACL 在资源头锁内读取；拥有者仅获得资源管理权，不能用于数据策略豁免。 */
@Component
public class ReportResourceAccess {
    @Resource private ReportAuthorizationMapper store;
    @Resource private ReportJson json;
    @Resource private ReportPrincipals principals;

    public ReportAuthorization.ResourcePolicy policy(String kind, long id) {
        ReportResourceAclDO row = store.acl(kind, id);
        return row == null
                ? new ReportAuthorization.ResourcePolicy(0, List.of())
                : new ReportAuthorization.ResourcePolicy(
                        row.getLockVersion(),
                        json.read(
                                row.getPolicyJson(),
                                new TypeReference<List<ReportAuthorization.ResourceMember>>() {}));
    }

    public void require(ReportDatasetDO dataset, long actor, ReportResourceActionEnum action) {
        if (dataset == null) throw new AccessDeniedException("数据集不存在或无权访问");
        require(
                ReportResourceKindEnum.DATASET,
                dataset.getId(),
                dataset.getOwnerId(),
                actor,
                action);
    }

    public void require(
            ReportDatasetDO dataset, Set<String> identities, ReportResourceActionEnum action) {
        if (dataset == null) throw new AccessDeniedException("数据集不存在或无权访问");
        require(
                ReportResourceKindEnum.DATASET,
                dataset.getId(),
                dataset.getOwnerId(),
                identities,
                action);
    }

    /** 所有资源共用一份 ACL；拥有者仅隐式获得资源动作，不豁免来源数据授权。 */
    public void require(
            ReportResourceKindEnum kind,
            long id,
            long ownerId,
            long actor,
            ReportResourceActionEnum action) {
        if (actor <= 0) throw new AccessDeniedException("报表资源不存在或无权访问");
        require(kind, id, ownerId, principals.current(actor).principals(), action);
    }

    public void require(
            ReportResourceKindEnum kind,
            long id,
            long ownerId,
            Set<String> identities,
            ReportResourceActionEnum action) {
        if (!actions(kind, id, ownerId, identities).contains(action))
            throw new AccessDeniedException("没有报表资源操作权限：" + action.getCode());
    }

    public Set<ReportResourceActionEnum> actions(
            ReportResourceKindEnum kind, long id, long ownerId, Set<String> identities) {
        if (identities.contains(ApplicationPrincipalEnum.USER.getCode() + ":" + ownerId))
            return EnumSet.allOf(ReportResourceActionEnum.class);
        Set<ReportResourceActionEnum> actions = EnumSet.noneOf(ReportResourceActionEnum.class);
        for (ReportAuthorization.ResourceMember member : policy(kind.getCode(), id).members())
            if (identities.contains(member.principalKind() + ":" + member.principalId()))
                member.actions()
                        .forEach(action -> actions.add(ReportResourceActionEnum.fromCode(action)));
        return actions;
    }
}
