package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService;
import com.lingan.ucp.nocode.runtime.service.bizfile.TaskBusinessFileSessions;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.lingan.ucp.nocode.runtime.service.task.TaskGroupRuntime;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 每个附件请求重新验证任务身份、生命周期和固定表单投影，再进入既有文件底座。 临时会话按任务节点、办理项和记录隔离，不登记应用成员，不为旧任务补授权限。 */
@Service
public class TaskBusinessFileServiceImpl implements TaskBusinessFileService {
    @Resource private TaskWorkEntryService entries;
    @Resource private TaskGroupRuntime runtime;
    @Resource private ApplicationPublishedService published;
    @Resource private BizFileBrowseService browse;
    @Resource private BizFileUploadService uploads;
    @Resource private TaskEntryRuntimeScope taskScope;

    @Override
    public PageResult<BusinessFiles.File> files(TaskBusinessFiles.Files request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少任务附件查询");
        BusinessFiles.FileQuery query = request.query();
        return execute(
                request.target(),
                actor,
                false,
                form -> {
                    location(form, query.applicationId(), query.objectId(), query.recordId(), true);
                    if (query.groupKeys() != null && !query.groupKeys().isEmpty())
                        throw invalid("任务办理不开放业务文件目录");
                    field(form, query.detailId(), query.rowId(), query.fieldId(), false);
                    return browse.files(query, actor);
                });
    }

    @Override
    public BusinessFiles.Content content(TaskBusinessFiles.Content request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少任务附件内容查询");
        BusinessFiles.ContentQuery query = request.query();
        return execute(
                request.target(),
                actor,
                false,
                form -> {
                    location(form, query.applicationId(), query.objectId(), query.recordId(), true);
                    field(form, query.detailId(), query.rowId(), query.fieldId(), false);
                    return browse.content(query, actor);
                });
    }

    /** 根任务锁与上传登记同事务，转交/完成不能与旧编辑窗口的上传交叉放行。 */
    @Override
    @Transactional
    public BusinessFiles.Uploaded upload(
            TaskBusinessFiles.Upload request, long actor, InputStream content) {
        if (request == null || request.query() == null) throw invalid("缺少任务附件上传内容");
        BusinessFiles.UploadQuery query = request.query();
        return execute(
                request.target(),
                actor,
                true,
                form -> {
                    location(
                            form, query.applicationId(), query.objectId(), query.recordId(), false);
                    field(form, query.detailId(), null, query.fieldId(), true);
                    return uploads.upload(
                            new BusinessFiles.UploadQuery(
                                    query.applicationId(),
                                    query.objectId(),
                                    query.recordId(),
                                    query.detailId(),
                                    query.fieldId(),
                                    session(request.target(), form, query.sessionKey()),
                                    query.idempotencyKey(),
                                    query.fileName(),
                                    query.contentType(),
                                    query.size()),
                            actor,
                            content);
                });
    }

    @Override
    @Transactional
    public BusinessFiles.TemporaryContent temporaryContent(
            TaskBusinessFiles.Temporary request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少任务临时附件查询");
        TaskBusinessFiles.TemporaryQuery query = request.query();
        return execute(
                request.target(),
                actor,
                true,
                form -> {
                    if (!Objects.equals(form.binding().object().objectId(), query.objectId()))
                        throw invalid("附件对象不属于当前任务表单");
                    field(form, query.detailId(), null, query.fieldId(), true);
                    return uploads.temporaryContent(
                            new BusinessFiles.TemporaryQuery(
                                    query.objectId(), query.fieldId(),
                                    session(request.target(), form, query.sessionKey()),
                                            query.fileId()),
                            actor);
                });
    }

    @Override
    @Transactional
    public boolean renew(TaskBusinessFiles.Renew request, long actor) {
        if (request == null) throw invalid("缺少任务附件续期请求");
        return execute(
                request.target(),
                actor,
                true,
                form ->
                        uploads.renew(
                                session(request.target(), form, request.sessionKey()), actor));
    }

    @Override
    public InputStream contentStream(BusinessFiles.Content content, long offset) {
        return browse.contentStream(content, offset);
    }

    @Override
    public InputStream temporaryStream(BusinessFiles.TemporaryContent content, long offset) {
        return uploads.temporaryStream(content, offset);
    }

    private <T> T execute(
            TaskWorkEntries.Form target,
            long actor,
            boolean write,
            Function<TaskCenter.FormContext, T> action) {
        if (target == null || target.taskId() == null || target.entryKey() == null)
            throw invalid("缺少任务办理项身份");
        return runtime.execute(
                target.taskId(),
                target.entryKey(),
                actor,
                write,
                () -> {
                    TaskCenter.FormContext form = entries.form(target, actor);
                    // 旧任务没有委托快照时仍需本人进行中身份，业务授权继续走普通应用链。
                    if (write
                            && entries.entries(target.taskId(), actor).stream()
                                    .noneMatch(
                                            entry ->
                                                    target.entryKey().equals(entry.config().key())
                                                            && entry.canWrite()))
                        throw invalid("当前任务不可办理，不能上传或续期附件");
                    return published.withVersion(
                            form.binding().resource(), () -> action.apply(form));
                });
    }

    private void location(
            TaskCenter.FormContext form,
            String app,
            String object,
            String record,
            boolean existing) {
        TaskCenter.BusinessRef ref = form.binding();
        if (!Objects.equals(ref.resource().applicationId(), app)
                || !Objects.equals(ref.object().objectId(), object)
                || !Objects.equals(ref.recordId(), record)
                || existing && (record == null || record.isBlank()))
            throw invalid("附件位置必须属于当前任务的固定表单和记录");
    }

    /** 表单模型是办理项投影后的上限；记录能力和明细行还须在同一上限内再次收窄。 */
    private void field(
            TaskCenter.FormContext form, String detail, String row, String field, boolean write) {
        if (field == null || field.isBlank()) throw invalid("必须指定当前表单附件字段");
        if (write
                && form.form().options() != null
                && Boolean.TRUE.equals(form.form().options().readOnly()))
            throw invalid("当前任务表单为只读，不能上传附件");
        ApplicationAuthorization.Capabilities caps = form.model().permissions();
        ApplicationAuthorization.Capabilities recordCaps =
                form.record() == null ? caps : form.record().record().permissions();
        if (detail == null) {
            if (!present(form.form().nodes(), field, null, write)) throw invalid("附件字段未在当前任务表单开放");
            if (row != null
                    || !caps.readFields().contains(field)
                    || !recordCaps.readFields().contains(field)
                    || write
                            && (!caps.writeFields().contains(field)
                                    || !recordCaps.writeFields().contains(field)))
                throw invalid("当前任务表单不允许访问此附件字段");
            if (form.model().object().fields().stream().noneMatch(item -> field.equals(item.id())))
                throw invalid("附件字段不属于当前表单主表");
            return;
        }
        boolean detailPresented =
                present(form.form().nodes(), null, detail, write)
                        || !present(form.form().nodes(), null, detail, false)
                                && form.form().detailIds() != null
                                && form.form().detailIds().contains(detail);
        List<ApplicationUi.Node> detailNodes =
                form.form().detailNodes() == null ? null : form.form().detailNodes().get(detail);
        if (!detailPresented || detailNodes != null && !present(detailNodes, field, null, write))
            throw invalid("附件明细字段未在当前任务表单开放");
        if (!caps.readDetails().contains(detail)
                || !recordCaps.readDetails().contains(detail)
                || write
                        && (!caps.writeDetails().contains(detail)
                                || !recordCaps.writeDetails().contains(detail)))
            throw invalid("当前任务表单不允许访问此明细区");
        DataCenter.Detail definition =
                form.model().object().details().stream()
                        .filter(item -> detail.equals(item.id()))
                        .findFirst()
                        .orElseThrow(() -> invalid("附件明细区不存在"));
        if (definition.fields().stream().noneMatch(item -> field.equals(item.id())))
            throw invalid("附件字段不属于当前明细区");
        if (row != null
                && (form.record() == null
                        || form.record().details().getOrDefault(detail, List.of()).stream()
                                .noneMatch(item -> row.equals(item.id()))))
            throw invalid("附件明细行不属于当前记录");
        if (!write && row == null) throw invalid("明细附件必须指定当前记录的明细行");
    }

    private boolean present(
            List<ApplicationUi.Node> nodes, String field, String detail, boolean write) {
        if (nodes == null) return false;
        for (ApplicationUi.Node node : nodes) {
            if (write
                    && node.presentation() != null
                    && Boolean.TRUE.equals(node.presentation().readOnly())) continue;
            if (field != null && field.equals(node.fieldId())
                    || detail != null
                            && node.detail() != null
                            && detail.equals(node.detail().detailId())
                    || present(node.children(), field, detail, write)) return true;
        }
        return false;
    }

    private String session(TaskWorkEntries.Form target, TaskCenter.FormContext form, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("上传会话标识无效");
        String record = form.binding().recordId();
        if (taskScope.delegated())
            return TaskBusinessFileSessions.key(target.taskId(), target.entryKey(), record, key);
        // 历史无委托快照任务继续使用普通授权，不能生成带自动任务授权含义的会话。
        return DigestUtil.sha256Hex(
                target.taskId()
                        + "\n"
                        + target.entryKey()
                        + "\n"
                        + (record == null ? "" : record)
                        + "\n"
                        + key);
    }
}
