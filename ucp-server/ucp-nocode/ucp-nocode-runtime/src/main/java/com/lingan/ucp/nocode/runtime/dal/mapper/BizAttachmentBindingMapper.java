package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizAttachmentBindingDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业务附件绑定 Mapper
 *
 * <p>身份与文件的组合唯一定位一条绑定；状态流转 SQL 统一在 XML 维护。
 */
@Mapper
public interface BizAttachmentBindingMapper extends BaseMapperX<BizAttachmentBindingDO> {

    /** 同一记录字段下的全部绑定（含 HISTORY），用于保存时按当前值做差集 */
    List<BizAttachmentBindingDO> selectListByIdentity(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("detailId") String detailId,
            @Param("rowId") String rowId,
            @Param("fieldId") String fieldId);

    /** 记录相关的全部附件绑定，记录删除时整组转 HISTORY */
    List<BizAttachmentBindingDO> selectListByRecord(
            @Param("objectId") String objectId, @Param("recordId") String recordId);

    /** 指定明细行的全部附件绑定（跨字段），行删除时整组转 HISTORY */
    List<BizAttachmentBindingDO> selectListByRow(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("detailId") String detailId,
            @Param("rowId") String rowId);

    int markHistory(@Param("ids") List<Long> ids, @Param("actor") String actor);
}
