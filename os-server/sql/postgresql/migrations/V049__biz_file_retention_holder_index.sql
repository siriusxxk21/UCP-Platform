-- P1-6：文件保留引用按持有者查询索引。前置 V040。
-- 工作草稿重存与草稿作废需要按（持有者类型、持有者编号）整体物理释放登记；
-- 无该索引时只能全表扫描，保留引用表随历史修订持续增长。

CREATE INDEX nocode_biz_file_retention_holder_idx
    ON public.nocode_biz_file_retention (holder_type, holder_id) WHERE deleted=0;
