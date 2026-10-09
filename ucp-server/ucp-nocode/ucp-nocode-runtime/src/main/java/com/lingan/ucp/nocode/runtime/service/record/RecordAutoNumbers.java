package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.BusinessCounterMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** 实际新增时生成对象编号；与主从写入共用事务，预演不占号，失败回滚。 */
@Component
public class RecordAutoNumbers {
    @Resource private DataObjectApi objects;
    @Resource private BusinessCounterMapper counters;

    public void generate(
            String objectId, RuntimeSchema.Table table, Map<String, Object> payload, long actor) {
        // 所有入口使用对象当前发布规则；旧应用快照也为后来新增的系统字段生成值。
        var current = objects.getPublished(objectId);
        // 周期变化会切换计数器行，额外按稳定字段锁保护跨周期查重。
        // 主表和全部明细以统一顺序获取锁，避免不同明细提交顺序产生锁环。
        var numberedFields = new TreeSet<String>();
        current.fieldOptions()
                .forEach(
                        (id, o) -> {
                            if (o.autoNumber() != null) numberedFields.add(id);
                        });
        current.details()
                .forEach(
                        d ->
                                d.fieldOptions()
                                        .forEach(
                                                (id, o) -> {
                                                    if (o.autoNumber() != null)
                                                        numberedFields.add(id);
                                                }));
        numberedFields.forEach(
                id -> counters.lockField("nocode-auto-number:" + objectId + ":" + id));
        List<FieldDefinition> fields;
        Map<String, DataCenter.FieldOptions> options;
        if (current.tableName().equals(table.name())
                && ObjectTables.main(current).schemaName().equals(table.schema())) {
            fields = current.fields();
            options = current.fieldOptions();
        } else {
            var detail =
                    current.details().stream()
                            .filter(
                                    d ->
                                            d.tableName().equals(table.name())
                                                    && ObjectTables.detail(current, d)
                                                            .schemaName()
                                                            .equals(table.schema()))
                            .findFirst()
                            .orElseThrow(() -> invalid("自动编号明细不属于当前对象"));
            fields = detail.fields();
            options = detail.fieldOptions();
        }
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (var field : fields) {
            var option = options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (!FieldTypeEnum.AUTO_NUMBER.matches(field.type())
                    || option.autoNumber() == null
                    || MemberStateEnum.INACTIVE.matches(option.state())) continue;
            var rule = option.autoNumber();
            AutoNumberOptions.validate(field, rule);
            String column = option.columnName() == null ? field.code() : option.columnName();
            String quotedTable = PostgreSqlCommands.table(table.schema(), table.name());
            String quotedColumn = PostgreSqlCommands.identifier(column);
            // 同对象字段计数器行锁串行化并发；规则改动遇历史同号时跳过，历史数据不重编。
            String value = null;
            for (int attempts = 0; attempts < 10000; attempts++) {
                long next =
                        counters.nextConfigured(
                                Long.parseLong(objectId),
                                Long.parseLong(field.id()),
                                rule.periodKey(date),
                                rule.startValue(),
                                Long.toString(actor));
                String candidate = rule.format(date, next);
                if (!counters.numberExists(quotedTable, quotedColumn, candidate)) {
                    value = candidate;
                    break;
                }
            }
            if (value == null) throw invalid("自动编号与大量历史编号冲突，请调整前缀或起始值");
            payload.put(column, value);
        }
    }
}
