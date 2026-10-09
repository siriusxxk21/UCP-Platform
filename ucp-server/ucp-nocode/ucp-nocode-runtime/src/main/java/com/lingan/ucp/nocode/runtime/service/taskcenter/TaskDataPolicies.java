package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 总任务资源和权限的固定快照；模板发起只能继承批准范围，不能以模板为普通创建者扩权。 */
@Component
public class TaskDataPolicies {
    public static final String BUSINESS = "__business";
    @Resource private TaskBusiness business;
    @Resource private TaskDataPolicyCompiler compiler;
    @Resource private ObjectMapper json;
    @Resource private TaskStandardWork standardWork;

    public record FrozenRoot(NodeInput task, Frozen grants) {}

    public FrozenRoot freeze(NodeInput root, long actor) {
        return freeze(root, actor, null);
    }

    /** 重新批准范围不隐式升级既有资源版本；换资源才解析新的发布引用。 */
    public FrozenRoot freeze(NodeInput root, long actor, FrozenRoot previous) {
        if (root == null || root.dataPolicy() == null) return null;
        DataPolicy policy = root.dataPolicy();
        if (policy.version() != 1 || policy.business() == null || policy.feedback() == null)
            throw invalid("请选择有效的总任务业务和反馈数据权限");
        List<ResourceGrant> resources = new ArrayList<>();
        if (root.binding() != null)
            resources.add(
                    compiler.compile(
                            BUSINESS,
                            root.binding(),
                            reference(BUSINESS, root.binding(), previous),
                            actor));
        Set<String> keys = new HashSet<>(Set.of(BUSINESS));
        if (root.entries() != null)
            for (TaskWorkEntries.Config entry : root.entries()) {
                // 旧单业务项的配置仍使用原保留身份，不能重复批准或迁移历史数据集。
                if (entry != null
                        && BUSINESS.equals(entry.key())
                        && root.binding() != null
                        && Objects.equals(root.binding(), entry.binding())) {
                    standardWork.validate(
                            entry.workRule(),
                            business.model(resources.get(0).ref(), entry.binding(), actor),
                            entry);
                    continue;
                }
                if (entry == null || entry.binding() == null || !keys.add(entry.key()))
                    throw invalid("总任务反馈资源必须明确绑定表单，且入口标识不能重复或使用保留名称");
                ResourceGrant resource =
                        compiler.compile(
                                entry.key(),
                                entry.binding(),
                                reference(entry.key(), entry.binding(), previous),
                                actor);
                standardWork.validate(
                        entry.workRule(),
                        business.model(resource.ref(), entry.binding(), actor),
                        entry);
                Set<String> readable = new HashSet<>();
                Set<String> writable = new HashSet<>();
                resource.grants()
                        .forEach(
                                grant -> {
                                    readable.addAll(grant.readFields());
                                    writable.addAll(grant.writeFields());
                                });
                if (entry.readableFieldIds() != null
                                && !readable.containsAll(entry.readableFieldIds())
                        || entry.writableFieldIds() != null
                                && !writable.containsAll(entry.writableFieldIds()))
                    throw invalid("任务反馈字段超出可委托的读取或修改权限");
                if (entry.readableFieldIds() != null
                        && entry.writableFieldIds() != null
                        && !entry.readableFieldIds().containsAll(entry.writableFieldIds()))
                    throw invalid("允许修改的反馈字段必须同时允许查看");
                resources.add(resource);
            }
        return new FrozenRoot(root, new Frozen(actor, resources));
    }

    private BusinessRef reference(String key, Binding binding, FrozenRoot previous) {
        if (previous != null)
            for (ResourceGrant grant : previous.grants().resources())
                if (Objects.equals(key, grant.entryKey())
                        && Objects.equals(binding, grant.binding())) return grant.ref();
        return business.resolveForGrant(binding);
    }

    /** 标题、时间和人员可调整；固定业务资源、字段裁剪及数据范围不得借用模板授权修改。 */
    public void requireSame(NodeInput input, FrozenRoot frozen) {
        if (frozen == null) return;
        NodeInput approved = frozen.task();
        if (!Objects.equals(input.dataPolicy(), approved.dataPolicy())
                || !Objects.equals(input.binding(), approved.binding())
                || !Objects.equals(
                        input.entries() == null ? List.of() : input.entries(),
                        approved.entries() == null ? List.of() : approved.entries()))
            throw invalid("模板的数据权限和资源已经固定，请由模板发布者修改后重新发布");
        for (ResourceGrant grant : frozen.grants().resources())
            compiler.revalidate(grant, frozen.grants().grantorId());
    }

    /** 发起时仅允许调整标准工时差额；权限、资源和计量基准仍必须等于模板快照。 */
    public FrozenRoot forLaunch(NodeInput input, FrozenRoot frozen) {
        if (frozen == null) return null;
        requireLaunchEntries(input.entries(), frozen.task().entries());
        if (!Objects.equals(input.dataPolicy(), frozen.task().dataPolicy())
                || !Objects.equals(input.binding(), frozen.task().binding()))
            throw invalid("模板的数据权限和资源已经固定，请由模板发布者修改后重新发布");
        FrozenRoot adjusted = new FrozenRoot(input, frozen.grants());
        requireSame(input, adjusted);
        return adjusted;
    }

    public void requireLaunchEntries(
            List<TaskWorkEntries.Config> input, List<TaskWorkEntries.Config> approved) {
        if (!Objects.equals(workBaselines(input), workBaselines(approved)))
            throw invalid("模板的业务资源、计量规则和基准工时已固定，发起时只能加减本次工时");
        if (input != null)
            for (TaskWorkEntries.Config entry : input) standardWork.validate(entry.workRule());
    }

    /** 模板只保存基准；避免把一次任务的调整意外带入后续发布版本。 */
    public void requireTemplateWorkRules(List<NodeInput> nodes) {
        for (NodeInput node : nodes)
            if (node.entries() != null)
                for (TaskWorkEntries.Config entry : node.entries())
                    if (entry.workRule() != null
                            && entry.workRule().adjustmentMinutes() != null
                            && entry.workRule().adjustmentMinutes() != 0)
                        throw invalid("模板请直接设置标准工时，工时加减只用于本次任务发起");
    }

    private List<TaskWorkEntries.Config> workBaselines(List<TaskWorkEntries.Config> entries) {
        if (entries == null) return List.of();
        if (entries.stream().anyMatch(Objects::isNull)) throw invalid("业务办理项不能为空");
        return entries.stream()
                .map(
                        entry -> {
                            TaskWorkEntries.WorkRule rule = entry.workRule();
                            return new TaskWorkEntries.Config(
                                    entry.key(),
                                    entry.name(),
                                    entry.binding(),
                                    entry.dataMode(),
                                    entry.sourceNodeId(),
                                    entry.sourceEntryKey(),
                                    entry.readableFieldIds(),
                                    entry.writableFieldIds(),
                                    entry.required(),
                                    entry.allowAll(),
                                    rule == null
                                            ? null
                                            : new TaskWorkEntries.WorkRule(
                                                    rule.mode(),
                                                    rule.minutes(),
                                                    rule.quantityFieldId(),
                                                    rule.conditionFieldId(),
                                                    rule.conditionValue()),
                                    entry.dataScope());
                        })
                .toList();
    }

    public String encode(FrozenRoot frozen) {
        if (frozen == null) return null;
        try {
            return json.writeValueAsString(frozen);
        } catch (Exception error) {
            throw invalid("任务数据授权无法保存");
        }
    }

    public FrozenRoot decode(String raw) {
        if (raw == null) return null;
        try {
            return json.readValue(raw, FrozenRoot.class);
        } catch (Exception error) {
            throw invalid("任务数据授权无法读取");
        }
    }
}
