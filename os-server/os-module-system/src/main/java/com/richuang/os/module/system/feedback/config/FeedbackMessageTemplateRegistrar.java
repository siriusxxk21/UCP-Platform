package com.richuang.os.module.system.feedback.config;

import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.msg.api.MsgTypeRegistParam;
import com.richuang.os.module.system.feedback.service.SystemFeedbackServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建反馈通知模板，已有模板由管理员维护且不会被覆盖。
 */
@Component
@RequiredArgsConstructor
public class FeedbackMessageTemplateRegistrar implements ApplicationRunner {

    private static final String CONTENT = """
            <div style="font-size:14px;line-height:1.8;color:#303133;">
              <div><strong>${typeLabel}：${title}</strong></div>
              <div><span style="color:#909399;">反馈类型：</span>${typeKey}（${typeValue}）</div>
              <div><span style="color:#909399;">提交人：</span>${submitterName}</div>
              <div><span style="color:#909399;">来源页面：</span>${pageTitle}（${pagePath}）</div>
              <div><span style="color:#909399;">构建版本：</span>${buildCommit}</div>
              <div><span style="color:#909399;">提交时间：</span>${submittedAt}</div>
              <div style="margin-top:10px;padding:12px;background:#f5f7fa;border-radius:6px;">${descriptionHtml}</div>
              ${imageHtml}
            </div>
            """;
    private static final String NOTICE_CONFIG = """
            [{"channel":"default","open":1,"msgTemplate":{
              "title":"【${typeLabel}】${title}",
              "content":"${submitterName} 提交了${typeLabel}：${title}"
            }}]
            """;
    private static final String WORKFLOW_NOTICE_CONFIG = """
            [{"channel":"default","open":1,"msgTemplate":{"title":"","content":""}}]
            """;

    private final IMsgSendService msgSendService;

    @Override
    public void run(ApplicationArguments args) {
        msgSendService.registMsgTypeIfAbsent(new MsgTypeRegistParam()
                .setCode(SystemFeedbackServiceImpl.MESSAGE_CODE)
                .setName("用户问题反馈")
                .setPriority(3)
                .setSubscribeAble(0)
                .setTemplateTitle("【${typeLabel}】${title}")
                .setTemplateContent(CONTENT)
                .setTemplateUrl("")
                .setNoticeConfig(NOTICE_CONFIG));
        registerWorkflowTemplate(SystemFeedbackServiceImpl.HANDLER_MESSAGE_CODE,
                "反馈处理通知", "/system/feedback");
        registerWorkflowTemplate(SystemFeedbackServiceImpl.SUBMITTER_MESSAGE_CODE,
                "反馈确认通知", "/workspace");
    }

    /** 工作流通知使用独立模板，避免覆盖管理员维护的提交反馈模板和接收人配置。 */
    private void registerWorkflowTemplate(String code, String name, String route) {
        msgSendService.registMsgTypeIfAbsent(new MsgTypeRegistParam()
                .setCode(code)
                .setName(name)
                .setPriority(2)
                .setSubscribeAble(0)
                .setTemplateTitle("【反馈跟进】${title}")
                .setTemplateContent("${content}")
                .setTemplateUrl(route)
                .setNoticeConfig(WORKFLOW_NOTICE_CONFIG));
    }
}
