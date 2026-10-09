package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 记录引用检查和多对多读写；保留目标授权、关联策略及写入顺序。 */
@Component
public class RecordRelationAccess {
    @Resource private RecordContextResolver contexts;
    @Resource private RecordPersistence persistence;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordRelations relations;

    void validateReferences(
            String app,
            DataCenter.Definition d,
            RuntimeSchema.Table t,
            Map<String, Object> payload,
            long actor) {
        for (var r : d.relations()) {
            if (RelationTypeEnum.MANY_TO_MANY.matches(r.kind())) continue;
            String column = t.columns().get(r.fieldId());
            if (column == null || !payload.containsKey(column) || payload.get(column) == null)
                continue;
            // 目标对象不必被应用引用，成员也不必有它的授权：按引用访问核对所选记录存在且在可选范围内。
            var targetDefinition = contexts.readable(app, r.targetObjectId(), actor);
            var target = schemas.main(targetDefinition);
            // 读取并锁住引用记录，避免引用建立与目标逻辑删除交错。
            persistence.authorizedRead(
                    target,
                    payload.get(column).toString(),
                    actor,
                    true,
                    policy.referenceAccess(app, d, r, targetDefinition, actor, Set.of(), null),
                    ApplicationActionEnum.READ);
        }
    }

    Map<String, List<String>> readRelations(
            DataCenter.Definition d, String id, long actor, Set<String> readable) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var relation : d.relations())
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())
                    && readable.contains(relation.id()))
                result.put(relation.id(), relations.targets(d, relation, id, actor));
        return result;
    }

    /** 未提交关系保持原样；已提交关系按目标 ID 集合替换，新增目标必须可见且仍有效。 */
    void saveRelations(
            Save command, DataCenter.Definition d, String id, boolean insert, long actor) {
        saveRelations(command, d, id, insert, actor, true);
    }

    void saveRelations(
            Save command,
            DataCenter.Definition d,
            String id,
            boolean insert,
            long actor,
            boolean writing) {
        Map<String, List<String>> submitted =
                command.relations() == null ? Map.of() : command.relations();
        for (var relation : d.relations()) {
            if (!RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) continue;
            if (!submitted.containsKey(relation.id())) {
                if (insert && Boolean.TRUE.equals(relation.required()))
                    throw invalid("请选择关联：" + relation.name());
                continue;
            }
            var selected = submitted.get(relation.id());
            if (selected == null
                    || selected.size() > 500
                    || new HashSet<>(selected).size() != selected.size()
                    || selected.stream()
                            .anyMatch(v -> v == null || v.isBlank() || v.length() > 500))
                throw invalid("关联记录为空、重复或超过 500 条");
            if (Boolean.TRUE.equals(relation.required()) && selected.isEmpty())
                throw invalid("请选择关联：" + relation.name());
            var before = insert ? List.<String>of() : relations.targets(d, relation, id, actor);
            for (String targetId : selected)
                if (!before.contains(targetId)) {
                    var target =
                            contexts.readable(
                                    command.applicationId(), relation.targetObjectId(), actor);
                    persistence.authorizedRead(
                            schemas.main(target),
                            targetId,
                            actor,
                            true,
                            policy.referenceAccess(
                                    command.applicationId(),
                                    d,
                                    relation,
                                    target,
                                    actor,
                                    Set.of(),
                                    null),
                            ApplicationActionEnum.READ);
                    if (writing) relations.attach(d, relation, id, targetId, actor);
                }
            for (String targetId : before)
                if (writing && !selected.contains(targetId))
                    relations.detach(d, relation, id, targetId, actor);
        }
    }
}
