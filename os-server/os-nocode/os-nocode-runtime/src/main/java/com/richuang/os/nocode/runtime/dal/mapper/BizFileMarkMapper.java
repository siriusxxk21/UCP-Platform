package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.runtime.dal.dataobject.BizFileMarkDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业务文件用户标记 Mapper
 *
 * <p>收藏与最近访问的幂等写入与列表 SQL 统一在 XML 维护，逻辑删除语义与唯一约束在 SQL 内闭合。
 */
@Mapper
public interface BizFileMarkMapper extends BaseMapperX<BizFileMarkDO> {

    /** 收藏登记：重复收藏幂等，保留首次收藏时间 */
    int insertFavorite(
            @Param("userId") Long userId,
            @Param("objectId") String objectId,
            @Param("entryId") Long entryId,
            @Param("actor") String actor);

    /** 取消收藏：逻辑删除，再次收藏重新登记 */
    int deleteFavorite(
            @Param("userId") Long userId,
            @Param("entryId") Long entryId,
            @Param("actor") String actor);

    /** 最近访问登记：同一节点只保留一条，重复访问顺延访问时间 */
    int upsertRecent(
            @Param("userId") Long userId,
            @Param("objectId") String objectId,
            @Param("entryId") Long entryId,
            @Param("actor") String actor);

    /** 本人对对象内某类标记的节点编号，按标记时间倒序；调用方据此在授权链内重取可见文件 */
    List<Long> selectEntryIds(
            @Param("userId") Long userId,
            @Param("objectId") String objectId,
            @Param("markType") String markType,
            @Param("limit") Integer limit);
}
