package com.lingan.ucp.module.infra.service.file;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lingan.ucp.module.infra.controller.admin.file.vo.config.FileConfigSaveReqVO;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileConfigDO;
import com.lingan.ucp.module.infra.dal.mysql.file.FileConfigMapper;
import com.lingan.ucp.module.infra.dal.mysql.file.FileMapper;
import com.lingan.ucp.module.infra.framework.file.core.client.FileClientConfig;
import com.lingan.ucp.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.lingan.ucp.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.lingan.ucp.module.infra.framework.file.core.enums.FileStorageEnum;

import jakarta.validation.Validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

/** 验证已写入文件的连接参数可维护，但不能原地改变历史文件的存储位置。 */
class FileConfigServiceImplTest {

    private final FileConfigMapper configMapper = mock(FileConfigMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final FileConfigServiceImpl service = new FileConfigServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "fileConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(
                service, "validator", Validation.buildDefaultValidatorFactory().getValidator());
        when(fileMapper.selectCount(any(SFunction.class), any())).thenReturn(1L);
    }

    @Test
    void refusesLocalBasePathChangeWhenFilesExist() {
        LocalFileClientConfig original = new LocalFileClientConfig();
        original.setBasePath("/data/old");
        original.setDomain("https://files.example.com");
        when(configMapper.selectById(1L))
                .thenReturn(config(1L, FileStorageEnum.LOCAL.getStorage(), original));

        FileConfigSaveReqVO request = request(1L, FileStorageEnum.LOCAL.getStorage());
        request.setConfig(Map.of("basePath", "/data/new", "domain", "https://files.example.com"));

        assertThrows(RuntimeException.class, () -> service.updateFileConfig(request));
        verify(configMapper, never()).updateById(any(FileConfigDO.class));
    }

    @Test
    void refusesS3BucketChangeWhenFilesExist() {
        S3FileClientConfig original = new S3FileClientConfig();
        original.setEndpoint("http://localhost:9000");
        original.setBucket("old-bucket");
        when(configMapper.selectById(2L))
                .thenReturn(config(2L, FileStorageEnum.S3.getStorage(), original));

        FileConfigSaveReqVO request = request(2L, FileStorageEnum.S3.getStorage());
        request.setConfig(
                Map.of(
                        "endpoint", "http://localhost:9000",
                        "bucket", "new-bucket",
                        "accessKey", "key",
                        "accessSecret", "secret",
                        "enablePathStyleAccess", true,
                        "enablePublicAccess", false));

        assertThrows(RuntimeException.class, () -> service.updateFileConfig(request));
        verify(configMapper, never()).updateById(any(FileConfigDO.class));
    }

    @Test
    void allowsLocalDomainChangeWithoutMovingFiles() {
        LocalFileClientConfig original = new LocalFileClientConfig();
        original.setBasePath("/data/files");
        original.setDomain("https://old.example.com");
        when(configMapper.selectById(3L))
                .thenReturn(config(3L, FileStorageEnum.LOCAL.getStorage(), original));

        FileConfigSaveReqVO request = request(3L, FileStorageEnum.LOCAL.getStorage());
        request.setConfig(Map.of("basePath", "/data/files", "domain", "https://new.example.com"));

        service.updateFileConfig(request);
        verify(configMapper).updateById(any(FileConfigDO.class));
    }

    @Test
    void allowsS3CredentialChangeWithoutMovingFiles() {
        S3FileClientConfig original = new S3FileClientConfig();
        original.setEndpoint("http://localhost:9000");
        original.setBucket("files");
        original.setAccessKey("old-key");
        original.setAccessSecret("old-secret");
        original.setEnablePathStyleAccess(true);
        original.setEnablePublicAccess(false);
        when(configMapper.selectById(4L))
                .thenReturn(config(4L, FileStorageEnum.S3.getStorage(), original));

        FileConfigSaveReqVO request = request(4L, FileStorageEnum.S3.getStorage());
        request.setConfig(
                Map.of(
                        "endpoint", "http://localhost:9000",
                        "bucket", "files",
                        "accessKey", "new-key",
                        "accessSecret", "new-secret",
                        "enablePathStyleAccess", true,
                        "enablePublicAccess", false));

        service.updateFileConfig(request);
        verify(configMapper).updateById(any(FileConfigDO.class));
    }

    private FileConfigDO config(Long id, Integer storage, FileClientConfig clientConfig) {
        FileConfigDO config = new FileConfigDO();
        config.setId(id);
        config.setStorage(storage);
        config.setConfig(clientConfig);
        return config;
    }

    private FileConfigSaveReqVO request(Long id, Integer storage) {
        FileConfigSaveReqVO request = new FileConfigSaveReqVO();
        request.setId(id);
        request.setName("测试连接");
        request.setStorage(storage);
        return request;
    }
}
