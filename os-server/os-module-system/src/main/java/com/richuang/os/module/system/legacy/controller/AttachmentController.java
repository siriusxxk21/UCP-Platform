package com.richuang.os.module.system.legacy.controller;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.legacy.entity.SysAttachment;
import com.richuang.os.module.system.legacy.service.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件控制器（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/attachment")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @Value("${file.upload.path:./uploads}")
    private String uploadPath;

    /**
     * 上传附件
     */
    @PostMapping("/upload")
    public Result<SysAttachment> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "businessType", required = false) String businessType,
            @RequestParam(value = "businessId", required = false) String businessId,
            HttpServletRequest request) {

        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");

        SysAttachment attachment = attachmentService.upload(file, businessType, businessId, tenantId, userId);
        return Result.success(attachment);
    }

    /**
     * 预览附件（图片等，inline模式）
     */
    @GetMapping("/preview/{id}")
    public ResponseEntity<Resource> preview(@PathVariable String id) {
        SysAttachment attachment = attachmentService.getById(id);
        if (attachment == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(uploadPath + "/" + attachment.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.getFileName() + "\"")
                .body(resource);
    }

    /**
     * 下载附件
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        SysAttachment attachment = attachmentService.getById(id);
        if (attachment == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(uploadPath + "/" + attachment.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String encodedFilename = URLEncoder.encode(attachment.getFileName(), StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"")
                .body(resource);
    }

    /**
     * 删除附件
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        attachmentService.delete(id);
        return Result.success();
    }
}
