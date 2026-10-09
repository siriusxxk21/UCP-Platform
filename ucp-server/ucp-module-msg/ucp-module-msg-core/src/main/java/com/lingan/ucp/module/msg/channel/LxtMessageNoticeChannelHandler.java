package com.lingan.ucp.module.msg.channel;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.framework.common.util.http.HttpUtils;
import com.lingan.ucp.module.msg.api.MsgReceiverType;
import com.lingan.ucp.module.msg.core.MessageNoticeProperty;
import com.lingan.ucp.module.msg.core.MsgNoticeTask;
import com.lingan.ucp.module.msg.core.MsgStrTemplateUtil;
import com.lingan.ucp.module.msg.core.channel.*;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "message.notice.lxt.enabled", matchIfMissing = true)
public class LxtMessageNoticeChannelHandler implements NoticeChannelHandler {

    private final MessageNoticeProperty messageNoticeProperty;

    private final AdminUserApi adminUserApi;

    public LxtMessageNoticeChannelHandler(MessageNoticeProperty messageNoticeProperty, AdminUserApi adminUserApi) {
        this.messageNoticeProperty = messageNoticeProperty;
        this.adminUserApi = adminUserApi;
    }

    @Override
    public NoticeChannel support() {
        return new NoticeChannel("lxt", "凌信通", null,
                Arrays.asList(
                        Metadata.of("adviceTitle", "通知标题", MetadataType.text, null, false),
                        Metadata.of("adviceContent", "通知内容", MetadataType.richText, null, false),
                        Metadata.of("adviceLinkUrl", "通知链接", MetadataType.text, null, false),
                        Metadata.of("sourceId", "源数据ID", MetadataType.text, null, false),
                        Metadata.of("modelName", "模块名称", MetadataType.text, null, false)))
                ;
    }

    @Override
    public void execute(MsgNoticeTask noticeTask, JSONObject template) throws NoticeException {
        String userName = noticeTask.getNotice().getReceiverId();
        if (MsgReceiverType.USER.name().equals(noticeTask.getNotice().getReceiverType())) {
            AdminUserRespDTO user = adminUserApi.getUser(Long.valueOf(noticeTask.getNotice().getReceiverId()));
            if (user == null) {
                throw new NoticeException("用户不存在");
            }
            userName = user.getUsername();
        }

        Map<String, Object> context = JSONObject.parseObject(noticeTask.getMsg().getMsgData());
        Map<String, Object> body = new HashMap<>();
        body.put("adviceSendUserName", null);
        body.put("adviceSendName", "系统");
        body.put("adviceTitle", StrUtil.blankToDefault(MsgStrTemplateUtil.parse(template.getString("adviceTitle"), context), noticeTask.getMsg().getTitle()));
        body.put("adviceContent", StrUtil.blankToDefault(MsgStrTemplateUtil.parse(template.getString("adviceContent"), context), noticeTask.getMsg().getContent()));
        body.put("adviceLinkUrl", StrUtil.blankToDefault(MsgStrTemplateUtil.parse(template.getString("adviceLinkUrl"), context), null));
        body.put("toUserNames", userName);
        body.put("adviceSecret", "0");
        body.put("adviceType", "2");
        body.put("skipType", null);
        body.put("sourceId", StrUtil.blankToDefault(MsgStrTemplateUtil.parse(template.getString("sourceId"), context), null));
        body.put("sysName", StrUtil.blankToDefault(messageNoticeProperty.getLxt().getSysName(), "凌安智研管理平台"));
        body.put("modelName", StrUtil.blankToDefault(MsgStrTemplateUtil.parse(template.getString("modelName"), context), null));
        body.put("adviceCategory", "2");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("appId", messageNoticeProperty.getLxt().getAppId());

        String responseBody = HttpUtils.post(messageNoticeProperty.getLxt().getUrl(), headers, JSONObject.toJSONString(body));
        JSONObject response = JSONObject.parseObject(responseBody);
        if (!response.getBooleanValue("success")) {
            throw new BusinessException(responseBody);
        }
    }
}
