package com.lingan.ucp.nocode.controller.admin.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.ReportFolders;
import com.lingan.ucp.nocode.report.service.folder.ReportFolderService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 分类目录与资源 ACL 分离，所有身份仍来自当前会话。 */
@Tag(name = "无代码 - 报表目录")
@RestController
@RequestMapping("/nocode/report/folder")
@PreAuthorize("isAuthenticated()")
public class ReportFolderController {
    @Resource private ReportFolderService folders;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/tree")
    @Operation(summary = "查询对应资源类别的可发现目录与祖先")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<List<ReportFolders.Item>> tree(
            @RequestParam(required = false) String resourceKind) {
        return Result.success(folders.tree(resourceKind, access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "新建或调整报表分类目录")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportFolders.Item> save(@RequestBody JsonNode body) {
        return Result.success(
                folders.save(requests.design(body, ReportFolders.Save.class), access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除对应类别的空报表目录")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportFolders.Deleted> delete(@RequestBody JsonNode body) {
        return Result.success(
                folders.delete(requests.design(body, ReportFolders.Delete.class), access.actor()));
    }
}
