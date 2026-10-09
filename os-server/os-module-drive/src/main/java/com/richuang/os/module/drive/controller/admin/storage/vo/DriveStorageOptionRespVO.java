package com.richuang.os.module.drive.controller.admin.storage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 可供网盘选择的存储配置，不暴露密钥和连接参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriveStorageOptionRespVO {

    private Long id;
    private String name;
    private Integer storage;
    private Boolean master;
}
