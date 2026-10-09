package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.runtime.dal.dataobject.BizUploadSessionDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务附件上传会话 Mapper
 *
 * <p>会话状态流转与续期、过期扫描 SQL 统一在 XML 维护。
 */
@Mapper
public interface BizUploadSessionMapper extends BaseMapperX<BizUploadSessionDO> {

    /** 待保存文件的有效临时会话：按对象、字段与文件内容过滤，过期视为无效 */
    List<BizUploadSessionDO> selectTemporaryList(
            @Param("objectId") String objectId,
            @Param("fieldId") String fieldId,
            @Param("fileIds") List<Long> fileIds);

    /** 同一上传会话与业务字段内的有效幂等会话。 */
    BizUploadSessionDO selectIdempotent(
            @Param("userId") Long userId,
            @Param("objectId") String objectId,
            @Param("fieldId") String fieldId,
            @Param("sessionKey") String sessionKey,
            @Param("idempotencyKey") String idempotencyKey);

    /** 保存事务原子占用临时会话，与过期清理竞争时以状态更新结果为准。 */
    int claimBinding(@Param("ids") List<Long> ids, @Param("actor") String actor);

    int markBound(@Param("ids") List<Long> ids, @Param("actor") String actor);

    /** 有效编辑会话续期：仅本人该会话键下的临时上传顺延有效期 */
    int renew(
            @Param("sessionKey") String sessionKey,
            @Param("userId") Long userId,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("actor") String actor);

    /** 草稿引用续留：本人该对象下仍有效、文件命中给定集合的临时上传顺延有效期 */
    int renewByFiles(
            @Param("objectId") String objectId,
            @Param("fileIds") List<Long> fileIds,
            @Param("userId") Long userId,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("actor") String actor);

    /** 已过期的临时会话，供清理任务按批登记物理删除补偿 */
    List<BizUploadSessionDO> selectExpiredList(@Param("limit") Integer limit);

    int markExpired(@Param("ids") List<Long> ids, @Param("actor") String actor);

    /** 待清理会话：过期标记后的会话，供清理任务按批复核保留引用并删除内容 */
    List<BizUploadSessionDO> selectCleanupList(@Param("limit") Integer limit);

    /** 内容删除完成登记：仅已过期待清理会话转为已清理 */
    int markCleaned(@Param("ids") List<Long> ids, @Param("actor") String actor);

    /** 对象当前未完成的有效上传会话数，供发布预检提示在途上传 */
    int countTemporaryByObject(@Param("objectId") String objectId);
}
