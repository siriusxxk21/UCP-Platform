package com.richuang.os.module.infra.controller.admin.file;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.framework.tenant.core.aop.TenantIgnore;
import com.richuang.os.module.infra.controller.admin.file.vo.file.*;
import com.richuang.os.module.infra.controller.admin.file.vo.file.*;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static com.richuang.os.framework.common.pojo.Result.success;
import static com.richuang.os.module.infra.framework.file.core.utils.FileTypeUtils.writeAttachment;

@Tag(name = "管理后台 - 文件存储")
@RestController
@RequestMapping("/infra/file")
@Validated
@Slf4j
public class FileController {

    private static final long AVATAR_CACHE_MAX_AGE_SECONDS = 365L * 24 * 60 * 60;

    @Resource
    private FileService fileService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "模式一：后端上传文件")
    @Parameter(name = "file", description = "文件附件", required = true,
            schema = @Schema(type = "string", format = "binary"))
    public Result<FileDO> uploadFile(@Valid FileUploadReqVO uploadReqVO) throws Exception {
        MultipartFile file = uploadReqVO.getFile();
        byte[] content = IoUtil.readBytes(file.getInputStream());
        return success(fileService.createFile(content, file.getOriginalFilename(),
                uploadReqVO.getDirectory(), file.getContentType()));
    }

    @GetMapping("/download/{id}")
    @Operation(summary = "下载文件")
    public void download(HttpServletResponse response, @PathVariable Long id) throws Exception {
        FileDO file = fileService.getFile(id);
        if (file == null || BooleanUtil.isTrue(file.getProtectedFlag())) {
            // 受保护文件只能经业务模块鉴权后读取，此处统一按不存在处理，不暴露其存在性
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        String path = URLUtil.decode(file.getPath(), StandardCharsets.UTF_8, false);
        // 读取内容
        byte[] content = fileService.getFileContent(file.getConfigId(), path);
        if (content == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        writeAttachment(response, file.getName(), content);
    }

    @GetMapping("/presigned-url")
    @Operation(summary = "获取文件预签名地址（上传）", description = "模式二：前端上传文件：用于前端直接上传七牛、阿里云 OSS 等文件存储器")
    @Parameters({
            @Parameter(name = "name", description = "文件名称", required = true),
            @Parameter(name = "directory", description = "文件目录")
    })
    public Result<FilePresignedUrlRespVO> getFilePresignedUrl(
            @RequestParam("name") String name,
            @RequestParam(value = "directory", required = false) String directory) {
        return success(fileService.presignPutUrl(name, directory));
    }

    @PostMapping("/create")
    @Operation(summary = "创建文件", description = "模式二：前端上传文件：配合 presigned-url 接口，记录上传了上传的文件")
    public Result<Long> createFile(@Valid @RequestBody FileCreateReqVO createReqVO) {
        return success(fileService.createFile(createReqVO));
    }

    @GetMapping("/get")
    @Operation(summary = "获得文件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:query')")
    public Result<FileRespVO> getFile(@RequestParam("id") Long id) {
        FileDO file = fileService.getFile(id);
        // 受保护文件只能经业务模块鉴权后读取，此处不返回其访问地址
        if (file == null || BooleanUtil.isTrue(file.getProtectedFlag())) {
            return success(null);
        }
        return success(BeanUtils.toBean(file, FileRespVO.class));
    }

    @GetMapping("/get-list")
    @Operation(summary = "获得文件")
    @Parameter(name = "ids", description = "编号", required = true)
    public Result<List<FileDO>> getFiles(@RequestParam("ids") List<Long> ids) {
        // 受保护文件不在此接口返回，避免通用接口被用于获取其访问地址
        return success(fileService.getFiles(ids).stream()
                .filter(file -> !BooleanUtil.isTrue(file.getProtectedFlag()))
                .collect(Collectors.toList()));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除文件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:delete')")
    public Result<Boolean> deleteFile(@RequestParam("id") Long id) throws Exception {
        fileService.deleteFile(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除文件")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:delete')")
    public Result<Boolean> deleteFileList(@RequestParam("ids") List<Long> ids) throws Exception {
        fileService.deleteFileList(ids);
        return success(true);
    }

    @GetMapping("/{configId}/get/**")
    @PermitAll
    @TenantIgnore
    @Operation(summary = "下载文件")
    @Parameter(name = "configId", description = "配置编号", required = true)
    public void getFileContent(HttpServletRequest request,
                               HttpServletResponse response,
                               @PathVariable("configId") Long configId) throws Exception {
        // 获取请求的路径
        String path = StrUtil.subAfter(request.getRequestURI(), "/get/", false);
        if (StrUtil.isEmpty(path)) {
            throw new IllegalArgumentException("结尾的 path 路径必须传递");
        }
        // 解码，解决中文路径的问题
        // https://gitee.com/zhijiantianya/ruoyi-vue-pro/pulls/807/
        // https://gitee.com/zhijiantianya/ruoyi-vue-pro/pulls/1432/
        path = URLUtil.decode(path, StandardCharsets.UTF_8, false);

        // 受保护文件只能经业务模块鉴权后读取，此处统一按不存在处理，不暴露其存在性
        if (fileService.isProtectedFile(configId, path)) {
            log.warn("[getFileContent][configId({}) 路径为受保护文件，拒绝通用接口读取]", configId);
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        // 读取内容
        byte[] content = fileService.getFileContent(configId, path);
        if (content == null) {
            log.warn("[getFileContent][configId({}) path({}) 文件不存在]", configId, path);
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        configureAvatarCache(response, path);
        writeAttachment(response, path, content);
    }

    /**
     * 头像上传后使用包含时间戳的新文件路径，因此可按不可变资源长期缓存。
     * 显式写入三个缓存响应头，避免 Spring Security 的默认 no-store 头导致每次页面切换都重新下载。
     */
    private void configureAvatarCache(HttpServletResponse response, String path) {
        if (!StrUtil.startWith(path, "avatar/")) {
            return;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                "public, max-age=" + AVATAR_CACHE_MAX_AGE_SECONDS + ", immutable");
        response.setHeader(HttpHeaders.PRAGMA, "");
        response.setDateHeader(HttpHeaders.EXPIRES,
                System.currentTimeMillis() + AVATAR_CACHE_MAX_AGE_SECONDS * 1000);
    }

    @GetMapping("/page")
    @Operation(summary = "获得文件分页")
    @PreAuthorize("@ss.hasPermission('infra:file:query')")
    public Result<PageResult<FileRespVO>> getFilePage(@Valid FilePageReqVO pageVO) {
        PageResult<FileDO> pageResult = fileService.getFilePage(pageVO);
        return success(BeanUtils.toBean(pageResult, FileRespVO.class));
    }

}
