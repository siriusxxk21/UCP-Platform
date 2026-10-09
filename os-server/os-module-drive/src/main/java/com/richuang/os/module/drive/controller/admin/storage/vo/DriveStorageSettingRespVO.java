package com.richuang.os.module.drive.controller.admin.storage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 网盘当前存储源与可选连接配置。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriveStorageSettingRespVO {

    private Long selectedConfigId;
    private Long effectiveConfigId;
    private List<DriveStorageOptionRespVO> options;
}
