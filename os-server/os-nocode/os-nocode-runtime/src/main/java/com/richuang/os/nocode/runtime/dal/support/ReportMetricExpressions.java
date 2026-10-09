package com.richuang.os.nocode.runtime.dal.support;

import com.richuang.os.nocode.enums.ReportFormulaEnum;
import com.richuang.os.nocode.enums.ReportOperationEnum;
import com.richuang.os.nocode.runtime.dal.query.*;

import java.util.*;
import java.util.stream.IntStream;

/** 展开报表指标的依赖树；只描述访问顺序，聚合函数、类型转换及除零处理由 XML 渲染。 */
public final class ReportMetricExpressions {
    private ReportMetricExpressions() {}

    public enum Stage {
        AGGREGATE,
        FORMULA_START,
        FORMULA_OPERATOR,
        FORMULA_END,
        // 以下四个只在多个数据来源时出现：PRESENCE_* 包住一个基础指标的聚合（该来源在这一组没有行 ⇒ 空），
        // ZERO_* 包住加、减的一个操作数（空当 0）。单来源的展开里不出现，渲染与原先逐字相同。
        PRESENCE_START,
        PRESENCE_END,
        ZERO_START,
        ZERO_END
    }

    /**
     * index/operation 用于叶子聚合；formula 用于复合表达式的中序和结束位置；source 仅 PRESENCE_START 用：指标所属来源的下标（0 = 来源 1）。
     */
    public record Token(
            Stage stage,
            Integer index,
            ReportOperationEnum operation,
            ReportFormulaEnum formula,
            Integer source) {
        public Token(
                Stage stage,
                Integer index,
                ReportOperationEnum operation,
                ReportFormulaEnum formula) {
            this(stage, index, operation, formula, null);
        }
    }

    /** 与原编译器一致，逐指标展开，遇到当前递归路径中的重复引用即拒绝循环。 */
    public static List<List<Token>> expand(ReportStatement statement) {
        var expressions = new ArrayList<List<Token>>();
        for (int i = 0; i < statement.metrics().size(); i++) {
            var tokens = new ArrayList<Token>();
            visit(statement, i, new HashSet<>(), tokens);
            expressions.add(tokens);
        }
        return expressions;
    }

    private static void visit(
            ReportStatement statement, int index, Set<Integer> path, List<Token> tokens) {
        if (!path.add(index)) throw new IllegalStateException("Cyclic report metric");
        var metric = statement.metrics().get(index);
        var operation = ReportOperationEnum.fromCode(metric.operation());
        if (operation == ReportOperationEnum.FORMULA) {
            var formula = metric.formula();
            int left = indexOf(statement, formula.left()),
                    right = indexOf(statement, formula.right());
            var operator = ReportFormulaEnum.fromCode(formula.operator());
            boolean zero =
                    statement.sources() != null
                            && (operator == ReportFormulaEnum.ADD
                                    || operator == ReportFormulaEnum.SUBTRACT);
            tokens.add(new Token(Stage.FORMULA_START, null, null, null));
            operand(statement, left, path, tokens, zero);
            tokens.add(new Token(Stage.FORMULA_OPERATOR, null, null, operator));
            operand(statement, right, path, tokens, zero);
            tokens.add(new Token(Stage.FORMULA_END, null, null, operator));
        } else if (statement.sources() != null) {
            tokens.add(
                    new Token(Stage.PRESENCE_START, index, null, null, source(statement, index)));
            tokens.add(new Token(Stage.AGGREGATE, index, operation, null));
            tokens.add(new Token(Stage.PRESENCE_END, index, null, null));
        } else {
            tokens.add(new Token(Stage.AGGREGATE, index, operation, null));
        }
        path.remove(index);
    }

    /** 多来源的加、减：每个操作数各自外包「空当 0」；其余情形与原先相同。 */
    private static void operand(
            ReportStatement statement,
            int index,
            Set<Integer> path,
            List<Token> tokens,
            boolean zero) {
        if (zero) tokens.add(new Token(Stage.ZERO_START, null, null, null));
        visit(statement, index, path, tokens);
        if (zero) tokens.add(new Token(Stage.ZERO_END, null, null, null));
    }

    /** 基础指标所属来源的下标：恰有一个来源分支在这个位置上是本来源的指标。 */
    private static int source(ReportStatement statement, int index) {
        for (int s = 0; s < statement.sources().size(); s++) {
            ReportStatement.Slot slot = statement.sources().get(s).slots().get(index);
            if (slot != null && slot.local() != null) return s;
        }
        throw new IllegalStateException("Report metric without source");
    }

    private static int indexOf(ReportStatement statement, String id) {
        return IntStream.range(0, statement.metrics().size())
                .filter(index -> statement.metrics().get(index).id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
