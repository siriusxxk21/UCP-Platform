package com.richuang.os.module.system.legacy.controller;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.legacy.service.MessageComponentService;
import com.richuang.os.module.system.legacy.vo.MessageComponentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消息内容组件Controller（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/message-component")
@RequiredArgsConstructor
public class MessageComponentController {

    private final MessageComponentService componentService;

    /**
     * 获取所有可用组件
     */
    @GetMapping("/list")
    public Result<List<MessageComponentVO>> list() {
        return Result.success(componentService.listAvailable());
    }

    /**
     * 获取组件详情
     */
    @GetMapping("/{id}")
    public Result<MessageComponentVO> getDetail(@PathVariable String id) {
        return Result.success(componentService.getDetail(id));
    }

    /**
     * 根据类型获取组件列表
     */
    @GetMapping("/type/{componentType}")
    public Result<List<MessageComponentVO>> listByType(@PathVariable String componentType) {
        return Result.success(componentService.listByType(componentType));
    }
}
