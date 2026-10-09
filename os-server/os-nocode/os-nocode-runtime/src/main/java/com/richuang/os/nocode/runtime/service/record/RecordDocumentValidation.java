package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.runtime.dal.mapper.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 整单规则输入、数值归一化与错误脱敏；读取完整明细后按原顺序校验。 */
@Component
public class RecordDocumentValidation {
    @Resource private RecordPersistence persistence;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordMapper records;
    @Resource private com.richuang.os.nocode.runtime.service.record.DetailPositions detailPositions;

    /** 根记录已锁定，按当前完整性版本加载全部内部明细；未提交组也参与规则。 */
    DocumentPolicies.Input documentInput(
            DataCenter.Definition d, String id, long actor, Map<String, Map<String, String>> keys) {
        Map<String, List<DocumentPolicies.InputRow>> groups = new LinkedHashMap<>();
        for (var detail : d.details()) {
            if (!MemberStateEnum.ACTIVE.matches(detail.state())) continue;
            var rows =
                    id == null
                            ? List.<Row>of()
                            : records
                                    .rows(
                                            schemas.detail(d, detail)
                                                    .statement(
                                                            null, id, Long.toString(actor), false))
                                    .stream()
                                    .map(persistence::row)
                                    .toList();
            if (rows.size() > 500) throw invalid("整单明细超过 500 行，不能进行部分校验");
            groups.put(
                    detail.id(),
                    detailPositions.order(d.objectId(), detail.id(), id, rows, Row::id).stream()
                            .map(
                                    row ->
                                            new DocumentPolicies.InputRow(
                                                    row.id(),
                                                    keys.getOrDefault(detail.id(), Map.of())
                                                            .getOrDefault(
                                                                    row.id(), "row-" + row.id()),
                                                    row.values()))
                            .toList());
        }
        return typedDocument(
                d,
                new DocumentPolicies.Input(
                        id == null
                                ? Map.of()
                                : persistence
                                        .read(schemas.main(d), id, null, actor, false)
                                        .values(),
                        groups));
    }

    static DocumentPolicies.Input typedDocument(
            DataCenter.Definition d, DocumentPolicies.Input input) {
        Map<String, List<DocumentPolicies.InputRow>> groups = new LinkedHashMap<>();
        d.details()
                .forEach(
                        detail ->
                                groups.put(
                                        detail.id(),
                                        input
                                                .details()
                                                .getOrDefault(detail.id(), List.of())
                                                .stream()
                                                .map(
                                                        row ->
                                                                new DocumentPolicies.InputRow(
                                                                        row.id(),
                                                                        row.clientRowKey(),
                                                                        typedValues(
                                                                                detail.fields(),
                                                                                detail
                                                                                        .fieldOptions(),
                                                                                row.values())))
                                                .toList()));
        return new DocumentPolicies.Input(
                typedValues(d.fields(), d.fieldOptions(), input.values()), groups);
    }

    static Map<String, Object> typedValues(
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            Map<String, Object> input) {
        var result = new LinkedHashMap<>(input);
        for (var field : fields) {
            var type = FieldTypeEnum.fromCode(field.type());
            var option = options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (type.isComputed() && option.resultType() != null)
                type = FieldTypeEnum.fromCode(option.resultType());
            if (type.isNumeric() && result.get(field.id()) != null)
                result.put(
                        field.id(),
                        com.richuang.os.nocode.metadata.service.formula.FormulaEvaluator.number(
                                result.get(field.id())));
        }
        return result;
    }

    static void requireDocument(
            DataCenter.Definition d,
            DocumentPolicies.Input before,
            DocumentPolicies.Input candidate,
            ApplicationAuthorization.Capabilities caps) {
        var after = typedDocument(d, candidate);
        try {
            DocumentStates.requireWrite(d, before, after);
            var problems = DocumentPolicies.evaluate(DocumentPolicies.policy(d), after);
            if (!problems.isEmpty()) throw DocumentValidation.error(problems);
        } catch (ServiceException e) {
            if (e.getDetails() instanceof Map<?, ?> data
                    && data.get("problems") instanceof List<?> problems) {
                List<DocumentPolicy.Problem> visible = new ArrayList<>();
                for (Object item : problems) {
                    var p = (DocumentPolicy.Problem) item;
                    var rule =
                            DocumentPolicies.policy(d).rules().stream()
                                    .filter(r -> Objects.equals(r.id(), p.ruleId()))
                                    .findFirst()
                                    .orElse(null);
                    boolean readable =
                            (p.detailId() == null || caps.readDetails().contains(p.detailId()))
                                    && (p.fieldId() == null
                                            || p.detailId() != null
                                            || caps.readFields().contains(p.fieldId()))
                                    && (rule == null
                                            || readableExpression(rule.when(), d, caps)
                                                    && readableExpression(
                                                            rule.assertion(), d, caps));
                    visible.add(
                            readable
                                    ? p
                                    : new DocumentPolicy.Problem(
                                            null,
                                            DocumentRuleScopeEnum.DOCUMENT.getCode(),
                                            null,
                                            null,
                                            null,
                                            null,
                                            "整单校验未通过，请联系有权查看完整单据的人员处理"));
                }
                throw e.setDetails(Map.of("problems", visible.stream().distinct().toList()))
                        .setMessage(visible.getFirst().message());
            }
            throw e;
        }
    }

    static boolean readableExpression(
            DocumentPolicy.Expression e,
            DataCenter.Definition d,
            ApplicationAuthorization.Capabilities caps) {
        if (e == null) return true;
        if (e.detailId() != null && !caps.readDetails().contains(e.detailId())) return false;
        if (e.fieldId() != null
                && !caps.readFields().contains(e.fieldId())
                && d.details().stream()
                        .noneMatch(
                                t ->
                                        caps.readDetails().contains(t.id())
                                                && t.fields().stream()
                                                        .anyMatch(f -> f.id().equals(e.fieldId()))))
            return false;
        return e.args().stream().allMatch(a -> readableExpression(a, d, caps));
    }
}
