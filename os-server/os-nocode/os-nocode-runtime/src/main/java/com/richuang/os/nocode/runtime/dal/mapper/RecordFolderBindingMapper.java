package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderBindingDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 记录文件夹对应关系 Mapper
 *
 * <p>（对象、记录、来源、锚）定位一行；读取只看未删除的行。SQL 统一在 XML 维护。
 */
@Mapper
public interface RecordFolderBindingMapper extends BaseMapperX<RecordFolderBindingDO> {

    RecordFolderBindingDO selectBinding(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("sourceId") Long sourceId,
            @Param("anchorEntryId") Long anchorEntryId);

    /** 这些记录里，在某来源、某锚下已有对应关系的记录编号（补建时整页先筛一遍） */
    List<String> selectBoundRecordIds(
            @Param("objectId") String objectId,
            @Param("sourceId") Long sourceId,
            @Param("anchorEntryId") Long anchorEntryId,
            @Param("recordIds") Collection<String> recordIds);

    /** 逻辑删除：对应关系指向的节点已被彻底删除时作废旧行 */
    int deleteBinding(@Param("id") Long id, @Param("actor") String actor);
}
