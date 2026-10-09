-- 前置 V060。新日/周清单与旧区间排期并存；不转换、归档或删除既有安排。
-- 校验：迁移目录摘要检查、Flyway info/verify，以及日周独立性/旧排期隔离集成测试。
-- 影响仅为计划类型列和约束/索引；不修改任务归属、执行状态、预计或实际日期。
ALTER TABLE public.nocode_task_plan ADD COLUMN plan_mode varchar(16) NOT NULL DEFAULT 'SCHEDULE';
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_mode_ck
    CHECK (plan_mode IN ('SCHEDULE', 'CHECKLIST'));
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_checklist_period_ck
    CHECK (plan_mode != 'CHECKLIST' OR
        (period = 'DAY' AND end_date = plan_date) OR
        (period = 'WEEK' AND extract(isodow FROM plan_date) = 1 AND end_date = plan_date + 6));
DROP INDEX public.nocode_task_plan_active_uk;
CREATE UNIQUE INDEX nocode_task_plan_active_uk
    ON public.nocode_task_plan(task_id,user_id,plan_mode,period,plan_date,end_date,source) WHERE deleted=0;
CREATE INDEX nocode_task_checklist_membership_idx
    ON public.nocode_task_plan(user_id,period,plan_date,task_id)
    WHERE deleted=0 AND plan_mode='CHECKLIST';
