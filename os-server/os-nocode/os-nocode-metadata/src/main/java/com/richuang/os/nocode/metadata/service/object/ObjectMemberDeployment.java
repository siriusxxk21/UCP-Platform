package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadata;
import com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.ObjectTables;
import com.richuang.os.nocode.api.RelationSources;
import com.richuang.os.nocode.enums.RelationTypeEnum;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.service.table.DataTableService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** 成员身份以发布定义和真实结构为边界，草稿中的稳定 ID 本身不表示已经部署。 */
@Component
public class ObjectMemberDeployment {
    @Resource private ObjectDesignReader reader;
    @Resource private DatabaseMetadataReader database;

    boolean detailLocked(
            ObjectDraftHeadDO head, String id, String schema, String table, boolean adopted) {
        if (adopted) return true;
        Definition published = reader.published(head.getId().toString());
        return published != null && published.details().stream().anyMatch(d -> d.id().equals(id))
                || database.relationExists(schema, table);
    }

    boolean relationLocked(ObjectDraftHeadDO head, Relation relation) {
        Definition published = reader.published(head.getId().toString());
        if (published != null
                && published.relations().stream().anyMatch(r -> r.id().equals(relation.id())))
            return true;
        if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
            String table =
                    DataTableService.relationTable(
                            database, head.getSchemaName(), head.getId().toString(), relation.id());
            return database.relationExists(head.getSchemaName(), table);
        }
        RelationSources.Source source =
                RelationSources.source(reader.definition(head.getId().toString()), relation);
        DatabaseMetadata.Table table =
                database.readTable(source.binding().schemaName(), source.tableName()).orElse(null);
        if (table == null) return false;
        if (table.constraints().stream()
                .anyMatch(c -> c.name().equals("nocode_fk_r_" + relation.id()))) return true;
        FieldOptions option =
                source.options().getOrDefault(relation.fieldId(), FieldOptions.defaults());
        if (!Boolean.TRUE.equals(option.generated())) return false;
        FieldDefinition field =
                source.fields().stream()
                        .filter(f -> Objects.equals(f.id(), relation.fieldId()))
                        .findFirst()
                        .orElse(null);
        String column =
                option.columnName() != null
                        ? option.columnName()
                        : field == null ? null : field.code();
        return table.columns().stream().anyMatch(c -> c.name().equals(column));
    }

    boolean indexLocked(ObjectDraftHeadDO head, Index index) {
        Definition published = reader.published(head.getId().toString());
        if (published != null
                && published.indexes().stream().anyMatch(i -> i.id().equals(index.id())))
            return true;
        Definition current = reader.definition(head.getId().toString());
        return ObjectTables.bindings(current).keySet().stream()
                .map(ref -> database.readTable(ref.schema(), ref.name()).orElse(null))
                .filter(Objects::nonNull)
                .anyMatch(
                        table ->
                                table.indexes().stream()
                                        .anyMatch(i -> i.name().equals("nocode_i_" + index.id())));
    }
}
