package com.richuang.os.nocode.metadata.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/** 数据中心内部查询投影；数据库 JSON 在服务边界转换成结构化 API。 */
public final class DataCenterRows {
    private DataCenterRows() {}

    /** 明细写入使用与主表相同的持久化实体和公共字段。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends NocodeObjectTableDO {}

    @Data
    public static class Field {
        private Long stableFieldId;
        private String fieldCode;
        private String columnName;
        private String configJson;
        private String fieldState;
        private Boolean relationGenerated;
        private String dataClassification;
    }

    /** 发布计划持久化实体；执行时间是业务事件，创建时间继承 BaseDO。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName(value = "nocode_publish_plan", schema = "public")
    public static class Plan extends BaseDO {
        @TableId private String id;
        private Long objectId;
        private Long objectVersionId;
        private Integer versionNo;
        private Integer revision;
        private String schemaChecksum;
        private String baselineHash;
        private String planJson;
        private String state;
        private OffsetDateTime executedAt;
        private String reason;
        private String errorMessage;
    }

    @Data
    public static class Deployment {
        private Long objectId;
        private Integer versionNo;
        private String schemaName;
        private String tableName;
        private String structureHash;
        private String structureJson;
        private OffsetDateTime verifiedAt;
    }
}
