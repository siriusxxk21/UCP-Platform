package com.lingan.ucp.nocode.application.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeLinkageTriggerDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 数据联动自动更新的反向索引；行在应用发布事务内登记，之后只读。 */
@Mapper
public interface LinkageTriggerMapper extends BaseMapperX<NocodeLinkageTriggerDO> {
    int create(@Param("row") NocodeLinkageTriggerDO row, @Param("actor") String actor);

    /** 某应用某个发布版本登记的全部行，按目标对象、目标字段排序。 */
    List<NocodeLinkageTriggerDO> ofApplication(
            @Param("app") long app, @Param("version") int version);

    /** 某应用某个发布版本里，以给定对象为来源的行。 */
    List<NocodeLinkageTriggerDO> bySource(
            @Param("app") long app, @Param("version") int version, @Param("source") long source);

    /** 对象是否是任何一条索引行（任何应用、任何版本）的来源或目标；object 为 null 时问「是否存在任何索引行」。 */
    boolean participates(@Param("object") Long object);

    /** 仅供按发布快照重建：物理删除一个应用版本的行（唯一约束不含 deleted，不能逻辑删除后重插）。 */
    int purge(@Param("app") long app, @Param("version") int version);

    /**
     * 其它启用中、已发布且固定了给定对象的应用：每行一个 JSON，含 applicationId、name、version 与固定的对象 ID 列表。 低频（总览接口）使用，⛔
     * 不要用在保存路径上。
     */
    List<String> activePinning(@Param("object") long object, @Param("except") long except);
}
