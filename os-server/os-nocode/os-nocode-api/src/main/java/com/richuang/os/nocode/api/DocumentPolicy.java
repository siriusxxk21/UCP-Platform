package com.richuang.os.nocode.api;

import java.util.List;

/** 随对象结构发布的整单完整性规则。字段与明细引用均使用稳定 ID。 */
public record DocumentPolicy(
        List<Rule> rules,
        Lifecycle lifecycle,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                BusinessHandling.Policy handling) {
    public DocumentPolicy(List<Rule> rules, Lifecycle lifecycle) {
        this(rules, lifecycle, null);
    }

    public DocumentPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record Rule(
            String id,
            String name,
            String scope,
            String detailId,
            Expression when,
            Expression assertion,
            String fieldId,
            String message) {}

    /** 受控表达式只描述数据，不携带可执行代码。 */
    public record Expression(
            String op, String fieldId, String detailId, Object value, List<Expression> args) {
        public Expression {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    public record Lifecycle(
            String fieldId, String initialState, List<State> states, List<Action> actions) {
        public Lifecycle {
            states = states == null ? List.of() : List.copyOf(states);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record State(
            String code,
            String name,
            List<String> lockedFields,
            List<String> lockedDetails,
            boolean allowDelete) {
        public State {
            lockedFields = lockedFields == null ? List.of() : List.copyOf(lockedFields);
            lockedDetails = lockedDetails == null ? List.of() : List.copyOf(lockedDetails);
        }
    }

    public record Action(
            String code, String name, List<String> fromStates, String toState, String permission) {
        public Action {
            fromStates = fromStates == null ? List.of() : List.copyOf(fromStates);
        }
    }

    /** 行号不是身份；新增明细用客户端稳定键定位，已有明细仍保留记录 ID。 */
    public record Problem(
            String ruleId,
            String scope,
            String detailId,
            String clientRowKey,
            String recordId,
            String fieldId,
            String message) {}
}
