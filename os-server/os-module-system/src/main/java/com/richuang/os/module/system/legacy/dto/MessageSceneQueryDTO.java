package com.richuang.os.module.system.legacy.dto;

import lombok.Data;

/**
 * 业务场景查询DTO
 */
@Data
public class MessageSceneQueryDTO {

    /**
     * 场景编码
     */
    private String sceneCode;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 场景类型
     */
    private Integer sceneType;

    /**
     * 场景分类
     */
    private String category;

    /**
     * 关联模板ID
     */
    private String templateId;

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
