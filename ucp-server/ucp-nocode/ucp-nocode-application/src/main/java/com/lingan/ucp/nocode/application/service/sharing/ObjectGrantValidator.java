package com.lingan.ucp.nocode.application.service.sharing;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.Selections;
import com.lingan.ucp.nocode.enums.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** 对象共享上限和应用成员共用同一套权限值校验与集合交集语义。 */
@Component
public class ObjectGrantValidator {
    /** 一个对象版本（或几个版本的并集）里可被授权的主表字段、启用明细与多对多关系，保持定义里的顺序。 */
    public record Universe(List<String> fields, List<String> details, List<String> relations) {
        public static Universe of(DataCenter.Definition... definitions) {
            Set<String> fields = new LinkedHashSet<>(),
                    details = new LinkedHashSet<>(),
                    relations = new LinkedHashSet<>();
            for (DataCenter.Definition d : definitions) {
                if (d == null) continue;
                for (FieldDefinition field : d.fields()) {
                    DataCenter.FieldOptions options =
                            d.fieldOptions() == null ? null : d.fieldOptions().get(field.id());
                    if (options == null || !MemberStateEnum.INACTIVE.matches(options.state()))
                        fields.add(field.id());
                }
                for (DataCenter.Detail detail : d.details())
                    if (MemberStateEnum.ACTIVE.matches(detail.state())) details.add(detail.id());
                for (DataCenter.Relation relation : d.relations())
                    if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
                        relations.add(relation.id());
            }
            return new Universe(List.copyOf(fields), List.copyOf(details), List.copyOf(relations));
        }
    }

    /**
     * 字段能不能出现在「可填写和修改字段」里：公式、汇总、自动编号、主键、系统生成列（关系字段除外）写不了。 与授权界面的候选口径相同， 判断「可写清单是不是恰好等于全部」时全集只算这些。
     */
    public static boolean writable(DataCenter.Definition d, String fieldId) {
        FieldDefinition field =
                d.fields().stream().filter(f -> f.id().equals(fieldId)).findFirst().orElse(null);
        if (field == null) return false;
        DataCenter.FieldOptions options =
                d.fieldOptions() == null ? null : d.fieldOptions().get(fieldId);
        return !FieldTypeEnum.FORMULA.matches(field.type())
                && !FieldTypeEnum.SUMMARY.matches(field.type())
                && !FieldTypeEnum.AUTO_NUMBER.matches(field.type())
                && (options == null
                        || !Boolean.TRUE.equals(options.primaryKey())
                                && (!Boolean.TRUE.equals(options.generated())
                                        || d.relations().stream()
                                                .anyMatch(r -> fieldId.equals(r.fieldId()))));
    }

    public void validate(ObjectGrant grant, DataCenter.Definition d) {
        normalize(grant, d, Universe.of(d));
    }

    /**
     * 保存前的校验与规范化；落库的必须是返回值，不是入参。
     *
     * <p>六个清单各自可以是「全部」（{@link Selections#ALL}）或清单。清单里已停用、已不存在的 ID 直接去掉而不报错；可写清单里不在可读清单内的项同样去掉。
     * 计算取数不再由人勾选（由授权现场推出），这里一律置空。操作、记录范围、记录条件的校验不变。
     *
     * @param d 校验记录条件用的对象版本
     * @param clean 清单里的 ID 只要仍在这个全集里就保留（调用方可传多个版本的并集）
     */
    public ObjectGrant normalize(ObjectGrant grant, DataCenter.Definition d, Universe clean) {
        if (grant == null || !d.objectId().equals(grant.objectId())) throw invalid("授权对象不匹配");
        ApplicationScopeEnum.fromCode(grant.scope());
        if (grant.actions() == null
                || !grant.actions().contains(ApplicationActionEnum.READ.getCode()))
            throw invalid("业务授权必须包含查看权限；撤销请使用撤销授权操作");
        grant.actions().forEach(ApplicationActionEnum::fromCode);
        if (!grant.actions().containsAll(grant.actionScopes().keySet()))
            throw invalid("记录条件必须对应已允许操作");
        grant.actionScopes().values().forEach(scope -> scope.validate(d, true));
        Set<String> readFields = cleaned(grant.readFields(), clean.fields(), "可查看字段");
        Set<String> readDetails = cleaned(grant.readDetails(), clean.details(), "可查看内部明细");
        Set<String> readRelations = cleaned(grant.readRelations(), clean.relations(), "可查看多对多关系");
        return new ObjectGrant(
                grant.objectId(),
                grant.actions(),
                grant.scope(),
                readFields,
                writable(grant.writeFields(), readFields, clean.fields(), "可填写和修改字段"),
                readDetails,
                writable(grant.writeDetails(), readDetails, clean.details(), "可修改内部明细"),
                readRelations,
                writable(grant.writeRelations(), readRelations, clean.relations(), "可修改多对多关系"),
                grant.actionScopes(),
                Set.of());
    }

    private Set<String> cleaned(Set<String> values, List<String> universe, String label) {
        if (values == null) throw invalid(label + "包含无效项");
        Selections.requireWellFormed(values, label);
        return Selections.clean(values, universe);
    }

    /** 可写清单：是「全部」⇒ 含义是读到的都可写；是清单 ⇒ 只留仍可读的项。 */
    private Set<String> writable(
            Set<String> values, Set<String> readable, List<String> universe, String label) {
        Set<String> result = cleaned(values, universe, label);
        if (Selections.isAll(result) || Selections.isAll(readable)) return result;
        Set<String> kept = new LinkedHashSet<>(result);
        kept.retainAll(readable);
        return Collections.unmodifiableSet(kept);
    }

    /**
     * 把一份授权对着对象版本展开成普通 ID 集合：结果不含「全部」哨兵，也不含已停用或不存在的 ID。
     *
     * <p>可写的「全部」展开成同一份授权展开后的可读集合，不是对象的全部；这是写权限不越过读权限的唯一保证。只在算出某个人有效授权的最后一步调用。
     */
    public ObjectGrant resolve(ObjectGrant grant, DataCenter.Definition d) {
        Universe universe = Universe.of(d);
        Set<String> readFields = resolved(grant.readFields(), universe.fields());
        Set<String> readDetails = resolved(grant.readDetails(), universe.details());
        Set<String> readRelations = resolved(grant.readRelations(), universe.relations());
        return new ObjectGrant(
                grant.objectId(),
                grant.actions(),
                grant.scope(),
                readFields,
                resolved(grant.writeFields(), List.copyOf(readFields)),
                readDetails,
                resolved(grant.writeDetails(), List.copyOf(readDetails)),
                readRelations,
                resolved(grant.writeRelations(), List.copyOf(readRelations)),
                grant.actionScopes(),
                Set.of());
    }

    private Set<String> resolved(Set<String> values, List<String> universe) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Selections.resolve(values, universe)));
    }

    /**
     * 成员授权不得超出上限。操作与记录范围超出仍然拒绝；六个清单超出上限的项直接去掉并返回收紧后的授权（去掉权限永远朝安全方向）， 任一边是「全部」时不需要收紧——运行时取交集后再展开。
     */
    public ObjectGrant within(ObjectGrant member, ObjectGrant ceiling) {
        if (ceiling == null) throw invalid("对象尚未授权给此应用或授权已撤销");
        // 条件上限在运行时独立取交集；成员可添加不同的条件，不用结构相等误拒绝合法收紧。
        if (!ceiling.actions().containsAll(member.actions())
                || ApplicationScopeEnum.OWN.matches(ceiling.scope())
                        && !ApplicationScopeEnum.OWN.matches(member.scope()))
            throw invalid("成员授权超出对象授予应用的范围");
        Set<String> readFields = tightened(member.readFields(), ceiling.readFields());
        Set<String> readDetails = tightened(member.readDetails(), ceiling.readDetails());
        Set<String> readRelations = tightened(member.readRelations(), ceiling.readRelations());
        return new ObjectGrant(
                member.objectId(),
                member.actions(),
                member.scope(),
                readFields,
                readable(tightened(member.writeFields(), ceiling.writeFields()), readFields),
                readDetails,
                readable(tightened(member.writeDetails(), ceiling.writeDetails()), readDetails),
                readRelations,
                readable(
                        tightened(member.writeRelations(), ceiling.writeRelations()),
                        readRelations),
                member.actionScopes(),
                member.computeFields());
    }

    private Set<String> tightened(Set<String> member, Set<String> ceiling) {
        if (Selections.isAll(member) || Selections.isAll(ceiling)) return member;
        Set<String> kept = new LinkedHashSet<>(member);
        kept.retainAll(ceiling);
        return Collections.unmodifiableSet(kept);
    }

    private Set<String> readable(Set<String> write, Set<String> read) {
        if (Selections.isAll(write) || Selections.isAll(read)) return write;
        Set<String> kept = new LinkedHashSet<>(write);
        kept.retainAll(read);
        return Collections.unmodifiableSet(kept);
    }

    public ObjectGrant intersect(ObjectGrant a, ObjectGrant b) {
        Map<String, com.lingan.ucp.nocode.api.DataScope> scopes = new LinkedHashMap<>();
        for (String action : intersection(a.actions(), b.actions())) {
            var scope =
                    com.lingan.ucp.nocode.api.DataScope.and(
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
                Selections.intersect(a.readFields(), b.readFields()),
                Selections.intersect(a.writeFields(), b.writeFields()),
                Selections.intersect(a.readDetails(), b.readDetails()),
                Selections.intersect(a.writeDetails(), b.writeDetails()),
                Selections.intersect(a.readRelations(), b.readRelations()),
                Selections.intersect(a.writeRelations(), b.writeRelations()),
                scopes,
                Set.of());
    }

    private Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.retainAll(b);
        return Set.copyOf(result);
    }
}
