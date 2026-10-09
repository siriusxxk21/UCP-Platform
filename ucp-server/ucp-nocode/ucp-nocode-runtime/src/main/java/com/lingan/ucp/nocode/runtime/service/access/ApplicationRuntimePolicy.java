package com.lingan.ucp.nocode.runtime.service.access;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.datapermission.core.util.DataPermissionUtils;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 运行授权与设计权限分离，逐记录合并授权，禁止把不同范围的操作交叉放大。 */
@Service
public class ApplicationRuntimePolicy {
    @Resource private DataObjectApi maintenanceObjects;
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ApplicationService applications;
    @Resource private ApplicationAuthorizationService authorization;
    @Resource private ObjectSharingService sharing;
    @Resource private ObjectGrantValidator validator;
    @Resource private ScopeConditions scopes;
    @Resource private TaskEntryRuntimeScope taskScope;
    @Resource private com.lingan.ucp.module.system.api.user.AdminUserApi users;
    @Resource private com.lingan.ucp.module.system.api.dept.DeptApi departments;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog
            automations;

    /**
     * 创建人的管理身份只免除应用内成员登记，不免除对象授予应用的上限。
     *
     * <p>返回的每条授权都已对着 d 展开：清单里不含「全部」哨兵，也不含已停用的 ID，下游按普通 ID 集合使用。
     */
    public List<ObjectGrant> effectiveGrants(String app, DataCenter.Definition d, long actor) {
        List<ObjectGrant> stored = storedGrants(app, d.objectId(), actor);
        // 数据联动自动更新的系统窄授权本身就是显式集合：按对象当前发布版列出，可能比应用固定的版本 d 多出字段，
        // 也把明细字段列在可读字段里。原样返回，不按 d 再裁，否则会把它声明可写的字段裁掉。
        if (linkageWrite(app, d.objectId(), actor)) return stored;
        return stored.stream().map(g -> validator.resolve(g, d)).toList();
    }

    /** storedGrants 走的是不是「数据联动自动更新」或「按日期自动执行」那一支；判断条件与它进入那一支之前的三个分支一致。 */
    private boolean linkageWrite(String app, String object, long actor) {
        return actor > 0
                && app != null
                && !com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope
                        .permits(app, actor)
                && (com.lingan.ucp.nocode.runtime.service.record.LinkageWriteScope.target(
                                        app, object)
                                != null
                        || com.lingan.ucp.nocode.runtime.service.record.DateTriggerWriteScope
                                        .target(app, object)
                                != null);
    }

    /** 未展开的有效授权：只能用来判断操作、记录范围与记录条件，不能读六个清单。维护入口那一支本身就是显式集合。 */
    private List<ObjectGrant> storedGrants(String app, String object, long actor) {
        if (actor <= 0) return List.of();
        if (com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                app, actor))
            return List.of(
                    com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope.grant(
                            maintenanceObjects.getPublished(object)));
        if (app == null) return List.of();
        // 系统写数据联动的自动更新字段：只对 Scope 的应用与目标对象给一条窄授权（读全部、只能改声明的字段），
        // 不看操作者自己的授权，也不受任务入口声明的对象范围限制，所以放在任务入口分支之前。
        var linkage =
                com.lingan.ucp.nocode.runtime.service.record.LinkageWriteScope.target(app, object);
        if (linkage != null) return List.of(linkageGrant(object, linkage.writable()));
        // 按日期自动执行以系统身份写目标：同一形状的窄授权（读全部、只能改规则赋值的字段），不看操作者自己的授权。
        var dated =
                com.lingan.ucp.nocode.runtime.service.record.DateTriggerWriteScope.target(
                        app, object);
        if (dated != null) return List.of(linkageGrant(object, dated.writable()));
        var ceiling = sharing.storedPermission(object, app);
        if (ceiling == null) return List.of();
        var entry = taskScope.current();
        if (entry != null) {
            if (entry.actor() != actor || !entry.applicationId().equals(app)) return List.of();
            // 仅发布规则内部回写：声明字段与应用共享上限相交，不混入员工普通办理授权。
            com.lingan.ucp.nocode.runtime.service.record.AutomationWriteScope.Context automation =
                    com.lingan.ucp.nocode.runtime.service.record.AutomationWriteScope.taskTarget(
                            app, object);
            if (automation != null && entry.data() != null && entry.data().writable()) {
                // 规则可维护表单未暴露的计算字段，但不能绕过目标办理项的视图筛选及操作限制。
                return entry.grants().getOrDefault(object, List.of()).stream()
                        .filter(g -> g.actions().contains("UPDATE"))
                        .map(
                                g ->
                                        new ObjectGrant(
                                                object,
                                                Set.of("READ", "UPDATE"),
                                                g.scope(),
                                                ceiling.readFields(),
                                                automation.fields(),
                                                Set.of(),
                                                Set.of(),
                                                Set.of(),
                                                Set.of(),
                                                g.actionScopes(),
                                                Set.of()))
                        .map(g -> validator.intersect(g, ceiling))
                        .toList();
            }
            return entry.grants().getOrDefault(object, List.of()).stream()
                    .map(g -> validator.intersect(g, ceiling))
                    .filter(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()))
                    .toList();
        }
        if (owner(app, actor)) return List.of(ceiling);
        return authorization.grants(app, object, actor).stream()
                .map(g -> validator.intersect(g, ceiling))
                .filter(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()))
                .toList();
    }

    /**
     * 只判断「能不能看、记录范围、查看有没有记录条件」的调用方用（实时推送的订阅判断）：按对象 ID 取未展开的有效授权，不需要对象定义。
     *
     * <p>⛔ 返回值里的六个清单可能还是「全部」哨兵，不能当字段 / 明细 / 关系的 ID 集合读；要读清单请用带对象定义的 {@link #effectiveGrants(String,
     * DataCenter.Definition, long)}。
     */
    public List<ObjectGrant> scopeGrants(String app, String object, long actor) {
        return storedGrants(app, object, actor);
    }

    public boolean canRead(String app, String object, long actor) {
        return storedGrants(app, object, actor).stream()
                .anyMatch(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()));
    }

    public void requireEntry(String applicationId, long actor) {
        if (com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                applicationId, actor)) return;
        if (applicationId == null || applicationId.isBlank()) throw invalid("缺少应用上下文");
        // 数据联动自动更新是系统写入：回填由应用设计者发起，他不一定是运行成员；任务入口里触发时应用就是入口的应用。
        if (com.lingan.ucp.nocode.runtime.service.record.LinkageWriteScope.permitsEntry(
                        applicationId)
                || com.lingan.ucp.nocode.runtime.service.record.DateTriggerWriteScope.permitsEntry(
                        applicationId)) return;
        var entry = taskScope.current();
        if (entry != null) {
            if (entry.actor() != actor || !entry.applicationId().equals(applicationId))
                throw invalid("当前任务入口不能访问此应用");
            return;
        }
        if (actor <= 0
                || !owner(applicationId, actor)
                        && authorization.grants(applicationId, null, actor).isEmpty())
            throw invalid("没有此应用的运行权限");
    }

    /**
     * 是不是应用创建人：要读应用头（共享行锁）。调用方之后都会读发布快照（目录共享锁）、再读对象头；发布应用是「目录独占锁 → 应用头」， 发布对象是「目录独占锁 → 对象头 →
     * 应用头」（暂停应用 / 自动跟随）。所以已经在事务里时先把目录共享锁取了，与它们同一个顺序，
     * 否则会握着应用头去等目录锁、对方握着目录锁等应用头。不在事务里时锁留不住，不用取；只读作用域内同一事务只取一次（与读发布快照记的是同一把）。
     */
    private boolean owner(String app, long actor) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) automations.lock(false);
        return applications.isOwner(app, actor);
    }

    /**
     * 系统只读授权：READ、范围为全部数据、主表字段全部可读，不含任何写操作。只给开启了「来源变化时自动更新」的数据联动读来源对象用 （按全部数据计算，不随操作者可见范围）；⛔
     * 不要用于其它读取。
     */
    public Access systemRead(DataCenter.Definition d, long actor) {
        Set<String> fields = new HashSet<>();
        BusinessFields.fields(d).forEach(f -> fields.add(f.id()));
        Set<String> relations = new HashSet<>();
        d.relations().forEach(r -> relations.add(r.id()));
        return new Access(
                actor,
                d,
                List.of(
                        new ObjectGrant(
                                d.objectId(),
                                Set.of(ApplicationActionEnum.READ.getCode()),
                                ApplicationScopeEnum.ALL.getCode(),
                                fields,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                relations,
                                Set.of(),
                                Map.of(),
                                Set.of())));
    }

    /** 系统写联动字段的窄授权：读、改，范围为全部数据，全部字段可读，可写字段只有给定的那些；明细与关系只读。 */
    private ObjectGrant linkageGrant(String object, Set<String> writable) {
        var d = maintenanceObjects.getPublished(object);
        Set<String> fields = new HashSet<>();
        BusinessFields.fields(d).forEach(f -> fields.add(f.id()));
        Set<String> details = new HashSet<>();
        for (var detail : d.details())
            if (MemberStateEnum.ACTIVE.matches(detail.state())) {
                details.add(detail.id());
                detail.fields().forEach(f -> fields.add(f.id()));
            }
        // 应用固定的版本可能比当前发布版多出字段：声明可写的字段一并算可读，否则会被「可写须可读」裁掉。
        fields.addAll(writable);
        Set<String> relations = new HashSet<>();
        d.relations().forEach(r -> relations.add(r.id()));
        return new ObjectGrant(
                object,
                Set.of(
                        ApplicationActionEnum.READ.getCode(),
                        ApplicationActionEnum.UPDATE.getCode()),
                ApplicationScopeEnum.ALL.getCode(),
                fields,
                Set.copyOf(writable),
                details,
                Set.of(),
                relations,
                Set.of(),
                Map.of(),
                Set.of());
    }

    public Access access(String app, DataCenter.Definition d, long actor) {
        if (actor <= 0) throw invalid("请先登录");
        var grants = effectiveGrants(app, d, actor);
        var access = new Access(actor, d, grants, scopeContext(grants, actor));
        if (!access.any(ApplicationActionEnum.READ)) throw invalid("没有此对象的查看权限");
        return access;
    }

    /**
     * 成员在目标对象上已经有显式授权时，引用里是否仍并上「只看名称」的隐式授权（契约 15.5 的并集，现状）。
     *
     * <p>改成 false = 有显式授权的成员以显式授权为准（含更窄的记录范围，例如「只看本人创建的」时引用里也只选得到本人创建的），
     * 只有在目标对象上没有任何显式授权的成员才得到隐式的名称授权。这一条待业务方确认，改动只有这一个常量；对应用例
     * RuntimeIntegrationTest.relationAuthorizationIsExplicitAndTargetReadScopeCannotBeBypassed
     * 的第一段断言要随之换回「被拒」。
     */
    private static final boolean IMPLIED_NAMES_ALONGSIDE_EXPLICIT = true;

    /**
     * 给人看的引用上下文（引用候选、名称回显、保存时核对所选记录）里，这个人对关系目标对象的访问权。两部分的并集：
     *
     * <ul>
     *   <li>隐式授权（应用对目标对象的有效上限非空时才有）：操作只有查看；记录范围与查看条件取自有效上限；可查看字段 = 名称相关字段 ∩ 上限的可查看字段。名称相关字段 =
     *       关系字段上配的显示名字段 ∪ 目标对象的标题字段 / 标题模板用到的字段 ∪ 调用方给的匹配字段（表单为这个字段配的联动匹配字段）。 没有明细、没有关联、没有任何写。
     *   <li>这个人在目标对象上已有的显式授权（目标被显式引用且成员有授权时），普通应用运行原样并进来。任务办理只保留其名称／匹配字段和只读操作，
     *       并保留显式授权的记录条件；有显式任务资源时不再并入更宽的隐式授权。
     * </ul>
     *
     * <p>成员不需要被授予目标对象的任何权限：只要能读本对象上的这个关系字段，就能看到目标记录的名称并选择。字段范围取名称相关字段而不是全部字段，
     * 是因为候选的关键词搜索是在这个人可读的全部文本字段上做的——给了全部字段，成员就能靠搜索试探出目标对象任何文本字段里有没有某个词。 前提：调用方已确认这个人能读 owner
     * 上的这个关系字段。自动补齐的任务依赖引用不要求候选已在本组提交，但仍遵守对象共享和调用方追加的视图／引用筛选； 显式任务资源继续保留其行条件与 GROUP
     * 记录集合，普通任务列表、详情及写操作也不扩大范围。
     *
     * @param implied 目标对象是不是本应用隐式可读的；null 表示按应用的已发布版本判断（运行期），设计预览按草稿引用给出
     */
    public Access referenceAccess(
            String app,
            DataCenter.Definition owner,
            DataCenter.Relation relation,
            DataCenter.Definition target,
            long actor,
            Set<String> matchFieldIds,
            java.util.function.BooleanSupplier implied) {
        if (actor <= 0) throw invalid("请先登录");
        if (com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                app, actor)) return access(app, target, actor);
        boolean taskReference = taskScope.delegated();
        if (taskReference) {
            TaskEntryRuntimeScope.Invocation invocation = taskScope.current();
            if (invocation.actor() != actor || !Objects.equals(invocation.applicationId(), app))
                throw invalid("当前任务入口不能访问此应用");
            if (relation == null
                    || !Objects.equals(relation.targetObjectId(), target.objectId())
                    || !owner.relations().contains(relation)) throw invalid("引用来源不属于当前任务表单");
        }
        Set<String> names = new LinkedHashSet<>(RecordTitles.fieldIds(target));
        String label =
                com.lingan.ucp.nocode.runtime.service.rules.FieldRuleLabels.labelFieldId(
                        owner, relation);
        if (label != null) names.add(label);
        if (matchFieldIds != null) names.addAll(matchFieldIds);
        List<ObjectGrant> grants = new ArrayList<>(effectiveGrants(app, target, actor));
        // 引用候选不是办理数据：仅补齐名称和联动匹配读取，不能沿辅助对象的 CRUD 授权读取其它字段。
        // 已显式配置的目标资源仍保留其视图／共享行条件，不能再并入更宽的隐式上限。
        if (taskReference) grants.replaceAll(grant -> referenceGrant(grant, names));
        ObjectGrant ceiling =
                app == null
                        ? null
                        : implied == null
                                ? sharing.ceiling(app, target)
                                : sharing.ceiling(app, target, implied);
        if (ceiling != null
                && ceiling.actions().contains(ApplicationActionEnum.READ.getCode())
                && (!taskReference
                        || !taskScope
                                .current()
                                .data()
                                .configuredObjects()
                                .contains(target.objectId()))
                && (IMPLIED_NAMES_ALONGSIDE_EXPLICIT && !taskReference || grants.isEmpty())) {
            names.retainAll(validator.resolve(ceiling, target).readFields());
            DataScope condition = ceiling.actionScopes().get(ApplicationActionEnum.READ.getCode());
            grants.add(
                    new ObjectGrant(
                            target.objectId(),
                            Set.of(ApplicationActionEnum.READ.getCode()),
                            ceiling.scope(),
                            Set.copyOf(names),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            condition == null
                                    ? Map.of()
                                    : Map.of(ApplicationActionEnum.READ.getCode(), condition),
                            Set.of()));
        }
        // 一条授权都没有（目标既不是隐式可读、成员也没有它的授权，或数据管理员撤销了授权）时不抛错：
        // 候选为空、名称回显成「已失效或无权限的引用」、保存时核对所选记录不通过，由各调用点按空授权处理。
        return new Access(actor, target, grants, scopeContext(grants, actor), taskReference);
    }

    /** 依赖选择只给当前已发布关系必需字段，写能力、明细和递归关系能力均不随引用传递。 */
    private ObjectGrant referenceGrant(ObjectGrant grant, Set<String> names) {
        Set<String> readable = new LinkedHashSet<>(grant.readFields());
        readable.retainAll(names);
        DataScope condition = grant.actionScopes().get(ApplicationActionEnum.READ.getCode());
        return new ObjectGrant(
                grant.objectId(),
                Set.of(ApplicationActionEnum.READ.getCode()),
                grant.scope(),
                Set.copyOf(readable),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                condition == null
                        ? Map.of()
                        : Map.of(ApplicationActionEnum.READ.getCode(), condition),
                Set.of());
    }

    /** 记录条件里用到「当前部门」时才解析成员的部门身份。 */
    private Map<String, Object> scopeContext(List<ObjectGrant> grants, long actor) {
        Map<String, Object> context = new HashMap<>();
        context.put(ScopeValueSourceEnum.CURRENT_USER.getCode(), Long.toString(actor));
        if (grants.stream()
                .flatMap(g -> g.actionScopes().values().stream())
                .anyMatch(this::usesDepartment)) {
            var user = users.getUser(actor);
            if (user != null && user.getDeptId() != null) {
                // 解析当前成员的身份依据，不借用系统管理角色的部门可见范围。
                // 仅此处的组织元数据读取忽略管理端过滤；业务记录仍由下方授权交集限制。
                var department =
                        DataPermissionUtils.executeIgnore(
                                () -> departments.getDept(user.getDeptId()));
                if (department != null && Integer.valueOf(0).equals(department.getStatus())) {
                    context.put(
                            ScopeValueSourceEnum.CURRENT_DEPARTMENT.getCode(),
                            user.getDeptId().toString());
                    var ids = new LinkedHashSet<String>();
                    ids.add(user.getDeptId().toString());
                    var children =
                            DataPermissionUtils.executeIgnore(
                                    () -> departments.getChildDeptList(user.getDeptId()));
                    if (children != null)
                        children.stream()
                                .filter(v -> Integer.valueOf(0).equals(v.getStatus()))
                                .forEach(v -> ids.add(v.getId().toString()));
                    context.put(
                            ScopeValueSourceEnum.CURRENT_DEPARTMENT_TREE.getCode(),
                            List.copyOf(ids));
                }
            }
        }
        return Map.copyOf(context);
    }

    private boolean usesDepartment(DataScope scope) {
        return scope.conditions().stream()
                        .anyMatch(
                                c ->
                                        Set.of(
                                                        ScopeValueSourceEnum.CURRENT_DEPARTMENT,
                                                        ScopeValueSourceEnum
                                                                .CURRENT_DEPARTMENT_TREE)
                                                .contains(
                                                        ScopeValueSourceEnum.fromCode(
                                                                c.valueSource())))
                || scope.groups().stream().anyMatch(this::usesDepartment);
    }

    /** 计数、分页、引用和统计先在数据库限制记录，不能先分页再丢弃越权行。 */
    public com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> conditions(
            Access access,
            RuntimeSchema.Table table,
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where,
            ApplicationActionEnum action,
            String alias) {
        if (where == null) {
            where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
        }
        appendAction(access, table, where, ApplicationActionEnum.READ, alias);
        if (action != ApplicationActionEnum.READ) appendAction(access, table, where, action, alias);
        // 引用名称来自依赖对象的候选范围，不要求候选预先作为本组办理记录提交。
        // 只有服务端 referenceAccess 构建的只读能力可走此分支，普通查询与所有写入仍受本组范围限制。
        Set<String> taskRecords =
                taskScope.recordIds(
                        access.definition().objectId(),
                        access.taskReference() && action == ApplicationActionEnum.READ);
        if (taskRecords != null) {
            if (taskRecords.isEmpty()) where.apply(sqlFragments.alwaysFalse());
            else where.in(sqlFragments.column(alias, table.key().name(), true), taskRecords);
        }
        return where;
    }

    private void appendAction(
            Access access,
            RuntimeSchema.Table table,
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where,
            ApplicationActionEnum action,
            String alias) {
        where.nested(
                group -> {
                    var grants =
                            access.grants().stream()
                                    .filter(g -> g.actions().contains(action.getCode()))
                                    .toList();
                    if (grants.isEmpty()) {
                        group.apply(sqlFragments.alwaysFalse());
                        return;
                    }
                    boolean first = true;
                    for (var grant : grants) {
                        if (!first) group.or();
                        first = false;
                        group.nested(
                                rule -> {
                                    if (ApplicationScopeEnum.OWN.matches(grant.scope()))
                                        rule.eq(
                                                sqlFragments.creator(alias),
                                                Long.toString(access.actor()));
                                    var scope = grant.actionScopes().get(action.getCode());
                                    if (scope != null) {
                                        scope.validateEffective(access.definition());
                                        scopes.append(
                                                rule,
                                                scope,
                                                access.definition(),
                                                table,
                                                access.context(),
                                                alias);
                                    } else if (ApplicationScopeEnum.ALL.matches(grant.scope()))
                                        rule.apply(sqlFragments.alwaysTrue());
                                });
                    }
                });
    }

    /** OWN 范围以数据库 creator 为准。隐藏字段既不返回，也不能参与客户端筛选或排序。 */
    public record Access(
            long actor,
            DataCenter.Definition definition,
            List<ObjectGrant> grants,
            Map<String, Object> context,
            boolean taskReference) {
        public Access(
                long actor,
                DataCenter.Definition definition,
                List<ObjectGrant> grants,
                Map<String, Object> context) {
            this(actor, definition, grants, context, false);
        }

        public Access(long actor, DataCenter.Definition definition, List<ObjectGrant> grants) {
            this(
                    actor,
                    definition,
                    grants,
                    Map.of(ScopeValueSourceEnum.CURRENT_USER.getCode(), Long.toString(actor)));
        }

        public boolean any(ApplicationActionEnum action) {
            return grants.stream().anyMatch(g -> g.actions().contains(action.getCode()));
        }

        public boolean all(ApplicationActionEnum action) {
            return grants.stream()
                    .anyMatch(
                            g ->
                                    ApplicationScopeEnum.ALL.matches(g.scope())
                                            && g.actions().contains(action.getCode()));
        }

        public String creatorFilter(ApplicationActionEnum action) {
            return all(action) ? null : Long.toString(actor);
        }

        public Capabilities forRow(String creator) {
            return forRow(creator, null);
        }

        private boolean allows(
                ObjectGrant grant, String creator, Map<String, Object> values, String action) {
            if (!grant.actions().contains(action)
                    || ApplicationScopeEnum.OWN.matches(grant.scope())
                            && !Long.toString(actor).equals(creator)) return false;
            var scope = grant.actionScopes().get(action);
            return values == null || scope == null || scope.matches(definition, values, context);
        }

        public Capabilities forRow(String creator, Map<String, Object> values) {
            Set<String> actions = new HashSet<>(),
                    read = new HashSet<>(),
                    write = new HashSet<>(),
                    readDetails = new HashSet<>(),
                    writeDetails = new HashSet<>(),
                    readRelations = new HashSet<>(),
                    writeRelations = new HashSet<>();
            for (var g : grants) {
                if (ApplicationScopeEnum.OWN.matches(g.scope())
                        && !Long.toString(actor).equals(creator)) continue;
                g.actions().stream()
                        .filter(action -> allows(g, creator, values, action))
                        .forEach(actions::add);
                if (allows(g, creator, values, ApplicationActionEnum.READ.getCode())) {
                    read.addAll(g.readFields());
                    readDetails.addAll(g.readDetails());
                    readRelations.addAll(g.readRelations());
                }
                // 可写字段必须来自对当前记录实际拥有写操作的同一条授权。
                if (allows(g, creator, values, ApplicationActionEnum.UPDATE.getCode())
                        || allows(g, creator, values, ApplicationActionEnum.CREATE.getCode())) {
                    write.addAll(g.writeFields());
                    writeDetails.addAll(g.writeDetails());
                    writeRelations.addAll(g.writeRelations());
                }
            }
            return new Capabilities(
                    actions, read, write, readDetails, writeDetails, readRelations, writeRelations);
        }

        public Capabilities require(String creator, ApplicationActionEnum action) {
            return require(creator, null, action);
        }

        public Capabilities require(
                String creator, Map<String, Object> values, ApplicationActionEnum action) {
            var result = forRow(creator, values);
            if (!result.actions().contains(ApplicationActionEnum.READ.getCode())
                    || !result.actions().contains(action.getCode()))
                throw invalid("没有此记录的" + action.getCode() + "权限");
            Set<String> writable = new HashSet<>(),
                    detailWritable = new HashSet<>(),
                    relationWritable = new HashSet<>();
            for (var g : grants)
                if (allows(g, creator, values, action.getCode())) {
                    writable.addAll(g.writeFields());
                    detailWritable.addAll(g.writeDetails());
                    relationWritable.addAll(g.writeRelations());
                }
            writable.retainAll(result.readFields());
            detailWritable.retainAll(result.readDetails());
            relationWritable.retainAll(result.readRelations());
            return new Capabilities(
                    result.actions(),
                    result.readFields(),
                    writable,
                    result.readDetails(),
                    detailWritable,
                    result.readRelations(),
                    relationWritable);
        }

        public Set<String> queryRelations() {
            if (grants.stream().anyMatch(g -> !g.actionScopes().isEmpty()))
                return intersection(true);
            return forRow(all(ApplicationActionEnum.READ) ? null : Long.toString(actor))
                    .readRelations();
        }

        public Set<String> queryFields() {
            if (grants.stream().anyMatch(g -> !g.actionScopes().isEmpty()))
                return intersection(false);
            return forRow(all(ApplicationActionEnum.READ) ? null : Long.toString(actor))
                    .readFields();
        }

        /** 子表汇总和存在性筛选不能利用只对部分记录开放的明细推断隐藏数据。 */
        public Set<String> queryDetails() {
            if (grants.stream().noneMatch(g -> !g.actionScopes().isEmpty()))
                return forRow(all(ApplicationActionEnum.READ) ? null : Long.toString(actor))
                        .readDetails();
            Set<String> result = null;
            for (var grant : grants)
                if (grant.actions().contains(ApplicationActionEnum.READ.getCode())) {
                    if (result == null) result = new HashSet<>(grant.readDetails());
                    else result.retainAll(grant.readDetails());
                }
            return result == null ? Set.of() : result;
        }

        private Set<String> intersection(boolean relations) {
            Set<String> fields = null;
            for (var g : grants)
                if (g.actions().contains(ApplicationActionEnum.READ.getCode())) {
                    var current = relations ? g.readRelations() : g.readFields();
                    if (fields == null) fields = new HashSet<>(current);
                    else fields.retainAll(current);
                }
            return fields == null ? Set.of() : fields;
        }
    }
}
