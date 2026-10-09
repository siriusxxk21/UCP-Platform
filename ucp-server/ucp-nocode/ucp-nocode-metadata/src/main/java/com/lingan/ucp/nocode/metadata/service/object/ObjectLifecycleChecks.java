package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.ObjectOperationPreview.*;
import com.lingan.ucp.nocode.api.ObjectOperationPreview.DataScope;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 对象治理的共用只读规则；预检和持有修订锁的执行端读取同一依赖及保留表范围。 */
@Component
public class ObjectLifecycleChecks {
    @Resource private ObjectDesignReader reader;
    @Resource private ObjectDesignCodec codec;
    @Resource private DataCenterMapper store;
    @Resource private DatabaseMetadataReader database;
    @Resource private ObjectOperationDataQueries data;

    record Evaluation(List<Impact> impacts, List<DataScope> scopes) {}

    private record OwnedTable(ObjectOperationScopeEnum kind, String name, ObjectTables.Ref table) {}

    Evaluation inspect(ObjectDraftHeadDO head, LifecycleActionEnum action, boolean includeScopes) {
        List<Impact> impacts = new ArrayList<>();
        if (action != LifecycleActionEnum.ENABLE)
            for (Dependency dependency : store.dependencies(head.getId()))
                impacts.add(ObjectOperationImpacts.dependency(dependency, null, true));
        if (!includeScopes && action != LifecycleActionEnum.DELETE)
            return new Evaluation(impacts, List.of());
        Definition definition = reader.definition(head.getId().toString());
        List<DataScope> scopes = new ArrayList<>();
        for (OwnedTable owned : tables(head, definition)) {
            ObjectTables.Ref table = owned.table();
            boolean exists = database.relationExists(table.schema(), table.name());
            Long count = exists ? data.rows(table.schema(), table.name()) : null;
            String location = owned.name() + " / " + table.schema() + "." + table.name();
            if (exists && count == null)
                impacts.add(
                        ObjectOperationImpacts.own(
                                ObjectOperationCheckEnum.UNKNOWN_DATA,
                                definition,
                                null,
                                location,
                                "无法核实此表的数据数量，当前结果为未知",
                                "检查数据库读取权限及结构状态，恢复后重新预检；不能按零条记录继续"));
            if (action == LifecycleActionEnum.DELETE && count != null && count > 0)
                impacts.add(
                        ObjectOperationImpacts.own(
                                ObjectOperationCheckEnum.BUSINESS_ROWS,
                                definition,
                                null,
                                location,
                                "对象或明细包含业务数据，不能删除对象：此表仍有 " + count + " 条业务记录（包含逻辑删除）",
                                "在已有授权业务入口处理记录；历史保留表请由负责该数据的管理员处理，再重新检查"));
            scopes.add(
                    new DataScope(
                            owned.kind().getCode(),
                            owned.name(),
                            table.schema(),
                            table.name(),
                            null,
                            count,
                            null,
                            true,
                            exists ? "物理表及记录保留；本操作不清空数据、不删除物理表" : "当前尚无此物理表；本操作不创建或删除物理表"));
        }
        return new Evaluation(List.copyOf(impacts), List.copyOf(scopes));
    }

    /** 必须在原执行事务取得对象修订锁后调用；预检结果不作为执行授权。 */
    void requireAllowed(ObjectDraftHeadDO head, LifecycleActionEnum action) {
        inspect(head, action, false).impacts().stream()
                .filter(Impact::blocking)
                .findFirst()
                .ifPresent(
                        impact -> {
                            String message =
                                    ObjectOperationCheckEnum.DEPENDENCY.matches(impact.code())
                                            ? "对象仍被其他对象或资源引用，不能停用或删除："
                                                    + impact.sourceName()
                                                    + " / "
                                                    + impact.location()
                                            : impact.message();
                            throw invalid(message + "。" + impact.resolution());
                        });
    }

    private List<OwnedTable> tables(ObjectDraftHeadDO head, Definition definition) {
        Map<ObjectTables.Ref, OwnedTable> tables = new LinkedHashMap<>();
        TableBinding main = ObjectTables.main(definition);
        add(
                tables,
                ObjectOperationScopeEnum.MAIN,
                definition.objectName() + " / 主表",
                main.schemaName(),
                definition.tableName());
        for (Detail detail : definition.details()) {
            TableBinding binding = ObjectTables.detail(definition, detail);
            add(
                    tables,
                    ObjectOperationScopeEnum.DETAIL,
                    definition.objectName() + " / 明细「" + detail.name() + "」",
                    binding.schemaName(),
                    detail.tableName());
        }
        for (Relation relation : store.relations(head.getVersionId()))
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
                add(
                        tables,
                        ObjectOperationScopeEnum.RELATION,
                        "多对多关系「" + relation.name() + "」",
                        head.getSchemaName(),
                        DataTableService.relationTable(
                                database,
                                head.getSchemaName(),
                                head.getId().toString(),
                                relation.id()));
        com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows.Deployment deployment =
                store.deployment(head.getId());
        if (deployment != null) {
            Map<?, ?> retained = codec.read(deployment.getStructureJson(), Map.class);
            for (Object key : retained.keySet()) {
                ObjectTables.Ref table =
                        ObjectTables.fromKey(key.toString(), deployment.getSchemaName());
                add(
                        tables,
                        ObjectOperationScopeEnum.RETAINED,
                        "历史部署保留表",
                        table.schema(),
                        table.name());
            }
        }
        return List.copyOf(tables.values());
    }

    private static void add(
            Map<ObjectTables.Ref, OwnedTable> tables,
            ObjectOperationScopeEnum kind,
            String name,
            String schema,
            String table) {
        ObjectTables.Ref ref = new ObjectTables.Ref(schema, table);
        tables.putIfAbsent(ref, new OwnedTable(kind, name, ref));
    }
}
