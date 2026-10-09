package com.richuang.os.nocode.report.dal.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.report.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

import java.util.Set;

/** 仪表板及版本写入与数据集依赖同事务执行。 */
@Mapper
public interface ReportDashboardMapper extends BaseMapperX<ReportDashboardDO> {
    ReportDashboardDO lock(@Param("id") long id, @Param("write") boolean write);

    IPage<ReportDashboardDO> page(
            IPage<ReportDashboardDO> page,
            @Param("actor") long actor,
            @Param("search") String search);

    IPage<ReportDashboardDO> availablePage(
            IPage<ReportDashboardDO> page,
            @Param("actor") long actor,
            @Param("principals") Set<String> principals,
            @Param("manage") boolean manage,
            @Param("search") String search,
            @Param("folder") Long folder,
            @Param("status") String status);

    int create(@Param("row") ReportDashboardDO row, @Param("actor") String actor);

    int save(
            @Param("row") ReportDashboardDO row,
            @Param("expected") int expected,
            @Param("actor") String actor);

    int publish(@Param("id") long id, @Param("number") int number, @Param("actor") String actor);

    int move(
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("folder") Long folder,
            @Param("actor") String actor);

    int status(
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("status") String status,
            @Param("actor") String actor);

    int remove(@Param("id") long id, @Param("expected") int expected, @Param("actor") String actor);

    long incomingReferences(@Param("id") long id);

    void clearDependencies(@Param("id") long id, @Param("actor") String actor);

    IPage<ReportDashboardVersionDO> versions(
            IPage<ReportDashboardVersionDO> page, @Param("id") long id);

    int createVersion(@Param("row") ReportDashboardVersionDO row, @Param("actor") String actor);

    ReportDashboardVersionDO version(@Param("id") long id, @Param("number") int number);

    ReportDashboardVersionDO request(
            @Param("id") long id, @Param("actor") String actor, @Param("request") String request);

    void clearDraftDependencies(@Param("id") long id, @Param("actor") String actor);

    void dependency(
            @Param("id") long id,
            @Param("number") int number,
            @Param("dataset") long dataset,
            @Param("version") int version,
            @Param("fields") String fields,
            @Param("actor") String actor);
}
