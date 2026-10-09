package com.richuang.os.nocode.tools;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/** 仅用于测试：在真正执行 Mapper 写入后抛出异常，验证数据库事务共同回滚。 */
@Intercepts(
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}))
public class WriteFailureInterceptor implements Interceptor {

    private final ThreadLocal<String> failurePrefix = new ThreadLocal<>();

    public void failAfter(String sqlPrefix) {
        failurePrefix.set(sqlPrefix);
    }

    public void clear() {
        failurePrefix.remove();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        String prefix = failurePrefix.get();
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        String sql =
                statement
                        .getBoundSql(invocation.getArgs()[1])
                        .getSql()
                        .replaceAll("\\s+", " ")
                        .trim();
        if (prefix != null && sql.startsWith(prefix)) {
            throw new IllegalStateException("B1 intentional failure after MyBatis database write");
        }
        return result;
    }
}
