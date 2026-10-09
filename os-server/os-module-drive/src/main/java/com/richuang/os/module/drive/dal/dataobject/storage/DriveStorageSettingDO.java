package com.richuang.os.module.drive.dal.dataobject.storage;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 网盘存储源设置。固定编号 1，未选择时沿用文件底座主配置。 */
@TableName("drive_storage_setting")
@Data
@EqualsAndHashCode(callSuper = true)
public class DriveStorageSettingDO extends BaseDO {

    private Long id;
    private Long configId;
}
