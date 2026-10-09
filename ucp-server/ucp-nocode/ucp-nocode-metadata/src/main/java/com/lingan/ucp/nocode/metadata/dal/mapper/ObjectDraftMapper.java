package com.lingan.ucp.nocode.metadata.dal.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectTableDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectVersionDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对象草稿聚合的持久化入口，复用底座 Mapper 扫描、BaseMapperX 和 MyBatis 会话。
 *
 * <p>普通对象插入使用 BaseMapperX；跨表读取、原子修订、字段 upsert 和对象命名互斥在 XML 中维护。物理数据库目录通过底座 DatabaseMetadataReader
 * 读取。所有业务参数使用预编译绑定，不接收调用方 SQL。
 */
@Mapper
public interface ObjectDraftMapper extends BaseMapperX<NocodeObjectDO> {

    /** 由底座分页插件生成分页和总数查询。 */
    IPage<ObjectDraftHeadDO> selectObjectPage(
            IPage<ObjectDraftHeadDO> page, @Param("name") String name, @Param("code") String code);

    /** 普通读取持有共享锁；只读事务使用快照；保存持有排他锁，确保对象头与字段来自同一修订。 */
    ObjectDraftHeadDO selectHead(@Param("id") long id, @Param("writeLock") boolean writeLock);

    List<FieldDefinition> selectFields(@Param("versionId") long versionId);

    /** 序列和锁查询禁用缓存，不能把上次 nextval 结果复用为新 ID。 */
    long nextStableId();

    void lockTableName(@Param("name") String name);

    boolean tableNameClaimed(@Param("name") String name, @Param("objectId") Long objectId);

    /** 显式比较修订号，保证对象整体保存只有一个并发请求成功。 */
    int updateDraftHead(
            @Param("object") NocodeObjectDO object, @Param("expectedVersion") int expectedVersion);

    int insertVersion(@Param("version") NocodeObjectVersionDO version);

    int insertMainTable(@Param("table") NocodeObjectTableDO table);

    int updateVersion(
            @Param("versionId") long versionId,
            @Param("schema") String schema,
            @Param("checksum") String checksum,
            @Param("revision") int revision);

    int updateMainTable(@Param("tableId") long tableId, @Param("name") String name);

    int deleteField(@Param("versionId") long versionId, @Param("stableId") long stableId);

    int upsertField(
            @Param("versionId") long versionId,
            @Param("tableId") long tableId,
            @Param("field") FieldDefinition field);

    /** 与草稿保存共用事务，只有整次保存成功才能留下成功审计。 */
    int insertAudit(
            @Param("traceId") String traceId,
            @Param("actorId") long actorId,
            @Param("operation") String operation,
            @Param("objectId") long objectId,
            @Param("detail") String detail);
}
