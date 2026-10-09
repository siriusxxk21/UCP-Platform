package com.lingan.ucp.module.system.controller.admin.dept;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.system.controller.admin.dept.vo.dept.*;
import com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO;
import com.lingan.ucp.module.system.service.dept.DeptService;
import com.lingan.ucp.module.system.service.dept.UserDeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.lingan.ucp.framework.common.pojo.Result.success;

@Tag(name = "管理后台 - 部门")
@RestController
@RequestMapping("/system/dept")
@Validated
public class DeptController {

    @Resource
    private DeptService deptService;
    @Resource
    private UserDeptService userDeptService;

    @PostMapping("create")
    @Operation(summary = "创建部门")
    @PreAuthorize("@ss.hasPermission('system:dept:create')")
    public Result<Long> createDept(@Valid @RequestBody DeptSaveReqVO createReqVO) {
        Long deptId = deptService.createDept(createReqVO);
        return success(deptId);
    }

    @PostMapping
    @Operation(summary = "创建部门")
    @PreAuthorize("@ss.hasPermission('system:dept:create')")
    public Result<Long> createDeptByRest(@Valid @RequestBody DeptSaveReqVO createReqVO) {
        return createDept(createReqVO);
    }

    @PutMapping("update")
    @Operation(summary = "更新部门")
    @PreAuthorize("@ss.hasPermission('system:dept:update')")
    public Result<Boolean> updateDept(@Valid @RequestBody DeptSaveReqVO updateReqVO) {
        deptService.updateDept(updateReqVO);
        return success(true);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新部门")
    @PreAuthorize("@ss.hasPermission('system:dept:update')")
    public Result<Boolean> updateDeptByRest(@PathVariable("id") Long id,
                                            @Valid @RequestBody DeptSaveReqVO updateReqVO) {
        updateReqVO.setId(id);
        deptService.updateDept(updateReqVO);
        return success(true);
    }

    @DeleteMapping("delete")
    @Operation(summary = "删除部门")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:dept:delete')")
    public Result<Boolean> deleteDept(@RequestParam("id") Long id) {
        deptService.deleteDept(id);
        return success(true);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除部门")
    @PreAuthorize("@ss.hasPermission('system:dept:delete')")
    public Result<Boolean> deleteDeptByRest(@PathVariable("id") Long id) {
        deptService.deleteDept(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除部门")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:dept:delete')")
    public Result<Boolean> deleteDeptList(@RequestParam("ids") List<Long> ids) {
        deptService.deleteDeptList(ids);
        return success(true);
    }

    @GetMapping("/list")
    @Operation(summary = "获取部门列表")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public Result<List<DeptRespVO>> getDeptList(DeptListReqVO reqVO) {
        List<DeptDO> list = deptService.getDeptList(reqVO);
        return success(BeanUtils.toBean(list, DeptRespVO.class));
    }

    @GetMapping("/tree")
    @Operation(summary = "获取部门树")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public Result<List<DeptRespVO>> getDeptTree(DeptListReqVO reqVO) {
        List<DeptDO> list = deptService.getDeptList(reqVO);
        return success(BeanUtils.toBean(list, DeptRespVO.class));
    }

    @GetMapping("/tree/{orgId}")
    @Operation(summary = "根据组织编号获取部门树")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public Result<List<DeptRespVO>> getDeptTreeByOrgId(@PathVariable("orgId") Long orgId,
                                                       @RequestParam(value = "name", required = false) String name,
                                                       @RequestParam(value = "deptName", required = false) String deptName,
                                                       @RequestParam(value = "status", required = false) Integer status) {
        DeptListReqVO reqVO = new DeptListReqVO();
        reqVO.setOrgId(orgId);
        reqVO.setName(StringUtils.hasText(name) ? name : deptName);
        reqVO.setStatus(status);
        List<DeptDO> list = deptService.getDeptList(reqVO);
        return success(BeanUtils.toBean(list, DeptRespVO.class));
    }

    @GetMapping(value = {"/list-all-simple", "/simple-list"})
    @Operation(summary = "获取部门精简信息列表", description = "只包含被开启的部门，主要用于前端的下拉选项")
    public Result<List<DeptSimpleRespVO>> getSimpleDeptList() {
        List<DeptDO> list = deptService.getDeptList(
                new DeptListReqVO().setStatus(CommonStatusEnum.ENABLE.getStatus()));
        return success(BeanUtils.toBean(list, DeptSimpleRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获得部门信息")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public Result<DeptRespVO> getDept(@RequestParam("id") Long id) {
        DeptDO dept = deptService.getDept(id);
        return success(BeanUtils.toBean(dept, DeptRespVO.class));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新部门状态")
    @PreAuthorize("@ss.hasPermission('system:dept:update')")
    public Result<Boolean> updateDeptStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        DeptDO dept = deptService.getDept(id);
        DeptSaveReqVO updateReqVO = BeanUtils.toBean(dept, DeptSaveReqVO.class);
        updateReqVO.setStatus(status);
        deptService.updateDept(updateReqVO);
        return success(true);
    }

    @PostMapping("/list-by-ids")
    @Operation(summary = "根据部门编号列表获取部门")
    public Result<List<DeptRespVO>> getDeptListByIds(@RequestBody List<Long> ids) {
        List<DeptDO> list = deptService.getDeptList(ids);
        return success(BeanUtils.toBean(list, DeptRespVO.class));
    }

    @GetMapping("/{deptId}/users/page")
    @Operation(summary = "分页获取部门用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public Result<PageResult<DeptUserRespVO>> getDeptUsersPage(@PathVariable("deptId") Long deptId,
                                                               @RequestParam(defaultValue = "1") Integer pageNum,
                                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                                               @RequestParam(required = false) String username) {
        return success(userDeptService.getDeptUsersPage(deptId, pageNum, pageSize, username));
    }

    @GetMapping("/{deptId}/users")
    @Operation(summary = "获取部门用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public Result<List<DeptUserRespVO>> getDeptUsers(@PathVariable("deptId") Long deptId,
                                                     @RequestParam(required = false) String username) {
        return success(userDeptService.getDeptUsers(deptId, username));
    }

    @PostMapping("/{deptId}/users")
    @Operation(summary = "添加用户到部门")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public Result<Boolean> addUserToDept(@PathVariable("deptId") Long deptId,
                                         @Valid @RequestBody DeptUserSaveReqVO reqVO) {
        userDeptService.addUsers(deptId, List.of(reqVO.getUserId()), reqVO.getPost(),
                Boolean.TRUE.equals(reqVO.getIsMain()));
        return success(true);
    }

    @PostMapping("/{deptId}/users/batch")
    @Operation(summary = "批量添加用户到部门")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public Result<Boolean> batchAddUsersToDept(@PathVariable("deptId") Long deptId,
                                               @Valid @RequestBody DeptUserBatchSaveReqVO reqVO) {
        userDeptService.addUsers(deptId, reqVO.getUserIds(), reqVO.getPost(),
                Boolean.TRUE.equals(reqVO.getIsMain()));
        return success(true);
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "从部门移除用户")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public Result<Boolean> removeUserFromDept(@PathVariable("id") Long relationId) {
        userDeptService.removeUser(relationId);
        return success(true);
    }

    @PutMapping("/users/{id}/main")
    @Operation(summary = "设置用户主部门")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public Result<Boolean> setAsMainDept(@PathVariable("id") Long relationId,
                                         @RequestParam("deptId") Long deptId) {
        userDeptService.setMainDept(relationId, deptId);
        return success(true);
    }

    @PutMapping("/users/{id}/post")
    @Operation(summary = "更新用户在部门中的岗位")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public Result<Boolean> updateUserPost(@PathVariable("id") Long relationId,
                                          @RequestParam("post") String post) {
        userDeptService.updatePost(relationId, post);
        return success(true);
    }

    @GetMapping("/{deptId}/available-users/page")
    @Operation(summary = "分页获取可加入部门的用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public Result<PageResult<DeptUserRespVO>> getAvailableUsersPage(@PathVariable("deptId") Long deptId,
                                                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                                                    @RequestParam(defaultValue = "10") Integer pageSize,
                                                                    @RequestParam(required = false) String username) {
        return success(userDeptService.getAvailableUsersPage(deptId, pageNum, pageSize, username));
    }

    @GetMapping("/{deptId}/available-users")
    @Operation(summary = "获取可加入部门的用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public Result<List<DeptUserRespVO>> getAvailableUsers(@PathVariable("deptId") Long deptId,
                                                          @RequestParam(required = false) String username) {
        return success(userDeptService.getAvailableUsers(deptId, username));
    }

    @Data
    public static class DeptUserSaveReqVO {
        @NotNull(message = "用户编号不能为空")
        private Long userId;
        private String post;
        private Boolean isMain;
    }

    @Data
    public static class DeptUserBatchSaveReqVO {
        @NotEmpty(message = "用户编号不能为空")
        private List<Long> userIds;
        private String post;
        private Boolean isMain;
    }

}
