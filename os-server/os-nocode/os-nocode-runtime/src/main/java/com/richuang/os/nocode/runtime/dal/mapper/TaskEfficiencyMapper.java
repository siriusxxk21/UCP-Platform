package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.nocode.api.TaskEfficiency.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 受管理范围约束的数据库聚合；不逐任务装载办理事实或业务对象。 */
@Mapper
public interface TaskEfficiencyMapper {
    Metrics metrics(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<Trend> trend(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<Distribution> distribution(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<Employee> employees(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    long employeeCount(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<Task> tasks(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    long taskCount(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<com.richuang.os.nocode.api.TaskEfficiency.Record> records(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    long recordCount(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<EmployeeOption> employeeOptions(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);

    List<TemplateOption> templateOptions(
            @Param("q") Query query, @Param("actor") long actor, @Param("admin") boolean admin);
}
