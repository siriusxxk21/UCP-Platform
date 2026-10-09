package com.lingan.ucp.nocode.controller.admin;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.ObjectSharing;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 对象共享由数据管理员维护；应用侧只读取上限，不能通过成员授权扩大范围。 */
@Tag(name = "无代码 - 对象共享")
@RestController
@RequestMapping("/nocode/object-sharing")
public class ObjectSharingController {
    @Resource private ObjectSharingService sharing;
    @Resource private ApplicationService applications;
    @Resource private NocodeAccess access;
    @Resource private com.lingan.ucp.nocode.api.DataObjectApi objects;

    @GetMapping("/definition")
    @Operation(summary = "读取对象共享定义")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<com.lingan.ucp.nocode.api.DataObjectApi.PublishedObject> definition(
            @RequestParam String objectId) {
        return Result.success(objects.getVersion(objectId, null));
    }

    @GetMapping("/targets")
    @Operation(summary = "查询对象共享目标")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<List<ObjectSharing.Target>> targets() {
        return Result.success(sharing.targets(access.actor()));
    }

    @GetMapping("/list")
    @Operation(summary = "查询对象共享授权")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<List<ObjectSharing.Grant>> list(@RequestParam String objectId) {
        return Result.success(sharing.forObject(objectId, access.actor()));
    }

    @GetMapping("/application")
    @Operation(summary = "读取指定应用的对象共享授权")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<List<ObjectSharing.Grant>> application(@RequestParam String id) {
        applications.requireDesigner(id, access.actor());
        return Result.success(sharing.forApplication(id));
    }

    @PostMapping("/save")
    @Operation(summary = "保存对象共享授权")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<ObjectSharing.Grant> save(@RequestBody ObjectSharing.Save command) {
        return Result.success(sharing.save(command, access.actor()));
    }
}
