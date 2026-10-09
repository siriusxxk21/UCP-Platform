package com.lingan.ucp.nocode.tools;

import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.tools.ReferenceConstantMigrationReport.Row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按报告行改对象定义里的条件固定值：只换那一个值，规则里其余内容（显示名字段、其它条件、联动开关等）原样保留。 */
final class ReferenceConstantEdits {
    private ReferenceConstantEdits() {}

    record Edited(Map<String, DataCenter.FieldOptions> main, List<DataCenter.Detail> details) {}

    /** forward：before → after（apply）；否则 after → before（rollback）。当前值与期望不符即抛出，不覆盖别人的改动。 */
    static Edited apply(
            Map<String, DataCenter.FieldOptions> main,
            List<DataCenter.Detail> details,
            List<Row> rows,
            boolean forward) {
        var mainOptions = new LinkedHashMap<>(main);
        Map<String, Map<String, DataCenter.FieldOptions>> detailOptions = new LinkedHashMap<>();
        for (var t : details) detailOptions.put(t.id(), new LinkedHashMap<>(t.fieldOptions()));
        for (var row : rows) {
            var options = row.detailId() == null ? mainOptions : detailOptions.get(row.detailId());
            var o = options == null ? null : options.get(row.fieldId());
            if (o == null || o.rules() == null)
                throw new IllegalStateException(row.describe() + "：字段规则已不存在");
            String expected = forward ? row.before() : row.after();
            String next = forward ? row.after() : row.before();
            options.put(row.fieldId(), o.withRules(replace(o.rules(), row, expected, next)));
        }
        List<DataCenter.Detail> nextDetails = new ArrayList<>();
        for (var t : details) {
            var changed = detailOptions.get(t.id());
            nextDetails.add(
                    changed.equals(t.fieldOptions())
                            ? t
                            : new DataCenter.Detail(
                                    t.id(),
                                    t.code(),
                                    t.name(),
                                    t.tableName(),
                                    t.state(),
                                    t.fields(),
                                    changed,
                                    t.indexes(),
                                    t.binding()));
        }
        return new Edited(mainOptions, nextDetails);
    }

    /** 当前定义里这一行对应位置上的值（字符串形式）；位置不存在时为 null。 */
    static String value(DataCenter.Definition d, Row row) {
        DataCenter.FieldOptions o;
        if (row.detailId() == null) o = d.fieldOptions().get(row.fieldId());
        else
            o =
                    d.details().stream()
                            .filter(t -> t.id().equals(row.detailId()))
                            .findFirst()
                            .map(t -> t.fieldOptions().get(row.fieldId()))
                            .orElse(null);
        var conditions = o == null || o.rules() == null ? null : conditions(o.rules(), row.rule());
        if (conditions == null || row.conditionIndex() >= conditions.size()) return null;
        var c = conditions.get(row.conditionIndex());
        Object raw = c == null ? null : c.value();
        if (raw instanceof List<?> items)
            raw = row.valueIndex() < items.size() ? items.get(row.valueIndex()) : null;
        else if (row.valueIndex() != 0) raw = null;
        return raw == null ? null : String.valueOf(raw);
    }

    private static List<FieldRules.Condition> conditions(FieldRules rules, String rule) {
        if (ReferenceConstantMigrationPlanner.LINKAGE.equals(rule))
            return rules.linkage() == null ? null : rules.linkage().conditions();
        return rules.reference() == null ? null : rules.reference().filter();
    }

    private static FieldRules replace(FieldRules rules, Row row, String expected, String next) {
        var list = conditions(rules, row.rule());
        if (list == null || row.conditionIndex() >= list.size())
            throw new IllegalStateException(row.describe() + "：条件已不存在");
        var c = list.get(row.conditionIndex());
        Object value;
        if (c.value() instanceof List<?> items) {
            if (row.valueIndex() >= items.size()
                    || !Objects.equals(String.valueOf(items.get(row.valueIndex())), expected))
                throw new IllegalStateException(row.describe() + "：当前值已不是「" + expected + "」");
            var copy = new ArrayList<Object>(items);
            copy.set(row.valueIndex(), next);
            value = copy;
        } else {
            if (row.valueIndex() != 0 || !Objects.equals(String.valueOf(c.value()), expected))
                throw new IllegalStateException(row.describe() + "：当前值已不是「" + expected + "」");
            value = next;
        }
        var conditions = new ArrayList<>(list);
        conditions.set(
                row.conditionIndex(),
                new FieldRules.Condition(
                        c.fieldId(), c.operator(), c.valueSource(), value, c.formFieldId()));
        if (ReferenceConstantMigrationPlanner.LINKAGE.equals(row.rule())) {
            var l = rules.linkage();
            return new FieldRules(
                    rules.reference(),
                    new FieldRules.Linkage(
                            l.sourceObjectId(),
                            conditions,
                            l.valueFieldId(),
                            l.multiRow(),
                            l.readOnly(),
                            l.autoUpdate(),
                            l.emptyValue()),
                    rules.defaultFormula(),
                    rules.rounding(),
                    null,
                    null);
        }
        return new FieldRules(
                new FieldRules.Reference(rules.reference().labelFieldId(), conditions),
                rules.linkage(),
                rules.defaultFormula(),
                rules.rounding(),
                null,
                null);
    }
}
