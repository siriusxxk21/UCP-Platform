package com.richuang.os.module.system.legacy.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.legacy.dto.MessageSceneDTO;
import com.richuang.os.module.system.legacy.dto.MessageSceneQueryDTO;
import com.richuang.os.module.system.legacy.service.MessageSceneService;
import com.richuang.os.module.system.legacy.vo.MessageSceneVO;
import com.richuang.os.module.system.legacy.vo.ScheduleJobLogVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务场景Controller（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/message-scene")
@RequiredArgsConstructor
public class MessageSceneController {

    private final MessageSceneService sceneService;

    /**
     * 分页查询场景列表
     */
    @GetMapping("/list")
    public Result<Page<MessageSceneVO>> list(MessageSceneQueryDTO queryDTO) {
        return Result.success(sceneService.list(queryDTO));
    }

    /**
     * 获取场景详情
     */
    @GetMapping("/{id}")
    public Result<MessageSceneVO> getDetail(@PathVariable String id) {
        return Result.success(sceneService.getDetail(id));
    }

    /**
     * 创建场景
     */
    @PostMapping
    public Result<String> create(@Valid @RequestBody MessageSceneDTO dto) {
        return Result.success(sceneService.create(dto));
    }

    /**
     * 更新场景
     */
    @PutMapping
    public Result<Void> update(@Valid @RequestBody MessageSceneDTO dto) {
        sceneService.update(dto);
        return Result.success();
    }

    /**
     * 删除场景
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        sceneService.delete(id);
        return Result.success();
    }

    /**
     * 批量删除场景
     */
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody List<String> ids) {
        sceneService.batchDelete(ids);
        return Result.success();
    }

    /**
     * 启用场景
     */
    @PutMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable String id) {
        sceneService.enable(id);
        return Result.success();
    }

    /**
     * 禁用场景
     */
    @PutMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable String id) {
        sceneService.disable(id);
        return Result.success();
    }

    /**
     * 手动执行场景
     */
    @PostMapping("/{id}/execute")
    public Result<String> execute(@PathVariable String id,
                                  @RequestBody(required = false) Map<String, Object> variables) {
        return Result.success(sceneService.executeManually(id, variables));
    }

    /**
     * 获取场景执行日志
     */
    @GetMapping("/{id}/logs")
    public Result<Page<ScheduleJobLogVO>> logs(@PathVariable String id,
                                               @RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(sceneService.listLogs(id, pageNum, pageSize));
    }
}
