package com.richuang.os.nocode.controller.admin;

import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.infra.framework.file.core.utils.FileRangeUtils;
import com.richuang.os.module.infra.framework.file.core.utils.FileTypeUtils;
import com.richuang.os.nocode.api.RecordFolders;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderConfigService;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 记录文件夹 Controller
 *
 * <p>配置接口沿用数据对象的查询 / 管理权限。浏览接口类级只要求登录：每个请求带凭据（应用、对象、记录、来源），应用入口与数据维护入口由 applicationId 是否为空区分，准入与「能看
 * / 能改这条记录」都由服务每次重新校验，控制器只负责参数、Range 与响应头。
 *
 * <p>所有地址里 parentId = 0 / targetParentId = 0 表示「这个文件夹的根」；根自身的编号不出现在任何响应里。
 */
@Slf4j
@Tag(name = "无代码 - 记录文件夹")
@RestController
@RequestMapping("/nocode/record-folder")
@PreAuthorize("isAuthenticated()")
public class RecordFolderController {
    /** 浏览器会当作页面或脚本执行的内容类型片段：这类文件一律按附件下载并加沙箱策略，不在页面内打开 */
    private static final List<String> ACTIVE_CONTENT_TYPES =
            List.of("html", "xml", "svg", "xsl", "javascript", "ecmascript");

    /** 可执行内容的沙箱策略：即使被当作页面打开也不能运行脚本、不能加载任何资源 */
    private static final String ACTIVE_CONTENT_POLICY = "default-src 'none'; sandbox";

    @Resource private RecordFolderConfigService configs;
    @Resource private RecordFolderService folders;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/config")
    @Operation(summary = "查询对象的文件夹来源", description = "按页签顺序返回；含页签名称、文件夹路径与配置失效原因的回显")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<RecordFolders.Source>> config(@RequestParam("objectId") String objectId) {
        return Result.success(configs.list(objectId, access.actor()));
    }

    @PostMapping("/config/save")
    @Operation(summary = "保存对象的文件夹来源", description = "整份替换，保存即生效，不需要发布；任一条校验不过整份拒绝")
    @PreAuthorize("@nocodeAccess.manage()")
    public Result<List<RecordFolders.Source>> saveConfig(@RequestBody JsonNode body) {
        return Result.success(
                configs.save(
                        requests.runtime(body, RecordFolders.SaveConfig.class), access.actor()));
    }

    @GetMapping("/config/candidates")
    @Operation(summary = "查询可选的关联字段与对方的文件夹", description = "对象已发布版本主表上的关联；多对多与对方未配置文件夹的给出不可选原因")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<RecordFolders.Candidate>> candidates(
            @RequestParam("objectId") String objectId) {
        return Result.success(configs.candidates(objectId, access.actor()));
    }

    @GetMapping("/config/name-fields")
    @Operation(summary = "查询可用于子文件夹命名的字段", description = "对象已发布版本主表上类型可命名的字段，按字段在对象里的顺序")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<RecordFolders.NameField>> nameFields(
            @RequestParam("objectId") String objectId) {
        return Result.success(configs.nameFields(objectId, access.actor()));
    }

    @PostMapping("/config/backfill")
    @Operation(summary = "为已有记录补建文件夹", description = "一次处理一页（最多 100 条），返回游标与计数；由前端一页一页驱动，可中途停")
    @PreAuthorize("@nocodeAccess.manage()")
    public Result<RecordFolders.BackfillResult> backfill(@RequestBody JsonNode body) {
        return Result.success(
                configs.backfill(
                        requests.runtime(body, RecordFolders.Backfill.class), access.actor()));
    }

    @PostMapping("/open")
    @Operation(summary = "打开记录的文件夹页签", description = "逐个来源解析状态与是否可写；只读，不建任何东西")
    public Result<RecordFolders.Opened> open(@RequestBody JsonNode body) {
        return Result.success(
                folders.open(
                        requests.runtime(body, RecordFolders.OpenQuery.class), access.actor()));
    }

    @PostMapping("/entry/list")
    @Operation(summary = "列出文件夹里的一层", description = "parentId 为 0 表示这个文件夹的根；文件夹还没建时返回空列表")
    public Result<List<RecordFolders.Entry>> list(@RequestBody JsonNode body) {
        return Result.success(folders.list(query(body), access.actor()));
    }

    @PostMapping("/entry/get")
    @Operation(summary = "读取节点详情")
    public Result<RecordFolders.Entry> get(@RequestBody JsonNode body) {
        return Result.success(folders.get(query(body), access.actor()));
    }

    @PostMapping("/entry/path")
    @Operation(summary = "读取节点所在目录相对根的名称路径", description = "根的直接子节点返回空列表")
    public Result<List<String>> path(@RequestBody JsonNode body) {
        return Result.success(folders.path(query(body), access.actor()));
    }

    @PostMapping("/entry/search")
    @Operation(summary = "在文件夹内按名称搜索")
    public Result<List<RecordFolders.Entry>> search(@RequestBody JsonNode body) {
        return Result.success(folders.search(query(body), access.actor()));
    }

    @PostMapping("/entry/create-folder")
    @Operation(summary = "新建子文件夹", description = "记录的文件夹还没建时，能改这条记录的人先把它建出来")
    public Result<Long> createFolder(@RequestBody JsonNode body) {
        return Result.success(folders.createFolder(query(body), access.actor()));
    }

    @PostMapping("/entry/upload")
    @Operation(summary = "上传文件", description = "记录的文件夹还没建时，能改这条记录的人先把它建出来；上传内容期间不持有数据库事务")
    public Result<RecordFolders.Entry> upload(
            @RequestParam(value = "applicationId", required = false) String applicationId,
            @RequestParam("objectId") String objectId,
            @RequestParam("recordId") String recordId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        return Result.success(
                folders.upload(
                        new RecordFolders.UploadQuery(
                                StrUtil.blankToDefault(applicationId, null),
                                objectId,
                                recordId,
                                sourceId,
                                parentId,
                                file.getOriginalFilename(),
                                file.getContentType(),
                                file.getSize()),
                        access.actor(),
                        file.getInputStream()));
    }

    @PostMapping("/entry/rename")
    @Operation(summary = "重命名节点")
    public Result<Boolean> rename(@RequestBody JsonNode body) {
        folders.rename(query(body), access.actor());
        return Result.success(Boolean.TRUE);
    }

    @PostMapping("/entry/move")
    @Operation(summary = "在文件夹内移动节点", description = "targetParentId 为 0 表示移到这个文件夹的根")
    public Result<Boolean> move(@RequestBody JsonNode body) {
        folders.move(query(body), access.actor());
        return Result.success(Boolean.TRUE);
    }

    @PostMapping("/entry/copy")
    @Operation(summary = "在文件夹内复制节点", description = "返回新节点编号")
    public Result<Long> copy(@RequestBody JsonNode body) {
        return Result.success(folders.copy(query(body), access.actor()));
    }

    @PostMapping("/entry/trash")
    @Operation(summary = "删除节点", description = "移入网盘回收站；任何一个不能删则整批不做")
    public Result<Boolean> trash(@RequestBody JsonNode body) {
        folders.trash(query(body), access.actor());
        return Result.success(Boolean.TRUE);
    }

    @PostMapping("/entry/trash-list")
    @Operation(summary = "最近删除", description = "只列本人删除的、经由这条记录放进去的节点")
    public Result<List<RecordFolders.Entry>> trashList(@RequestBody JsonNode body) {
        return Result.success(folders.trashList(query(body), access.actor()));
    }

    @PostMapping("/entry/restore")
    @Operation(summary = "恢复最近删除的节点", description = "原位置已不存在时拒绝")
    public Result<RecordFolders.Entry> restore(@RequestBody JsonNode body) {
        return Result.success(folders.restore(query(body), access.actor()));
    }

    @GetMapping("/entry/content")
    @Operation(
            summary = "读取文件内容",
            description =
                    "每次请求重验凭据；支持 Range 断点请求，用于预览与下载。HTML、XML、SVG、脚本一律按附件下载并带沙箱策略，其余响应头与网盘自己的内容接口一致")
    public void content(
            @RequestParam(value = "applicationId", required = false) String applicationId,
            @RequestParam("objectId") String objectId,
            @RequestParam("recordId") String recordId,
            @RequestParam("sourceId") String sourceId,
            @RequestParam("id") Long id,
            @RequestParam(value = "inline", defaultValue = "false") Boolean inline,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        long actor = access.actor();
        RecordFolders.Content content =
                folders.content(
                        new RecordFolders.ContentQuery(
                                StrUtil.blankToDefault(applicationId, null),
                                objectId,
                                recordId,
                                sourceId,
                                id),
                        actor);
        long total =
                content.length() != null && content.length() > 0
                        ? content.length()
                        : content.size();
        // 类型取节点上存的类型（上传时由服务端确定），与网盘自己的内容接口一致
        String type = StrUtil.blankToDefault(content.mimeType(), "application/octet-stream");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setContentType(type);
        // 可执行内容（HTML、XML、SVG、脚本）不内联：与表单同源打开会以登录用户的身份运行其中的脚本
        boolean active = isActiveContent(type);
        FileTypeUtils.writeContentDisposition(
                response, content.name(), type, Boolean.TRUE.equals(inline) && !active);
        if (active) {
            response.setHeader("Content-Security-Policy", ACTIVE_CONTENT_POLICY);
        }
        FileRangeUtils.ByteRange range =
                FileRangeUtils.parseRangeHeader(request.getHeader(HttpHeaders.RANGE), total);
        if (range != null && !range.isSatisfiable()) {
            // 越界区间不打开内容流，直接按 RFC 7233 返回 416
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, StrUtil.format("bytes */{}", total));
            return;
        }
        long start = range != null ? range.getStart() : 0L;
        try (InputStream stream = folders.contentStream(content, actor, start)) {
            // writeStream 负责 200/206、Content-Range 与响应体，并保证内容流关闭
            FileRangeUtils.writeStream(response, total, range, stream);
        } catch (IOException interrupted) {
            // 预览时的连接中断属于常态，降级为调试日志，避免污染错误日志
            log.debug("[content][记录文件夹文件({}) 内容传输中断]", content.name(), interrupted);
        }
    }

    /** 内容类型（忽略参数与大小写）是否属于浏览器会执行的类型。 */
    static boolean isActiveContent(String type) {
        String essence = StrUtil.subBefore(type, ';', false).trim().toLowerCase();
        return ACTIVE_CONTENT_TYPES.stream().anyMatch(essence::contains);
    }

    private RecordFolders.EntryQuery query(JsonNode body) {
        RecordFolders.EntryQuery query = requests.runtime(body, RecordFolders.EntryQuery.class);
        // 应用编号的空白归一为 null：空串不能带到入口判定
        return new RecordFolders.EntryQuery(
                StrUtil.blankToDefault(query.applicationId(), null),
                query.objectId(),
                query.recordId(),
                query.sourceId(),
                query.id(),
                query.parentId(),
                query.targetParentId(),
                query.ids(),
                query.name(),
                query.limit());
    }
}
