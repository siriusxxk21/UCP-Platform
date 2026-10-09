package com.lingan.ucp.nocode.controller.admin.workflow;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.workflow.FlowMaterials;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.workflow.service.material.FlowMaterialService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 身份从登录态读取；请求中的材料 ID 不授予任何业务记录或草稿权限。 */
@Tag(name = "无代码 - 流程办理材料")
@RestController
@RequestMapping("/nocode/flow-material")
@PreAuthorize("isAuthenticated()")
public class FlowMaterialController {
    @Resource private FlowMaterialService service;
    @Resource private NocodeAccess access;

    @PostMapping("/list")
    @Operation(summary = "查询可见流程办理材料")
    public Result<FlowMaterials.Page> list(@RequestBody FlowMaterials.Query command) {
        if (command == null || command.processInstanceId() == null) throw invalid("请选择流程实例");
        return Result.success(service.list(command, access.actor()));
    }

    @PostMapping("/detail")
    @Operation(summary = "读取流程办理材料详情")
    public Result<FlowMaterials.Detail> detail(@RequestBody FlowMaterials.Get command) {
        if (command == null || command.processInstanceId() == null) throw invalid("请选择流程实例");
        return Result.success(service.detail(command, access.actor()));
    }
}
