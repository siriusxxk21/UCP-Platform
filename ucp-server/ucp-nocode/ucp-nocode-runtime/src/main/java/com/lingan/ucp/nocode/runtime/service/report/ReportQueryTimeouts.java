package com.lingan.ucp.nocode.runtime.service.report;

import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * 统计查询超时的识别与业务提示。ReportMapper 的统计语句都设了 {@link #SECONDS} 秒超时，到点由驱动取消语句（PostgreSQL SQLSTATE 57014）， 经
 * MyBatis/Spring 转成 {@link QueryTimeoutException}；不转换时会落到全局处理器成为「系统异常」，用户无从下手。
 */
final class ReportQueryTimeouts {
    /** 与 ReportMapper.xml 里 pivot 语句的 timeout 一致。 */
    static final int SECONDS = 20;

    static final String PIVOT_MESSAGE =
            "统计数据量过大，超过 " + SECONDS + " 秒未算完，请减少维度、改用更粗的日期分组（如按月）或加筛选条件";

    private static final String QUERY_CANCELED = "57014";

    private ReportQueryTimeouts() {}

    /** 异常链上任一层是查询超时/被取消即为超时；其它数据库错误原样抛出。 */
    static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof QueryTimeoutException || t instanceof SQLTimeoutException) return true;
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState()))
                return true;
        }
        return false;
    }
}
