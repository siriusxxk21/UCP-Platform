package com.lingan.ucp.nocode.controller.admin;

import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.BusinessFiles;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileMarkService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService;
import com.lingan.ucp.nocode.web.*;

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
import java.util.function.LongFunction;

/**
 * 业务文件浏览 Controller
 *
 * <p>应用入口与数据维护入口共用同一组端点，入口身份由 applicationId 区分，服务端分别沿用应用运行授权或
 * 数据对象管理授权，不在控制器内拼接两者的授权链；类级只要求登录，具体入口准入由服务校验。 上传与内容读取每次请求都重验各自授权链，控制器只负责参数、Range 与响应头。
 */
@Tag(name = "无代码 - 业务文件")
@RestController
@RequestMapping("/nocode/biz-file")
@PreAuthorize("isAuthenticated()")
public class BizFileController {
    @Resource private BizFileBrowseService browse;
    @Resource private BizFileMarkService marks;
    @Resource private BizFileUploadService upload;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/config/spaces")
    @Operation(summary = "查询对象设计可选业务空间", description = "需要数据对象查询与管理权；配置保存稳定空间编号")
    public Result<java.util.List<BusinessFiles.ConfigSpace>> configSpaces() {
        return Result.success(upload.configSpaces(access.actor()));
    }

    @PostMapping("/objects")
    @Operation(summary = "查询入口内业务空间")
    public Result<java.util.List<BusinessFiles.Space>> objects(@RequestBody JsonNode body) {
        return Result.success(
                browse.spaces(
                        requests.runtime(body, BusinessFiles.EntryQuery.class), access.actor()));
    }

    @PostMapping("/directories")
    @Operation(summary = "浏览业务文件目录")
    public Result<PageResult<BusinessFiles.Directory>> directories(@RequestBody JsonNode body) {
        return Result.success(
                browse.directories(
                        requests.runtime(body, BusinessFiles.DirectoryQuery.class),
                        access.actor()));
    }

    @PostMapping("/files")
    @Operation(summary = "查询与搜索业务文件")
    public Result<PageResult<BusinessFiles.File>> files(@RequestBody JsonNode body) {
        return Result.success(
                browse.files(
                        requests.runtime(body, BusinessFiles.FileQuery.class), access.actor()));
    }

    @PostMapping("/locate")
    @Operation(summary = "定位业务文件导航链", description = "按内容读取同一位置身份返回规则版本到附件字段的导航路径，供网盘业务文件入口建立面包屑")
    public Result<java.util.List<BusinessFiles.Directory>> locate(@RequestBody JsonNode body) {
        return Result.success(
                browse.locate(
                        requests.runtime(body, BusinessFiles.ContentQuery.class), access.actor()));
    }

    @PostMapping("/mark/files")
    @Operation(summary = "查询收藏或最近访问文件", description = "本人标记按时间倒序取前 100 条，再经入口完整授权链重新过滤")
    public Result<PageResult<BusinessFiles.File>> markedFiles(@RequestBody JsonNode body) {
        return Result.success(
                marks.markedFiles(
                        requests.runtime(body, BusinessFiles.MarkQuery.class), access.actor()));
    }

    @PostMapping("/mark/favorite")
    @Operation(summary = "收藏或取消收藏业务文件", description = "先按内容读取同一位置身份重验可见绑定，再写本人收藏标记")
    public Result<Boolean> favorite(@RequestBody JsonNode body) {
        return Result.success(
                marks.favorite(
                        requests.runtime(body, BusinessFiles.FavoriteQuery.class), access.actor()));
    }

    @PostMapping("/mark/access")
    @Operation(summary = "登记最近访问", description = "网盘业务文件入口预览/下载时调用；位置失效或不可见时静默跳过")
    public Result<Boolean> access(@RequestBody JsonNode body) {
        marks.access(requests.runtime(body, BusinessFiles.ContentQuery.class), access.actor());
        return Result.success(Boolean.TRUE);
    }

    @PostMapping("/upload")
    @Operation(summary = "上传业务附件", description = "写入受保护内容并登记上传会话；保存业务记录时再校验会话归属并建立网盘节点")
    public Result<BusinessFiles.Uploaded> upload(
            @RequestParam(value = "applicationId", required = false) String applicationId,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "recordId", required = false) String recordId,
            @RequestParam(value = "detailId", required = false) String detailId,
            @RequestParam("fieldId") String fieldId,
            @RequestParam("sessionKey") String sessionKey,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        return Result.success(
                upload.upload(
                        new BusinessFiles.UploadQuery(
                                StrUtil.blankToDefault(applicationId, null),
                                objectId,
                                StrUtil.blankToDefault(recordId, null),
                                StrUtil.blankToDefault(detailId, null),
                                fieldId,
                                sessionKey,
                                StrUtil.blankToDefault(idempotencyKey, null),
                                file.getOriginalFilename(),
                                file.getContentType(),
                                file.getSize()),
                        access.actor(),
                        file.getInputStream()));
    }

    @PostMapping("/upload/renew")
    @Operation(summary = "续期上传会话", description = "本人该会话键下的临时上传顺延有效期；返回 false 表示会话已过期，需要重新上传")
    public Result<Boolean> renew(@RequestParam("sessionKey") String sessionKey) {
        return Result.success(upload.renew(sessionKey, access.actor()));
    }

    @GetMapping("/upload/content")
    @Operation(summary = "读取临时上传内容", description = "仅上传会话本人在有效期内可读；支持 Range 断点请求，用于保存前的预览")
    public void temporaryContent(
            @RequestParam("objectId") String objectId,
            @RequestParam("fieldId") String fieldId,
            @RequestParam("sessionKey") String sessionKey,
            @RequestParam("fileId") Long fileId,
            @RequestParam(value = "inline", defaultValue = "false") Boolean inline,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        BusinessFiles.TemporaryContent content =
                upload.temporaryContent(
                        new BusinessFiles.TemporaryQuery(objectId, fieldId, sessionKey, fileId),
                        access.actor());
        stream(
                request,
                response,
                content.name(),
                content.mimeType(),
                content.length(),
                content.size(),
                Boolean.TRUE.equals(inline),
                start -> upload.temporaryStream(content, start));
    }

    @GetMapping("/content")
    @Operation(summary = "读取业务文件内容", description = "每次请求重验完整业务授权链；支持 Range 断点请求，用于预览与下载")
    public void content(
            @RequestParam(value = "applicationId", required = false) String applicationId,
            @RequestParam("objectId") String objectId,
            @RequestParam("recordId") String recordId,
            @RequestParam(value = "detailId", required = false) String detailId,
            @RequestParam(value = "rowId", required = false) String rowId,
            @RequestParam("fieldId") String fieldId,
            @RequestParam("entryId") Long entryId,
            @RequestParam(value = "inline", defaultValue = "false") Boolean inline,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        BusinessFiles.Content content =
                browse.content(
                        new BusinessFiles.ContentQuery(
                                StrUtil.blankToDefault(applicationId, null),
                                objectId,
                                recordId,
                                StrUtil.blankToDefault(detailId, null),
                                StrUtil.blankToDefault(rowId, null),
                                fieldId,
                                entryId),
                        access.actor());
        stream(
                request,
                response,
                content.name(),
                content.mimeType(),
                content.length(),
                content.size(),
                Boolean.TRUE.equals(inline),
                start -> browse.contentStream(content, start));
    }

    /** 沿用所有业务附件入口共同的 Range、安全响应头和流关闭规则。 */
    private void stream(
            HttpServletRequest request,
            HttpServletResponse response,
            String name,
            String mimeType,
            Long length,
            long size,
            boolean inline,
            LongFunction<InputStream> open)
            throws IOException {
        BusinessFileResponses.stream(request, response, name, mimeType, length, size, inline, open);
    }
}
