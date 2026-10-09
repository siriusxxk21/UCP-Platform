package com.lingan.ucp.nocode.report.dal.mapper;

import com.lingan.ucp.nocode.report.dal.dataobject.ReportDashboardDO;
import com.lingan.ucp.nocode.report.dal.dataobject.ReportDashboardVersionDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 应用固定引用的精确目录和反向索引；业务 SQL 保持在 XML。 */
@Mapper
public interface ReportCatalogMapper {
    com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardDO> page(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<ReportDashboardDO> page,
            @Param("actor") long actor,
            @Param("principals") java.util.Collection<String> principals,
            @Param("search") String search);

    ReportDashboardDO dashboard(@Param("id") long id);

    ReportDashboardVersionDO version(@Param("id") long id, @Param("number") int number);

    void clearDraft(@Param("id") long id, @Param("actor") String actor);

    void dependency(
            @Param("id") long id,
            @Param("number") int number,
            @Param("target") long target,
            @Param("version") int version,
            @Param("fields") String fields,
            @Param("actor") String actor);
}
