package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.enums.RelationDirectionEnum;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 设计校验和运行查询共用关系解析，页面只引用已有关系，不产生第二套关系定义。 */
@Component
public class ApplicationPageBindings {
    public record Resolved(
            RelationDirectionEnum direction,
            DataCenter.Definition source,
            DataCenter.Relation relation,
            DataCenter.Definition current,
            DataCenter.Definition target) {}

    public Resolved resolve(
            String currentId,
            String targetId,
            ApplicationUi.RelationBinding binding,
            Function<String, DataCenter.Definition> definitions) {
        if (currentId == null || binding == null) throw invalid("关联列表需要页面当前对象和关系绑定");
        var direction = RelationDirectionEnum.fromCode(binding.direction());
        var current = definitions.apply(currentId);
        var target = definitions.apply(targetId);
        if (current == null || target == null) throw invalid("关联列表的对象不属于应用");
        var source = direction == RelationDirectionEnum.INCOMING ? target : current;
        var expectedTarget = direction == RelationDirectionEnum.INCOMING ? currentId : targetId;
        var relation =
                source.relations().stream()
                        .filter(r -> Objects.equals(r.id(), binding.relationId()))
                        .filter(r -> Objects.equals(r.targetObjectId(), expectedTarget))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联列表绑定的关系或方向不匹配"));
        return new Resolved(direction, source, relation, current, target);
    }

    public ApplicationUi.Node node(List<ApplicationUi.Node> nodes, String id) {
        if (nodes != null)
            for (var node : nodes) {
                if (Objects.equals(node.id(), id)) return node;
                var found = node(node.children(), id);
                if (found != null) return found;
            }
        return null;
    }
}
