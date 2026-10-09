package com.richuang.os.nocode.runtime.service.application;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.mapper.BusinessCounterMapper;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 编号与业务主从写入共用事务，失败回滚计数；不替换数据中心字段约束。 */
@Service
public class ApplicationBusinessRules {
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resources;
    @Resource private BusinessCounterMapper counters;

    private List<ApplicationBusiness.NumberRule> numbers(String app, String object) {
        if (com.richuang.os.nocode.runtime.service.maintenance.ObjectMaintenanceScope.active(app))
            return List.of();
        return applications.published(app).definition().resources().stream()
                .filter(r -> ApplicationResourceKindEnum.NUMBER_RULE.matches(r.kind()))
                .map(r -> resources.decode(r.config(), ApplicationBusiness.NumberRule.class))
                .filter(n -> n.objectId().equals(object))
                .toList();
    }

    public Map<String, Object> prepare(
            String app,
            DataCenter.Definition d,
            Map<String, Object> input,
            boolean creating,
            long actor,
            Set<String> writableFields) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        for (ApplicationBusiness.NumberRule rule : numbers(app, d.objectId())) {
            if (input.containsKey(rule.fieldId())) throw invalid("业务编号由系统生成，不能手动修改");
            if (!creating) continue;
            // 应用配置产生的值也是业务写入，不能借“自动生成”绕过共享上限和成员字段授权。
            if (!writableFields.contains(rule.fieldId())) throw invalid("没有自动编号目标字段的写入权限");
            NumberPeriodEnum period = NumberPeriodEnum.fromCode(rule.period());
            String date =
                    switch (period) {
                        case NONE -> "";
                        case YEAR -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
                        case MONTH -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
                        case DAY -> LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
                    };
            long next =
                    counters.next(
                            Long.parseLong(d.objectId()),
                            Long.parseLong(rule.fieldId()),
                            period.getCode() + ":" + date,
                            Long.toString(actor));
            result.put(
                    rule.fieldId(),
                    rule.prefix()
                            + date
                            + String.format(Locale.ROOT, "%0" + rule.width() + "d", next));
        }
        if (!writableFields.containsAll(result.keySet())) throw invalid("包含无权修改的业务字段");
        return result;
    }

    /** 运行表单提示自动生成；不回写全局字段元数据或已发布对象快照。 */
    public DataCenter.Definition decorate(
            String app, DataCenter.Definition d, RuntimeSchema.Table physical) {
        LinkedHashMap<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<>(d.fieldOptions());
        // 兼容早期纳管版本丢失的布尔默认值；仅装饰已授权字段，不改写发布快照。
        if (ObjectSourceEnum.ADOPTED.matches(physical.binding().source()))
            for (FieldDefinition field : d.fields()) {
                DataCenter.FieldOptions option =
                        options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
                if (option.defaultValue() != null) continue;
                physical.physical().columns().stream()
                        .filter(c -> c.name().equals(physical.column(field)))
                        .findFirst()
                        .ifPresent(
                                column -> {
                                    String value =
                                            NativeDefaults.booleanConstant(
                                                    column.nativeType(),
                                                    column.defaultExpression());
                                    if (value != null)
                                        options.put(field.id(), option.withDefaultValue(value));
                                });
            }
        // 设计元数据的 generated 也表示平台创建的引用列；运行态只看该列的业务值是否由数据库生成。
        for (FieldDefinition field : d.fields()) {
            boolean systemManaged = BaseDOColumns.NAMES.contains(physical.column(field));
            if (!systemManaged
                    && d.relations().stream()
                            .noneMatch(r -> Objects.equals(r.fieldId(), field.id()))) continue;
            DataCenter.FieldOptions o =
                    options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadata.Column column =
                    physical.physical().columns().stream()
                            .filter(c -> c.name().equals(physical.column(field)))
                            .findFirst()
                            .orElseThrow();
            boolean generated =
                    systemManaged
                            || column.identityKind() != null && !column.identityKind().isBlank()
                            || column.generatedKind() != null && !column.generatedKind().isBlank();
            options.put(field.id(), DataCenter.FieldOptions.copyOf(o).generated(generated).build());
        }

        for (ApplicationBusiness.NumberRule rule : numbers(app, d.objectId())) {
            if (d.fields().stream().noneMatch(f -> f.id().equals(rule.fieldId()))) continue;
            var o = options.getOrDefault(rule.fieldId(), DataCenter.FieldOptions.defaults());
            options.put(rule.fieldId(), DataCenter.FieldOptions.copyOf(o).generated(true).build());
        }
        return new DataCenter.Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                d.readOnly(),
                d.titleFieldId(),
                d.settings(),
                d.fields(),
                options,
                d.relations(),
                d.indexes(),
                d.details(),
                d.mainBinding());
    }
}
