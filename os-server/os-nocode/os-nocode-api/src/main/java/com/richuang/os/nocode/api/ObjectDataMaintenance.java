package com.richuang.os.nocode.api;

import com.richuang.os.common.dto.DynamicConditionDTO;

import java.util.List;
import java.util.Map;

/** 数据对象维护只接受对象及记录上下文；客户端不能提供应用、流程或任意表名。 */
public final class ObjectDataMaintenance {
    private ObjectDataMaintenance() {}

    public record Model(
            int versionNo,
            String checksum,
            ApplicationRecords.Model model,
            Map<String, String> readonlyReasons,
            String createRestriction,
            Map<String, String> columnTypes) {}

    public record Query(
            String objectId,
            int pageNo,
            int pageSize,
            String search,
            Map<String, Object> equal,
            String sortFieldId,
            boolean descending,
            DynamicConditionDTO conditions,
            List<String> recordIds) {}

    public record Save(
            String objectId,
            int versionNo,
            String checksum,
            String id,
            String expectedRevision,
            Map<String, Object> values,
            String requestKey) {}

    public record Delete(
            String objectId,
            int versionNo,
            String checksum,
            String id,
            String expectedRevision,
            String impactToken) {}

    public record Selection(
            String objectId,
            String fieldId,
            String detailId,
            String search,
            int pageNo,
            int pageSize,
            List<String> selected,
            String recordId,
            Boolean creating) {}

    public record Impact(
            String objectId,
            String objectName,
            String recordId,
            String recordTitle,
            String relationName,
            String action,
            String message) {}

    public record DeletePreview(
            boolean allowed,
            String recordTitle,
            List<Impact> impacts,
            String message,
            String impactToken) {}

    /** 清空整列以当前已发布版本和预检指纹确认；明细列由 detailId 定位。 */
    public record ClearColumn(
            String objectId,
            int versionNo,
            String checksum,
            String detailId,
            String fieldId,
            String impactToken) {}

    public record ClearColumnPreview(
            boolean allowed,
            String objectName,
            String fieldName,
            String detailName,
            String columnType,
            int versionNo,
            String checksum,
            long activeRows,
            long deletedRows,
            List<String> blockers,
            String message,
            String impactToken) {}

    public record ClearColumnResult(long clearedActiveRows, long clearedDeletedRows) {}
}
