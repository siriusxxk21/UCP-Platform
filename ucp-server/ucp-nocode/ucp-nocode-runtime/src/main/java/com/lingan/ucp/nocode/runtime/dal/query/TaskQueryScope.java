package com.lingan.ucp.nocode.runtime.dal.query;

import com.lingan.ucp.nocode.api.TaskCenter.RecordRef;

import java.util.List;

/** 仅由发布页面解析生成的任务查询范围，不接受客户端SQL、物理列或任意对象身份。 */
public record TaskQueryScope(
        RecordRef context,
        String applicationId,
        String objectId,
        RecordStatement business,
        boolean filterBusiness,
        List<String> statuses,
        List<String> urgencies,
        List<String> priorities,
        String category,
        List<String> templateIds,
        String sort,
        boolean descending,
        boolean candidates) {
    public static TaskQueryScope empty() {
        return new TaskQueryScope(
                null,
                null,
                null,
                null,
                false,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                "expectedEnd",
                false,
                false);
    }
}
