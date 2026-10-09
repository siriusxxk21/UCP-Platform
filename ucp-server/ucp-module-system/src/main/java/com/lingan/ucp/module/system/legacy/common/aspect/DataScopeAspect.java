package com.lingan.ucp.module.system.legacy.common.aspect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingan.ucp.common.annotation.DataScope;
import com.lingan.ucp.common.context.DataScopeContext;
import com.lingan.ucp.module.system.legacy.common.context.UserContext;
import com.lingan.ucp.common.enums.DataScopeType;
import com.lingan.ucp.module.system.legacy.entity.SysRoleDataScope;
import com.lingan.ucp.module.system.legacy.mapper.SysDepartmentMapper;
import com.lingan.ucp.module.system.legacy.mapper.SysRoleDataScopeMapper;
import com.lingan.ucp.module.system.legacy.mapper.SysUserRoleMapper;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据权限切面（已废弃 - v2.0 统一认证迁移，新版不再通过 MyBatis 拦截器注入 SQL 条件）
 * 在方法执行前根据当前用户的数据权限范围构建 SQL 条件片段写入 DataScopeContext，
 * 由 DataScopeInnerInterceptor 在 MyBatis 查询层消费注入
 */
@Aspect
// @Component -- 已废弃，新版数据权限体系不再依赖此切面
@Slf4j
@RequiredArgsConstructor
public class DataScopeAspect {

    /**
     * 数据权限过滤关键字（保留，供 Mapper XML 手动引用）
     */
    public static final String DATA_SCOPE = "${dataScope}";

    private final SysDepartmentMapper departmentMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleDataScopeMapper roleDataScopeMapper;

    @Before("@annotation(dataScope)")
    public void before(JoinPoint point, DataScope dataScope) {
        if (dataScope.ignore()) {
            return;
        }

        // 清除上一次可能残留的数据权限 SQL
        DataScopeContext.clear();

        UserInfoVO userInfo = getLoginUser();
        // 未登录或系统管理员不限制
        if (userInfo == null || isSuperAdmin(userInfo)) {
            return;
        }

        String scopeSql = buildScopeSql(userInfo, dataScope);
        if (scopeSql != null && !scopeSql.isEmpty()) {
            DataScopeContext.setScopeSql(scopeSql);
            log.debug("数据权限SQL: {}", scopeSql);
        }
    }

    @After("@annotation(dataScope)")
    public void after(JoinPoint point, DataScope dataScope) {
        // 方法执行完毕后清除，防止当前线程后续非 @DataScope 查询被误影响
        DataScopeContext.clear();
    }

    // ────────────────────────────────────────────
    // 内部方法
    // ────────────────────────────────────────────

    /**
     * 从 UserContext 获取当前登录用户（由 JwtInterceptor 在请求入口处从 Redis 加载）
     */
    private UserInfoVO getLoginUser() {
        return UserContext.getCurrentUser();
    }

    /**
     * 判断是否为系统管理员（userType = 3），不受数据权限限制
     */
    private boolean isSuperAdmin(UserInfoVO user) {
        return user.getUserType() != null && user.getUserType() == 3;
    }

    /**
     * 根据用户数据权限范围构建 SQL 条件片段
     * 支持多表别名（逗号分隔）场景
     */
    private String buildScopeSql(UserInfoVO user, DataScope annotation) {
        Integer dataScope = user.getDataScope();
        if (dataScope == null) {
            dataScope = DataScopeType.ALL.getCode();
        }

        StringBuilder sql = new StringBuilder();
        String deptAlias = annotation.deptAlias();
        String userAlias = annotation.userAlias();

        String[] deptAliases = deptAlias.isEmpty() ? new String[]{""} : deptAlias.split(",");
        String[] userAliases = userAlias.isEmpty() ? new String[]{""} : userAlias.split(",");

        switch (DataScopeType.getByCode(dataScope)) {
            case ALL:
                return "";

            case DEPT_ONLY:
                if (user.getDeptId() != null) {
                    for (String alias : deptAliases) {
                        sql.append(" AND ").append(buildColumnName(alias.trim(), "dept_id"))
                                .append(" = ").append(user.getDeptId());
                    }
                }
                break;

            case DEPT_AND_CHILD:
                if (user.getDeptId() != null) {
                    Set<String> childIds = getChildDeptIds(user.getDeptId());
                    childIds.add(user.getDeptId());
                    String idStr = childIds.stream().map(String::valueOf).collect(Collectors.joining(","));
                    for (String alias : deptAliases) {
                        sql.append(" AND ").append(buildColumnName(alias.trim(), "dept_id"))
                                .append(" IN (").append(idStr).append(")");
                    }
                }
                break;

            case SELF_ONLY:
                if (user.getId() != null) {
                    for (String alias : userAliases) {
                        sql.append(" AND ").append(buildColumnName(alias.trim(), "create_by"))
                                .append(" = ").append(user.getId());
                    }
                }
                break;

            case CUSTOM:
                Set<String> customIds = getCustomDeptIds(user.getId());
                if (!customIds.isEmpty()) {
                    String idStr = customIds.stream().map(String::valueOf).collect(Collectors.joining(","));
                    for (String alias : deptAliases) {
                        sql.append(" AND ").append(buildColumnName(alias.trim(), "dept_id"))
                                .append(" IN (").append(idStr).append(")");
                    }
                }
                break;

            default:
                return "";
        }

        return sql.toString();
    }

    private String buildColumnName(String alias, String columnName) {
        if (alias == null || alias.isEmpty()) {
            return columnName;
        }
        return alias + "." + columnName;
    }

    /**
     * 获取指定部门的所有子部门 ID（基于 parentIds 路径查询）
     */
    private Set<String> getChildDeptIds(String deptId) {
        com.lingan.ucp.module.system.legacy.entity.SysDepartment dept = departmentMapper.selectById(deptId);
        if (dept == null) {
            return new HashSet<>();
        }
        String parentPath = dept.getParentIds() + "," + dept.getId();
        List<String> childIds = departmentMapper.selectChildIds(parentPath);
        return new HashSet<>(childIds);
    }

    /**
     * 获取用户自定义数据权限的部门 ID 集合
     * 逻辑：用户角色列表 → 角色数据权限配置（dataScope=5/CUSTOM）→ 合并 customDeptIds
     */
    private Set<String> getCustomDeptIds(String userId) {
        List<String> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<SysRoleDataScope> scopes = roleDataScopeMapper.selectList(
                new LambdaQueryWrapper<SysRoleDataScope>()
                        .in(SysRoleDataScope::getRoleId, roleIds)
                        .eq(SysRoleDataScope::getDataScope, DataScopeType.CUSTOM.getCode())
        );

        Set<String> deptIds = new HashSet<>();
        for (SysRoleDataScope scope : scopes) {
            if (scope.getCustomDeptIds() != null && !scope.getCustomDeptIds().isEmpty()) {
                Arrays.stream(scope.getCustomDeptIds().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(deptIds::add);
            }
        }
        return deptIds;
    }
}
