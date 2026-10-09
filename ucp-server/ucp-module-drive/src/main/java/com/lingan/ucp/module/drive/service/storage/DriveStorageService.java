package com.lingan.ucp.module.drive.service.storage;

import com.lingan.ucp.module.drive.controller.admin.storage.vo.DriveStorageSettingRespVO;

/** 网盘存储源服务。 */
public interface DriveStorageService {

    DriveStorageSettingRespVO getSetting();

    void updateSetting(Long configId, Long userId);

    /** 获取网盘当前选定的配置编号；空值表示沿用底座主配置。 */
    Long getSelectedConfigId();
}
