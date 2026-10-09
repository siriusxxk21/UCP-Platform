package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.enums.*;

import java.util.*;
import java.util.stream.Collectors;

/** 对象共享上限和应用成员共用同一套权限值校验与集合交集语义。 */
public class ObjectGrantRules {
    public void validate(ObjectGrant grant, DataCenter.Definition d) {
        if (grant == null || !d.objectId().equals(grant.objectId())) throw invalid("授权对象不匹配");
        ApplicationScopeEnum.fromCode(grant.scope());
        if (grant.actions() == null
                || !grant.actions().contains(ApplicationActionEnum.READ.getCode()))
            throw invalid("业务授权必须包含查看权限；撤销请使用撤销授权操作");
        grant.actions().forEach(ApplicationActionEnum::fromCode);
        if (!grant.actions().containsAll(grant.actionScopes().keySet()))
            throw invalid("记录条件必须对应已允许操作");
        grant.actionScopes().values().forEach(scope -> scope.validate(d, true));
        subset(grant.computeFields(), grant.readFields(), "计算取数授权必须属于可读字段");
        if (!grant.computeFields().isEmpty()
                && (!ApplicationScopeEnum.ALL.matches(grant.scope())
                        || grant.actionScopes().containsKey(ApplicationActionEnum.READ.getCode())))
            throw invalid("共享计算取数需要明确的全部记录范围，不能随操作者变化");
        subset(
                grant.readFields(),
                d.fields().stream().map(f -> f.id()).collect(Collectors.toSet()),
                "可读字段");
        subset(grant.writeFields(), grant.readFields(), "可写字段必须可读");
        subset(
                grant.readDetails(),
                d.details().stream()
                        .filter(t -> MemberStateEnum.ACTIVE.matches(t.state()))
                        .map(t -> t.id())
                        .collect(Collectors.toSet()),
                "可读明细");
        subset(grant.writeDetails(), grant.readDetails(), "可写明细必须可读");
        subset(
                grant.readRelations(),
                d.relations().stream()
                        .filter(r -> RelationTypeEnum.MANY_TO_MANY.matches(r.kind()))
                        .map(r -> r.id())
                        .collect(Collectors.toSet()),
                "可读多对多关系");
        subset(grant.writeRelations(), grant.readRelations(), "可写关系必须可读");
    }

    private void subset(Set<String> values, Set<String> available, String label) {
        if (values == null || available == null || !available.containsAll(values))
            throw invalid(label + "包含无效项");
    }

    public void within(ObjectGrant member, ObjectGrant ceiling) {
        within(member, ceiling, "应用");
    }

    /** 不同消费主体共用集合上限，条件始终留到运行期独立求交。 */
    public void within(ObjectGrant member, ObjectGrant ceiling, String consumer) {
        if (ceiling == null) throw invalid("对象尚未授权给此" + consumer + "或授权已撤销");
        // 条件上限在运行时独立取交集；成员可添加不同的条件，不用结构相等误拒绝合法收紧。
        if (!ceiling.actions().containsAll(member.actions())
                || !ceiling.readFields().containsAll(member.readFields())
                || !ceiling.writeFields().containsAll(member.writeFields())
                || !ceiling.readDetails().containsAll(member.readDetails())
                || !ceiling.writeDetails().containsAll(member.writeDetails())
                || !ceiling.readRelations().containsAll(member.readRelations())
                || !ceiling.writeRelations().containsAll(member.writeRelations())
                || ApplicationScopeEnum.OWN.matches(ceiling.scope())
                        && !ApplicationScopeEnum.OWN.matches(member.scope()))
            throw invalid("成员授权超出对象授予" + consumer + "的范围");
    }

    public ObjectGrant intersect(ObjectGrant a, ObjectGrant b) {
        Map<String, com.richuang.os.nocode.api.DataScope> scopes = new LinkedHashMap<>();
        for (String action : intersection(a.actions(), b.actions())) {
            DataScope scope =
                    com.richuang.os.nocode.api.DataScope.and(
                            a.actionScopes().get(action), b.actionScopes().get(action));
            if (scope != null) scopes.put(action, scope);
        }
        return new ObjectGrant(
                a.objectId(),
                intersection(a.actions(), b.actions()),
                ApplicationScopeEnum.OWN.matches(a.scope())
                                || ApplicationScopeEnum.OWN.matches(b.scope())
                        ? ApplicationScopeEnum.OWN.getCode()
                        : ApplicationScopeEnum.ALL.getCode(),
                intersection(a.readFields(), b.readFields()),
                intersection(a.writeFields(), b.writeFields()),
                intersection(a.readDetails(), b.readDetails()),
                intersection(a.writeDetails(), b.writeDetails()),
                intersection(a.readRelations(), b.readRelations()),
                intersection(a.writeRelations(), b.writeRelations()),
                scopes,
                intersection(a.computeFields(), b.computeFields()));
    }

    private Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.retainAll(b);
        return Set.copyOf(result);
    }
}
