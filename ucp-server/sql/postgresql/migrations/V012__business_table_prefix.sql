-- 新生成业务表采用 biz_；历史物理名保留，不重命名已有业务表或改写版本快照。
-- 新建只能使用 biz_ 的约束由持有对象锁的服务执行，数据库兼容已有元数据行。
ALTER TABLE public.nocode_object_table
    DROP CONSTRAINT nocode_object_table_generated_name;
ALTER TABLE public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_generated_name
    CHECK (table_name ~ '^(biz_|nocode_data_)[a-z][a-z0-9_]*$');
