package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.TaskEfficiency.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskEfficiencyMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 复用任务管理权限与不可变办理事实；同次汇总及分页使用一致快照，不访问业务字段内容。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaskEfficiencyServiceImpl implements TaskEfficiencyService {
    @Resource private TaskEfficiencyMapper store;
    @Resource private PermissionCommonApi permissions;

    @Override
    public Overview overview(Query input, long actor) {
        boolean admin = requireManager(actor);
        Query query = normalize(input);
        Metrics metrics = store.metrics(query, actor, admin);
        Map<LocalDate, Trend> measured = new HashMap<>();
        store.trend(query, actor, admin).forEach(value -> measured.put(value.date(), value));
        List<Trend> trend = new ArrayList<>();
        for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
            trend.add(measured.getOrDefault(date, new Trend(date, BigDecimal.ZERO, 0)));
        }
        return new Overview(
                metrics.standardMinutes(),
                metrics.recordCount(),
                metrics.employeeCount(),
                metrics.completedNodeCount(),
                metrics.activeNodeCount(),
                metrics.overdueNodeCount(),
                List.copyOf(trend),
                store.distribution(query, actor, admin));
    }

    @Override
    public PageResult<Employee> employees(Query input, long actor) {
        boolean admin = requireManager(actor);
        Query query = normalize(input);
        return new PageResult<>(
                store.employees(query, actor, admin), store.employeeCount(query, actor, admin));
    }

    @Override
    public PageResult<Task> tasks(Query input, long actor) {
        boolean admin = requireManager(actor);
        Query query = normalize(input);
        return new PageResult<>(
                store.tasks(query, actor, admin), store.taskCount(query, actor, admin));
    }

    @Override
    public PageResult<com.lingan.ucp.nocode.api.TaskEfficiency.Record> records(
            Query input, long actor) {
        boolean admin = requireManager(actor);
        Query query = normalize(input);
        return new PageResult<>(
                store.records(query, actor, admin), store.recordCount(query, actor, admin));
    }

    @Override
    public Options options(Query input, long actor) {
        boolean admin = requireManager(actor);
        Query query = normalize(input);
        // 候选不能被自身已选值锁死，否则选中一个模板后便无法直接切换到其他模板。
        Query templateQuery =
                new Query(
                        query.from(),
                        query.to(),
                        query.employeeId(),
                        null,
                        query.rootTaskId(),
                        query.pageNo(),
                        query.pageSize(),
                        query.search(),
                        query.sortBy(),
                        query.descending());
        return new Options(
                store.employeeOptions(query, actor, admin),
                store.templateOptions(templateQuery, actor, admin));
    }

    /** 员工的参与/执行身份不授予统计权；普通创建人只可统计自己创建的整组。 */
    private boolean requireManager(long actor) {
        boolean admin =
                permissions.hasAnyRoles(actor, "super_admin")
                        || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
        if (!admin && !permissions.hasAnyPermissions(actor, "nocode:task:create")) {
            throw invalid("没有任务管理统计权限");
        }
        return admin;
    }

    private Query normalize(Query input) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Query query =
                input == null
                        ? new Query(null, null, null, null, null, null, null, null, null, null)
                        : input;
        LocalDate from = query.from() == null ? today.withDayOfMonth(1) : query.from();
        LocalDate to = query.to() == null ? today : query.to();
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 365)
            throw invalid("统计日期请选择不超过366天的有效区间");
        int pageNo = query.pageNo() == null ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null ? 20 : query.pageSize();
        if (pageNo < 1 || pageNo > 100000 || pageSize < 1 || pageSize > 100)
            throw invalid("统计分页参数无效");
        if (query.employeeId() != null && query.employeeId() <= 0) throw invalid("员工标识无效");
        String sort = query.sortBy() == null ? "standardMinutes" : query.sortBy();
        if (!Set.of("standardMinutes", "recordCount", "completedNodeCount", "overdueNodeCount")
                .contains(sort)) throw invalid("不支持的统计排序字段");
        return new Query(
                from,
                to,
                query.employeeId(),
                text(query.templateId(), 64),
                text(query.rootTaskId(), 64),
                pageNo,
                pageSize,
                text(query.search(), 200),
                sort,
                !Boolean.FALSE.equals(query.descending()));
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw invalid("统计筛选文字过长");
        return value.trim();
    }
}
