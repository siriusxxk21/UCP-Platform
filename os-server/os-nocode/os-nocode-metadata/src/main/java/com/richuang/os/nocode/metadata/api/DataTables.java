package com.richuang.os.nocode.metadata.api;

import com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadata;
import com.richuang.os.nocode.api.DataCenter.Check;

import java.time.OffsetDateTime;
import java.util.*;

/** 物理表目录、纳管预检和受控预览的契约；复用底座结构事实类型。 */
public final class DataTables {
    private DataTables() {}

    /** 目录展示行，包括所属对象、容量及结构核验状态。 */
    public record TableRow(
            String schemaName,
            String tableName,
            String comment,
            String kind,
            String management,
            String role,
            boolean system,
            String objectId,
            String objectName,
            Integer publishedVersion,
            long estimatedRows,
            long totalBytes,
            String structureState,
            OffsetDateTime verifiedAt) {}

    /** 物理事实与当前对象字段映射。 */
    public record TableDetail(
            TableRow table,
            DatabaseMetadata.Table structure,
            String fingerprint,
            Map<String, String> fieldMapping,
            List<Check> checks) {}

    /** 纳管前的结构指纹、准入检查和可选标题列。 */
    public record Preflight(
            String schemaName,
            String tableName,
            String fingerprint,
            boolean allowed,
            boolean readOnly,
            List<Check> checks,
            List<String> titleColumns,
            DatabaseMetadata.Table structure) {}

    /** 受分页及敏感列遮蔽约束的物理数据预览。 */
    public record Preview(
            List<String> columns,
            List<Map<String, Object>> rows,
            boolean hasMore,
            List<String> maskedColumns) {}
}
