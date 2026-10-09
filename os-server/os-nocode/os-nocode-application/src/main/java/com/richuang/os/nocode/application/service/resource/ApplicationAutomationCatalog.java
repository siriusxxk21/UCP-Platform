package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 发布目录复用应用不可变版本，规则配置不另建规则表。按日期自动执行（mode=DATE）的调度与执行账本另见 nocode_date_trigger_state /
 * nocode_date_trigger_done（只记「执行到哪天、哪条记录已处理」，不存规则本身）。
 */
@Component
public class ApplicationAutomationCatalog {
    @Resource private ApplicationMapper applications;
    @Resource private ApplicationAutomationValidator validator;
    @Resource private ObjectMapper json;
    @Resource private ObjectSharingService sharing;

    @Resource
    private com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator grantValidator;

    @Resource private DataObjectApi objects;

    /** 共享目录锁是事务级锁：只读作用域内同一事务只取一次；独占锁和作用域外的调用逐次执行。 */
    public void lock(boolean exclusive) {
        if (exclusive) {
            applications.automationCatalogLock(true);
            return;
        }
        ReadRequestMemo.onceInTransaction(
                ReadRequestMemo.key("automation.catalog.shared-lock"),
                () -> {
                    applications.automationCatalogLock(false);
                    return Boolean.TRUE;
                });
    }

    /** 只读作用域内同一事务（或同一请求的无事务段）只读取、解析一次已发布目录。 */
    public List<ApplicationAutomations.Published> published() {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("automation.catalog.published"), this::readPublished);
    }

    private List<ApplicationAutomations.Published> readPublished() {
        return applications.automationSnapshots().stream()
                .map(
                        raw -> {
                            try {
                                return json.readValue(raw, ApplicationAutomations.Published.class);
                            } catch (java.io.IOException e) {
                                throw invalid("已发布自动更新规则无法读取");
                            }
                        })
                .toList();
    }

    public ApplicationAutomations.Config config(ApplicationCenter.Resource resource) {
        return json.convertValue(resource.config(), ApplicationAutomations.Config.class);
    }

    /** 留存动作与自动更新共用发布目录和锁，目标字段保护覆盖所有应用写入入口。 */
    public record Capture(
            String app, int version, String id, String name, ApplicationBusiness.Action action) {}

    public List<Capture> captures(String object) {
        List<Capture> result = new ArrayList<>();
        for (ApplicationAutomations.Published published : published()) {
            for (ApplicationCenter.Resource resource : published.definition().resources()) {
                if (!ApplicationResourceKindEnum.ACTION.matches(resource.kind())
                        || !BusinessActionKindEnum.CAPTURE_VALUES.matches(
                                Objects.toString(resource.config().get("kind"), ""))) continue;
                ApplicationBusiness.Action action =
                        json.convertValue(resource.config(), ApplicationBusiness.Action.class);
                if (object == null || object.equals(action.objectId()))
                    result.add(
                            new Capture(
                                    published.applicationId(),
                                    published.version(),
                                    resource.id(),
                                    resource.name(),
                                    action));
            }
        }
        return result;
    }

    /** 发布时防止跨应用多重写入、循环和越过共享上限；运行仍检查当前操作者实时权限。 */
    public void validate(String app, ApplicationCenter.Definition candidate) {
        var all = new ArrayList<ApplicationAutomations.Config>();
        for (var p : published())
            if (!p.applicationId().equals(app))
                for (var r : p.definition().resources())
                    if (ApplicationResourceKindEnum.AUTOMATION.matches(r.kind()))
                        all.add(config(r));
        for (var r : candidate.resources()) {
            if (!ApplicationResourceKindEnum.AUTOMATION.matches(r.kind())) continue;
            var c = config(r);
            all.add(c);
            if (Boolean.FALSE.equals(c.enabled())) continue;
            var source = sharing.storedPermission(c.objectId(), app);
            var target = resolvedPermission(app, candidate, c.targetObjectId());
            if (source == null
                    || !source.actions().contains(ApplicationActionEnum.READ.getCode())
                    || !ApplicationScopeEnum.ALL.matches(source.scope())
                    || source.actionScopes().containsKey(ApplicationActionEnum.READ.getCode()))
                throw invalid("自动更新“" + r.name() + "”需要来源对象完整读取共享授权");
            if (target == null
                    || !target.actions().contains(ApplicationActionEnum.UPDATE.getCode())
                    || !target.writeFields()
                            .containsAll(
                                    c.assignments().stream()
                                            .map(ApplicationAutomations.Assignment::fieldId)
                                            .toList()))
                throw invalid("自动更新“" + r.name() + "”缺少目标对象修改及目标字段共享授权");
        }
        validator.graph(all);
        validateCaptureOwnership(app, candidate);
    }

    /** 共享上限里的「全部」对着应用定义固定的对象版本展开成普通 ID 集合；没有授权（或已撤销）时为 null。 */
    private ObjectGrant resolvedPermission(
            String app, ApplicationCenter.Definition candidate, String objectId) {
        ObjectGrant stored = sharing.storedPermission(objectId, app);
        if (stored == null) return null;
        Integer pinned =
                candidate.objects().stream()
                        .filter(reference -> reference.objectId().equals(objectId))
                        .map(ApplicationCenter.ObjectReference::versionNo)
                        .findFirst()
                        .orElse(null);
        return grantValidator.resolve(stored, objects.getVersion(objectId, pinned).definition());
    }

    private void validateCaptureOwnership(String app, ApplicationCenter.Definition candidate) {
        List<ApplicationCenter.Definition> definitions = new ArrayList<>();
        for (ApplicationAutomations.Published published : published())
            if (!published.applicationId().equals(app)) definitions.add(published.definition());
        definitions.add(candidate);
        Map<String, String> captureOwners = new HashMap<>();
        Map<String, List<ApplicationAutomations.Config>> automationOwners = new HashMap<>();
        Set<String> captures = new HashSet<>();
        for (ApplicationCenter.Definition definition : definitions) {
            for (ApplicationCenter.Resource resource : definition.resources()) {
                if (ApplicationResourceKindEnum.ACTION.matches(resource.kind())
                        && BusinessActionKindEnum.CAPTURE_VALUES.matches(
                                Objects.toString(resource.config().get("kind"), ""))) {
                    ApplicationBusiness.Action action =
                            json.convertValue(resource.config(), ApplicationBusiness.Action.class);
                    for (String field : action.captures().keySet()) {
                        String key = action.objectId() + ":" + field;
                        if (captureOwners.putIfAbsent(key, resource.name()) != null
                                || automationOwners.containsKey(key))
                            throw invalid("留存字段已被其他动作或自动更新使用：" + resource.name());
                        captures.add(key);
                    }
                    if (definition == candidate) {
                        ObjectGrant permission =
                                resolvedPermission(app, candidate, action.objectId());
                        if (permission == null
                                || !permission
                                        .actions()
                                        .contains(ApplicationActionEnum.READ.getCode())
                                || !permission
                                        .actions()
                                        .contains(ApplicationActionEnum.UPDATE.getCode())
                                || !permission.readFields().containsAll(action.captures().values())
                                || !permission
                                        .writeFields()
                                        .containsAll(action.captures().keySet()))
                            throw invalid("留存动作缺少来源公式读取或目标字段修改共享授权：" + resource.name());
                    }
                } else if (ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) {
                    ApplicationAutomations.Config config = config(resource);
                    if (Boolean.FALSE.equals(config.enabled())) continue;
                    for (ApplicationAutomations.Assignment assignment : config.assignments()) {
                        String key = config.targetObjectId() + ":" + assignment.fieldId();
                        if (captureOwners.containsKey(key))
                            throw invalid("字段不能同时被留存动作和其他规则写入：" + resource.name());
                        List<ApplicationAutomations.Config> fieldOwners =
                                automationOwners.computeIfAbsent(key, ignored -> new ArrayList<>());
                        if (fieldOwners.stream()
                                .anyMatch(owner -> !validator.allowsSharedWrite(owner, config)))
                            throw invalid("持续维护的字段由该规则独占：多个自动更新规则不能写入同一目标字段");
                        fieldOwners.add(config);
                    }
                }
            }
        }
        for (ApplicationCenter.Definition definition : definitions) {
            for (ApplicationCenter.Resource resource : definition.resources()) {
                if (ApplicationResourceKindEnum.ACTION.matches(resource.kind())
                        && BusinessActionKindEnum.UPDATE_FIELDS.matches(
                                Objects.toString(resource.config().get("kind"), ""))) {
                    ApplicationBusiness.Action action =
                            json.convertValue(resource.config(), ApplicationBusiness.Action.class);
                    if (action.values().keySet().stream()
                            .anyMatch(id -> captures.contains(action.objectId() + ":" + id)))
                        throw invalid("普通更新动作不能写入留存字段：" + resource.name());
                } else if (ApplicationResourceKindEnum.NUMBER_RULE.matches(resource.kind())) {
                    ApplicationBusiness.NumberRule rule =
                            json.convertValue(
                                    resource.config(), ApplicationBusiness.NumberRule.class);
                    if (captures.contains(rule.objectId() + ":" + rule.fieldId()))
                        throw invalid("留存字段不能同时用于业务编号");
                }
            }
        }
    }
}
