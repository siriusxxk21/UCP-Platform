package com.richuang.os.nocode.api;

/** 任务办理附件契约；位置必须与服务端解析的固定任务表单、记录及字段一致。 */
public final class TaskBusinessFiles {
    private TaskBusinessFiles() {}

    public record Files(TaskWorkEntries.Form target, BusinessFiles.FileQuery query) {}

    public record Content(
            TaskWorkEntries.Form target, BusinessFiles.ContentQuery query, Boolean inline) {}

    /** 明细身份在临时文件阶段也必须验证，不允许以主表字段路径绕过明细权限。 */
    public record TemporaryQuery(
            String objectId, String fieldId, String sessionKey, Long fileId, String detailId) {}

    public record Temporary(TaskWorkEntries.Form target, TemporaryQuery query, Boolean inline) {}

    public record Upload(TaskWorkEntries.Form target, BusinessFiles.UploadQuery query) {}

    public record Renew(TaskWorkEntries.Form target, String sessionKey) {}
}
