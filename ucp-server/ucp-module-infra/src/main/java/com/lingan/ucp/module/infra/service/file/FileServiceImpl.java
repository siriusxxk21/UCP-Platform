package com.lingan.ucp.module.infra.service.file;

import static cn.hutool.core.date.DatePattern.PURE_DATE_PATTERN;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

import com.google.common.annotations.VisibleForTesting;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.util.http.HttpUtils;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import com.lingan.ucp.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import com.lingan.ucp.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.dal.mysql.file.FileMapper;
import com.lingan.ucp.module.infra.framework.file.core.client.FileClient;
import com.lingan.ucp.module.infra.framework.file.core.utils.FilePathUtils;
import com.lingan.ucp.module.infra.framework.file.core.utils.FileTypeUtils;

import jakarta.annotation.Resource;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 文件 Service 实现类
 *
 * @author os
 */
@Service
@Slf4j
public class FileServiceImpl implements FileService {

    /**
     * 上传文件的前缀，是否包含日期（yyyyMMdd）
     *
     * <p>目的：按照日期，进行分目录
     */
    static boolean PATH_PREFIX_DATE_ENABLE = true;

    /**
     * 上传文件的后缀，是否启用
     *
     * <p>算法：当前时间戳（毫秒）+ 5 位随机数；目的是保证文件的唯一性，避免覆盖 定制：可按需调整成 UUID、或者其他方式
     *
     * <p>修改说明：从 false 改为 true，修复同名文件（如 image.png）在同一天内多次上传时被覆盖的问题。
     * 启用后路径格式为：yyyyMMdd/文件名_时间戳_随机数.ext，既保持日期目录结构，又保证文件名唯一。
     */
    static boolean PATH_SUFFIX_TIMESTAMP_ENABLE = true;

    /**
     * 后缀是否作为上级目录
     *
     * <p>true：{@code yyyyMMdd/<后缀>/原文件名.ext}；保留原文件名 false：{@code yyyyMMdd/原文件名_<后缀>.ext}；后缀拼到文件名
     *
     * <p>修改说明：设为 false，将时间戳后缀拼入文件名而非作为子目录， 保持扁平的日期目录结构（yyyyMMdd/文件名_时间戳_随机数.ext）。
     */
    static boolean PATH_SUFFIX_AS_DIRECTORY = false;

    @Resource private FileConfigService fileConfigService;

    @Resource private FileMapper fileMapper;

    @Override
    public PageResult<FileDO> getFilePage(FilePageReqVO pageReqVO) {
        return fileMapper.selectPage(pageReqVO);
    }

    @Override
    @SneakyThrows
    public FileDO createFile(byte[] content, String name, String directory, String type) {
        return createFile(content, name, directory, type, false);
    }

    @Override
    @SneakyThrows
    public FileDO createFile(
            Long configId, byte[] content, String name, String directory, String type) {
        return createFile(configId, content, name, directory, type, false);
    }

    @Override
    @SneakyThrows
    public FileDO createProtectedFile(byte[] content, String name, String directory, String type) {
        return createFile(content, name, directory, type, true);
    }

    @Override
    public FileDO createProtectedFile(
            InputStream content, long contentLength, String name, String directory, String type)
            throws Exception {
        FileClient client = fileConfigService.getMasterFileClient();
        Assert.notNull(client, "客户端(master) 不能为空");
        return createProtectedFile(client, content, contentLength, name, directory, type);
    }

    @Override
    public FileDO createProtectedFile(
            Long configId,
            InputStream content,
            long contentLength,
            String name,
            String directory,
            String type)
            throws Exception {
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        return createProtectedFile(client, content, contentLength, name, directory, type);
    }

    private FileDO createProtectedFile(
            FileClient client,
            InputStream content,
            long contentLength,
            String name,
            String directory,
            String type)
            throws Exception {
        // 1. 处理 name 的合法性，禁止携带目录路径；流式上传无法按内容回退命名，因此名称必填
        name = FilePathUtils.validateFileName(name);
        Assert.notEmpty(name, "文件名称不能为空");
        // 2. 处理 type 为空的情况：流式上传不能嗅探内容，按文件名推断
        if (StrUtil.isEmpty(type)) {
            type = FileTypeUtils.getMineType(name);
        }
        if (StrUtil.isEmpty(FileUtil.extName(name))) {
            String extension = FileTypeUtils.getExtension(type);
            if (StrUtil.isNotEmpty(extension)) {
                name = name + extension;
            }
        }
        // 3. 生成上传的 path，需要保证唯一
        String path = generateUploadPath(name, directory);
        // 4. 流式写入存储器
        String url = client.upload(content, contentLength, path, type);
        // 5. 保存到数据库
        FileDO fileDO =
                new FileDO()
                        .setConfigId(client.getId())
                        .setName(name)
                        .setPath(path)
                        .setUrl(url)
                        .setType(type)
                        .setSize(contentLength)
                        .setProtectedFlag(true);
        try {
            fileMapper.insert(fileDO);
        } catch (RuntimeException databaseFailure) {
            // 对象存储与元数据库不在同一事务：元数据登记失败时立即回收刚写入的对象。
            try {
                client.delete(path);
            } catch (Exception deleteFailure) {
                databaseFailure.addSuppressed(deleteFailure);
                log.error(
                        "[createProtectedFile][元数据登记失败且对象回收失败 configId={} path={}]",
                        client.getId(),
                        path,
                        deleteFailure);
            }
            throw databaseFailure;
        }
        return fileDO;
    }

    @SneakyThrows
    private FileDO createFile(
            byte[] content, String name, String directory, String type, boolean protectedFlag) {
        FileClient client = fileConfigService.getMasterFileClient();
        Assert.notNull(client, "客户端(master) 不能为空");
        return createFile(client.getId(), content, name, directory, type, protectedFlag);
    }

    private FileDO createFile(
            Long configId,
            byte[] content,
            String name,
            String directory,
            String type,
            boolean protectedFlag)
            throws Exception {
        // 1.1 处理 name 的合法性，禁止携带目录路径
        name = FilePathUtils.validateFileName(name);

        // 1.2.1 处理 type 为空的情况
        if (StrUtil.isEmpty(type)) {
            type = FileTypeUtils.getMineType(content, name);
        }
        // 1.2.2 处理 name 为空的情况
        if (StrUtil.isEmpty(name)) {
            name = DigestUtil.sha256Hex(content);
        }
        if (StrUtil.isEmpty(FileUtil.extName(name))) {
            // 如果 name 没有后缀 type，则补充后缀
            String extension = FileTypeUtils.getExtension(type);
            if (StrUtil.isNotEmpty(extension)) {
                name = name + extension;
            }
        }

        // 2.1 生成上传的 path，需要保证唯一
        String path = generateUploadPath(name, directory);
        // 2.2 上传到文件存储器
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        String url = client.upload(content, path, type);
        FileDO fileDO =
                new FileDO()
                        .setConfigId(client.getId())
                        .setName(name)
                        .setPath(path)
                        .setUrl(url)
                        .setType(type)
                        .setSize((long) content.length)
                        .setProtectedFlag(protectedFlag);
        // 3. 保存到数据库
        fileMapper.insert(fileDO);
        return fileDO;
    }

    @Override
    public boolean isProtectedFile(Long configId, String path) {
        return fileMapper.existsProtected(configId, path);
    }

    @VisibleForTesting
    String generateUploadPath(String name, String directory) {
        // 1.1 处理 name 和 directory 的合法性
        name = FilePathUtils.validateFileName(name);
        FilePathUtils.validatePath(name);
        FilePathUtils.validateDirectory(directory);
        // 1.2 生成前缀、后缀
        String prefix = null;
        if (PATH_PREFIX_DATE_ENABLE) {
            prefix = LocalDateTimeUtil.format(LocalDateTimeUtil.now(), PURE_DATE_PATTERN);
        }
        String suffix = null;
        if (PATH_SUFFIX_TIMESTAMP_ENABLE) {
            // 5 位随机数，避免同一毫秒内的重复
            suffix =
                    String.valueOf(System.currentTimeMillis())
                            + RandomUtil.randomInt(10000, 100000);
        }

        // 2.1 先拼接 suffix 后缀
        if (StrUtil.isNotEmpty(suffix)) {
            if (PATH_SUFFIX_AS_DIRECTORY) {
                name = suffix + StrUtil.SLASH + name;
            } else {
                String ext = FileUtil.extName(name);
                if (StrUtil.isNotEmpty(ext)) {
                    name =
                            FileUtil.mainName(name)
                                    + StrUtil.C_UNDERLINE
                                    + suffix
                                    + StrUtil.DOT
                                    + ext;
                } else {
                    name = name + StrUtil.C_UNDERLINE + suffix;
                }
            }
        }
        // 2.2 再拼接 prefix 前缀
        if (StrUtil.isNotEmpty(prefix)) {
            name = prefix + StrUtil.SLASH + name;
        }
        // 2.3 最后拼接 directory 目录
        if (StrUtil.isNotEmpty(directory)) {
            name = directory + StrUtil.SLASH + name;
        }
        return name;
    }

    @Override
    @SneakyThrows
    public FilePresignedUrlRespVO presignPutUrl(String name, String directory) {
        // 1. 生成上传的 path，需要保证唯一
        String path = generateUploadPath(name, directory);

        // 2. 获取文件预签名地址
        FileClient fileClient = fileConfigService.getMasterFileClient();
        String uploadUrl = fileClient.presignPutUrl(path);
        String visitUrl = fileClient.presignGetUrl(path, null);
        return new FilePresignedUrlRespVO()
                .setConfigId(fileClient.getId())
                .setPath(path)
                .setUploadUrl(uploadUrl)
                .setUrl(visitUrl);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        FileClient fileClient = fileConfigService.getMasterFileClient();
        return fileClient.presignGetUrl(url, expirationSeconds);
    }

    @Override
    public String presignGetUrl(Long configId, String url, Integer expirationSeconds) {
        FileClient fileClient = fileConfigService.getFileClient(configId);
        Assert.notNull(fileClient, "客户端({}) 不能为空", configId);
        return fileClient.presignGetUrl(url, expirationSeconds);
    }

    @Override
    public Long createFile(FileCreateReqVO createReqVO) {
        // 1.1 校验参数的合法性
        FilePathUtils.validatePath(createReqVO.getPath());
        createReqVO.setName(FilePathUtils.validateFileName(createReqVO.getName()));
        // 1.2 处理 URL 的合法性，移除 URL 中的查询参数（例如签名参数），保证 URL 的唯一性
        createReqVO.setUrl(HttpUtils.removeUrlQuery(createReqVO.getUrl())); // 目的：移除私有桶情况下，URL 的签名参数

        // 2. 保存到数据库
        FileDO file = BeanUtils.toBean(createReqVO, FileDO.class);
        fileMapper.insert(file);
        return file.getId();
    }

    @Override
    public FileDO getFile(Long id) {
        return validateFileExists(id);
    }

    @Override
    public List<FileDO> getFiles(List<Long> ids) {
        return fileMapper.selectList(FileDO::getId, ids);
    }

    @Override
    public void deleteFile(Long id) throws Exception {
        // 1.1 校验存在
        FileDO file = validateFileExists(id);
        // 1.2 校验路径合法性，避免误删文件存储器中的其他文件
        FilePathUtils.validatePath(file.getPath());

        // 2.1 从文件存储器中删除
        FileClient client = fileConfigService.getFileClient(file.getConfigId());
        Assert.notNull(client, "客户端({}) 不能为空", file.getConfigId());
        client.delete(file.getPath());

        // 2.2 删除记录
        fileMapper.deleteById(id);
    }

    @Override
    @SneakyThrows
    public void deleteFileList(List<Long> ids) {
        // 删除文件
        List<FileDO> files = fileMapper.selectByIds(ids);
        for (FileDO file : files) {
            FilePathUtils.validatePath(file.getPath());
            // 获取客户端
            FileClient client = fileConfigService.getFileClient(file.getConfigId());
            Assert.notNull(client, "客户端({}) 不能为空", file.getPath());
            // 删除文件
            client.delete(file.getPath());
        }

        // 删除记录
        fileMapper.deleteByIds(ids);
    }

    private FileDO validateFileExists(Long id) {
        FileDO fileDO = fileMapper.selectById(id);
        if (fileDO == null) {
            throw exception(FILE_NOT_EXISTS);
        }
        return fileDO;
    }

    @Override
    public byte[] getFileContent(Long configId, String path) throws Exception {
        // 1. 校验路径合法性
        FilePathUtils.validatePath(path);

        // 2.1 获取客户端
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        // 2.2 获取文件内容
        return client.getContent(path);
    }

    @Override
    public InputStream getFileContentStream(Long configId, String path, long offset)
            throws Exception {
        FilePathUtils.validatePath(path);
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        return client.getContentStream(path, offset);
    }

    @Override
    public Long getFileContentLength(Long configId, String path) throws Exception {
        FilePathUtils.validatePath(path);
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        return client.getContentLength(path);
    }
}
