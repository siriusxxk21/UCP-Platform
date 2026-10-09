package com.lingan.ucp.module.system.legacy.service.impl;

import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.module.system.legacy.entity.SysAttachment;
import com.lingan.ucp.module.system.legacy.mapper.SysAttachmentMapper;
import com.lingan.ucp.module.system.legacy.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 附件服务实现
 */
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private final SysAttachmentMapper attachmentMapper;

    @Value("${file.upload.path:./uploads}")
    private String uploadPath;

    @Override
    public SysAttachment upload(MultipartFile file, String businessType, String businessId, String tenantId, String userId) {
        if (file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        // 生成文件名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString().replace("-", "") + extension;

        // 按日期分目录存储
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String relativePath = businessType + "/" + datePath + "/" + newFilename;
        String fullPath = uploadPath + "/" + relativePath;

        // 创建目录
        File destFile = new File(fullPath).getAbsoluteFile();
        if (!destFile.getParentFile().exists()) {
            destFile.getParentFile().mkdirs();
        }

        // 保存文件
        try {
            file.transferTo(destFile);
        } catch (IOException e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }

        // 保存记录
        SysAttachment attachment = new SysAttachment();
        attachment.setFileName(originalFilename);
        attachment.setFilePath(relativePath);
        attachment.setFileSize(file.getSize());
        attachment.setFileType(file.getContentType());
        attachment.setStorageType(1); // 本地存储
        attachment.setBusinessType(businessType);
        attachment.setBusinessId(businessId);
        attachment.setTenantId(tenantId);
        attachment.setCreateBy(userId);

        attachmentMapper.insert(attachment);
        return attachment;
    }

    @Override
    public SysAttachment getById(String id) {
        return attachmentMapper.selectById(id);
    }

    @Override
    public void delete(String id) {
        SysAttachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) {
            return;
        }

        // 删除物理文件
        File file = new File(uploadPath + "/" + attachment.getFilePath());
        if (file.exists()) {
            file.delete();
        }

        // 删除记录
        attachmentMapper.deleteById(id);
    }
}
