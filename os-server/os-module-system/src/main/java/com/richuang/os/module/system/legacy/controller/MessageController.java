package com.richuang.os.module.system.legacy.controller;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.legacy.dto.MessageDTO;
import com.richuang.os.module.system.legacy.dto.MessageQueryDTO;
import com.richuang.os.module.system.legacy.service.MessageService;
import com.richuang.os.module.system.legacy.vo.MessageDetailVO;
import com.richuang.os.module.system.legacy.vo.MessagePageVO;
import com.richuang.os.module.system.legacy.vo.UnreadCountVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 消息控制器（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 获取消息列表
     */
    @GetMapping("/list")
    public Result<MessagePageVO> list(MessageQueryDTO queryDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        MessagePageVO pageVO = messageService.list(queryDTO, userId);
        return Result.success(pageVO);
    }

    /**
     * 获取消息详情
     */
    @GetMapping("/{id}")
    public Result<MessageDetailVO> getDetail(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        MessageDetailVO detail = messageService.getDetail(id, userId);
        return Result.success(detail);
    }

    /**
     * 发送消息
     */
    @PostMapping("/send")
    public Result<Void> send(@Valid @RequestBody MessageDTO messageDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");
        messageService.send(messageDTO, userId, tenantId);
        return Result.success();
    }

    /**
     * 保存草稿
     */
    @PostMapping("/draft")
    public Result<Void> saveDraft(@Valid @RequestBody MessageDTO messageDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");
        messageService.saveDraft(messageDTO, userId, tenantId);
        return Result.success();
    }

    /**
     * 标记已读
     */
    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.markAsRead(id, userId);
        return Result.success();
    }

    /**
     * 批量标记已读
     */
    @PutMapping("/read/batch")
    public Result<Void> batchMarkAsRead(@RequestBody List<String> ids, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.batchMarkAsRead(ids, userId);
        return Result.success();
    }

    /**
     * 全部已读
     */
    @PutMapping("/read/all")
    public Result<Void> markAllAsRead(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");
        messageService.markAllAsRead(userId, tenantId);
        return Result.success();
    }

    /**
     * 标星/取消标星
     */
    @PutMapping("/{id}/star")
    public Result<Void> toggleStar(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.toggleStar(id, userId);
        return Result.success();
    }

    /**
     * 删除消息(移入回收站)
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.delete(id, userId);
        return Result.success();
    }

    /**
     * 批量删除消息
     */
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody List<String> ids, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.batchDelete(ids, userId);
        return Result.success();
    }

    /**
     * 恢复消息
     */
    @PutMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.restore(id, userId);
        return Result.success();
    }

    /**
     * 彻底删除消息
     */
    @DeleteMapping("/{id}/permanent")
    public Result<Void> deletePermanently(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        messageService.deletePermanently(id, userId);
        return Result.success();
    }

    /**
     * 获取未读消息数
     */
    @GetMapping("/unread/count")
    public Result<UnreadCountVO> getUnreadCount(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");
        UnreadCountVO count = messageService.getUnreadCount(userId, tenantId);
        return Result.success(count);
    }

    /**
     * 获取发送的消息列表
     */
    @GetMapping("/sent")
    public Result<MessagePageVO> listSent(MessageQueryDTO queryDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String tenantId = (String) request.getAttribute("tenantId");
        MessagePageVO pageVO = messageService.listSent(queryDTO, userId, tenantId);
        return Result.success(pageVO);
    }

    /**
     * 批量级联删除消息（管理端使用）
     * 删除消息及其所有关联数据
     */
    @DeleteMapping("/batch/cascade")
    public Result<Void> batchDeleteCascade(@RequestBody List<String> ids, HttpServletRequest request) {
        String tenantId = (String) request.getAttribute("tenantId");
        messageService.batchDeleteCascade(ids, tenantId);
        return Result.success();
    }
}
