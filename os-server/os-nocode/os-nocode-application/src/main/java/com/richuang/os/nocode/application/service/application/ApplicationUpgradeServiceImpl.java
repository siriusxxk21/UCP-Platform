package com.richuang.os.nocode.application.service.application;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.module.bpm.api.definition.BpmProcessDefinitionApi;
import com.richuang.os.module.bpm.api.definition.dto.BpmBusinessBindingDTO;
import com.richuang.os.nocode.api.ApplicationAutomations;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldConversionCompatibility;
import com.richuang.os.nocode.api.FieldConversionDependencyInspector;
import com.richuang.os.nocode.api.FieldStorage;
import com.richuang.os.nocode.api.ObjectApplicationUpgrade;
import com.richuang.os.nocode.api.ObjectContracts;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationVersionDO;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.application.dal.mapper.RecordProcessMapper;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationFieldConversionDependencies;
import com.richuang.os.nocode.application.service.resource.ApplicationRuleWriteExclusion;
import com.richuang.os.nocode.enums.ApplicationResourceKindEnum;
import com.richuang.os.nocode.enums.ApplicationStatusEnum;
import com.richuang.os.nocode.enums.HandlingStateEnum;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 对象发布前找出本次新增的应用不兼容项，并在同一事务暂停已确认的应用。 */
@Service
public class ApplicationUpgradeServiceImpl implements ApplicationUpgradeService {
    private static final String MANAGE_PERMISSION = "nocode:app:manage";
    private static final List<String> PENDING_HANDLING =
            List.of(
                    HandlingStateEnum.PENDING.getCode(),
                    HandlingStateEnum.APPLY_PENDING.getCode(),
                    HandlingStateEnum.APPLY_FAILED.getCode());

    @Resource private ApplicationMapper store;
    @Resource private ApplicationService applications;
    @Resource private ApplicationAutomationCatalog automations;
    @Resource private ApplicationRuleWriteExclusion ruleWrites;
    @Resource private ApplicationFieldConversionDependencies dependencies;
    @Resource private RecordProcessMapper processes;
    @Resource private BpmProcessDefinitionApi processDefinitions;
    @Resource private PermissionCommonApi permissions;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;
    @Resource private com.richuang.os.nocode.api.DataObjectApi objects;
    @Resource private ApplicationFollowService follows;

    @Override
    public void lock() {
        automations.lock(true);
    }

    @Override
    public List<String> ruleWriteConflicts(DataCenter.Definition proposed) {
        return ruleWrites.conflicts(proposed);
    }

    @Override
    public List<DataCenter.Check> followChecks(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            List<ObjectApplicationUpgrade.Impact> impacts,
            long actor) {
        return follows.followChecks(previous, proposed, impacts, actor);
    }

    @Override
    public void follow(
            DataCenter.Definition previous,
            DataCenter.Definition published,
            String planId,
            long actor,
            String objectReason) {
        follows.follow(previous, published, planId, actor, objectReason);
    }

    @Override
    public List<ObjectApplicationUpgrade.Impact> inspect(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> clearedFieldIds,
            long actor) {
        if (proposed == null) throw invalid("应用维护检查缺少目标对象定义");
        if (previous == null) return List.of();
        if (!Objects.equals(previous.objectId(), proposed.objectId()))
            throw invalid("应用维护检查缺少同一对象的发布前后定义");
        Set<String> changed = changedFields(previous, proposed);
        changed.addAll(clearedFieldIds == null ? Set.of() : clearedFieldIds);
        Map<String, List<FieldConversionDependencyInspector.Impact>> resourceImpacts =
                byApplication(
                        dependencies.inspectRaw(
                                previous,
                                proposed,
                                changed,
                                clearedFieldIds == null ? Set.of() : clearedFieldIds));
        Map<String, List<FieldConversionDependencyInspector.Impact>> baselineImpacts =
                byApplication(dependencies.inspectRaw(previous, previous, changed, Set.of()));
        List<ObjectApplicationUpgrade.Impact> result = new ArrayList<>();
        for (String id : store.runnableIds()) {
            NocodeApplicationDO app = store.selectById(Long.parseLong(id));
            if (app == null || app.getPublishedVersion() == null) continue;
            NocodeApplicationVersionDO release =
                    store.version(app.getId(), app.getPublishedVersion());
            if (release == null) throw invalid("应用发布版本不存在，无法检查维护影响：" + app.getAppName());
            ApplicationCenter.Snapshot snapshot = snapshot(release);
            ApplicationCenter.ObjectReference reference =
                    snapshot.definition().objects().stream()
                            .filter(ref -> previous.objectId().equals(ref.objectId()))
                            .findFirst()
                            .orElse(null);
            if (reference == null) continue;
            DataCenter.Definition fixed = applicationsObject(reference);
            LinkedHashSet<String> reasons = new LinkedHashSet<>();
            try {
                com.richuang.os.nocode.metadata.service.formula.OrderedCalculationStateService
                        .requireCompatible(fixed, proposed);
            } catch (com.richuang.os.framework.common.exception.ServiceException error) {
                reasons.add(error.getMessage());
            }
            Set<String> detailedFields = new HashSet<>();
            Map<String, FieldConversionCompatibility.Field> fixedFields =
                    FieldConversionCompatibility.fields(fixed);
            Map<String, FieldConversionCompatibility.Field> beforeFields =
                    FieldConversionCompatibility.fields(previous);
            Map<String, FieldConversionCompatibility.Field> afterFields =
                    FieldConversionCompatibility.fields(proposed);
            for (String fieldId : changed) {
                FieldConversionCompatibility.Field pinned = fixedFields.get(fieldId);
                if (pinned == null) continue;
                FieldConversionCompatibility.Field before = beforeFields.get(fieldId);
                FieldConversionCompatibility.Field after = afterFields.get(fieldId);
                boolean writeBroken =
                        FieldConversionCompatibility.writeCompatible(pinned, before)
                                && !FieldConversionCompatibility.writeCompatible(pinned, after);
                boolean storageBroken =
                        storageAccepts(pinned, before) && !storageAccepts(pinned, after);
                if (writeBroken || storageBroken) {
                    detailedFields.add(pinned.definition().name());
                    reasons.add(
                            "字段“"
                                    + pinned.definition().name()
                                    + "”的旧应用"
                                    + (writeBroken && storageBroken
                                            ? "写入与物理列"
                                            : writeBroken ? "写入" : "物理列")
                                    + "契约与新结构不兼容");
                }
            }
            Set<String> baseline = new HashSet<>(ObjectContracts.breakingChanges(fixed, previous));
            for (String message : ObjectContracts.breakingChanges(fixed, proposed)) {
                if (baseline.contains(message)) continue;
                boolean covered =
                        detailedFields.stream()
                                .anyMatch(name -> message.equals("字段“" + name + "”的类型、约束或计算规则不兼容"));
                if (!covered) reasons.add(message);
            }
            Set<FieldConversionDependencyInspector.Impact> oldResources =
                    new HashSet<>(baselineImpacts.getOrDefault(id, List.of()));
            for (FieldConversionDependencyInspector.Impact impact :
                    resourceImpacts.getOrDefault(id, List.of()))
                if (!oldResources.contains(impact))
                    reasons.add(impact.location() + "：" + impact.message());
            if (reasons.isEmpty()) continue;
            List<String> blockers = blockers(app, snapshot, actor);
            result.add(
                    new ObjectApplicationUpgrade.Impact(
                            id,
                            app.getAppName(),
                            app.getLockVersion(),
                            app.getPublishedVersion(),
                            reference.versionNo(),
                            List.copyOf(reasons),
                            blockers,
                            "/nocode-app/workspace?id=" + id));
        }
        return List.copyOf(result);
    }

    @Override
    public void suspend(List<ObjectApplicationUpgrade.Impact> impacts, long actor, String reason) {
        if (impacts == null) throw invalid("缺少待暂停应用清单");
        if (impacts.isEmpty()) return;
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, MANAGE_PERMISSION))
            throw new AccessDeniedException("没有应用管理权限，无法暂停受影响应用");
        validator.text(reason, "应用维护说明", 1000);
        Set<String> seen = new HashSet<>();
        for (ObjectApplicationUpgrade.Impact impact : impacts) {
            if (impact == null || !seen.add(impact.applicationId()) || impact.reasons().isEmpty())
                throw invalid("应用维护清单无效，请重新预检");
            applications.requireDesigner(impact.applicationId(), actor);
            NocodeApplicationDO app =
                    store.lock(validator.id(impact.applicationId(), "应用 ID"), true);
            if (app == null
                    || !ApplicationStatusEnum.ACTIVE.matches(app.getStatus())
                    || !Objects.equals(app.getLockVersion(), impact.revision())
                    || !Objects.equals(app.getPublishedVersion(), impact.applicationVersion()))
                throw invalid("应用状态或版本已变化，请重新检查影响：" + impact.applicationName());
            NocodeApplicationVersionDO release =
                    store.version(app.getId(), app.getPublishedVersion());
            if (release == null) throw invalid("应用发布版本不存在：" + app.getAppName());
            List<String> currentBlockers = blockers(app, snapshot(release), actor);
            if (!currentBlockers.isEmpty())
                throw invalid(
                        "应用“" + app.getAppName() + "”不能暂停：" + String.join("；", currentBlockers));
            if (store.status(
                            app.getId(),
                            ApplicationStatusEnum.DISABLED.getCode(),
                            Long.toString(actor))
                    != 1) throw invalid("暂停应用失败，请重新检查影响：" + app.getAppName());
        }
    }

    private DataCenter.Definition applicationsObject(ApplicationCenter.ObjectReference reference) {
        com.richuang.os.nocode.api.DataObjectApi.PublishedObject version =
                objects.getVersion(reference.objectId(), reference.versionNo());
        if (!Objects.equals(reference.checksum(), version.checksum()))
            throw invalid("应用固定对象版本校验和不匹配，请先修复应用引用");
        return version.definition();
    }

    private ApplicationCenter.Snapshot snapshot(NocodeApplicationVersionDO release) {
        try {
            ApplicationCenter.Snapshot result =
                    json.readValue(release.getDefinitionJson(), ApplicationCenter.Snapshot.class);
            String checksum =
                    DigestUtil.sha256Hex(
                            json.copy()
                                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                                    .writeValueAsString(result));
            if (!checksum.equals(release.getChecksum())) throw invalid("应用发布快照完整性校验失败");
            return result;
        } catch (IOException ex) {
            throw invalid("应用发布快照无法读取");
        }
    }

    private boolean storageAccepts(
            FieldConversionCompatibility.Field pinned, FieldConversionCompatibility.Field current) {
        return current != null
                && FieldStorage.accepts(
                        FieldStorage.sqlType(pinned.definition(), pinned.options()),
                        FieldStorage.sqlType(current.definition(), current.options()));
    }

    private Set<String> changedFields(
            DataCenter.Definition previous, DataCenter.Definition proposed) {
        Map<String, FieldConversionCompatibility.Field> old =
                FieldConversionCompatibility.fields(previous);
        Map<String, FieldConversionCompatibility.Field> next =
                FieldConversionCompatibility.fields(proposed);
        Set<String> changed = new LinkedHashSet<>(old.keySet());
        changed.addAll(next.keySet());
        changed.removeIf(id -> Objects.equals(old.get(id), next.get(id)));
        return changed;
    }

    private Map<String, List<FieldConversionDependencyInspector.Impact>> byApplication(
            List<FieldConversionDependencyInspector.Impact> impacts) {
        Map<String, List<FieldConversionDependencyInspector.Impact>> result = new LinkedHashMap<>();
        for (FieldConversionDependencyInspector.Impact impact : impacts)
            result.computeIfAbsent(impact.sourceId(), unused -> new ArrayList<>()).add(impact);
        return result;
    }

    /** 暂停整应用会停止所有自动更新，首版不做历史补算；生效流程和待办同样不能丢入口。 */
    private List<String> blockers(
            NocodeApplicationDO app, ApplicationCenter.Snapshot snapshot, long actor) {
        List<String> result = new ArrayList<>();
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, MANAGE_PERMISSION))
            result.add("缺少应用管理权限");
        try {
            applications.requireDesigner(app.getId().toString(), actor);
        } catch (AccessDeniedException ex) {
            result.add("不是该应用创建人或平台管理员");
        }
        if (!processes.active(app.getId()).isEmpty()) result.add("存在运行中的流程");
        if (store.unresolvedHandling(app.getId(), PENDING_HANDLING) > 0) result.add("存在未完成的业务办理申请");
        for (ApplicationCenter.Resource resource : snapshot.definition().resources()) {
            if (!ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) continue;
            ApplicationAutomations.Config config =
                    json.convertValue(resource.config(), ApplicationAutomations.Config.class);
            if (!Boolean.FALSE.equals(config.enabled()))
                result.add("自动更新“" + resource.name() + "”仍生效；请先在应用中心停用并发布");
        }
        for (BpmBusinessBindingDTO binding :
                processDefinitions.getEffectiveBusinessBindings("nocode")) {
            try {
                JsonNode configuration = json.readTree(binding.configuration());
                if (configuration != null
                        && app.getId()
                                .toString()
                                .equals(
                                        configuration
                                                .path("resource")
                                                .path("applicationId")
                                                .asText()))
                    result.add(
                            "流程“"
                                    + binding.processName()
                                    + "”V"
                                    + binding.processVersion()
                                    + " 的节点“"
                                    + Objects.toString(binding.nodeName(), binding.nodeId())
                                    + "”仍绑定该应用");
            } catch (IOException ex) {
                throw invalid("流程业务绑定配置无法读取，无法安全暂停应用");
            }
        }
        return List.copyOf(result);
    }
}
