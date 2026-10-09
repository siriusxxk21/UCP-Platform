package com.lingan.ucp.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;

/**
 * 动态查询条件 —— 递归树型结构（ConditionGroup）
 *
 * <p>前端 LaDynamicSearch 组件输出的嵌套条件结构，支持 (A AND B) OR (C AND D) 等任意嵌套逻辑。
 * 后端 DynamicQueryProcessor 将其递归翻译为 MyBatis-Plus QueryWrapper。</p>
 *
 * <p>JSON 示例：
 * <pre>
 * {
 *   "logic": "OR",
 *   "items": [
 *     {
 *       "logic": "AND",
 *       "items": [
 *         { "field": "username", "operator": "like", "value": "admin" },
 *         { "field": "status",   "operator": "eq",   "value": 1 }
 *       ]
 *     },
 *     {
 *       "logic": "AND",
 *       "items": [
 *         { "field": "nickname", "operator": "like", "value": "测试" }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 * 等价 SQL：WHERE (username LIKE '%admin%' AND status = 1) OR (nickname LIKE '%测试%')
 */
@Data
public class DynamicConditionDTO {

    /**
     * 条件逻辑：AND / OR
     */
    private Logic logic;

    /**
     * 子项列表：可以是条件项（ConditionItem），也可以是嵌套的条件组（ConditionGroup）
     */
    private List<Item> items;

    /**
     * 逻辑枚举
     */
    public enum Logic {
        AND, OR
    }

    /**
     * 条件项 / 条件组的联合类型
     * <p>通过 type 字段区分：
     * <ul>
     *   <li>type = "condition" → 条件项（field + operator + value）</li>
     *   <li>type = "group"     → 嵌套条件组（logic + items），递归结构</li>
     * </ul>
     */
    @Data
    public static class Item {

        /**
         * 类型标识：condition / group
         */
        private String type;

        // ===== condition 类型字段 =====

        /**
         * 字段标识（如 username / phone / status）
         */
        private String field;

        /**
         * 运算符（eq / neq / like / notLike / startWith / endWith / gt / gte / lt / lte / in / between）
         */
        private String operator;

        /**
         * 条件值（between 时为 [start, end] 数组）
         */
        private Object value;

        // ===== group 类型字段 =====

        /**
         * 嵌套条件组的逻辑（仅 type=group 时有效）
         */
        private Logic groupLogic;

        /**
         * 嵌套条件组的子项列表（仅 type=group 时有效）
         */
        private List<Item> groupItems;

        /**
         * 判断是否为条件组类型
         */
        @JsonIgnore
        public boolean isGroup() {
            return "group".equals(type);
        }
    }
}
