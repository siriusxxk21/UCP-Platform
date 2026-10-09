package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.dal.query.*;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 记录持久层的参数编码、原始读取、行授权及修订检查；调用方持有原事务。 */
@Component
public class RecordPersistence {
    @Resource private RecordMapper records;
    @Resource private ObjectMapper json;
    @Resource private RuntimeSchema schemas;
    @Resource private com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope taskScope;

    static Map<String, Object> mergeValues(
            RuntimeSchema.Table t, Map<String, Object> before, Map<String, Object> payload) {
        var result = new LinkedHashMap<>(before);
        for (var field : t.fields())
            if (payload.containsKey(t.column(field)))
                result.put(field.id(), payload.get(t.column(field)));
        return result;
    }

    RecordStatement writeStatement(
            RuntimeSchema.Table t, String id, String parent, Map<String, Object> data, long actor) {
        schemas.requireWriteCompatible(t, data.keySet());
        var b = t.statement(id, parent, Long.toString(actor), false);
        return new RecordStatement(
                b.schema(),
                b.table(),
                b.keyColumn(),
                b.fields(),
                b.textFields(),
                b.numericFields(),
                b.deletedColumn(),
                b.id(),
                b.parentColumn(),
                b.parentId(),
                null,
                null,
                List.of(),
                "{}",
                null,
                false,
                1,
                0,
                new ArrayList<>(data.keySet()),
                write(data),
                b.actor(),
                false);
    }

    Row read(RuntimeSchema.Table t, String id, String parent, long actor, boolean lock) {
        if (id == null || id.isBlank() || id.length() > 500) throw invalid("记录 ID 无效");
        var found = records.rows(t.statement(id, parent, Long.toString(actor), lock));
        if (found.size() != 1) throw new ServiceException(NOT_FOUND, "记录不存在或已删除");
        return row(found.getFirst());
    }

    Row row(String raw) {
        try {
            var stored = json.readValue(raw, StoredRow.class);
            return new Row(stored.id(), stored.revision(), stored.values());
        } catch (Exception e) {
            throw invalid("业务记录读取失败");
        }
    }

    record StoredRow(
            String id, String revision, Map<String, Object> values, String recordCreator) {}

    Row visible(String raw, ApplicationRuntimePolicy.Access access) {
        try {
            var stored = json.readValue(raw, StoredRow.class);
            var caps =
                    access.require(
                            stored.recordCreator(), stored.values(), ApplicationActionEnum.READ);
            if (caps.actions().contains(ApplicationActionEnum.UPDATE.getCode()))
                caps =
                        access.require(
                                stored.recordCreator(),
                                stored.values(),
                                ApplicationActionEnum.UPDATE);
            else
                caps =
                        new ApplicationAuthorization.Capabilities(
                                caps.actions(),
                                caps.readFields(),
                                Set.of(),
                                caps.readDetails(),
                                Set.of(),
                                caps.readRelations(),
                                Set.of());
            Map<String, Object> output = new LinkedHashMap<>(stored.values());
            output.keySet().retainAll(caps.readFields());
            return new Row(stored.id(), stored.revision(), output, caps);
        } catch (java.io.IOException e) {
            throw invalid("业务记录读取失败");
        }
    }

    Row authorizedRead(
            RuntimeSchema.Table table,
            String id,
            long actor,
            boolean lock,
            ApplicationRuntimePolicy.Access access,
            ApplicationActionEnum action) {
        return authorizedRead(table, id, actor, lock, access, action, false);
    }

    /** 仅把记录已经不存在视为空值；身份、任务范围和行权限错误仍正常抛出。 */
    Row authorizedReadIfPresent(
            RuntimeSchema.Table table,
            String id,
            long actor,
            ApplicationRuntimePolicy.Access access) {
        return authorizedRead(table, id, actor, false, access, ApplicationActionEnum.READ, true);
    }

    private Row authorizedRead(
            RuntimeSchema.Table table,
            String id,
            long actor,
            boolean lock,
            ApplicationRuntimePolicy.Access access,
            ApplicationActionEnum action,
            boolean allowMissing) {
        if (id == null || id.isBlank() || id.length() > 500) throw invalid("记录 ID 无效");
        // 引用保存校验与候选沿用同一窄读取能力；其它详情读取和写入不能绕过任务数据集合。
        taskScope.requireRecord(
                access.definition().objectId(),
                id,
                access.taskReference() && action == ApplicationActionEnum.READ);
        var raw = records.rows(table.statement(id, null, Long.toString(actor), lock));
        if (allowMissing && raw.isEmpty()) return null;
        if (raw.size() != 1) throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        try {
            var stored = json.readValue(raw.getFirst(), StoredRow.class);
            var caps = access.require(stored.recordCreator(), stored.values(), action);
            if (action == ApplicationActionEnum.READ) return visible(raw.getFirst(), access);
            return new Row(stored.id(), stored.revision(), stored.values(), caps);
        } catch (java.io.IOException e) {
            throw invalid("业务记录读取失败");
        }
    }

    String write(Object value) {
        try {
            String text = json.writeValueAsString(value);
            if (text.length() > 2_000_000) throw invalid("记录内容过大");
            return text;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("记录格式无效");
        }
    }

    /** 跨应用规则不能借调用应用的更宽范围移动目标；候选值沿用真实创建人检查规则应用权限。 */
    ApplicationAuthorization.Capabilities requireCandidate(
            RuntimeSchema.Table table,
            String id,
            Map<String, Object> candidate,
            long actor,
            ApplicationRuntimePolicy.Access access) {
        String creator = Long.toString(actor);
        if (id != null) {
            taskScope.requireRecord(access.definition().objectId(), id);
            var raw = records.rows(table.statement(id, null, Long.toString(actor), false));
            if (raw.size() != 1) throw invalid("自动更新目标不存在");
            try {
                creator = json.readValue(raw.getFirst(), StoredRow.class).recordCreator();
            } catch (java.io.IOException e) {
                throw invalid("自动更新目标无法读取");
            }
        }
        return access.require(creator, candidate, ApplicationActionEnum.UPDATE);
    }

    void writable(RuntimeSchema.Table table) {
        if (!table.writable()) throw invalid("该表为只读或当前结构不符合写入条件");
    }

    void checkRevision(Row row, String revision) {
        if (!Objects.equals(row.revision(), revision)) throw conflict();
    }

    ServiceException conflict() {
        return new ServiceException(CONFLICT, "记录已被修改，请刷新后重试");
    }
}
