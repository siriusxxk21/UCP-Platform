package com.lingan.ucp.nocode.web.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 跨进程转发：把本进程提交的记录变更发到 Redis 频道，别的进程（服务进程）收到后分发给自己的订阅。导入工具等不是 Web 应用的进程只发布、不消费。
 *
 * <p>频道名带数据库标识：Redis 发布订阅不按 db 编号隔离，测试环境与线上共用实例时不带标识会串。发布失败只记日志（同一原因 60 秒一次），不重试；失败后短暂停发，避免 Redis
 * 不通时每个批都等一次超时而拖住本地分发。
 */
public class RecordLiveRelay implements RecordLiveHub.Relay {
    public static final String CHANNEL_PREFIX = "nocode:live:";
    static final int VERSION = 1;
    static final long WARN_INTERVAL_MILLIS = 60_000;
    static final long PAUSE_MILLIS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(RecordLiveRelay.class);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern ORIGIN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    /** 一条从别的进程转来的变更。 */
    public record Incoming(RecordChangeBatch batch, String origin) {}

    private final StringRedisTemplate redis;
    private final String channel;
    private final String node = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper();
    private final LongSupplier clock;
    private volatile long pausedUntil;
    private String warnedReason;
    private long warnedAt;

    public RecordLiveRelay(StringRedisTemplate redis, String channel) {
        this(redis, channel, System::currentTimeMillis);
    }

    public RecordLiveRelay(StringRedisTemplate redis, String channel, LongSupplier clock) {
        this.redis = redis;
        this.channel = channel;
        this.clock = clock;
    }

    /** 频道名：配置值非空则整体覆盖；否则为前缀加数据库标识。取不到数据库标识时返回 null（不转发）。 */
    public static String channelName(String configured, Supplier<String> database) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        String name = database.get();
        return name == null || name.isBlank() ? null : CHANNEL_PREFIX + name;
    }

    public String channel() {
        return channel;
    }

    public String node() {
        return node;
    }

    @Override
    public void publish(RecordChangeBatch batch, String origin) {
        if (channel == null || batch == null || batch.objects().isEmpty()) return;
        long now = clock.getAsLong();
        if (now < pausedUntil) return;
        try {
            redis.convertAndSend(channel, encode(batch, origin));
        } catch (Throwable error) {
            pausedUntil = now + PAUSE_MILLIS;
            String reason = error.getClass().getName() + ": " + error.getMessage();
            synchronized (this) {
                if (reason.equals(warnedReason) && now - warnedAt < WARN_INTERVAL_MILLIS) return;
                warnedReason = reason;
                warnedAt = now;
            }
            log.warn("记录变更转发失败（不重试，本地分发不受影响）: channel={}, error={}", channel, reason);
        }
    }

    /**
     * 线上格式：{"v":1,"node":…,"origin":…或null,"objects":[{"objectId","many","created","updated","deleted"}]}。
     */
    public String encode(RecordChangeBatch batch, String origin) {
        ObjectNode root = json.createObjectNode();
        root.put("v", VERSION);
        root.put("node", node);
        if (origin == null) root.putNull("origin");
        else root.put("origin", origin);
        ArrayNode objects = root.putArray("objects");
        for (RecordChangeBatch.ObjectChange change : batch.objects()) {
            ObjectNode item = objects.addObject();
            item.put("objectId", change.objectId());
            item.put("many", change.many());
            ArrayNode created = item.putArray("created");
            change.created().forEach(created::add);
            ArrayNode updated = item.putArray("updated");
            change.updated().forEach(updated::add);
            ArrayNode deleted = item.putArray("deleted");
            change.deleted().forEach(deleted::add);
        }
        return root.toString();
    }

    /** 解析收到的一条消息。自己发的返回 null（不重复分发）；版本不认识或内容损坏也返回 null 并记日志；从不抛出。 */
    public Incoming decode(String message) {
        try {
            JsonNode root = json.readTree(message);
            if (root == null || !root.isObject() || root.path("v").asInt(-1) != VERSION) {
                log.warn("丢弃无法识别的记录变更转发消息: channel={}", channel);
                return null;
            }
            if (node.equals(root.path("node").asText(null))) return null;
            String origin = root.path("origin").isTextual() ? root.path("origin").asText() : null;
            if (origin != null && !ORIGIN.matcher(origin).matches()) origin = null;
            List<RecordChangeBatch.ObjectChange> changes = new ArrayList<>();
            for (JsonNode item : root.path("objects")) {
                String objectId = item.path("objectId").asText("");
                if (!ID.matcher(objectId).matches()) continue;
                List<String> created = ids(item.path("created"));
                List<String> updated = ids(item.path("updated"));
                List<String> deleted = ids(item.path("deleted"));
                boolean many =
                        item.path("many").asBoolean(false)
                                || created == null
                                || updated == null
                                || deleted == null
                                || created.size() + updated.size() + deleted.size()
                                        > RecordChangeCollector.ID_CAP;
                changes.add(
                        many
                                ? new RecordChangeBatch.ObjectChange(
                                        objectId, true, List.of(), List.of(), List.of())
                                : new RecordChangeBatch.ObjectChange(
                                        objectId, false, created, updated, deleted));
            }
            if (changes.isEmpty()) return null;
            return new Incoming(new RecordChangeBatch(changes), origin);
        } catch (Throwable error) {
            log.warn("丢弃损坏的记录变更转发消息: channel={}, error={}", channel, error.toString());
            return null;
        }
    }

    /** 一个标识列表；形状不对或超过上限时返回 null，调用方按「不逐条列出」处理。 */
    private static List<String> ids(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > RecordChangeCollector.ID_CAP) return null;
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode id : node) {
            if (!id.isTextual() || !ID.matcher(id.asText()).matches()) return null;
            result.add(id.asText());
        }
        return result;
    }
}
