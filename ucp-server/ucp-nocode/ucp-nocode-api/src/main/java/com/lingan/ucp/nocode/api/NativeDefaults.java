package com.lingan.ucp.nocode.api;

/** 只提取可无歧义展示的原生布尔常量；不在客户端解释或执行数据库表达式。 */
public final class NativeDefaults {
    private NativeDefaults() {}

    public static String booleanConstant(String nativeType, String expression) {
        if (!"boolean".equals(nativeType) || expression == null) return null;
        String value = expression.trim();
        return "true".equals(value) || "false".equals(value) ? value : null;
    }
}
