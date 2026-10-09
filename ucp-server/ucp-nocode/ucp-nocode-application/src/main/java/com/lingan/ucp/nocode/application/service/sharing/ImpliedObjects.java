package com.lingan.ucp.nocode.application.service.sharing;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationReadableObjects;
import com.lingan.ucp.nocode.api.CalculationOptions;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.api.NocodeErrorCodes;
import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeObjectApplicationGrantDO;
import com.lingan.ucp.nocode.application.dal.mapper.ObjectApplicationGrantMapper;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.ImpliedViaEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.ObjectStatusEnum;
import com.lingan.ucp.nocode.enums.SelectionSourceEnum;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 关联对象的隐式只读：应用引用了对象 A，A 关联到的对象 B 不进「已引用对象」，系统自动放行应用对 B 的只读。
 *
 * <p>隐式集合是现算的，不落库：
 *
 * <ul>
 *   <li>第一跳，从每个显式引用对象的固定版本出发：关系的目标对象（含内部明细上的关系、多对多关系）、数据联动的来源对象、引用筛选的目标对象、挑取值的来源对象、计算字段的来源对象。
 *   <li>后续跳只沿「取数链」传、不沿关系传：隐式对象 B 的计算来源对象和联动 / 挑取值来源对象也算隐式（读 B 的计算字段、联动字段时系统要去那里取数）； B 的关系目标不算（A
 *       显示的是 B 的名称，用不到它）。深度上限与计算依赖上限一致。
 *   <li>隐式对象没有固定版本，一律用最新发布版；取不到最新发布版（未发布、已停用）的不进集合。
 * </ul>
 *
 * <p>隐式对象不能被当成独立对象使用：不能为它建列表、表单、相关列表、任务入口，不能写它。那条边界不在这里，而在各处「资源归属只认显式引用」的检查上。
 */
@Component
public class ImpliedObjects {
    /** 与计算依赖的层数上限一致。 */
    static final int MAX_DEPTH = 16;

    private static final int CACHE_CAPACITY = 256;

    @Resource private DataObjectApi objects;
    @Resource private ApplicationService applications;
    @Resource private ObjectApplicationGrantMapper grants;
    @Resource private DraftValidator validator;
    @Resource private ObjectDraftMapper objectHeads;

    /** 第一跳只取决于不可变的应用发布快照与固定对象版本：按「应用 + 发布版本号 + 校验和」缓存。后续跳依赖对象最新版，每次现算。 */
    private final Map<String, FirstHop> firstHops =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, FirstHop>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, FirstHop> eldest) {
                            return size() > CACHE_CAPACITY;
                        }
                    });

    /** closed = 数据管理员撤销了这个应用对它的授权（有授权行且为撤销）；这时它仍在集合里，但读不了。 */
    public record Implied(
            String objectId, DataCenter.Definition latest, List<Via> via, boolean closed) {}

    /** kind 取 {@link ImpliedViaEnum} 的编码；name 是关系名，或配了规则 / 计算的那个字段的名称。 */
    public record Via(String fromObjectId, String kind, String name) {}

    /**
     * 设计预览用的定义表：显式引用（草稿固定版本）之外补上隐式可读对象（最新发布版），并记住哪些是隐式的。
     *
     * <p>解析「关系目标、规则来源、计算来源」时用 {@link #get}；把对象当它自己用的地方（预览的主对象、资源归属）必须用 {@link #explicit}，
     * 隐式对象在那里等同于不存在。
     */
    public static final class Definitions extends LinkedHashMap<String, DataCenter.Definition> {
        private final Set<String> implied = new HashSet<>();

        public boolean implied(String objectId) {
            return implied.contains(objectId);
        }

        public DataCenter.Definition explicit(String objectId) {
            return implied.contains(objectId) ? null : get(objectId);
        }
    }

    /** 调用方已放入全部显式引用之后调用一次：把它们带出来的隐式可读对象补进去。 */
    public void complete(Definitions definitions) {
        Map<String, DataCenter.Definition> explicit = new LinkedHashMap<>(definitions);
        for (Implied found : of(explicit).values()) {
            definitions.put(found.objectId(), found.latest());
            definitions.implied.add(found.objectId());
        }
    }

    /** 预览定义表里 objectId 是不是隐式对象；不是预览定义表（运行期）时返回 null，由调用方按已发布版本判断。 */
    public static java.util.function.BooleanSupplier impliedIn(
            Map<String, DataCenter.Definition> preview, String objectId) {
        if (preview instanceof Definitions definitions) return () -> definitions.implied(objectId);
        return preview == null ? null : () -> false;
    }

    private record FirstHop(Set<String> explicit, Map<String, List<Via>> targets) {}

    /** 纯计算：explicit 是显式引用对象各自固定版本的定义；latest 给出对象的最新发布版，取不到返回 null。结果不含显式对象，保持发现顺序。 */
    public static Map<String, Implied> compute(
            Map<String, DataCenter.Definition> explicit,
            Function<String, DataCenter.Definition> latest) {
        Map<String, List<Via>> first = new LinkedHashMap<>();
        for (DataCenter.Definition d : explicit.values())
            collect(d, true, explicit.keySet(), first);
        return expand(explicit.keySet(), first, latest);
    }

    /** 从第一跳出发沿取数链展开。 */
    private static Map<String, Implied> expand(
            Set<String> explicit,
            Map<String, List<Via>> first,
            Function<String, DataCenter.Definition> latest) {
        Map<String, List<Via>> reasons = new LinkedHashMap<>();
        first.forEach((id, via) -> reasons.put(id, new ArrayList<>(via)));
        Map<String, Implied> result = new LinkedHashMap<>();
        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(first.keySet());
        first.keySet().forEach(id -> depth.put(id, 1));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) continue;
            DataCenter.Definition definition = latest.apply(id);
            if (definition == null) continue;
            result.put(id, new Implied(id, definition, reasons.get(id), false));
            if (depth.get(id) >= MAX_DEPTH) continue;
            Map<String, List<Via>> next = new LinkedHashMap<>();
            collect(definition, false, explicit, next);
            for (Map.Entry<String, List<Via>> entry : next.entrySet()) {
                reasons.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .addAll(entry.getValue());
                if (!visited.contains(entry.getKey()) && !depth.containsKey(entry.getKey())) {
                    depth.put(entry.getKey(), depth.get(id) + 1);
                    queue.add(entry.getKey());
                }
            }
        }
        Map<String, Implied> frozen = new LinkedHashMap<>();
        result.forEach(
                (id, implied) ->
                        frozen.put(
                                id,
                                new Implied(
                                        id,
                                        implied.latest(),
                                        List.copyOf(reasons.get(id)),
                                        false)));
        return Collections.unmodifiableMap(frozen);
    }

    /** d 指向的其它对象。relations 为真时把关系目标也算上（只有显式对象的第一跳算；隐式对象的关系目标不传递）。 取数来源（数据联动、挑取值、计算）两种情况都算。 */
    private static void collect(
            DataCenter.Definition d,
            boolean relations,
            Set<String> explicit,
            Map<String, List<Via>> into) {
        if (relations)
            for (DataCenter.Relation relation : d.relations())
                add(
                        into,
                        d,
                        explicit,
                        relation.targetObjectId(),
                        ImpliedViaEnum.RELATION,
                        relation.name());
        fields(d, d.fields(), d.fieldOptions(), relations, explicit, into);
        for (DataCenter.Detail detail : d.details())
            if (!MemberStateEnum.INACTIVE.matches(detail.state()))
                fields(d, detail.fields(), detail.fieldOptions(), relations, explicit, into);
        for (FieldDefinition field : d.fields()) {
            DataCenter.FieldOptions options = options(d.fieldOptions(), field);
            CalculationOptions calculation = options.calculation();
            if (calculation == null || MemberStateEnum.INACTIVE.matches(options.state())) continue;
            try {
                add(
                        into,
                        d,
                        explicit,
                        Calculations.target(d, calculation),
                        ImpliedViaEnum.CALCULATION,
                        field.name());
            } catch (ServiceException unresolved) {
                // 计算指向的关系已不存在：由对象与应用的校验点名报错，这里不把它算作可读对象。
            }
        }
        // 对方登记跨对象规则依赖用的同一个函数；上面逐项识别漏掉的也补进来，口径不比依赖登记窄。
        for (String id : FieldRuleValidator.dependencies(d).keySet())
            if (!into.containsKey(id) && (relations || !relationTarget(d, id)))
                add(into, d, explicit, id, ImpliedViaEnum.LINKAGE, d.objectName());
    }

    private static boolean relationTarget(DataCenter.Definition d, String objectId) {
        return d.relations().stream().anyMatch(r -> objectId.equals(r.targetObjectId()));
    }

    private static void fields(
            DataCenter.Definition d,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> fieldOptions,
            boolean relations,
            Set<String> explicit,
            Map<String, List<Via>> into) {
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions options = options(fieldOptions, field);
            if (MemberStateEnum.INACTIVE.matches(options.state())) continue;
            FieldRules rules = options.rules();
            if (rules != null && rules.linkage() != null)
                add(
                        into,
                        d,
                        explicit,
                        rules.linkage().sourceObjectId(),
                        ImpliedViaEnum.LINKAGE,
                        field.name());
            if (relations
                    && rules != null
                    && rules.reference() != null
                    && rules.reference().filter() != null
                    && !rules.reference().filter().isEmpty())
                for (DataCenter.Relation relation : d.relations())
                    if (field.id().equals(relation.fieldId()))
                        add(
                                into,
                                d,
                                explicit,
                                relation.targetObjectId(),
                                ImpliedViaEnum.REFERENCE_FILTER,
                                field.name());
            SelectionFields.Source selection = options.selection();
            if (selection != null
                    && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(selection.kind()))
                add(
                        into,
                        d,
                        explicit,
                        selection.sourceObjectId(),
                        ImpliedViaEnum.PICK,
                        field.name());
        }
    }

    private static DataCenter.FieldOptions options(
            Map<String, DataCenter.FieldOptions> fieldOptions, FieldDefinition field) {
        DataCenter.FieldOptions options =
                fieldOptions == null ? null : fieldOptions.get(field.id());
        return options == null ? DataCenter.FieldOptions.defaults() : options;
    }

    private static void add(
            Map<String, List<Via>> into,
            DataCenter.Definition from,
            Set<String> explicit,
            String target,
            ImpliedViaEnum kind,
            String name) {
        if (target == null
                || target.isBlank()
                || target.equals(from.objectId())
                || explicit.contains(target)) return;
        into.computeIfAbsent(target, key -> new ArrayList<>())
                .add(new Via(from.objectId(), kind.getCode(), name));
    }

    /**
     * 最新发布版；未发布、已停用或不存在时为 null。
     *
     * <p>先看对象头再取定义，不靠接住异常来判断：取定义的服务自带一层参与事务，在里面抛错会把外层事务标成只能回滚， 接住异常也救不回来（外层提交时才报「事务已被标记为只能回滚」）。
     */
    public DataCenter.Definition latest(String objectId) {
        if (objectId == null || !objectId.matches("[1-9][0-9]{0,18}")) return null;
        NocodeObjectDO head = objectHeads.selectById(Long.parseLong(objectId));
        if (head == null
                || !ObjectStatusEnum.ACTIVE.matches(head.getStatus())
                || head.getCurrentPublishedVersionNo() == null) return null;
        return objects.getVersion(objectId, null).definition();
    }

    /** 一组显式引用（各自固定版本的定义）带出来的隐式可读对象；不看授权，closed 恒为 false。 */
    public Map<String, Implied> of(Map<String, DataCenter.Definition> explicit) {
        return compute(explicit, this::latest);
    }

    /** 同上，并标出数据管理员已经对这个应用关闭（撤销授权）的那些。 */
    public Map<String, Implied> of(
            String applicationId, Map<String, DataCenter.Definition> explicit) {
        Map<String, Implied> result = new LinkedHashMap<>();
        for (Implied implied : of(explicit).values())
            result.put(
                    implied.objectId(),
                    new Implied(
                            implied.objectId(),
                            implied.latest(),
                            implied.via(),
                            closed(applicationId, implied.objectId())));
        return Collections.unmodifiableMap(result);
    }

    private boolean closed(String applicationId, String objectId) {
        if (applicationId == null) return false;
        NocodeObjectApplicationGrantDO row =
                grants.find(validator.id(objectId, "对象"), validator.id(applicationId, "应用"));
        return row != null && "null".equals(String.valueOf(row.getGrantJson()).trim());
    }

    /** 工作台用：当前草稿里的引用带出来的隐式可读对象及原因。引用按版本号与校验和核对，不要求固定版本仍与最新结构兼容。 */
    public List<ApplicationReadableObjects.Item> readable(ApplicationReadableObjects.Query query) {
        if (query == null) throw NocodeErrorCodes.invalid("缺少应用引用");
        ApplicationCenter.Definition normalized =
                applications.normalize(
                        new ApplicationCenter.Definition(
                                query.objects() == null ? List.of() : query.objects(), List.of()),
                        false);
        Map<String, DataCenter.Definition> explicit = new LinkedHashMap<>();
        for (ApplicationCenter.ObjectReference reference : normalized.objects())
            explicit.put(
                    reference.objectId(),
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition());
        List<ApplicationReadableObjects.Item> result = new ArrayList<>();
        for (Implied found : of(query.applicationId(), explicit).values()) {
            List<ApplicationReadableObjects.Via> via = new ArrayList<>();
            for (Via step : found.via()) {
                DataCenter.Definition from = explicit.get(step.fromObjectId());
                if (from == null) from = latest(step.fromObjectId());
                via.add(
                        new ApplicationReadableObjects.Via(
                                step.fromObjectId(),
                                from == null ? null : from.objectName(),
                                step.kind(),
                                step.name()));
            }
            result.add(
                    new ApplicationReadableObjects.Item(
                            objects.getVersion(found.objectId(), null),
                            List.copyOf(via),
                            found.closed()));
        }
        return List.copyOf(result);
    }

    /**
     * 运行期判断：按应用的已发布版本（任务办理绑定旧版本时按那个版本），objectId 是不是它隐式可读的对象。显式引用的对象返回 false。
     *
     * <p>只回答「在不在集合里」，不看授权；数据管理员是否关闭由 {@link ObjectSharingService#ceiling} 决定。
     */
    public boolean implied(String applicationId, String objectId) {
        if (applicationId == null || objectId == null) return false;
        // 只读作用域内同一事务对同一应用、对象只判断一次（应用的已发布版本在其中同样只读一次）。
        return ReadRequestMemo.once(
                ReadRequestMemo.key("sharing.implied", applicationId, objectId),
                () -> impliedInRelease(applicationId, objectId));
    }

    private boolean impliedInRelease(String applicationId, String objectId) {
        ApplicationCenter.Published release = applications.published(applicationId);
        FirstHop first = firstHop(applicationId, release);
        if (first.explicit().contains(objectId)) return false;
        if (first.targets().containsKey(objectId)) return latest(objectId) != null;
        if (first.targets().isEmpty()) return false;
        return expand(first.explicit(), first.targets(), this::latest).containsKey(objectId);
    }

    private FirstHop firstHop(String applicationId, ApplicationCenter.Published release) {
        String key = applicationId + ":" + release.versionNo() + ":" + release.checksum();
        FirstHop cached = firstHops.get(key);
        if (cached != null) return cached;
        Map<String, DataCenter.Definition> explicit = new LinkedHashMap<>();
        for (ApplicationCenter.ObjectReference reference : release.definition().objects())
            explicit.put(
                    reference.objectId(),
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition());
        Map<String, List<Via>> targets = new LinkedHashMap<>();
        for (DataCenter.Definition d : explicit.values())
            collect(d, true, explicit.keySet(), targets);
        Map<String, List<Via>> frozen = new LinkedHashMap<>();
        targets.forEach((id, via) -> frozen.put(id, List.copyOf(via)));
        FirstHop computed =
                new FirstHop(Set.copyOf(explicit.keySet()), Collections.unmodifiableMap(frozen));
        firstHops.put(key, computed);
        return computed;
    }
}
