package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 关联表的目录校验和持久化。调用者先持有主记录锁，并逐条检查新增目标的应用权限。 */
@Service
public class RecordRelations {
    @Resource private DatabaseMetadataReader database;
    @Resource private RecordMapper records;
    @Resource private ObjectMapper json;

    public RelationStatement statement(
            Definition source, Relation relation, String sourceId, String targetId, long actor) {
        String table =
                DataTableService.relationTable(
                        database, source.schemaName(), source.objectId(), relation.id());
        var actual =
                database.readTable(source.schemaName(), table)
                        .orElseThrow(() -> invalid("关联表不存在：" + relation.name()));
        if (!BaseDOColumns.differences(actual).isEmpty()
                || actual.columns().stream().noneMatch(c -> "source_id".equals(c.name()))
                || actual.columns().stream().noneMatch(c -> "target_id".equals(c.name())))
            throw invalid("关联表结构不符合平台规范：" + relation.name());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source_id", sourceId);
            payload.put("target_id", targetId);
            return new RelationStatement(
                    source.schemaName(),
                    table,
                    source.schemaName(),
                    source.tableName(),
                    ObjectTables.main(source).keyColumn(),
                    sourceId,
                    targetId,
                    json.writeValueAsString(payload),
                    Long.toString(actor));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("关联内容无法序列化");
        }
    }

    public List<String> targets(Definition source, Relation relation, String id, long actor) {
        var result = records.relationTargets(statement(source, relation, id, null, actor));
        if (result.size() > 500) throw invalid("单组关联超过 500 条，不能整组编辑");
        return result;
    }

    public Map<String, List<String>> targets(
            Definition source, Relation relation, List<String> ids, long actor) {
        if (ids.isEmpty()) return Map.of();
        try {
            var result = new LinkedHashMap<String, List<String>>();
            for (String raw :
                    records.relationBatchTargets(
                            statement(source, relation, null, null, actor),
                            json.writeValueAsString(ids))) {
                var item = json.readTree(raw);
                result.computeIfAbsent(item.get("source").asText(), k -> new ArrayList<>())
                        .add(item.get("target").asText());
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("关联内容无法读取");
        }
    }

    public List<String> sources(Definition source, Relation relation, String targetId, long actor) {
        var result = records.relationSources(statement(source, relation, null, targetId, actor));
        if (result.size() > 500) throw invalid("关联记录超过单次处理上限，请先分批解除关系");
        return result;
    }

    public void attach(Definition source, Relation relation, String id, String target, long actor) {
        records.attach(statement(source, relation, id, target, actor));
    }

    public void detach(Definition source, Relation relation, String id, String target, long actor) {
        records.detach(statement(source, relation, id, target, actor));
    }
}
