package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordHistoryDO;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 表级事务锁同时保护首次快照和写入顺序；所有外部值通过参数绑定。 */
@Mapper
public interface RecordHistoryMapper extends BaseMapperX<RecordHistoryDO> {
    String lock(String object);

    /** 仅补充本事务尚未提交的真实历史来源；不按时间或相邻任务收据猜测前后值。 */
    int bindTaskContribution(
            @Param("checkpoint") long checkpoint,
            @Param("app") String app,
            @Param("object") String object,
            @Param("record") String record,
            @Param("actor") String actor,
            @Param("contribution") String contribution);

    /** 单次贡献的主记录历史，可含同一事务内联动产生的连续变更。 */
    List<String> contributionEvents(
            @Param("contribution") String contribution,
            @Param("app") String app,
            @Param("object") String object,
            @Param("record") String record);

    /** 旧贡献只按唯一保存修订定位；结果不唯一时调用方必须标记未知。 */
    List<String> revisionEvents(
            @Param("app") String app,
            @Param("object") String object,
            @Param("record") String record,
            @Param("revision") String revision);

    /** 只观察当前数据库事务，不能把同一操作者其他窗口的保存串入联合事件。 */
    long transactionCheckpoint();

    List<String> transactionEvents(
            @Param("checkpoint") long checkpoint,
            @Param("app") String app,
            @Param("actor") String actor);

    String coverage(String object);

    void start(@Param("object") String object, @Param("actor") String actor);

    void append(
            @Param("object") String object,
            @Param("record") String record,
            @Param("app") String app,
            @Param("operation") String operation,
            @Param("before") String before,
            @Param("after") String after,
            @Param("definition") String definition,
            @Param("actor") String actor,
            @Param("operationId") String operationId,
            @Param("policyVersion") String policyVersion,
            @Param("source") String source);

    /** 初次历史覆盖在数据库内生成整表快照，不受运行页面的分页和字段范围裁剪。 */
    void baseline(
            @Param("statement") com.lingan.ucp.nocode.runtime.dal.query.RecordStatement statement,
            @Param("object") String object,
            @Param("definition") String definition,
            @Param("actor") String actor);

    /** 数据库时间与提交快照共同确定一次检索；不获取业务写锁。 */
    String view();

    // 行详情只需要历史字段 ID 与名称；不再逐行传输完整对象、视图和关联定义。

    /** 范围事件按时间和 ID 游标读取，批次边界不改变统计口径。 */
    List<String> events(
            @Param("object") String object,
            @Param("start") String start,
            @Param("end") String end,
            @Param("visibility") String visibility,
            @Param("cursorTime") String cursorTime,
            @Param("cursorId") long cursorId,
            @Param("records") List<String> records,
            @Param("fields") boolean fields);

    /** 全表授权需要检查记录值，但每批最多 1000 行，且完全不读取表结构。 */
    List<String> snapshotBatch(
            @Param("object") String object,
            @Param("end") String end,
            @Param("visibility") String visibility,
            @Param("cursor") String cursor);

    /** 仅为当前页或单行详情读取必要字段名。 */
    List<String> snapshots(
            @Param("object") String object,
            @Param("end") String end,
            @Param("visibility") String visibility,
            @Param("records") List<String> records);

    /** 来源与操作者由可信入口限定；历史值只在服务端参与范围判断，不直接返回浏览器。 */
    List<String> taskEvents(
            @Param("app") String app,
            @Param("object") String object,
            @Param("entry") String entry,
            @Param("actor") String actor,
            @Param("before") long before);

    /** FORM 历史定位只能读取本人通过同一入口成功创建的记录，不能据此浏览整个对象。 */
    boolean createdFromTask(
            @Param("app") String app,
            @Param("object") String object,
            @Param("entry") String entry,
            @Param("record") String record,
            @Param("actor") String actor);
}
