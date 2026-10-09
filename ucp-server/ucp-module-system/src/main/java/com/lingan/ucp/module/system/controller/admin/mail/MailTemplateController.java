package com.lingan.ucp.module.system.controller.admin.mail;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.system.controller.admin.mail.vo.template.*;
import com.lingan.ucp.module.system.dal.dataobject.mail.MailTemplateDO;
import com.lingan.ucp.module.system.service.mail.MailSendService;
import com.lingan.ucp.module.system.service.mail.MailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 邮件模版")
@RestController
@RequestMapping("/system/mail-template")
public class MailTemplateController {

    @Resource
    private MailTemplateService mailTempleService;
    @Resource
    private MailSendService mailSendService;

    @PostMapping("/create")
    @Operation(summary = "创建邮件模版")
    @PreAuthorize("@ss.hasPermission('system:mail-template:create')")
    public Result<Long> createMailTemplate(@Valid @RequestBody MailTemplateSaveReqVO createReqVO){
        return success(mailTempleService.createMailTemplate(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改邮件模版")
    @PreAuthorize("@ss.hasPermission('system:mail-template:update')")
    public Result<Boolean> updateMailTemplate(@Valid @RequestBody MailTemplateSaveReqVO updateReqVO){
        mailTempleService.updateMailTemplate(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除邮件模版")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:mail-template:delete')")
    public Result<Boolean> deleteMailTemplate(@RequestParam("id") Long id) {
        mailTempleService.deleteMailTemplate(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除邮件模版")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:mail-template:delete')")
    public Result<Boolean> deleteMailTemplateList(@RequestParam("ids") List<Long> ids) {
        mailTempleService.deleteMailTemplateList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得邮件模版")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:mail-template:query')")
    public Result<MailTemplateRespVO> getMailTemplate(@RequestParam("id") Long id) {
        MailTemplateDO template = mailTempleService.getMailTemplate(id);
        return success(BeanUtils.toBean(template, MailTemplateRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得邮件模版分页")
    @PreAuthorize("@ss.hasPermission('system:mail-template:query')")
    public Result<PageResult<MailTemplateRespVO>> getMailTemplatePage(@Valid MailTemplatePageReqVO pageReqVO) {
        PageResult<MailTemplateDO> pageResult = mailTempleService.getMailTemplatePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, MailTemplateRespVO.class));
    }

    @GetMapping({"/list-all-simple", "/simple-list"})
    @Operation(summary = "获得邮件模版精简列表", description = "只包含被开启的邮件模版，主要用于前端的下拉选项")
    public Result<List<MailTemplateSimpleRespVO>> getSimpleMailTemplateList() {
        List<MailTemplateDO> list = mailTempleService.getMailTemplateListByStatus(
                CommonStatusEnum.ENABLE.getStatus());
        return success(BeanUtils.toBean(list, MailTemplateSimpleRespVO.class));
    }

    @PostMapping("/send-mail")
    @Operation(summary = "发送短信")
    @PreAuthorize("@ss.hasPermission('system:mail-template:send-mail')")
    public Result<Long> sendMail(@Valid @RequestBody MailTemplateSendReqVO sendReqVO) {
        return success(mailSendService.sendSingleMailToAdmin(getLoginUserId(),
                sendReqVO.getToMails(), sendReqVO.getCcMails(), sendReqVO.getBccMails(),
                sendReqVO.getTemplateCode(), sendReqVO.getTemplateParams()));
    }

}
