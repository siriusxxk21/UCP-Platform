package com.lingan.ucp.nocode.tools;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.BusinessFields;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRuleMatrix;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.api.RecordConditionValues;
import com.lingan.ucp.nocode.api.RelativeDates;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.RecordQueryOperatorEnum;
import com.lingan.ucp.nocode.enums.RuleValueSourceEnum;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleLabels;
import com.lingan.ucp.nocode.tools.ReferenceConstantMigrationReport.Row;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 引用字段条件固定值的存量判定（纯计算；读库由 {@link Targets} 给出）。范围是对象字段规则里 RuleConditionRows 那套条件：主表与明细字段的
 * 引用筛选（rules.reference.filter）与数据联动条件（rules.linkage.conditions）。
 *
 * <p>一行 = 一个条件值。只列「不合法」的值：条件字段是单值引用、值来源固定值、比较不是为空类，而值按字段类型转换不了或目标对象里没有这个主键。 不合法时按显示名找（显示名与表单候选同一个
 * {@link FieldRuleLabels#label}）：完全相同的恰好 1 条 ⇒ CONVERT；0 条 NO_MATCH；多条 AMBIGUOUS。
 * 同一对象里只要还有不能转换的值，这个对象的 CONVERT 一律改记 BLOCKED（对象设计保存校验整个对象，改一半发布不出去）。
 */
final class ReferenceConstantMigrationPlanner {
    static final String REFERENCE_FILTER = "REFERENCE_FILTER", LINKAGE = "LINKAGE";
    static final String CONVERT = "CONVERT", NO_MATCH = "NO_MATCH", AMBIGUOUS = "AMBIGUOUS";
    static final String TOO_MANY_ROWS = "TOO_MANY_ROWS", TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE";

    /** 能转换、但同一对象里还有不能自动转换的值：对象设计保存会因那几处被拒，这个对象整体不动（apply 跳过），先在对象设计里手工改掉那几处再 dry-run。 */
    static final String BLOCKED = "BLOCKED";

    /** 按显示名找记录时最多扫的目标行数；超过即不动、列进报告。 */
    static final int SCAN_CAP = 200_000;

    private ReferenceConstantMigrationPlanner() {}

    /** 读库的部分：目标对象当前发布版、主键是否存在、全部行（主键 + 存储值）。 */
    interface Targets {
        /** 对象当前发布版；未发布、停用或读不了返回 null。 */
        DataCenter.Definition definition(String objectId);

        /** 目标对象里存在的主键（已删除的不算）。 */
        Set<String> existing(String objectId, Collection<String> ids);

        /** 目标对象的全部行；超过 cap 行返回 null。 */
        List<Stored> rows(String objectId, int cap);
    }

    record Stored(String id, Map<String, Object> values) {}

    /** 规则所在的位置：主表字段（detail 为 null）或明细字段。 */
    private record Scope(
            DataCenter.Detail detail, FieldDefinition field, DataCenter.FieldOptions options) {}

    static List<Row> plan(DataCenter.Definition object, int versionNo, Targets targets) {
        List<Row> rows = new ArrayList<>();
        for (var scope : scopes(object)) {
            var rules = scope.options().rules();
            if (rules == null) continue;
            if (rules.reference() != null && rules.reference().filter() != null) {
                var relation = BusinessFields.relation(object, scope.field().id());
                if (relation != null && !BusinessFields.multiple(relation))
                    conditions(
                            rows,
                            object,
                            versionNo,
                            scope,
                            REFERENCE_FILTER,
                            rules.reference().filter(),
                            subject(object, relation.targetObjectId(), targets),
                            targets);
            }
            if (rules.linkage() != null && rules.linkage().conditions() != null)
                conditions(
                        rows,
                        object,
                        versionNo,
                        scope,
                        LINKAGE,
                        rules.linkage().conditions(),
                        subject(object, rules.linkage().sourceObjectId(), targets),
                        targets);
        }
        return blockMixed(rows);
    }

    /** 同一对象里有不能自动转换的值时，可转换的行改记 BLOCKED，并点名是哪几处挡住了。 */
    private static List<Row> blockMixed(List<Row> rows) {
        var stuck = rows.stream().filter(r -> !CONVERT.equals(r.status())).toList();
        if (stuck.isEmpty() || stuck.size() == rows.size()) return rows;
        String note =
                "同一对象里还有不能自动转换的值（"
                        + String.join(
                                "；",
                                stuck.stream()
                                        .map(r -> r.describe() + "「" + r.before() + "」")
                                        .toList())
                        + "），对象设计保存会被拒：请先在对象设计里改掉这几处，再重新 dry-run";
        return rows.stream()
                .map(r -> CONVERT.equals(r.status()) ? r.status(BLOCKED, note) : r)
                .toList();
    }

    private static DataCenter.Definition subject(
            DataCenter.Definition object, String id, Targets targets) {
        if (id == null) return null;
        return id.equals(object.objectId()) ? object : targets.definition(id);
    }

    private static void conditions(
            List<Row> rows,
            DataCenter.Definition object,
            int versionNo,
            Scope scope,
            String rule,
            List<FieldRules.Condition> conditions,
            DataCenter.Definition subject,
            Targets targets) {
        if (subject == null) return;
        for (int i = 0; i < conditions.size(); i++) {
            var c = conditions.get(i);
            if (c == null
                    || !RuleValueSourceEnum.CONSTANT.matches(c.valueSource())
                    || FieldRuleMatrix.valueless(c.operator())
                    || FieldRules.RECORD_KEY.equals(c.fieldId())
                    || c.value() == null
                    || RelativeDates.isRelative(c.value())) continue;
            var field =
                    subject.fields().stream()
                            .filter(f -> f.id().equals(c.fieldId()))
                            .findFirst()
                            .orElse(null);
            var relation = field == null ? null : BusinessFields.relation(subject, field.id());
            if (relation == null || BusinessFields.multiple(relation)) continue;
            var options =
                    subject.fieldOptions()
                            .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            var target = subject(subject, relation.targetObjectId(), targets);
            List<?> values = c.value() instanceof List<?> items ? items : List.of(c.value());
            for (int j = 0; j < values.size(); j++) {
                Object raw = values.get(j);
                if (raw == null) continue;
                String text = String.valueOf(raw);
                boolean formatted = formatted(field, options, raw, c.operator());
                if (formatted
                        && (target == null
                                || targets.existing(target.objectId(), List.of(text))
                                        .contains(text))) continue;
                var base =
                        new Row(
                                object.objectId(),
                                object.objectName(),
                                versionNo,
                                scope.detail() == null ? null : scope.detail().id(),
                                scope.detail() == null ? null : scope.detail().name(),
                                scope.field().id(),
                                scope.field().name(),
                                rule,
                                i,
                                j,
                                field.id(),
                                field.name(),
                                relation.targetObjectId(),
                                target == null ? null : target.objectName(),
                                text,
                                null,
                                null,
                                null,
                                null);
                rows.add(match(base, subject, relation, target, targets));
            }
        }
    }

    private static Row match(
            Row base,
            DataCenter.Definition subject,
            DataCenter.Relation relation,
            DataCenter.Definition target,
            Targets targets) {
        if (target == null) return base.with(TARGET_UNAVAILABLE, null, null, "目标对象未发布或已停用");
        var stored = targets.rows(target.objectId(), SCAN_CAP);
        if (stored == null)
            return base.with(TOO_MANY_ROWS, null, null, "目标对象超过 " + SCAN_CAP + " 行，不按名称查找");
        String labelFieldId = FieldRuleLabels.labelFieldId(subject, relation);
        List<String> ids = new ArrayList<>();
        for (var row : stored)
            if (Objects.equals(
                    FieldRuleLabels.label(target, row.values(), labelFieldId), base.before()))
                ids.add(row.id());
        if (ids.size() == 1) return base.with(CONVERT, ids.getFirst(), 1, null);
        if (ids.isEmpty()) return base.with(NO_MATCH, null, 0, "没有显示名与之完全相同的记录");
        return base.with(
                AMBIGUOUS,
                null,
                ids.size(),
                "显示名相同的记录有 "
                        + ids.size()
                        + " 条："
                        + String.join("、", ids.stream().limit(10).toList()));
    }

    /** 与运行时同一个转换函数：转换不了即不是这个引用字段能存的值。 */
    private static boolean formatted(
            FieldDefinition field, DataCenter.FieldOptions options, Object raw, String operator) {
        try {
            RecordConditionValues.value(
                    field, options, raw, RecordQueryOperatorEnum.fromCode(operator));
            return true;
        } catch (ServiceException | IllegalArgumentException malformed) {
            return false;
        }
    }

    private static List<Scope> scopes(DataCenter.Definition d) {
        List<Scope> result = new ArrayList<>();
        for (var f : d.fields()) {
            var o = d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
            if (!MemberStateEnum.INACTIVE.matches(o.state())) result.add(new Scope(null, f, o));
        }
        for (var t : d.details() == null ? List.<DataCenter.Detail>of() : d.details()) {
            if (MemberStateEnum.INACTIVE.matches(t.state())) continue;
            for (var f : t.fields()) {
                var o =
                        t.fieldOptions() == null
                                ? DataCenter.FieldOptions.defaults()
                                : t.fieldOptions()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
                if (!MemberStateEnum.INACTIVE.matches(o.state())) result.add(new Scope(t, f, o));
            }
        }
        return result;
    }
}
