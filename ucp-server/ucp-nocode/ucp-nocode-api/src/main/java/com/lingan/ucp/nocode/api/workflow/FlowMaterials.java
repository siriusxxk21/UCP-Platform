package com.lingan.ucp.nocode.api.workflow;

import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.ApplicationUi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 审批材料专用只读契约；身份、前序路径、资源和字段范围全部由服务端解析。 */
public final class FlowMaterials {
    private FlowMaterials() {}

    public record Query(String processInstanceId, String taskId) {}

    public record Get(String processInstanceId, String taskId, String materialId) {}

    public record Item(
            String id,
            String taskId,
            String nodeId,
            String nodeName,
            String formName,
            String submitterName,
            LocalDateTime submittedAt,
            int revision,
            String kind,
            String state,
            boolean required,
            String warning) {}

    public record Page(
            List<Item> items, String reviewToken, boolean reviewRequired, String blockedReason) {}

    public record Detail(Item item, FlowForm flowForm, BusinessForm businessForm) {}

    public record FlowForm(String conf, List<String> fields, Map<String, Object> values) {}

    public record BusinessForm(
            String applicationId,
            String recordId,
            ApplicationUi.Form form,
            ApplicationRecords.Model model,
            Map<String, Object> values,
            Map<String, String> displayValues) {}
}
