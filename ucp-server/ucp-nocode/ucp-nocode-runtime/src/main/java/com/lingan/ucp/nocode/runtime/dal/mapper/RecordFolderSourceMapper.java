package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordFolderSourceDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 记录文件夹来源 Mapper
 *
 * <p>读取一律只看未删除的行；删除是逻辑删除（唯一约束与引用检查都按未删除行）。SQL 统一在 XML 维护。
 */
@Mapper
public interface RecordFolderSourceMapper extends BaseMapperX<RecordFolderSourceDO> {

    /** 对象的全部来源，按页签顺序 */
    List<RecordFolderSourceDO> selectByObject(@Param("objectId") String objectId);

    /** 未删除的来源；已删除或不存在返回 null */
    RecordFolderSourceDO selectActive(@Param("id") Long id);

    /** 锁住来源行（事务结束释放）：同一来源下的子文件夹建立串行化。已删除的来源同样锁住并返回，由调用方重新解析后判定 */
    RecordFolderSourceDO lock(@Param("id") Long id);

    /** 引用了这些来源的未删除来源（别的对象「用关联记录的文件夹」指到这里） */
    List<RecordFolderSourceDO> selectReferrers(@Param("ids") Collection<Long> ids);

    /** 对象上「记录保存时就建」的来源，按页签顺序 */
    List<RecordFolderSourceDO> selectOnSave(@Param("objectId") String objectId);

    /** 整行改写：种类变化时把不适用的列置空 */
    int updateSource(@Param("row") RecordFolderSourceDO row, @Param("actor") String actor);

    /** 逻辑删除 */
    int deleteSources(@Param("ids") Collection<Long> ids, @Param("actor") String actor);
}
