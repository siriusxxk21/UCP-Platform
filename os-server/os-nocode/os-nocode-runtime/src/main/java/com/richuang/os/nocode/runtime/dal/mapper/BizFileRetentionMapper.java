package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.runtime.dal.dataobject.BizFileRetentionDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文件保留引用 Mapper；读写经 BaseMapperX，按（文件、持有者）唯一约束幂等登记。 */
@Mapper
public interface BizFileRetentionMapper extends BaseMapperX<BizFileRetentionDO> {

    /** 幂等登记一批文件到同一持有者；已存在的组合保持不变 */
    int register(
            @Param("fileIds") List<Long> fileIds,
            @Param("holderType") String holderType,
            @Param("holderId") String holderId,
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("actor") String actor);

    /** 物理删除持有者的全部登记；唯一约束不含 deleted，释放不能走逻辑删除 */
    int deleteByHolder(@Param("holderType") String holderType, @Param("holderId") String holderId);

    /** 入参文件中被有效持有者引用的子集，供物理清理前复核 */
    List<Long> selectEffectiveFileIds(@Param("fileIds") List<Long> fileIds);
}
