package com.lingan.ucp.nocode.application.service.authorization;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.application.dal.mapper.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 在底座身份之上定义应用业务权限；实时读取，不随页面发布/回退恢复旧授权。 */
@Service
public class ApplicationAuthorizationService {
    @Resource private ApplicationService applications;
    @Resource private ApplicationMapper appStore;
    @Resource private ObjectDraftMapper designLocks;
    @Resource private ApplicationAccessMapper store;
    @Resource private DataObjectApi objects;
    @Resource private DraftValidator validator;
    @Resource private AdminUserApi users;
    @Resource private RoleApi roles;
    @Resource private PermissionCommonApi permissions;
    @Resource private ObjectMapper json;
    @Resource private ObjectSharingService sharing;
    @Resource private ObjectGrantValidator grantValidator;
    @Resource private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public Policy get(String id) {
        applications.get(id);
        return policy(validator.id(id, "应用"));
    }

    /** 可信运行编排已持应用头锁；只读授权修订，避免读取草稿对象造成锁序提前。 */
    public int revision(String id) {
        return policy(validator.id(id, "应用")).revision();
    }

    private Policy policy(long id) {
        var data = store.policy(id);
        if (data == null) return new Policy(0, List.of());
        try {
            return new Policy(
                    data.getLockVersion(),
                    json.readValue(data.getPolicyJson(), new TypeReference<List<Member>>() {}));
        } catch (java.io.IOException e) {
            throw invalid("应用授权配置无法读取");
        }
    }

    /** 由具备底座应用管理权限的控制器调用；共享应用头写锁和独立授权修订号避免覆盖。 */
    public Policy save(Save request, long actor) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(s -> saveLocked(request, actor));
    }

    private Policy saveLocked(Save request, long actor) {
        if (request == null || actor <= 0) throw invalid("缺少授权配置或操作者");
        // 发布读取先目录锁、后应用头锁；授权保存遵循同一顺序，再取设计锁。
        appStore.automationCatalogLock(false);
        designLocks.lockTableName("nocode-design-write");
        applications.requireDesigner(request.applicationId(), actor);
        long id = validator.id(request.applicationId(), "应用");
        if (appStore.lock(id, true) == null) throw invalid("应用不存在");
        if (policy(id).revision() != request.expectedRevision())
            throw new ServiceException(CONFLICT, "授权已被修改，请刷新重试");
        var members = request.members() == null ? List.<Member>of() : request.members();
        members = validateMembers(request.applicationId(), members);
        try {
            String text = json.writeValueAsString(members);
            if (text.length() > 2_000_000) throw invalid("授权配置过大");
            store.save(id, text, Long.toString(actor));
            return policy(id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("授权配置无法保存");
        }
    }

    /**
     * 应用与任务入口共用身份、对象和共享上限校验；调用方独立校验管理身份与修订号。
     *
     * <p>返回规范化并按上限收紧后的成员列表，调用方必须落库返回值：清单里已停用的 ID 去掉，超出对象授予应用范围的项去掉（操作与记录范围超限仍然拒绝）。
     */
    public List<Member> validateMembers(String applicationId, List<Member> members) {
        if (members == null) throw invalid("缺少成员授权");
        if (members.size() > 200) throw invalid("最多配置 200 个成员或角色");
        // 允许对草稿或当前发布对象授权；运行时仍只认当前发布对象，未来字段不会自动获权。
        var app = applications.get(applicationId);
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        if (app.application().publishedVersion() != null
                && ApplicationStatusEnum.ACTIVE.matches(app.application().status()))
            applications
                    .published(applicationId)
                    .definition()
                    .objects()
                    .forEach(
                            r ->
                                    definitions.put(
                                            r.objectId(),
                                            objects.getVersion(r.objectId(), r.versionNo())
                                                    .definition()));
        // 清理死 ID 用「已发布固定版本 ∪ 草稿固定版本」：草稿先同步到新版本时，仍在运行的旧版本用得到的字段不能被删掉。
        Map<String, DataCenter.Definition> released = new LinkedHashMap<>(definitions);
        app.draft()
                .objects()
                .forEach(
                        r ->
                                definitions.put(
                                        r.objectId(),
                                        objects.getVersion(r.objectId(), r.versionNo())
                                                .definition()));
        Set<String> principals = new HashSet<>();
        List<Member> normalized = new ArrayList<>();
        for (var member : members) {
            if (member == null) throw invalid("成员不能为空");
            var kind = ApplicationPrincipalEnum.fromCode(member.principalKind());
            long principal = validator.id(member.principalId(), "成员");
            if (!principals.add(kind.getCode() + ":" + principal)) throw invalid("成员或角色重复");
            if (kind == ApplicationPrincipalEnum.USER) users.validateUser(principal);
            else roles.validRoleList(List.of(principal));
            if (member.objects() == null || member.objects().size() > 100)
                throw invalid("成员对象授权无效");
            Set<String> granted = new HashSet<>();
            List<ObjectGrant> objectGrants = new ArrayList<>();
            for (var grant : member.objects()) {
                if (grant == null || !granted.add(grant.objectId())) throw invalid("对象授权为空或重复");
                var d = definitions.get(grant.objectId());
                if (d == null) throw invalid("授权对象必须先引用到应用");
                ObjectGrant cleaned =
                        grantValidator.normalize(
                                grant,
                                d,
                                ObjectGrantValidator.Universe.of(
                                        d, released.get(grant.objectId())));
                if (!grant.computeFields().isEmpty()) throw invalid("计算取数授权由数据中心授予应用，不能授予成员");
                objectGrants.add(
                        grantValidator.within(
                                cleaned,
                                sharing.storedPermission(grant.objectId(), applicationId)));
            }
            normalized.add(new Member(member.principalKind(), member.principalId(), objectGrants));
        }
        return List.copyOf(normalized);
    }

    /** 只读作用域内同一事务（或同一请求的无事务段）对同一应用的成员授权只读取一次，对同一对象、操作者只解析一次。 */
    public List<ObjectGrant> grants(String app, String object, long actor) {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("authorization.grants", app, object, actor),
                () -> grants(members(app), object, actor));
    }

    private List<Member> members(String app) {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("authorization.members", app),
                () -> policy(validator.id(app, "应用")).members());
    }

    /** 只合并调用方指定授权集合；入口不能意外混入其他入口的成员权限。 */
    public List<ObjectGrant> grants(List<Member> members, String object, long actor) {
        if (actor <= 0) throw invalid("请先登录");
        List<ObjectGrant> result = new ArrayList<>();
        for (var member : members) {
            boolean matched;
            if (ApplicationPrincipalEnum.USER.matches(member.principalKind()))
                matched = Long.toString(actor).equals(member.principalId());
            else {
                var role = roles.getRole(validator.id(member.principalId(), "角色"));
                matched =
                        role != null
                                && Integer.valueOf(0).equals(role.getStatus())
                                && permissions.hasAnyRoles(actor, role.getCode());
            }
            if (matched)
                member.objects().stream()
                        .filter(g -> object == null || object.equals(g.objectId()))
                        .forEach(result::add);
        }
        return List.copyOf(result);
    }
}
