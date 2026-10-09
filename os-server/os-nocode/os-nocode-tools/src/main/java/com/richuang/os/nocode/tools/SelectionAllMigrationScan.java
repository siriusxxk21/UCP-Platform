package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.SelectionAllMigrationReport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.module.system.api.permission.RoleApi;
import com.richuang.os.module.system.api.permission.dto.RoleRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator.Universe;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 一次对库的扫描：读出四个存放处的授权清单，逐行算出转换计划（或「全部」的展开计划）。只读，不写库；必须在调用方开好的事务里跑。
 *
 * <p>顺序：先对象→应用授权，再应用成员授权与任务入口成员授权（它们的全集依赖前者转换后的结果），最后应用草稿里的任务入口允许范围。
 * 已发布的应用快照不在扫描范围内。computeFields、允许操作、记录范围、记录条件一律不碰；授权已撤销的行跳过。
 *
 * <p>「后加」的判据：字段、明细、关系第一次出现在对象已发布版本里的时间，晚于这份配置最后一次保存的时间。授权表的保存时间是不带时区的墙上时间， 按数据库会话时区换算（JDBC
 * 驱动把会话时区设成 JVM 时区，所以工具必须与在线服务用同一 JVM 时区启动）。
 */
final class SelectionAllMigrationScan {
    private static final List<String> KEYS =
            List.of(
                    "readFields",
                    "writeFields",
                    "readDetails",
                    "writeDetails",
                    "readRelations",
                    "writeRelations");
    private static final String NUMERIC_ID = "[1-9][0-9]{0,18}";

    /** 把保存时间换算成时刻（saved_at），并原样带出墙上时间的文本（saved_text，回滚时恢复用）。 */
    private static final String SAVED_AT =
            "(%1$s.update_time AT TIME ZONE current_setting('TimeZone')) AS saved_at,"
                    + " %1$s.update_time::text AS saved_text";

    private final JdbcTemplate jdbc;
    private final DataObjectApi objects;
    private final RoleApi roles;
    private final AdminUserApi users;
    private final ObjectMapper json;
    private final ObjectGrantValidator grants = new ObjectGrantValidator();

    /** 做判定用的全集来源；不变式永远对着直接从对象定义算出的全集核对，与它无关。 */
    private final Function<DataCenter.Definition, Universe> planningUniverse;

    /** 只处理应用编码以它开头的应用；空串为全部。 */
    private final String prefix;

    /** 为真时做的是「把全部展开成清单」，否则是「把清单转成全部」。 */
    private final boolean expand;

    final List<Row> rows = new ArrayList<>();
    final List<String> violations = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();

    /** （对象:应用）→ 本次转换后的对象→应用授权；成员与入口的全集取自它。 */
    private final Map<String, ObjectNode> ceilings = new HashMap<>();

    private final Map<String, ObjectFacts> facts = new HashMap<>();
    private final Map<String, DataCenter.Definition> versions = new HashMap<>();

    SelectionAllMigrationScan(
            JdbcTemplate jdbc,
            DataObjectApi objects,
            RoleApi roles,
            AdminUserApi users,
            ObjectMapper json,
            Function<DataCenter.Definition, Universe> planningUniverse,
            String prefix,
            boolean expand) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.roles = roles;
        this.users = users;
        this.json = json;
        this.planningUniverse = planningUniverse;
        this.prefix = prefix == null ? "" : prefix;
        this.expand = expand;
    }

    SelectionAllMigrationScan run() {
        scanObjectGrants();
        scanMembers();
        scanEntryMembers();
        scanEntryLimits();
        return this;
    }

    private boolean included(Object applicationCode) {
        return prefix.isEmpty() || Objects.toString(applicationCode, "").startsWith(prefix);
    }

    // ── 对象版本 ──

    /** 对象的最新发布版、已发布的版本号、以及「每个字段、明细、关系第一次出现在已发布版本里的时间」。 对象未发布或已停用时没有这份事实（null）。 */
    private record ObjectFacts(
            DataCenter.Definition latest,
            Set<Integer> published,
            Map<String, Instant> firstPublished) {}

    private ObjectFacts facts(String objectId) {
        if (facts.containsKey(objectId)) return facts.get(objectId);
        ObjectFacts result = null;
        List<Map<String, Object>> head =
                objectId.matches(NUMERIC_ID)
                        ? jdbc.queryForList(
                                "SELECT status, current_published_version_no FROM"
                                        + " public.nocode_object WHERE id=? AND deleted=0",
                                Long.valueOf(objectId))
                        : List.of();
        if (!head.isEmpty()
                && "ACTIVE".equals(head.getFirst().get("status"))
                && head.getFirst().get("current_published_version_no") != null) {
            int current = ((Number) head.getFirst().get("current_published_version_no")).intValue();
            Map<String, Instant> first = new HashMap<>();
            Set<Integer> published = new HashSet<>();
            for (Map<String, Object> version :
                    jdbc.queryForList(
                            "SELECT version_no, published_at FROM public.nocode_object_version"
                                    + " WHERE object_id=? AND state='PUBLISHED' AND deleted=0 AND"
                                    + " version_no <= ? ORDER BY version_no",
                            Long.valueOf(objectId),
                            current)) {
                int number = ((Number) version.get("version_no")).intValue();
                published.add(number);
                Instant publishedAt = instant(version.get("published_at"));
                Universe universe = Universe.of(version(objectId, number));
                for (String id : universe.fields()) first(first, "readFields:" + id, publishedAt);
                for (String id : universe.details()) first(first, "readDetails:" + id, publishedAt);
                for (String id : universe.relations())
                    first(first, "readRelations:" + id, publishedAt);
            }
            if (published.contains(current))
                result = new ObjectFacts(version(objectId, current), published, first);
        }
        facts.put(objectId, result);
        return result;
    }

    private DataCenter.Definition version(String objectId, int number) {
        return versions.computeIfAbsent(
                objectId + ":" + number, key -> objects.getVersion(objectId, number).definition());
    }

    /** 只记第一次出现的那个版本的发布时间；那个版本查不到发布时间就记成 null（「后加」判定为查不到），不让更晚的版本顶替它。 */
    private static void first(Map<String, Instant> first, String key, Instant publishedAt) {
        if (!first.containsKey(key)) first.put(key, publishedAt);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof Instant time) return time;
        throw new IllegalStateException("无法识别的时间类型：" + value.getClass());
    }

    // ── JSON ──

    private JsonNode tree(Object text) {
        try {
            return json.readTree(text.toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("授权 JSON 无法读取", e);
        }
    }

    private ObjectGrant grant(JsonNode node) {
        try {
            return json.treeToValue(node, ObjectGrant.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("授权 JSON 无法读取", e);
        }
    }

    // ── 一份授权的转换上下文 ──

    /**
     * upper 是上一层对着对象定义展开后的授权（对象→应用授权没有上一层，传 null 表示对象本身）；savedAt 是这份配置最后一次保存的时间，为 null
     * 表示没有保存时间可比（应用草稿里的入口范围）：这时只认「恰好等于全部」。
     */
    private final class Bound implements SelectionAllMigrationPlanner.Dimensions {
        private final DataCenter.Definition definition;
        private final ObjectGrant upper;
        private final Map<String, Instant> firstPublished;
        private final Instant savedAt;
        private final boolean planning;

        Bound(
                DataCenter.Definition definition,
                ObjectGrant upper,
                Map<String, Instant> firstPublished,
                Instant savedAt,
                boolean planning) {
            this.definition = definition;
            this.upper = upper;
            this.firstPublished = firstPublished;
            this.savedAt = savedAt;
            this.planning = planning;
        }

        /** 核对不变式用的同一上下文：全集直接取自对象定义。 */
        Bound truth() {
            return new Bound(definition, upper, firstPublished, savedAt, false);
        }

        private List<String> universe(String readKey) {
            Universe universe =
                    planning ? planningUniverse.apply(definition) : Universe.of(definition);
            return switch (readKey) {
                case "readFields" -> universe.fields();
                case "readDetails" -> universe.details();
                default -> universe.relations();
            };
        }

        private Set<String> upperList(String key) {
            if (upper == null) return null;
            return switch (key) {
                case "readFields" -> upper.readFields();
                case "writeFields" -> upper.writeFields();
                case "readDetails" -> upper.readDetails();
                case "writeDetails" -> upper.writeDetails();
                case "readRelations" -> upper.readRelations();
                default -> upper.writeRelations();
            };
        }

        @Override
        public List<String> readUniverse(String key) {
            Set<String> allowed = upperList(key);
            return universe(key).stream()
                    .filter(id -> allowed == null || allowed.contains(id))
                    .toList();
        }

        @Override
        public List<String> writeUniverse(String key, List<String> readable) {
            return writeExpansion(key, readable).stream()
                    .filter(
                            id ->
                                    !"writeFields".equals(key)
                                            || ObjectGrantValidator.writable(definition, id))
                    .toList();
        }

        @Override
        public List<String> writeExpansion(String key, List<String> readable) {
            Set<String> allowed = upperList(key);
            return readable.stream().filter(id -> allowed == null || allowed.contains(id)).toList();
        }

        @Override
        public Boolean later(String key, String id) {
            if (savedAt == null) return Boolean.FALSE;
            Instant published = firstPublished.get(key + ":" + id);
            return published == null ? null : published.isAfter(savedAt);
        }

        @Override
        public String name(String key, String id) {
            return switch (key) {
                case "readFields" ->
                        definition.fields().stream()
                                .filter(f -> f.id().equals(id))
                                .map(f -> f.name())
                                .findFirst()
                                .orElse("已不存在的字段");
                case "readDetails" ->
                        definition.details().stream()
                                .filter(t -> t.id().equals(id))
                                .map(DataCenter.Detail::name)
                                .findFirst()
                                .orElse("已不存在的明细");
                default ->
                        definition.relations().stream()
                                .filter(r -> r.id().equals(id))
                                .map(DataCenter.Relation::name)
                                .findFirst()
                                .orElse("已不存在的关系");
            };
        }
    }

    private ObjectNode convert(
            ObjectNode source, Bound bound, String objectId, String holder, List<Change> changes) {
        if (!expand)
            return SelectionAllMigrationPlanner.convert(
                    source,
                    bound,
                    bound.truth(),
                    true,
                    objectId,
                    bound.definition.objectName(),
                    holder,
                    changes,
                    violations);
        ObjectNode expanded = SelectionAllMigrationPlanner.expand(source, bound.truth());
        if (expanded != source)
            for (String key : KEYS)
                if (!Objects.equals(source.get(key), expanded.get(key)))
                    changes.add(
                            new Change(
                                    objectId,
                                    bound.definition.objectName(),
                                    holder,
                                    key,
                                    SelectionAllMigrationPlanner.label(key),
                                    EXPANDED,
                                    0,
                                    List.of(),
                                    SelectionAllMigrationPlanner.strings(source.get(key)),
                                    SelectionAllMigrationPlanner.strings(expanded.get(key))));
        return expanded;
    }

    // ── ① 对象→应用授权 ──

    private void scanObjectGrants() {
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT g.object_id, g.application_id, a.app_code, a.app_name,"
                                + " g.grant_json::text AS body, "
                                + SAVED_AT.formatted("g")
                                + " FROM public.nocode_object_application_grant g JOIN"
                                + " public.nocode_application a ON a.id=g.application_id WHERE"
                                + " g.deleted=0 AND a.deleted=0 ORDER BY g.application_id,"
                                + " g.object_id")) {
            if (!included(row.get("app_code"))) continue;
            String objectId = row.get("object_id").toString();
            String applicationId = row.get("application_id").toString();
            // 授权已撤销的行存的是 JSON null：跳过，也不给成员当上限。
            if (!(tree(row.get("body")) instanceof ObjectNode before)) continue;
            ObjectFacts known = facts(objectId);
            if (known == null) {
                skipped.add(
                        "对象→应用授权：应用「" + row.get("app_name") + "」的对象 " + objectId + " 未发布或已停用，跳过");
                continue;
            }
            List<Change> changes = new ArrayList<>();
            ObjectNode after =
                    convert(
                            before,
                            new Bound(
                                    known.latest(),
                                    null,
                                    known.firstPublished(),
                                    instant(row.get("saved_at")),
                                    true),
                            objectId,
                            "应用上限",
                            changes);
            ceilings.put(objectId + ":" + applicationId, after);
            if (!changes.isEmpty())
                rows.add(
                        new Row(
                                OBJECT_GRANT,
                                applicationId,
                                Objects.toString(row.get("app_name")),
                                objectId,
                                null,
                                Objects.toString(row.get("saved_text")),
                                before,
                                after,
                                changes,
                                PLANNED));
        }
    }

    // ── ②④ 成员授权 ──

    private String holder(JsonNode member) {
        String id = member.path("principalId").asText();
        boolean user = "USER".equals(member.path("principalKind").asText());
        String name = null;
        try {
            if (id.matches(NUMERIC_ID)) {
                if (user && users != null) {
                    AdminUserRespDTO found = users.getUser(Long.parseLong(id));
                    name = found == null ? null : found.getNickname();
                } else if (!user && roles != null) {
                    RoleRespDTO found = roles.getRole(Long.parseLong(id));
                    name = found == null ? null : found.getName();
                }
            }
        } catch (RuntimeException unavailable) {
            // 名称只用于留底清单；查不到时用编号代替，不影响转换。
            name = null;
        }
        return (user ? "用户「" : "角色「") + (name == null ? "编号 " + id : name) + "」";
    }

    /**
     * 转换一份成员列表（应用成员授权与入口成员授权共用）。上一层是本次转换后的对象→应用授权；entry 非空时再收到入口的允许范围之内
     * （入口范围里的「全部」按应用草稿固定的对象版本展开，与运行期一致）。
     *
     * @param where 跳过说明与留底清单里的前缀
     */
    private ArrayNode members(
            ArrayNode before,
            String applicationId,
            Instant savedAt,
            String where,
            Entry entry,
            List<Change> changes) {
        ArrayNode after = before.deepCopy();
        for (JsonNode member : after) {
            if (!(member.get("objects") instanceof ArrayNode grantsOfMember)) continue;
            for (int i = 0; i < grantsOfMember.size(); i++) {
                if (!(grantsOfMember.get(i) instanceof ObjectNode source)) continue;
                String objectId = source.path("objectId").asText();
                ObjectNode ceiling = ceilings.get(objectId + ":" + applicationId);
                ObjectFacts known = facts(objectId);
                if (ceiling == null || known == null) {
                    skipped.add(where + holder(member) + "：对象 " + objectId + " 没有有效的应用上限，跳过");
                    continue;
                }
                ObjectGrant upper = grant(ceiling);
                if (entry != null) {
                    ObjectNode limit = entry.limits().get(objectId);
                    Integer number = entry.pinned().get(objectId);
                    if (limit == null || number == null || !known.published().contains(number)) {
                        skipped.add(where + holder(member) + "：对象 " + objectId + " 不在入口的允许范围里，跳过");
                        continue;
                    }
                    upper =
                            grants.intersect(
                                    grants.resolve(grant(limit), version(objectId, number)), upper);
                }
                grantsOfMember.set(
                        i,
                        convert(
                                source,
                                new Bound(
                                        known.latest(),
                                        grants.resolve(upper, known.latest()),
                                        known.firstPublished(),
                                        savedAt,
                                        true),
                                objectId,
                                where + holder(member),
                                changes));
            }
        }
        return after;
    }

    private void scanMembers() {
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT p.application_id, a.app_code, a.app_name, p.policy_json::text AS"
                                + " body, "
                                + SAVED_AT.formatted("p")
                                + " FROM public.nocode_application_access p JOIN"
                                + " public.nocode_application a ON a.id=p.application_id WHERE"
                                + " p.deleted=0 AND a.deleted=0 ORDER BY p.application_id")) {
            if (!included(row.get("app_code"))) continue;
            if (!(tree(row.get("body")) instanceof ArrayNode before)) continue;
            String applicationId = row.get("application_id").toString();
            List<Change> changes = new ArrayList<>();
            ArrayNode after =
                    members(before, applicationId, instant(row.get("saved_at")), "", null, changes);
            if (!changes.isEmpty())
                rows.add(
                        new Row(
                                APPLICATION_ACCESS,
                                applicationId,
                                Objects.toString(row.get("app_name")),
                                null,
                                null,
                                Objects.toString(row.get("saved_text")),
                                before,
                                after,
                                changes,
                                PLANNED));
        }
    }

    /** 应用草稿里的一个任务入口：名称、对象 ID → 允许范围、草稿固定的对象版本（对象 ID → 版本号）。 */
    private record Entry(
            String name, Map<String, ObjectNode> limits, Map<String, Integer> pinned) {}

    private static Map<String, Integer> pinned(JsonNode design) {
        Map<String, Integer> pinned = new HashMap<>();
        for (JsonNode reference : design.path("objects"))
            pinned.put(reference.path("objectId").asText(), reference.path("versionNo").asInt());
        return pinned;
    }

    private Map<String, Entry> entries(JsonNode design) {
        Map<String, Entry> result = new LinkedHashMap<>();
        Map<String, Integer> pinned = pinned(design);
        for (JsonNode resource : design.path("resources")) {
            if (!"TASK_ENTRY".equals(resource.path("kind").asText())) continue;
            Map<String, ObjectNode> limits = new LinkedHashMap<>();
            for (JsonNode limit : resource.path("config").path("limits"))
                if (limit instanceof ObjectNode value)
                    limits.put(value.path("objectId").asText(), value);
            String id = resource.path("id").asText();
            result.put(id, new Entry(resource.path("name").asText(id), limits, pinned));
        }
        return result;
    }

    private void scanEntryMembers() {
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT p.application_id, p.entry_id, a.app_code, a.app_name,"
                                + " a.design_json::text AS design, p.policy_json::text AS body, "
                                + SAVED_AT.formatted("p")
                                + " FROM public.nocode_task_entry_access p JOIN"
                                + " public.nocode_application a ON a.id=p.application_id WHERE"
                                + " p.deleted=0 AND a.deleted=0 ORDER BY p.application_id,"
                                + " p.entry_id")) {
            if (!included(row.get("app_code"))) continue;
            if (!(tree(row.get("body")) instanceof ArrayNode before)) continue;
            String applicationId = row.get("application_id").toString();
            String entryId = row.get("entry_id").toString();
            Entry entry = entries(tree(row.get("design"))).get(entryId);
            if (entry == null) {
                if (!before.isEmpty())
                    skipped.add(
                            "任务入口成员授权：应用「" + row.get("app_name") + "」的草稿里没有入口 " + entryId + "，跳过");
                continue;
            }
            List<Change> changes = new ArrayList<>();
            ArrayNode after =
                    members(
                            before,
                            applicationId,
                            instant(row.get("saved_at")),
                            "入口「" + entry.name() + "」· ",
                            entry,
                            changes);
            if (!changes.isEmpty())
                rows.add(
                        new Row(
                                ENTRY_ACCESS,
                                applicationId,
                                Objects.toString(row.get("app_name")),
                                null,
                                entryId,
                                Objects.toString(row.get("saved_text")),
                                before,
                                after,
                                changes,
                                PLANNED));
        }
    }

    // ── ③ 应用草稿里的任务入口允许范围 ──

    /** 全集是草稿固定的对象版本；没有保存时间可比，只认「恰好等于全部」。一行带出该应用全部入口的范围（入口 ID → 范围数组）。 */
    private void scanEntryLimits() {
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT a.id, a.app_code, a.app_name, a.design_json::text AS design,"
                            + " a.update_time::text AS saved_text FROM public.nocode_application a"
                            + " WHERE a.deleted=0 AND a.design_json::text LIKE '%TASK_ENTRY%' ORDER"
                            + " BY a.id")) {
            if (!included(row.get("app_code"))) continue;
            JsonNode design = tree(row.get("design"));
            Map<String, Integer> pinned = pinned(design);
            ObjectNode before = json.createObjectNode(), after = json.createObjectNode();
            List<Change> changes = new ArrayList<>();
            for (JsonNode resource : design.path("resources")) {
                if (!"TASK_ENTRY".equals(resource.path("kind").asText())) continue;
                if (!(resource.path("config").path("limits") instanceof ArrayNode limits)) continue;
                String entryId = resource.path("id").asText();
                ArrayNode converted = limits.deepCopy();
                for (int i = 0; i < converted.size(); i++) {
                    if (!(converted.get(i) instanceof ObjectNode source)) continue;
                    String objectId = source.path("objectId").asText();
                    Integer number = pinned.get(objectId);
                    ObjectFacts known = number == null ? null : facts(objectId);
                    if (known == null || !known.published().contains(number)) {
                        skipped.add(
                                "任务入口允许范围：应用「"
                                        + row.get("app_name")
                                        + "」入口 "
                                        + entryId
                                        + " 的对象 "
                                        + objectId
                                        + " 没有可用的固定版本，跳过");
                        continue;
                    }
                    converted.set(
                            i,
                            convert(
                                    source,
                                    new Bound(
                                            version(objectId, number), null, Map.of(), null, true),
                                    objectId,
                                    "入口「" + resource.path("name").asText(entryId) + "」的允许范围",
                                    changes));
                }
                before.set(entryId, limits);
                after.set(entryId, converted);
            }
            if (!changes.isEmpty())
                rows.add(
                        new Row(
                                ENTRY_LIMIT,
                                row.get("id").toString(),
                                Objects.toString(row.get("app_name")),
                                null,
                                null,
                                Objects.toString(row.get("saved_text")),
                                before,
                                after,
                                changes,
                                PLANNED));
        }
    }
}
