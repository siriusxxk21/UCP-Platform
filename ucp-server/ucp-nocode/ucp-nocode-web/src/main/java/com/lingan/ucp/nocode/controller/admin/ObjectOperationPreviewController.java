package com.lingan.ucp.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.ObjectOperationPreview;
import com.lingan.ucp.nocode.enums.ObjectOperationEnum;
import com.lingan.ucp.nocode.metadata.service.object.ObjectOperationPreviewService;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 数据中心操作预检沿用管理／编辑权限，不提供业务写入或应用发布入口。 */
@Tag(name = "无代码 - 对象操作预检")
@RestController
@RequestMapping("/nocode/design")
@PreAuthorize("isAuthenticated()")
public class ObjectOperationPreviewController {
    @Resource private ObjectOperationPreviewService previews;
    @Resource private StrictRequestDecoder requests;
    @Resource private NocodeAccess access;

    @PostMapping("/operation-preview")
    @Operation(summary = "只读预检对象启停删除和字段停用恢复")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<ObjectOperationPreview.Result> preview(@RequestBody JsonNode body) {
        ObjectOperationPreview.Request request =
                requests.design(body, ObjectOperationPreview.Request.class);
        ObjectOperationEnum operation = ObjectOperationEnum.fromCode(request.operation());
        if (operation.fieldOperation() ? !access.update() : !access.manage())
            throw new AccessDeniedException(operation.fieldOperation() ? "没有对象编辑权限" : "没有对象管理权限");
        return Result.success(previews.preview(request));
    }
}
