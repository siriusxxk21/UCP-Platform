package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Map;

/** 管理员有序计算校准契约；位置由服务器保存，客户端只提交固定版本与批次标识。 */
public final class OrderedCalculationCalibration {
    private OrderedCalculationCalibration() {}

    public record PreviewRequest(
            String objectId, int versionNo, String checksum, List<String> fieldIds) {}

    public record FieldPreview(
            String fieldId,
            String signature,
            String state,
            long groups,
            long rows,
            long nullRows,
            long changedRows,
            long fillRows,
            long incorrectRows,
            long validNullRows) {}

    public record Preview(
            String objectId, int versionNo, String checksum, List<FieldPreview> fields) {}

    /** maxGroups 限制本次请求推进的组数；重复请求依靠持久进度续接，不重放已完成组。 */
    public record Command(
            String objectId,
            int versionNo,
            String checksum,
            List<String> fieldIds,
            Map<String, String> signatures,
            String requestId,
            int maxGroups) {}

    public record Result(
            String requestId, boolean complete, List<OrderedCalculations.State> states) {}
}
