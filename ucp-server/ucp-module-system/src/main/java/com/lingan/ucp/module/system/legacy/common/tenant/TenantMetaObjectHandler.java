package com.lingan.ucp.module.system.legacy.common.tenant;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lingan.ucp.common.tenant.TenantContext;
import com.lingan.ucp.module.system.legacy.common.context.UserContext;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 字段自动填充处理器
 * <p>
 * 负责以下字段的自动填充：
 * <ul>
 *   <li>createTime - 创建时间，INSERT 时自动填充</li>
 *   <li>updateTime - 更新时间，INSERT/UPDATE 时自动填充</li>
 *   <li>deleted - 逻辑删除标记，INSERT 时默认填充为 0</li>
 *   <li>tenantId - 租户ID，INSERT 时从 TenantContext 获取并填充</li>
 * </ul>
 *
 * <p>使用方式：实体类字段添加对应注解：
 * <ul>
 *   <li>@TableField(fill = FieldFill.INSERT) - 仅插入时填充</li>
 *   <li>@TableField(fill = FieldFill.INSERT_UPDATE) - 插入和更新时都填充</li>
 * </ul>
 *
 * @see TenantContext
 * @see com.baomidou.mybatisplus.annotation.FieldFill
 */
@Slf4j
@Component
public class TenantMetaObjectHandler implements MetaObjectHandler {

    /**
     * INSERT 操作时的字段自动填充
     * <p>
     * 执行时机：MyBatis Plus 执行 INSERT 语句之前
     * <p>
     * 填充字段：
     * <ul>
     *   <li>createTime - 当前时间</li>
     *   <li>updateTime - 当前时间</li>
     *   <li>deleted - 0（未删除）</li>
     *   <li>tenantId - 从 {@link TenantContext} 获取当前租户ID</li>
     * </ul>
     *
     * @param metaObject 元对象，包含实体类字段信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
        fillCreateBy(metaObject);
        fillUpdateBy(metaObject);
        fillTenantId(metaObject);
    }

    /**
     * UPDATE 操作时的字段自动填充
     * <p>
     * 执行时机：MyBatis Plus 执行 UPDATE 语句之前
     * <p>
     * 填充字段：
     * <ul>
     *   <li>updateTime - 当前时间</li>
     * </ul>
     *
     * @param metaObject 元对象，包含实体类字段信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        fillUpdateBy(metaObject);
    }

    /**
     * 填充创建人ID
     * <p>
     * 仅在以下条件全部满足时填充：
     * <ol>
     *   <li>实体类存在 createBy 字段</li>
     *   <li>createBy 字段当前值为空（未手动设置）</li>
     *   <li>{@link UserContext} 中存在当前用户信息</li>
     * </ol>
     *
     * @param metaObject 元对象
     */
    private void fillCreateBy(MetaObject metaObject) {
        if (!metaObject.hasSetter("createBy")) {
            return;
        }
        Object existing = metaObject.getValue("createBy");
        if (existing != null) {
            return;
        }
        UserInfoVO user = UserContext.getCurrentUser();
        if (user != null && user.getId() != null) {
            setFieldValByName("createBy", user.getId(), metaObject);
            if (log.isDebugEnabled()) {
                log.debug("自动填充创建人: {}", user.getId());
            }
        }
    }

    /**
     * 填充更新人ID
     * <p>
     * 仅在以下条件全部满足时填充：
     * <ol>
     *   <li>实体类存在 updateBy 字段</li>
     *   <li>updateBy 字段当前值为空（未手动设置）</li>
     *   <li>{@link UserContext} 中存在当前用户信息</li>
     * </ol>
     *
     * @param metaObject 元对象
     */
    private void fillUpdateBy(MetaObject metaObject) {
        if (!metaObject.hasSetter("updateBy")) {
            return;
        }
        Object existing = metaObject.getValue("updateBy");
        if (existing != null) {
            return;
        }
        UserInfoVO user = UserContext.getCurrentUser();
        if (user != null && user.getId() != null) {
            setFieldValByName("updateBy", user.getId(), metaObject);
            if (log.isDebugEnabled()) {
                log.debug("自动填充更新人: {}", user.getId());
            }
        }
    }

    /**
     * 填充租户ID字段
     * <p>
     * 仅在以下条件全部满足时填充：
     * <ol>
     *   <li>实体类存在 tenantId 字段</li>
     *   <li>tenantId 字段当前值为空（未手动设置）</li>
     *   <li>{@link TenantContext} 中存在有效的租户ID（不为 null 且大于 0）</li>
     * </ol>
     *
     * <p>安全说明：
     * 强制使用 TenantContext 中的租户ID，忽略请求参数中可能伪造的 tenantId 值，
     * 防止跨租户数据篡改攻击。
     *
     * @param metaObject 元对象，包含实体类字段信息
     */
    private void fillTenantId(MetaObject metaObject) {
        // 检查实体类是否有 tenantId 字段
        if (!metaObject.hasSetter("tenantId")) {
            return;
        }

        // 如果 tenantId 已有值，不覆盖（支持手动指定场景，如系统管理员操作）
        Object tenantIdObj = metaObject.getValue("tenantId");
        if (tenantIdObj != null) {
            return;
        }

        // 从上下文获取当前租户ID
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isEmpty()) {
            // 强制使用当前上下文的 tenantId，防止恶意伪造
            setFieldValByName("tenantId", tenantId, metaObject);
            if (log.isDebugEnabled()) {
                log.debug("自动填充租户ID: {}", tenantId);
            }
        }
    }
}
