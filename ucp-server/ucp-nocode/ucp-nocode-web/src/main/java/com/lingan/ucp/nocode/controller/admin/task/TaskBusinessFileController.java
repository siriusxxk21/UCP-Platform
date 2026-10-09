package com.lingan.ucp.nocode.controller.admin.task;

import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.BusinessFiles;
import com.lingan.ucp.nocode.api.TaskBusinessFiles;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBusinessFileService;
import com.lingan.ucp.nocode.web.BusinessFileResponses;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/** 任务表单附件专用适配；任务内权限不变为应用运行权限或网盘浏览权限。 */
@Tag(name = "无代码 - 任务办理附件")
@RestController
@RequestMapping("/nocode/tasks/entry-files")
@PreAuthorize("isAuthenticated()")
public class TaskBusinessFileController {
    @Resource private TaskBusinessFileService service;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @PostMapping("/files")
    @Operation(summary = "列出当前任务表单字段内的附件")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<BusinessFiles.File>> files(@RequestBody JsonNode body) {
        return Result.success(
                service.files(
                        requests.runtime(body, TaskBusinessFiles.Files.class), access.actor()));
    }

    @PostMapping("/content")
    @Operation(summary = "在当前任务授权下预览或下载已保存附件")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public void content(
            @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        TaskBusinessFiles.Content query = requests.runtime(body, TaskBusinessFiles.Content.class);
        BusinessFiles.Content content = service.content(query, access.actor());
        BusinessFileResponses.stream(
                request,
                response,
                content.name(),
                content.mimeType(),
                content.length(),
                content.size(),
                Boolean.TRUE.equals(query.inline()),
                offset -> service.contentStream(content, offset));
    }

    @PostMapping("/temporary-content")
    @Operation(summary = "预览当前任务表单会话中尚未保存的本人附件")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public void temporaryContent(
            @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        TaskBusinessFiles.Temporary query =
                requests.runtime(body, TaskBusinessFiles.Temporary.class);
        BusinessFiles.TemporaryContent content = service.temporaryContent(query, access.actor());
        BusinessFileResponses.stream(
                request,
                response,
                content.name(),
                content.mimeType(),
                content.length(),
                content.size(),
                Boolean.TRUE.equals(query.inline()),
                offset -> service.temporaryStream(content, offset));
    }

    @PostMapping("/upload")
    @Operation(summary = "以当前任务执行人身份上传固定表单附件")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<BusinessFiles.Uploaded> upload(
            @RequestParam("taskId") String taskId,
            @RequestParam("entryKey") String entryKey,
            @RequestParam(value = "contributionId", required = false) String contributionId,
            @RequestParam("applicationId") String applicationId,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "recordId", required = false) String recordId,
            @RequestParam(value = "detailId", required = false) String detailId,
            @RequestParam("fieldId") String fieldId,
            @RequestParam("sessionKey") String sessionKey,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        TaskWorkEntries.Form target =
                new TaskWorkEntries.Form(
                        taskId,
                        entryKey,
                        StrUtil.blankToDefault(recordId, null),
                        StrUtil.blankToDefault(contributionId, null));
        BusinessFiles.UploadQuery query =
                new BusinessFiles.UploadQuery(
                        applicationId,
                        objectId,
                        target.recordId(),
                        StrUtil.blankToDefault(detailId, null),
                        fieldId,
                        sessionKey,
                        StrUtil.blankToDefault(idempotencyKey, null),
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize());
        try (InputStream content = file.getInputStream()) {
            return Result.success(
                    service.upload(
                            new TaskBusinessFiles.Upload(target, query), access.actor(), content));
        }
    }

    @PostMapping("/renew")
    @Operation(summary = "续期当前任务仍允许办理的本人附件会话")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Boolean> renew(@RequestBody JsonNode body) {
        return Result.success(
                service.renew(
                        requests.runtime(body, TaskBusinessFiles.Renew.class), access.actor()));
    }
}
