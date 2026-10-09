package com.lingan.ucp.nocode.application.service.resource;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.dal.dataobject.DateTriggerDoneDO;
import com.lingan.ucp.nocode.application.dal.dataobject.DateTriggerStateDO;
import com.lingan.ucp.nocode.application.dal.mapper.DateTriggerMapper;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 按日期自动执行的账本（nocode_date_trigger_state / _done）。
 *
 * <p>生效（arm）只在应用发布、恢复历史版本、发布启用与启停这几处、与发布指针同一事务里调用：新生效的规则从「日历今天」起算（封账日 = 昨天），
 * 发布前已经过去的日期一律不补；一直生效的规则保留原账本继续。停用、暂停应用或从版本里拿掉的规则失效，之后再生效时同样从当天重新起算。
 */
@Component
public class ApplicationDateTriggers {
    /** 运行情况里列出的失败明细上限。 */
    public static final int FAILURE_LIMIT = 20;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource private DateTriggerMapper mapper;
    @Resource private ApplicationAutomationCatalog catalog;

    /** 新版本（或重新启用的版本）里启用的按日期规则：未生效的从今天起算，已生效的不动；其余规则失效。 */
    public void arm(String application, ApplicationCenter.Definition definition, long actor) {
        long app = Long.parseLong(application);
        List<String> keep = new ArrayList<>();
        if (definition != null)
            for (var resource : definition.resources()) {
                if (!ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) continue;
                var config = catalog.config(resource);
                if (Boolean.FALSE.equals(config.enabled())
                        || !AutomationModeEnum.DATE.matches(config.mode())) continue;
                keep.add(resource.id());
                mapper.arm(
                        app,
                        resource.id(),
                        FormulaDates.today().minusDays(1),
                        Long.toString(actor));
            }
        mapper.disarm(app, keep, Long.toString(actor));
    }

    /** 应用停用、暂停：全部规则失效。 */
    public void disarm(String application, long actor) {
        mapper.disarm(Long.parseLong(application), List.of(), Long.toString(actor));
    }

    /** 运行端兜底：目录里在跑、却没有账本或账本未生效的规则（例如发布早于本功能上线）从今天起算。 */
    public void armLate(String application, String resource, long actor) {
        mapper.arm(
                Long.parseLong(application),
                resource,
                FormulaDates.today().minusDays(1),
                Long.toString(actor));
    }

    public List<DateTriggerStateDO> states() {
        return mapper.states(null);
    }

    public void close(String application, String resource, LocalDate date) {
        mapper.close(Long.parseLong(application), resource, date);
    }

    public void scanned(
            String application, String resource, LocalDate date, boolean manual, String error) {
        mapper.scanned(
                Long.parseLong(application),
                resource,
                date,
                manual ? "MANUAL" : "AUTO",
                error == null ? null : truncate(error));
    }

    public boolean claim(
            String application, String resource, LocalDate date, String source, long actor) {
        return mapper.claim(
                        Long.parseLong(application), resource, date, source, Long.toString(actor))
                == 1;
    }

    public void finish(
            String application,
            String resource,
            LocalDate date,
            String source,
            boolean wrote,
            int targets) {
        mapper.finish(
                Long.parseLong(application),
                resource,
                date,
                source,
                wrote ? "SUCCESS" : "UNCHANGED",
                targets);
    }

    public void failed(
            String application,
            String resource,
            LocalDate date,
            String source,
            String message,
            long actor) {
        mapper.failed(
                Long.parseLong(application),
                resource,
                date,
                source,
                truncate(Objects.toString(message, "执行失败")),
                Long.toString(actor));
    }

    public Set<String> done(String application, String resource, LocalDate date) {
        return new HashSet<>(mapper.doneSources(Long.parseLong(application), resource, date));
    }

    public void clearFailed(String application, String resource, LocalDate date) {
        mapper.clearFailed(Long.parseLong(application), resource, date);
    }

    public void purge(LocalDate before) {
        mapper.purge(before);
    }

    /** 应用创建人的用户 ID；读不到或不是数字时为 0（调用方据此报错）。 */
    public long creator(String application) {
        try {
            return Long.parseLong(
                    Objects.toString(mapper.creator(Long.parseLong(application)), "").trim());
        } catch (NumberFormatException missing) {
            return 0;
        }
    }

    /** 应用里每条按日期规则的账本与最近一个业务日的结果，按资源 ID 排序。 */
    public List<DateTriggerRuns.Status> status(String application) {
        long app = Long.parseLong(application);
        List<DateTriggerRuns.Status> result = new ArrayList<>();
        for (DateTriggerStateDO state : mapper.states(app)) {
            LocalDate day = state.getLastScanDate();
            Map<String, Integer> counts = new HashMap<>();
            List<DateTriggerRuns.Failure> failures = new ArrayList<>();
            if (day != null) {
                for (String row : mapper.outcomeCounts(app, state.getResourceId(), day)) {
                    int colon = row.lastIndexOf(':');
                    counts.put(row.substring(0, colon), Integer.parseInt(row.substring(colon + 1)));
                }
                for (DateTriggerDoneDO row :
                        mapper.doneRows(app, state.getResourceId(), day, FAILURE_LIMIT))
                    if ("FAILED".equals(row.getOutcome()))
                        failures.add(
                                new DateTriggerRuns.Failure(
                                        row.getSourceRecordId(),
                                        row.getMessage(),
                                        row.getDoneAt() == null
                                                ? null
                                                : row.getDoneAt().format(TIME)));
            }
            result.add(
                    new DateTriggerRuns.Status(
                            state.getResourceId(),
                            Boolean.TRUE.equals(state.getArmed()),
                            Objects.toString(state.getClosedDate(), null),
                            state.getLastScanAt() == null
                                    ? null
                                    : state.getLastScanAt().format(TIME),
                            state.getLastTrigger(),
                            state.getLastError(),
                            day == null ? null : day.toString(),
                            counts.getOrDefault("SUCCESS", 0),
                            counts.getOrDefault("UNCHANGED", 0),
                            counts.getOrDefault("FAILED", 0),
                            failures));
        }
        return result;
    }

    private static String truncate(String text) {
        return text.length() <= 1000 ? text : text.substring(0, 997) + "...";
    }
}
