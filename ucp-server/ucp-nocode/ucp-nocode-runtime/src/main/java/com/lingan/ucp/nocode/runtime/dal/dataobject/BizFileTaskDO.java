package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件操作任务 DO
 *
 * <p>物理删除补偿、目录整理等异步动作的持久状态；只登记已知操作，不扫描无归属文件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_file_task", schema = "public")
public class BizFileTaskDO extends BaseDO {
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskType;
    private Long fileId;

    /** 任务参数 JSON，由各任务类型自行解释 */
    private String payloadJson;

    /** PENDING 待执行、RUNNING 执行中、DONE 完成、FAILED 失败可重试 */
    private String state;

    private Integer attempts;
    private String lastError;
}
