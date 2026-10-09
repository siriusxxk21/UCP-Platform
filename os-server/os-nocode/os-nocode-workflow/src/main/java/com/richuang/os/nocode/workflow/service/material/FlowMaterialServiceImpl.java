package com.richuang.os.nocode.workflow.service.material;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.bpm.api.task.BpmProcessTaskApi;
import com.richuang.os.module.bpm.api.task.BpmTaskMaterialReviewGuard;
import com.richuang.os.module.bpm.api.task.dto.BpmMaterialReviewContextDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.workflow.FlowMaterials;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.work.service.submission.WorkSubmissionService;
import com.richuang.os.nocode.workflow.service.task.FlowTaskBindingService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 任务身份由 BPM 核验，业务成果由来源绑定核验，字段授权在每次读取及通过时重算。 */
@Service
public class FlowMaterialServiceImpl implements FlowMaterialService, BpmTaskMaterialReviewGuard {
    @Resource private BpmProcessTaskApi tasks;
    @Resource private FlowTaskBindingService bindings;
    @Resource private WorkSubmissionService submissions;
    @Resource private ApplicationPublishedService published;
    @Resource private DataObjectApi objects;
    @Resource private RecordService records;
    @Resource private ObjectMapper json;

    @Value("${nocode.material-review-token-key:}")
    private String configuredKey;

    private byte[] tokenKey;

    @PostConstruct
    void initialize() {
        tokenKey = new byte[32];
        if (configuredKey == null || configuredKey.isBlank())
            new SecureRandom().nextBytes(tokenKey);
        else tokenKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public FlowMaterials.Page list(FlowMaterials.Query command, long actor) {
        if (command == null) throw invalid("请选择流程任务");
        BpmMaterialReviewContextDTO context =
                tasks.materialReviewContext(actor, command.processInstanceId(), command.taskId());
        FlowMaterialServiceImpl.Rendered result = render(context, actor);
        boolean required = reviewRequired(context, result);
        return new FlowMaterials.Page(
                result.items(),
                context.active() && result.blocked() == null ? token(context, result, actor) : null,
                required,
                result.blocked());
    }

    @Override
    public FlowMaterials.Detail detail(FlowMaterials.Get command, long actor) {
        if (command == null || command.materialId() == null) throw invalid("请选择审批材料");
        BpmMaterialReviewContextDTO context =
                tasks.materialReviewContext(actor, command.processInstanceId(), command.taskId());
        FlowMaterialServiceImpl.Rendered result = render(context, actor);
        FlowMaterials.Detail detail = result.details().get(command.materialId());
        if (detail == null) throw invalid("材料不存在或不属于当前任务可查阅的材料");
        return detail;
    }

    @Override
    public void validate(long actor, String taskId, String reviewToken) {
        // 空实例参数仅供已认证内部任务校验：BPM 从真实 taskId 解析实例，HTTP 仍必须提交实例 ID。
        BpmMaterialReviewContextDTO context = tasks.materialReviewContext(actor, null, taskId);
        if (context.businessTask()) return;
        FlowMaterialServiceImpl.Rendered result = render(context, actor);
        if (!reviewRequired(context, result)) return;
        if (result.blocked() != null) throw invalid(result.blocked());
        if (reviewToken == null || !validToken(reviewToken, context, result, actor))
            throw invalid("审批材料或可读字段已变化，请刷新并重新查阅后通过");
    }

    @Override
    public boolean requiresReview(long actor, String taskId) {
        BpmMaterialReviewContextDTO context = tasks.materialReviewContext(actor, null, taskId);
        return reviewRequired(context, render(context, actor));
    }

    private boolean reviewRequired(BpmMaterialReviewContextDTO context, Rendered result) {
        return context.active()
                && !context.businessTask()
                && (!result.items().isEmpty() || result.blocked() != null);
    }

    private Rendered render(BpmMaterialReviewContextDTO context, long actor) {
        ArrayList<FlowMaterials.Item> items = new ArrayList<FlowMaterials.Item>();
        LinkedHashMap<String, FlowMaterials.Detail> details =
                new LinkedHashMap<String, FlowMaterials.Detail>();
        String blocked = context.blockedReason();
        for (BpmMaterialReviewContextDTO.Material fact : context.materials()) {
            String kind =
                    fact.business() == null
                            ? FlowMaterialKindEnum.FLOW_FORM.getCode()
                            : FlowMaterialKindEnum.APPLICATION_FORM.getCode();
            String id = kind + ":" + fact.taskId();
            String name = fact.form() == null ? "业务表单" : fact.form().name();
            FlowMaterials.FlowForm flow = null;
            FlowMaterials.BusinessForm business = null;
            String warning = fact.warning();
            boolean unavailable = false;
            try {
                if (fact.business() != null) {
                    com.richuang.os.nocode.workflow.dal.dataobject.task.FlowTaskBindingDO row =
                            bindings.read(fact.taskId());
                    if (row == null
                            || row.getSubmissionId() == null
                            || !Objects.equals(row.getSubmitter(), fact.submitterId()))
                        throw invalid("前序业务材料未完成封存");
                    bindings.match(row, fact.business());
                    com.richuang.os.nocode.api.work.WorkDrafts.Submission submission =
                            submissions.flowMaterial(
                                    row.getSubmissionId(),
                                    fact.taskId(),
                                    Long.parseLong(fact.submitterId()));
                    com.richuang.os.nocode.api.workflow.FlowTasks.Configuration configuration =
                            bindings.configuration(fact.business());
                    if (!Objects.equals(configuration.resource(), submission.resource()))
                        throw invalid("前序材料与固定表单版本不一致");
                    ApplicationCenter.Resource resource = published.resolve(submission.resource());
                    name = resource.name();
                    ApplicationUi.Form source =
                            json.convertValue(resource.config(), ApplicationUi.Form.class);
                    if (!Objects.equals(source.objectId(), submission.objectId()))
                        throw invalid("材料对象不一致");
                    ApplicationCenter.Published app =
                            published.getVersion(
                                    submission.resource().applicationId(),
                                    submission.resource().applicationVersion());
                    ApplicationCenter.ObjectReference ref =
                            app.definition().objects().stream()
                                    .filter(o -> o.objectId().equals(source.objectId()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("材料对象版本不存在"));
                    DataObjectApi.PublishedObject version =
                            objects.getVersion(source.objectId(), ref.versionNo());
                    if (!Objects.equals(version.checksum(), ref.checksum()))
                        throw invalid("材料对象版本校验失败");
                    DataCenter.Definition definition = version.definition();
                    DataCenter.Definition current = objects.getPublished(source.objectId());
                    LinkedHashSet<String> visible = new LinkedHashSet<String>();
                    collect(source.nodes(), visible);
                    visible.retainAll(submission.values().keySet());
                    // 源表单与实际提交决定分享上限，当前字段分类可立即收紧历史 TASK 授权。
                    visible.removeIf(
                            field -> !shareable(definition, field) || !shareable(current, field));
                    if ("BUSINESS".equals(context.access())) {
                        ApplicationRecords.Aggregate live =
                                published.withVersion(
                                        submission.resource(),
                                        () ->
                                                records.get(
                                                        submission.resource().applicationId(),
                                                        source.objectId(),
                                                        submission.recordId(),
                                                        actor));
                        visible.retainAll(live.record().permissions().readFields());
                    }
                    if (visible.isEmpty()) throw invalid("当前权限下没有可查阅的已提交字段");
                    LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
                    visible.forEach(field -> values.put(field, submission.values().get(field)));
                    ApplicationUi.Form form =
                            new ApplicationUi.Form(
                                    source.objectId(),
                                    sanitize(source.nodes(), visible),
                                    List.of(),
                                    new ApplicationUi.FormOptions("TOP", null, true));
                    List<FieldDefinition> fields =
                            definition.fields().stream()
                                    .filter(field -> visible.contains(field.id()))
                                    .toList();
                    LinkedHashMap<String, DataCenter.FieldOptions> options =
                            new LinkedHashMap<String, DataCenter.FieldOptions>();
                    for (String field : visible) {
                        var original = definition.fieldOptions().get(field);
                        // 材料查阅只投影展示所需配置；对象规则含来源对象与条件，不向查阅方下发。
                        options.put(
                                field,
                                new DataCenter.FieldOptions(
                                        null,
                                        original.classification(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        original.state(),
                                        original.options(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null));
                    }
                    DataCenter.Definition safe =
                            new DataCenter.Definition(
                                    definition.objectId(),
                                    definition.objectCode(),
                                    definition.objectName(),
                                    null,
                                    null,
                                    null,
                                    definition.source(),
                                    true,
                                    visible.contains(definition.titleFieldId())
                                            ? definition.titleFieldId()
                                            : null,
                                    null,
                                    fields,
                                    options,
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    null);
                    ApplicationAuthorization.Capabilities caps =
                            new ApplicationAuthorization.Capabilities(
                                    Set.of(ApplicationActionEnum.READ.getCode()),
                                    visible,
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    business =
                            new FlowMaterials.BusinessForm(
                                    submission.resource().applicationId(),
                                    submission.recordId(),
                                    form,
                                    new ApplicationRecords.Model(
                                            safe, false, false, null, null, Map.of(), caps),
                                    values,
                                    Map.of());
                } else if (fact.form() != null) {
                    flow =
                            new FlowMaterials.FlowForm(
                                    fact.form().conf(), fact.form().fields(), fact.form().values());
                    unavailable = fact.form().values().isEmpty();
                }
            } catch (ServiceException unavailableSource) {
                unavailable = true;
                warning = unavailableSource.getMessage();
            } catch (org.springframework.security.access.AccessDeniedException denied) {
                unavailable = true;
                warning = "当前没有查阅该材料的权限";
            }
            if (unavailable && blocked == null) blocked = "部分材料暂不可查阅，请查看对应材料的具体说明";
            FlowMaterials.Item item =
                    new FlowMaterials.Item(
                            id,
                            fact.taskId(),
                            fact.nodeId(),
                            fact.nodeName(),
                            name,
                            fact.submitterName(),
                            fact.submittedAt(),
                            1,
                            kind,
                            unavailable
                                    ? FlowMaterialStateEnum.UNAVAILABLE.getCode()
                                    : FlowMaterialStateEnum.CURRENT.getCode(),
                            true,
                            warning);
            items.add(item);
            details.put(
                    id,
                    new FlowMaterials.Detail(
                            item, unavailable ? null : flow, unavailable ? null : business));
        }
        return new Rendered(items, details, blocked);
    }

    private boolean shareable(DataCenter.Definition definition, String field) {
        DataCenter.FieldOptions options = definition.fieldOptions().get(field);
        FieldDefinition metadata =
                definition.fields().stream()
                        .filter(f -> field.equals(f.id()))
                        .findFirst()
                        .orElse(null);
        return metadata != null
                && options != null
                && DataClassificationEnum.NORMAL.matches(options.classification())
                && MemberStateEnum.ACTIVE.matches(options.state())
                && !Set.of(
                                FieldTypeEnum.IMAGE.getCode(),
                                FieldTypeEnum.ATTACHMENT.getCode(),
                                FieldTypeEnum.RICH_TEXT.getCode())
                        .contains(metadata.type());
    }

    private void collect(List<ApplicationUi.Node> nodes, Set<String> fields) {
        if (nodes == null) return;
        for (ApplicationUi.Node node : nodes) {
            if ("FIELD".equals(node.type()) && node.fieldId() != null) fields.add(node.fieldId());
            collect(node.children(), fields);
        }
    }

    private List<ApplicationUi.Node> sanitize(List<ApplicationUi.Node> nodes, Set<String> fields) {
        if (nodes == null) return List.of();
        ArrayList<ApplicationUi.Node> output = new ArrayList<ApplicationUi.Node>();
        for (ApplicationUi.Node node : nodes) {
            if ("FIELD".equals(node.type()) && fields.contains(node.fieldId())) {
                ApplicationUi.FieldPresentation p = node.presentation();
                output.add(
                        new ApplicationUi.Node(
                                node.id(),
                                node.type(),
                                node.fieldId(),
                                null,
                                null,
                                node.span(),
                                List.of(),
                                null,
                                new ApplicationUi.FieldPresentation(
                                        p == null ? null : p.label(), null, null, true)));
            } else output.addAll(sanitize(node.children(), fields));
        }
        return output;
    }

    private String token(BpmMaterialReviewContextDTO context, Rendered result, long actor) {
        String stamp = Long.toString(System.currentTimeMillis());
        return stamp + "." + signature(stamp, context, result, actor);
    }

    private boolean validToken(
            String token, BpmMaterialReviewContextDTO context, Rendered result, long actor) {
        try {
            String[] parts = token.split("\\.", -1);
            long elapsed = System.currentTimeMillis() - Long.parseLong(parts[0]);
            return parts.length == 2
                    && elapsed >= 0
                    && elapsed <= 30 * 60_000L
                    && MessageDigest.isEqual(
                            parts[1].getBytes(StandardCharsets.UTF_8),
                            signature(parts[0], context, result, actor)
                                    .getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String signature(
            String stamp, BpmMaterialReviewContextDTO context, Rendered result, long actor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            // 包含材料值、固定版本呈现及实时字段投影；权限收紧后旧页面不能直接通过。
            String input =
                    stamp
                            + ":"
                            + actor
                            + ":"
                            + context.tenantId()
                            + ":"
                            + context.processInstanceId()
                            + ":"
                            + context.processDefinitionId()
                            + ":"
                            + context.taskId()
                            + ":"
                            + context.access()
                            + ":"
                            + json.writeValueAsString(result);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("审批材料令牌生成失败", failure);
        }
    }

    private record Rendered(
            List<FlowMaterials.Item> items,
            Map<String, FlowMaterials.Detail> details,
            String blocked) {}
}
