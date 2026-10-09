package com.lingan.ucp.module.drive.controller.admin.space;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveBizSpaceCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveSpaceCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveSpaceRespVO;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveSpaceUpdateReqVO;
import com.lingan.ucp.module.drive.service.space.DriveSpaceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台 - 网盘空间")
@RestController
@RequestMapping("/drive/space")
@Validated
public class DriveSpaceController {

    @Resource private DriveSpaceService spaceService;

    @GetMapping("/my-list")
    @Operation(summary = "获得我的空间列表", description = "含个人空间与已获授权的团队空间")
    @PreAuthorize("@ss.hasPermission('drive:space:query')")
    public Result<List<DriveSpaceRespVO>> getMySpaceList() {
        return success(spaceService.getMySpaceList(getLoginUserId()));
    }

    @GetMapping("/manage-list")
    @Operation(summary = "获得可治理空间列表", description = "普通空间按用户权限返回，业务空间仅向空间治理员展示")
    @PreAuthorize(
            "@ss.hasPermission('drive:space:query') && "
                    + "@ss.hasAnyPermissions('drive:space:create', 'drive:space:update')")
    public Result<List<DriveSpaceRespVO>> getManageSpaceList() {
        return success(spaceService.getManageSpaceList(getLoginUserId()));
    }

    @GetMapping("/business-list")
    @Operation(summary = "获得可进入的业务空间列表", description = "业务空间的文件夹区：只返回当前用户在空间根上有角色的业务空间")
    @PreAuthorize("@ss.hasPermission('drive:space:query')")
    public Result<List<DriveSpaceRespVO>> getBusinessSpaceList() {
        return success(spaceService.getBusinessSpaceList(getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得空间", description = "含当前用户在该空间上的角色，无权访问时拒绝")
    @Parameter(name = "id", description = "空间编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('drive:space:query')")
    public Result<DriveSpaceRespVO> getSpace(@RequestParam("id") Long id) {
        return success(spaceService.getSpaceDetail(id, getLoginUserId()));
    }

    @PostMapping("/create")
    @Operation(summary = "创建团队空间")
    @PreAuthorize("@ss.hasPermission('drive:space:create')")
    public Result<Long> createSpace(@Valid @RequestBody DriveSpaceCreateReqVO reqVO) {
        return success(spaceService.createTeamSpace(reqVO, getLoginUserId()));
    }

    @PostMapping("/create-business")
    @Operation(summary = "创建业务空间", description = "业务空间无归属人，内容访问仅由业务授权链决定")
    @PreAuthorize("@ss.hasPermission('drive:space:create')")
    public Result<Long> createBusinessSpace(@Valid @RequestBody DriveBizSpaceCreateReqVO reqVO) {
        return success(spaceService.createBizSpace(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改团队空间")
    @PreAuthorize("@ss.hasPermission('drive:space:update')")
    public Result<Boolean> updateSpace(@Valid @RequestBody DriveSpaceUpdateReqVO reqVO) {
        spaceService.updateTeamSpace(reqVO, getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除团队空间", description = "空间内仍有内容时不允许删除")
    @Parameter(name = "id", description = "空间编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('drive:space:delete')")
    public Result<Boolean> deleteSpace(@RequestParam("id") Long id) {
        spaceService.deleteTeamSpace(id, getLoginUserId());
        return success(true);
    }
}
