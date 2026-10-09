package com.lingan.ucp.module.drive.controller.admin.entry;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.lingan.ucp.module.infra.framework.file.core.utils.FileTypeUtils.writeContentDisposition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.collection.CollectionUtils;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryBatchReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryBreadcrumbRespVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryFolderCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryInheritReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryRenameReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryRespVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntrySearchReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveTrashListReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.service.entry.DriveEntryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@Tag(name = "管理后台 - 网盘节点")
@RestController
@RequestMapping("/drive/entry")
@Validated
@Slf4j
public class DriveEntryController {

    /** 内容流式输出的缓冲区大小 */
    private static final int STREAM_BUFFER_SIZE = 8 * 1024;

    @Resource private DriveEntryService entryService;

    @GetMapping("/list")
    @Operation(summary = "获得目录下的节点列表")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public Result<List<DriveEntryRespVO>> getEntryList(@Valid DriveEntryListReqVO reqVO) {
        return success(
                convertList(entryService.getEntryList(reqVO, getLoginUserId()), getLoginUserId()));
    }

    @GetMapping("/search")
    @Operation(summary = "按名称搜索节点", description = "只返回当前用户有权限的节点")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public Result<List<DriveEntryRespVO>> searchEntryList(@Valid DriveEntrySearchReqVO reqVO) {
        return success(
                convertList(
                        entryService.searchEntryList(reqVO, getLoginUserId()), getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得节点", description = "含当前用户在该节点上的角色与收藏状态")
    @Parameter(name = "id", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public Result<DriveEntryRespVO> getEntry(@RequestParam("id") Long id) {
        return success(entryService.getEntryDetail(id, getLoginUserId()));
    }

    @GetMapping("/breadcrumb")
    @Operation(summary = "获得节点的路径面包屑")
    @Parameter(name = "entryId", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public Result<List<DriveEntryBreadcrumbRespVO>> getBreadcrumbList(
            @RequestParam("entryId") Long entryId) {
        return success(entryService.getBreadcrumbList(entryId, getLoginUserId()));
    }

    @PostMapping("/create-folder")
    @Operation(summary = "新建目录")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<Long> createFolder(@Valid @RequestBody DriveEntryFolderCreateReqVO reqVO) {
        return success(entryService.createFolder(reqVO, getLoginUserId()));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "按空间与父节点落库，文件内容以流式写入受保护存储")
    @Parameter(
            name = "file",
            description = "文件附件",
            required = true,
            schema = @Schema(type = "string", format = "binary"))
    @PreAuthorize("@ss.hasPermission('drive:entry:upload')")
    public Result<DriveEntryRespVO> uploadFile(
            @RequestParam("spaceId") Long spaceId,
            @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        DriveEntryDO entry =
                entryService.uploadFile(
                        spaceId,
                        parentId,
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize(),
                        file.getInputStream(),
                        getLoginUserId());
        return success(BeanUtils.toBean(entry, DriveEntryRespVO.class));
    }

    @PutMapping("/rename")
    @Operation(summary = "重命名节点")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<Boolean> renameEntry(@Valid @RequestBody DriveEntryRenameReqVO reqVO) {
        entryService.renameEntry(reqVO, getLoginUserId());
        return success(true);
    }

    @PutMapping("/move")
    @Operation(summary = "移动节点")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<Boolean> moveEntry(@Valid @RequestBody DriveEntryMoveReqVO reqVO) {
        entryService.moveEntry(reqVO, getLoginUserId());
        return success(true);
    }

    @PutMapping("/inherit")
    @Operation(summary = "设置节点是否继承上级授权", description = "关闭继承后，该节点及其子树只按本级及以下授权判定，需可管理权限")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<Boolean> updateInheritParent(@Valid @RequestBody DriveEntryInheritReqVO reqVO) {
        entryService.updateInheritParent(reqVO, getLoginUserId());
        return success(true);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制节点", description = "支持目录整体复制，跨空间复制时按目标空间重新计容")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<Long> copyEntry(@Valid @RequestBody DriveEntryCopyReqVO reqVO) {
        return success(entryService.copyEntry(reqVO, getLoginUserId()));
    }

    @PutMapping("/trash")
    @Operation(summary = "移入回收站")
    @PreAuthorize("@ss.hasPermission('drive:entry:delete')")
    public Result<Boolean> trashEntryList(@Valid @RequestBody DriveEntryBatchReqVO reqVO) {
        entryService.trashEntryList(reqVO.getIds(), getLoginUserId());
        return success(true);
    }

    @PutMapping("/restore")
    @Operation(summary = "从回收站恢复", description = "原位置不可用时恢复到空间根目录")
    @Parameter(name = "id", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:entry:update')")
    public Result<DriveEntryRespVO> restoreEntry(@RequestParam("id") Long id) {
        return success(
                BeanUtils.toBean(
                        entryService.restoreEntry(id, getLoginUserId()), DriveEntryRespVO.class));
    }

    @DeleteMapping("/purge")
    @Operation(summary = "彻底删除", description = "删除回收站中的节点及其内容，不可恢复")
    @PreAuthorize("@ss.hasPermission('drive:entry:delete')")
    public Result<Boolean> purgeEntryList(@Valid @RequestBody DriveEntryBatchReqVO reqVO) {
        entryService.purgeEntryList(reqVO.getIds(), getLoginUserId());
        return success(true);
    }

    @GetMapping("/trash-list")
    @Operation(summary = "获得回收站节点列表")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public Result<List<DriveEntryRespVO>> getTrashList(@Valid DriveTrashListReqVO reqVO) {
        return success(
                convertList(entryService.getTrashList(reqVO, getLoginUserId()), getLoginUserId()));
    }

    @GetMapping("/content")
    @Operation(summary = "下载或预览文件内容", description = "支持 Range 断点请求，用于图片、音视频与 PDF 预览")
    @Parameter(name = "id", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:entry:query')")
    public void getContent(
            @RequestParam("id") Long id,
            @RequestParam(value = "inline", defaultValue = "false") Boolean inline,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        DriveEntryService.DriveEntryContent content =
                entryService.getContentInfo(id, getLoginUserId());
        long length =
                content.getLength() != null && content.getLength() > 0 ? content.getLength() : 0;
        String mimeType =
                StrUtil.blankToDefault(
                        content.getEntry().getMimeType(), "application/octet-stream");

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(mimeType);
        writeContentDisposition(
                response, content.getEntry().getName(), mimeType, Boolean.TRUE.equals(inline));

        long start = 0L;
        long end = length - 1;
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        boolean ranged = StrUtil.startWithIgnoreCase(rangeHeader, "bytes=");
        if (ranged) {
            long[] range = parseRange(StrUtil.subAfter(rangeHeader, "bytes=", false), length);
            if (range == null) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, StrUtil.format("bytes */{}", length));
                return;
            }
            start = range[0];
            end = range[1];
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(
                    HttpHeaders.CONTENT_RANGE,
                    StrUtil.format("bytes {}-{}/{}", start, end, length));
        }
        response.setContentLengthLong(end - start + 1);
        try (InputStream stream = entryService.getContentStream(id, getLoginUserId(), start)) {
            copyRange(stream, response.getOutputStream(), end - start + 1);
        } catch (IOException exception) {
            // 预览时的连接中断属于常态，降级为调试日志，避免污染错误日志
            log.debug("[getContent][节点({}) 内容传输中断]", id, exception);
        }
    }

    private List<DriveEntryRespVO> convertList(List<DriveEntryDO> list, Long userId) {
        List<DriveEntryRespVO> result = BeanUtils.toBean(list, DriveEntryRespVO.class);
        if (CollUtil.isEmpty(result)) {
            return result;
        }
        Map<Long, Boolean> favoriteMap =
                entryService.getFavoriteMap(
                        userId, CollectionUtils.convertList(result, DriveEntryRespVO::getId));
        result.forEach(vo -> vo.setFavorite(favoriteMap.getOrDefault(vo.getId(), Boolean.FALSE)));
        return result;
    }

    /**
     * 解析 Range 头，按 RFC 7233 支持 bytes=start-end、bytes=start-、bytes=-suffix 三种写法
     *
     * @return 闭区间 [start, end]；无法满足时返回 null
     */
    private long[] parseRange(String value, long length) {
        if (StrUtil.isBlank(value) || length <= 0) {
            return null;
        }
        String spec = StrUtil.subBefore(value, ",", false);
        String startPart = StrUtil.subBefore(spec, "-", false);
        String endPart = StrUtil.subAfter(spec, "-", false);
        try {
            if (StrUtil.isBlank(startPart)) {
                // 后缀写法：取末尾若干字节
                long suffix = Long.parseLong(StrUtil.trim(endPart));
                if (suffix <= 0) {
                    return null;
                }
                long start = Math.max(length - suffix, 0);
                return new long[] {start, length - 1};
            }
            long start = Long.parseLong(StrUtil.trim(startPart));
            long end =
                    StrUtil.isBlank(endPart) ? length - 1 : Long.parseLong(StrUtil.trim(endPart));
            if (start > end || start >= length) {
                return null;
            }
            return new long[] {start, Math.min(end, length - 1)};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 按字节数拷贝，避免把整个文件读入内存 */
    private void copyRange(InputStream input, OutputStream output, long count) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long remaining = count;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.flush();
    }
}
