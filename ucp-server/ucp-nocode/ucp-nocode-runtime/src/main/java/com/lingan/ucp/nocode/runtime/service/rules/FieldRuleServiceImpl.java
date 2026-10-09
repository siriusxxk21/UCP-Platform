package com.lingan.ucp.nocode.runtime.service.rules;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;
import com.lingan.ucp.nocode.metadata.service.object.LinkageEmptyValues;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope;
import com.lingan.ucp.nocode.runtime.service.record.RecordCalculations;
import com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport;
import com.lingan.ucp.nocode.runtime.service.record.RecordSummaries;
import com.lingan.ucp.nocode.runtime.service.record.RecordValues;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/**
 * 字段规则运行期求值：数据联动、公式默认值与引用筛选范围。
 *
 * <p>规则只取应用固定的对象版本（设计预览取服务端核验过的草稿引用），请求里不接受任何配置；来源取数按当前操作者 READ 行范围与可查询字段执行。非 APPLIED 一律 value=null
 * 并附一句人话，不抛 500、不滤掉条目；只有参数与权限错误走 invalid。同一请求内「来源对象 + 已解析条件」只查一次（跨行共享）。
 *
 * <p>开启了「来源变化时自动更新」的数据联动一律按全部数据读来源（系统只读授权：不看操作者的对象权限、行范围与字段权限），
 * 所有求值入口同此口径——表单即时求值、目标保存时的强制、来源触发、预告、回填——否则表单里看到的值与存下的值会不同。 来源对象的版本仍取应用固定版本。没开自动更新的联动照旧按操作者权限。
 *
 * <p>只读口径（2026-09-29 业务方裁定，取代设计稿 D7/B29/B31）：结果的 readOnly 只由配置决定，不看求值状态——数据联动的 readOnly 为 null
 * 按只读、显式 false 可手改；公式默认值一律只读，新建与编辑都求值。只读结果非 APPLIED 时 value=null（字段为空且不能填），message 照旧说明原因。
 */
@Service
public class FieldRuleServiceImpl implements FieldRuleService {
    /** 每个明细组一次最多重算的行数，与保存上限一致（RecordDetailWriter）。 */
    static final int MAX_DETAIL_GROUPS = 20;

    @Resource private RecordSelectionSupport selections;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private TaskEntryRuntimeScope taskScope;
    @Resource private RecordValues recordValues;
    @Resource private RecordSummaries summaries;
    @Resource private RecordCalculations calculations;
    @Resource private ApplicationService applications;
    @Resource private DataObjectApi objects;

    @Resource
    private com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService
            orderedStates;

    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    @Resource
    private com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects impliedObjects;

    @Resource private com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess systemRead;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    /** 应用运行：规则取自应用固定版本。 */
    public FieldRules.Evaluation evaluate(FieldRules.EvaluateQuery query, long actor) {
        return run(query, actor, null);
    }

    /** 设计预览：对象版本取自服务端核验的草稿引用；权限仍按实时应用授权。 */
    public FieldRules.Evaluation preview(FieldRules.EvaluatePreview request, long actor) {
        if (request == null || request.query() == null) throw invalid("预览请求不能为空");
        var query = request.query();
        if (query.applicationId() == null || query.objectId() == null) throw invalid("求值请求缺少应用或对象");
        applications.requireDesigner(query.applicationId(), actor);
        var normalized =
                applications.normalize(
                        new ApplicationCenter.Definition(request.objects(), List.of()));
        com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions definitions =
                new com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions();
        normalized
                .objects()
                .forEach(
                        ref ->
                                definitions.put(
                                        ref.objectId(),
                                        objects.getVersion(ref.objectId(), ref.versionNo())
                                                .definition()));
        // 草稿没引用、但因关联而隐式可读的对象也进定义表；求值的主对象仍只认显式引用。
        impliedObjects.complete(definitions);
        var d = definitions.explicit(query.objectId());
        if (d == null) throw invalid("求值对象不属于当前草稿引用");
        if (request.form() != null && !d.objectId().equals(request.form().objectId()))
            throw invalid("表单不属于当前对象");
        return run(query, actor, definitions);
    }

    private FieldRules.Evaluation run(
            FieldRules.EvaluateQuery query,
            long actor,
            Map<String, DataCenter.Definition> preview) {
        if (query == null || query.applicationId() == null || query.objectId() == null)
            throw invalid("求值请求缺少应用或对象");
        var groups = query.details() == null ? List.<FieldRules.DetailRows>of() : query.details();
        if (groups.size() > MAX_DETAIL_GROUPS) throw invalid("一次最多重算 20 个明细组");
        for (var group : groups) {
            if (group == null || group.detailId() == null) throw invalid("明细组缺少明细 ID");
            if (group.rows() != null && group.rows().size() > FieldRules.ROW_BATCH_CAP)
                throw invalid("一次最多重算 500 行明细");
            if (group.rows() != null)
                for (var row : group.rows()) {
                    if (row == null || row.rowKey() == null) throw invalid("明细行缺少行标识");
                    if (row.detailRecordId() != null && query.recordId() == null)
                        throw invalid("已有明细必须指定所属主记录");
                }
        }
        return transaction.execute(
                status -> {
                    var d =
                            preview == null
                                    ? selections.ruleDefinition(
                                            query.applicationId(), query.objectId(), actor)
                                    : preview.get(query.objectId());
                    var access = policy.access(query.applicationId(), d, actor);
                    var caps = selections.ruleCapabilities(d, access, query.recordId(), actor);
                    var ctx =
                            new RuleContext(
                                    query.applicationId(), d, actor, preview, query.recordId());
                    var graph = FieldRuleGraph.of(d);
                    var session = new Session(graph);
                    Map<String, Object> work =
                            new LinkedHashMap<>(query.values() == null ? Map.of() : query.values());
                    Set<String> changed = set(query.changed());
                    Set<String> overridable = set(query.overridable());
                    var mainRules = ruleFields(d.fields(), d.fieldOptions());
                    Set<String> targets =
                            changed.isEmpty() ? mainRules : downstream(graph, mainRules, changed);
                    List<FieldRules.Result> results = new ArrayList<>();
                    var main =
                            evaluateMain(
                                    ctx,
                                    work,
                                    targets,
                                    overridable,
                                    session,
                                    caps.readFields()::contains);
                    results.addAll(main);
                    // 前端可能把明细字段 ID 放进主表 changed（避免被当成全量）：主表只认主表字段。
                    Set<String> masterChanged = new LinkedHashSet<>();
                    for (var field : d.fields())
                        if (changed.contains(field.id())) masterChanged.add(field.id());
                    for (var r : main) if (written(r, overridable)) masterChanged.add(r.fieldId());
                    for (var group : groups) {
                        var detail =
                                d.details().stream()
                                        .filter(
                                                t ->
                                                        t.id().equals(group.detailId())
                                                                && MemberStateEnum.ACTIVE.matches(
                                                                        t.state())
                                                                && caps.readDetails()
                                                                        .contains(t.id()))
                                        .findFirst()
                                        .orElse(null);
                        // 没有明细查看权时整组不返回，不回显任何求值结果。
                        if (detail == null || group.rows() == null) continue;
                        results.addAll(
                                evaluateRows(
                                        ctx, detail, work, masterChanged, group.rows(), session));
                    }
                    return new FieldRules.Evaluation(results);
                });
    }

    @Override
    public List<FieldRules.Result> evaluate(
            RuleContext ctx,
            Map<String, Object> values,
            Set<String> targets,
            Set<String> overridable,
            boolean creating) {
        var d = ctx.definition();
        var graph = FieldRuleGraph.of(d);
        return evaluateMain(
                ctx,
                new LinkedHashMap<>(values == null ? Map.of() : values),
                targets == null || targets.isEmpty()
                        ? ruleFields(d.fields(), d.fieldOptions())
                        : targets,
                overridable == null ? Set.of() : overridable,
                new Session(graph),
                id -> true);
    }

    @Override
    public List<FieldRules.Result> evaluateRows(
            RuleContext ctx,
            String detailId,
            Map<String, Object> masterValues,
            Set<String> masterChanged,
            List<FieldRules.RowInput> rows) {
        var detail =
                ctx.definition().details().stream()
                        .filter(t -> t.id().equals(detailId))
                        .findFirst()
                        .orElseThrow(() -> invalid("明细不存在"));
        return evaluateRows(
                ctx,
                detail,
                masterValues == null ? Map.of() : masterValues,
                masterChanged == null ? Set.of() : masterChanged,
                rows == null ? List.of() : rows,
                new Session(FieldRuleGraph.of(ctx.definition())));
    }

    @Override
    public ReferenceScope referenceScope(
            RuleContext ctx,
            String detailId,
            DataCenter.Relation relation,
            Map<String, Object> values) {
        return referenceScope(
                ctx, detailId, relation, values, new Session(FieldRuleGraph.of(ctx.definition())));
    }

    @Override
    public Set<String> idsWithinScope(
            RuleContext ctx,
            DataCenter.Relation relation,
            ReferenceScope scope,
            Collection<String> ids) {
        return idsWithinScope(
                ctx, relation, scope, ids, new Session(FieldRuleGraph.of(ctx.definition())));
    }

    /**
     * 引用筛选条件可用的目标字段（系统取数口径：应用对目标对象的有效上限里可查看、且不是实时计算的字段）；目标取不了数时为空集。
     * 引用候选把它交给条件编译：筛选是配置的一部分，不该受当前操作者在目标对象上能看哪些字段的限制。
     */
    public Set<String> referenceFilterFields(RuleContext ctx, DataCenter.Relation relation) {
        Source src =
                source(
                        ctx,
                        relation.targetObjectId(),
                        new Session(FieldRuleGraph.of(ctx.definition())),
                        false);
        return src.failure() != null ? Set.of() : src.allowed();
    }

    /** 关系字段上的规则配置位：主表字段取主表扩展属性，明细字段取所属明细的扩展属性。 */
    public static DataCenter.FieldOptions fieldOptions(
            DataCenter.Definition d, DataCenter.Relation relation) {
        if (relation == null || relation.fieldId() == null) return null;
        if (relation.sourceDetailId() == null) return d.fieldOptions().get(relation.fieldId());
        return d.details().stream()
                .filter(t -> t.id().equals(relation.sourceDetailId()))
                .findFirst()
                .map(t -> t.fieldOptions().get(relation.fieldId()))
                .orElse(null);
    }

    /** 候选接口复用：可查询字段（已剔除 LIVE 计算字段）与目标定义，与规则求值同一口径。 */
    public Set<String> queryableFields(
            DataCenter.Definition target, ApplicationRuntimePolicy.Access access) {
        var allowed = new HashSet<>(access.queryFields());
        allowed.removeIf(id -> Calculations.live(target.fieldOptions().get(id)));
        orderedStates.requireReady(target, allowed);
        return allowed;
    }

    // ── 主表与明细 ──

    private List<FieldRules.Result> evaluateMain(
            RuleContext ctx,
            Map<String, Object> work,
            Set<String> targets,
            Set<String> overridable,
            Session session,
            Predicate<String> visible) {
        var d = ctx.definition();
        var names = names(d.fields(), "");
        var codes = codes(d.fields(), Map.of());
        List<FieldRules.Result> results = new ArrayList<>();
        for (String id : session.graph.topo()) {
            if (!targets.contains(id) || !visible.test(id)) continue;
            var field = find(d.fields(), id);
            if (field == null) continue;
            var options = d.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults());
            var out = evaluateField(ctx, null, field, options, work, names, codes, session, null);
            for (var r : out) {
                if (FieldRuleKindEnum.REFERENCE.matches(r.kind()))
                    r = withInScope(ctx, d, null, field.id(), r, work, session);
                else if (written(r, overridable)) work.put(id, r.value());
                else if (cleared(r)) work.put(id, null);
                results.add(r);
            }
        }
        return results;
    }

    private List<FieldRules.Result> evaluateRows(
            RuleContext ctx,
            DataCenter.Detail detail,
            Map<String, Object> master,
            Set<String> masterChanged,
            List<FieldRules.RowInput> rows,
            Session session) {
        var d = ctx.definition();
        var names = names(d.fields(), "主表 · ");
        names.putAll(names(detail.fields(), ""));
        var codes = codes(d.fields(), Map.of());
        codes = codes(detail.fields(), codes);
        var ruleIds = ruleFields(detail.fields(), detail.fieldOptions());
        Set<String> fromMaster = new LinkedHashSet<>();
        for (String m : masterChanged)
            fromMaster.addAll(
                    session.graph.detailTargetsOfMaster(m).getOrDefault(detail.id(), Set.of()));
        List<FieldRules.Result> results = new ArrayList<>();
        // 引用复核延后到所有行算完，按「关系 + 已解析范围」分组，每组一次 key IN (…) 查询。
        Map<String, List<Integer>> pendingScopes = new LinkedHashMap<>();
        Map<String, ReferenceScope> scopes = new HashMap<>();
        Map<String, DataCenter.Relation> scopeRelations = new HashMap<>();
        Map<Integer, List<String>> selected = new HashMap<>();
        for (var row : rows) {
            Set<String> changed = set(row.changed());
            Set<String> overridable = set(row.overridable());
            boolean creating = Boolean.TRUE.equals(row.creating()) && row.detailRecordId() == null;
            Set<String> targets = new LinkedHashSet<>();
            if (creating && changed.isEmpty()) targets.addAll(ruleIds);
            else {
                targets.addAll(downstream(session.graph, ruleIds, changed));
                targets.addAll(fromMaster);
            }
            Map<String, Object> rowWork =
                    new LinkedHashMap<>(row.values() == null ? Map.of() : row.values());
            for (String id : session.graph.topo()) {
                if (!targets.contains(id)) continue;
                var field = find(detail.fields(), id);
                if (field == null) continue;
                var options =
                        detail.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults());
                Map<String, Object> merged = new LinkedHashMap<>(master);
                merged.putAll(rowWork);
                for (var r :
                        evaluateField(
                                ctx,
                                detail.id(),
                                field,
                                options,
                                merged,
                                names,
                                codes,
                                session,
                                row.rowKey())) {
                    if (FieldRuleKindEnum.REFERENCE.matches(r.kind())) {
                        var ids = FieldRuleConditions.candidates(rowWork.get(field.id()));
                        if (FieldRuleStateEnum.APPLIED.matches(r.state()) && !ids.isEmpty()) {
                            var relation = relation(d, detail.id(), field.id());
                            var scope = referenceScope(ctx, detail.id(), relation, merged, session);
                            String key = relation.id() + "|" + session.key(scope);
                            pendingScopes
                                    .computeIfAbsent(key, k -> new ArrayList<>())
                                    .add(results.size());
                            scopes.put(key, scope);
                            scopeRelations.put(key, relation);
                            selected.put(
                                    results.size(), ids.stream().map(Object::toString).toList());
                        }
                    } else if (written(r, overridable)) rowWork.put(id, r.value());
                    else if (cleared(r)) rowWork.put(id, null);
                    results.add(r);
                }
            }
        }
        for (var entry : pendingScopes.entrySet()) {
            var ids = new LinkedHashSet<String>();
            entry.getValue().forEach(i -> ids.addAll(selected.get(i)));
            var within =
                    idsWithinScope(
                            ctx,
                            scopeRelations.get(entry.getKey()),
                            scopes.get(entry.getKey()),
                            ids,
                            session);
            for (int index : entry.getValue()) {
                var r = results.get(index);
                results.set(index, inScope(r, within.containsAll(selected.get(index))));
            }
        }
        return results;
    }

    /** 一个字段上的规则：联动、公式默认值、引用筛选各出一条结果（通常只有一种）。只读只由配置决定（见类说明）。 */
    private List<FieldRules.Result> evaluateField(
            RuleContext ctx,
            String detailId,
            FieldDefinition field,
            DataCenter.FieldOptions options,
            Map<String, Object> values,
            Map<String, String> names,
            Map<String, String> codes,
            Session session,
            String rowKey) {
        var rules = options.rules();
        if (rules == null) return List.of();
        List<FieldRules.Result> out = new ArrayList<>();
        if (rules.linkage() != null)
            out.add(
                    linkage(ctx, field, options, rules, values, names, session)
                            .locked(rules.readOnlyLinkage())
                            .at(field.id(), FieldRuleKindEnum.LINKAGE, detailId, rowKey));
        if (rules.defaultFormula() != null)
            out.add(
                    formula(field, options, rules, values, names, codes)
                            .locked(true)
                            .at(field.id(), FieldRuleKindEnum.DEFAULT_FORMULA, detailId, rowKey));
        if (rules.reference() != null
                && rules.reference().filter() != null
                && !rules.reference().filter().isEmpty()) {
            var relation = relation(ctx.definition(), detailId, field.id());
            if (relation != null) {
                var scope = referenceScope(ctx, detailId, relation, values, session);
                out.add(
                        new Outcome(
                                        scope.state(),
                                        null,
                                        0,
                                        false,
                                        scope.message(),
                                        scope.pendingFields())
                                .at(field.id(), FieldRuleKindEnum.REFERENCE, detailId, rowKey));
            }
        }
        return out;
    }

    // ── 数据联动（逐步对照老 resolveOne） ──

    private Outcome linkage(
            RuleContext ctx,
            FieldDefinition target,
            DataCenter.FieldOptions targetOptions,
            FieldRules rules,
            Map<String, Object> values,
            Map<String, String> names,
            Session session) {
        var l = rules.linkage();
        if (l.sourceObjectId() == null || l.valueFieldId() == null)
            return Outcome.fail(
                    FieldRuleStateEnum.INCOMPLETE_CONFIG, "这条数据联动还没配完（来源对象 / 取哪个字段），不填值");
        // 来源一律按全部数据读（系统取数，见 resolve）：开没开自动更新都一样。标记仍传下去——它区分行缓存，
        // 也决定对象数据维护入口里用哪一种授权。
        var src = source(ctx, l.sourceObjectId(), session, l.autoUpdateOn());
        if (src.failure() != null) return src.failure();
        var valueField = find(src.definition().fields(), l.valueFieldId());
        var valueOptions =
                valueField == null
                        ? null
                        : src.definition()
                                .fieldOptions()
                                .getOrDefault(valueField.id(), DataCenter.FieldOptions.defaults());
        if (valueField == null || MemberStateEnum.INACTIVE.matches(valueOptions.state()))
            return Outcome.fail(
                    FieldRuleStateEnum.SOURCE_FIELD_MISSING,
                    "来源对象「" + src.definition().objectName() + "」里没有这个字段或已停用，不填值");
        if (!src.access().queryFields().contains(valueField.id()))
            return Outcome.fail(
                    FieldRuleStateEnum.SOURCE_NOT_READABLE,
                    sourceFieldDenied(ctx, src, valueField) + "，不填值");
        boolean numeric = LinkageValues.numeric(valueField, valueOptions);
        // 先判档位与取整编码，再查库。
        var mode = LinkageValues.checkMode(l.multiRow(), numeric, rules.rounding());
        if (mode != null) return Outcome.of(mode, 0);
        var scope =
                FieldRuleConditions.compile(
                        l.conditions(),
                        src.definition(),
                        src.allowed(),
                        values,
                        names,
                        l.autoUpdateOn(),
                        ctx.recordId(),
                        objectNames(ctx, session));
        if (!FieldRuleStateEnum.APPLIED.matches(scope.state()))
            return FieldRuleStateEnum.PENDING_ROW_VALUE.matches(scope.state())
                    ? unmatched(
                            l,
                            target,
                            targetOptions,
                            new Outcome(
                                    scope.state(),
                                    null,
                                    0,
                                    false,
                                    scope.message(),
                                    scope.pendingFields()))
                    : new Outcome(
                            scope.state(), null, 0, false, scope.message(), scope.pendingFields());
        var rows = session.rows(ctx, src, scope);
        boolean truncated = rows.size() > FieldRules.MATCH_CAP;
        var effective = truncated ? rows.subList(0, FieldRules.MATCH_CAP) : rows;
        if (effective.isEmpty())
            return unmatched(
                    l,
                    target,
                    targetOptions,
                    Outcome.fail(FieldRuleStateEnum.NO_MATCH, "这条联动一行都没命中，不填值（不给空串、不给 0）"));
        String multiRow = LinkageValues.mode(l.multiRow());
        if (truncated
                && (LinkageMultiRowEnum.CONCAT.matches(multiRow)
                        || LinkageMultiRowEnum.SUM.matches(multiRow)))
            return new Outcome(
                    FieldRuleStateEnum.TOO_MANY_ROWS.getCode(),
                    null,
                    effective.size(),
                    false,
                    "命中行数超过 " + FieldRules.MATCH_CAP + " 行，结果一定是残的，不填值",
                    List.of());
        if (FieldTypeEnum.fromCode(valueField.type()).isComputed())
            effective = session.enriched(ctx, src, scope, effective, this);
        var cells = effective.stream().map(r -> r.values().get(valueField.id())).toList();
        var reduced = LinkageValues.reduce(multiRow, cells, numeric, target, rules.rounding());
        if (!reduced.applied()) return Outcome.of(reduced, effective.size());
        var mismatch = mismatch(target, targetOptions, reduced.value());
        if (mismatch != null)
            return new Outcome(
                    FieldRuleStateEnum.VALUE_TYPE_MISMATCH.getCode(),
                    null,
                    effective.size(),
                    false,
                    mismatch,
                    List.of());
        return new Outcome(
                FieldRuleStateEnum.APPLIED.getCode(),
                reduced.value(),
                effective.size(),
                false,
                null,
                List.of());
    }

    /**
     * 「没有匹配记录时填入」：只在未命中（NO_MATCH）或依赖待定（PENDING_ROW_VALUE）且配置了 emptyValue 时生效，结果改为 APPLIED、命中 0
     * 行。其它任何失败状态都不经过这里；命中了行但取到的那一格为空也不算没有匹配。全系统只有这一处。
     */
    private Outcome unmatched(
            FieldRules.Linkage l,
            FieldDefinition target,
            DataCenter.FieldOptions targetOptions,
            Outcome outcome) {
        if (!l.autoUpdateOn() || l.emptyValue() == null) return outcome;
        Object value;
        try {
            value = LinkageEmptyValues.parse(target, l.emptyValue());
        } catch (ServiceException | IllegalArgumentException e) {
            return Outcome.fail(
                    FieldRuleStateEnum.VALUE_TYPE_MISMATCH,
                    "「没有匹配记录时填入」的值「" + l.emptyValue() + "」不符合字段「" + target.name() + "」的类型，不填值");
        }
        var mismatch = mismatch(target, targetOptions, value);
        if (mismatch != null) return Outcome.fail(FieldRuleStateEnum.VALUE_TYPE_MISMATCH, mismatch);
        return new Outcome(FieldRuleStateEnum.APPLIED.getCode(), value, 0, false, null, List.of());
    }

    // ── 公式默认值：新建与编辑都求值（依赖变化即重算，2026-09-29） ──

    private Outcome formula(
            FieldDefinition target,
            DataCenter.FieldOptions targetOptions,
            FieldRules rules,
            Map<String, Object> values,
            Map<String, String> names,
            Map<String, String> codes) {
        var rounding = LinkageValues.checkRounding(rules.rounding());
        if (rounding != null) return Outcome.of(rounding, 0);
        Map<String, String> columns = new LinkedHashMap<>();
        codes.keySet().forEach(code -> columns.put(code, code));
        FieldExpressions.Parsed parsed;
        try {
            parsed = FieldExpressions.parse(rules.defaultFormula(), columns);
        } catch (ServiceException e) {
            return Outcome.fail(FieldRuleStateEnum.INCOMPLETE_CONFIG, "公式默认值无效：" + e.getMessage());
        }
        Object result;
        try {
            result =
                    FormulaEvaluator.evaluate(
                            parsed.expression(), code -> blank(values.get(codes.get(code))));
        } catch (ServiceException e) {
            return Outcome.fail(
                    FieldRuleStateEnum.VALUE_TYPE_MISMATCH, "公式默认值无法求值：" + e.getMessage());
        }
        if (result == null) {
            var pending =
                    parsed.references().stream()
                            .map(codes::get)
                            .filter(Objects::nonNull)
                            .filter(id -> FieldRuleConditions.candidates(values.get(id)).isEmpty())
                            .sorted()
                            .toList();
            // 依赖为空不落键、不写 0：说明在等哪些字段。
            if (!pending.isEmpty())
                return new Outcome(
                        FieldRuleStateEnum.PENDING_ROW_VALUE.getCode(),
                        null,
                        0,
                        false,
                        "公式默认值要先知道当前表单的「"
                                + String.join(
                                        "」「",
                                        pending.stream()
                                                .map(id -> names.getOrDefault(id, id))
                                                .toList())
                                + "」，请先填写",
                        pending);
            return new Outcome(
                    FieldRuleStateEnum.APPLIED.getCode(), null, 0, false, null, List.of());
        }
        Object value = LinkageValues.round(result, target, rules.rounding());
        if (value instanceof BigDecimal number) value = plain(number, target);
        var mismatch = mismatch(target, targetOptions, value);
        if (mismatch != null) return Outcome.fail(FieldRuleStateEnum.VALUE_TYPE_MISMATCH, mismatch);
        return new Outcome(FieldRuleStateEnum.APPLIED.getCode(), value, 0, false, null, List.of());
    }

    // ── 引用筛选 ──

    private ReferenceScope referenceScope(
            RuleContext ctx,
            String detailId,
            DataCenter.Relation relation,
            Map<String, Object> values,
            Session session) {
        var options = fieldOptions(ctx.definition(), relation);
        var rules = options == null ? null : options.rules();
        var reference = rules == null ? null : rules.reference();
        if (reference == null || reference.filter() == null || reference.filter().isEmpty())
            return new ReferenceScope(
                    FieldRuleStateEnum.APPLIED.getCode(), null, List.of(), null, false, null);
        var src = source(ctx, relation.targetObjectId(), session, false);
        if (src.failure() != null)
            return FieldRuleConditions.fail(
                    FieldRuleStateEnum.fromCode(src.failure().state()), src.failure().message());
        var d = ctx.definition();
        var names = names(d.fields(), detailId == null ? "" : "主表 · ");
        if (detailId != null)
            d.details().stream()
                    .filter(t -> t.id().equals(detailId))
                    .findFirst()
                    .ifPresent(t -> names.putAll(names(t.fields(), "")));
        return FieldRuleConditions.compile(
                reference.filter(),
                src.definition(),
                src.allowed(),
                values,
                names,
                objectNames(ctx, session));
    }

    /** 报错用的对象名：按本次求值同一口径解析对象定义，解析不了时为 null（调用方退回字段名）。 */
    private java.util.function.Function<String, String> objectNames(
            RuleContext ctx, Session session) {
        return id -> {
            var src = source(ctx, id, session, false);
            return src.failure() == null ? src.definition().objectName() : null;
        };
    }

    private Set<String> idsWithinScope(
            RuleContext ctx,
            DataCenter.Relation relation,
            ReferenceScope scope,
            Collection<String> ids,
            Session session) {
        if (ids == null || ids.isEmpty() || scope == null) return Set.of();
        if (!FieldRuleStateEnum.APPLIED.matches(scope.state())) return Set.of();
        var src = source(ctx, relation.targetObjectId(), session, false);
        if (src.failure() != null) return Set.of();
        // 规则筛选的可用字段仍取应用共享上限；任务候选／回显／保存复核必须使用同一引用读取范围，
        // 避免自动补齐依赖再次落入空 GROUP 集合，也不能绕过显式任务资源的记录边界。
        ApplicationRuntimePolicy.Access referenceAccess =
                taskScope.delegated()
                        ? policy.referenceAccess(
                                ctx.applicationId(),
                                ctx.definition(),
                                relation,
                                src.definition(),
                                ctx.actor(),
                                Set.of(),
                                com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects
                                        .impliedIn(ctx.preview(), relation.targetObjectId()))
                        : src.access();
        return selections.ruleIdsWithin(
                ctx.applicationId(),
                src.definition(),
                ids,
                scope,
                src.allowed(),
                ctx.actor(),
                referenceAccess);
    }

    private FieldRules.Result withInScope(
            RuleContext ctx,
            DataCenter.Definition d,
            String detailId,
            String fieldId,
            FieldRules.Result r,
            Map<String, Object> values,
            Session session) {
        var ids = FieldRuleConditions.candidates(values.get(fieldId));
        if (!FieldRuleStateEnum.APPLIED.matches(r.state()) || ids.isEmpty()) return r;
        var relation = relation(d, detailId, fieldId);
        var scope = referenceScope(ctx, detailId, relation, values, session);
        var list = ids.stream().map(Object::toString).toList();
        return inScope(r, idsWithinScope(ctx, relation, scope, list, session).containsAll(list));
    }

    // ── 来源对象与行缓存 ──

    /** system 为真时来源按全部数据读；缓存键带上这个标记，同一次求值里系统读与操作者读不串用。 */
    private Source source(RuleContext ctx, String objectId, Session session, boolean system) {
        return session.sources.computeIfAbsent(
                (system ? "S|" : "A|") + objectId, key -> resolve(ctx, objectId, system));
    }

    private Source resolve(RuleContext ctx, String objectId, boolean system) {
        DataCenter.Definition d;
        if (ctx.preview() != null) {
            d = ctx.preview().get(objectId);
            if (d == null)
                return Source.failed(FieldRuleStateEnum.SOURCE_TABLE_MISSING, "来源对象未加入当前应用引用，不填值");
        } else if (ObjectMaintenanceScope.permits(ctx.applicationId(), ctx.actor())) {
            // 对象数据维护入口不带应用：规则取自对象当前发布版，来源对象同样按其当前发布版解析，照常强制。
            try {
                d = selections.ruleDefinition(null, objectId, ctx.actor());
            } catch (ServiceException e) {
                return Source.failed(FieldRuleStateEnum.SOURCE_TABLE_MISSING, "来源对象未发布或已停用，不填值");
            }
        } else if (ctx.applicationId() == null) {
            // 既不带应用、也不在对象数据维护入口内：没有可依据的对象版本，明确失败而不是空指针。
            return Source.failed(
                    FieldRuleStateEnum.SOURCE_TABLE_MISSING, "缺少应用上下文，无法确定来源对象的版本，不填值");
        } else {
            // 结构化区分：先看是否在应用固定对象集里（或因关联而隐式可读），再取定义：显式引用按固定版本，隐式可读按最新发布版。
            boolean pinned =
                    applications.published(ctx.applicationId()).definition().objects().stream()
                            .anyMatch(r -> r.objectId().equals(objectId));
            if (!pinned && !impliedObjects.implied(ctx.applicationId(), objectId))
                return Source.failed(FieldRuleStateEnum.SOURCE_TABLE_MISSING, "来源对象未加入应用，不填值");
            try {
                d = selections.ruleSource(ctx.applicationId(), objectId, ctx.actor());
            } catch (ServiceException e) {
                return Source.failed(FieldRuleStateEnum.SOURCE_TABLE_MISSING, "来源对象已不存在或已停用，不填值");
            }
        }
        ApplicationRuntimePolicy.Access access;
        if (ObjectMaintenanceScope.permits(ctx.applicationId(), ctx.actor())) {
            // 对象数据维护入口：没有「应用对来源对象的授权」可查。开了自动更新的联动照旧用系统只读授权，其余按维护授权本身。
            if (system) access = policy.systemRead(d, ctx.actor());
            else
                try {
                    access = policy.access(ctx.applicationId(), d, ctx.actor());
                } catch (ServiceException e) {
                    return Source.failed(
                            FieldRuleStateEnum.SOURCE_NOT_READABLE,
                            "当前用户无权读取来源对象「" + d.objectName() + "」，不填值");
                }
        } else {
            // 系统取数：数据联动（不分是否开了自动更新）、引用筛选都按全部数据算，不看操作者在来源对象上的权限；
            // 前提是应用对来源对象的授权允许（全部记录、可查看、查看无记录条件）——默认授权是全量，所以默认情况下
            // 与「系统只读授权：全部字段可读」的结果相同。来源没被应用引用但因关联而隐式可读时同样成立。
            com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess.Outcome outcome =
                    systemRead(ctx, d);
            if (outcome.denial() != null)
                return Source.failed(
                        FieldRuleStateEnum.SOURCE_NOT_READABLE, outcome.denial().reason() + "，不填值");
            access = new ApplicationRuntimePolicy.Access(ctx.actor(), d, List.of(outcome.grant()));
        }
        return new Source(d, access, queryableFields(d, access), null, system);
    }

    /** 设计预览按草稿引用判断来源对象是不是隐式可读，运行期按应用的已发布版本判断。 */
    private com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess.Outcome systemRead(
            RuleContext ctx, DataCenter.Definition source) {
        java.util.function.BooleanSupplier implied =
                com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.impliedIn(
                        ctx.preview(), source.objectId());
        return implied == null
                ? systemRead.inspect(ctx.applicationId(), source, null)
                : systemRead.inspect(ctx.applicationId(), source, null, implied);
    }

    /** 来源字段读不到时的说明：系统取数的口径下，原因在应用对来源对象的授权，不在当前用户。 */
    private String sourceFieldDenied(RuleContext ctx, Source src, FieldDefinition field) {
        if (ObjectMaintenanceScope.permits(ctx.applicationId(), ctx.actor()))
            return "当前用户无权读取来源字段「" + field.name() + "」";
        java.util.function.BooleanSupplier implied =
                com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.impliedIn(
                        ctx.preview(), src.definition().objectId());
        com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess.Outcome outcome =
                implied == null
                        ? systemRead.inspect(
                                ctx.applicationId(), src.definition(), Set.of(field.id()))
                        : systemRead.inspect(
                                ctx.applicationId(), src.definition(), Set.of(field.id()), implied);
        return outcome.denial() != null
                ? outcome.denial().reason()
                : "来源字段「" + field.name() + "」是实时计算字段，数据联动读不了";
    }

    record Source(
            DataCenter.Definition definition,
            ApplicationRuntimePolicy.Access access,
            Set<String> allowed,
            Outcome failure,
            boolean system) {
        static Source failed(FieldRuleStateEnum state, String message) {
            return new Source(null, null, null, Outcome.fail(state, message), false);
        }

        /** 行缓存键的前缀：系统读与操作者读命中的行不同，不能共用。 */
        String cacheKey() {
            return (system ? "S|" : "A|") + definition.objectId();
        }
    }

    /** 单次请求的缓存：来源对象解析、以及「来源对象 + 已解析条件」的命中行。 */
    final class Session {
        final FieldRuleGraph graph;
        final Map<String, Source> sources = new HashMap<>();
        final Map<String, List<Row>> rows = new HashMap<>();
        final Map<String, List<Row>> enriched = new HashMap<>();

        Session(FieldRuleGraph graph) {
            this.graph = graph;
        }

        String key(ReferenceScope scope) {
            try {
                return json.writeValueAsString(
                        Arrays.asList(scope.conditions(), scope.recordKeys()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw invalid("规则条件无法序列化");
            }
        }

        List<Row> rows(RuleContext ctx, Source src, ReferenceScope scope) {
            return rows.computeIfAbsent(
                    src.cacheKey() + "|" + key(scope),
                    k ->
                            selections.ruleRows(
                                    ctx.applicationId(),
                                    src.definition(),
                                    src.access(),
                                    scope,
                                    src.allowed(),
                                    FieldRules.MATCH_CAP + 1,
                                    ctx.actor()));
        }

        /** 来源值字段是公式或汇总时，按 queryRows 同顺序补全计算值后再取格。 */
        List<Row> enriched(
                RuleContext ctx,
                Source src,
                ReferenceScope scope,
                List<Row> base,
                FieldRuleServiceImpl service) {
            return enriched.computeIfAbsent(
                    src.cacheKey() + "|" + key(scope) + "|" + base.size(),
                    k -> {
                        var d = src.definition();
                        var rows = service.summaries.enrich(d, base);
                        return ctx.preview() == null
                                ? service.calculations.enrich(
                                        ctx.applicationId(), d, rows, ctx.actor())
                                : service.calculations.enrich(
                                        ctx.applicationId(), d, rows, ctx.actor(), ctx.preview());
                    });
        }
    }

    /** 求值中间结果；落成 Result 时补字段、种类与行标识。readOnly 由 {@link #locked} 按配置写入，与状态无关。 */
    record Outcome(
            String state,
            Object value,
            int matchedRows,
            boolean readOnly,
            String message,
            List<String> pendingFields) {
        static Outcome fail(FieldRuleStateEnum state, String message) {
            return new Outcome(state.getCode(), null, 0, false, message, List.of());
        }

        static Outcome of(LinkageValues.Outcome value, int matched) {
            return new Outcome(
                    value.state(), value.value(), matched, false, value.message(), List.of());
        }

        /** 按配置标记只读：只读字段非 APPLIED 时同样只读（值为空、不能填）。 */
        Outcome locked(boolean readOnly) {
            return new Outcome(state, value, matchedRows, readOnly, message, pendingFields);
        }

        FieldRules.Result at(
                String fieldId, FieldRuleKindEnum kind, String detailId, String rowKey) {
            boolean applied = FieldRuleStateEnum.APPLIED.matches(state);
            return new FieldRules.Result(
                    fieldId,
                    kind.getCode(),
                    state,
                    applied ? value : null,
                    matchedRows,
                    readOnly,
                    message,
                    pendingFields == null ? List.of() : pendingFields,
                    detailId,
                    rowKey,
                    null);
        }
    }

    // ── 工具 ──

    /** 只有 APPLIED 且目标可被覆盖（用户未改过或只读）时，新值才参与下游求值。 */
    private static boolean written(FieldRules.Result r, Set<String> overridable) {
        return !FieldRuleKindEnum.REFERENCE.matches(r.kind())
                && FieldRuleStateEnum.APPLIED.matches(r.state())
                && (overridable.contains(r.fieldId()) || r.readOnly());
    }

    /** 只读字段未取到值（依赖未填或未命中）时字段为空、不能填：下游按空值求值，与保存时强制写空一致。 */
    private static boolean cleared(FieldRules.Result r) {
        return !FieldRuleKindEnum.REFERENCE.matches(r.kind())
                && r.readOnly()
                && (FieldRuleStateEnum.PENDING_ROW_VALUE.matches(r.state())
                        || FieldRuleStateEnum.NO_MATCH.matches(r.state()));
    }

    private static FieldRules.Result inScope(FieldRules.Result r, boolean inScope) {
        return new FieldRules.Result(
                r.fieldId(),
                r.kind(),
                r.state(),
                r.value(),
                r.matchedRows(),
                r.readOnly(),
                inScope ? r.message() : "所选记录不符合当前筛选",
                r.pendingFields(),
                r.detailId(),
                r.rowKey(),
                inScope);
    }

    private String mismatch(FieldDefinition target, DataCenter.FieldOptions options, Object value) {
        // 文本超长单独说明原因：仍是 VALUE_TYPE_MISMATCH（值不符合目标字段），不截断、不填值。
        var tooLong = LinkageValues.tooLong(target, value);
        if (tooLong != null) return tooLong;
        try {
            recordValues.convert(target, options, value);
            return null;
        } catch (ServiceException e) {
            return "结果「" + LinkageValues.text(value) + "」不符合字段「" + target.name() + "」的类型，不填值";
        }
    }

    /** 数值结果去掉无意义的尾零；整数目标只在没有小数部分时转成整数串，否则留给类型检查报不匹配。 */
    private static Object plain(BigDecimal number, FieldDefinition target) {
        var stripped = number.stripTrailingZeros();
        if (stripped.scale() < 0) stripped = stripped.setScale(0);
        return stripped.toPlainString();
    }

    private static Object blank(Object value) {
        return value instanceof String s && s.isEmpty() ? null : value;
    }

    private static Set<String> ruleFields(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options) {
        Set<String> result = new LinkedHashSet<>();
        for (var f : fields) {
            var o = options == null ? null : options.get(f.id());
            if (o != null && o.rules() != null) result.add(f.id());
        }
        return result;
    }

    /**
     * changed 的传递闭包（含自身）∩ 有规则的字段：带规则的变化字段自身也是目标，另加直接或间接依赖了任一变化字段的规则字段。保存时路 C 把要重算的字段本身放进 changed。
     */
    private static Set<String> downstream(
            FieldRuleGraph graph, Set<String> candidates, Set<String> changed) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : candidates)
            if (changed.contains(id) || graph.dependsOn(id).stream().anyMatch(changed::contains))
                result.add(id);
        return result;
    }

    private static Set<String> set(List<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    private static FieldDefinition find(List<FieldDefinition> fields, String id) {
        return fields.stream().filter(f -> Objects.equals(f.id(), id)).findFirst().orElse(null);
    }

    private static Map<String, String> names(List<FieldDefinition> fields, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        fields.forEach(f -> result.put(f.id(), prefix + f.name()));
        return result;
    }

    /** 公式编码到全局字段 ID：明细公式先登记主表编码，再以本行编码覆盖（本行优先）。 */
    private static Map<String, String> codes(
            List<FieldDefinition> fields, Map<String, String> base) {
        Map<String, String> result = new LinkedHashMap<>(base);
        fields.forEach(f -> result.put(f.code(), f.id()));
        return result;
    }

    private static DataCenter.Relation relation(
            DataCenter.Definition d, String detailId, String fieldId) {
        return d.relations().stream()
                .filter(
                        r ->
                                Objects.equals(r.fieldId(), fieldId)
                                        && Objects.equals(r.sourceDetailId(), detailId)
                                        && !RelationTypeEnum.MANY_TO_MANY.matches(r.kind()))
                .findFirst()
                .orElse(null);
    }
}
