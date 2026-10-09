-- P1-6：业务文件浏览/搜索/统计的权限过滤索引。前置 V040。
-- 业务附件绑定增加展示名、大小与类型冗余列：单业务入口的目录浏览、文件名搜索与容量统计
-- 必须与记录权限条件在同一条 SQL 内完成分页与计数，不能在取回全部文件后再过滤；
-- 受管节点展示名与内容在绑定后不可变（受管节点禁止普通重命名与覆盖），
-- 因此以绑定时网盘返回的节点信息写入冗余列，由对象/记录/字段/节点索引支撑查询。
-- 同时补充按（对象、状态、记录）与（对象、规则版本、分组键串）的浏览索引。

ALTER TABLE public.nocode_biz_attachment_binding
    ADD COLUMN file_name varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN file_size bigint NOT NULL DEFAULT 0,
    ADD COLUMN mime_type varchar(128) NOT NULL DEFAULT '';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_name IS '受管网盘节点展示名（绑定后不可变）；列表、搜索与统计与权限条件在同一 SQL 内过滤';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_size IS '内容大小（字节），绑定时自文件底座冗余；用于权限过滤后的容量统计';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.mime_type IS '内容 MIME 类型，绑定时自文件底座冗余；仅用于展示';

-- 既有绑定从网盘节点回填；节点已删除的历史绑定保持默认空值
UPDATE public.nocode_biz_attachment_binding b
SET file_name = COALESCE(e.name, ''),
    file_size = COALESCE(e.size, 0),
    mime_type = COALESCE(e.mime_type, '')
FROM public.drive_entry e
WHERE e.id = b.entry_id
  AND e.deleted = 0;

CREATE INDEX nocode_biz_attachment_binding_browse_idx
    ON public.nocode_biz_attachment_binding (object_id, state, record_id) WHERE deleted=0;
CREATE INDEX nocode_biz_directory_binding_group_idx
    ON public.nocode_biz_directory_binding (object_id, rule_version, group_keys)
    WHERE deleted=0 AND detail_id='' AND row_id='' AND field_id='';
