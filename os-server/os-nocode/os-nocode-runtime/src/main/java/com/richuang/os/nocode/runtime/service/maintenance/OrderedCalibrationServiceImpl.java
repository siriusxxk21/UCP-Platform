package com.richuang.os.nocode.runtime.service.maintenance;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.OrderedCalculationCalibration.*;
import com.richuang.os.nocode.api.OrderedCalculations.Readiness;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.metadata.service.formula.Calculations;
import com.richuang.os.nocode.metadata.service.formula.OrderedCalculationStateService;
import com.richuang.os.nocode.runtime.dal.mapper.ObjectMaintenanceMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.richuang.os.nocode.runtime.service.record.OrderedRecordCalculations;
import com.richuang.os.nocode.runtime.service.record.RecordCalculations;

import jakarta.annotation.*;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** 沿用维护授权及对象锁；进度与每组结果同事务提交，不依赖线程内任务或定时器。 */
@Service
public class OrderedCalibrationServiceImpl implements OrderedCalibrationService {
    @Resource private PermissionCommonApi permissions;
    @Resource private DataObjectApi objects;
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private ObjectMaintenanceMapper definitionLocks;
    @Resource private RecordHistoryMapper objectLocks;
    @Resource private OrderedCalculationStateService states;
    @Resource private OrderedRecordCalculations ordered;
    @Resource private RecordCalculations calculations;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void authorize(long actor) {
        if (actor <= 0
                || !permissions.hasAnyPermissions(actor, "nocode:object:query")
                || !permissions.hasAnyPermissions(actor, "nocode:object:manage"))
            throw new AccessDeniedException("校准需要数据对象管理权限");
    }

    private <T> T locked(String object, long actor, Supplier<T> work) {
        authorize(actor);
        if (object == null || !object.matches("[1-9][0-9]*")) throw invalid("数据对象标识无效");
        return transaction.execute(
                status -> {
                    catalog.lock(false);
                    definitionLocks.lockDefinitions();
                    objectLocks.lock(object);
                    return ObjectMaintenanceScope.run(actor, work);
                });
    }

    private DataCenter.Definition version(String object, int number, String checksum) {
        DataObjectApi.PublishedObject published = objects.getVersion(object, null);
        if (published.versionNo() != number || !Objects.equals(published.checksum(), checksum))
            throw invalid("对象版本已变化，请刷新并重新预览校准");
        return published.definition();
    }

    private List<String> fields(DataCenter.Definition d, List<String> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > 50)
            throw invalid("请选择 1 至 50 个有序落库字段");
        if (new HashSet<>(selected).size() != selected.size()) throw invalid("校准字段不能重复");
        for (String field : selected)
            if (!Calculations.orderedStored(d.fieldOptions().get(field)))
                throw invalid("只能选择当前已发布的有序落库字段");
        return selected;
    }

    @Override
    public List<OrderedCalculations.State> status(String object, long actor) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, "nocode:object:query"))
            throw new AccessDeniedException("需要数据对象查看权限");
        if (object == null || !object.matches("[1-9][0-9]*")) throw invalid("数据对象标识无效");
        boolean managing = permissions.hasAnyPermissions(actor, "nocode:object:manage");
        return transaction.execute(
                ignored -> {
                    DataCenter.Definition d = objects.getPublished(object);
                    return states.forObject(object).stream()
                            .filter(
                                    state ->
                                            Calculations.orderedStored(
                                                    d.fieldOptions().get(state.fieldId())))
                            .map(
                                    state ->
                                            managing
                                                    ? state
                                                    : new OrderedCalculations.State(
                                                            state.objectId(),
                                                            state.fieldId(),
                                                            state.signature(),
                                                            state.state(),
                                                            Map.of(),
                                                            0,
                                                            0,
                                                            0,
                                                            null,
                                                            state.revision()))
                            .toList();
                });
    }

    @Override
    public Preview preview(PreviewRequest command, long actor) {
        return locked(
                command.objectId(),
                actor,
                () -> {
                    DataCenter.Definition d =
                            version(command.objectId(), command.versionNo(), command.checksum());
                    List<FieldPreview> result = new ArrayList<>();
                    for (String field : fields(d, command.fieldIds())) {
                        List<RecordCalculations.OrderedGroup> groups =
                                calculations.orderedGroups(d, field, actor);
                        long rows = 0,
                                nullRows = 0,
                                changed = 0,
                                fill = 0,
                                incorrect = 0,
                                validNull = 0;
                        for (RecordCalculations.OrderedGroup group : groups) {
                            rows += group.rows();
                            nullRows += group.nullRows();
                            for (RecordCalculations.OrderedValue value :
                                    calculations.orderedGroup(
                                            null, d, field, group.values(), actor)) {
                                Object stored = value.stored().get(field);
                                if (stored == null && value.expected() == null) validNull++;
                                if (OrderedRecordCalculations.same(
                                        d, field, stored, value.expected())) continue;
                                changed++;
                                if (stored == null) fill++;
                                else incorrect++;
                            }
                        }
                        OrderedCalculations.State state = states.get(d.objectId(), field);
                        result.add(
                                new FieldPreview(
                                        field,
                                        OrderedCalculationStateService.signature(d, field),
                                        state == null ? Readiness.PENDING.getCode() : state.state(),
                                        groups.size(),
                                        rows,
                                        nullRows,
                                        changed,
                                        fill,
                                        incorrect,
                                        validNull));
                    }
                    return new Preview(
                            d.objectId(), command.versionNo(), command.checksum(), result);
                });
    }

    private DataCenter.Definition check(Command command) {
        if (command.requestId() == null || !command.requestId().matches("[A-Za-z0-9_-]{8,100}"))
            throw invalid("校准批次标识无效");
        if (command.maxGroups() < 1 || command.maxGroups() > 20) throw invalid("每次请推进 1 至 20 组");
        DataCenter.Definition d =
                version(command.objectId(), command.versionNo(), command.checksum());
        for (String field : fields(d, command.fieldIds()))
            if (command.signatures() == null
                    || !OrderedCalculationStateService.signature(d, field)
                            .equals(command.signatures().get(field)))
                throw invalid("规则摘要已变化，请重新预览校准");
        return d;
    }

    @Override
    public Result calibrate(Command command, long actor) {
        locked(
                command.objectId(),
                actor,
                () -> {
                    DataCenter.Definition d = check(command);
                    // 批次范围必须先完整核验，避免旧字段幂等跳过后把新增字段留在阻写状态。
                    for (OrderedCalculations.State state : states.forObject(d.objectId()))
                        if (command.requestId().equals(state.cursor().get("requestId")))
                            requireRequest(state, command);
                    for (String field : command.fieldIds()) {
                        OrderedCalculations.State state = states.get(d.objectId(), field);
                        if (state != null
                                && command.requestId().equals(state.cursor().get("requestId")))
                            continue;
                        if (state != null && Readiness.BACKFILLING.matches(state.state()))
                            throw invalid("字段已有未完成批次，请先继续或暂停原批次");
                        List<RecordCalculations.OrderedGroup> groups =
                                calculations.orderedGroups(d, field, actor);
                        Map<String, Object> cursor = new LinkedHashMap<>();
                        cursor.put("requestId", command.requestId());
                        cursor.put("fieldIds", command.fieldIds());
                        cursor.put("versionNo", command.versionNo());
                        cursor.put("checksum", command.checksum());
                        cursor.put("totalGroups", groups.size());
                        states.begin(
                                d.objectId(),
                                field,
                                command.signatures().get(field),
                                cursor,
                                groups.stream()
                                        .mapToLong(RecordCalculations.OrderedGroup::rows)
                                        .sum(),
                                actor);
                    }
                    return true;
                });
        return resume(command, actor);
    }

    @Override
    public Result resume(Command command, long actor) {
        locked(
                command.objectId(),
                actor,
                () -> {
                    check(command);
                    // 重试时一次恢复整个批次；尚未轮到的字段不能继续呈现上次失败。
                    for (OrderedCalculations.State state : selected(command))
                        if (Readiness.FAILED.matches(state.state()))
                            states.progress(
                                    state.objectId(),
                                    state.fieldId(),
                                    state.signature(),
                                    state.cursor(),
                                    state.completedGroups(),
                                    state.updatedRows(),
                                    actor);
                    return true;
                });
        for (int index = 0; index < command.maxGroups(); index++) {
            OrderedCalculations.State[] advancing = new OrderedCalculations.State[1];
            Map<String, Object> failurePosition = new LinkedHashMap<>();
            try {
                boolean done =
                        locked(
                                command.objectId(),
                                actor,
                                () -> {
                                    DataCenter.Definition d = check(command);
                                    List<OrderedCalculations.State> progress = selected(command);
                                    for (OrderedCalculations.State state : progress) {
                                        if (Readiness.READY.matches(state.state())) continue;
                                        // 组间已提交的暂停立即生效，只允许新的 resume 入口恢复。
                                        if (!Readiness.BACKFILLING.matches(state.state()))
                                            return true;
                                        advancing[0] = state;
                                        failurePosition.put(
                                                "failedGroupIndex", state.completedGroups());
                                        List<RecordCalculations.OrderedGroup> groups =
                                                calculations.orderedGroups(
                                                        d, state.fieldId(), actor);
                                        if (groups.size()
                                                != ((Number) state.cursor().get("totalGroups"))
                                                        .intValue())
                                            throw invalid("校准期间数据分组发生变化，请重新预览并开始新批次");
                                        int next = Math.toIntExact(state.completedGroups());
                                        long updated = state.updatedRows();
                                        if (next < groups.size()) {
                                            failurePosition.put(
                                                    "failedGroupValues", groups.get(next).values());
                                            updated +=
                                                    ordered.applyGroup(
                                                            null,
                                                            d,
                                                            state.fieldId(),
                                                            calculations.orderedGroup(
                                                                    null,
                                                                    d,
                                                                    state.fieldId(),
                                                                    groups.get(next).values(),
                                                                    actor),
                                                            actor);
                                            next++;
                                        }
                                        states.progress(
                                                d.objectId(),
                                                state.fieldId(),
                                                state.signature(),
                                                state.cursor(),
                                                next,
                                                updated,
                                                actor);
                                        if (next == groups.size())
                                            states.finish(
                                                    d.objectId(),
                                                    state.fieldId(),
                                                    state.signature(),
                                                    actor);
                                        return false;
                                    }
                                    return true;
                                });
                if (done) break;
            } catch (RuntimeException error) {
                if (advancing[0] == null) throw error;
                locked(
                        command.objectId(),
                        actor,
                        () -> {
                            check(command);
                            OrderedCalculations.State state =
                                    states.get(command.objectId(), advancing[0].fieldId());
                            // 失败组事务已回滚并释放锁；不能用旧失败覆盖后来完成的批次进度。
                            if (state != null
                                    && state.revision() == advancing[0].revision()
                                    && command.requestId()
                                            .equals(state.cursor().get("requestId"))) {
                                Map<String, Object> cursor = new LinkedHashMap<>(state.cursor());
                                cursor.putAll(failurePosition);
                                states.fail(
                                        command.objectId(),
                                        state.fieldId(),
                                        state.signature(),
                                        error.getMessage(),
                                        cursor,
                                        actor);
                            }
                            return true;
                        });
                break;
            }
        }
        return locked(command.objectId(), actor, () -> result(command));
    }

    private List<OrderedCalculations.State> selected(Command command) {
        List<OrderedCalculations.State> result = new ArrayList<>();
        for (String field : command.fieldIds()) {
            OrderedCalculations.State state = states.get(command.objectId(), field);
            requireRequest(state, command);
            result.add(state);
        }
        return result;
    }

    private void requireRequest(OrderedCalculations.State state, Command command) {
        if (state == null
                || !command.requestId().equals(state.cursor().get("requestId"))
                || !command.fieldIds().equals(state.cursor().get("fieldIds"))
                || !(state.cursor().get("versionNo") instanceof Number version)
                || version.intValue() != command.versionNo()
                || !Objects.equals(command.checksum(), state.cursor().get("checksum"))
                || !Objects.equals(state.signature(), command.signatures().get(state.fieldId())))
            throw invalid("校准批次或选择范围不一致，请刷新状态后重试");
    }

    private Result result(Command command) {
        check(command);
        List<OrderedCalculations.State> result = selected(command);
        return new Result(
                command.requestId(),
                result.stream().allMatch(state -> Readiness.READY.matches(state.state())),
                result);
    }

    @Override
    public Result pause(Command command, long actor) {
        return locked(
                command.objectId(),
                actor,
                () -> {
                    check(command);
                    for (OrderedCalculations.State state : selected(command)) {
                        if (Readiness.READY.matches(state.state())) continue;
                        states.fail(
                                command.objectId(),
                                state.fieldId(),
                                state.signature(),
                                "管理员已暂停，可继续原批次或切回 LIVE 后发布",
                                actor);
                    }
                    return result(command);
                });
    }
}
