package com.richuang.os.module.msg.web.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;
import com.richuang.os.module.msg.api.MsgReceiverType;
import com.richuang.os.module.msg.channel.SysMessageWebSocketListener;
import com.richuang.os.module.msg.web.entity.SysMsgNotice;
import com.richuang.os.module.msg.web.mapper.SysMsgNoticeMapper;
import com.richuang.os.module.msg.web.service.SysMsgNoticeService;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/msg/notice")
@RequiredArgsConstructor
@Slf4j
public class SysMsgNoticeController {

    private final SysMsgNoticeMapper sysMsgNoticeMapper;
    private final SysMsgNoticeService sysMsgNoticeService;
    private final SysMessageWebSocketListener sysMessageWebSocketListener;

    /**
     * 分页查询当前用户的消息列表（联表 sys_msg，统一过滤）
     */
    @GetMapping("/page")
    public Result<IPage<SysMsgNoticeVO>> page(@RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) Long msgTemplateId,
                                              @RequestParam(required = false) Integer hasRead,
                                              @RequestParam(required = false) String keyword) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        String receiverId = String.valueOf(userId);

        Page<SysMsgNoticeVO> page = new Page<>(pageNo, pageSize);
        // MyBatis Plus 分页插件会自动拦截 count 查询和 limit 拼接
        IPage<SysMsgNoticeVO> result = sysMsgNoticeMapper.selectNoticePage(
                page, receiverId, msgTemplateId, hasRead, keyword);

        // 如果分页插件未正确处理 count，手动补充
        if (result.getTotal() == 0 && CollUtil.isNotEmpty(result.getRecords())) {
            long total = sysMsgNoticeMapper.selectNoticeCount(
                    receiverId, msgTemplateId, hasRead, keyword);
            result.setTotal(total);
        }

        return Result.success(result);
    }

    /**
     * 标记消息为已读
     */
    @PutMapping("/read")
    public Result<Boolean> markRead(@RequestBody List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Result.success(true);
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        sysMsgNoticeService.lambdaUpdate()
                .in(SysMsgNotice::getId, ids)
                .eq(SysMsgNotice::getReceiverId, String.valueOf(userId))
                .eq(SysMsgNotice::getHasRead, 0)
                .set(SysMsgNotice::getHasRead, 1)
                .set(SysMsgNotice::getReadTime, LocalDateTime.now())
                .update();
        sysMessageWebSocketListener.msgCountNotice(userId);
        return Result.success(true);
    }

    /**
     * 获取消息通知详情
     */
    @GetMapping("/{messageId}")
    public Result<SysMsgNoticeVO> getDetail(@PathVariable Long messageId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        SysMsgNoticeVO vo = sysMsgNoticeMapper.selectNoticeById(messageId, String.valueOf(userId));
        if (vo == null) {
            return Result.error(404, "消息不存在");
        }
        return Result.success(vo);
    }

    /**
     * 获取未读消息数量
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        long count = sysMsgNoticeService.lambdaQuery()
                .eq(SysMsgNotice::getReceiverType, MsgReceiverType.USER.name())
                .eq(SysMsgNotice::getReceiverId, String.valueOf(userId))
                .eq(SysMsgNotice::getHasRead, 0)
                .count();
        return Result.success(count);
    }

    /**
     * 消息通知 VO（包含 SysMsg 的标题、内容等）
     */
    @lombok.Data
    public static class SysMsgNoticeVO {
        private Long id;
        private Long msgId;
        private Long msgTemplateId;
        private String title;
        private String content;
        private String url;
        private String sourceType;
        private String sourceId;
        private Integer priority;
        private String ownerName;
        private Integer hasRead;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date noticeTime;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date readTime;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime sendTime;
    }
}
