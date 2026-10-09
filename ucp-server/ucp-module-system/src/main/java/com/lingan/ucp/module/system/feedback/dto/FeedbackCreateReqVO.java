package com.lingan.ucp.module.system.feedback.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 问题反馈提交参数，图片与文本通过 multipart 一次提交。
 */
@Data
public class FeedbackCreateReqVO {
    private String type;
    private String title;
    private String description;
    private String pagePath;
    private String pageTitle;
    private String buildCommit;
    private Long projectId;
    private String projectName;
    private MultipartFile[] images;
}
