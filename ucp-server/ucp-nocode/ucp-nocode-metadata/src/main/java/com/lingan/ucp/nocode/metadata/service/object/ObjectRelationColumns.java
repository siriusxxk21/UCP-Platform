package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.enums.RelationTypeEnum;
import com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 关系生成引用列（关系编码 + "_id"）在同一张来源表里只能由一个字段成员占用。关系被删除后，生成列停用但仍占着编码和物理列；
 * 用同一编码重建关系时沿用这个停用的生成列（同一张表、同一物理列），而不是再造一个同名字段撞唯一约束。
 *
 * <p>只沿用「关系生成」的列：停用的普通字段占用时给出业务报错，请用户恢复原字段或更换关系编码。
 */
final class ObjectRelationColumns {
    private final DataCenterMapper store;
    private final ObjectDesignReader designReader;

    /** 不是独立 Bean：由关系保存在同一事务里用自身的映射器与读取器构造，不改变既有装配清单。 */
    ObjectRelationColumns(DataCenterMapper store, ObjectDesignReader designReader) {
        this.store = store;
        this.designReader = designReader;
    }

    /**
     * 复用已有列的单值关系在草稿里删掉、又用同一编码在同一列上重建时，沿用已发布关系的稳定 ID。
     *
     * <p>这一列在已发布版本里存着原目标对象的记录 ID；若当成一条新关系，发布时旧值会被直接当成新目标的记录（不同对象的 ID 可能恰好重合），不会提示清空。沿用稳定 ID
     * 后与「在原关系上更换目标」完全相同：编码、类型、来源仍受原有保护， 换目标时由发布计划逐列确认清空旧值；目标未变时就是原关系恢复。
     *
     * @return 对应的已发布关系；不满足「同编码、同列、同类型、同来源且本次未提交该关系」时返回 null
     */
    Relation publishedIdentity(
            ObjectDraftHeadDO head,
            String relationCode,
            Relation relation,
            List<Relation> requested) {
        if (relation.id() != null || relation.fieldId() == null) return null;
        Definition published = designReader.published(head.getId().toString());
        if (published == null) return null;
        Relation match =
                published.relations().stream()
                        .filter(r -> relationCode.equals(r.code()))
                        .findFirst()
                        .orElse(null);
        if (match == null
                || requested.stream().anyMatch(r -> match.id().equals(r.id()))
                || !Objects.equals(match.fieldId(), relation.fieldId())
                || !Objects.equals(match.kind(), relation.kind())
                || !Objects.equals(match.sourceDetailId(), relation.sourceDetailId())) return null;
        return match;
    }

    /**
     * 返回可沿用的生成列稳定 ID；没有可沿用的列时返回 null，由原逻辑新建。
     *
     * @param sourceFields 来源表当前启用的字段
     * @param previous 本次保存前草稿里的关系
     * @param requested 本次保存提交的关系
     * @param targetKeyType 目标真实主键类型；沿用列的存储类型必须一致
     */
    String retiredGeneratedField(
            ObjectDraftHeadDO head,
            long sourceTableId,
            String relationCode,
            Relation relation,
            List<FieldDefinition> sourceFields,
            Collection<Relation> previous,
            List<Relation> requested,
            String targetKeyType) {
        if (relation.fieldId() != null || RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
            return null;
        String column = relationCode + "_id";
        Map<String, DataCenterRows.Field> rows =
                store.fieldOptions(head.getVersionId()).stream()
                        .collect(
                                Collectors.toMap(
                                        f -> f.getStableFieldId().toString(), f -> f, (a, b) -> a));
        Set<String> kept =
                requested.stream()
                        .map(Relation::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        // 本次保存里被移除的关系，其生成列在保存末尾才停用；同编码重建时同样可以接手。
        Set<String> released =
                previous.stream()
                        .filter(r -> r.fieldId() != null && !kept.contains(r.id()))
                        .map(Relation::fieldId)
                        .collect(Collectors.toSet());
        List<FieldDefinition> candidates = new ArrayList<>();
        store.inactiveFields(head.getVersionId(), sourceTableId).stream()
                .filter(f -> occupies(f, rows.get(f.id()), column))
                .forEach(candidates::add);
        // 启用中的普通字段占用编码时仍由原逻辑报「关系字段编码已存在」。
        sourceFields.stream()
                .filter(
                        f ->
                                released.contains(f.id())
                                        && rows.get(f.id()) != null
                                        && Boolean.TRUE.equals(
                                                rows.get(f.id()).getRelationGenerated())
                                        && occupies(f, rows.get(f.id()), column))
                .forEach(candidates::add);
        for (var field : candidates) {
            var row = rows.get(field.id());
            if (row == null || !Boolean.TRUE.equals(row.getRelationGenerated()))
                throw new ServiceException(
                        DUPLICATE,
                        "关系生成的引用列 " + column + " 已被停用字段“" + field.name() + "”占用，请恢复原字段或修改关系编码");
            var option =
                    designReader
                            .options(head.getVersionId())
                            .getOrDefault(field.id(), FieldOptions.defaults());
            if (option.nativeType() != null && !sameType(option.nativeType(), targetKeyType))
                throw invalid("关系生成的引用列 " + column + " 仍保留原关系的数据，类型与新目标的主键不一致，请修改关系编码");
            // 生成列不支持「换目标并清空」：已发布关系指向别的对象时，沿用这一列会把旧值当成新目标的记录。
            Definition published = designReader.published(head.getId().toString());
            if (published != null
                    && published.relations().stream()
                            .anyMatch(
                                    r ->
                                            field.id().equals(r.fieldId())
                                                    && !Objects.equals(
                                                            r.targetObjectId(),
                                                            relation.targetObjectId())))
                throw new ServiceException(
                        DUPLICATE,
                        "关系生成的引用列 "
                                + column
                                + " 仍保存着已发布关系指向原目标对象的数据，不能用同一关系编码改指向其他对象；请修改关系编码后新建，或保持原目标对象");
            return field.id();
        }
        return null;
    }

    private static boolean occupies(
            FieldDefinition field, DataCenterRows.Field row, String column) {
        return column.equals(field.code()) || row != null && column.equals(row.getColumnName());
    }

    private static boolean sameType(String left, String right) {
        return PostgreSqlCommands.type(left)
                .replace("character varying", "varchar")
                .equals(PostgreSqlCommands.type(right).replace("character varying", "varchar"));
    }
}
