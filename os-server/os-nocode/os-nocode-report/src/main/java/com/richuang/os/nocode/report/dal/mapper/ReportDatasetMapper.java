package com.richuang.os.nocode.report.dal.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.report.dal.dataobject.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据集设计事务与现有对象变更共享设计锁；所有 SQL 保留在 XML。 */
@Mapper
public interface ReportDatasetMapper extends BaseMapperX<ReportDatasetDO> {
    void designLock();

    /** 运行共享设计锁，避免结构变更与跨对象取数形成相反锁序。 */
    void designReadLock();

    ReportDatasetDO lock(@Param("id") long id, @Param("write") boolean write);

    IPage<ReportDatasetDO> page(
            IPage<ReportDatasetDO> page,
            @Param("actor") long actor,
            @Param("search") String search,
            @Param("principals") java.util.Set<String> principals,
            @Param("folder") Long folder);

    int create(@Param("row") ReportDatasetDO row, @Param("actor") String actor);

    IPage<ReportDatasetDO> authorizationTargets(
            IPage<ReportDatasetDO> page, @Param("search") String search);

    int save(
            @Param("row") ReportDatasetDO row,
            @Param("expected") int expected,
            @Param("actor") String actor);

    int move(
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("folder") Long folder,
            @Param("actor") String actor);

    int publish(@Param("id") long id, @Param("number") int number, @Param("actor") String actor);

    int status(@Param("id") long id, @Param("status") String status, @Param("actor") String actor);

    int createVersion(@Param("row") ReportDatasetVersionDO row, @Param("actor") String actor);

    ReportDatasetVersionDO version(@Param("id") long id, @Param("number") int number);

    ReportDatasetVersionDO request(
            @Param("id") long id, @Param("actor") String actor, @Param("request") String request);

    IPage<ReportDatasetVersionDO> versions(
            IPage<ReportDatasetVersionDO> page, @Param("id") long id);

    long incomingReferences(@Param("id") long id);

    java.util.List<Integer> versionNumbers(@Param("id") long id);

    int remove(@Param("id") long id, @Param("expected") int expected, @Param("actor") String actor);

    int audit(@Param("row") ReportOperationLogDO row, @Param("actor") String actor);
}
