package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizFileTaskDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文件操作任务 Mapper；P1 仅登记任务行，执行与重试由清理任务驱动。 */
@Mapper
public interface BizFileTaskMapper extends BaseMapperX<BizFileTaskDO> {

    /** 登记一条失败补偿任务（首次失败）：内容编号与任务类型定位后续重试对象 */
    int insertTask(BizFileTaskDO task);

    /** 该文件对应任务类型下仍未完成的操作，供失败重试复用同一行 */
    BizFileTaskDO selectOpenByFile(
            @Param("taskType") String taskType, @Param("fileId") Long fileId);

    /** 按时间取未完成的可重试任务。 */
    List<BizFileTaskDO> selectOpenList(
            @Param("taskType") String taskType, @Param("limit") Integer limit);

    /** 记录一次失败：累加尝试次数并保留最后一次错误摘要 */
    int markFailed(
            @Param("id") Long id,
            @Param("lastError") String lastError,
            @Param("actor") String actor);

    /** 补偿完成：关闭该文件下未完成的任务行 */
    int markDone(
            @Param("taskType") String taskType,
            @Param("fileId") Long fileId,
            @Param("actor") String actor);
}
