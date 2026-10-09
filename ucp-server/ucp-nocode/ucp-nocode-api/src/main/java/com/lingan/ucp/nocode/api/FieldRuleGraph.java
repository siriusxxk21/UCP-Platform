package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.RuleValueSourceEnum;

import java.util.*;

/**
 * 对象规则依赖图（纯函数），运行时求值顺序与发布校验共用。节点是主表与全部有效内部明细字段的全局稳定 ID；边从依赖字段指向规则目标，来源为
 * 数据联动与引用筛选中的当前字段条件（FORM_FIELD）以及公式默认值引用的字段编码。明细字段的公式编码先按本行解析、再按主表解析。
 */
public final class FieldRuleGraph {
    /** 目标字段 → 直接依赖字段。 */
    private final Map<String, Set<String>> direct;

    /** 字段全局 ID → 所属内部明细 ID；主表字段为 null。 */
    private final Map<String, String> tables;

    /** 按定义顺序排列的全部节点。 */
    private final List<String> order;

    /** 配置了对象规则的字段。 */
    private final Set<String> ruled;

    private final List<String> topo;
    private final List<String> cycle;

    private FieldRuleGraph(
            Map<String, Set<String>> direct,
            Map<String, String> tables,
            List<String> order,
            Set<String> ruled) {
        this.direct = direct;
        this.tables = tables;
        this.order = order;
        this.ruled = ruled;
        this.cycle = findCycle();
        this.topo = sort();
    }

    public static FieldRuleGraph of(DataCenter.Definition d) {
        Map<String, String> tables = new LinkedHashMap<>();
        Map<String, FieldRules> rules = new LinkedHashMap<>();
        Map<String, String> mainCodes = new HashMap<>();
        for (var f : d.fields()) {
            var o = d.fieldOptions().get(f.id());
            if (!active(o)) continue;
            tables.put(f.id(), null);
            mainCodes.putIfAbsent(f.code(), f.id());
            if (o != null && o.rules() != null) rules.put(f.id(), o.rules());
        }
        Map<String, Map<String, String>> detailCodes = new HashMap<>();
        for (var detail : d.details() == null ? List.<DataCenter.Detail>of() : d.details()) {
            if (MemberStateEnum.INACTIVE.matches(detail.state())) continue;
            var codes = new HashMap<String, String>();
            for (var f : detail.fields()) {
                var o = detail.fieldOptions() == null ? null : detail.fieldOptions().get(f.id());
                if (!active(o)) continue;
                tables.put(f.id(), detail.id());
                codes.putIfAbsent(f.code(), f.id());
                if (o != null && o.rules() != null) rules.put(f.id(), o.rules());
            }
            detailCodes.put(detail.id(), codes);
        }
        Map<String, Set<String>> direct = new LinkedHashMap<>();
        for (var entry : rules.entrySet()) {
            String target = entry.getKey();
            var r = entry.getValue();
            var deps = new LinkedHashSet<String>();
            if (r.linkage() != null) formFields(r.linkage().conditions(), deps);
            if (r.reference() != null) formFields(r.reference().filter(), deps);
            if (r.defaultFormula() != null) {
                String detailId = tables.get(target);
                var local = detailId == null ? Map.<String, String>of() : detailCodes.get(detailId);
                for (String code : formulaCodes(r.defaultFormula())) {
                    String id = local.get(code);
                    if (id == null) id = mainCodes.get(code);
                    if (id != null) deps.add(id);
                }
            }
            deps.retainAll(tables.keySet());
            direct.put(target, deps);
        }
        var ruled = new LinkedHashSet<String>();
        rules.forEach(
                (id, r) -> {
                    if (r.linkage() != null || r.reference() != null || r.defaultFormula() != null)
                        ruled.add(id);
                });
        return new FieldRuleGraph(direct, tables, new ArrayList<>(tables.keySet()), ruled);
    }

    /** 该字段规则的传递依赖（明细字段可含主表字段），按定义顺序；无规则时为空。 */
    public List<String> dependsOn(String fieldId) {
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>(direct.getOrDefault(fieldId, Set.of()));
        while (!pending.isEmpty()) {
            String next = pending.poll();
            if (seen.add(next)) pending.addAll(direct.getOrDefault(next, Set.of()));
        }
        return order.stream().filter(seen::contains).toList();
    }

    /** 该字段规则的直接依赖（不展开传递依赖）；无规则时为空。 */
    public Set<String> direct(String fieldId) {
        return Set.copyOf(direct.getOrDefault(fieldId, Set.of()));
    }

    /** 配置了规则的字段按依赖先后排列；存在环时环上字段不出现（发布校验会拒绝成环配置）。 */
    public List<String> topo() {
        return topo;
    }

    /** 第一个依赖环，首尾为同一字段；无环返回空列表。 */
    public List<String> cycle() {
        return cycle;
    }

    /** 主表字段变化会影响的明细规则字段：明细 ID → 规则字段集合；只含非空分组。 */
    public Map<String, Set<String>> detailTargetsOfMaster(String masterFieldId) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (!tables.containsKey(masterFieldId) || tables.get(masterFieldId) != null) return result;
        for (String id : order) {
            String detailId = tables.get(id);
            if (detailId == null || !ruled.contains(id)) continue;
            if (dependsOn(id).contains(masterFieldId))
                result.computeIfAbsent(detailId, key -> new LinkedHashSet<>()).add(id);
        }
        return result;
    }

    /** 字段所属内部明细 ID；主表字段或未知字段为 null。 */
    public String detailOf(String fieldId) {
        return tables.get(fieldId);
    }

    /** 公式默认值中的字段编码：受控语法里标识符由字母、数字和下划线组成且不以数字开头，后接左括号的是函数名，单引号内是文本常量。 仅用于建图；语法合法性由元数据层的公式解析器负责。 */
    public static Set<String> formulaCodes(String formula) {
        var result = new LinkedHashSet<String>();
        if (formula == null) return result;
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if (c == '\'') {
                i++;
                while (i < formula.length()) {
                    if (formula.charAt(i) == '\'') {
                        if (i + 1 < formula.length() && formula.charAt(i + 1) == '\'') i += 2;
                        else break;
                    } else i++;
                }
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                while (i < formula.length()
                        && (Character.isDigit(formula.charAt(i)) || formula.charAt(i) == '.')) i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < formula.length()
                        && (Character.isLetterOrDigit(formula.charAt(i))
                                || formula.charAt(i) == '_')) i++;
                int next = i;
                while (next < formula.length() && Character.isWhitespace(formula.charAt(next)))
                    next++;
                if (next >= formula.length() || formula.charAt(next) != '(')
                    result.add(formula.substring(start, i));
            } else i++;
        }
        return result;
    }

    private static boolean active(DataCenter.FieldOptions o) {
        return o == null || !MemberStateEnum.INACTIVE.matches(o.state());
    }

    private static void formFields(List<FieldRules.Condition> conditions, Set<String> deps) {
        if (conditions == null) return;
        for (var c : conditions)
            if (c != null
                    && RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource())
                    && c.formFieldId() != null) deps.add(c.formFieldId());
    }

    private List<String> findCycle() {
        var state = new HashMap<String, Integer>();
        var stack = new ArrayList<String>();
        // 边方向为“依赖 → 目标”；从目标反向遍历依赖得到的环再反转，保持书写顺序。
        for (String start : order) {
            if (state.getOrDefault(start, 0) != 0) continue;
            var found = visit(start, state, stack);
            if (!found.isEmpty()) {
                var path = new ArrayList<>(found);
                Collections.reverse(path);
                return List.copyOf(path);
            }
        }
        return List.of();
    }

    private List<String> visit(String id, Map<String, Integer> state, List<String> stack) {
        state.put(id, 1);
        stack.add(id);
        for (String next : direct.getOrDefault(id, Set.of())) {
            int seen = state.getOrDefault(next, 0);
            if (seen == 1) {
                var path = new ArrayList<>(stack.subList(stack.indexOf(next), stack.size()));
                path.add(next);
                return path;
            }
            if (seen == 0) {
                var found = visit(next, state, stack);
                if (!found.isEmpty()) return found;
            }
        }
        stack.remove(stack.size() - 1);
        state.put(id, 2);
        return List.of();
    }

    private List<String> sort() {
        var result = new ArrayList<String>();
        var done = new HashSet<String>();
        var blocked = new HashSet<String>(cycle);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String id : order) {
                if (done.contains(id) || blocked.contains(id)) continue;
                if (!done.containsAll(direct.getOrDefault(id, Set.of()))) continue;
                done.add(id);
                if (ruled.contains(id)) result.add(id);
                progress = true;
            }
        }
        return List.copyOf(result);
    }
}
