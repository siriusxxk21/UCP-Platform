package com.lingan.ucp.nocode.controller.admin;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.EngineLink;
import com.lingan.ucp.nocode.runtime.service.engine.EngineLinkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;

import org.springframework.web.bind.annotation.*;

/**
 * 设计引擎回调系统的两个接口（laneEG）。
 *
 * <p>不走登录会话：调用方是引擎服务，凭系统签发的短期令牌（请求头 {@code X-Engine-Token}，不用 Authorization，避免底座把它当 OAuth2
 * 令牌查库）。令牌在服务层验签，并按令牌用户实时复查记录与对象权限；令牌本身不授予权限。只开这两个接口。
 */
@Tag(name = "无代码 - 设计引擎回调")
@RestController
@RequestMapping("/nocode/engine-link")
public class EngineLinkController {
    static final String TOKEN_HEADER = "X-Engine-Token";

    @Resource private EngineLinkService engines;

    @GetMapping("/library")
    @PermitAll
    @Operation(summary = "设计引擎读取材料库/构件库（只读）")
    public Result<EngineLink.LibraryPage> library(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam String kind,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int pageNo) {
        return Result.success(engines.library(token, kind, search, pageNo));
    }

    @PostMapping("/bom")
    @PermitAll
    @Operation(summary = "设计引擎写回材料清单（幂等，按当前记录覆盖）")
    public Result<EngineLink.BomResult> bom(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody EngineLink.BomCommand command) {
        return Result.success(engines.bom(token, command));
    }
}
