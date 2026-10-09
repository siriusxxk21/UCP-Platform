package com.richuang.os.common.context;

/**
 * 数据权限上下文
 * 用于在ThreadLocal中存储数据权限SQL片段
 */
public class DataScopeContext {

    private static final ThreadLocal<String> DATA_SCOPE_SQL = new ThreadLocal<>();

    /**
     * 获取数据权限SQL
     */
    public static String getScopeSql() {
        return DATA_SCOPE_SQL.get();
    }

    /**
     * 设置数据权限SQL
     */
    public static void setScopeSql(String sql) {
        DATA_SCOPE_SQL.set(sql);
    }

    /**
     * 清除数据权限SQL
     */
    public static void clear() {
        DATA_SCOPE_SQL.remove();
    }

    /**
     * 判断是否有数据权限SQL
     */
    public static boolean hasScopeSql() {
        String sql = DATA_SCOPE_SQL.get();
        return sql != null && !sql.isEmpty();
    }
}
