package com.richuang.os.nocode.tools;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅用于测试：在测量线程上按「Mapper 语句 + 参数」记录真实执行次数，其它线程和未测量的调用不受影响。 */
@Intercepts({
    @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
            type = Executor.class,
            method = "update",
            args = {MappedStatement.class, Object.class})
})
public class StatementLedger implements Interceptor {
    /** 写语句的汇总键：total(ledger, WRITE) 为 0 表示本次动作没有写库。 */
    static final String WRITE = "#write";

    private static final ThreadLocal<Map<String, Integer>> ACTIVE = new ThreadLocal<>();

    /** 返回本次动作在当前线程上执行的语句：键为「Mapper简名.方法 参数」，值为次数。 */
    static Map<String, Integer> record(Runnable action) {
        Map<String, Integer> ledger = new LinkedHashMap<>();
        ACTIVE.set(ledger);
        try {
            action.run();
        } finally {
            ACTIVE.remove();
        }
        return ledger;
    }

    /** 某个 Mapper 方法（不分参数）的总执行次数。 */
    static int total(Map<String, Integer> ledger, String statement) {
        int result = 0;
        for (Map.Entry<String, Integer> entry : ledger.entrySet())
            if (statementOf(entry.getKey()).equals(statement)) result += entry.getValue();
        return result;
    }

    /** 各 Mapper 方法的总执行次数。 */
    static Map<String, Integer> totals(Map<String, Integer> ledger) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ledger.forEach((key, count) -> result.merge(statementOf(key), count, Integer::sum));
        return result;
    }

    static String statementOf(String key) {
        return key.substring(0, key.indexOf(' '));
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Map<String, Integer> ledger = ACTIVE.get();
        if (ledger != null) {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId();
            int method = id.lastIndexOf('.');
            int type = id.lastIndexOf('.', method - 1);
            String name = id.substring(type + 1);
            ledger.merge(name + " " + invocation.getArgs()[1], 1, Integer::sum);
            // 写语句另记一笔：INSERT/UPDATE/DELETE，以及声明 affectData 的 RETURNING 查询（记录写入用它实现）。
            if (statement.getSqlCommandType() != SqlCommandType.SELECT || statement.isDirtySelect())
                ledger.merge(WRITE + " " + name, 1, Integer::sum);
        }
        return invocation.proceed();
    }
}
