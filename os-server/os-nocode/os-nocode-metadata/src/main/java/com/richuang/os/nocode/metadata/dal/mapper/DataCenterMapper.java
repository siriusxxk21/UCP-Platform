package com.richuang.os.nocode.metadata.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows;
import com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 版本、配置与发布记录使用同一底座 MyBatis 会话，不包含连接管理。 */
@Mapper
public interface DataCenterMapper extends BaseMapperX<NocodeObjectDO> {
    com.baomidou.mybatisplus.core.metadata.IPage<ObjectDraftHeadDO> selectDesignPage(
            com.baomidou.mybatisplus.core.metadata.IPage<ObjectDraftHeadDO> page,
            @Param("name") String name,
            @Param("code") String code,
            @Param("status") String status,
            @Param("source") String source,
            @Param("owner") String owner,
            @Param("category") String category);

    List<String> categories();

    List<ObjectDraftHeadDO> allHeads();

    String tableConfig(@Param("tableId") long tableId);

    int tableBinding(@Param("tableId") long tableId, @Param("binding") String binding);

    List<DataCenterRows.Detail> details(@Param("versionId") long versionId);

    List<FieldDefinition> detailFields(@Param("tableId") long tableId);

    /** 指定当前版本、表范围读取停用字段，编码仍由这些行占用。 */
    List<FieldDefinition> inactiveFields(
            @Param("versionId") long versionId, @Param("tableId") long tableId);

    int restoreField(
            @Param("versionId") long versionId,
            @Param("fieldId") long fieldId,
            @Param("actor") long actor);

    /** 仅已发布且当时有效的同身份字段能证明保留列归属。 */
    String lastPublishedFieldSchema(
            @Param("objectId") long objectId, @Param("fieldId") long fieldId);

    int insertDetail(
            @Param("versionId") long versionId, @Param("table") DataCenterRows.Detail table);

    int updateDetail(
            @Param("id") long id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("config") String config);

    int deactivateDetailFields(@Param("tableId") long tableId);

    String versionSchema(@Param("objectId") long objectId, @Param("versionNo") int versionNo);

    String versionChecksum(@Param("versionId") long versionId);

    List<DataCenter.Version> versions(@Param("objectId") long objectId);

    List<DataCenterRows.Field> fieldOptions(@Param("versionId") long versionId);

    int updateFieldOptions(
            @Param("versionId") long versionId,
            @Param("fieldId") long fieldId,
            @Param("options") String options,
            @Param("state") String state,
            @Param("classification") String classification,
            @Param("columnName") String columnName,
            @Param("generated") boolean generated);

    int settings(
            @Param("id") long id,
            @Param("settings") String settings,
            @Param("titleTemplate") String titleTemplate);

    int renameMainTable(@Param("versionId") long versionId, @Param("name") String name);

    int markAdopted(
            @Param("id") long id,
            @Param("schema") String schema,
            @Param("readOnly") boolean readOnly,
            @Param("hash") String hash);

    int auditTable(
            @Param("trace") String trace,
            @Param("actor") long actor,
            @Param("resource") String resource,
            @Param("detail") String detail);

    int cloneVersion(@Param("id") long id, @Param("actor") long actor);

    int cloneTables(@Param("id") long id);

    int cloneFields(@Param("id") long id);

    int cloneRelations(@Param("id") long id);

    int cloneIndexes(@Param("id") long id);

    int advanceVersion(@Param("id") long id, @Param("actor") long actor);

    int setLifecycle(
            @Param("id") long id, @Param("status") String status, @Param("actor") long actor);

    int publishVersion(
            @Param("id") long id, @Param("versionId") long versionId, @Param("actor") long actor);

    int publishHead(@Param("id") long id, @Param("actor") long actor);

    int markReconciliation(
            @Param("id") long id, @Param("versionId") long versionId, @Param("hash") String hash);

    int insertPlan(@Param("plan") DataCenterRows.Plan plan);

    DataCenterRows.Plan plan(@Param("id") String id, @Param("lock") boolean lock);

    List<DataCenterRows.Plan> plans(@Param("objectId") long objectId);

    int finishPlan(
            @Param("id") String id,
            @Param("state") String state,
            @Param("actor") long actor,
            @Param("reason") String reason,
            @Param("error") String error);

    int insertDeployment(
            @Param("id") long id,
            @Param("versionNo") int versionNo,
            @Param("schema") String schema,
            @Param("table") String table,
            @Param("hash") String hash,
            @Param("structure") String structure,
            @Param("actor") long actor);

    DataCenterRows.Deployment deployment(@Param("id") long id);

    int verifyDeployment(@Param("id") long id);

    List<DataCenter.Dependency> dependencies(@Param("id") long id);

    List<DataCenter.Relation> relations(@Param("versionId") long versionId);

    List<DataCenter.Index> indexes(@Param("versionId") long versionId);

    int deleteRelations(@Param("versionId") long versionId);

    int insertRelation(
            @Param("versionId") long versionId, @Param("r") DataCenter.Relation relation);

    int deleteIndexes(@Param("versionId") long versionId);

    /** 物理删除草稿版本里已逻辑删除、编码相同而稳定 ID 不同的旧关系行；已发布版本不受影响。 */
    int purgeDeletedRelationCode(
            @Param("versionId") long versionId,
            @Param("code") String code,
            @Param("id") String stableId);

    /** 物理删除草稿版本里已逻辑删除、编码相同而稳定 ID 不同的旧索引行；已发布版本不受影响。 */
    int purgeDeletedIndexCode(
            @Param("versionId") long versionId,
            @Param("code") String code,
            @Param("id") String stableId);

    int insertIndex(
            @Param("versionId") long versionId,
            @Param("i") DataCenter.Index index,
            @Param("fields") String fields);

    int upsertDependency(
            @Param("d") DataCenter.Dependency dependency,
            @Param("fields") String fields,
            @Param("actor") String actor);

    int deleteDependencies(
            @Param("kind") String kind, @Param("key") String key, @Param("actor") String actor);
}
