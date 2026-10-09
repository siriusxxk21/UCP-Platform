-- 恢复本机 devops 数据库中 admin（用户 1、租户 1）的超级管理员角色关联。
-- 原因：system_users 和 system_role 均正常，但 system_user_role 中缺少 admin 的关联。
-- super_admin 在 PermissionServiceImpl 中自动获得全部菜单，无需逐项写入 system_role_menu。
-- 整个 DO 块原子执行；已存在有效关联时不重复插入，也不改动其他账号的授权。
DO $$
DECLARE
    binding_id bigint;
BEGIN
    IF current_database() <> 'devops' THEN
        RAISE EXCEPTION '此修复仅适用于已核对的 devops 数据库';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.system_users
        WHERE id = 1 AND username = 'admin' AND tenant_id = 1 AND status = 0 AND deleted = 0
    ) THEN
        RAISE EXCEPTION 'admin 用户状态与已核对的修复前提不一致';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.system_role
        WHERE id = 1 AND code = 'super_admin' AND tenant_id = 1 AND status = 0 AND deleted = 0
    ) THEN
        RAISE EXCEPTION '超级管理员角色状态与已核对的修复前提不一致';
    END IF;

    -- 防止修复与后台授权同时插入重复关联；事务结束即释放锁。
    PERFORM set_config('lock_timeout', '5s', true);
    LOCK TABLE public.system_user_role IN SHARE ROW EXCLUSIVE MODE;
    IF NOT EXISTS (
        SELECT 1 FROM public.system_user_role
        WHERE user_id = 1 AND role_id = 1 AND deleted = 0
    ) THEN
        -- 兼容历史导入数据未同步序列的情况，只跳过已占用的编号。
        LOOP
            binding_id := nextval('public.system_user_role_seq');
            EXIT WHEN NOT EXISTS (SELECT 1 FROM public.system_user_role WHERE id = binding_id);
        END LOOP;
        INSERT INTO public.system_user_role
            (id, user_id, role_id, creator, create_time, updater, update_time, deleted, tenant_id)
        VALUES
            (binding_id, 1, 1, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, 0, 1);
    END IF;
END $$;

-- 验证结果应为 admin -> super_admin，且用户、角色、关联均有效。
SELECT u.id AS user_id, u.username, r.id AS role_id, r.code AS role_code,
       ur.id AS binding_id, ur.tenant_id
FROM public.system_users u
JOIN public.system_user_role ur ON ur.user_id = u.id AND ur.deleted = 0
JOIN public.system_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 0
WHERE u.id = 1 AND u.username = 'admin' AND u.deleted = 0;

-- SQL 之外需要失效该用户的角色缓存：Redis 当前配置库中的 user_role_ids:1。
-- 然后退出并重新登录，以刷新前端权限及登录时生成的用户信息。
