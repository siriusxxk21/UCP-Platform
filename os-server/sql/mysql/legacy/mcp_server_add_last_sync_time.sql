-- MCP Server 动态工具同步：添加最后同步时间字段

ALTER TABLE ai_mcp_server
    ADD COLUMN last_sync_time DATETIME NULL COMMENT '最后同步时间（MCP tools/list 调用成功时更新）';
