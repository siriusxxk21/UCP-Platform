package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.enums.RecordChangeOperationEnum;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.runtime.service.live.RecordChangeCollector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 记录变更登记簿（契约 3.1–3.4）：同一事务合并成一份、提交之后才交付、回滚不交付、内层独立事务不串簿、监听者异常不外泄。 用夹具里的真实事务管理器与真实连接；登记簿本身不接任何写点。
 */
class RecordChangeCollectorTest extends NocodeIntegrationSupport {
    private static final String OBJECT = "3057";
    private static final RecordChangeOperationEnum CREATE = RecordChangeOperationEnum.CREATE;
    private static final RecordChangeOperationEnum UPDATE = RecordChangeOperationEnum.UPDATE;
    private static final RecordChangeOperationEnum DELETE = RecordChangeOperationEnum.DELETE;

    private RecordChangeCollector collector;
    private List<RecordChangeBatch> delivered;
    private TransactionTemplate tx;
    private TransactionTemplate independent;
    private String table;

    @BeforeEach
    void prepare() {
        collector = new RecordChangeCollector();
        delivered = new ArrayList<>();
        collector.listen(delivered::add);
        tx = new TransactionTemplate(manager);
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        table = "test_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.execute("CREATE TABLE public." + table + " (id bigint PRIMARY KEY)");
    }

    @AfterEach
    void dropProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS public." + table);
    }

    @Test
    void t1_sameTransactionIsDeliveredOnceAsOneBatch() {
        tx.executeWithoutResult(
                status -> {
                    collector.changed(OBJECT, "88", UPDATE);
                    collector.changed(OBJECT, "89", UPDATE);
                    assertThat(delivered).as("提交之前不交付").isEmpty();
                });
        assertThat(delivered).as("同一事务恰好交付一次").hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, false, List.of(), List.of("88", "89"), List.of()));
    }

    @Test
    void t2_rollbackDeliversNothing() {
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            collector.changed(OBJECT, "88", UPDATE);
                                            throw new IllegalStateException("回滚");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivered).as("回滚的事务不交付").isEmpty();
        // 回滚后登记簿已解绑：下一个事务从一本空簿开始，不带上一个事务的记录。
        tx.executeWithoutResult(status -> collector.changed(OBJECT, "90", CREATE));
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, false, List.of("90"), List.of(), List.of()));
    }

    @Test
    void t3_listenerRunsAfterTheDataIsVisibleToOtherConnections() {
        List<Integer> seenByOtherConnection = new ArrayList<>();
        List<Integer> seenBeforeCommit = new ArrayList<>();
        collector.listen(batch -> seenByOtherConnection.add(countOnOtherConnection()));
        tx.executeWithoutResult(
                status -> {
                    jdbc.update("INSERT INTO public." + table + " (id) VALUES (1)");
                    collector.changed(OBJECT, "1", CREATE);
                    seenBeforeCommit.add(countOnOtherConnection());
                });
        assertThat(seenBeforeCommit).as("对照：提交之前别的连接看不到这一行").containsExactly(0);
        assertThat(seenByOtherConnection).as("监听者被调用时，别的连接已能查到事务里写的那行").containsExactly(1);
    }

    @Test
    void t4_innerIndependentTransactionCommitsWhileOuterRollsBack() {
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            collector.changed(OBJECT, "outer", UPDATE);
                                            independent.executeWithoutResult(
                                                    inner ->
                                                            collector.changed(
                                                                    OBJECT, "inner", UPDATE));
                                            assertThat(delivered).as("内层提交后立即交付内层的那一份").hasSize(1);
                                            throw new IllegalStateException("外层回滚");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivered).as("只交付内层").hasSize(1);
        assertThat(delivered.getFirst().objects())
                .as("内层的批里没有外层登记的记录")
                .containsExactly(change(OBJECT, false, List.of(), List.of("inner"), List.of()));
    }

    @Test
    void t5_innerIndependentTransactionRollsBackWhileOuterCommits() {
        tx.executeWithoutResult(
                status -> {
                    collector.changed(OBJECT, "outer", UPDATE);
                    assertThatThrownBy(
                                    () ->
                                            independent.executeWithoutResult(
                                                    inner -> {
                                                        collector.changed(OBJECT, "inner", UPDATE);
                                                        throw new IllegalStateException("内层回滚");
                                                    }))
                            .isInstanceOf(IllegalStateException.class);
                    // 内层结束后外层的登记簿恢复：继续登记仍记到外层这本上。
                    collector.changed(OBJECT, "outer-2", UPDATE);
                });
        assertThat(delivered).as("只交付外层").hasSize(1);
        assertThat(delivered.getFirst().objects())
                .as("外层的批里没有内层登记的记录")
                .containsExactly(
                        change(OBJECT, false, List.of(), List.of("outer", "outer-2"), List.of()));
    }

    @Test
    void t6_listenerFailureNeverReachesTheCaller() {
        RecordChangeCollector failing = new RecordChangeCollector();
        List<RecordChangeBatch> second = new ArrayList<>();
        failing.listen(
                batch -> {
                    throw new IllegalStateException("监听者故障");
                });
        failing.listen(
                batch -> {
                    throw new AssertionError("监听者抛出 Error");
                });
        failing.listen(second::add);
        String result =
                tx.execute(
                        status -> {
                            failing.changed(OBJECT, "88", UPDATE);
                            return "已保存";
                        });
        assertThat(result).as("数据已提交，调用方必须正常拿到返回值").isEqualTo("已保存");
        assertThat(second).as("前面的监听者抛出后，后面的监听者仍被调用").hasSize(1);
    }

    @Test
    void t7_mergeTableWithinOneTransaction() {
        assertMerged(c -> c.changed(OBJECT, "1", CREATE), List.of("1"), List.of(), List.of());
        assertMerged(c -> c.changed(OBJECT, "1", UPDATE), List.of(), List.of("1"), List.of());
        assertMerged(c -> c.changed(OBJECT, "1", DELETE), List.of(), List.of(), List.of("1"));
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", CREATE);
                    c.changed(OBJECT, "1", UPDATE);
                },
                List.of("1"),
                List.of(),
                List.of());
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", UPDATE);
                    c.changed(OBJECT, "1", DELETE);
                },
                List.of(),
                List.of(),
                List.of("1"));
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", UPDATE);
                    c.changed(OBJECT, "1", UPDATE);
                },
                List.of(),
                List.of("1"),
                List.of());
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", DELETE);
                    c.changed(OBJECT, "1", DELETE);
                },
                List.of(),
                List.of(),
                List.of("1"));
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", DELETE);
                    c.changed(OBJECT, "1", CREATE);
                },
                List.of(),
                List.of("1"),
                List.of());
        assertMerged(
                c -> {
                    c.changed(OBJECT, "1", DELETE);
                    c.changed(OBJECT, "1", UPDATE);
                },
                List.of(),
                List.of("1"),
                List.of());
        // 新增后又删除：净效果为无，整本簿子为空 ⇒ 不交付。
        delivered.clear();
        tx.executeWithoutResult(
                status -> {
                    collector.changed(OBJECT, "1", CREATE);
                    collector.changed(OBJECT, "1", DELETE);
                });
        assertThat(delivered).as("新增后删除 ⇒ 不交付").isEmpty();
        // 同一事务里另一条记录仍照常交付，且不带上净效果为无的那条。
        tx.executeWithoutResult(
                status -> {
                    collector.changed(OBJECT, "1", CREATE);
                    collector.changed(OBJECT, "2", UPDATE);
                    collector.changed(OBJECT, "1", DELETE);
                });
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, false, List.of(), List.of("2"), List.of()));
        // 任何 → bulk：该对象转 many、清空集合；别的对象不受影响。
        delivered.clear();
        tx.executeWithoutResult(
                status -> {
                    collector.changed(OBJECT, "1", CREATE);
                    collector.changed("4001", "7", DELETE);
                    collector.bulk(OBJECT);
                });
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(
                        change(OBJECT, true, List.of(), List.of(), List.of()),
                        change("4001", false, List.of(), List.of(), List.of("7")));
    }

    @Test
    void t8_idCapTurnsTheObjectIntoManyAndDropsTheIds() {
        tx.executeWithoutResult(
                status -> {
                    for (int index = 1; index <= RecordChangeCollector.ID_CAP; index++)
                        collector.changed(OBJECT, Integer.toString(index), UPDATE);
                });
        assertThat(delivered).hasSize(1);
        RecordChangeBatch.ObjectChange atCap = delivered.getFirst().objects().getFirst();
        assertThat(atCap.many()).as("恰好 200 条不降级").isFalse();
        assertThat(atCap.updated()).hasSize(200).contains("1", "200");

        delivered.clear();
        tx.executeWithoutResult(
                status -> {
                    for (int index = 1; index <= RecordChangeCollector.ID_CAP + 1; index++)
                        collector.changed(OBJECT, Integer.toString(index), UPDATE);
                    // 降级之后继续登记上万条：被忽略，不重新攒 ID。
                    for (int index = 1000; index < 12000; index++)
                        collector.changed(OBJECT, Integer.toString(index), CREATE);
                });
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().objects())
                .as("第 201 条起转 many，三个列表为空")
                .containsExactly(change(OBJECT, true, List.of(), List.of(), List.of()));

        // 三个集合之和计数：新增、修改、删除混在一起超过上限同样降级。
        delivered.clear();
        tx.executeWithoutResult(
                status -> {
                    for (int index = 1; index <= 100; index++)
                        collector.changed(OBJECT, "c" + index, CREATE);
                    for (int index = 1; index <= 100; index++)
                        collector.changed(OBJECT, "u" + index, UPDATE);
                    collector.changed(OBJECT, "d1", DELETE);
                });
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, true, List.of(), List.of(), List.of()));

        // bulk 之后再 changed 被忽略。
        delivered.clear();
        tx.executeWithoutResult(
                status -> {
                    collector.bulk(OBJECT);
                    collector.changed(OBJECT, "1", CREATE);
                });
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, true, List.of(), List.of(), List.of()));
    }

    @Test
    void t9_withoutTransactionDeliversImmediatelyAndClosedHandleStopsDelivery() throws Exception {
        List<RecordChangeBatch> extra = new ArrayList<>();
        AutoCloseable handle = collector.listen(extra::add);
        collector.changed(OBJECT, "88", UPDATE);
        assertThat(delivered).as("没有事务时立即交付只含这一条的批").hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, false, List.of(), List.of("88"), List.of()));
        assertThat(extra).hasSize(1);
        collector.bulk(OBJECT);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.getLast().objects())
                .containsExactly(change(OBJECT, true, List.of(), List.of(), List.of()));
        handle.close();
        collector.changed(OBJECT, "89", UPDATE);
        assertThat(delivered).hasSize(3);
        assertThat(extra).as("句柄关闭后不再被调用").hasSize(2);
        assertThatThrownBy(
                        () -> collector.changed(OBJECT, "90", RecordChangeOperationEnum.BASELINE))
                .as("只接受 CREATE / UPDATE / DELETE")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertMerged(
            Consumer<RecordChangeCollector> steps,
            List<String> created,
            List<String> updated,
            List<String> deleted) {
        delivered.clear();
        tx.executeWithoutResult(status -> steps.accept(collector));
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().objects())
                .containsExactly(change(OBJECT, false, created, updated, deleted));
    }

    private int countOnOtherConnection() {
        try (var connection = ds.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM public." + table)) {
            rows.next();
            return rows.getInt(1);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static RecordChangeBatch.ObjectChange change(
            String objectId,
            boolean many,
            List<String> created,
            List<String> updated,
            List<String> deleted) {
        return new RecordChangeBatch.ObjectChange(objectId, many, created, updated, deleted);
    }
}
