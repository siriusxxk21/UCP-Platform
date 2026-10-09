package com.richuang.os.nocode.metadata.service.formula;

import com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额目标公式默认值的取整约束：金额结果统一由规则上的取整方式取整，公式里不能再写 round()。 在解析后的语法树上检查函数名，不做子串匹配， 字段编码或文本常量中出现 round
 * 不受影响。FORMULA 计算字段没有取整配置位，不经过此检查。
 */
public final class MoneyRounding {
    private MoneyRounding() {}

    /** 取整前先归到的小数位数：吸收除法中间结果的尾差，又远小于 1 円。 */
    public static final int SETTLE_SCALE = 10;

    /**
     * 金额取成整数。公式求值的除法只保留 16 位中间小数，1000/3*3 得到 999.9999999999999999， 直接「向下取整」「去掉小数」会少 1 円；所以先四舍五入到 10
     * 位小数吸收这类尾差，再按取整方式取整。联动与公式默认值、 预览与保存都走这一处。
     */
    public static BigDecimal settle(BigDecimal value, RoundingMode mode) {
        return value.setScale(SETTLE_SCALE, RoundingMode.HALF_UP).setScale(0, mode);
    }

    public static boolean usesRound(Expression expression) {
        if (expression instanceof FunctionValue v) {
            if ("round".equalsIgnoreCase(v.name())) return true;
            for (var argument : v.arguments()) if (usesRound(argument)) return true;
            return false;
        }
        if (expression instanceof BinaryValue v) return usesRound(v.left()) || usesRound(v.right());
        return false;
    }
}
