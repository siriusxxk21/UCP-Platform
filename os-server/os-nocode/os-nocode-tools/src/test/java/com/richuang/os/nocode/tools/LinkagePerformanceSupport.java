package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.LinkageSyncFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 数据联动自动更新性能测量的公共部分：语句计数器、计时、批量造数。具体场景分在三个类里——构建服务器的全局锁持有超过 60 分钟会被下一个任务抢占
 * （测试库随之被重建），所以每个类单独跑都要落在这个时限内。
 *
 * <p>都不进常规门禁：只在环境变量 LINKAGE_PERFORMANCE=1 时运行（常规全量里显示为跳过）。耗时数字是测量值，不是通过线，打到标准输出（行首
 * LINKAGE_PERFORMANCE）；硬断言只钉与数据量无关的结构性事实。
 */
abstract class LinkagePerformanceSupport {
    LinkageSyncFixture x;

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new Statements());
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup(TestInfo test) {
        System.out.println("LINKAGE_PERFORMANCE starting=" + test.getDisplayName());
        x = new LinkageSyncFixture();
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    /** 当前线程每个 Mapper 语句的执行次数（键为「Mapper.方法」），只在测量线程激活时统计。 */
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
    public static class Statements implements Interceptor {
        static final ThreadLocal<Map<String, Long>> ACTIVE = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Map<String, Long> current = ACTIVE.get();
            if (current != null && invocation.getArgs()[0] instanceof MappedStatement mapped) {
                String id = mapped.getId();
                int method = id.lastIndexOf('.');
                int type = id.lastIndexOf('.', method - 1);
                current.merge(id.substring(type + 1), 1L, Long::sum);
                current.merge("*", 1L, Long::sum);
            }
            return invocation.proceed();
        }
    }

    record Measured<T>(T value, Map<String, Long> statements, long millis) {
        long total() {
            return statements.getOrDefault("*", 0L);
        }

        long of(String id) {
            return statements.getOrDefault(id, 0L);
        }

        /** 改写业务行的语句数。 */
        long writes() {
            return of("RecordMapper.insert")
                    + of("RecordMapper.update")
                    + of("RecordMapper.delete");
        }
    }

    static <T> Measured<T> measure(String label, Supplier<T> work) {
        Map<String, Long> statements = new TreeMap<>();
        long started = System.nanoTime();
        Statements.ACTIVE.set(statements);
        T value;
        try {
            value = work.get();
        } finally {
            Statements.ACTIVE.remove();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println(
                "LINKAGE_PERFORMANCE "
                        + label
                        + " elapsedMs="
                        + millis
                        + " statements="
                        + statements.getOrDefault("*", 0L)
                        + " detail="
                        + statements);
        return new Measured<>(value, statements, millis);
    }

    List<String> bulkFlows(int count) {
        jdbc.update(
                "INSERT INTO public.\""
                        + x.flow.tableName()
                        + "\"(name, status, creator, updater, create_time, update_time, deleted)"
                        + " SELECT '流水' || g, 'wdj', '10001', '10001', clock_timestamp(),"
                        + " clock_timestamp(), 0 FROM generate_series(1, ?) g",
                count);
        return jdbc.queryForList(
                "SELECT id::text FROM public.\"" + x.flow.tableName() + "\" ORDER BY id",
                String.class);
    }

    List<Map<String, Object>> voucherRows(List<String> flowIds, int from, int size, boolean same) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = from; i < from + size; i++)
            rows.add(
                    values(
                            id(x.voucher, "name"),
                            "导入凭证" + i,
                            id(x.voucher, "status"),
                            i % 2 == 0 ? "ylr" : "ysh",
                            relationField(x.voucher, "flow"),
                            same ? flowIds.getFirst() : flowIds.get(i)));
        return rows;
    }
}
