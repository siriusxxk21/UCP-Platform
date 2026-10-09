package com.richuang.os.module.system.legacy.service;

import com.richuang.os.module.system.legacy.entity.SysAttachment;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件服务接口
 */
public interface AttachmentService {

    /**
     * 上传附件
     */
    SysAttachment upload(MultipartFile file, String businessType, String businessId, String tenantId, String userId);

    /**
     * 获取附件
     */
    SysAttachment getById(String id);

    /**
     * 删除附件
     */
    void delete(String id);
}
