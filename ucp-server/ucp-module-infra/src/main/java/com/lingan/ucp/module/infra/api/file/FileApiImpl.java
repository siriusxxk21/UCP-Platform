package com.lingan.ucp.module.infra.api.file;

import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.service.file.FileService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.InputStream;

/**
 * 文件 API 实现类
 *
 * @author os
 */
@Service
@Validated
public class FileApiImpl implements FileApi {

    @Resource private FileService fileService;

    @Override
    public FileDO createFile(byte[] content, String name, String directory, String type) {
        return fileService.createFile(content, name, directory, type);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        return fileService.presignGetUrl(url, expirationSeconds);
    }

    @Override
    public byte[] getFileContent(Long fileId) {
        FileDO file = fileService.getFile(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在");
        }
        try {
            return fileService.getFileContent(file.getConfigId(), file.getPath());
        } catch (Exception exception) {
            throw new IllegalStateException("文件读取失败", exception);
        }
    }

    @Override
    public void deleteFile(Long fileId) {
        if (fileService.getFile(fileId) == null) {
            return;
        }
        try {
            fileService.deleteFile(fileId);
        } catch (Exception exception) {
            throw new IllegalStateException("文件删除失败", exception);
        }
    }

    @Override
    public FileDO createProtectedFile(byte[] content, String name, String directory, String type) {
        return fileService.createProtectedFile(content, name, directory, type);
    }

    @Override
    public FileDO createProtectedFile(
            InputStream content, long contentLength, String name, String directory, String type) {
        try {
            return fileService.createProtectedFile(content, contentLength, name, directory, type);
        } catch (Exception exception) {
            throw new IllegalStateException("文件上传失败", exception);
        }
    }

    @Override
    public FileDO createProtectedFile(
            Long configId,
            InputStream content,
            long contentLength,
            String name,
            String directory,
            String type) {
        try {
            return fileService.createProtectedFile(
                    configId, content, contentLength, name, directory, type);
        } catch (Exception exception) {
            throw new IllegalStateException("文件上传失败", exception);
        }
    }

    @Override
    public InputStream getFileContentStream(Long fileId, long offset) {
        FileDO file = fileService.getFile(fileId);
        if (file == null) {
            return null;
        }
        try {
            return fileService.getFileContentStream(file.getConfigId(), file.getPath(), offset);
        } catch (Exception exception) {
            throw new IllegalStateException("文件读取失败", exception);
        }
    }

    @Override
    public Long getFileContentLength(Long fileId) {
        FileDO file = fileService.getFile(fileId);
        if (file == null) {
            return null;
        }
        // 上传时已记录实际长度，省去每次 Range 请求探测存储；异常记录再回源核对
        if (file.getSize() != null && file.getSize() > 0) {
            return file.getSize();
        }
        try {
            return fileService.getFileContentLength(file.getConfigId(), file.getPath());
        } catch (Exception exception) {
            throw new IllegalStateException("文件长度读取失败", exception);
        }
    }
}
