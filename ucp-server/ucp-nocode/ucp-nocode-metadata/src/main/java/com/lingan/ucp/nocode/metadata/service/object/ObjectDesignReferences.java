package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** 发布快照前验证跨对象引用及计算依赖，不修改固定发布版本。 */
@Component
public class ObjectDesignReferences {
    @Resource private ObjectDesignReader designReader;

    /** 运行层选择目录：挑取值来源字段的公共字典项由它解析；未装配时只认局部选项。 */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<SelectionTargetValidator>
            selectionTargets;

    /** 运行层记录核对：引用字段的条件固定值必须是目标对象里的记录；未装配时只核对值的格式。 */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<ReferenceRecordLookup> recordLookups;

    void validateReferences(ObjectDraftHeadDO h) {
        Design d = designReader.load(h);
        Definition current = designReader.definition(h.getId().toString());
        // 与只读操作预检共用纯规则；APP 仍是提示，不改变原停用语义。
        ObjectFieldOperationRules.references(current, d.dependencies()).stream()
                .filter(ObjectOperationPreview.Impact::blocking)
                .findFirst()
                .ifPresent(
                        impact -> {
                            throw invalid(impact.message());
                        });
        validateExpressions(d.draft().fields(), d.fieldOptions(), d.details(), true);
        DocumentPolicies.validate(current);
        BusinessFilePolicies.validate(current);
        Calculations.validate(
                current,
                id -> id.equals(current.objectId()) ? current : designReader.published(id));
        d.details().stream()
                .filter(t -> MemberStateEnum.ACTIVE.matches(t.state()))
                .forEach(t -> validateExpressions(t.fields(), t.fieldOptions(), List.of(), false));
        var catalog = selectionTargets.getIfAvailable();
        java.util.function.BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>>
                choices = catalog == null ? FieldRuleValidator::localChoices : catalog::options;
        FieldRuleValidator.validate(
                current,
                id -> id.equals(current.objectId()) ? current : activePublished(id),
                this::unavailable,
                choices,
                recordLookups.getIfAvailable());
    }

    /** 规则来源只认已发布且启用的对象当前版本；草稿、停用或已删除对象视为不可用。 */
    private Definition activePublished(String id) {
        try {
            var h = designReader.head(id, false);
            if (!ObjectStatusEnum.ACTIVE.matches(h.getStatus())
                    || h.getCurrentPublishedVersionNo() == null) return null;
            return designReader.published(id);
        } catch (ServiceException unavailable) {
            return null;
        }
    }

    private String unavailable(String id) {
        try {
            return "「" + designReader.head(id, false).getObjectName() + "」未发布或已停用";
        } catch (ServiceException missing) {
            return "（ID " + id + "）不存在";
        }
    }

    /** 计算字段只引用同表基础字段；汇总只引用当前对象的有效内部明细。 */
    void validateExpressions(
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            List<Detail> details,
            boolean main) {
        Map<String, String> columns = new HashMap<>();
        fields.stream()
                .filter(f -> !FieldTypeEnum.fromCode(f.type()).isComputed())
                .forEach(
                        f -> {
                            DataCenter.FieldOptions o =
                                    options.getOrDefault(f.id(), FieldOptions.defaults());
                            columns.put(
                                    f.code(), o.columnName() == null ? f.code() : o.columnName());
                        });
        for (FieldDefinition f : fields) {
            DataCenter.FieldOptions o = options.getOrDefault(f.id(), FieldOptions.defaults());
            if (FieldTypeEnum.FORMULA.matches(f.type()) && o.calculation() == null)
                FieldExpressions.parse(o.expression(), columns);
            // 日期函数的类型检查：只报与日期有关的错（参数不是日期、结果是日期、落库公式用了 TODAY() 等）。
            if (FieldTypeEnum.FORMULA.matches(f.type()) && o.calculation() == null)
                FormulaDates.checkField(
                        f.name(),
                        FieldExpressions.parse(o.expression(), columns).expression(),
                        FormulaDates.generated(fields, options));
            if (!main && o.calculation() != null) throw invalid("关联计算应配置在对象主表中");
            if (FieldTypeEnum.SUMMARY.matches(f.type())) {
                if (!main) throw invalid("汇总字段应在主表中定义");
                java.util.regex.Matcher match =
                        Pattern.compile(
                                        "(count|sum|avg|min|max)\\(([a-z][a-z0-9_]*)(?:\\.([a-z][a-z0-9_]*))?\\)")
                                .matcher(Objects.toString(o.expression(), ""));
                if (!match.matches()) throw invalid("汇总语法为 count(items) 或 sum(items.amount)，不接受脚本");
                DataCenter.Detail detail =
                        details.stream()
                                .filter(
                                        t ->
                                                t.code().equals(match.group(2))
                                                        && MemberStateEnum.ACTIVE.matches(
                                                                t.state()))
                                .findFirst()
                                .orElseThrow(() -> invalid("汇总引用的明细不存在或已停用"));
                if ("count".equals(match.group(1))) {
                    if (match.group(3) != null || !FieldTypeEnum.INTEGER.matches(o.resultType()))
                        throw invalid("count 汇总不指定字段，结果类型为整数");
                } else {
                    FieldDefinition target =
                            detail.fields().stream()
                                    .filter(v -> v.code().equals(match.group(3)))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("汇总字段不存在"));
                    DataCenter.FieldOptions targetOption =
                            detail.fieldOptions()
                                    .getOrDefault(target.id(), FieldOptions.defaults());
                    boolean numericSource =
                            FieldTypeEnum.fromCode(target.type()).isNumeric()
                                    || FieldTypeEnum.FORMULA.matches(target.type())
                                            && (FieldTypeEnum.INTEGER.matches(
                                                            targetOption.resultType())
                                                    || FieldTypeEnum.DECIMAL.matches(
                                                            targetOption.resultType())
                                                    || FieldTypeEnum.MONEY.matches(
                                                            targetOption.resultType()));
                    if (!numericSource
                            || MemberStateEnum.INACTIVE.matches(targetOption.state())
                            || !(FieldTypeEnum.DECIMAL.matches(o.resultType())
                                    || FieldTypeEnum.MONEY.matches(o.resultType())))
                        throw invalid("数值汇总应引用启用的数值字段或数值公式，结果类型为小数或金额");
                }
                if (Boolean.TRUE.equals(f.required())
                        || Boolean.TRUE.equals(f.unique())
                        || o.defaultValue() != null) throw invalid("汇总为查询计算字段，不能配置存储约束或默认值");
            }
        }
    }
}
