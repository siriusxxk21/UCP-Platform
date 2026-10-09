package com.richuang.os.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.TaskCenter.Detail;
import com.richuang.os.nocode.api.TaskCenter.Draft;
import com.richuang.os.nocode.api.TaskCenter.DraftPublish;
import com.richuang.os.nocode.api.TaskCenter.DraftRef;
import com.richuang.os.nocode.api.TaskCenter.DraftSave;
import com.richuang.os.nocode.api.TaskCenter.DraftSummary;
import com.richuang.os.nocode.api.TaskCenter.Ref;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskLaunchDraftService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 个人任务编排草稿入口；发起权限是入口资格，所有者边界由服务层再次约束。 */
@Tag(name = "无代码 - 任务编排草稿")
@RestController
@RequestMapping("/nocode/tasks")
@PreAuthorize("isAuthenticated()")
public class TaskLaunchDraftController {
    @Resource private TaskLaunchDraftService drafts;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/drafts")
    @Operation(summary = "列出本人未发布的任务编排草稿")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<List<DraftSummary>> list() {
        return Result.success(drafts.list(access.actor()));
    }

    @PostMapping("/draft-get")
    @Operation(summary = "读取本人任务编排草稿")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<Draft> get(@RequestBody JsonNode body) {
        return Result.success(drafts.get(requests.design(body, Ref.class).id(), access.actor()));
    }

    @PostMapping("/draft-save")
    @Operation(summary = "保存本人任务编排草稿")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<Draft> save(@RequestBody JsonNode body) {
        return Result.success(drafts.save(requests.design(body, DraftSave.class), access.actor()));
    }

    @PostMapping("/draft-delete")
    @Operation(summary = "删除本人未发布的任务编排草稿")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<Boolean> delete(@RequestBody JsonNode body) {
        drafts.delete(requests.design(body, DraftRef.class), access.actor());
        return Result.success(true);
    }

    @PostMapping("/draft-publish")
    @Operation(summary = "原子发布本人任务编排草稿")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<Detail> publish(@RequestBody JsonNode body) {
        return Result.success(
                drafts.publish(requests.design(body, DraftPublish.class), access.actor()));
    }
}
