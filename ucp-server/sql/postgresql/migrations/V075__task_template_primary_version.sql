-- 前置 V074：在统一 dev 序列中引入任务模板主版本，不修改发布快照、实例和草稿。
-- 从未安装该字段的环境新增并回填；已执行旧分支任务 V066 的环境仅验证原结构。
-- 旧分支历史冲突须先按 manual/20261006_reconcile_task_v066.sql 审核处理，禁止自动 repair。
-- 验证：primary_version 可回指历史版本；兼容路径保留所有模板头值（包括 NULL）。
DO $$
BEGIN
    LOCK TABLE public.nocode_task_template IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid='public.nocode_task_template'::regclass
                 AND attname='primary_version' AND NOT attisdropped) THEN
        IF NOT EXISTS (SELECT 1 FROM pg_attribute
                       WHERE attrelid='public.nocode_task_template'::regclass
                         AND attname='primary_version' AND NOT attisdropped
                         AND atttypid='integer'::regtype AND NOT attnotnull AND NOT atthasdef)
           OR NOT EXISTS (
               SELECT 1 FROM pg_constraint
               WHERE conrelid='public.nocode_task_template'::regclass
                 AND conname='nocode_task_template_primary_version_ck'
                 AND contype='c' AND convalidated
                 AND pg_get_constraintdef(oid) = 'CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))'
           ) THEN
            RAISE EXCEPTION 'Unexpected task primary-version structure; inspect before migration';
        END IF;
        -- 不重复回填：用户可能已将默认发起版本切回早期版本。
    ELSE
        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.nocode_task_template'::regclass
                     AND conname='nocode_task_template_primary_version_ck') THEN
            RAISE EXCEPTION 'Unexpected task primary-version constraint without column';
        END IF;
        ALTER TABLE public.nocode_task_template ADD COLUMN primary_version integer;
        UPDATE public.nocode_task_template
        SET primary_version = published_version
        WHERE published_version IS NOT NULL;
        ALTER TABLE public.nocode_task_template
            ADD CONSTRAINT nocode_task_template_primary_version_ck
            CHECK (primary_version IS NULL OR
                   (published_version IS NOT NULL AND primary_version BETWEEN 1 AND published_version));
    END IF;
END $$;

COMMENT ON COLUMN public.nocode_task_template.published_version IS '最新发布序号，仅随新版本发布递增';
COMMENT ON COLUMN public.nocode_task_template.primary_version IS '默认发起版本；切换不修改模板草稿和已有实例';
