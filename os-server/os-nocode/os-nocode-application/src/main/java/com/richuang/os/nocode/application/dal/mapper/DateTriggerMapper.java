package com.richuang.os.nocode.application.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.application.dal.dataobject.DateTriggerDoneDO;
import com.richuang.os.nocode.application.dal.dataobject.DateTriggerStateDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** 按日期自动执行的账本：规则封账日与逐条处理记录。写入同时负责在保存点回滚后清掉本会话的一级缓存。 */
@Mapper
public interface DateTriggerMapper extends BaseMapperX<DateTriggerStateDO> {
    List<DateTriggerStateDO> states(@Param("app") Long app);

    /** 生效：没有账本时新建；账本未生效时改为生效并把封账日改成 closed。已生效的账本不动，返回 0。 */
    int arm(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("closed") LocalDate closed,
            @Param("actor") String actor);

    /** 应用里不在 keep 中的账本一律失效；keep 为空表示整个应用失效。 */
    int disarm(
            @Param("app") long app,
            @Param("keep") Collection<String> keep,
            @Param("actor") String actor);

    /** 封账：封账日只增不减。 */
    int close(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date);

    int scanned(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date,
            @Param("trigger") String trigger,
            @Param("error") String error);

    /** 认领一条来源记录：插入 RUNNING 行；已被（别的实例 / 别的轮次）处理过返回 0。与写目标同一事务提交。 */
    int claim(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date,
            @Param("source") String source,
            @Param("actor") String actor);

    int finish(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date,
            @Param("source") String source,
            @Param("outcome") String outcome,
            @Param("targets") int targets);

    /** 失败另起事务记一行；已有行（别人刚处理完）时不覆盖。 */
    int failed(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date,
            @Param("source") String source,
            @Param("message") String message,
            @Param("actor") String actor);

    List<String> doneSources(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date);

    int clearFailed(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date);

    int purge(@Param("before") LocalDate before);

    /** 某规则某业务日的处理记录（失败在前，最多 limit 条）。 */
    List<DateTriggerDoneDO> doneRows(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date,
            @Param("limit") int limit);

    /** 某规则某业务日各结果的条数：每行「结果:条数」。 */
    List<String> outcomeCounts(
            @Param("app") long app,
            @Param("resource") String resource,
            @Param("date") LocalDate date);

    /** 应用创建人：系统执行时用作留痕的操作者（不参与权限判断）。 */
    String creator(@Param("app") long app);
}
