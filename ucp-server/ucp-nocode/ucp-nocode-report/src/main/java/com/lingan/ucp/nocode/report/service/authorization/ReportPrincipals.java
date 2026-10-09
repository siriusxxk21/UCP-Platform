package com.lingan.ucp.nocode.report.service.authorization;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.datapermission.core.util.DataPermissionUtils;
import com.lingan.ucp.module.system.api.dept.DeptApi;
import com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO;
import com.lingan.ucp.module.system.api.permission.PermissionApi;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.permission.dto.RoleRespDTO;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.enums.ApplicationPrincipalEnum;
import com.lingan.ucp.nocode.enums.ScopeValueSourceEnum;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 只解析 OS 身份，不授予业务权限；独立短事务避开查询事务缓存，便于结果交付前重验。 */
@Component
public class ReportPrincipals {
    @Resource private AdminUserApi users;
    @Resource private DeptApi departments;
    @Resource private RoleApi roles;
    @Resource private PermissionApi permissions;
    @Resource private DraftValidator validator;
    @Resource private PlatformTransactionManager manager;

    /** 记录运行授权涉及的稳定身份，不把昵称等展示属性当成权限修订。 */
    public record Snapshot(
            long actor,
            Long department,
            Set<Long> posts,
            Set<String> principals,
            Map<String, Object> scopeContext) {}

    public Snapshot snapshot(long actor) {
        if (actor <= 0) throw new AccessDeniedException("请先登录");
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
        return transaction.execute(tx -> current(actor));
    }

    /** 管理操作在现有事务读取一次身份，避免持资源锁时再借第二个连接；运行前后使用 snapshot。 */
    public Snapshot current(long actor) {
        if (actor <= 0) throw new AccessDeniedException("请先登录");
        // 仅身份元数据绕过管理端部门过滤；业务记录永远使用数据集授权范围。
        return DataPermissionUtils.executeIgnore(
                () -> {
                    AdminUserRespDTO user = users.getUser(actor);
                    if (user == null || !Integer.valueOf(0).equals(user.getStatus()))
                        throw new AccessDeniedException("当前用户已停用或不存在");
                    Set<String> identities = new TreeSet<>();
                    identities.add(ApplicationPrincipalEnum.USER.getCode() + ":" + actor);
                    Set<Long> assigned = permissions.getUserRoleIds(actor);
                    if (assigned != null && !assigned.isEmpty()) {
                        List<RoleRespDTO> candidates = roles.getRoleList(assigned);
                        if (candidates != null)
                            for (RoleRespDTO role : candidates)
                                if (role != null
                                        && assigned.contains(role.getId())
                                        && Integer.valueOf(0).equals(role.getStatus()))
                                    identities.add(
                                            ApplicationPrincipalEnum.ROLE.getCode()
                                                    + ":"
                                                    + role.getId());
                    }
                    Map<String, Object> scopeContext = new HashMap<>();
                    scopeContext.put(
                            ScopeValueSourceEnum.CURRENT_USER.getCode(), Long.toString(actor));
                    if (user.getDeptId() != null) {
                        DeptRespDTO department = departments.getDept(user.getDeptId());
                        if (department != null
                                && Integer.valueOf(0).equals(department.getStatus())) {
                            scopeContext.put(
                                    ScopeValueSourceEnum.CURRENT_DEPARTMENT.getCode(),
                                    department.getId().toString());
                            Set<String> tree = new TreeSet<>();
                            tree.add(department.getId().toString());
                            List<DeptRespDTO> children =
                                    departments.getChildDeptList(department.getId());
                            if (children != null)
                                children.stream()
                                        .filter(
                                                child ->
                                                        Integer.valueOf(0)
                                                                .equals(child.getStatus()))
                                        .forEach(child -> tree.add(child.getId().toString()));
                            scopeContext.put(
                                    ScopeValueSourceEnum.CURRENT_DEPARTMENT_TREE.getCode(),
                                    List.copyOf(tree));
                        }
                    }
                    return new Snapshot(
                            actor,
                            user.getDeptId(),
                            user.getPostIds() == null ? Set.of() : Set.copyOf(user.getPostIds()),
                            Set.copyOf(identities),
                            Map.copyOf(scopeContext));
                });
    }

    public String validate(String kind, String id) {
        ApplicationPrincipalEnum principal = ApplicationPrincipalEnum.fromCode(kind);
        long value = validator.id(id, "授权成员");
        if (!Long.toString(value).equals(id)) throw invalid("授权成员 ID 必须规范化");
        if (principal == ApplicationPrincipalEnum.USER) users.validateUser(value);
        else roles.validRoleList(List.of(value));
        return principal.getCode() + ":" + value;
    }

    public void unchanged(Snapshot before) {
        if (!before.equals(snapshot(before.actor())))
            throw new AccessDeniedException("用户身份或角色已变化，请重新查询");
    }
}
