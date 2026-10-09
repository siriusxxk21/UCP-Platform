package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据集VO
 */
@Data
public class DatasetVO {

    private String id;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 数据集编码
     */
    private String datasetCode;

    /**
     * 数据集名称
     */
    private String datasetName;

    /**
     * 数据集类型
     */
    private String datasetType;

    /**
     * 数据集描述
     */
    private String description;

    /**
     * 关联的数据源ID
     */
    private String datasourceId;

    /**
     * 数据源名称
     */
    private String datasourceName;

    /**
     * 数据源配置
     */
    private Object sourceConfig;

    /**
     * 参数定义
     */
    private Object params;

    /**
     * 字段定义
     */
    private Object fields;

    /**
     * 是否启用缓存
     */
    private Boolean cacheEnabled;

    /**
     * 缓存有效期
     */
    private Integer cacheTtl;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
