
package com.richuang.os.module.msg.web.controller;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.msg.core.channel.NoticeChannel;
import com.richuang.os.module.msg.core.channel.NoticeChannelHandler;
import com.richuang.os.module.msg.web.entity.SysMsgTemplate;
import com.richuang.os.module.msg.web.entity.SysMsgTemplateTarget;
import com.richuang.os.module.msg.web.service.SysMsgTemplateService;
import com.richuang.os.module.msg.web.service.SysMsgTemplateTargetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/msg/template")
@RequiredArgsConstructor
@Slf4j
public class SysMsgTemplateController {

    private final SysMsgTemplateService sysMsgTemplateService;

    private final SysMsgTemplateTargetService sysMsgTemplateTargetService;

    private final List<NoticeChannelHandler> channelHandlers;

	/**
	 * 获取支持的通知渠道列表
	 */
	@GetMapping("/supportChannels")
	public Result<List<NoticeChannel>> supportChannels() {
		return Result.success(channelHandlers.stream()
				.map(NoticeChannelHandler::support)
				.collect(Collectors.toList()));
	}

	/**
	 * 查询所有模板列表（用于下拉选择）
	 */
	@GetMapping("/list")
	public Result<List<SysMsgTemplate>> list() {
		LambdaQueryWrapper<SysMsgTemplate> queryWrapper = new LambdaQueryWrapper<SysMsgTemplate>()
				.select(SysMsgTemplate::getId, SysMsgTemplate::getCode, SysMsgTemplate::getName,
						SysMsgTemplate::getSubscribeAble, SysMsgTemplate::getMetaData)
				.orderByDesc(SysMsgTemplate::getCreateTime);
		return Result.success(sysMsgTemplateService.list(queryWrapper));
	}

	/**
	 * 分页列表查询
	 * @param page
	 * @param keyword 关键字（模糊匹配编码或名称）
	 * @return
	 */
	@GetMapping("/page")
	public Result<Page<SysMsgTemplate>> page(Page<SysMsgTemplate> page, @RequestParam(required = false) String keyword) {
		LambdaQueryWrapper<SysMsgTemplate> queryWrapper = new LambdaQueryWrapper<>();
		queryWrapper.and(StrUtil.isNotBlank(keyword), w -> w
						.like(SysMsgTemplate::getCode, keyword)
						.or()
						.like(SysMsgTemplate::getName, keyword))
				.orderByDesc(SysMsgTemplate::getCreateTime);
		return Result.success(sysMsgTemplateService.page(page, queryWrapper));
	}

	/**
	 * 添加
	 */
	@PostMapping("/add")
	@Transactional
	public Result<Boolean> add(@RequestBody SysMsgTemplate entity) {
		return Result.success(sysMsgTemplateService.create(entity));
	}

	/**
	 * 编辑
	 */
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
	public Result<Boolean> edit(@RequestBody SysMsgTemplate entity) {
		return Result.success(sysMsgTemplateService.modify(entity));
	}

	/**
	 * 通过id删除
	 */
	@DeleteMapping("/delete")
	public Result<Boolean> delete(@RequestParam Long id) {
		return Result.success(sysMsgTemplateService.removeById(id));
	}

    /** 查询当前租户的模板默认接收对象。 */
    @GetMapping("/{templateId}/targets")
    public Result<List<SysMsgTemplateTarget>> listTargets(@PathVariable Long templateId) {
        return Result.success(sysMsgTemplateTargetService.listByTemplateId(templateId));
    }

    /** 覆盖保存当前租户的模板默认接收对象。 */
    @PutMapping("/{templateId}/targets")
    public Result<Boolean> saveTargets(@PathVariable Long templateId,
                                       @RequestBody List<SysMsgTemplateTarget> targets) {
        sysMsgTemplateTargetService.replace(templateId, targets);
        return Result.success(true);
    }

}
