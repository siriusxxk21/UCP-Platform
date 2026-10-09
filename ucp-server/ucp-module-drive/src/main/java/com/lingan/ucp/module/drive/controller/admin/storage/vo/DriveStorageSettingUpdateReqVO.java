package com.lingan.ucp.module.drive.controller.admin.storage.vo;

import lombok.Data;

/** 选择网盘后续写入使用的存储配置；空值表示跟随平台主配置。 */
@Data
public class DriveStorageSettingUpdateReqVO {

    private Long configId;
}
