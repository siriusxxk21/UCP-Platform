package com.lingan.ucp.nocode.runtime.service.bizfile;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;

/** 将任务身份写入既有上传会话键；保存附件时重验，不能只凭同一上传人跨任务引用 fileId。 */
public final class TaskBusinessFileSessions {
    private static final String PREFIX = "task_";

    private TaskBusinessFileSessions() {}

    public static String key(String taskId, String entryKey, String recordId, String clientKey) {
        return prefix(taskId, entryKey, recordId)
                + DigestUtil.sha256Hex(clientKey).substring(0, 32);
    }

    public static boolean scoped(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    /** 普通入口不能使用任务会话；任务办理也不能借入另一节点、办理项或记录的临时附件。 */
    public static void require(TaskEntryRuntimeScope scope, String key, String recordId) {
        TaskEntryRuntimeScope.Invocation invocation = scope.current();
        TaskEntryRuntimeScope.TaskData task = invocation == null ? null : invocation.data();
        if (task == null && !scoped(key)) return;
        if (task == null
                || !task.writable()
                || key == null
                || !key.startsWith(prefix(task.taskId(), task.entryKey(), recordId)))
            throw invalid("附件上传会话不属于当前任务办理项，请在当前任务重新上传");
    }

    private static String prefix(String taskId, String entryKey, String recordId) {
        return PREFIX
                + DigestUtil.sha256Hex(
                                taskId
                                        + "\n"
                                        + entryKey
                                        + "\n"
                                        + (recordId == null ? "" : recordId))
                        .substring(0, 27);
    }
}
