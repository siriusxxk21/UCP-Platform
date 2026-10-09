package com.richuang.os.module.bpm.controller.admin.definition;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.bpm.controller.admin.definition.vo.group.BpmUserGroupPageReqVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.group.BpmUserGroupRespVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.group.BpmUserGroupSaveReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmUserGroupDO;
import com.richuang.os.module.bpm.service.definition.BpmUserGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.richuang.os.framework.common.pojo.Result.success;
import static com.richuang.os.framework.common.util.collection.CollectionUtils.convertList;

@Tag(name = "管理后台 - 用户组")
@RestController
@RequestMapping("/bpm/user-group")
@Validated
public class BpmUserGroupController {

    @Resource
    private BpmUserGroupService userGroupService;

    @PostMapping("/create")
    @Operation(summary = "创建用户组")
    @PreAuthorize("@ss.hasPermission('bpm:user-group:create')")
    public Result<Long> createUserGroup(@Valid @RequestBody BpmUserGroupSaveReqVO createReqVO) {
        return success(userGroupService.createUserGroup(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新用户组")
    @PreAuthorize("@ss.hasPermission('bpm:user-group:update')")
    public Result<Boolean> updateUserGroup(@Valid @RequestBody BpmUserGroupSaveReqVO updateReqVO) {
        userGroupService.updateUserGroup(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户组")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:user-group:delete')")
    public Result<Boolean> deleteUserGroup(@RequestParam("id") Long id) {
        userGroupService.deleteUserGroup(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得用户组")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('bpm:user-group:query')")
    public Result<BpmUserGroupRespVO> getUserGroup(@RequestParam("id") Long id) {
        BpmUserGroupDO userGroup = userGroupService.getUserGroup(id);
        return success(BeanUtils.toBean(userGroup, BpmUserGroupRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得用户组分页")
    @PreAuthorize("@ss.hasPermission('bpm:user-group:query')")
    public Result<PageResult<BpmUserGroupRespVO>> getUserGroupPage(@Valid BpmUserGroupPageReqVO pageVO) {
        PageResult<BpmUserGroupDO> pageResult = userGroupService.getUserGroupPage(pageVO);
        return success(BeanUtils.toBean(pageResult, BpmUserGroupRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取用户组精简信息列表", description = "只包含被开启的用户组，主要用于前端的下拉选项")
    public Result<List<BpmUserGroupRespVO>> getUserGroupSimpleList() {
        List<BpmUserGroupDO> list = userGroupService.getUserGroupListByStatus(CommonStatusEnum.ENABLE.getStatus());
        return success(convertList(list, group -> new BpmUserGroupRespVO().setId(group.getId()).setName(group.getName())));
    }

}
