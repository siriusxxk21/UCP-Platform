package com.richuang.os.nocode.runtime.service.rules;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;
import com.richuang.os.nocode.metadata.service.formula.FormulaEvaluator;
import com.richuang.os.nocode.metadata.service.formula.MoneyRounding;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

/**
 * 保存时的对象规则强制（设计稿 6 章、15.3.2、15.4.5）。所有写入入口都经过公共保存流水线调用本组件。
 *
 * <p>依赖值一律取服务端合并值（旧值加本次写入），不接受客户端声明的依赖或配置。只读口径（2026-09-29 业务方裁定，取代设计稿 D7/B29/B31）：
 *
 * <ul>
 *   <li>只读联动（linkage.readOnly 为 true 或 null）：新建、任一依赖变化或客户端提交该字段时重算；APPLIED 写服务端值，PENDING_ROW_VALUE
 *       与 NO_MATCH 强制写空（不接受客户端值），其它状态拒绝保存。显式 false 的联动保存时不重算。
 *   <li>公式默认值一律只读：新建、任一依赖变化或客户端提交该字段时重算，忽略客户端值；结果为空时写空（更新时清掉旧值）。
 *   <li>写权限：属服务端受控赋值，不要求表单节点可写，但仍受字段写权限约束；无权时不写、保持旧值。
 * </ul>
 */
@Component
public class FieldRuleEnforcer {
    /** 状态与种类编码同 FieldRuleStateEnum、FieldRuleKindEnum（5.3）。 */
    static final String APPLIED = "APPLIED";

    static final String PENDING = "PENDING_ROW_VALUE";
    static final String NO_MATCH = "NO_MATCH";
    static final String LINKAGE = "LINKAGE";
    static final String FORM_FIELD = "FORM_FIELD";

    /** 规则错误定位到字段；明细行错误由写入编排包装成行级问题。 */
    private static final String FIELD_KEY = "fieldId";

    @Resource private FieldRuleEvaluator evaluator;

    /** 主表：只读联动强制与公式默认值重算。返回新的写入值，不修改入参。 */
    public Map<String, Object> prepare(
            RuleContext ctx,
            boolean insert,
            Map<String, Object> previous,
            Map<String, Object> input,
            Set<String> clientKeys,
            Set<String> authorized) {
        var d = ctx.definition();
        var rules = rules(d.fields(), d.fieldOptions());
        if (rules.isEmpty()) return input;
        var graph = FieldRuleGraph.of(d);
        var names = names(d);
        Map<String, Object> working = new LinkedHashMap<>(previous);
        working.putAll(input);
        Map<String, Object> result = new LinkedHashMap<>(input);
        Map<String, String> codes = new HashMap<>();
        d.fields().forEach(f -> codes.put(f.code(), f.id()));
        for (String id : graph.topo()) {
            var rule = rules.get(id);
            if (rule == null || !rule.readOnlyLinkage() && rule.defaultFormula() == null) continue;
            boolean triggered =
                    insert
                            || clientKeys.contains(id)
                            || graph.dependsOn(id).stream()
                                    .anyMatch(dep -> !same(previous.get(dep), working.get(dep)));
            if (!triggered) continue;
            if (rule.readOnlyLinkage()) {
                var outcome =
                        first(
                                evaluator.evaluate(ctx, working, Set.of(id), Set.of(), insert),
                                id,
                                null,
                                null);
                force(
                        id,
                        names.get(id),
                        outcome,
                        clientKeys,
                        authorized,
                        previous,
                        working,
                        result);
            } else {
                Object value =
                        authorized.contains(id)
                                ? formula(
                                        field(d.fields(), id),
                                        rule,
                                        codes,
                                        working::get,
                                        names.get(id))
                                : null;
                write(id, value, authorized, previous, working, result);
            }
        }
        return result;
    }

    /** 主表：挑对象的值必须落在对象引用筛选内。merged 是服务端合并值（全局字段 ID）。 */
    public void validateReferences(
            RuleContext ctx,
            Map<String, Object> merged,
            Map<String, Object> previous,
            boolean insert) {
        var d = ctx.definition();
        var rules = rules(d.fields(), d.fieldOptions());
        if (rules.isEmpty()) return;
        var graph = FieldRuleGraph.of(d);
        var names = names(d);
        for (var relation : d.relations()) {
            if (relation.sourceDetailId() != null || BusinessFields.multiple(relation)) continue;
            var rule = rules.get(relation.fieldId());
            if (!filtered(rule)) continue;
            String id = relation.fieldId();
            Object value = merged.get(id);
            if (blank(value)) continue;
            boolean triggered =
                    insert
                            || !same(value, previous.get(id))
                            || graph.dependsOn(id).stream()
                                    .anyMatch(dep -> !same(previous.get(dep), merged.get(dep)));
            if (triggered) requireInScope(ctx, null, relation, merged, value, names);
        }
    }

    /** 一行明细的规则输入：previous 为库中旧值，input 为本次写入值，clientKeys 为客户端提交的字段。 */
    public record RowState(
            String rowKey,
            String rowId,
            boolean create,
            Map<String, Object> previous,
            Map<String, Object> input,
            Set<String> clientKeys) {}

    /** 应用规则后的本行写入值；error 非空时该行保存失败，由写入编排按行定位。 */
    public record RowOutcome(Map<String, Object> input, ServiceException error) {}

    /** 单行入口，等同只含一行的 {@link #prepareRows}；该行失败时直接抛出。 */
    public Map<String, Object> prepareRow(
            RuleContext ctx,
            DataCenter.Detail detail,
            String rowKey,
            String rowId,
            boolean create,
            Map<String, Object> previous,
            Map<String, Object> input,
            Map<String, Object> mainPrevious,
            Map<String, Object> mainCandidate,
            Set<String> clientKeys) {
        var outcome =
                prepareRows(
                                ctx,
                                detail,
                                List.of(
                                        new RowState(
                                                rowKey,
                                                rowId,
                                                create,
                                                previous,
                                                input,
                                                clientKeys)),
                                mainPrevious,
                                mainCandidate)
                        .getFirst();
        if (outcome.error() != null) throw outcome.error();
        return outcome.input();
    }

    /**
     * 明细分组：只读联动强制与公式默认值重算（15.4.5，只读口径同主表）。整组待重算的行一次交给求值器，使来源查询按行间缓存复用。
     *
     * <p>新增行全部重算（复制行带来的值同样被覆盖）；已有行只在本行依赖、主表依赖变化或客户端提交该字段时重算。公式默认值先按当前值算一遍（联动条件可能引用它），
     * 只读联动强制写入后再按最终值重算一遍。返回值与 rows 一一对应。
     */
    public List<RowOutcome> prepareRows(
            RuleContext ctx,
            DataCenter.Detail detail,
            List<RowState> rows,
            Map<String, Object> mainPrevious,
            Map<String, Object> mainCandidate) {
        var rules = rules(detail.fields(), detail.fieldOptions());
        if (rules.isEmpty())
            return rows.stream().map(r -> new RowOutcome(r.input(), null)).toList();
        var d = ctx.definition();
        var graph = FieldRuleGraph.of(d);
        var names = names(d);
        Set<String> rowFields = new HashSet<>();
        detail.fields().forEach(f -> rowFields.add(f.id()));
        var masterChanged = changed(mainPrevious, mainCandidate);
        // 明细公式按编码解析：本行编码优先，其余取主表（15.4.2）。
        Map<String, String> codes = new HashMap<>();
        d.fields().forEach(f -> codes.put(f.code(), f.id()));
        detail.fields().forEach(f -> codes.put(f.code(), f.id()));
        var order = graph.topo().stream().filter(rules::containsKey).toList();
        var readonly = order.stream().filter(id -> rules.get(id).readOnlyLinkage()).toList();
        var formulas =
                order.stream()
                        .filter(
                                id ->
                                        !rules.get(id).readOnlyLinkage()
                                                && rules.get(id).defaultFormula() != null)
                        .toList();
        var formulaPass =
                new FormulaPass(
                        detail, rules, formulas, graph, codes, rowFields, masterChanged, names);
        List<Map<String, Object>> working = new ArrayList<>(), results = new ArrayList<>();
        List<Set<String>> produced = new ArrayList<>(), triggered = new ArrayList<>();
        ServiceException[] errors = new ServiceException[rows.size()];
        List<FieldRules.RowInput> batch = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            Map<String, Object> values = new LinkedHashMap<>(row.previous());
            values.putAll(row.input());
            working.add(values);
            results.add(new LinkedHashMap<>(row.input()));
            produced.add(new LinkedHashSet<>());
            Set<String> targets = new LinkedHashSet<>();
            triggered.add(targets);
            try {
                formulaPass.run(row, values, results.get(i), mainCandidate, produced.get(i));
            } catch (ServiceException e) {
                errors[i] = e;
                continue;
            }
            for (String id : readonly) if (formulaPass.triggered(row, id, values)) targets.add(id);
            if (targets.isEmpty()) continue;
            // 新增行 changed 为空即取本行全部规则；已有行 changed 含目标本身与其本行依赖（目标自身也算目标）。
            List<String> rowChanged = new ArrayList<>();
            if (!row.create())
                for (String id : targets) {
                    rowChanged.add(id);
                    graph.dependsOn(id).stream()
                            .filter(rowFields::contains)
                            .forEach(rowChanged::add);
                }
            batch.add(
                    new FieldRules.RowInput(
                            row.rowKey(),
                            row.rowId(),
                            row.create(),
                            new LinkedHashMap<>(values),
                            List.copyOf(new LinkedHashSet<>(rowChanged)),
                            List.copyOf(produced.get(i))));
        }
        List<FieldRules.Result> evaluated =
                batch.isEmpty()
                        ? List.of()
                        : evaluator.evaluateRows(
                                ctx, detail.id(), mainCandidate, masterChanged, batch);
        List<RowOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (errors[i] != null) {
                outcomes.add(new RowOutcome(row.input(), errors[i]));
                continue;
            }
            var values = working.get(i);
            var result = results.get(i);
            try {
                for (String id : triggered.get(i))
                    force(
                            id,
                            names.get(id),
                            first(evaluated, id, detail.id(), row.rowKey()),
                            row.clientKeys(),
                            rowFields,
                            row.previous(),
                            values,
                            result);
                // 公式默认值可能引用刚强制写入的只读联动值：按最终值重算（第一遍算过的与因此变化的依赖都算）。
                if (!triggered.get(i).isEmpty())
                    formulaPass.run(row, values, result, mainCandidate, produced.get(i));
                outcomes.add(new RowOutcome(result, null));
            } catch (ServiceException e) {
                outcomes.add(new RowOutcome(row.input(), e));
            }
        }
        return outcomes;
    }

    /**
     * 明细一行的公式默认值重算：按拓扑序，触发（新增行、客户端提交、本行依赖或主表依赖变化）或本轮已算过（produced）的公式写入 values 与 result，
     * 结果为空时写空。本行编码优先，其余取主表候选值（15.4.2）。
     */
    private record FormulaPass(
            DataCenter.Detail detail,
            Map<String, FieldRules> rules,
            List<String> formulas,
            FieldRuleGraph graph,
            Map<String, String> codes,
            Set<String> rowFields,
            Set<String> masterChanged,
            Map<String, String> names) {
        void run(
                RowState row,
                Map<String, Object> values,
                Map<String, Object> result,
                Map<String, Object> mainCandidate,
                Set<String> produced) {
            for (String id : formulas) {
                if (!produced.contains(id) && !triggered(row, id, values)) continue;
                Object value =
                        formula(
                                field(detail.fields(), id),
                                rules.get(id),
                                codes,
                                f -> rowFields.contains(f) ? values.get(f) : mainCandidate.get(f),
                                names.get(id));
                values.put(id, value);
                result.put(id, value);
                produced.add(id);
            }
        }

        /** 新增行、客户端提交该字段、本行依赖相对库中旧值变化或主表依赖变化。 */
        boolean triggered(RowState row, String id, Map<String, Object> values) {
            return row.create()
                    || row.clientKeys().contains(id)
                    || graph.dependsOn(id).stream()
                            .anyMatch(
                                    dep ->
                                            rowFields.contains(dep)
                                                    ? !same(
                                                            row.previous().get(dep),
                                                            values.get(dep))
                                                    : masterChanged.contains(dep));
        }
    }

    /** 明细行：引用筛选复核。rowValues 是本行服务端合并值；主表值取本次候选值。 */
    public void validateRowReferences(
            RuleContext ctx,
            DataCenter.Detail detail,
            Map<String, Object> rowValues,
            Map<String, Object> previous,
            Map<String, Object> mainPrevious,
            Map<String, Object> mainCandidate,
            boolean create) {
        var rules = rules(detail.fields(), detail.fieldOptions());
        if (rules.isEmpty()) return;
        var d = ctx.definition();
        var graph = FieldRuleGraph.of(d);
        var names = names(d);
        Set<String> rowFields = new HashSet<>();
        detail.fields().forEach(f -> rowFields.add(f.id()));
        var masterChanged = changed(mainPrevious, mainCandidate);
        Map<String, Object> merged = new LinkedHashMap<>(mainCandidate);
        merged.putAll(rowValues);
        for (var relation : d.relations()) {
            if (!detail.id().equals(relation.sourceDetailId())) continue;
            var rule = rules.get(relation.fieldId());
            if (!filtered(rule)) continue;
            String id = relation.fieldId();
            Object value = rowValues.get(id);
            if (blank(value)) continue;
            boolean triggered =
                    create
                            || !same(value, previous.get(id))
                            || graph.dependsOn(id).stream()
                                    .anyMatch(
                                            dep ->
                                                    rowFields.contains(dep)
                                                            ? !same(
                                                                    previous.get(dep),
                                                                    rowValues.get(dep))
                                                            : masterChanged.contains(dep));
            if (triggered) requireInScope(ctx, detail.id(), relation, merged, value, names);
        }
    }

    /** 主表依赖变化会影响其只读联动、公式默认值或引用筛选的明细分组；API 省略这些分组时由写入编排补齐整组。 */
    public Set<String> detailsAffectedByMaster(
            DataCenter.Definition d,
            Map<String, Object> mainPrevious,
            Map<String, Object> candidate) {
        Set<String> result = new LinkedHashSet<>();
        var masterChanged = changed(mainPrevious, candidate);
        if (masterChanged.isEmpty()) return result;
        var graph = FieldRuleGraph.of(d);
        for (String master : masterChanged)
            graph.detailTargetsOfMaster(master)
                    .forEach(
                            (detailId, targets) -> {
                                var detail =
                                        d.details().stream()
                                                .filter(x -> x.id().equals(detailId))
                                                .findFirst()
                                                .orElse(null);
                                if (detail == null) return;
                                var rules = rules(detail.fields(), detail.fieldOptions());
                                if (targets.stream()
                                        .map(rules::get)
                                        .anyMatch(
                                                r ->
                                                        r != null
                                                                && (r.readOnlyLinkage()
                                                                        || r.defaultFormula()
                                                                                != null
                                                                        || filtered(r))))
                                    result.add(detailId);
                            });
        return result;
    }

    /** 规则错误所指字段；非本组件抛出的错误返回 null。 */
    public static String fieldId(ServiceException error) {
        return error.getDetails() instanceof Map<?, ?> details
                        && details.get(FIELD_KEY) instanceof String id
                ? id
                : null;
    }

    private static void force(
            String id,
            String name,
            FieldRules.Result outcome,
            Set<String> clientKeys,
            Set<String> authorized,
            Map<String, Object> previous,
            Map<String, Object> working,
            Map<String, Object> result) {
        if (outcome == null) {
            // 操作者读不到的字段求值器不返回结果；客户端试图写它时不能放行未经复核的值。
            if (clientKeys.contains(id)) throw problem(id, "字段「" + name + "」的数据联动无法求值，请刷新后重试");
            return;
        }
        boolean applied = APPLIED.equals(outcome.state());
        // 只读联动未命中或依赖未填：字段为空且不能填，强制写空、不接受客户端值（2026-09-29 口径）。
        if (!applied && !PENDING.equals(outcome.state()) && !NO_MATCH.equals(outcome.state()))
            throw problem(
                    id,
                    "字段「"
                            + name
                            + "」的数据联动"
                            + FieldRuleConditions.reason(outcome.message(), outcome.state()));
        write(id, applied ? outcome.value() : null, authorized, previous, working, result);
    }

    /** 服务端受控赋值：不要求表单节点可写，但仍受字段写权限约束；无权时不写、保持旧值，客户端值也不接受。 */
    private static void write(
            String id,
            Object value,
            Set<String> authorized,
            Map<String, Object> previous,
            Map<String, Object> working,
            Map<String, Object> result) {
        if (!authorized.contains(id)) {
            result.remove(id);
            working.put(id, previous.get(id));
            return;
        }
        result.put(id, value);
        working.put(id, value);
    }

    private void requireInScope(
            RuleContext ctx,
            String detailId,
            DataCenter.Relation relation,
            Map<String, Object> values,
            Object value,
            Map<String, String> names) {
        String id = relation.fieldId();
        String name = names.getOrDefault(id, relation.name());
        var scope = evaluator.referenceScope(ctx, detailId, relation, values);
        if (scope == null) throw problem(id, "「" + name + "」的引用筛选无法求值，请刷新后重试");
        if (PENDING.equals(scope.state()))
            throw problem(
                    id,
                    "请先填写「"
                            + String.join(
                                    "」「",
                                    (scope.pendingFields() == null
                                                    ? List.<String>of()
                                                    : scope.pendingFields())
                                            .stream().map(f -> names.getOrDefault(f, f)).toList())
                            + "」再选择「"
                            + name
                            + "」");
        if (!APPLIED.equals(scope.state()))
            throw problem(
                    id,
                    "「"
                            + name
                            + "」的引用筛选"
                            + FieldRuleConditions.reason(scope.message(), scope.state()));
        var ids = SelectionCatalog.ids(value);
        var within = evaluator.idsWithinScope(ctx, relation, scope, ids);
        if (within == null || !within.containsAll(ids))
            throw problem(id, "「" + name + "」所选记录不符合对象引用筛选，请重新选择");
    }

    /** 公式默认值：依赖为空时结果为空（调用方写空、不写 0）；MONEY 目标按取整方式取成整数（15.3.2）。 */
    private static Object formula(
            FieldDefinition field,
            FieldRules rule,
            Map<String, String> codes,
            Function<String, Object> lookup,
            String name) {
        Object raw;
        try {
            raw =
                    FormulaEvaluator.evaluate(
                            FieldExpressions.parse(rule.defaultFormula(), codes).expression(),
                            id -> blank(lookup.apply(id)) ? null : lookup.apply(id));
        } catch (ServiceException e) {
            throw problem(field.id(), "字段「" + name + "」的公式默认值无法求值：" + e.getMessage());
        }
        if (raw == null) return null;
        var type = FieldTypeEnum.fromCode(field.type());
        if (!type.isNumeric()) return raw instanceof BigDecimal n ? n.toPlainString() : raw;
        BigDecimal number;
        try {
            number = FormulaEvaluator.number(raw);
        } catch (ServiceException e) {
            throw problem(field.id(), "字段「" + name + "」的公式默认值结果不是数值：" + raw);
        }
        if (type == FieldTypeEnum.MONEY)
            return MoneyRounding.settle(number, rounding(field.id(), name, rule.rounding()))
                    .toPlainString();
        var exact = number.stripTrailingZeros();
        if (exact.scale() < 0) exact = exact.setScale(0);
        int scale = type == FieldTypeEnum.INTEGER ? 0 : field.scale() == null ? -1 : field.scale();
        // 非金额数值不取整：超出字段小数位时拒绝，不静默截断（VALUE_TYPE_MISMATCH 同口径）。
        if (scale >= 0 && exact.scale() > scale)
            throw problem(
                    field.id(), "字段「" + name + "」的公式默认值结果「" + exact.toPlainString() + "」超出字段小数位数");
        return exact.toPlainString();
    }

    /** 取整方式编码同 MoneyRoundingEnum：HALF_UP / FLOOR / DOWN，空为 FLOOR。 */
    static RoundingMode rounding(String id, String name, String code) {
        if (code == null) return RoundingMode.FLOOR;
        return switch (code) {
            case "HALF_UP" -> RoundingMode.HALF_UP;
            case "FLOOR" -> RoundingMode.FLOOR;
            case "DOWN" -> RoundingMode.DOWN;
            default ->
                    throw problem(
                            id, "字段「" + name + "」的取整方式「" + code + "」无效，可选：四舍五入 / 向下取整 / 去掉小数");
        };
    }

    private static FieldRules.Result first(
            List<FieldRules.Result> results, String id, String detailId, String rowKey) {
        if (results == null) return null;
        return results.stream()
                .filter(
                        r ->
                                id.equals(r.fieldId())
                                        && LINKAGE.equals(r.kind())
                                        && (detailId == null
                                                || r.detailId() == null
                                                || detailId.equals(r.detailId()))
                                        && (rowKey == null
                                                || r.rowKey() == null
                                                || rowKey.equals(r.rowKey())))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, FieldRules> rules(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options) {
        Map<String, FieldRules> result = new LinkedHashMap<>();
        if (options == null) return result;
        for (var field : fields) {
            var option = options.get(field.id());
            if (option == null
                    || option.rules() == null
                    || MemberStateEnum.INACTIVE.matches(option.state())) continue;
            result.put(field.id(), option.rules());
        }
        return result;
    }

    private static boolean filtered(FieldRules rule) {
        return rule != null
                && rule.reference() != null
                && rule.reference().filter() != null
                && !rule.reference().filter().isEmpty();
    }

    private static Map<String, String> names(DataCenter.Definition d) {
        Map<String, String> names = new HashMap<>();
        d.fields().forEach(f -> names.put(f.id(), f.name()));
        for (var detail : d.details())
            detail.fields().forEach(f -> names.put(f.id(), detail.name() + " · " + f.name()));
        return names;
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String id) {
        return fields.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    private static Set<String> changed(Map<String, Object> before, Map<String, Object> after) {
        Set<String> result = new LinkedHashSet<>();
        after.forEach(
                (id, value) -> {
                    if (!same(before.get(id), value)) result.add(id);
                });
        return result;
    }

    /** 空值、空串、空集合视为没有值。 */
    static boolean blank(Object value) {
        return value == null
                || value instanceof String s && s.isEmpty()
                || value instanceof Collection<?> c && c.isEmpty();
    }

    /** 比较旧值与候选值：集合按选择 ID，数值按大小，避免 JSON 读回类型与规范化类型不同而误判为变化。 */
    public static boolean same(Object a, Object b) {
        if (blank(a) || blank(b)) return blank(a) && blank(b);
        if (a instanceof Collection<?> || b instanceof Collection<?>)
            return SelectionCatalog.ids(a).equals(SelectionCatalog.ids(b));
        var x = decimal(a);
        var y = decimal(b);
        if (x != null && y != null) return x.compareTo(y) == 0;
        return a.toString().equals(b.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Boolean) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ServiceException problem(String fieldId, String message) {
        return invalid(message).setDetails(Map.of(FIELD_KEY, fieldId));
    }
}
