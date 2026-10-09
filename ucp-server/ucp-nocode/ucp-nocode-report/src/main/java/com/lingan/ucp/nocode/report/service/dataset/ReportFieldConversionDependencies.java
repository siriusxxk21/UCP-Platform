package com.lingan.ucp.nocode.report.service.dataset;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.FieldConversionCompatibility.Field;
import com.lingan.ucp.nocode.enums.DependencyKindEnum;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 接入既有字段转换预检及发布复核；草稿、固定版本及授权条件均按登记的字段保护。 */
@Component
public class ReportFieldConversionDependencies implements FieldConversionDependencyInspector {
    @Resource private DataCenterMapper store;

    @Override
    public List<Impact> inspect(
            DataCenter.Definition previous, DataCenter.Definition proposed, Set<String> fieldIds) {
        return inspect(previous, proposed, fieldIds, Set.of());
    }

    @Override
    public List<Impact> inspect(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Set<String> clearedFieldIds) {
        Map<String, Field> before = FieldConversionCompatibility.fields(previous);
        Map<String, Field> after = FieldConversionCompatibility.fields(proposed);
        List<Impact> result = new ArrayList<>();
        for (DataCenter.Dependency dependency :
                store.dependencies(Long.parseLong(proposed.objectId()))) {
            if (!DependencyKindEnum.DATASET.matches(dependency.sourceKind())) continue;
            for (String fieldId : fieldIds) {
                if (dependency.fieldIds().contains(fieldId)
                        && (clearedFieldIds.contains(fieldId)
                                || !ReportReadCompatibility.field(
                                        before.get(fieldId), after.get(fieldId)))) {
                    result.add(
                            new Impact(
                                    fieldId,
                                    SourceKind.DATASET.name(),
                                    dependency.sourceKey(),
                                    dependency.sourceName(),
                                    "报表中心 / " + dependency.sourceName(),
                                    "数据集或授权条件仍依赖此字段的原值类型，请先调整引用",
                                    null,
                                    true));
                }
            }
        }
        return List.copyOf(result);
    }
}
