-- 将已有数据视图统一为每页 10 条。草稿与当前运行快照分别处理，禁止顺带发布草稿。
-- 发布版本保持不可变：仅分页不同的运行快照追加新版本，历史内容及其校验和保留。
LOCK TABLE public.nocode_application, public.nocode_application_version IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    app record;
    old_snapshot jsonb;
    new_snapshot jsonb;
    new_design jsonb;
    source_definition jsonb;
    new_resources jsonb;
    next_version integer;
    changed boolean;
BEGIN
    FOR app IN SELECT * FROM public.nocode_application WHERE deleted=0 ORDER BY id LOOP
        changed := false;
        new_design := app.design_json;
        next_version := app.published_version;

        -- 只改 VIEW.config.pageSize，保留顺序及所有其他资源属性。
        source_definition := app.design_json;
        IF EXISTS (
                SELECT 1 FROM jsonb_array_elements(source_definition->'resources') r
                WHERE r->>'kind'='VIEW' AND r#>'{config,pageSize}' IS DISTINCT FROM '10'::jsonb
            ) THEN
                SELECT jsonb_agg(
                    CASE WHEN r->>'kind'='VIEW' THEN jsonb_set(r, '{config,pageSize}', '10'::jsonb)
                         ELSE r END ORDER BY n
                ) INTO new_resources
                FROM jsonb_array_elements(source_definition->'resources') WITH ORDINALITY AS items(r,n);
                new_design := jsonb_set(source_definition, '{resources}', new_resources);
                changed := true;
        END IF;

        IF app.published_version IS NOT NULL THEN
            SELECT definition_json INTO STRICT old_snapshot
            FROM public.nocode_application_version
            WHERE application_id=app.id AND version_no=app.published_version AND deleted=0;
            source_definition := old_snapshot->'definition';
            IF EXISTS (
                SELECT 1 FROM jsonb_array_elements(source_definition->'resources') r
                WHERE r->>'kind'='VIEW' AND r#>'{config,pageSize}' IS DISTINCT FROM '10'::jsonb
            ) THEN
                SELECT jsonb_agg(
                    CASE WHEN r->>'kind'='VIEW' THEN jsonb_set(r, '{config,pageSize}', '10'::jsonb)
                         ELSE r END ORDER BY n
                ) INTO new_resources
                FROM jsonb_array_elements(source_definition->'resources') WITH ORDINALITY AS items(r,n);
                new_snapshot := jsonb_set(old_snapshot, '{definition,resources}', new_resources);
                SELECT COALESCE(max(version_no),0)+1 INTO next_version
                FROM public.nocode_application_version WHERE application_id=app.id;
                INSERT INTO public.nocode_application_version
                    (application_id,version_no,definition_json,checksum,reason,creator,updater)
                VALUES (app.id,next_version,new_snapshot,
                    encode(sha256(convert_to(new_snapshot::text,'UTF8')),'hex'),
                    '系统分页统一：数据视图默认每页 10 条',
                    'nocode-pagination-migration','nocode-pagination-migration');
                changed := true;
            END IF;
        END IF;

        IF changed THEN
            UPDATE public.nocode_application
            SET design_json=new_design,published_version=next_version,lock_version=lock_version+1,
                updater='nocode-pagination-migration',update_time=clock_timestamp()
            WHERE id=app.id;
        END IF;
    END LOOP;
END $$;
