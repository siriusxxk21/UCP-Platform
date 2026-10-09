package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizDirectoryBindingDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业务目录绑定 Mapper
 *
 * <p>身份四元组（对象/记录/明细/行）加字段 ID 定位一条绑定；SQL 统一在 XML 维护。
 */
@Mapper
public interface BizDirectoryBindingMapper extends BaseMapperX<BizDirectoryBindingDO> {

    /** 事务级锁定稳定业务身份，避免并发首建产生多个受管叶目录。 */
    int lockIdentity(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("detailId") String detailId,
            @Param("rowId") String rowId,
            @Param("fieldId") String fieldId);

    BizDirectoryBindingDO selectByIdentity(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("detailId") String detailId,
            @Param("rowId") String rowId,
            @Param("fieldId") String fieldId);

    /** 记录相关的全部目录绑定（含明细区/明细行/字段层级），目录调整与删除时整组处理 */
    List<BizDirectoryBindingDO> selectListByRecord(
            @Param("objectId") String objectId, @Param("recordId") String recordId);

    /** 指定明细行的全部目录绑定（明细行目录与字段目录），行删除时整组清理 */
    List<BizDirectoryBindingDO> selectListByRow(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("detailId") String detailId,
            @Param("rowId") String rowId);

    /** 已按规则归档的记录数（记录级目录绑定），供发布预检展示规则变更影响范围 */
    int countRecordDirectories(@Param("objectId") String objectId);
}
