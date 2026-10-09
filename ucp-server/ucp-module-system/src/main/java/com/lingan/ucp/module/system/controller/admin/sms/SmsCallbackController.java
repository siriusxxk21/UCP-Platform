package com.lingan.ucp.module.system.controller.admin.sms;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.servlet.ServletUtils;
import com.lingan.ucp.framework.tenant.core.aop.TenantIgnore;
import com.lingan.ucp.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.lingan.ucp.module.system.service.sms.SmsSendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;

import static com.lingan.ucp.framework.common.pojo.Result.success;

@Tag(name = "管理后台 - 短信回调")
@RestController
@RequestMapping("/system/sms/callback")
public class SmsCallbackController {

    @Resource
    private SmsSendService smsSendService;

    @PostMapping("/aliyun")
    @PermitAll
    @TenantIgnore
    @Operation(summary = "阿里云短信的回调", description = "参见 https://help.aliyun.com/document_detail/120998.html 文档")
    public Result<Boolean> receiveAliyunSmsStatus(HttpServletRequest request) throws Throwable {
        String text = ServletUtils.getBody(request);
        smsSendService.receiveSmsStatus(SmsChannelEnum.ALIYUN.getCode(), text);
        return success(true);
    }

    @PostMapping("/tencent")
    @PermitAll
    @TenantIgnore
    @Operation(summary = "腾讯云短信的回调", description = "参见 https://cloud.tencent.com/document/product/382/52077 文档")
    public Result<Boolean> receiveTencentSmsStatus(HttpServletRequest request) throws Throwable {
        String text = ServletUtils.getBody(request);
        smsSendService.receiveSmsStatus(SmsChannelEnum.TENCENT.getCode(), text);
        return success(true);
    }


    @PostMapping("/huawei")
    @PermitAll
    @TenantIgnore
    @Operation(summary = "华为云短信的回调", description = "参见 https://support.huaweicloud.com/api-msgsms/sms_05_0003.html 文档")
    public Result<Boolean> receiveHuaweiSmsStatus(@RequestBody String requestBody) throws Throwable {
        smsSendService.receiveSmsStatus(SmsChannelEnum.HUAWEI.getCode(), requestBody);
        return success(true);
    }

    @PostMapping("/qiniu")
    @PermitAll
    @TenantIgnore
    @Operation(summary = "七牛云短信的回调", description = "参见 https://developer.qiniu.com/sms/5910/message-push 文档")
    public Result<Boolean> receiveQiniuSmsStatus(@RequestBody String requestBody) throws Throwable {
        smsSendService.receiveSmsStatus(SmsChannelEnum.QINIU.getCode(), requestBody);
        return success(true);
    }

}