-- 使用消息中心的既有站内信模板和任务详情跳转，不覆盖管理员维护的现有配置。
INSERT INTO public.sys_msg_template
    (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9060001,'nocode-task-acceptance','任务验收提醒',0,0,
    '任务验收提醒','任务的验收状态已更新',
    '/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-acceptance' AND deleted=0);
