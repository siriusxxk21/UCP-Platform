package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.common.util.DynamicQueryProcessor;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.RecordQueryOperatorEnum;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;

import org.springframework.stereotype.Component;

import java.util.*;

/** 校验业务字段和查询权限后复用底座条件处理器；未知条件必须报错，不能被静默忽略。 */
@Component
public final class RecordConditions {
    @jakarta.annotation.Resource private RuntimeConditionSql sqlFragments;

    /** 字段「空」的形态：文本形态的列把空串也算空，多选把空列表也算空，其余只认 NULL。 */
    private enum Empty {
        NULL_ONLY,
        BLANK_TEXT,
        EMPTY_LIST
    }

    public QueryWrapper<Object> append(
            QueryWrapper<Object> where,
            DynamicConditionDTO input,
            RuntimeSchema.Table table,
            Set<String> allowed) {
        return append(where, input, table, allowed, null);
    }

    /**
     * 对象规则（引用筛选、数据联动）的条件：校验与 append 相同，空值按业务口径处理（2026-10-01）。
     *
     * <p>「不等于」对空值安全：来源字段为空的行算不等于，会被选中（底座 neq 是裸 &lt;&gt;，空值行两头都选不中）。「为空 / 不为空」把文本形态列的空串、
     * 多选的空列表也算空。视图、统计等其它查询仍走 append，口径不变。
     */
    public QueryWrapper<Object> appendRule(
            QueryWrapper<Object> where,
            DynamicConditionDTO input,
            RuntimeSchema.Table table,
            Set<String> allowed) {
        return append(where, input, table, allowed, new HashMap<>());
    }

    private QueryWrapper<Object> append(
            QueryWrapper<Object> where,
            DynamicConditionDTO input,
            RuntimeSchema.Table table,
            Set<String> allowed,
            Map<String, Empty> empties) {
        if (input == null) return where;
        if (input.getLogic() == null) throw invalid("请选择查询条件的 AND / OR 关系");
        if (where == null) {
            where = new QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
        }
        Map<String, String> mapping = new HashMap<>();
        var normalized =
                items(input.getItems(), table, allowed, mapping, empties, 0, new int[] {0});
        where.nested(w -> apply(w, normalized, input.getLogic(), mapping, empties));
        return where;
    }

    public QueryWrapper<Object> compile(
            DynamicConditionDTO input, RuntimeSchema.Table table, Set<String> queryFields) {
        if (input == null) return null;
        Map<String, String> mapping = new HashMap<>();
        var normalized = new DynamicConditionDTO();
        if (input.getLogic() == null) throw invalid("请选择查询条件的 AND / OR 关系");
        normalized.setLogic(input.getLogic());
        normalized.setItems(
                items(input.getItems(), table, queryFields, mapping, null, 0, new int[] {0}));
        var wrapper = new QueryWrapper<Object>();
        wrapper.setParamAlias("dynamicQuery");
        wrapper.nested(w -> apply(w, normalized.getItems(), normalized.getLogic(), mapping, null));
        return wrapper;
    }

    /** 多选包含扩展与对象规则的空值口径留在业务域，普通算子继续复用底座处理器。empties 非 null 表示按对象规则口径。 */
    private void apply(
            QueryWrapper<Object> where,
            List<DynamicConditionDTO.Item> items,
            DynamicConditionDTO.Logic logic,
            Map<String, String> mapping,
            Map<String, Empty> empties) {
        boolean first = true;
        for (var item : items) {
            if (!first && logic == DynamicConditionDTO.Logic.OR) where.or();
            first = false;
            if (item.isGroup())
                where.nested(
                        w ->
                                apply(
                                        w,
                                        item.getGroupItems(),
                                        item.getGroupLogic(),
                                        mapping,
                                        empties));
            else if (empties != null && "neq".equals(item.getOperator()))
                where.apply(
                        sqlFragments.distinctFrom(mapping.get(item.getField())), item.getValue());
            else if (empties != null && Set.of("isNull", "notNull").contains(item.getOperator())) {
                String column = mapping.get(item.getField());
                boolean negate = "notNull".equals(item.getOperator());
                switch (empties.get(item.getField())) {
                    case BLANK_TEXT -> where.apply(sqlFragments.blankText(column, negate));
                    case EMPTY_LIST -> where.apply(sqlFragments.emptyList(column, negate));
                    case NULL_ONLY -> {
                        if (negate) where.isNotNull(column);
                        else where.isNull(column);
                    }
                }
            } else if (Set.of("containsAny", "containsAll").contains(item.getOperator())) {
                String column = mapping.get(item.getField());
                String payload =
                        com.richuang.os.framework.common.util.json.JsonUtils.toJsonString(
                                item.getValue());
                if ("containsAll".equals(item.getOperator()))
                    where.apply(sqlFragments.containsAll(column), payload);
                else where.apply(sqlFragments.containsAny(column, false), payload);
            } else {
                var single = new DynamicConditionDTO();
                single.setLogic(DynamicConditionDTO.Logic.AND);
                single.setItems(List.of(item));
                DynamicQueryProcessor.applyConditions(where, single, mapping);
            }
        }
    }

    public static void validateReferences(DynamicConditionDTO input, DataCenter.Definition d) {
        if (input != null) validateReferenceItems(input.getItems(), d);
    }

    private static void validateReferenceItems(
            List<DynamicConditionDTO.Item> items, DataCenter.Definition d) {
        for (var item : items) {
            if (item.isGroup()) validateReferenceItems(item.getGroupItems(), d);
            else if (com.richuang.os.nocode.api.BusinessFields.relation(d, item.getField()) != null
                    && !Set.of("eq", "neq", "in").contains(item.getOperator()))
                throw invalid("对象引用仅支持等于、不等于、属于任一");
        }
    }

    private List<DynamicConditionDTO.Item> items(
            List<DynamicConditionDTO.Item> input,
            RuntimeSchema.Table table,
            Set<String> queryFields,
            Map<String, String> mapping,
            Map<String, Empty> empties,
            int depth,
            int[] count) {
        if (depth > 4 || input == null || input.isEmpty() || input.size() > 50)
            throw invalid("查询条件不能为空，最多 50 项、4 层分组");
        List<DynamicConditionDTO.Item> result = new ArrayList<>();
        for (var source : input) {
            if (source == null || ++count[0] > 50) throw invalid("查询条件最多 50 项");
            var item = new DynamicConditionDTO.Item();
            item.setType(source.getType());
            if (source.isGroup()) {
                if (source.getGroupLogic() == null) throw invalid("条件组缺少 AND / OR 关系");
                item.setGroupLogic(source.getGroupLogic());
                item.setGroupItems(
                        items(
                                source.getGroupItems(),
                                table,
                                queryFields,
                                mapping,
                                empties,
                                depth + 1,
                                count));
            } else {
                if (!"condition".equals(source.getType())) throw invalid("查询条件类型无效");
                if (!queryFields.contains(source.getField())) throw invalid("不能按无权查询的字段筛选");
                FieldDefinition field =
                        table.fields().stream()
                                .filter(f -> f.id().equals(source.getField()))
                                .findFirst()
                                .orElseThrow(() -> invalid("查询字段不存在"));
                field =
                        com.richuang.os.nocode.api.OrderedCalculations.queryField(
                                field, table.options().get(field.id()));
                var type = FieldTypeEnum.fromCode(field.type());
                var operator = RecordQueryOperatorEnum.fromCode(source.getOperator());
                boolean emptiness =
                        operator == RecordQueryOperatorEnum.IS_NULL
                                || operator == RecordQueryOperatorEnum.NOT_NULL;
                // 多选的「为空 / 不为空」只在对象规则口径下开放（按空列表判断）；其它入口的词表不变。
                if (!operator.supportsType(type)
                        && !(empties != null && emptiness && type == FieldTypeEnum.MULTI_SELECT))
                    throw invalid("字段不支持此查询运算符：" + field.name());
                String column = table.columns().get(field.id());
                if (column == null) throw invalid("查询字段未映射到业务列");
                boolean typed =
                        type.isNumeric()
                                || type == FieldTypeEnum.BOOLEAN
                                || type == FieldTypeEnum.DATE
                                || type == FieldTypeEnum.DATETIME
                                || type == FieldTypeEnum.TIME;
                mapping.put(
                        field.id(),
                        sqlFragments.column(
                                "t", column, !(typed || type == FieldTypeEnum.MULTI_SELECT)));
                if (empties != null)
                    empties.put(
                            field.id(),
                            type == FieldTypeEnum.MULTI_SELECT
                                    ? Empty.EMPTY_LIST
                                    : typed ? Empty.NULL_ONLY : Empty.BLANK_TEXT);
                var options =
                        table.options()
                                .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
                if (com.richuang.os.nocode.api.RelativeDates.isRelative(source.getValue())) {
                    // 相对日期：按执行当天换成同一字段的边界条件组原位替换（值已类型化），不占 4 层 / 50 项预算，不存换算结果。
                    result.add(
                            com.richuang.os.nocode.api.RelativeDates.expand(
                                    field,
                                    options,
                                    operator.getCode(),
                                    source.getValue(),
                                    com.richuang.os.nocode.api.RelativeDates.today()));
                    continue;
                }
                Object value;
                if (operator == RecordQueryOperatorEnum.IN
                        || operator == RecordQueryOperatorEnum.CONTAINS_ANY
                        || operator == RecordQueryOperatorEnum.CONTAINS_ALL
                        || operator == RecordQueryOperatorEnum.BETWEEN) {
                    if (!(source.getValue() instanceof List<?> list)
                            || list.isEmpty()
                            || list.size() > 100
                            || operator == RecordQueryOperatorEnum.BETWEEN && list.size() != 2)
                        throw invalid("查询范围值无效");
                    FieldDefinition queryField = field;
                    value =
                            list.stream()
                                    .map(v -> value(queryField, options, v, operator))
                                    .toList();
                } else value = value(field, options, source.getValue(), operator);
                item.setField(field.id());
                item.setOperator(operator.getCode());
                item.setValue(value);
            }
            result.add(item);
        }
        return result;
    }

    /** 运行查询复用与配置保存相同的字段值转换。 */
    public static Object value(
            FieldDefinition field,
            DataCenter.FieldOptions options,
            Object raw,
            RecordQueryOperatorEnum operator) {
        return com.richuang.os.nocode.api.RecordConditionValues.value(
                field, options, raw, operator);
    }
}
