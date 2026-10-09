package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.LinkageSyncFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;

import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.*;

/**
 * 批量导入来源 5000 条（10 批 × 500）两种形态的语句数与耗时：每行指向不同目标、全部指向同一个目标。
 *
 * <p>实测整类约 42 分钟（2026-10-02，构建服务器，带逐条 SQL 调试日志），单独跑，不要和别的类并在同一次任务里。
 */
@Tag("linkage-performance")
@EnabledIfEnvironmentVariable(named = "LINKAGE_PERFORMANCE", matches = "1")
class LinkageImportPerformanceTest extends LinkagePerformanceSupport {
    /** 批量导入来源 5000 条（10 批 × 500），每行指向不同目标：每批语句数相同，结束后对账为 0。 */
    @Test
    void importingFiveThousandSourcesForDistinctTargets() {
        x.benchmark();
        var flows = bulkFlows(5000);
        List<Measured<Integer>> batches = new ArrayList<>();
        for (int batch = 0; batch < 10; batch++) {
            int from = batch * 500;
            batches.add(
                    measure(
                            "import distinct-targets batch=" + (batch + 1),
                            () ->
                                    x.runtime.importRecords(
                                            x.app,
                                            x.voucher.objectId(),
                                            voucherRows(flows, from, 500, false),
                                            10001)));
        }
        long total = batches.stream().mapToLong(Measured::millis).sum();
        long slowest = batches.stream().mapToLong(Measured::millis).max().orElse(0);
        System.out.println(
                "LINKAGE_PERFORMANCE import distinct-targets totalMs="
                        + total
                        + " slowestBatchMs="
                        + slowest
                        + "（一批一个事务，期间独占全局执行锁）");
        // 第一批含历史基线等一次性成本，从第二批起逐批语句数应当相同（对行数线性）。
        for (int i = 2; i < batches.size(); i++)
            assertThat(batches.get(i).total())
                    .as("第 " + (i + 1) + " 批")
                    .isEqualTo(batches.get(1).total());
        assertThat(x.pending()).isZero();
    }

    /** 同样 5000 行，但全指向同一个目标：目标只在值真正变化时被写（取第一行 ⇒ 只变一次）。 */
    @Test
    void importingFiveThousandSourcesForOneTargetWritesItOnce() {
        x.benchmark();
        var flows = bulkFlows(1);
        long writes = 0;
        long total = 0;
        for (int batch = 0; batch < 10; batch++) {
            int from = batch * 500;
            var measured =
                    measure(
                            "import same-target batch=" + (batch + 1),
                            () ->
                                    x.runtime.importRecords(
                                            x.app,
                                            x.voucher.objectId(),
                                            voucherRows(flows, from, 500, true),
                                            10001));
            total += measured.millis();
            // 每行来源各插入一次；目标的写入体现在 update 上。
            writes += measured.of("RecordMapper.update");
        }
        System.out.println(
                "LINKAGE_PERFORMANCE import same-target totalMs="
                        + total
                        + " targetUpdates="
                        + writes);
        assertThat(writes).as("目标只在值真正变化时被写").isEqualTo(1);
        assertThat(x.linkageHistory(x.flow, flows.getFirst())).hasSize(1);
        assertThat(x.flowStatus(flows.getFirst())).isEqualTo("ylr");
        assertThat(x.pending()).isZero();
    }
}
