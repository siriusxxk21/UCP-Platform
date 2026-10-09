package com.richuang.os.nocode.schema.service.publish;

import static com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.*;
import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.ObjectTables;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignService;
import com.richuang.os.nocode.metadata.service.table.DataTableService;
import com.richuang.os.nocode.schema.service.compile.SchemaCompiler;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 发布计划与结构基线检查、物理表锁集合；事务和失败留痕仍由发布服务控制。 */
@Component
public class SchemaPublishContext {
    @Resource private ObjectDesignService designs;
    @Resource private DataTableService tables;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDraftMapper objects;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;

    void requireDraft(ObjectDraftHeadDO h) {
        if (!VersionStateEnum.DRAFT.matches(h.getVersionState())
                || !Set.of(ObjectStatusEnum.DRAFT.getCode(), ObjectStatusEnum.ACTIVE.getCode())
                        .contains(h.getStatus())) throw invalid("只有启用对象的草稿可以发布");
    }

    boolean reconciliation(ObjectDraftHeadDO h, Definition d) {
        return h.getReconciliationHash() != null
                && Objects.equals(h.getReconciliationVersionId(), h.getVersionId())
                && h.getReconciliationHash().equals(DigestUtil.sha256Hex(tables.capture(d)));
    }

    SchemaCompiler.Compilation reconciliationCompilation() {
        return new SchemaCompiler.Compilation(
                List.of(),
                List.of(
                        new Step(
                                SchemaChangeEnum.RECONCILE.getCode(),
                                "发布已核对的真实结构映射和新基线；保留原表及数据，不执行结构修改")),
                List.of());
    }

    DataCenterRows.Plan requirePlan(String id, boolean lock) {
        try {
            UUID.fromString(id);
        } catch (Exception e) {
            throw invalid("发布计划 ID 无效");
        }
        var plan = store.plan(id, lock);
        if (plan == null || Boolean.TRUE.equals(plan.getDeleted())) throw invalid("发布计划不存在");
        designs.head(plan.getObjectId().toString(), false);
        return plan;
    }

    /** 目标主键、目标版本与依赖也参与校验，避免只校验当前对象。 */
    String contextHash(Definition definition) {
        var context = new TreeMap<String, Object>();
        context.put("tables", tables.capture(definition));
        context.put("dependencies", store.dependencies(Long.parseLong(definition.objectId())));
        for (var relation : definition.relations()) {
            var target = designs.head(relation.targetObjectId(), false);
            var actual =
                    database.readTable(target.getSchemaName(), target.getTableName()).orElse(null);
            context.put(
                    "target:" + relation.targetObjectId(),
                    Arrays.asList(
                            target.getStatus(),
                            target.getCurrentPublishedVersionNo(),
                            actual == null ? null : tables.fingerprint(actual)));
        }
        return DigestUtil.sha256Hex(designs.write(context));
    }

    void lockTables(Definition definition) {
        var selected = new TreeMap<String, String[]>();
        ObjectTables.bindings(definition)
                .keySet()
                .forEach(
                        ref ->
                                selected.put(
                                        ref.key(definition.schemaName()),
                                        new String[] {ref.schema(), ref.name()}));
        for (var r : definition.relations()) {
            var target = designs.head(r.targetObjectId(), false);
            selected.put(
                    target.getSchemaName() + "." + target.getTableName(),
                    new String[] {target.getSchemaName(), target.getTableName()});
            if (RelationTypeEnum.MANY_TO_MANY.matches(r.kind())) {
                String table =
                        DataTableService.relationTable(
                                database, definition.schemaName(), definition.objectId(), r.id());
                selected.put(
                        definition.schemaName() + "." + table,
                        new String[] {definition.schemaName(), table});
            }
        }
        for (var pair : selected.values()) {
            objects.lockTableName("nocode-table:" + pair[1]);
            if (database.relationExists(pair[0], pair[1])) commands.execute(lock(pair[0], pair[1]));
        }
    }
}
