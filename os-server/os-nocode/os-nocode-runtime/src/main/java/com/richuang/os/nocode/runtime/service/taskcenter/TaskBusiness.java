package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.work.PublishedResourceRef;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.enums.HandlingStateEnum;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.handling.BusinessHandlingService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 对象和表单引用固定版本，实际读写仍通过公共授权、校验、审批和记录服务。 */
@Component
public class TaskBusiness {
    @Resource private ApplicationPublishedService published;
    @Resource private ApplicationRuntimePolicy policy;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Resource private BusinessHandlingService handling;
    @Resource private TaskEntryRuntimeService entries;
    @Resource private DataObjectApi objects;
    @Resource private ObjectMapper json;
    @Resource private TaskBoundViews boundViews;
    @Resource private com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope taskScope;

    public BusinessRef resolve(Binding binding, long actor) {
        return resolve(binding, actor, true);
    }

    /** 仅为授权编译器解析发布引用；授权资格必须由编译器核验，不能用此方法执行业务读写。 */
    public BusinessRef resolveForGrant(Binding binding) {
        return resolve(binding, 0, false);
    }

    private BusinessRef resolve(Binding binding, long actor, boolean authorize) {
        if (binding == null) return null;
        ApplicationCenter.Published app = published.getCurrent(binding.applicationId());
        ApplicationCenter.Resource resource =
                app.definition().resources().stream()
                        .filter(r -> r.id().equals(binding.formId()) && "FORM".equals(r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务表单不是已发布的应用表单"));
        ApplicationUi.Form form = json.convertValue(resource.config(), ApplicationUi.Form.class);
        ApplicationCenter.ObjectReference object =
                app.definition().objects().stream()
                        .filter(o -> o.objectId().equals(form.objectId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务表单未引用数据对象"));
        DataObjectApi.PublishedObject version =
                objects.getVersion(object.objectId(), object.versionNo());
        boundViews.scope(boundViews.view(binding, app, object.objectId()), version.definition());
        if (!version.checksum().equals(object.checksum())) throw invalid("对象引用摘要不一致");
        if (authorize && binding.entryId() != null) {
            TaskEntries.Context context =
                    entries.context(
                            new TaskEntries.Locator(
                                    binding.applicationId(), binding.entryId(), app.versionNo()),
                            actor);
            if (!Objects.equals(context.config().formId(), binding.formId())
                    || !context.config().objectId().equals(object.objectId()))
                throw invalid("办理入口与任务表单不匹配");
        } else if (authorize) {
            policy.requireEntry(binding.applicationId(), actor);
            records.model(binding.applicationId(), object.objectId(), actor);
        }
        return new BusinessRef(
                new PublishedResourceRef(
                        binding.applicationId(),
                        app.versionNo(),
                        app.checksum(),
                        resource.id(),
                        "FORM"),
                object,
                null,
                null);
    }

    public ApplicationUi.Form definition(BusinessRef ref) {
        ApplicationCenter.Resource resource = published.resolve(ref.resource());
        return json.convertValue(resource.config(), ApplicationUi.Form.class);
    }

    public DataCenter.Definition currentDefinition(BusinessRef ref) {
        return objects.getPublished(ref.object().objectId());
    }

    /** 全业务列表同样按照冻结及当前视图、表单字段进行裁剪。 */
    public ApplicationRecords.Row project(
            BusinessRef ref, Binding binding, ApplicationRecords.Row row) {
        return boundViews.project(binding, ref, row);
    }

    public void project(RecordRef ref, long actor) {
        if (ref == null) return;
        if (ref.applicationId() == null || ref.objectId() == null || ref.recordId() == null)
            throw invalid("项目需要完整的应用、对象和记录身份");
        records.get(ref.applicationId(), ref.objectId(), ref.recordId(), actor);
    }

    public ApplicationRecords.Aggregate read(BusinessRef ref, Binding binding, long actor) {
        if (ref == null || ref.recordId() == null) return null;
        ApplicationRecords.Aggregate result =
                published.withVersion(
                        ref.resource(),
                        () ->
                                binding != null
                                                && binding.entryId() != null
                                                && !taskScope.delegated()
                                        ? entries.get(
                                                new TaskEntries.Get(
                                                        locator(ref, binding), ref.recordId()),
                                                actor)
                                        : records.get(
                                                ref.resource().applicationId(),
                                                ref.object().objectId(),
                                                ref.recordId(),
                                                actor));
        if (!taskScope.delegated())
            boundViews.requireRecord(
                    binding,
                    ref,
                    result,
                    objects.getVersion(ref.object().objectId(), ref.object().versionNo())
                            .definition());
        return boundViews.project(binding, ref, result);
    }

    public ApplicationRecords.Model model(BusinessRef ref, Binding binding, long actor) {
        boundViews.scope(
                binding,
                ref,
                objects.getVersion(ref.object().objectId(), ref.object().versionNo()).definition());
        ApplicationRecords.Model result =
                published.withVersion(
                        ref.resource(),
                        () ->
                                binding != null
                                                && binding.entryId() != null
                                                && !taskScope.delegated()
                                        ? entries.context(locator(ref, binding), actor).model()
                                        : records.model(
                                                ref.resource().applicationId(),
                                                ref.object().objectId(),
                                                actor));
        return new ApplicationRecords.Model(
                result.object(),
                result.writable(),
                result.generatedKey(),
                result.keyFieldId(),
                result.keyType(),
                result.details(),
                boundViews.capabilities(binding, ref, result.permissions()),
                result.managedFieldIds(),
                result.orderedStates());
    }

    /** 已删除的业务引用不再作为完成材料；任务授权和记录权限仍由公共读链路完整检查。 */
    public ApplicationRecords.Aggregate readIfPresent(
            BusinessRef ref, Binding binding, long actor) {
        if (ref == null || ref.recordId() == null) return null;
        if (binding != null && binding.entryId() != null && !taskScope.delegated())
            return read(ref, binding, actor);
        ApplicationRecords.Aggregate result =
                published.withVersion(
                        ref.resource(),
                        () ->
                                records.getIfPresent(
                                        ref.resource().applicationId(),
                                        ref.object().objectId(),
                                        ref.recordId(),
                                        actor));
        if (!taskScope.delegated())
            boundViews.requireRecord(
                    binding,
                    ref,
                    result,
                    objects.getVersion(ref.object().objectId(), ref.object().versionNo())
                            .definition());
        return boundViews.project(binding, ref, result);
    }

    public BusinessRef existing(BusinessRef ref, Binding binding, RecordRef existing, long actor) {
        if (ref == null
                || !ref.resource().applicationId().equals(existing.applicationId())
                || !ref.object().objectId().equals(existing.objectId())
                || existing.recordId() == null) throw invalid("已有记录与任务表单不匹配");
        BusinessRef resolved =
                new BusinessRef(ref.resource(), ref.object(), existing.recordId(), null);
        read(resolved, binding, actor);
        return resolved;
    }

    public BusinessRef save(
            BusinessRef ref,
            Binding binding,
            Sharing sharing,
            ApplicationRecords.Save input,
            long actor) {
        if (ref == null || input == null) throw invalid("缺少任务表单或业务输入");
        if (binding != null && binding.viewId() != null) {
            read(ref, binding, actor);
            ApplicationAuthorization.Capabilities caps = model(ref, binding, actor).permissions();
            Set<String> writable = caps.writeFields();
            if (input.values() != null && !writable.containsAll(input.values().keySet()))
                throw invalid("输入包含当前办理视图或表单不允许修改的字段");
            if (input.details() != null
                            && !caps.writeDetails().containsAll(input.details().keySet())
                    || input.relations() != null
                            && !caps.writeRelations().containsAll(input.relations().keySet()))
                throw invalid("输入包含当前办理视图或表单不允许修改的明细或关联");
            if (input.relatedRecords() != null && !input.relatedRecords().isEmpty())
                throw invalid("视图办理暂不支持跨对象联合保存，请把关联对象配置为独立办理项");
        }
        if (ref.requestId() != null) {
            BusinessHandling.Request previous =
                    handling.detail(ref.requestId(), null, actor).request();
            if (!resubmittable(previous)) throw invalid("已有审批申请尚未生效，请先处理原申请");
            // 复核原申请归属、当前权限及记录修订；旧申请和材料保持不变。
            published.withVersion(ref.resource(), () -> handling.reopen(ref.requestId(), actor));
        }
        if (!Objects.equals(ref.resource().applicationId(), input.applicationId())
                || !Objects.equals(ref.object().objectId(), input.objectId())
                || !Objects.equals(ref.recordId(), input.id())
                || input.context() != null
                || input.actionCode() != null
                || input.formId() != null
                        && !Objects.equals(input.formId(), ref.resource().resourceId()))
            throw invalid("业务输入与任务固定表单及记录不匹配");
        if (sharing.mode() == DataMode.SHARED) {
            Set<String> allowed = new HashSet<>(sharing.writableFieldIds());
            if (input.values() != null && !allowed.containsAll(input.values().keySet()))
                throw invalid("当前节点不能补充这些共享字段");
            if (input.details() != null && !input.details().isEmpty()
                    || input.relations() != null && !input.relations().isEmpty()
                    || input.relatedRecords() != null && !input.relatedRecords().isEmpty())
                throw invalid("共享节点不能修改明细或关联记录");
        }
        ApplicationRecords.Save command =
                new ApplicationRecords.Save(
                        ref.resource().applicationId(),
                        ref.object().objectId(),
                        ref.recordId(),
                        input.expectedRevision(),
                        input.values(),
                        input.details(),
                        input.relations(),
                        null,
                        ref.resource().resourceId(),
                        input.requestKey(),
                        null,
                        input.relatedRecords());
        BusinessHandling.Result result =
                published.withVersion(
                        ref.resource(),
                        () ->
                                binding != null
                                                && binding.entryId() != null
                                                && !taskScope.delegated()
                                        ? entries.submit(
                                                new TaskEntries.Save(
                                                        locator(ref, binding), command),
                                                actor)
                                        : handling.submit(command, actor));
        if (binding != null && binding.viewId() != null) {
            if (result.request() != null) throw invalid("视图办理暂不支持待审批写入，请使用不需要审批的办理表单");
            if (!taskScope.delegated())
                boundViews.requireRecord(
                        binding,
                        ref,
                        result.result(),
                        objects.getVersion(ref.object().objectId(), ref.object().versionNo())
                                .definition());
        }
        return new BusinessRef(
                ref.resource(),
                ref.object(),
                result.result() == null ? ref.recordId() : result.result().record().id(),
                result.request() == null ? null : result.request().id());
    }

    public BusinessRef refresh(BusinessRef ref, long actor) {
        if (ref == null || ref.requestId() == null) return ref;
        BusinessHandling.Detail request = handling.detail(ref.requestId(), null, actor);
        if ("APPROVED".equals(request.request().status()) && request.request().recordId() != null)
            return new BusinessRef(
                    ref.resource(), ref.object(), request.request().recordId(), null);
        return ref;
    }

    public BusinessHandling.Result result(BusinessRef ref, Binding binding, long actor) {
        if (ref.requestId() != null) {
            BusinessHandling.Request request =
                    handling.detail(ref.requestId(), null, actor).request();
            String outcome =
                    resubmittable(request)
                                    || HandlingStateEnum.APPLY_FAILED.matches(request.status())
                            ? request.status()
                            : "SUBMITTED";
            return new BusinessHandling.Result(outcome, null, request);
        }
        return new BusinessHandling.Result("EFFECTIVE", read(ref, binding, actor), null);
    }

    /** 调用方先证明本任务、本操作者的保存事件；回执材料仍按当前记录和入口权限重新投影。 */
    public BusinessHandling.Result receipt(
            BusinessRef ref, Binding binding, String key, long actor) {
        model(ref, binding, actor);
        BusinessHandling.Result receipt =
                published.withVersion(
                        ref.resource(),
                        () ->
                                handling.receipt(
                                        new BusinessHandling.Receipt(
                                                ref.resource().applicationId(),
                                                ref.object().objectId(),
                                                key),
                                        actor));
        if (receipt == null) throw invalid("任务已记录提交，但业务回执暂不可确认，请勿重复新建");
        if (receipt.request() != null) {
            BusinessRef submitted =
                    new BusinessRef(
                            ref.resource(),
                            ref.object(),
                            receipt.request().recordId(),
                            receipt.request().id());
            return result(refresh(submitted, actor), binding, actor);
        }
        if (receipt.result() == null) throw invalid("业务回执缺少提交结果，请刷新后确认");
        BusinessRef saved =
                new BusinessRef(ref.resource(), ref.object(), receipt.result().record().id(), null);
        ApplicationRecords.Aggregate current = read(saved, binding, actor);
        return new BusinessHandling.Result(
                "EFFECTIVE",
                TaskMaterials.project(receipt.result(), current, currentDefinition(saved)),
                null);
    }

    /** 驳回/撤回恢复封存的填写意图；公共恢复入口会重新裁剪权限并检查业务修订。 */
    public ApplicationRecords.Aggregate formRecord(BusinessRef ref, Binding binding, long actor) {
        if (ref.requestId() != null) {
            BusinessHandling.Request request =
                    handling.detail(ref.requestId(), null, actor).request();
            if (resubmittable(request))
                return published.withVersion(
                        ref.resource(), () -> handling.reopen(ref.requestId(), actor).initial());
        }
        return read(ref, binding, actor);
    }

    private boolean resubmittable(BusinessHandling.Request request) {
        return HandlingStateEnum.REJECTED.matches(request.status())
                || HandlingStateEnum.CANCELED.matches(request.status());
    }

    public List<String> writable(BusinessRef ref, Binding binding, Sharing sharing, long actor) {
        ApplicationRecords.Model model = model(ref, binding, actor);
        Set<String> fields = new HashSet<>(model.permissions().writeFields());
        ApplicationRecords.Aggregate record = read(ref, binding, actor);
        if (record != null && record.record().permissions() != null)
            fields.retainAll(record.record().permissions().writeFields());
        if (sharing.mode() == DataMode.SHARED) fields.retainAll(sharing.writableFieldIds());
        return fields.stream().sorted().toList();
    }

    private TaskEntries.Locator locator(BusinessRef ref, Binding binding) {
        return new TaskEntries.Locator(
                ref.resource().applicationId(),
                binding.entryId(),
                ref.resource().applicationVersion());
    }
}
