package com.richuang.os.module.drive.service.permission;

import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;

import java.util.function.Supplier;

/**
 * 限定子树：一次调用期间，只在一棵目录子树内生效的临时角色。仅由 DriveFolderApiImpl 设置。
 *
 * <p>绑在当前线程上：只对设置它的那一次同步调用有效，调用结束（含抛异常）即摘除。⛔ 不得在线程池、异步任务里使用—— 换了线程就不再有限定子树，权限判定退回普通规则。
 */
public final class DriveScopes {

    /**
     * 一棵限定子树
     *
     * @param spaceId 子树所在空间
     * @param rootEntryId 子树的根（一个普通目录节点）
     * @param role 子树内的临时角色，只允许可查看或可编辑
     */
    public record Scope(Long spaceId, Long rootEntryId, DrivePermissionRoleEnum role) {
        public Scope {
            if (spaceId == null || rootEntryId == null) {
                throw new IllegalArgumentException("限定子树需要空间编号与根节点编号");
            }
            if (role != DrivePermissionRoleEnum.VIEWER && role != DrivePermissionRoleEnum.EDITOR) {
                throw new IllegalArgumentException("限定子树的角色只能是可查看或可编辑");
            }
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private DriveScopes() {}

    /** 当前线程上的限定子树；没有时返回 null */
    public static Scope current() {
        return CURRENT.get();
    }

    /** 设置后执行，finally 中恢复为之前的值；已有限定子树时再次设置抛 IllegalStateException。 */
    public static <T> T call(Scope scope, Supplier<T> work) {
        if (scope == null) {
            throw new IllegalArgumentException("限定子树不能为空");
        }
        Scope previous = CURRENT.get();
        if (previous != null) {
            throw new IllegalStateException("当前线程已有限定子树，不能嵌套设置");
        }
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            // previous 恒为空（上面已拒绝嵌套）：恢复为之前的值即摘除
            CURRENT.remove();
        }
    }

    public static void run(Scope scope, Runnable work) {
        call(
                scope,
                () -> {
                    work.run();
                    return null;
                });
    }
}
