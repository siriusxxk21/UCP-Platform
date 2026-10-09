-- 从真实 PostgreSQL 获取列、约束与索引契约；问号是 JDBC 的 regclass 参数。
SELECT jsonb_build_object(
    'table', t.relname,
    'columns', (SELECT jsonb_agg(jsonb_build_object(
        'name', a.attname, 'type', format_type(a.atttypid,a.atttypmod),
        'notnull', a.attnotnull, 'identity', a.attidentity::text, 'generated', a.attgenerated::text,
        'default', pg_get_expr(d.adbin,d.adrelid)) ORDER BY a.attnum)
        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
        WHERE a.attrelid=t.oid AND a.attnum>0 AND NOT a.attisdropped),
    'constraints', COALESCE((SELECT jsonb_agg(jsonb_build_object('name', c.conname,
        'definition', regexp_replace(pg_get_constraintdef(c.oid), '(public|pg_temp(_[0-9]+)?)\.', '', 'g'),
        'validated', c.convalidated, 'deferrable', c.condeferrable) ORDER BY c.conname)
        FROM pg_constraint c WHERE c.conrelid=t.oid AND c.contype IN ('p','u','f','c','x')), '[]'::jsonb),
    'indexes', COALESCE((SELECT jsonb_agg(jsonb_build_object('name', x.relname,
        'definition', regexp_replace(pg_get_indexdef(i.indexrelid), '(public|pg_temp(_[0-9]+)?)\.', '', 'g'),
        'valid',i.indisvalid,'ready',i.indisready) ORDER BY x.relname)
        FROM pg_index i JOIN pg_class x ON x.oid=i.indexrelid WHERE i.indrelid=t.oid),'[]'::jsonb))
FROM pg_class t WHERE t.oid=to_regclass(?);
