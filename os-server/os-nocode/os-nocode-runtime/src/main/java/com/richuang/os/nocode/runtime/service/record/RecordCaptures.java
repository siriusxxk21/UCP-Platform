package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationCaptureRules;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 在现有动作事务内读取新鲜公式值并留存，普通写入不得覆盖留存目标。 */
@Component
public class RecordCaptures {
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordPersistence persistence;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordCalculations calculations;
    @Resource private org.springframework.beans.factory.ObjectProvider<RecordWriteService> writer;

    public List<String> managedFields(String object) {
        return catalog.captures(object).stream()
                .flatMap(c -> c.action().captures().keySet().stream())
                .distinct()
                .toList();
    }

    /** 已有表单可能回传未变字段；允许相同值但移出写载荷，默认值也不得写入受管字段。 */
    public Map<String, Object> prepare(
            DataCenter.Definition definition,
            String id,
            Map<String, Object> input,
            Map<String, Object> requested,
            long actor) {
        List<String> managed = managedFields(definition.objectId());
        if (managed.isEmpty()) return input;
        Map<String, Object> result = new LinkedHashMap<>(input);
        Map<String, Object> previous =
                id == null
                        ? Map.of()
                        : persistence
                                .read(schemas.main(definition), id, null, actor, false)
                                .values();
        CaptureWriteScope.Context context = CaptureWriteScope.current();
        for (String field : managed) {
            if (context != null
                    && context.objectId().equals(definition.objectId())
                    && Objects.equals(context.recordId(), id)
                    && context.captures().containsKey(field)) continue;
            if (requested != null
                    && requested.containsKey(field)
                    && !same(definition, field, requested.get(field), previous.get(field)))
                throw invalid("字段由业务动作留存，不能手工修改或清空");
            result.remove(field);
        }
        return result;
    }

    private boolean same(DataCenter.Definition definition, String id, Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (FieldTypeEnum.fromCode(DataScope.field(definition, id).type()).isNumeric()) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))
                        == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Objects.equals(left, right);
    }

    public Aggregate execute(
            ApplicationBusiness.Execute command,
            ApplicationBusiness.Action action,
            DataCenter.Definition definition,
            int applicationVersion,
            int objectVersion,
            String actionName,
            long actor) {
        ApplicationCaptureRules.validate(action, definition);
        RuntimeSchema.Table table = schemas.main(definition);
        ApplicationRuntimePolicy.Access access =
                policy.access(command.applicationId(), definition, actor);
        Row stored =
                persistence.authorizedRead(
                        table,
                        command.recordId(),
                        actor,
                        true,
                        access,
                        ApplicationActionEnum.UPDATE);
        persistence.checkRevision(stored, command.expectedRevision());
        Row readable =
                persistence.authorizedRead(
                        table,
                        command.recordId(),
                        actor,
                        false,
                        access,
                        ApplicationActionEnum.READ);
        if (!readable.permissions().readFields().containsAll(action.captures().values())
                || !stored.permissions().writeFields().containsAll(action.captures().keySet()))
            throw invalid("没有来源公式读取或留存目标修改权限");
        for (String field : action.captures().keySet())
            if (stored.values().get(field) != null) throw invalid("此记录已经留存确认值，不能重复确认或覆盖");
        Map<String, Object> fresh =
                calculations.freshValues(
                        command.applicationId(),
                        definition,
                        command.recordId(),
                        new HashSet<>(action.captures().values()),
                        actor);
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, String> capture : action.captures().entrySet()) {
            Object value = fresh.get(capture.getValue());
            if (value == null) throw invalid("来源公式结果为空，未留存任何确认值");
            input.put(capture.getKey(), value);
        }
        CaptureWriteScope.Context context =
                new CaptureWriteScope.Context(
                        command.applicationId(),
                        applicationVersion,
                        command.actionId(),
                        actionName,
                        definition.objectId(),
                        objectVersion,
                        command.recordId(),
                        action.captures());
        return CaptureWriteScope.run(
                context,
                () ->
                        writer.getObject()
                                .save(
                                        new Save(
                                                command.applicationId(),
                                                command.objectId(),
                                                command.recordId(),
                                                command.expectedRevision(),
                                                input,
                                                null),
                                        actor));
    }
}
