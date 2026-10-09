-- 移除已废弃的自定义 codegen 模块。前置 V034；保留 os-module-infra 的通用代码生成器。
-- 清理低代码生成器菜单、角色关联及本模块专属数据表；历史全量快照仅作恢复参考，不回写。
-- 校验：菜单树不存在 /generator，且 gen_*、db_design、sys_dataset 表均不存在。
WITH RECURSIVE generator_menus AS (
    SELECT id
    FROM public.system_menu
    WHERE path = '/generator'
       OR path LIKE '/generator/%'
       OR component LIKE 'generator/%'
    UNION
    SELECT child.id
    FROM public.system_menu child
    INNER JOIN generator_menus parent ON child.parent_id = parent.id
)
DELETE FROM public.system_role_menu
WHERE menu_id IN (SELECT id FROM generator_menus);

WITH RECURSIVE generator_menus AS (
    SELECT id
    FROM public.system_menu
    WHERE path = '/generator'
       OR path LIKE '/generator/%'
       OR component LIKE 'generator/%'
    UNION
    SELECT child.id
    FROM public.system_menu child
    INNER JOIN generator_menus parent ON child.parent_id = parent.id
)
DELETE FROM public.system_menu
WHERE id IN (SELECT id FROM generator_menus);

DROP TABLE IF EXISTS public.db_design,
    public.gen_table_relation,
    public.gen_generate_record,
    public.gen_project_template,
    public.gen_template_version,
    public.gen_field_config,
    public.gen_config,
    public.gen_history,
    public.gen_project,
    public.gen_template,
    public.gen_template_category,
    public.gen_datasource,
    public.sys_dataset CASCADE;

DO $$
DECLARE
    table_name text;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.system_menu
        WHERE path = '/generator'
           OR path LIKE '/generator/%'
           OR component LIKE 'generator/%'
    ) THEN
        RAISE EXCEPTION '自定义 codegen 菜单清理不完整';
    END IF;

    FOREACH table_name IN ARRAY ARRAY[
        'db_design', 'gen_table_relation', 'gen_generate_record', 'gen_project_template',
        'gen_template_version', 'gen_field_config', 'gen_config', 'gen_history',
        'gen_project', 'gen_template', 'gen_template_category', 'gen_datasource', 'sys_dataset'
    ] LOOP
        IF to_regclass('public.' || table_name) IS NOT NULL THEN
            RAISE EXCEPTION '自定义 codegen 表未删除：%', table_name;
        END IF;
    END LOOP;
END $$;
