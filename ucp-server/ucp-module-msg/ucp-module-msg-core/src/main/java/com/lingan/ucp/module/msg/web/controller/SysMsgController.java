package com.lingan.ucp.module.msg.web.controller;

import cn.hutool.core.lang.Assert;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.module.msg.api.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/msg")
@RequiredArgsConstructor
@Slf4j
public class SysMsgController {

    private final IMsgSendService msgSendService;

    private final List<MsgTargetService> msgTargetServiceList;

    @GetMapping("/supportTargetTypies")
    public Result<List<MsgTargetType>> supportTargetTypies() {
        return Result.success(msgTargetServiceList.stream()
                .map(MsgTargetService::support)
                .collect(Collectors.toList()));
    }

    /**
     * 发送消息
     */
    @PostMapping("/send")
    public Result<String> send(@RequestBody MsgSendParam sendParam) {
        Assert.notNull(sendParam);
        Assert.notBlank(sendParam.getMsgCode());
        Assert.notBlank(sendParam.getTitle());
//        Assert.notEmpty(sendParam.getTargets());
        sendParam.setOwnerId(String.valueOf(SecurityFrameworkUtils.getLoginUserId()));
        sendParam.setOwnerName(SecurityFrameworkUtils.getLoginUserNickname());
        return Result.success(msgSendService.send(sendParam).toString());
    }
}
