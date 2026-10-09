package com.lingan.ucp.nocode.work.service.event;

import com.lingan.ucp.nocode.api.work.WorkSourceRef;

/** 只登记事务事实；事件读取不授予材料访问权限。 */
public interface WorkEventService {
    void submitted(WorkSourceRef source, String submissionId, long actor);
}
