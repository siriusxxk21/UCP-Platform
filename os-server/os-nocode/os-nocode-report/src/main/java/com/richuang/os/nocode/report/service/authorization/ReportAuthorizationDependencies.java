package com.richuang.os.nocode.report.service.authorization;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.enums.DependencyKindEnum;
import com.richuang.os.nocode.report.dal.dataobject.ReportDatasetDO;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 授权条件独立于展示字段登记依赖；调用方持设计及资源写锁，变更与审计同事务。 */
@Component
public class ReportAuthorizationDependencies {
    @Resource private DataObjectApi objects;

    public void ceiling(ReportDatasetDO dataset, String objectId, ObjectGrant grant, long actor) {
        replace(dataset, "ceiling:" + objectId, grant == null ? List.of() : List.of(grant), actor);
    }

    public void policy(ReportDatasetDO dataset, List<Member> members, long actor) {
        replace(
                dataset,
                "policy",
                members.stream().flatMap(member -> member.objects().stream()).toList(),
                actor);
    }

    private void replace(
            ReportDatasetDO dataset, String suffix, List<ObjectGrant> grants, long actor) {
        String key = dataset.getId() + ":" + suffix;
        objects.removeDependencies(DependencyKindEnum.DATASET.getCode(), key, actor);
        Map<String, Set<String>> fields = new TreeMap<>();
        for (ObjectGrant grant : grants) {
            Set<String> used = fields.computeIfAbsent(grant.objectId(), ignored -> new TreeSet<>());
            grant.actionScopes().values().forEach(scope -> collect(scope, used));
        }
        fields.forEach(
                (id, used) -> {
                    if (!used.isEmpty())
                        objects.registerDependency(
                                new DataCenter.Dependency(
                                        DependencyKindEnum.DATASET.getCode(),
                                        key,
                                        dataset.getName() + " [" + suffix + "]",
                                        id,
                                        List.copyOf(used)),
                                actor);
                });
    }

    private void collect(DataScope scope, Set<String> fields) {
        scope.conditions().forEach(condition -> fields.add(condition.fieldId()));
        scope.groups().forEach(group -> collect(group, fields));
    }
}
