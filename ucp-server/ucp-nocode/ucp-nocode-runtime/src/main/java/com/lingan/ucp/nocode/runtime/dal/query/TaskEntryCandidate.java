package com.lingan.ucp.nocode.runtime.dal.query;

/** 可见任务中的入口候选投影；对外返回前仍由服务复核当前应用权限。 */
public record TaskEntryCandidate(String label, String bindingJson, String businessJson) {}
