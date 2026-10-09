package com.richuang.os.nocode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.nocode.api.Selections;
import com.richuang.os.nocode.tools.SelectionAllMigrationReport.Change;
import com.richuang.os.nocode.tools.SelectionAllMigrationReport.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 存量授权清单转「全部」的判据（契约 7.1）。纯函数：不连库。
 *
 * <p>对一份清单 L、全集 U：先去掉已停用或不存在的 ID 得到 C；C 为空 ⇒ 保持空（空永远不转）；C 等于 U ⇒ 转「全部」（EXACT）； 否则看缺的那些项 M = U −
 * C：每一项都是「这份配置最后一次保存之后才发布出来的」⇒ 转「全部」（LATER_ONLY，M 就是因此新获得权限的项）； 只要有一项是保存之前就已发布的 ⇒
 * 保留清单（KEPT，那是故意没选）；没有这种项但有查不到发布时间的 ⇒ 保留清单并单列（UNDETERMINED）。
 *
 * <p>「后加」只认发布时间晚于保存时间，不用「ID 比清单里最大的还大」来猜：那会把「故意只开放前几个字段可改」误判，落在可修改字段上就是放大写权限。
 */
final class SelectionAllMigrationPlanner {
    static final String ALREADY_ALL = "ALREADY_ALL";
    static final String EXACT = "EXACT";
    static final String LATER_ONLY = "LATER_ONLY";
    static final String KEPT = "KEPT";
    static final String UNDETERMINED = "UNDETERMINED";

    static final List<String> READ_KEYS = List.of("readFields", "readDetails", "readRelations");

    private SelectionAllMigrationPlanner() {}

    /** 一份清单的判定结果；after 是转换后的清单，gained 是因此新获得权限的 ID。 */
    record Decision(String outcome, List<String> after, int removedDead, List<String> gained) {}

    /**
     * @param stored 库里的清单
     * @param universe 这个维度的全集（保持定义顺序）
     * @param later ID 是不是在这份配置最后一次保存之后才发布出来的；查不到发布时间返回 null
     */
    static Decision decide(
            List<String> stored, List<String> universe, Function<String, Boolean> later) {
        if (Selections.isAll(stored)) return new Decision(ALREADY_ALL, List.of("*"), 0, List.of());
        Set<String> available = new LinkedHashSet<>(universe);
        List<String> kept = stored.stream().filter(available::contains).distinct().toList();
        int dead = stored.size() - kept.size();
        if (kept.isEmpty()) return new Decision(KEPT, kept, dead, List.of());
        List<String> missing = universe.stream().filter(id -> !kept.contains(id)).toList();
        if (missing.isEmpty()) return new Decision(EXACT, List.of("*"), dead, List.of());
        boolean unknown = false;
        for (String id : missing) {
            Boolean added = later.apply(id);
            if (Boolean.FALSE.equals(added)) return new Decision(KEPT, kept, dead, List.of());
            if (added == null) unknown = true;
        }
        if (unknown) return new Decision(UNDETERMINED, kept, dead, List.of());
        return new Decision(LATER_ONLY, List.of("*"), dead, missing);
    }

    /** 不变式（契约 7.2）：展开(转换后) − 展开(转换前) 必须恰好等于记下的新获得项；展开(转换前) − 展开(转换后) 必须为空。返回违反说明，成立时为 null。 */
    static String violation(
            List<String> before, Decision decision, List<String> universe, String where) {
        Set<String> expandedBefore = new LinkedHashSet<>(Selections.resolve(before, universe));
        Set<String> expandedAfter =
                new LinkedHashSet<>(Selections.resolve(decision.after(), universe));
        Set<String> gained = new LinkedHashSet<>(expandedAfter);
        gained.removeAll(expandedBefore);
        Set<String> lost = new LinkedHashSet<>(expandedBefore);
        lost.removeAll(expandedAfter);
        if (!lost.isEmpty()) return where + "：转换后少了 " + lost;
        if (!gained.equals(new LinkedHashSet<>(decision.gained())))
            return where + "：新获得的项 " + gained + " 与记录的 " + decision.gained() + " 不符";
        return null;
    }

    /** 一份授权（JSON 对象，六个清单）的转换上下文：各维度的全集、名称与「是不是后加的」判定。 */
    interface Dimensions {
        /** 可读清单的全集。key 是 readFields、readDetails 或 readRelations。 */
        List<String> readUniverse(String key);

        /** 可写清单的全集：转换后的可读清单展开结果里、上一层允许写且写得了的那些。 */
        List<String> writeUniverse(String key, List<String> readable);

        /** 可写的「全部」在运行期展开成什么：可读清单里上一层允许写的那些（写不了的字段留在里面无害）。 */
        List<String> writeExpansion(String key, List<String> readable);

        Boolean later(String key, String id);

        String name(String key, String id);
    }

    static String writeKey(String readKey) {
        return "write" + readKey.substring("read".length());
    }

    static String label(String key) {
        return switch (key) {
            case "readFields" -> "可查看字段";
            case "writeFields" -> "可填写和修改字段";
            case "readDetails" -> "可查看内部明细";
            case "writeDetails" -> "可修改内部明细";
            case "readRelations" -> "可查看多对多关系";
            default -> "可修改多对多关系";
        };
    }

    /**
     * 转换一份授权：返回新的 JSON 对象（输入不被修改），并把每个维度的结果追加到 changes、不变式违反追加到 violations。 只动六个清单；
     * computeFields、actions、scope、actionScopes 与其它键一律不碰。原对象里没有的清单键不凭空加上。
     *
     * @param dimensions 做判定用的上下文
     * @param truth 核对不变式用的上下文：全集直接取自对象定义，与判定用的那份互相独立
     * @param convertWrites 引用资料类的范围不提供可写的「全部」时传 false：可写清单只清死 ID
     */
    static ObjectNode convert(
            ObjectNode grant,
            Dimensions dimensions,
            Dimensions truth,
            boolean convertWrites,
            String objectId,
            String objectName,
            String holder,
            List<Change> changes,
            List<String> violations) {
        ObjectNode result = grant.deepCopy();
        for (String readKey : READ_KEYS) {
            List<String> readBefore = strings(grant.get(readKey));
            List<String> readUniverse = dimensions.readUniverse(readKey);
            List<String> readAfter = readBefore;
            if (grant.has(readKey)) {
                Decision decision =
                        decide(readBefore, readUniverse, id -> dimensions.later(readKey, id));
                readAfter = decision.after();
                record(
                        result,
                        readKey,
                        readBefore,
                        decision,
                        truth.readUniverse(readKey),
                        dimensions,
                        objectId,
                        objectName,
                        holder,
                        changes,
                        violations);
            }
            String writeKey = writeKey(readKey);
            if (!grant.has(writeKey)) continue;
            List<String> writeBefore = strings(grant.get(writeKey));
            List<String> writeUniverse =
                    dimensions.writeUniverse(writeKey, Selections.resolve(readAfter, readUniverse));
            Decision decision =
                    convertWrites
                            ? decide(
                                    writeBefore, writeUniverse, id -> dimensions.later(readKey, id))
                            : cleaned(writeBefore, writeUniverse);
            record(
                    result,
                    writeKey,
                    writeBefore,
                    decision,
                    truth.writeUniverse(
                            writeKey, Selections.resolve(readAfter, truth.readUniverse(readKey))),
                    dimensions,
                    objectId,
                    objectName,
                    holder,
                    changes,
                    violations);
        }
        return result;
    }

    /** 只清死 ID，不转「全部」。 */
    private static Decision cleaned(List<String> stored, List<String> universe) {
        if (Selections.isAll(stored)) return new Decision(ALREADY_ALL, List.of("*"), 0, List.of());
        List<String> kept = stored.stream().filter(universe::contains).distinct().toList();
        return new Decision(KEPT, kept, stored.size() - kept.size(), List.of());
    }

    private static void record(
            ObjectNode result,
            String key,
            List<String> before,
            Decision decision,
            List<String> universe,
            Dimensions dimensions,
            String objectId,
            String objectName,
            String holder,
            List<Change> changes,
            List<String> violations) {
        if (ALREADY_ALL.equals(decision.outcome())) return;
        String where = holder + " / " + objectName + " / " + label(key);
        String violation = violation(before, decision, universe, where);
        if (violation != null) violations.add(violation);
        if (decision.after().equals(before)) {
            // 保留的清单也逐条进报告（结果 KEPT 或 UNDETERMINED），库里不改；本来就是空的清单不列。
            if (!before.isEmpty())
                changes.add(
                        new Change(
                                objectId,
                                objectName,
                                holder,
                                key,
                                label(key),
                                decision.outcome(),
                                0,
                                List.of(),
                                before,
                                before));
            return;
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        decision.after().forEach(array::add);
        result.set(key, array);
        String readKey = key.startsWith("write") ? "read" + key.substring("write".length()) : key;
        List<Item> gained = new ArrayList<>();
        for (String id : decision.gained()) gained.add(new Item(id, dimensions.name(readKey, id)));
        changes.add(
                new Change(
                        objectId,
                        objectName,
                        holder,
                        key,
                        label(key),
                        decision.outcome(),
                        decision.removedDead(),
                        List.copyOf(gained),
                        before,
                        decision.after()));
    }

    static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> result.add(item.asText()));
        return result;
    }

    /** 把一份授权里的「全部」按全集展开成显式清单（回滚到旧代码之前用）；没有「全部」时原样返回同一个对象。 */
    static ObjectNode expand(ObjectNode grant, Dimensions dimensions) {
        ObjectNode result = null;
        for (String readKey : READ_KEYS) {
            List<String> readStored = strings(grant.get(readKey));
            List<String> readUniverse = dimensions.readUniverse(readKey);
            List<String> readable = Selections.resolve(readStored, readUniverse);
            if (Selections.isAll(readStored)) {
                if (result == null) result = grant.deepCopy();
                result.set(readKey, array(readable));
            }
            String writeKey = writeKey(readKey);
            List<String> writeStored = strings(grant.get(writeKey));
            if (Selections.isAll(writeStored)) {
                if (result == null) result = grant.deepCopy();
                // 展开成清单时保持运行期的含义：可读清单里上一层允许写的那些。
                result.set(writeKey, array(dimensions.writeExpansion(writeKey, readable)));
            }
        }
        return result == null ? grant : result;
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }
}
