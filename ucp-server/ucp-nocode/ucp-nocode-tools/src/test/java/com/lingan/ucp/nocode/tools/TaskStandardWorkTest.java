package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.*;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskWorkRecordDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskStandardWork;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 标准工时只取有效快照，覆盖去重、不同人员/节点、数量差额和删除冲减。 */
class TaskStandardWorkTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskWorkEntryMapper store = mock(TaskWorkEntryMapper.class);
    private final TaskStandardWork work = new TaskStandardWork();

    TaskStandardWorkTest() {
        ReflectionTestUtils.setField(work, "json", json);
        ReflectionTestUtils.setField(work, "store", store);
    }

    private Config config(WorkRuleMode mode) {
        return new Config(
                "entry",
                "办理项",
                new TaskCenter.Binding("app", "form", null),
                DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                false,
                true,
                new WorkRule(mode, 10, "quantity", "state", "done"));
    }

    private TaskWorkRecordDO fact(
            String node,
            String actor,
            String record,
            Operation operation,
            Map<String, Object> values)
            throws Exception {
        TaskWorkRecordDO row = new TaskWorkRecordDO();
        row.setId(UUID.randomUUID().toString());
        row.setTaskId(node);
        row.setEntryKey("entry");
        row.setCreator(actor);
        row.setOperation(operation.name());
        row.setBusinessJson(
                json.writeValueAsString(
                        new TaskCenter.BusinessRef(
                                new PublishedResourceRef("app", 1, "checksum", "form", "FORM"),
                                null,
                                record,
                                null)));
        row.setSnapshotJson(
                json.writeValueAsString(
                        new ApplicationRecords.Aggregate(
                                new ApplicationRecords.Row(record, "1", values, null),
                                Map.of(),
                                List.of())));
        return row;
    }

    @Test
    void recordDeduplicatesWithinActorAndNodeAndIgnoresNoChangeAndLink() throws Exception {
        List<TaskWorkRecordDO> facts =
                List.of(
                        fact("node", "2", "a", Operation.UPDATED, Map.of()),
                        fact("other", "1", "a", Operation.CREATED, Map.of()),
                        fact("node", "1", "b", Operation.LINKED, Map.of()),
                        fact("node", "1", "c", Operation.UNCHANGED, Map.of()),
                        fact("node", "1", "a", Operation.UPDATED, Map.of()),
                        fact("node", "1", "a", Operation.CREATED, Map.of()));
        WorkSummary summary =
                work.summarize("node", config(WorkRuleMode.RECORD_ONCE), facts, 1, true);
        assertThat(summary.myMinutes()).isEqualByComparingTo("10");
        assertThat(summary.myRecordCount()).isEqualTo(1);
        assertThat(summary.totalMinutes()).isEqualByComparingTo("20");
        assertThat(summary.totalRecordCount()).isEqualTo(2);
        assertThat(
                        work.summarize("node", config(WorkRuleMode.RECORD_ONCE), facts, 1, false)
                                .totalMinutes())
                .isNull();
    }

    @Test
    void quantityUsesLatestOwnEffectiveSnapshotRatherThanAddingSaves() throws Exception {
        List<TaskWorkRecordDO> facts =
                List.of(
                        fact("node", "1", "a", Operation.UNCHANGED, Map.of("quantity", 999)),
                        fact("node", "2", "a", Operation.UPDATED, Map.of("quantity", 100)),
                        fact("node", "1", "a", Operation.UPDATED, Map.of("quantity", 2.5)),
                        fact("node", "1", "a", Operation.CREATED, Map.of("quantity", 5)));
        WorkSummary summary =
                work.summarize("node", config(WorkRuleMode.QUANTITY), facts, 1, false);
        assertThat(summary.myMinutes()).isEqualByComparingTo("25");
    }

    @Test
    void conditionOnlyCountsOnceAfterFirstMatchingEffectiveSave() throws Exception {
        List<TaskWorkRecordDO> facts =
                List.of(
                        fact("node", "1", "a", Operation.UPDATED, Map.of("state", "pending")),
                        fact("node", "1", "a", Operation.UPDATED, Map.of("state", "done")),
                        fact("node", "1", "a", Operation.CREATED, Map.of("state", "pending")));
        assertThat(
                        work.summarize("node", config(WorkRuleMode.CONDITION), facts, 1, false)
                                .myMinutes())
                .isEqualByComparingTo("10");
    }

    @Test
    void deleteReversesAllActorCreditsWithoutDiscardingFacts() throws Exception {
        List<TaskWorkRecordDO> facts =
                List.of(
                        fact("sibling", "3", "a", Operation.DELETED, Map.of()),
                        fact("node", "2", "a", Operation.UPDATED, Map.of()),
                        fact("node", "1", "a", Operation.CREATED, Map.of()));
        WorkSummary summary =
                work.summarize("node", config(WorkRuleMode.RECORD_ONCE), facts, 1, true);
        assertThat(summary.totalMinutes()).isZero();
        assertThat(summary.totalRecordCount()).isZero();
        assertThat(facts).hasSize(3);
    }

    @Test
    void pendingApprovalAndSupersededSubmissionDoNotCount() throws Exception {
        TaskWorkRecordDO pending = fact("node", "1", "a", Operation.CREATED, Map.of());
        pending.setBusinessJson(
                json.writeValueAsString(
                        new TaskCenter.BusinessRef(
                                new PublishedResourceRef("app", 1, "checksum", "form", "FORM"),
                                null,
                                null,
                                "request")));
        TaskWorkRecordDO superseded = fact("node", "1", "b", Operation.CREATED, Map.of());
        superseded.setSupersededBy("new");
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.RECORD_ONCE),
                                        List.of(pending, superseded),
                                        1,
                                        true)
                                .totalMinutes())
                .isZero();
        when(store.approvedRecord(pending.getId())).thenReturn("a");
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.RECORD_ONCE),
                                        List.of(pending),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("10");
    }

    @Test
    void rulesRejectInvalidDurationsAndMissingOperands() {
        assertThatThrownBy(
                        () ->
                                work.validate(
                                        new WorkRule(
                                                WorkRuleMode.RECORD_ONCE, -1, null, null, null)))
                .hasMessageContaining("标准工时");
        assertThatThrownBy(
                        () ->
                                work.validate(
                                        new WorkRule(WorkRuleMode.QUANTITY, 10, null, null, null)))
                .hasMessageContaining("数量字段");
        assertThatThrownBy(
                        () ->
                                work.validate(
                                        new WorkRule(
                                                WorkRuleMode.CONDITION,
                                                10,
                                                null,
                                                "state",
                                                List.of("done"))))
                .hasMessageContaining("条件字段");
    }

    @Test
    void missingRuleAndLegacyZeroPlaceholderDoNotRequireCountingFields() {
        assertThatCode(() -> work.validate(null)).doesNotThrowAnyException();
        for (WorkRuleMode mode : WorkRuleMode.values()) {
            WorkRule empty = new WorkRule(mode, 0, null, null, null);
            assertThatCode(() -> work.validate(empty, null, null)).doesNotThrowAnyException();
        }
        assertThatThrownBy(
                        () ->
                                work.validate(
                                        new WorkRule(
                                                WorkRuleMode.RECORD_ONCE, 0, null, null, null, -1)))
                .hasMessageContaining("调整后的标准工时");
    }

    private TaskWorkRecordDO priced(WorkRuleMode mode, int minutes, Object value) throws Exception {
        TaskWorkRecordDO fact =
                fact(
                        "node",
                        "1",
                        "a",
                        Operation.UPDATED,
                        mode == WorkRuleMode.QUANTITY
                                ? Map.of("quantity", value)
                                : Map.of("state", value));
        fact.setWorkRuleJson(
                json.writeValueAsString(new WorkRule(mode, minutes, "quantity", "state", "done")));
        return fact;
    }

    @Test
    void ruleSnapshotsPreserveFirstRecordAndFirstConditionRates() throws Exception {
        for (WorkRuleMode mode : List.of(WorkRuleMode.RECORD_ONCE, WorkRuleMode.CONDITION)) {
            assertThat(
                            work.summarize(
                                            "node",
                                            config(mode),
                                            List.of(
                                                    priced(mode, 60, "done"),
                                                    priced(mode, 30, "done")),
                                            1,
                                            true)
                                    .myMinutes())
                    .isEqualByComparingTo("30");
        }
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.CONDITION),
                                        List.of(
                                                priced(WorkRuleMode.CONDITION, 60, "done"),
                                                priced(WorkRuleMode.CONDITION, 30, "pending")),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("60");
    }

    @Test
    void quantityAddsAtNewRateAndCorrectionsReverseNewestOriginalSegments() throws Exception {
        TaskWorkRecordDO first = priced(WorkRuleMode.QUANTITY, 10, 5);
        TaskWorkRecordDO grow = priced(WorkRuleMode.QUANTITY, 20, 8);
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(grow, first),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("110");
        TaskWorkRecordDO shrink = priced(WorkRuleMode.QUANTITY, 99, 6);
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(shrink, grow, first),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("70");
        TaskWorkRecordDO regrow = priced(WorkRuleMode.QUANTITY, 30, 7);
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(regrow, shrink, grow, first),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("100");
        TaskWorkRecordDO zero = priced(WorkRuleMode.QUANTITY, 99, 0);
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(zero, regrow, shrink, grow, first),
                                        1,
                                        true)
                                .myMinutes())
                .isZero();
    }

    @Test
    void explicitUnmeteredHistoryDoesNotAcquireLaterRule() throws Exception {
        TaskWorkRecordDO before = fact("node", "1", "a", Operation.CREATED, Map.of());
        before.setWorkRuleJson("null");
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.RECORD_ONCE),
                                        List.of(before),
                                        1,
                                        true)
                                .myMinutes())
                .isZero();
    }

    @Test
    void stoppedQuantityKeepsBaselineAndIsNotBackfilledWhenCountingResumes() throws Exception {
        TaskWorkRecordDO first = priced(WorkRuleMode.QUANTITY, 10, 5);
        TaskWorkRecordDO stopped = priced(WorkRuleMode.QUANTITY, 0, 8);
        TaskWorkRecordDO resumed = priced(WorkRuleMode.QUANTITY, 20, 9);
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(stopped, first),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("50");
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.QUANTITY),
                                        List.of(resumed, stopped, first),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("70");
    }

    @Test
    void approvalUsesSubmissionRateAfterItBecomesEffective() throws Exception {
        TaskWorkRecordDO pending = priced(WorkRuleMode.RECORD_ONCE, 30, "done");
        pending.setBusinessJson(
                json.writeValueAsString(
                        new TaskCenter.BusinessRef(
                                new PublishedResourceRef("app", 1, "checksum", "form", "FORM"),
                                null,
                                null,
                                "request")));
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.RECORD_ONCE),
                                        List.of(pending),
                                        1,
                                        true)
                                .myMinutes())
                .isZero();
        when(store.approvedRecord(pending.getId())).thenReturn("a");
        assertThat(
                        work.summarize(
                                        "node",
                                        config(WorkRuleMode.RECORD_ONCE),
                                        List.of(pending),
                                        1,
                                        true)
                                .myMinutes())
                .isEqualByComparingTo("30");
    }
}
