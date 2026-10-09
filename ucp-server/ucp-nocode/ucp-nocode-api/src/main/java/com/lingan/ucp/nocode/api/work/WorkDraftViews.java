package com.lingan.ucp.nocode.api.work;

import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.ApplicationUi;
import com.lingan.ucp.nocode.api.SelectionFields;

import java.time.LocalDateTime;
import java.util.List;

/** 个人草稿工作区的读取契约；游标仅分页，不代表访问授权。 */
public final class WorkDraftViews {
    private WorkDraftViews() {}

    // 游标必须保留 PostgreSQL 微秒精度，不使用底座面向展示的毫秒时间序列化。
    public record Cursor(String createdAt, String id) {}

    public record Query(String applicationId, String state, Cursor before, int limit) {}

    /** 来源工作区共用分页形状，来源种类由服务方法固定，不接受浏览器切换。 */
    public record SourceQuery(String state, Cursor before, int limit) {}

    /** 内部列表扫描只返回身份，不将未经实时授权的值交给前端。 */
    public record Candidate(String id, LocalDateTime createdAt) {}

    /** 内部来源扫描；来源标识由数据库读取，不能直接作为前端访问凭据。 */
    public record SourceCandidate(String id, String sourceId, LocalDateTime createdAt) {}

    public record Item(
            String id,
            String state,
            String formName,
            String objectName,
            int applicationVersion,
            String recordId,
            LocalDateTime updatedAt) {}

    /** 不提供权限过滤前的总数；可能有空页，before 非空时可继续读取。 */
    public record Page(List<Item> items, Cursor before) {}

    public record Context(
            WorkDrafts.Draft draft,
            WorkDrafts.Submission submission,
            String formName,
            ApplicationUi.Form form,
            ApplicationRecords.Model model,
            ApplicationRecords.Row currentRecord,
            boolean writable,
            String blockedReason,
            boolean recordChanged,
            java.util.Map<String, List<ApplicationRecords.Row>> currentDetails) {
        public Context {
            currentDetails = currentDetails == null ? java.util.Map.of() : currentDetails;
        }

        public Context(
                WorkDrafts.Draft draft,
                WorkDrafts.Submission submission,
                String formName,
                ApplicationUi.Form form,
                ApplicationRecords.Model model,
                ApplicationRecords.Row currentRecord,
                boolean writable,
                String blockedReason,
                boolean recordChanged) {
            this(
                    draft,
                    submission,
                    formName,
                    form,
                    model,
                    currentRecord,
                    writable,
                    blockedReason,
                    recordChanged,
                    java.util.Map.of());
        }
    }

    /** 来源必须是服务端已保存的本人草稿；查询不能改变所属应用、对象或表单。 */
    public record Selection(String draftId, SelectionFields.Query query) {}
}
