package com.lingan.ucp.nocode.report.service.dataset;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 发布上限与依赖登记共用字段需求，包含关联连接键，避免只校验最终展示字段。 */
@Component
public class ReportDatasetUsage {
    @Resource private DataObjectApi objects;

    public Map<String, Set<String>> fields(ReportDatasets.ResolvedSource resolved) {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        for (ReportDatasets.ObjectReference reference : resolved.objects()) {
            fields.put(reference.objectId(), new TreeSet<>());
            definitions.put(
                    reference.objectId(),
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition());
        }
        resolved.fields().forEach(field -> fields.get(field.objectId()).add(field.sourceFieldId()));
        Map<String, String> paths = new HashMap<>();
        paths.put("", resolved.source().root().objectId());
        for (ReportDatasets.Relation reference :
                resolved.source().relations().stream()
                        .sorted(Comparator.comparingInt(relation -> relation.parentPath().size()))
                        .toList()) {
            String parentPath = String.join("/", reference.parentPath());
            String owner = paths.get(parentPath);
            DataCenter.Relation relation =
                    definitions.get(owner).relations().stream()
                            .filter(item -> item.id().equals(reference.relationId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("数据集关系依赖已失效"));
            fields.get(owner).add(relation.fieldId());
            if (relation.targetFieldId() != null && !relation.targetFieldId().isBlank())
                fields.get(reference.target().objectId()).add(relation.targetFieldId());
            paths.put(
                    parentPath.isEmpty() ? reference.id() : parentPath + "/" + reference.id(),
                    reference.target().objectId());
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        fields.forEach((id, values) -> result.put(id, Set.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }
}
