package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.nocode.runtime.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 入口元数据与贡献收据；调用方持有任务根锁，SQL 集中在 XML。 */
@Mapper
public interface TaskWorkEntryMapper {
    /** 与办理保存共用根锁，先封存旧进程遗留的空快照再调整实例单价。 */
    void freezeRules(@Param("root") String root);

    void changeWorkRule(
            @Param("id") String id, @Param("rule") String rule, @Param("actor") String actor);

    List<TaskWorkRecordDO> requestRecords(
            @Param("request") String request, @Param("actor") String actor);

    void unchanged(@Param("id") String id, @Param("actor") String actor);

    void applied(
            @Param("request") String request,
            @Param("snapshot") String snapshot,
            @Param("revision") String revision,
            @Param("actor") String actor);

    TaskWorkRecordDO handlingContribution(
            @Param("request") String request, @Param("actor") String actor);

    String approvedRecord(@Param("id") String id);

    void supersede(
            @Param("id") String id, @Param("next") String next, @Param("actor") String actor);

    TaskWorkEntryTemplateDO template(@Param("id") String id, @Param("version") int version);

    void publish(@Param("row") TaskWorkEntryTemplateDO row, @Param("actor") String actor);

    List<TaskWorkEntryDO> entries(@Param("task") String task);

    TaskWorkEntryDO entry(@Param("task") String task, @Param("key") String key);

    void saveEntry(@Param("row") TaskWorkEntryDO row, @Param("actor") String actor);

    void removeEntry(@Param("id") String id, @Param("actor") String actor);

    List<TaskWorkRecordDO> records(@Param("dataset") String dataset);

    TaskWorkRecordDO receipt(@Param("actor") String actor, @Param("key") String key);

    TaskWorkRecordDO record(@Param("id") String id);

    void saveRecord(@Param("row") TaskWorkRecordDO row, @Param("actor") String actor);
}
