package com.lingan.ucp.common.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.common.dto.DynamicConditionDTO.Item;
import com.lingan.ucp.common.dto.DynamicConditionDTO.Logic;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 动态查询条件处理器
 *
 * <p>将前端传来的 {@link DynamicConditionDTO}（递归树型结构）
 * 递归翻译为 MyBatis-Plus 的 {@link QueryWrapper} 条件。</p>
 *
 * <p>支持 12 种运算符：eq / neq / like / notLike / startWith / endWith / gt / gte / lt / lte / in / between</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 1. 字段映射：驼峰 → 数据库列名
 * Map&lt;String, String&gt; fieldMapping = Map.of(
 *     "username", "username",
 *     "phone", "phone",
 *     "status", "status",
 *     "createTime", "create_time"
 * );
 *
 * // 2. 构建条件
 * QueryWrapper&lt;SysUser&gt; wrapper = new QueryWrapper&lt;&gt;();
 * DynamicQueryProcessor.applyConditions(wrapper, conditions, fieldMapping);
 * </pre>
 */
public class DynamicQueryProcessor {

    /**
     * 将动态条件应用到 QueryWrapper
     *
     * @param wrapper      MyBatis-Plus QueryWrapper
     * @param conditions   前端传入的条件树
     * @param fieldMapping 字段映射（前端 field → 数据库列名），不在映射中的字段会被忽略（防注入）
     */
    public static <T> void applyConditions(
            QueryWrapper<T> wrapper,
            DynamicConditionDTO conditions,
            Map<String, String> fieldMapping
    ) {
        if (conditions == null || conditions.getItems() == null || conditions.getItems().isEmpty()) {
            return;
        }
        applyGroup(wrapper, conditions, fieldMapping, true);
    }

    /**
     * 将动态条件应用到 LambdaQueryWrapper
     *
     * @param wrapper      MyBatis-Plus LambdaQueryWrapper
     * @param conditions   前端传入的条件树
     * @param columnMapper 字段 → Lambda 列引用映射，不在映射中的字段会被忽略
     */
    public static <T> void applyConditions(
            LambdaQueryWrapper<T> wrapper,
            DynamicConditionDTO conditions,
            Map<String, Function<T, ?>> columnMapper
    ) {
        // LambdaQueryWrapper 暂不在此版本实现，后续扩展
        throw new UnsupportedOperationException("LambdaQueryWrapper 支持待后续实现");
    }

    // ===== 内部递归处理 =====

    /**
     * 递归处理条件组
     *
     * @param isFirst 是否为最外层（最外层不加 nested）
     */
    private static <T> void applyGroup(
            QueryWrapper<T> wrapper,
            DynamicConditionDTO group,
            Map<String, String> fieldMapping,
            boolean isFirst
    ) {
        if (group == null || group.getItems() == null || group.getItems().isEmpty()) {
            return;
        }

        List<Item> items = group.getItems();
        Logic logic = group.getLogic() != null ? group.getLogic() : Logic.AND;

        if (isFirst) {
            // 最外层直接追加条件
            for (int index = 0; index < items.size(); index++) {
                applyItem(wrapper, items.get(index), fieldMapping, logic, index > 0);
            }
        } else {
            // 嵌套层用 nested 包裹
            wrapper.nested(w -> {
                for (int index = 0; index < items.size(); index++) {
                    applyItem(w, items.get(index), fieldMapping, logic, index > 0);
                }
            });
        }
    }

    /**
     * 处理单个 item（可能是条件项或嵌套组）
     */
    private static <T> void applyItem(
            QueryWrapper<T> wrapper,
            Item item,
            Map<String, String> fieldMapping,
            Logic logic,
            boolean hasPreviousItem
    ) {
        if (item == null) return;

        if (item.isGroup()) {
            // 嵌套条件组 → 递归处理
            DynamicConditionDTO nestedGroup = new DynamicConditionDTO();
            nestedGroup.setLogic(item.getGroupLogic());
            nestedGroup.setItems(item.getGroupItems());
            if (logic == Logic.OR && hasPreviousItem) {
                wrapper.or();
            }
            applyGroup(wrapper, nestedGroup, fieldMapping, false);
        } else {
            // 单个条件项
            applyCondition(wrapper, item, fieldMapping, logic == Logic.OR && hasPreviousItem);
        }
    }

    /**
     * 应用单个条件
     */
    private static <T> void applyCondition(
            QueryWrapper<T> wrapper,
            Item item,
            Map<String, String> fieldMapping,
            boolean isOr
    ) {
        String column = fieldMapping.get(item.getField());
        // 不在映射中的字段直接忽略（安全防护）
        if (!StringUtils.hasText(column)) return;

        String operator = item.getOperator();
        Object value = item.getValue();

        // 值校验：大部分运算符需要非空值
        if (needsValue(operator) && !hasValue(value)) return;

        switch (operator) {
            case "isNull":
                if (isOr) wrapper.or().isNull(column);
                else wrapper.isNull(column);
                break;
            case "notNull":
                if (isOr) wrapper.or().isNotNull(column);
                else wrapper.isNotNull(column);
                break;
            case "eq":
                if (isOr) wrapper.or().eq(column, value);
                else wrapper.eq(column, value);
                break;
            case "neq":
                if (isOr) wrapper.or().ne(column, value);
                else wrapper.ne(column, value);
                break;
            case "like":
                if (isOr) wrapper.or().like(column, value);
                else wrapper.like(column, value);
                break;
            case "notLike":
                if (isOr) wrapper.or().notLike(column, value);
                else wrapper.notLike(column, value);
                break;
            case "startWith":
                if (isOr) wrapper.or().likeRight(column, value);
                else wrapper.likeRight(column, value);
                break;
            case "endWith":
                if (isOr) wrapper.or().likeLeft(column, value);
                else wrapper.likeLeft(column, value);
                break;
            case "gt":
                if (isOr) wrapper.or().gt(column, value);
                else wrapper.gt(column, value);
                break;
            case "gte":
                if (isOr) wrapper.or().ge(column, value);
                else wrapper.ge(column, value);
                break;
            case "lt":
                if (isOr) wrapper.or().lt(column, value);
                else wrapper.lt(column, value);
                break;
            case "lte":
                if (isOr) wrapper.or().le(column, value);
                else wrapper.le(column, value);
                break;
            case "in":
                applyInCondition(wrapper, column, value, isOr);
                break;
            case "between":
                applyBetweenCondition(wrapper, column, value, isOr);
                break;
            default:
                // 未知运算符忽略
                break;
        }
    }

    /**
     * 处理 in 运算符（value 可以是数组或逗号分隔字符串）
     */
    @SuppressWarnings("unchecked")
    private static <T> void applyInCondition(
            QueryWrapper<T> wrapper,
            String column,
            Object value,
            boolean isOr
    ) {
        Collection<?> values;
        if (value instanceof Collection) {
            values = (Collection<?>) value;
        } else if (value instanceof Object[]) {
            values = List.of((Object[]) value);
        } else if (value instanceof String) {
            values = List.of(((String) value).split(","));
        } else {
            values = List.of(value);
        }
        if (values.isEmpty()) return;
        if (isOr) wrapper.or().in(column, values);
        else wrapper.in(column, values);
    }

    /**
     * 处理 between 运算符（value 应为 [start, end] 数组）
     */
    private static <T> void applyBetweenCondition(
            QueryWrapper<T> wrapper,
            String column,
            Object value,
            boolean isOr
    ) {
        Object start;
        Object end;
        if (value instanceof List<?> list && list.size() >= 2) {
            start = list.get(0);
            end = list.get(1);
        } else if (value instanceof Object[] arr && arr.length >= 2) {
            start = arr[0];
            end = arr[1];
        } else {
            return;
        }
        if (isOr) wrapper.or().between(column, start, end);
        else wrapper.between(column, start, end);
    }

    // ===== 工具方法 =====

    private static boolean needsValue(String operator) {
        return !"isNull".equals(operator) && !"notNull".equals(operator);
    }

    private static boolean hasValue(Object value) {
        if (value == null) return false;
        if (value instanceof String s) return StringUtils.hasText(s);
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Object[] a) return a.length > 0;
        return true;
    }
}
