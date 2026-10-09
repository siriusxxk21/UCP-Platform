package com.richuang.os.nocode.api.work;

import com.richuang.os.nocode.api.ApplicationRecords;

import java.time.LocalDateTime;
import java.util.*;

/** 工作草稿与不可变整单材料；身份及来源由服务端受控入口确定。 */
public final class WorkDrafts {
    private WorkDrafts() {}

    public record Save(
            String id,
            Integer expectedRevision,
            PublishedResourceRef resource,
            String objectId,
            String recordId,
            String baseRecordRevision,
            Map<String, Object> values,
            Map<String, List<ApplicationRecords.Row>> details,
            Map<String, List<com.richuang.os.nocode.api.RelatedForms.Row>> relatedRecords) {
        public Save {
            details = details == null ? Map.of() : details;
            relatedRecords = relatedRecords == null ? Map.of() : relatedRecords;
        }

        public Save(
                String id,
                Integer expectedRevision,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String baseRecordRevision,
                Map<String, Object> values,
                Map<String, List<ApplicationRecords.Row>> details) {
            this(
                    id,
                    expectedRevision,
                    resource,
                    objectId,
                    recordId,
                    baseRecordRevision,
                    values,
                    details,
                    Map.of());
        }

        public Save(
                String id,
                Integer expectedRevision,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String baseRecordRevision,
                Map<String, Object> values) {
            this(
                    id,
                    expectedRevision,
                    resource,
                    objectId,
                    recordId,
                    baseRecordRevision,
                    values,
                    Map.of());
        }
    }

    public record Draft(
            String id,
            int revision,
            String state,
            PublishedResourceRef resource,
            String objectId,
            String recordId,
            String baseRecordRevision,
            Map<String, Object> values,
            LocalDateTime updatedAt,
            Map<String, List<ApplicationRecords.Row>> details,
            Map<String, List<com.richuang.os.nocode.api.RelatedForms.Row>> relatedRecords) {
        public Draft {
            details = details == null ? Map.of() : details;
            relatedRecords = relatedRecords == null ? Map.of() : relatedRecords;
        }

        public Draft(
                String id,
                int revision,
                String state,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String baseRecordRevision,
                Map<String, Object> values,
                LocalDateTime updatedAt,
                Map<String, List<ApplicationRecords.Row>> details) {
            this(
                    id,
                    revision,
                    state,
                    resource,
                    objectId,
                    recordId,
                    baseRecordRevision,
                    values,
                    updatedAt,
                    details,
                    Map.of());
        }

        public Draft(
                String id,
                int revision,
                String state,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String baseRecordRevision,
                Map<String, Object> values,
                LocalDateTime updatedAt) {
            this(
                    id,
                    revision,
                    state,
                    resource,
                    objectId,
                    recordId,
                    baseRecordRevision,
                    values,
                    updatedAt,
                    Map.of());
        }
    }

    public record Submit(
            String draftId, int expectedRevision, String idempotencyKey, String actionCode) {
        public Submit(String draftId, int expectedRevision, String idempotencyKey) {
            this(draftId, expectedRevision, idempotencyKey, null);
        }
    }

    public record Submission(
            String id,
            String draftId,
            PublishedResourceRef resource,
            String objectId,
            String recordId,
            String recordRevision,
            Map<String, Object> values,
            LocalDateTime submittedAt,
            Map<String, List<ApplicationRecords.Row>> details,
            Map<String, String> displayValues,
            String policyVersion,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    com.richuang.os.nocode.api.BusinessHandling.Material handling) {
        public Submission {
            details = details == null ? Map.of() : details;
            displayValues = displayValues == null ? Map.of() : displayValues;
        }

        public Submission(
                String id,
                String draftId,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String recordRevision,
                Map<String, Object> values,
                LocalDateTime submittedAt,
                Map<String, List<ApplicationRecords.Row>> details,
                Map<String, String> displayValues,
                String policyVersion) {
            this(
                    id,
                    draftId,
                    resource,
                    objectId,
                    recordId,
                    recordRevision,
                    values,
                    submittedAt,
                    details,
                    displayValues,
                    policyVersion,
                    null);
        }

        public Submission(
                String id,
                String draftId,
                PublishedResourceRef resource,
                String objectId,
                String recordId,
                String recordRevision,
                Map<String, Object> values,
                LocalDateTime submittedAt) {
            this(
                    id,
                    draftId,
                    resource,
                    objectId,
                    recordId,
                    recordRevision,
                    values,
                    submittedAt,
                    Map.of(),
                    Map.of(),
                    null);
        }
    }
}
