package com.lingan.ucp.nocode.application.service.task;

import com.lingan.ucp.nocode.api.TaskEntries;

/** 入口的实时授权和启停，不修改应用的普通成员权限。 */
public interface TaskEntryPolicyService {
    TaskEntries.Policy get(String applicationId, String entryId);

    TaskEntries.Policy save(TaskEntries.SavePolicy command, long actor);
}
