package com.lingan.ucp.nocode.report.dal.mapper;

import com.lingan.ucp.nocode.report.dal.dataobject.ReportFolderDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/** 目录变更共享全局设计锁；SQL 统一位于 XML。 */
@Mapper
public interface ReportFolderMapper {
    List<ReportFolderDO> all(@Param("kind") String kind);

    Set<Long> visibleSeeds(
            @Param("actor") long actor,
            @Param("principals") Set<String> principals,
            @Param("kind") String kind);

    int create(@Param("row") ReportFolderDO row, @Param("actor") String actor);

    int save(
            @Param("row") ReportFolderDO row,
            @Param("expected") int expected,
            @Param("actor") String actor);

    boolean occupied(@Param("id") long id, @Param("kind") String kind);

    int remove(
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("actor") String actor,
            @Param("kind") String kind);
}
