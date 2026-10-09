package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.SelectionAllMigrationPlanner.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.tools.SelectionAllMigrationReport.Change;
import com.lingan.ucp.nocode.tools.SelectionAllMigrationReport.Item;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存量清单转「全部」的判据：纯函数，不连库。
 *
 * <p>夹具对象：字段 1 名称、2 金额、3 合计（公式，写不了）、9 备注；明细 d1；多对多关系 m1。「后加」由每条用例自己指定。
 */
class SelectionAllMigrationPlannerTest {
    private static final List<String> FIELDS = List.of("1", "2", "3", "9");
    private final ObjectMapper json = new ObjectMapper();

    /** later 里没有的 ID 一律当作「保存之前就已发布」；值为 null 表示查不到发布时间。 */
    private static Decision decide(List<String> stored, Map<String, Boolean> later) {
        return SelectionAllMigrationPlanner.decide(
                stored, FIELDS, id -> later.containsKey(id) ? later.get(id) : Boolean.FALSE);
    }

    @Test
    void listEqualToTheUniverseBecomesAll() {
        Decision decision = decide(List.of("9", "1", "3", "2"), Map.of());
        assertThat(decision.outcome()).isEqualTo(EXACT);
        assertThat(decision.after()).containsExactly("*");
        assertThat(decision.gained()).isEmpty();
        assertThat(decision.removedDead()).isZero();
    }

    @Test
    void listMissingOnlyItemsPublishedAfterTheSaveBecomesAllAndNamesWhatIsGained() {
        Decision decision = decide(List.of("1", "2", "3"), Map.of("9", true));
        assertThat(decision.outcome()).isEqualTo(LATER_ONLY);
        assertThat(decision.after()).containsExactly("*");
        assertThat(decision.gained()).as("因此新获得的项").containsExactly("9");
    }

    /** 放大方向的钉子：缺的字段 ID 比清单里最大的还大，但它在保存之前就已发布——那是故意没选，必须保留清单。 */
    @Test
    void largerIdThatWasAlreadyPublishedBeforeTheSaveIsKept() {
        Decision decision = decide(List.of("1", "2", "3"), Map.of("9", false));
        assertThat(decision.outcome()).isEqualTo(KEPT);
        assertThat(decision.after()).containsExactly("1", "2", "3");
        assertThat(decision.gained()).isEmpty();
    }

    @Test
    void oneDeliberateOmissionKeepsTheListEvenWhenAnotherItemIsLater() {
        Decision decision = decide(List.of("1", "3"), Map.of("9", true, "2", false));
        assertThat(decision.outcome()).isEqualTo(KEPT);
        assertThat(decision.after()).containsExactly("1", "3");
    }

    @Test
    void unknownPublishTimeIsUndeterminedAndKept() {
        Map<String, Boolean> later = new HashMap<>();
        later.put("9", null);
        Decision decision = decide(List.of("1", "2", "3"), later);
        assertThat(decision.outcome()).isEqualTo(UNDETERMINED);
        assertThat(decision.after()).containsExactly("1", "2", "3");
        assertThat(decision.gained()).isEmpty();
    }

    /** 查不到的与「保存之前就已发布」的同时存在：后者说了算（故意没选）。 */
    @Test
    void deliberateOmissionWinsOverUnknown() {
        Map<String, Boolean> later = new HashMap<>();
        later.put("9", null);
        later.put("2", false);
        assertThat(decide(List.of("1", "3"), later).outcome()).isEqualTo(KEPT);
    }

    @Test
    void emptyAndDeadOnlyListsStayEmpty() {
        Decision empty = decide(List.of(), Map.of("1", true, "2", true, "3", true, "9", true));
        assertThat(empty.outcome()).as("空永远不转").isEqualTo(KEPT);
        assertThat(empty.after()).isEmpty();
        Decision dead = decide(List.of("777", "888"), Map.of());
        assertThat(dead.outcome()).isEqualTo(KEPT);
        assertThat(dead.after()).isEmpty();
        assertThat(dead.removedDead()).isEqualTo(2);
    }

    @Test
    void deadIdsAreDroppedBeforeComparing() {
        Decision exact = decide(List.of("1", "2", "3", "9", "777"), Map.of());
        assertThat(exact.outcome()).isEqualTo(EXACT);
        assertThat(exact.removedDead()).isEqualTo(1);
        Decision kept = decide(List.of("1", "777"), Map.of());
        assertThat(kept.outcome()).isEqualTo(KEPT);
        assertThat(kept.after()).containsExactly("1");
        assertThat(kept.removedDead()).isEqualTo(1);
    }

    @Test
    void storedAllIsLeftAlone() {
        assertThat(decide(List.of("*"), Map.of()).outcome()).isEqualTo(ALREADY_ALL);
    }

    @Test
    void invariantCatchesLostAndUnrecordedGains() {
        assertThat(
                        violation(
                                List.of("1", "2"),
                                new Decision(KEPT, List.of("1"), 0, List.of()),
                                FIELDS,
                                "甲"))
                .isEqualTo("甲：转换后少了 [2]");
        assertThat(
                        violation(
                                List.of("1", "2", "3"),
                                new Decision(EXACT, List.of("*"), 0, List.of()),
                                FIELDS,
                                "甲"))
                .isEqualTo("甲：新获得的项 [9] 与记录的 [] 不符");
        assertThat(
                        violation(
                                List.of("1", "2", "3", "777"),
                                new Decision(LATER_ONLY, List.of("*"), 1, List.of("9")),
                                FIELDS,
                                "甲"))
                .as("死 ID 不算少了")
                .isNull();
    }

    // ── 整份授权 ──

    /** 测试用的转换上下文：没有上一层；字段 3 写不了。 */
    private static final class Fixed implements Dimensions {
        private final List<String> fields;
        private final Map<String, Boolean> later;

        Fixed(List<String> fields, Map<String, Boolean> later) {
            this.fields = fields;
            this.later = later;
        }

        @Override
        public List<String> readUniverse(String key) {
            return switch (key) {
                case "readFields" -> fields;
                case "readDetails" -> List.of("d1");
                default -> List.of("m1");
            };
        }

        @Override
        public List<String> writeUniverse(String key, List<String> readable) {
            return readable.stream()
                    .filter(id -> !"writeFields".equals(key) || !"3".equals(id))
                    .toList();
        }

        @Override
        public List<String> writeExpansion(String key, List<String> readable) {
            return readable;
        }

        @Override
        public Boolean later(String key, String id) {
            return later.containsKey(key + ":" + id) ? later.get(key + ":" + id) : Boolean.FALSE;
        }

        @Override
        public String name(String key, String id) {
            return "名" + id;
        }
    }

    private ObjectNode grant(
            List<String> readFields,
            List<String> writeFields,
            List<String> readDetails,
            List<String> writeDetails,
            List<String> readRelations,
            List<String> writeRelations) {
        ObjectNode node = json.createObjectNode();
        node.put("objectId", "100");
        node.putArray("actions").add("READ").add("UPDATE");
        node.put("scope", "ALL");
        node.set("readFields", json.valueToTree(readFields));
        node.set("writeFields", json.valueToTree(writeFields));
        node.set("readDetails", json.valueToTree(readDetails));
        node.set("writeDetails", json.valueToTree(writeDetails));
        node.set("readRelations", json.valueToTree(readRelations));
        node.set("writeRelations", json.valueToTree(writeRelations));
        node.putObject("actionScopes");
        node.putArray("computeFields").add("2");
        return node;
    }

    private static Change change(List<Change> changes, String dimension) {
        return changes.stream()
                .filter(c -> c.dimension().equals(dimension))
                .findFirst()
                .orElseThrow();
    }

    /** 写清单的全集只算写得了的字段：公式字段 3 不在其中，1、2、9 就是全部。 */
    @Test
    void writeUniverseOnlyCountsWritableFields() {
        ObjectNode before =
                grant(
                        FIELDS,
                        List.of("1", "2", "9"),
                        List.of("d1"),
                        List.of(),
                        List.of("m1"),
                        List.of("m1"));
        String snapshot = before.toString();
        List<Change> changes = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Fixed dimensions = new Fixed(FIELDS, Map.of());
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "角色「财务」",
                        changes,
                        violations);
        assertThat(before.toString()).as("输入不被修改").isEqualTo(snapshot);
        assertThat(violations).isEmpty();
        assertThat(strings(after.get("readFields"))).containsExactly("*");
        assertThat(strings(after.get("writeFields"))).containsExactly("*");
        assertThat(strings(after.get("readDetails"))).containsExactly("*");
        assertThat(strings(after.get("writeDetails"))).as("空永远不转").isEmpty();
        assertThat(strings(after.get("readRelations"))).containsExactly("*");
        assertThat(strings(after.get("writeRelations"))).containsExactly("*");
        assertThat(after.get("computeFields")).as("计算取数不碰").isEqualTo(before.get("computeFields"));
        assertThat(after.get("actions")).isEqualTo(before.get("actions"));
        assertThat(after.get("scope")).isEqualTo(before.get("scope"));
        assertThat(after.get("actionScopes")).isEqualTo(before.get("actionScopes"));
        assertThat(changes).hasSize(5).allMatch(c -> c.outcome().equals(EXACT));
        assertThat(change(changes, "writeFields").dimensionName()).isEqualTo("可填写和修改字段");
        assertThat(change(changes, "writeFields").holder()).isEqualTo("角色「财务」");
    }

    /** 读清单只差后加的字段 9 而转成「全部」时，写清单的全集随之包含 9：写清单同样只差它 ⇒ 也转，并各自记下新获得的项。 */
    @Test
    void laterOnlyReadCarriesTheWriteListWithIt() {
        ObjectNode before =
                grant(
                        List.of("1", "2", "3"),
                        List.of("1", "2"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        List<Change> changes = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Fixed dimensions = new Fixed(FIELDS, Map.of("readFields:9", true));
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "应用上限",
                        changes,
                        violations);
        assertThat(violations).isEmpty();
        assertThat(strings(after.get("readFields"))).containsExactly("*");
        assertThat(strings(after.get("writeFields"))).containsExactly("*");
        assertThat(change(changes, "readFields").outcome()).isEqualTo(LATER_ONLY);
        assertThat(change(changes, "readFields").gained()).containsExactly(new Item("9", "名9"));
        assertThat(change(changes, "writeFields").outcome()).isEqualTo(LATER_ONLY);
        assertThat(change(changes, "writeFields").gained()).containsExactly(new Item("9", "名9"));
        assertThat(changes).hasSize(2);
    }

    /** 读清单转了、写清单故意少选了早就有的字段 2：写清单保留，不放大写权限。 */
    @Test
    void writeListWithADeliberateOmissionStaysAListWhenReadBecomesAll() {
        ObjectNode before =
                grant(
                        List.of("1", "2", "3"),
                        List.of("1"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        List<Change> changes = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Fixed dimensions = new Fixed(FIELDS, Map.of("readFields:9", true));
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "应用上限",
                        changes,
                        violations);
        assertThat(strings(after.get("readFields"))).containsExactly("*");
        assertThat(strings(after.get("writeFields"))).containsExactly("1");
        assertThat(changes)
                .extracting(Change::dimension, Change::outcome)
                .as("保留的清单也进报告")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("readFields", LATER_ONLY),
                        org.assertj.core.groups.Tuple.tuple("writeFields", KEPT));
        assertThat(violations).isEmpty();
    }

    /** 读清单保留时，写清单的全集就是那份读清单里写得了的：写清单等于它 ⇒ 转「全部」（含义是「可读的都可写」，不越过读清单）。 */
    @Test
    void writeListEqualToTheKeptReadListBecomesAll() {
        ObjectNode before =
                grant(List.of("1", "3"), List.of("1"), List.of(), List.of(), List.of(), List.of());
        List<Change> changes = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Fixed dimensions = new Fixed(FIELDS, Map.of());
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "应用上限",
                        changes,
                        violations);
        assertThat(strings(after.get("readFields"))).containsExactly("1", "3");
        assertThat(strings(after.get("writeFields"))).containsExactly("*");
        assertThat(violations).isEmpty();
        assertThat(changes)
                .extracting(Change::dimension, Change::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("readFields", KEPT),
                        org.assertj.core.groups.Tuple.tuple("writeFields", EXACT));
    }

    @Test
    void undeterminedIsReportedWithoutChangingTheList() {
        Map<String, Boolean> later = new HashMap<>();
        later.put("readFields:9", null);
        ObjectNode before =
                grant(
                        List.of("1", "2", "3"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        List<Change> changes = new ArrayList<>();
        Fixed dimensions = new Fixed(FIELDS, later);
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "应用上限",
                        changes,
                        new ArrayList<>());
        assertThat(after).isEqualTo(before);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().outcome()).isEqualTo(UNDETERMINED);
        assertThat(changes.getFirst().after()).containsExactly("1", "2", "3");
    }

    @Test
    void absentKeysAreNotInventedAndUnknownKeysSurvive() {
        ObjectNode before =
                grant(FIELDS, List.of(), List.of("d1"), List.of(), List.of(), List.of());
        before.remove(List.of("readRelations", "writeRelations", "writeDetails"));
        before.putObject("futureKey").put("kept", true);
        Fixed dimensions = new Fixed(FIELDS, Map.of());
        ObjectNode after =
                convert(
                        before,
                        dimensions,
                        dimensions,
                        true,
                        "100",
                        "甲",
                        "应用上限",
                        new ArrayList<>(),
                        new ArrayList<>());
        assertThat(Set.copyOf(names(after))).isEqualTo(Set.copyOf(names(before)));
        assertThat(after.get("futureKey")).isEqualTo(before.get("futureKey"));
        assertThat(strings(after.get("readDetails"))).containsExactly("*");
    }

    private static List<String> names(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 做判定的全集错了（漏了字段 9）而核对用的全集是对的 ⇒ 不变式报出来。 */
    @Test
    void invariantIsCheckedAgainstAnIndependentUniverse() {
        ObjectNode before =
                grant(
                        List.of("1", "2", "3"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        List<String> violations = new ArrayList<>();
        convert(
                before,
                new Fixed(List.of("1", "2", "3"), Map.of()),
                new Fixed(FIELDS, Map.of()),
                true,
                "100",
                "甲",
                "应用上限",
                new ArrayList<>(),
                violations);
        assertThat(violations).containsExactly("应用上限 / 甲 / 可查看字段：新获得的项 [9] 与记录的 [] 不符");
    }

    @Test
    void expandTurnsAllBackIntoListsAndLeavesListsAlone() {
        ObjectNode stored =
                grant(
                        List.of("*"),
                        List.of("*"),
                        List.of("d1"),
                        List.of(),
                        List.of("*"),
                        List.of());
        Fixed dimensions = new Fixed(FIELDS, Map.of());
        ObjectNode expanded = expand(stored, dimensions);
        assertThat(strings(expanded.get("readFields"))).containsExactlyElementsOf(FIELDS);
        assertThat(strings(expanded.get("writeFields")))
                .as("可写的「全部」展开成运行期的含义：可读清单")
                .containsExactlyElementsOf(FIELDS);
        assertThat(strings(expanded.get("readDetails"))).containsExactly("d1");
        assertThat(strings(expanded.get("readRelations"))).containsExactly("m1");
        assertThat(expanded.toString()).doesNotContain("*");
        ObjectNode plain =
                grant(List.of("1"), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(expand(plain, dimensions)).as("没有「全部」时原样返回同一个对象").isSameAs(plain);
    }
}
