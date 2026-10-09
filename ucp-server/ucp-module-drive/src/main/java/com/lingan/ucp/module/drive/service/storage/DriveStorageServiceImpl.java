package com.lingan.ucp.module.drive.service.storage;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.STORAGE_CONFIG_INVALID;

import com.lingan.ucp.module.drive.controller.admin.storage.vo.DriveStorageOptionRespVO;
import com.lingan.ucp.module.drive.controller.admin.storage.vo.DriveStorageSettingRespVO;
import com.lingan.ucp.module.drive.dal.dataobject.storage.DriveStorageSettingDO;
import com.lingan.ucp.module.drive.dal.mysql.storage.DriveStorageSettingMapper;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileConfigDO;
import com.lingan.ucp.module.infra.framework.file.core.enums.FileStorageEnum;
import com.lingan.ucp.module.infra.service.file.FileConfigService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.List;

/** 网盘存储源实现：只改变后续写入位置，已有文件始终按 infra_file.config_id 读取。 */
@Service
public class DriveStorageServiceImpl implements DriveStorageService {

    private static final long SETTING_ID = 1L;

    @Resource private DriveStorageSettingMapper settingMapper;
    @Resource private FileConfigService fileConfigService;

    @Override
    public DriveStorageSettingRespVO getSetting() {
        Long selectedId = getSelectedConfigId();
        List<FileConfigDO> configs = fileConfigService.getFileConfigList();
        List<DriveStorageOptionRespVO> options =
                configs.stream()
                        .filter(this::isSupported)
                        .map(
                                config ->
                                        new DriveStorageOptionRespVO(
                                                config.getId(),
                                                config.getName(),
                                                config.getStorage(),
                                                config.getMaster()))
                        .toList();
        Long effectiveId = selectedId;
        if (effectiveId == null) {
            effectiveId =
                    configs.stream()
                            .filter(config -> Boolean.TRUE.equals(config.getMaster()))
                            .map(FileConfigDO::getId)
                            .findFirst()
                            .orElse(null);
        }
        return new DriveStorageSettingRespVO(selectedId, effectiveId, options);
    }

    @Override
    public void updateSetting(Long configId, Long userId) {
        if (configId != null) {
            FileConfigDO config = fileConfigService.getFileConfig(configId);
            if (!isSupported(config)) {
                throw exception(STORAGE_CONFIG_INVALID);
            }
            // 先初始化客户端。S3 会检查 Bucket，配置错误时保留原选择。
            if (fileConfigService.getFileClient(configId) == null) {
                throw exception(STORAGE_CONFIG_INVALID);
            }
        }
        if (settingMapper.updateConfigId(configId, String.valueOf(userId)) != 1) {
            throw new IllegalStateException("网盘存储设置尚未完成数据库迁移");
        }
    }

    @Override
    public Long getSelectedConfigId() {
        DriveStorageSettingDO setting = settingMapper.selectById(SETTING_ID);
        if (setting == null) {
            throw new IllegalStateException("网盘存储设置尚未完成数据库迁移");
        }
        return setting.getConfigId();
    }

    private boolean isSupported(FileConfigDO config) {
        return config != null
                && (FileStorageEnum.LOCAL.getStorage().equals(config.getStorage())
                        || FileStorageEnum.S3.getStorage().equals(config.getStorage()));
    }
}
