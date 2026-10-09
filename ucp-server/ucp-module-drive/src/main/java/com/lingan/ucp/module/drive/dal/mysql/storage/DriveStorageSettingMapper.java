package com.lingan.ucp.module.drive.dal.mysql.storage;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.module.drive.dal.dataobject.storage.DriveStorageSettingDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 网盘存储源设置 Mapper。 */
@Mapper
public interface DriveStorageSettingMapper extends BaseMapperX<DriveStorageSettingDO> {

    /** configId 允许为空，SQL 必须显式写 NULL 才能恢复跟随平台主配置。 */
    int updateConfigId(@Param("configId") Long configId, @Param("updater") String updater);
}
