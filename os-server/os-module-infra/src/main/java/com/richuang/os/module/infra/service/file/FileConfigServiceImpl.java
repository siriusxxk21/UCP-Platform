package com.richuang.os.module.infra.service.file;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.framework.common.util.cache.CacheUtils.buildAsyncReloadingCache;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_DELETE_FAIL_IN_USE;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_DELETE_FAIL_MASTER;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_LOCATION_CHANGE_IN_USE;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_NOT_EXISTS;
import static com.richuang.os.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_STORAGE_CHANGE_NOT_ALLOWED;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.framework.common.util.validation.ValidationUtils;
import com.richuang.os.module.infra.controller.admin.file.vo.config.FileConfigPageReqVO;
import com.richuang.os.module.infra.controller.admin.file.vo.config.FileConfigSaveReqVO;
import com.richuang.os.module.infra.convert.file.FileConfigConvert;
import com.richuang.os.module.infra.dal.dataobject.file.FileConfigDO;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.dal.mysql.file.FileConfigMapper;
import com.richuang.os.module.infra.dal.mysql.file.FileMapper;
import com.richuang.os.module.infra.framework.file.core.client.FileClient;
import com.richuang.os.module.infra.framework.file.core.client.FileClientConfig;
import com.richuang.os.module.infra.framework.file.core.client.FileClientFactory;
import com.richuang.os.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.richuang.os.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.richuang.os.module.infra.framework.file.core.enums.FileStorageEnum;

import jakarta.annotation.Resource;
import jakarta.validation.Validator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件配置 Service 实现类
 *
 * @author os
 */
@Service
@Validated
@Slf4j
public class FileConfigServiceImpl implements FileConfigService {

    private static final Long CACHE_MASTER_ID = 0L;

    /** {@link FileClient} 缓存，通过它异步刷新 fileClientFactory */
    @Getter
    private final LoadingCache<Long, FileClient> clientCache =
            buildAsyncReloadingCache(
                    Duration.ofSeconds(10L),
                    new CacheLoader<Long, FileClient>() {

                        @Override
                        public FileClient load(Long id) {
                            FileConfigDO config =
                                    Objects.equals(CACHE_MASTER_ID, id)
                                            ? fileConfigMapper.selectByMaster()
                                            : fileConfigMapper.selectById(id);
                            if (config != null) {
                                fileClientFactory.createOrUpdateFileClient(
                                        config.getId(), config.getStorage(), config.getConfig());
                            }
                            return fileClientFactory.getFileClient(
                                    null == config ? id : config.getId());
                        }
                    });

    @Resource private FileClientFactory fileClientFactory;

    @Resource private FileConfigMapper fileConfigMapper;
    @Resource private FileMapper fileMapper;

    @Resource private Validator validator;

    @Override
    public Long createFileConfig(FileConfigSaveReqVO createReqVO) {
        FileConfigDO fileConfig =
                FileConfigConvert.INSTANCE
                        .convert(createReqVO)
                        .setConfig(
                                parseClientConfig(
                                        createReqVO.getStorage(), createReqVO.getConfig()))
                        .setMaster(false); // 默认非 master
        fileConfigMapper.insert(fileConfig);
        return fileConfig.getId();
    }

    @Override
    public void updateFileConfig(FileConfigSaveReqVO updateReqVO) {
        // 校验存在
        FileConfigDO config = validateFileConfigExists(updateReqVO.getId());
        if (!Objects.equals(config.getStorage(), updateReqVO.getStorage())) {
            throw exception(FILE_CONFIG_STORAGE_CHANGE_NOT_ALLOWED);
        }
        FileClientConfig clientConfig =
                parseClientConfig(config.getStorage(), updateReqVO.getConfig());
        validateLocationUnchangedIfInUse(config, clientConfig);
        // 更新
        FileConfigDO updateObj =
                FileConfigConvert.INSTANCE.convert(updateReqVO).setConfig(clientConfig);
        fileConfigMapper.updateById(updateObj);

        // 清空缓存
        clearCache(config.getId(), config.getMaster());
    }

    /** 已有文件按原配置编号和相对路径回源；原地更换根目录、节点或桶会使历史文件失联。 */
    private void validateLocationUnchangedIfInUse(FileConfigDO current, FileClientConfig updated) {
        if (fileMapper.selectCount(FileDO::getConfigId, current.getId()) == 0) {
            return;
        }
        FileClientConfig original = current.getConfig();
        if (original instanceof LocalFileClientConfig local
                && updated instanceof LocalFileClientConfig nextLocal
                && !Objects.equals(local.getBasePath(), nextLocal.getBasePath())) {
            throw exception(FILE_CONFIG_LOCATION_CHANGE_IN_USE);
        }
        if (original instanceof S3FileClientConfig s3
                && updated instanceof S3FileClientConfig nextS3
                && (!Objects.equals(s3.getEndpoint(), nextS3.getEndpoint())
                        || !Objects.equals(s3.getBucket(), nextS3.getBucket()))) {
            throw exception(FILE_CONFIG_LOCATION_CHANGE_IN_USE);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFileConfigMaster(Long id) {
        // 校验存在
        validateFileConfigExists(id);
        // 更新其它为非 master
        fileConfigMapper.updateBatch(new FileConfigDO().setMaster(false));
        // 更新
        fileConfigMapper.updateById(new FileConfigDO().setId(id).setMaster(true));

        // 清空缓存
        clearCache(null, true);
    }

    private FileClientConfig parseClientConfig(Integer storage, Map<String, Object> config) {
        // 获取配置类
        Class<? extends FileClientConfig> configClass =
                FileStorageEnum.getByStorage(storage).getConfigClass();
        FileClientConfig clientConfig =
                JsonUtils.parseObject2(JsonUtils.toJsonString(config), configClass);
        // 参数校验
        ValidationUtils.validate(validator, clientConfig);
        // 设置参数
        return clientConfig;
    }

    @Override
    public void deleteFileConfig(Long id) {
        // 校验存在
        FileConfigDO config = validateFileConfigExists(id);
        if (Boolean.TRUE.equals(config.getMaster())) {
            throw exception(FILE_CONFIG_DELETE_FAIL_MASTER);
        }
        validateNoFileReferences(id);
        // 删除
        fileConfigMapper.deleteById(id);

        // 清空缓存
        clearCache(id, config.getMaster());
    }

    @Override
    public void deleteFileConfigList(List<Long> ids) {
        // 校验是否有主配置
        List<FileConfigDO> configs = fileConfigMapper.selectByIds(ids);
        for (FileConfigDO config : configs) {
            if (Boolean.TRUE.equals(config.getMaster())) {
                throw exception(FILE_CONFIG_DELETE_FAIL_MASTER);
            }
            validateNoFileReferences(config.getId());
        }

        // 批量删除
        fileConfigMapper.deleteByIds(ids);

        // 清空缓存
        ids.forEach(id -> clearCache(id, false));
    }

    /** 文件记录按写入时的配置编号回源，删除仍有引用的连接会使历史文件无法读取。 */
    private void validateNoFileReferences(Long configId) {
        if (fileMapper.selectCount(FileDO::getConfigId, configId) > 0) {
            throw exception(FILE_CONFIG_DELETE_FAIL_IN_USE);
        }
    }

    /**
     * 清空指定文件配置
     *
     * @param id 配置编号
     * @param master 是否主配置
     */
    private void clearCache(Long id, Boolean master) {
        if (id != null) {
            clientCache.invalidate(id);
        }
        if (Boolean.TRUE.equals(master)) {
            clientCache.invalidate(CACHE_MASTER_ID);
        }
    }

    private FileConfigDO validateFileConfigExists(Long id) {
        FileConfigDO config = fileConfigMapper.selectById(id);
        if (config == null) {
            throw exception(FILE_CONFIG_NOT_EXISTS);
        }
        return config;
    }

    @Override
    public FileConfigDO getFileConfig(Long id) {
        return fileConfigMapper.selectById(id);
    }

    @Override
    public List<FileConfigDO> getFileConfigList() {
        return fileConfigMapper.selectList();
    }

    @Override
    public PageResult<FileConfigDO> getFileConfigPage(FileConfigPageReqVO pageReqVO) {
        return fileConfigMapper.selectPage(pageReqVO);
    }

    @Override
    public String testFileConfig(Long id) throws Exception {
        // 校验存在
        validateFileConfigExists(id);
        // 上传文件
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        return getFileClient(id)
                .upload(
                        content,
                        "public" + StrUtil.SLASH + IdUtil.fastSimpleUUID() + ".jpg",
                        "image/jpeg");
    }

    @Override
    public FileClient getFileClient(Long id) {
        return clientCache.getUnchecked(id);
    }

    @Override
    public FileClient getMasterFileClient() {
        return clientCache.getUnchecked(CACHE_MASTER_ID);
    }

    @Override
    public Long getFileConfigIdByName(String name) {
        FileConfigDO config = fileConfigMapper.selectByName(name);
        return config != null ? config.getId() : null;
    }
}
