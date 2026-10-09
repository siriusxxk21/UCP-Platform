package com.lingan.ucp.nocode.application.service.application;

import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.Selections;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator.Universe;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把一份应用定义里对某个对象的引用提到新版本，并做随行转换。纯函数：不连库，输入不被修改。
 *
 * <p>只做两件事：
 *
 * <ol>
 *   <li>对象引用换成新版本号与新校验和；其它引用不动，顺序不动。
 *   <li>任务入口允许范围里属于该对象的授权，六个清单各自：是「全部」不动；是清单则先去掉新版本里已不存在的
 *       ID，再看清单是不是恰好等于旧版本的全部——是就转成「全部」（以后新增的自动包含），不是就保留清单。空清单永远不转。可写清单的全集是展开后的可读清单里写得了的那些。
 * </ol>
 *
 * <p>除此之外逐字节不动：其它资源、资源顺序、未涉及的键。
 */
public final class ApplicationUpgrader {
    private static final String LIMITS = "limits";
    private static final String OBJECT_ID = "objectId";

    private ApplicationUpgrader() {}

    public static ApplicationCenter.Definition apply(
            ApplicationCenter.Definition definition,
            String objectId,
            int toVersion,
            String toChecksum,
            DataCenter.Definition from,
            DataCenter.Definition to) {
        List<ApplicationCenter.ObjectReference> references = new ArrayList<>();
        for (ApplicationCenter.ObjectReference reference : definition.objects())
            references.add(
                    reference.objectId().equals(objectId)
                            ? new ApplicationCenter.ObjectReference(objectId, toVersion, toChecksum)
                            : reference);
        List<ApplicationCenter.Resource> resources = new ArrayList<>();
        for (ApplicationCenter.Resource resource : definition.resources())
            resources.add(
                    ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind())
                            ? entry(resource, objectId, from, to)
                            : resource);
        return new ApplicationCenter.Definition(List.copyOf(references), List.copyOf(resources));
    }

    private static ApplicationCenter.Resource entry(
            ApplicationCenter.Resource resource,
            String objectId,
            DataCenter.Definition from,
            DataCenter.Definition to) {
        Object limits = resource.config() == null ? null : resource.config().get(LIMITS);
        if (!(limits instanceof List<?> list)) return resource;
        boolean changed = false;
        List<Object> converted = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> grant && objectId.equals(grant.get(OBJECT_ID))) {
                Map<String, Object> next = grant(grant, from, to);
                changed |= !next.equals(grant);
                converted.add(next);
            } else converted.add(item);
        }
        if (!changed) return resource;
        Map<String, Object> config = new LinkedHashMap<>(resource.config());
        config.put(LIMITS, converted);
        return new ApplicationCenter.Resource(
                resource.id(), resource.kind(), resource.code(), resource.name(), config);
    }

    private static Map<String, Object> grant(
            Map<?, ?> source, DataCenter.Definition from, DataCenter.Definition to) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(Objects.toString(key), value));
        Universe before = Universe.of(from), after = Universe.of(to);
        // 可读：全集是对象版本里的字段、启用明细、多对多关系。
        List<String> readFieldsBefore = strings(source.get("readFields"));
        List<String> readFields = convert(readFieldsBefore, before.fields(), after.fields());
        List<String> readDetailsBefore = strings(source.get("readDetails"));
        List<String> readDetails = convert(readDetailsBefore, before.details(), after.details());
        List<String> readRelationsBefore = strings(source.get("readRelations"));
        List<String> readRelations =
                convert(readRelationsBefore, before.relations(), after.relations());
        put(result, source, "readFields", readFields);
        put(result, source, "readDetails", readDetails);
        put(result, source, "readRelations", readRelations);
        // 可写：全集是展开后的可读清单；字段还要写得了（公式、汇总、自动编号等不算）。
        put(
                result,
                source,
                "writeFields",
                convert(
                        strings(source.get("writeFields")),
                        writable(from, Selections.resolve(readFieldsBefore, before.fields())),
                        writable(to, Selections.resolve(readFields, after.fields()))));
        put(
                result,
                source,
                "writeDetails",
                convert(
                        strings(source.get("writeDetails")),
                        Selections.resolve(readDetailsBefore, before.details()),
                        Selections.resolve(readDetails, after.details())));
        put(
                result,
                source,
                "writeRelations",
                convert(
                        strings(source.get("writeRelations")),
                        Selections.resolve(readRelationsBefore, before.relations()),
                        Selections.resolve(readRelations, after.relations())));
        return result;
    }

    /** 原配置里没有这个键（旧快照未配置关系）就不凭空加上。 */
    private static void put(
            Map<String, Object> result, Map<?, ?> source, String key, List<String> value) {
        if (source.containsKey(key)) result.put(key, value);
    }

    private static List<String> writable(DataCenter.Definition d, List<String> fieldIds) {
        return fieldIds.stream().filter(id -> ObjectGrantValidator.writable(d, id)).toList();
    }

    /**
     * 一份清单从旧版本搬到新版本。是「全部」⇒ 原样；否则先去掉新版本全集之外的 ID，结果为空 ⇒ 空；旧版本全集里（且仍在新版本里）的每一项清单都有 ⇒
     * 清单恰好等于旧版本的全部，转「全部」；否则保留清单。
     */
    static List<String> convert(
            List<String> stored, Collection<String> universeBefore, List<String> universeAfter) {
        if (Selections.isAll(stored)) return List.of(Selections.ALL);
        Set<String> available = new LinkedHashSet<>(universeAfter);
        List<String> kept = stored.stream().filter(available::contains).toList();
        if (kept.isEmpty()) return kept;
        Set<String> carried = new LinkedHashSet<>(universeBefore);
        carried.retainAll(available);
        return !carried.isEmpty() && kept.containsAll(carried) ? List.of(Selections.ALL) : kept;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> items)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) if (item != null) result.add(item.toString());
        return result;
    }
}
