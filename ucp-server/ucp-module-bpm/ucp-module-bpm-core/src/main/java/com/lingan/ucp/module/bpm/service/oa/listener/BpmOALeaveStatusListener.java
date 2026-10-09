package com.lingan.ucp.module.bpm.service.oa.listener;

import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatusEventListener;
import com.lingan.ucp.module.bpm.service.oa.BpmOALeaveService;
import com.lingan.ucp.module.bpm.service.oa.BpmOALeaveServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * OA 请假单的结果的监听器实现类
 *
 * @author 芋道源码
 */
@Component
public class BpmOALeaveStatusListener extends BpmProcessInstanceStatusEventListener {

    @Resource
    private BpmOALeaveService leaveService;

    @Override
    protected String getProcessDefinitionKey() {
        return BpmOALeaveServiceImpl.PROCESS_KEY;
    }

    @Override
    protected void onEvent(BpmProcessInstanceStatusEvent event) {
        leaveService.updateLeaveStatus(Long.parseLong(event.getBusinessKey()), event.getStatus());
    }

}
