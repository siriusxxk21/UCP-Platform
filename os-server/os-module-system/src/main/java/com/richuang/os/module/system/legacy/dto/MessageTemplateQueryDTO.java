package com.richuang.os.module.system.legacy.dto;

import lombok.Data;

/**
 * 消息模板查询DTO
 */
@Data
public class MessageTemplateQueryDTO {

    /**
     * 模板编码
     */
    private String templateCode;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 模板类型
     */
    private Integer templateType;

    /**
     * 模板分类
     */
    private String category;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 页码
     */
    private Integer pageNum = 1;

    /**
     * 每页大小
     */
    private Integer pageSize = 10;
}
