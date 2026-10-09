-- 移除已废弃的知识库、智能体及其 AI/MCP 支撑模块。前置 V035。
-- 清理模块菜单、角色菜单关联与专属数据表；保留 os-nocode、BPM、文件和消息模块。
-- 校验：知识库/智能体/AI/MCP 菜单及专属表均不存在。
WITH RECURSIVE removed_menus AS (
    SELECT id
    FROM public.system_menu
    WHERE path = '/knowledge'
       OR path LIKE '/knowledge/%'
       OR path = '/agent'
       OR path LIKE '/agent/%'
       OR path = '/agent-tool'
       OR path LIKE '/agent-tool/%'
       OR component LIKE 'knowledge/%'
       OR component LIKE 'agent/%'
       OR component LIKE 'agent-chat%'
       OR component LIKE 'agent-tool/%'
       OR permission LIKE 'knowledge:%'
       OR permission LIKE 'agent:%'
       OR permission LIKE 'ai:%'
       OR permission LIKE 'mcp:%'
    UNION
    SELECT child.id
    FROM public.system_menu child
    INNER JOIN removed_menus parent ON child.parent_id = parent.id
)
DELETE FROM public.system_role_menu
WHERE menu_id IN (SELECT id FROM removed_menus);

WITH RECURSIVE removed_menus AS (
    SELECT id
    FROM public.system_menu
    WHERE path = '/knowledge'
       OR path LIKE '/knowledge/%'
       OR path = '/agent'
       OR path LIKE '/agent/%'
       OR path = '/agent-tool'
       OR path LIKE '/agent-tool/%'
       OR component LIKE 'knowledge/%'
       OR component LIKE 'agent/%'
       OR component LIKE 'agent-chat%'
       OR component LIKE 'agent-tool/%'
       OR permission LIKE 'knowledge:%'
       OR permission LIKE 'agent:%'
       OR permission LIKE 'ai:%'
       OR permission LIKE 'mcp:%'
    UNION
    SELECT child.id
    FROM public.system_menu child
    INNER JOIN removed_menus parent ON child.parent_id = parent.id
)
DELETE FROM public.system_menu
WHERE id IN (SELECT id FROM removed_menus);

DROP TABLE IF EXISTS public.agent,
    public.agent_chat,
    public.agent_chat_record,
    public.agent_knowledge_rel,
    public.agent_tool,
    public.agent_tool_db_config,
    public.agent_tool_db_table,
    public.agent_tool_rel,
    public.agent_version,
    public.ai_agent,
    public.ai_agent_template,
    public.ai_doc_file,
    public.ai_doc_generate_task,
    public.ai_doc_smart_edit_session,
    public.ai_doc_snapshot,
    public.ai_doc_suggestion,
    public.ai_doc_template,
    public.ai_doc_version,
    public.ai_mcp_server,
    public.ai_memory,
    public.ai_message,
    public.ai_model_provider,
    public.knowledge_base,
    public.knowledge_category,
    public.knowledge_document,
    public.knowledge_document_tag,
    public.knowledge_hit_test,
    public.knowledge_problem,
    public.knowledge_segment,
    public.knowledge_tag,
    public.knowledge_task CASCADE;

DO $$
DECLARE
    table_name text;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.system_menu
        WHERE path = '/knowledge'
           OR path LIKE '/knowledge/%'
           OR path = '/agent'
           OR path LIKE '/agent/%'
           OR path = '/agent-tool'
           OR path LIKE '/agent-tool/%'
           OR component LIKE 'knowledge/%'
           OR component LIKE 'agent/%'
           OR component LIKE 'agent-chat%'
           OR component LIKE 'agent-tool/%'
           OR permission LIKE 'knowledge:%'
           OR permission LIKE 'agent:%'
           OR permission LIKE 'ai:%'
           OR permission LIKE 'mcp:%'
    ) THEN
        RAISE EXCEPTION '知识库、智能体或 AI/MCP 菜单清理不完整';
    END IF;

    FOREACH table_name IN ARRAY ARRAY[
        'agent', 'agent_chat', 'agent_chat_record', 'agent_knowledge_rel', 'agent_tool',
        'agent_tool_db_config', 'agent_tool_db_table', 'agent_tool_rel', 'agent_version',
        'ai_agent', 'ai_agent_template', 'ai_doc_file', 'ai_doc_generate_task',
        'ai_doc_smart_edit_session', 'ai_doc_snapshot', 'ai_doc_suggestion', 'ai_doc_template',
        'ai_doc_version', 'ai_mcp_server', 'ai_memory', 'ai_message', 'ai_model_provider',
        'knowledge_base', 'knowledge_category', 'knowledge_document', 'knowledge_document_tag',
        'knowledge_hit_test', 'knowledge_problem', 'knowledge_segment', 'knowledge_tag', 'knowledge_task'
    ] LOOP
        IF to_regclass('public.' || table_name) IS NOT NULL THEN
            RAISE EXCEPTION '知识库、智能体或 AI/MCP 表未删除：%', table_name;
        END IF;
    END LOOP;
END $$;
