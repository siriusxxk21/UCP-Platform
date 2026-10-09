package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.application.dal.dataobject.NocodeLinkageTriggerDO;
import com.richuang.os.nocode.application.dal.mapper.LinkageTriggerMapper;
import com.richuang.os.nocode.metadata.service.object.LinkageTriggerPlan;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据联动自动更新的反向索引（nocode_linkage_trigger）：应用发布时按「应用 × 应用版本」登记，运行期按触发保存的那个应用的发布版本读取。
 *
 * <p>行的内容由 {@link LinkageTriggerPlan#derive} 从固定对象定义集合推导；登记后不修改，所以索引与应用发布快照不会漂移。 发布事务持目录排它锁、
 * 记录事务持共享锁，一个记录事务内看到的索引不会中途变化。
 */
@Component
public class ApplicationLinkageTriggers {
    /** 索引读取的事务级缓存键：同一事务内同一个问题只问一次库。 */
    private static final String CACHE = ApplicationLinkageTriggers.class.getName() + ".cache";

    @Resource private LinkageTriggerMapper store;
    @Resource private DataObjectApi objects;
    @Resource private ObjectMapper json;

    /** 索引行（ID 均为字符串，与对象定义里的稳定 ID 同口径）。 */
    public record Trigger(
            String applicationId,
            int applicationVersion,
            String sourceObjectId,
            String targetObjectId,
            int targetObjectVersion,
            String targetFieldId,
            String anchor,
            String anchorFieldId,
            String signature) {
        public boolean currentRecordAnchor() {
            return LinkageTriggerPlan.ANCHOR_CURRENT_RECORD.equals(anchor);
        }
    }

    /** 其它启用中、已发布且固定了某个对象的应用。 */
    public record Pinning(String applicationId, String name, int version, List<String> objects) {}

    /** 应用定义固定的对象版本推导出的行（不读写索引表）：发布登记、草稿预告与重建共用。 */
    public List<LinkageTriggerPlan.Row> derive(ApplicationCenter.Definition definition) {
        Map<String, DataCenter.Definition> pinned = new LinkedHashMap<>();
        Map<String, Integer> versions = new HashMap<>();
        for (var ref : definition.objects()) {
            pinned.put(
                    ref.objectId(),
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition());
            versions.put(ref.objectId(), ref.versionNo());
        }
        return LinkageTriggerPlan.derive(pinned, versions);
    }

    /** 应用发布事务内登记新版本的行。 */
    public void register(
            String application, int version, ApplicationCenter.Definition definition, long actor) {
        insert(application, version, derive(definition), actor);
    }

    /**
     * 按已有发布快照重建一个应用版本的行（幂等）：表内容已与推导结果一致时不动；否则清掉该版本的行重新登记。
     *
     * @return 重建后该版本的行
     */
    public List<Trigger> rebuild(
            String application, int version, ApplicationCenter.Definition definition, long actor) {
        var expected = derive(definition);
        var current = ofApplication(application, version);
        if (!matches(expected, current)) {
            store.purge(Long.parseLong(application), version);
            insert(application, version, expected, actor);
            forget();
            current = ofApplication(application, version);
        }
        return current;
    }

    /** 某应用某个发布版本登记的全部行（同一事务内缓存）。 */
    @SuppressWarnings("unchecked")
    public List<Trigger> ofApplication(String application, int version) {
        return (List<Trigger>)
                cached(
                        "app|" + application + "|" + version,
                        () ->
                                store.ofApplication(Long.parseLong(application), version).stream()
                                        .map(ApplicationLinkageTriggers::trigger)
                                        .toList());
    }

    /** 某应用某个发布版本里，以给定对象为来源的自动更新字段。 */
    public List<Trigger> bySource(String application, int version, String sourceObject) {
        return store
                .bySource(Long.parseLong(application), version, Long.parseLong(sourceObject))
                .stream()
                .map(ApplicationLinkageTriggers::trigger)
                .toList();
    }

    /** 对象是否是任何一条索引行（任何应用、任何版本）的来源或目标；object 为 null 时问「是否存在任何索引行」。 一次带索引的 EXISTS，同一事务内缓存。 */
    public boolean participates(String object) {
        return (Boolean)
                cached(
                        "participates|" + Objects.toString(object, ""),
                        () -> store.participates(object == null ? null : Long.parseLong(object)));
    }

    /** 事务级缓存：没有事务时直接读库。索引行只在应用发布时变化（发布持目录排它锁，记录事务持共享锁）。 */
    private Object cached(String key, java.util.function.Supplier<Object> loader) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return loader.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> cache =
                (Map<String, Object>) TransactionSynchronizationManager.getResource(CACHE);
        if (cache == null) {
            Map<String, Object> created = new HashMap<>();
            cache = created;
            TransactionSynchronizationManager.bindResource(CACHE, created);
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void suspend() {
                            // 独立新事务不继承外层事务的缓存。
                            if (TransactionSynchronizationManager.getResource(CACHE) == created)
                                TransactionSynchronizationManager.unbindResource(CACHE);
                        }

                        @Override
                        public void resume() {
                            if (TransactionSynchronizationManager.getResource(CACHE) == null)
                                TransactionSynchronizationManager.bindResource(CACHE, created);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (TransactionSynchronizationManager.getResource(CACHE) == created)
                                TransactionSynchronizationManager.unbindResource(CACHE);
                        }
                    });
        }
        Object value = cache.get(key);
        if (value == null) {
            value = loader.get();
            cache.put(key, value);
        }
        return value;
    }

    /** 其它启用中、已发布且固定了给定对象的应用（低频：只给总览接口用）。 */
    public List<Pinning> activePinning(String object, String exceptApplication) {
        List<Pinning> result = new ArrayList<>();
        for (String raw :
                store.activePinning(Long.parseLong(object), Long.parseLong(exceptApplication))) {
            try {
                JsonNode node = json.readTree(raw);
                List<String> ids = new ArrayList<>();
                node.path("objects").forEach(id -> ids.add(id.asText()));
                result.add(
                        new Pinning(
                                node.path("applicationId").asText(),
                                node.path("name").asText(),
                                node.path("version").asInt(),
                                ids));
            } catch (java.io.IOException e) {
                throw invalid("应用发布快照无法读取");
            }
        }
        return result;
    }

    private void insert(
            String application, int version, List<LinkageTriggerPlan.Row> rows, long actor) {
        for (var row : rows) {
            var entity = new NocodeLinkageTriggerDO();
            entity.setApplicationId(Long.valueOf(application));
            entity.setApplicationVersion(version);
            entity.setSourceObjectId(Long.valueOf(row.sourceObjectId()));
            entity.setTargetObjectId(Long.valueOf(row.targetObjectId()));
            entity.setTargetObjectVersion(row.targetObjectVersion());
            entity.setTargetFieldId(Long.valueOf(row.targetFieldId()));
            entity.setAnchor(row.anchor());
            entity.setAnchorFieldId(Long.valueOf(row.anchorFieldId()));
            entity.setSignature(row.signature());
            store.create(entity, Long.toString(actor));
        }
        if (!rows.isEmpty()) forget();
    }

    /** 本事务内登记了新行：参与判断的缓存作废。 */
    private void forget() {
        if (TransactionSynchronizationManager.getResource(CACHE) instanceof Map<?, ?> cache)
            cache.clear();
    }

    private static boolean matches(List<LinkageTriggerPlan.Row> expected, List<Trigger> current) {
        if (expected.size() != current.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var e = expected.get(i);
            var c = current.get(i);
            if (!e.sourceObjectId().equals(c.sourceObjectId())
                    || !e.targetObjectId().equals(c.targetObjectId())
                    || e.targetObjectVersion() != c.targetObjectVersion()
                    || !e.targetFieldId().equals(c.targetFieldId())
                    || !e.anchor().equals(c.anchor())
                    || !e.anchorFieldId().equals(c.anchorFieldId())
                    || !e.signature().equals(c.signature())) return false;
        }
        return true;
    }

    private static Trigger trigger(NocodeLinkageTriggerDO row) {
        return new Trigger(
                row.getApplicationId().toString(),
                row.getApplicationVersion(),
                row.getSourceObjectId().toString(),
                row.getTargetObjectId().toString(),
                row.getTargetObjectVersion(),
                row.getTargetFieldId().toString(),
                row.getAnchor(),
                row.getAnchorFieldId().toString(),
                row.getSignature());
    }
}
