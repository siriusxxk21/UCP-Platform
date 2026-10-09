package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.*;
import java.util.regex.Pattern;

/** 将数据库最终约束转换为字段提示；不返回原始 SQL、约束细节或冲突记录的值。 */
final class RecordConstraintErrors {
    private RecordConstraintErrors() {}

    static ServiceException translate(
            DataIntegrityViolationException error, RuntimeSchema.Table table) {
        PSQLException pg = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof PSQLException found) {
                pg = found;
                break;
            }
        if (pg == null || pg.getServerErrorMessage() == null)
            return invalid("记录违反必填、唯一或引用约束，请检查输入");
        var detail = pg.getServerErrorMessage();
        String constraint = detail.getConstraint();
        String definition =
                table.physical().constraints().stream()
                        .filter(c -> Objects.equals(c.name(), constraint))
                        .map(c -> c.definition())
                        .findFirst()
                        .orElseGet(
                                () ->
                                        table.physical().indexes().stream()
                                                .filter(i -> Objects.equals(i.name(), constraint))
                                                .map(i -> i.definition())
                                                .findFirst()
                                                .orElse(""));
        var fields =
                table.fields().stream()
                        .filter(
                                f -> {
                                    String column = table.column(f);
                                    return Objects.equals(column, detail.getColumn())
                                            || Objects.equals(constraint, "nocode_u_" + f.id())
                                            || constraint != null
                                                    && constraint.startsWith(
                                                            "nocode_c_" + f.id() + "_")
                                            || Pattern.compile(
                                                            "(?<![A-Za-z0-9_])"
                                                                    + Pattern.quote(column)
                                                                    + "(?![A-Za-z0-9_])")
                                                    .matcher(definition)
                                                    .find();
                                })
                        .map(f -> f.name())
                        .distinct()
                        .toList();
        String label = fields.isEmpty() ? "相关字段" : String.join("、", fields);
        String message =
                switch (Objects.toString(pg.getSQLState(), "")) {
                    case "23505" -> "字段值重复，违反唯一规则：" + label;
                    case "23502" -> "必填字段不能为空：" + label;
                    case "23503" -> "关联记录不存在或仍被引用：" + label;
                    case "23514" -> "字段值不符合校验规则：" + label;
                    default -> "记录违反数据约束：" + label;
                };
        return invalid(message);
    }
}
