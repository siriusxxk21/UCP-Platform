-- 统一任务计划：保留历史引用、范围日期与独立并发修订。前置 V057；不改业务记录。
ALTER TABLE public.nocode_task_instance ADD COLUMN schedule_version integer NOT NULL DEFAULT 0;
ALTER TABLE public.nocode_task_plan ADD COLUMN end_date date;
ALTER TABLE public.nocode_task_plan ADD COLUMN history_reason varchar(80);
UPDATE public.nocode_task_plan SET end_date = CASE period
    WHEN 'WEEK' THEN plan_date + 6
    WHEN 'MONTH' THEN (plan_date + INTERVAL '1 month' - INTERVAL '1 day')::date
    ELSE plan_date END;
ALTER TABLE public.nocode_task_plan ALTER COLUMN end_date SET NOT NULL;
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_range_ck CHECK (end_date >= plan_date);
-- 旧唯一约束包含已删除记录，会覆盖改期/撤销历史；改为仅限制有效相同来源安排。
ALTER TABLE public.nocode_task_plan DROP CONSTRAINT nocode_task_plan_task_id_user_id_period_plan_date_key;
CREATE UNIQUE INDEX nocode_task_plan_active_uk
    ON public.nocode_task_plan(task_id,user_id,period,plan_date,end_date,source) WHERE deleted=0;
CREATE INDEX nocode_task_plan_range_idx
    ON public.nocode_task_plan(user_id,plan_date,end_date) WHERE deleted=0;
