package com.lingan.ucp.module.drive.controller.admin.share;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveSharePageReqVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareRespVO;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareUpdateReqVO;
import com.lingan.ucp.module.drive.service.share.DriveShareService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台 - 网盘分享")
@RestController
@RequestMapping("/drive/share")
@Validated
public class DriveShareController {

    @Resource private DriveShareService shareService;

    @PostMapping("/create")
    @Operation(summary = "创建分享", description = "组织内分享，按用户或部门下发查看、编辑权限")
    @PreAuthorize("@ss.hasPermission('drive:share:create')")
    public Result<Long> createShare(@Valid @RequestBody DriveShareCreateReqVO reqVO) {
        return success(shareService.createShare(reqVO, getLoginUserId()));
    }

    @PutMapping("/update")
    @Operation(summary = "修改分享", description = "可调整分享角色与失效时间")
    @PreAuthorize("@ss.hasPermission('drive:share:update')")
    public Result<Boolean> updateShare(@Valid @RequestBody DriveShareUpdateReqVO reqVO) {
        shareService.updateShare(reqVO, getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/revoke")
    @Operation(summary = "撤销分享")
    @Parameter(name = "id", description = "分享编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:share:delete')")
    public Result<Boolean> revokeShare(@RequestParam("id") Long id) {
        shareService.revokeShare(id, getLoginUserId());
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "获得我发起的分享分页")
    @PreAuthorize("@ss.hasPermission('drive:share:query')")
    public Result<PageResult<DriveShareRespVO>> getSharePage(@Valid DriveSharePageReqVO reqVO) {
        return success(shareService.getSharePage(reqVO, getLoginUserId()));
    }

    @GetMapping("/list-by-entry")
    @Operation(summary = "获得节点上的生效分享列表")
    @Parameter(name = "entryId", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:share:query')")
    public Result<List<DriveShareRespVO>> getShareListByEntry(
            @RequestParam("entryId") Long entryId) {
        return success(shareService.getShareListByEntry(entryId, getLoginUserId()));
    }

    @GetMapping("/shared-to-me")
    @Operation(summary = "获得与我共享的列表", description = "含直接分享给我与分享给我所在部门的节点")
    @PreAuthorize("@ss.hasPermission('drive:share:query')")
    public Result<List<DriveShareRespVO>> getSharedToMeList() {
        return success(shareService.getSharedToMeList(getLoginUserId()));
    }
}
