-- DEC-20260909-01：复用底座反馈表，收敛为两种状态并接入系统管理菜单。
UPDATE public.system_feedback
SET status='PENDING', version=version+1, updater='feedback-migration', update_time=now()
WHERE status IN ('PROCESSING','PENDING_VERIFICATION');
UPDATE public.system_feedback
SET feedback_type='BUG', version=version+1, updater='feedback-migration', update_time=now()
WHERE feedback_type='ISSUE';

DO $$
DECLARE
    system_id bigint;
    feedback_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT system_id FROM public.system_menu
    WHERE deleted=0 AND parent_id=0 AND path='/system';
    SELECT id INTO feedback_id FROM public.system_menu
    WHERE deleted=0 AND path='/system/feedback';
    IF feedback_id IS NULL THEN
        feedback_id := nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
        (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
         visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
        VALUES (feedback_id,'反馈工单','',2,90,system_id,'/system/feedback','CommentOutlined',
                'system/feedback/index','SystemFeedback',0,true,true,true,
                'feedback-migration','feedback-migration',now(),now(),0);
    ELSE
        UPDATE public.system_menu SET name='反馈工单',permission='',parent_id=system_id,
            component='system/feedback/index',status=0,visible=true,
            updater='feedback-migration',update_time=now()
        WHERE id=feedback_id;
    END IF;
END $$;
