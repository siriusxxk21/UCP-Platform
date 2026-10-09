package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.web.live.RecordLivePending;
import com.lingan.ucp.nocode.web.live.RecordsChanged;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 一个订阅的待发状态（契约 5.2、5.4、6.2、6.3、6.5）：合并、上限、序号范围、发起人单一/混合、落后补发。 */
class RecordLivePendingTest {
    private static final String OBJECT = "3057";
    private static final String EPOCH = "m3k9x2";

    @Test
    void nothingMergedMeansNoFrame() {
        assertThat(new RecordLivePending(true).frame(OBJECT, EPOCH, 0)).isNull();
    }

    @Test
    void mergesAcrossBatchesWithTheLedgerRules() {
        RecordLivePending pending = new RecordLivePending(true);
        pending.merge(created(OBJECT, "1", "2"), 5, "tab-aaaaaaaa");
        pending.merge(updated(OBJECT, "1", "3"), 6, "tab-aaaaaaaa");
        pending.merge(deleted(OBJECT, "2", "3", "4"), 7, "tab-aaaaaaaa");
        RecordsChanged frame = pending.frame(OBJECT, EPOCH, 99);
        assertThat(frame)
                .isEqualTo(
                        new RecordsChanged(
                                OBJECT,
                                "ids",
                                false,
                                List.of("1"),
                                List.of(),
                                List.of("3", "4"),
                                "tab-aaaaaaaa",
                                EPOCH,
                                5,
                                7));
        pending.delivered();
        assertThat(pending.frame(OBJECT, EPOCH, 99)).as("交付后清空").isNull();
        // 删除后又出现（防御）⇒ updated；清空后的下一帧从新的序号开始。
        pending.merge(deleted(OBJECT, "9"), 8, null);
        pending.merge(created(OBJECT, "9"), 9, null);
        assertThat(pending.frame(OBJECT, EPOCH, 99))
                .isEqualTo(
                        new RecordsChanged(
                                OBJECT,
                                "ids",
                                false,
                                List.of(),
                                List.of("9"),
                                List.of(),
                                null,
                                EPOCH,
                                8,
                                9));
    }

    @Test
    void createdThenDeletedInOneWindowSendsNothingButKeepsTheSequenceRange() {
        RecordLivePending pending = new RecordLivePending(true);
        pending.merge(created(OBJECT, "1"), 3, null);
        pending.merge(deleted(OBJECT, "1"), 4, null);
        assertThat(pending.frame(OBJECT, EPOCH, 4)).as("净效果为无：这一轮不发").isNull();
        pending.merge(updated(OBJECT, "2"), 5, null);
        RecordsChanged frame = pending.frame(OBJECT, EPOCH, 5);
        assertThat(frame.fromSeq()).as("没发出去的序号由下一帧覆盖，仍然接续").isEqualTo(3);
        assertThat(frame.seq()).isEqualTo(5);
        assertThat(frame.updated()).containsExactly("2");
        assertThat(frame.created()).isEmpty();
        assertThat(frame.deleted()).isEmpty();
    }

    @Test
    void moreThanTheCapBecomesObjectWithManyAndTheCapItselfDoesNot() {
        RecordLivePending atCap = new RecordLivePending(true);
        atCap.merge(
                new RecordChangeBatch.ObjectChange(
                        OBJECT, false, List.of(), ids(1, 120), List.of()),
                1,
                null);
        atCap.merge(
                new RecordChangeBatch.ObjectChange(
                        OBJECT, false, ids(121, 200), List.of(), List.of()),
                2,
                null);
        RecordsChanged exact = atCap.frame(OBJECT, EPOCH, 2);
        assertThat(exact.kind()).isEqualTo("ids");
        assertThat(exact.many()).isFalse();
        assertThat(exact.created().size() + exact.updated().size()).isEqualTo(200);

        atCap.merge(updated(OBJECT, "201"), 3, null);
        assertThat(atCap.frame(OBJECT, EPOCH, 3))
                .as("合并后超过 200 ⇒ object、many、三个列表为空，序号仍覆盖整段")
                .isEqualTo(
                        new RecordsChanged(
                                OBJECT, "object", true, List.of(), List.of(), List.of(), null,
                                EPOCH, 1, 3));
        atCap.merge(created(OBJECT, "777"), 4, null);
        RecordsChanged later = atCap.frame(OBJECT, EPOCH, 4);
        assertThat(later.created()).as("降级之后并入的记录不再逐条列出").isEmpty();
        assertThat(later.seq()).isEqualTo(4);

        RecordLivePending bulk = new RecordLivePending(true);
        bulk.merge(updated(OBJECT, "1"), 1, null);
        bulk.merge(many(OBJECT), 2, null);
        assertThat(bulk.frame(OBJECT, EPOCH, 2).kind()).isEqualTo("object");
        assertThat(bulk.frame(OBJECT, EPOCH, 2).many()).isTrue();
    }

    @Test
    void originIsKeptOnlyWhenEveryMergedChangeSharesIt() {
        RecordLivePending same = new RecordLivePending(true);
        same.merge(updated(OBJECT, "1"), 1, "tab-aaaaaaaa");
        same.merge(updated(OBJECT, "2"), 2, "tab-aaaaaaaa");
        assertThat(same.frame(OBJECT, EPOCH, 2).origin()).isEqualTo("tab-aaaaaaaa");

        RecordLivePending other = new RecordLivePending(true);
        other.merge(updated(OBJECT, "1"), 1, "tab-aaaaaaaa");
        other.merge(updated(OBJECT, "2"), 2, "tab-bbbbbbbb");
        assertThat(other.frame(OBJECT, EPOCH, 2).origin()).as("混入另一个发起人").isNull();

        RecordLivePending withNull = new RecordLivePending(true);
        withNull.merge(updated(OBJECT, "1"), 1, "tab-aaaaaaaa");
        withNull.merge(updated(OBJECT, "2"), 2, null);
        assertThat(withNull.frame(OBJECT, EPOCH, 2).origin()).as("混入没有发起人的变更").isNull();

        RecordLivePending nullFirst = new RecordLivePending(true);
        nullFirst.merge(updated(OBJECT, "1"), 1, null);
        nullFirst.merge(updated(OBJECT, "2"), 2, "tab-aaaaaaaa");
        assertThat(nullFirst.frame(OBJECT, EPOCH, 2).origin())
                .as("第一份没有发起人、后面有：同样是混合，不能取后面的")
                .isNull();
    }

    @Test
    void subscriptionWithoutIdVisibilityOnlyGetsObjectFramesWithTruthfulMany() {
        RecordLivePending hidden = new RecordLivePending(false);
        hidden.merge(created(OBJECT, "9001"), 1, "tab-aaaaaaaa");
        assertThat(hidden.frame(OBJECT, EPOCH, 1))
                .as("因权限不给标识：object、三个列表为空、many=false")
                .isEqualTo(
                        new RecordsChanged(
                                OBJECT,
                                "object",
                                false,
                                List.of(),
                                List.of(),
                                List.of(),
                                "tab-aaaaaaaa",
                                EPOCH,
                                1,
                                1));
        hidden.delivered();
        hidden.merge(many(OBJECT), 2, null);
        assertThat(hidden.frame(OBJECT, EPOCH, 2).many()).as("量大的那一份照实为 true").isTrue();
        assertThat(hidden.frame(OBJECT, EPOCH, 2).kind()).isEqualTo("object");
    }

    @Test
    void fallingBehindDropsIdsAndTheNextFrameCarriesTheCurrentSequenceOnly() {
        RecordLivePending pending = new RecordLivePending(true);
        pending.merge(updated(OBJECT, "88"), 4, "tab-aaaaaaaa");
        pending.fallBehind();
        pending.merge(created(OBJECT, "9001"), 5, "tab-aaaaaaaa");
        pending.merge(many(OBJECT), 6, "tab-aaaaaaaa");
        assertThat(pending.frame(OBJECT, EPOCH, 6))
                .as("落后补发：object、many=false（即使其间有量大的变更）、不带标识与发起人、序号取当前值")
                .isEqualTo(
                        new RecordsChanged(
                                OBJECT, "object", false, List.of(), List.of(), List.of(), null,
                                EPOCH, 6, 6));
        pending.delivered();
        pending.merge(updated(OBJECT, "89"), 7, null);
        RecordsChanged recovered = pending.frame(OBJECT, EPOCH, 7);
        assertThat(recovered.kind()).as("补发成功后恢复逐条列出").isEqualTo("ids");
        assertThat(recovered.updated()).containsExactly("89");
        assertThat(recovered.fromSeq()).isEqualTo(7);
    }

    private static List<String> ids(int from, int to) {
        List<String> result = new ArrayList<>();
        for (int index = from; index <= to; index++) result.add(Integer.toString(index));
        return result;
    }
}
