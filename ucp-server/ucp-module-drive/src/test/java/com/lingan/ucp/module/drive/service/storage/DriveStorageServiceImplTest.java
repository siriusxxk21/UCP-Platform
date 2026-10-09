package com.lingan.ucp.module.drive.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.module.drive.controller.admin.storage.vo.DriveStorageSettingRespVO;
import com.lingan.ucp.module.drive.controller.admin.storage.vo.DriveStorageSettingUpdateReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.storage.DriveStorageSettingDO;
import com.lingan.ucp.module.drive.dal.mysql.storage.DriveStorageSettingMapper;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileConfigDO;
import com.lingan.ucp.module.infra.framework.file.core.client.FileClient;
import com.lingan.ucp.module.infra.framework.file.core.enums.FileStorageEnum;
import com.lingan.ucp.module.infra.service.file.FileConfigService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

/** 验证网盘独立选择存储源，不改动平台主配置。 */
class DriveStorageServiceImplTest {

    private final DriveStorageSettingMapper settingMapper = mock(DriveStorageSettingMapper.class);
    private final FileConfigService fileConfigService = mock(FileConfigService.class);
    private final DriveStorageServiceImpl service = new DriveStorageServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "settingMapper", settingMapper);
        ReflectionTestUtils.setField(service, "fileConfigService", fileConfigService);
        DriveStorageSettingDO setting = new DriveStorageSettingDO();
        setting.setId(1L);
        when(settingMapper.selectById(1L)).thenReturn(setting);
    }

    @Test
    void followsPlatformMasterUntilExplicitSelection() {
        FileConfigDO master = new FileConfigDO();
        master.setId(11L);
        master.setStorage(FileStorageEnum.LOCAL.getStorage());
        master.setMaster(true);
        when(fileConfigService.getFileConfigList()).thenReturn(List.of(master));

        assertNull(service.getSelectedConfigId());
        assertEquals(11L, service.getSetting().getEffectiveConfigId());
    }

    @Test
    void acceptsS3ConfigAndCanReturnToMaster() {
        FileConfigDO s3 = new FileConfigDO();
        s3.setId(21L);
        s3.setStorage(FileStorageEnum.S3.getStorage());
        when(fileConfigService.getFileConfig(21L)).thenReturn(s3);
        when(fileConfigService.getFileClient(21L)).thenReturn(mock(FileClient.class));
        when(settingMapper.updateConfigId(21L, "7")).thenReturn(1);
        when(settingMapper.updateConfigId(null, "7")).thenReturn(1);

        service.updateSetting(21L, 7L);
        service.updateSetting(null, 7L);
        verify(settingMapper).updateConfigId(21L, "7");
        verify(settingMapper).updateConfigId(null, "7");
    }

    @Test
    void rejectsUnsupportedStorageWithoutChangingSelection() {
        FileConfigDO db = new FileConfigDO();
        db.setId(31L);
        db.setStorage(FileStorageEnum.DB.getStorage());
        when(fileConfigService.getFileConfig(31L)).thenReturn(db);

        assertThrows(RuntimeException.class, () -> service.updateSetting(31L, 7L));
        verify(settingMapper, never()).updateConfigId(31L, "7");
    }

    @Test
    void storageDtoKeepsRequestAndResponseJsonFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DriveStorageSettingUpdateReqVO request =
                mapper.readValue("{\"configId\":21}", DriveStorageSettingUpdateReqVO.class);
        assertEquals(21L, request.getConfigId());

        DriveStorageSettingRespVO response = new DriveStorageSettingRespVO(null, 21L, List.of());
        String json = mapper.writeValueAsString(response);
        assertTrue(json.contains("\"selectedConfigId\":null"));
        assertTrue(json.contains("\"effectiveConfigId\":21"));
        assertTrue(json.contains("\"options\":[]"));
    }
}
