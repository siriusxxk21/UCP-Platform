package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.api.TaskWorkEntries.Config;
import com.lingan.ucp.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 逐项范围不回填旧协议，也不允许共享入口绕过来源范围。 */
class TaskEntryScopesTest {
    private Config config(String key, boolean all, DataAccessMode scope) {
        return new Config(
                key,
                key,
                new Binding("app", "form", null),
                TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                false,
                all,
                null,
                scope);
    }

    @Test
    void explicitEntryScopeOverridesOnlyItselfAndLegacyPolicyRemainsIntact() {
        DataPolicy old = new DataPolicy(1, DataAccessMode.ALL, DataAccessMode.GROUP);
        assertThat(TaskEntryScopes.effective(config("__business", false, null), old))
                .isEqualTo(DataAccessMode.ALL);
        assertThat(TaskEntryScopes.effective(config("other", true, null), old))
                .isEqualTo(DataAccessMode.GROUP);
        assertThat(TaskEntryScopes.effective(config("__business", true, DataAccessMode.GROUP), old))
                .isEqualTo(DataAccessMode.GROUP);
        assertThat(TaskEntryScopes.effective(config("other", false, DataAccessMode.ALL), old))
                .isEqualTo(DataAccessMode.ALL);
        assertThat(old).isEqualTo(new DataPolicy(1, DataAccessMode.ALL, DataAccessMode.GROUP));
    }

    @Test
    void historicalJsonDoesNotGainAStoredScopeOrChangeAllowAllDefault() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Config old =
                json.readValue(
                        "{\"key\":\"legacy\",\"name\":\"旧项\",\"dataMode\":\"INDEPENDENT\",\"allowAll\":true}",
                        Config.class);
        assertThat(old.dataScope()).isNull();
        assertThat(TaskEntryScopes.effective(old, null)).isEqualTo(DataAccessMode.ALL);
        assertThat(TaskEntryScopes.effective(config("local", false, DataAccessMode.ALL), null))
                .isEqualTo(DataAccessMode.ALL);
        assertThat(TaskEntryScopes.effective(config("local", true, DataAccessMode.GROUP), null))
                .isEqualTo(DataAccessMode.GROUP);
    }

    @Test
    void sourceScopeCannotBeExpandedByChildAndHistoricalNarrowingIsPreserved() {
        Config source = config("source", true, DataAccessMode.GROUP);
        assertThat(TaskEntryScopes.shared(config("child", true, DataAccessMode.ALL), source))
                .isEqualTo(DataAccessMode.GROUP);
        assertThat(
                        TaskEntryScopes.shared(
                                config("child", false, null),
                                config("source", true, DataAccessMode.ALL)))
                .isEqualTo(DataAccessMode.GROUP);
        assertThat(
                        TaskEntryScopes.shared(
                                config("child", false, DataAccessMode.ALL),
                                config("source", false, DataAccessMode.ALL)))
                .isEqualTo(DataAccessMode.ALL);
    }

    @Test
    void launchCannotWidenScopeFrozenInPublishedTemplate() {
        NodeInput approved =
                new NodeInput(
                        "root",
                        null,
                        "模板",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(config("entry", false, DataAccessMode.GROUP)));
        NodeInput tampered =
                new NodeInput(
                        "root",
                        null,
                        "模板",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(config("entry", false, DataAccessMode.ALL)));
        TaskDataPolicies.FrozenRoot frozen =
                new TaskDataPolicies.FrozenRoot(
                        approved, new TaskDataPolicyCompiler.Frozen(1L, List.of()));
        assertThatThrownBy(() -> new TaskDataPolicies().requireSame(tampered, frozen))
                .hasMessageContaining("模板的数据权限和资源已经固定");
    }

    @Test
    void templateNodeRemappingPreservesEntryScopeAndWorkRule() {
        TaskWorkEntries.WorkRule rule =
                new TaskWorkEntries.WorkRule(
                        TaskWorkEntries.WorkRuleMode.RECORD_ONCE, 15, null, null, null);
        Config entry =
                new Config(
                        "work",
                        "办理项",
                        new Binding("app", "form", null),
                        TaskWorkEntries.DataMode.SOURCE_SHARED,
                        "parent",
                        "source",
                        null,
                        null,
                        false,
                        false,
                        rule,
                        DataAccessMode.GROUP);
        NodeInput node =
                new NodeInput(
                        "child",
                        "parent",
                        "子任务",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        new Sharing(DataMode.INDEPENDENT, null, List.of()),
                        List.of(entry));
        NodeInput mapped =
                ReflectionTestUtils.invokeMethod(
                        new TaskCenterServiceImpl(),
                        "remap",
                        node,
                        Map.of("child", "new-child", "parent", "new-parent"),
                        "child");
        assertThat(mapped.entries().getFirst().dataScope()).isEqualTo(DataAccessMode.GROUP);
        assertThat(mapped.entries().getFirst().workRule()).isEqualTo(rule);
        assertThat(mapped.entries().getFirst().sourceNodeId()).isEqualTo("new-parent");
    }
}
