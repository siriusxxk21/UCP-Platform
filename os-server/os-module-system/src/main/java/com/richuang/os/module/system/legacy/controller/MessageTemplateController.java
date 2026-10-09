package com.richuang.os.module.system.legacy.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.legacy.dto.MessageTemplateDTO;
import com.richuang.os.module.system.legacy.dto.MessageTemplateQueryDTO;
import com.richuang.os.module.system.legacy.service.MessageTemplateService;
import com.richuang.os.module.system.legacy.vo.MessageTemplateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 消息模板Controller（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/message-template")
@RequiredArgsConstructor
public class MessageTemplateController {

    private final MessageTemplateService templateService;

    /**
     * 分页查询模板列表
     */
    @GetMapping("/list")
    public Result<Page<MessageTemplateVO>> list(MessageTemplateQueryDTO queryDTO) {
        return Result.success(templateService.list(queryDTO));
    }

    /**
     * 获取模板详情
     */
    @GetMapping("/{id}")
    public Result<MessageTemplateVO> getDetail(@PathVariable String id) {
        return Result.success(templateService.getDetail(id));
    }

    /**
     * 创建模板
     */
    @PostMapping
    public Result<String> create(@Valid @RequestBody MessageTemplateDTO dto) {
        return Result.success(templateService.create(dto));
    }

    /**
     * 更新模板
     */
    @PutMapping
    public Result<Void> update(@Valid @RequestBody MessageTemplateDTO dto) {
        templateService.update(dto);
        return Result.success();
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        templateService.delete(id);
        return Result.success();
    }

    /**
     * 批量删除模板
     */
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody List<String> ids) {
        templateService.batchDelete(ids);
        return Result.success();
    }

    /**
     * 发布模板
     */
    @PutMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable String id) {
        templateService.publish(id);
        return Result.success();
    }

    /**
     * 下架模板
     */
    @PutMapping("/{id}/unpublish")
    public Result<Void> unpublish(@PathVariable String id) {
        templateService.unpublish(id);
        return Result.success();
    }

    /**
     * 复制模板
     */
    @PostMapping("/{id}/copy")
    public Result<String> copy(@PathVariable String id) {
        return Result.success(templateService.copy(id));
    }

    /**
     * 预览模板渲染结果
     */
    @PostMapping("/{id}/preview")
    public Result<MessageTemplateVO> preview(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> variables) {
        return Result.success(templateService.preview(id, variables));
    }

    /**
     * 获取所有已发布模板（下拉选择用）
     */
    @GetMapping("/published")
    public Result<List<MessageTemplateVO>> listPublished() {
        return Result.success(templateService.listPublished());
    }
}
