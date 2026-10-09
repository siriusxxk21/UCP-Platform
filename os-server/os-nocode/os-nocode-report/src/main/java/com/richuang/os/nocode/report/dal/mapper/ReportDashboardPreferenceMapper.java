package com.richuang.os.nocode.report.dal.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.report.dal.dataobject.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Set;

/** 当前会话用户的偏好分页与幂等写入；SQL 同时按实时VIEW过滤，偏好不能产生访问权。 */
@Mapper
public interface ReportDashboardPreferenceMapper extends BaseMapperX<ReportDashboardPreferenceDO> {
    IPage<ReportDashboardDO> page(
            IPage<ReportDashboardDO> page,
            @Param("actor") long actor,
            @Param("principals") Set<String> principals,
            @Param("search") String search,
            @Param("view") String view);

    ReportDashboardPreferenceDO get(@Param("actor") long actor, @Param("id") long id);

    int favorite(
            @Param("actor") long actor, @Param("id") long id, @Param("favorite") boolean favorite);

    int visit(@Param("actor") long actor, @Param("id") long id);
}
