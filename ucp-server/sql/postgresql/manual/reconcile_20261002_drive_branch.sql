-- 仅用于 ec7b2f53 网盘分支已执行 V001-V044 的开发库。
-- 由 ReconcileDriveBranch.java 装配既有 release SQL 的身份、原始正文和结构契约后执行。
-- 必须完整备份并停止应用写入；全部操作处于调用方同一事务，禁止拆段执行。
-- 旧网盘执行记录仅迁移 version/script，保留 checksum、rank、时间、执行者和 success。
-- 新任务历史只在原始 SQL 实际执行、结构校验通过后登记；不使用 repair 或 outOfOrder。
DO $reconcile$
DECLARE h record; expected record; p record; n integer; started timestamptz;
BEGIN
    PERFORM pg_advisory_xact_lock(20261001,3846);
    LOCK TABLE public.nocode_schema_history IN EXCLUSIVE MODE;
    IF (SELECT count(*) FROM public.nocode_schema_history)<>45
       OR (SELECT count(*) FROM public.nocode_schema_history WHERE type='BASELINE' AND version='0' AND success)<>1
       OR EXISTS(SELECT 1 FROM public.nocode_schema_history WHERE NOT success OR version IS NULL OR version !~ '^[0-9]+$') THEN
        RAISE EXCEPTION '仅接受完整 baseline 0 + V001-V044 的旧网盘分支，未执行任何升级。';
    END IF;
    FOR n IN 1..44 LOOP
        SELECT * INTO STRICT h FROM public.nocode_schema_history WHERE version::integer=n;
        SELECT * INTO STRICT expected FROM nocode_release_identity WHERE version=CASE WHEN n>=40 THEN n+7 ELSE n END;
        IF h.type<>'SQL' OR h.checksum IS DISTINCT FROM expected.checksum
           OR h.description IS DISTINCT FROM expected.description
           OR h.script IS DISTINCT FROM (CASE WHEN n>=40 THEN 'V'||lpad(n::text,3,'0')||substring(expected.script FROM 5) ELSE expected.script END) THEN
            RAISE EXCEPTION 'V% 原始身份不匹配，停止且不自动修复历史。',n;
        END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename IN (
       'nocode_task_instance','nocode_task_template','nocode_task_template_version','nocode_task_plan',
       'nocode_task_comment','nocode_task_event','nocode_task_record_link','nocode_task_entry_binding',
       'nocode_task_entry_record','nocode_task_entry_template_version'))
       OR EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND (path IN (
       '/task-center','/nocode-app/task-center/manage','/nocode-app/task-center/launch','/nocode-app/task-center/templates')
       OR permission='nocode:task:manage-all')) THEN
        RAISE EXCEPTION '任务中心已有部分结构或菜单，拒绝覆盖。';
    END IF;
    -- 先核对旧网盘最终结构，只有任务表尚未存在；网盘数据与结构均不重建。
    PERFORM pg_temp.nocode_release_contract((SELECT jsonb_agg(t) FROM nocode_release_final,
        LATERAL jsonb_array_elements(contract) t WHERE t->>'table' NOT LIKE 'nocode_task_%'));
    CREATE TEMP TABLE nocode_reconcile_history_before ON COMMIT DROP AS TABLE public.nocode_schema_history;
    CREATE TEMP TABLE nocode_reconcile_executed(version integer PRIMARY KEY, elapsed integer) ON COMMIT DROP;
    FOR p IN SELECT * FROM nocode_release_plan WHERE version BETWEEN 40 AND 46 ORDER BY version LOOP
        started:=clock_timestamp();
        EXECUTE p.body;
        PERFORM pg_temp.nocode_release_contract(p.contract);
        -- 40-44 当前仍被旧分支占用；先完成所有 SQL，稍后释放身份后统一登记。
        INSERT INTO nocode_reconcile_executed VALUES(p.version,(extract(epoch FROM clock_timestamp()-started)*1000)::integer);
        RAISE NOTICE '实际执行任务迁移 V%，结构校验通过。',p.version;
    END LOOP;
    UPDATE public.nocode_schema_history original SET version=lpad(i.version::text,3,'0'),script=i.script
    FROM nocode_release_identity i WHERE i.version BETWEEN 47 AND 51 AND original.version::integer=i.version-7;
    GET DIAGNOSTICS n=ROW_COUNT;
    IF n<>5 THEN RAISE EXCEPTION '网盘历史对齐数量异常：%',n; END IF;
    FOR expected IN SELECT i.*,e.elapsed FROM nocode_release_identity i JOIN nocode_reconcile_executed e USING(version) ORDER BY version LOOP
        INSERT INTO public.nocode_schema_history(installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success)
        SELECT max(installed_rank)+1,lpad(expected.version::text,3,'0'),expected.description,'SQL',expected.script,expected.checksum,
            current_user,clock_timestamp(),expected.elapsed,true FROM public.nocode_schema_history;
    END LOOP;
    IF EXISTS(SELECT 1 FROM nocode_reconcile_history_before old
        LEFT JOIN public.nocode_schema_history current ON current.installed_rank=old.installed_rank
        WHERE current.installed_rank IS NULL
           OR (to_jsonb(current)-'version'-'script') IS DISTINCT FROM (to_jsonb(old)-'version'-'script')
           OR current.version::integer<>(CASE WHEN old.version::integer>=40 THEN old.version::integer+7 ELSE old.version::integer END)
           OR current.script IS DISTINCT FROM (CASE WHEN old.version::integer>=40 THEN 'V'||lpad((old.version::integer+7)::text,3,'0')||substring(old.script FROM 5) ELSE old.script END)) THEN
        RAISE EXCEPTION '旧执行记录非身份字段发生变化，回滚。';
    END IF;
    RAISE NOTICE '七个真实执行记录已登记，五个旧网盘身份已对齐；尚未提交。';
END $reconcile$;
