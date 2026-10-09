package com.richuang.os.module.drive.controller.admin.storage;

import static com.richuang.os.framework.common.pojo.Result.success;
import static com.richuang.os.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.drive.controller.admin.storage.vo.DriveStorageSettingRespVO;
import com.richuang.os.module.drive.controller.admin.storage.vo.DriveStorageSettingUpdateReqVO;
import com.richuang.os.module.drive.service.storage.DriveStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 网盘存储配置入口，与平台文件主配置独立。 */
@Tag(name = "管理后台 - 网盘存储配置")
@RestController
@RequestMapping("/drive/storage")
public class DriveStorageController {

    @Resource private DriveStorageService storageService;

    @GetMapping("/get")
    @Operation(summary = "获取网盘存储配置及可选存储源")
    @PreAuthorize("@ss.hasPermission('drive:storage:query')")
    public Result<DriveStorageSettingRespVO> getSetting() {
        return success(storageService.getSetting());
    }

    @PutMapping("/update")
    @Operation(summary = "切换网盘后续写入的存储源")
    @PreAuthorize("@ss.hasPermission('drive:storage:update')")
    public Result<Boolean> updateSetting(@Valid @RequestBody DriveStorageSettingUpdateReqVO reqVO) {
        storageService.updateSetting(reqVO.getConfigId(), getLoginUserId());
        return success(true);
    }
}
