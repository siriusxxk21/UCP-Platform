package com.richuang.os.nocode.api;

import com.richuang.os.common.dto.DynamicConditionDTO;

import java.util.List;
import java.util.Map;

/** 多对象视图仅引用已发布对象、关系和字段；根记录与明细行的身份分别保留。 */
public final class DataViews {
    private DataViews() {}

    public record Composition(
            String grain, String detailId, List<Section> sections, List<Column> columns) {}

    /** 内部明细与独立关联对象互斥；展示子表和用子表条件筛选主记录分别配置。 */
    public record Section(
            String id,
            String name,
            String detailId,
            String objectId,
            String viewId,
            ApplicationUi.RelationBinding binding,
            List<String> fieldIds,
            DynamicConditionDTO conditions,
            int pageSize,
            Boolean showTable) {}

    /** 列 ID 是视图内稳定标识；类型由发布校验根据来源推导。 */
    public record Column(
            String id, String name, String sectionId, String fieldId, String kind, String type) {}

    public record ChildFilter(
            String sectionId,
            String search,
            Map<String, Object> equal,
            DynamicConditionDTO conditions,
            boolean requireMatch) {}

    public record ChildQuery(
            String applicationId,
            String objectId,
            String viewId,
            String sectionId,
            String recordId,
            int pageNo,
            int pageSize,
            String search,
            Map<String, Object> equal,
            String sortFieldId,
            boolean descending,
            DynamicConditionDTO conditions) {}

    /** 列与子区块已经过当前字段、对象和关系权限裁剪。 */
    public record Model(
            Composition composition,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> fieldOptions,
            Map<String, SectionModel> sections) {
        public Model(
                Composition composition,
                List<FieldDefinition> fields,
                Map<String, DataCenter.FieldOptions> fieldOptions) {
            this(composition, fields, fieldOptions, Map.of());
        }
    }

    public record SectionModel(
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> fieldOptions,
            ApplicationRecords.Model recordModel) {}
}
