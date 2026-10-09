package com.lingan.ucp.nocode.controller.admin;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.excel.core.util.ExcelUtils;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Excel/CSV 结构模板入口，授权与文件传输均沿用底座。 */
@Tag(name = "无代码 - 对象定义导入")
@RestController
@RequestMapping("/nocode/import")
public class ObjectImportController {
    @Resource private ObjectImportService service;
    @Resource private NocodeAccess access;

    @GetMapping("/template")
    @Operation(summary = "下载对象定义导入模板")
    @PreAuthorize("@nocodeAccess.importDesign()")
    public void template(HttpServletResponse response) throws IOException {
        ExcelUtils.write(
                response,
                "数据对象结构模板.xlsx",
                "字段定义",
                com.lingan.ucp.nocode.controller.admin.vo.ObjectImportRow.class,
                List.of(ObjectImportService.example()));
    }

    @PostMapping("/preview")
    @Operation(summary = "预览对象定义导入结果")
    @PreAuthorize("@nocodeAccess.importDesign()")
    public Result<com.lingan.ucp.nocode.api.ObjectImports.Preview> preview(
            @RequestParam MultipartFile file) {
        return Result.success(service.preview(file));
    }

    @PostMapping("/create")
    @Operation(summary = "导入并创建对象草稿")
    @PreAuthorize("@nocodeAccess.importDesign()")
    public Result<Design> create(@RequestBody ImportDesign body) {
        return Result.success(service.create(body, access.actor()));
    }
}
