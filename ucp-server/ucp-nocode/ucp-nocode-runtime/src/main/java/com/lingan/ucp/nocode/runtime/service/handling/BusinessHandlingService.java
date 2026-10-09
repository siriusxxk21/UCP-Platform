package com.lingan.ucp.nocode.runtime.service.handling;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;

/** 公共业务提交，流程推进由 BPM 底座负责。 */
public interface BusinessHandlingService {
    BusinessHandling.Result submit(ApplicationRecords.Save command, long actor);

    BusinessHandling.Result receipt(BusinessHandling.Receipt query, long actor);

    PageResult<BusinessHandling.Request> mine(BusinessHandling.Query query, long actor);

    BusinessHandling.Detail detail(String id, String taskId, long actor);

    BusinessHandling.Reopen reopen(String id, long actor);

    BusinessHandling.Request withdraw(BusinessHandling.Withdraw command, long actor);

    BusinessHandling.Request retry(BusinessHandling.Retry command, long actor);

    void reconcile();
}
