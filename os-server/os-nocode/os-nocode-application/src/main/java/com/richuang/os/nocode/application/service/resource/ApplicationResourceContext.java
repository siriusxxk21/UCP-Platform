package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 资源配置解码与资源图公共引用检查；严格 JSON 策略不修改共享配置。 */
@Component
public class ApplicationResourceContext {
    @Resource private ObjectMapper json;

    @PostConstruct
    void initialize() {
        json =
                json.copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public <T> T decode(Map<String, Object> config, Class<T> type) {
        try {
            return json.convertValue(config, type);
        } catch (IllegalArgumentException e) {
            throw invalid("资源配置格式无效：" + type.getSimpleName());
        }
    }

    void identifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw invalid("资源或节点标识无效");
    }

    DataCenter.Definition object(Map<String, DataCenter.Definition> definitions, String id) {
        var d = definitions.get(id);
        if (d == null) throw invalid("资源绑定对象不属于应用");
        return d;
    }

    Map<String, FieldDefinition> fields(DataCenter.Definition d) {
        Map<String, FieldDefinition> result = new LinkedHashMap<>();
        BusinessFields.fields(d).stream()
                .filter(
                        f ->
                                !MemberStateEnum.INACTIVE.matches(
                                        d.fieldOptions()
                                                .getOrDefault(
                                                        f.id(), DataCenter.FieldOptions.defaults())
                                                .state()))
                .forEach(f -> result.put(f.id(), f));
        return result;
    }

    ApplicationCenter.Resource resource(
            Map<String, ApplicationCenter.Resource> resources,
            String id,
            ApplicationResourceKindEnum... kinds) {
        var r = resources.get(id);
        if (r == null || Arrays.stream(kinds).noneMatch(k -> k.matches(r.kind())))
            throw invalid("页面/视图引用的资源不存在或类型不匹配");
        return r;
    }

    /** 在同一应用快照内解析列表表单；显式绑定失效必须报错，不回退到默认表单。 */
    public ApplicationCenter.Resource viewForm(
            Collection<ApplicationCenter.Resource> resources, ApplicationUi.View view) {
        if (view.formId() == null) return defaultForms(resources).get(view.objectId());
        ApplicationCenter.Resource selected =
                resources.stream()
                        .filter(r -> Objects.equals(r.id(), view.formId()))
                        .findFirst()
                        .orElse(null);
        if (selected == null || !ApplicationResourceKindEnum.FORM.matches(selected.kind()))
            throw invalid("列表指定的业务表单不存在或类型不匹配");
        if (!Objects.equals(
                decode(selected.config(), ApplicationUi.Form.class).objectId(), view.objectId()))
            throw invalid("视图表单必须绑定同一对象");
        return selected;
    }

    /** 默认表单唯一性同时用于草稿、发布及运行解析，不生成或改写任何资源。 */
    public Map<String, ApplicationCenter.Resource> defaultForms(
            Collection<ApplicationCenter.Resource> resources) {
        Map<String, ApplicationCenter.Resource> defaults = new LinkedHashMap<>();
        for (ApplicationCenter.Resource resource : resources) {
            if (!ApplicationResourceKindEnum.FORM.matches(resource.kind())) continue;
            ApplicationUi.Form form = decode(resource.config(), ApplicationUi.Form.class);
            if (form.options() == null || !Boolean.TRUE.equals(form.options().defaultForObject()))
                continue;
            if (defaults.putIfAbsent(form.objectId(), resource) != null)
                throw invalid("同一应用中每个对象只能设置一个默认表单");
        }
        return defaults;
    }

    /** 删除默认资源必须同时提供替代，或先明确取消其默认身份；移除整个对象不保留悬空默认。 */
    public void validateDefaultFormRemoval(
            ApplicationCenter.Definition before, ApplicationCenter.Definition after) {
        Map<String, ApplicationCenter.Resource> nextDefaults = defaultForms(after.resources());
        Set<String> nextObjects = new HashSet<>();
        after.objects().forEach(reference -> nextObjects.add(reference.objectId()));
        Map<String, String> nextFormObjects = new HashMap<>();
        after.resources().stream()
                .filter(resource -> ApplicationResourceKindEnum.FORM.matches(resource.kind()))
                .forEach(
                        resource ->
                                nextFormObjects.put(
                                        resource.id(),
                                        decode(resource.config(), ApplicationUi.Form.class)
                                                .objectId()));
        for (Map.Entry<String, ApplicationCenter.Resource> entry :
                defaultForms(before.resources()).entrySet()) {
            if (nextObjects.contains(entry.getKey())
                    && !Objects.equals(nextFormObjects.get(entry.getValue().id()), entry.getKey())
                    && !nextDefaults.containsKey(entry.getKey()))
                throw invalid("请先更换或取消默认表单，再删除：" + entry.getValue().name());
        }
    }

    /** 表单限定视图必须存在且与引用目标对象一致，删除、停用或换绑在保存／发布时被拦截。 */
    void validateSelectionViews(
            ApplicationUi.Form form,
            DataCenter.Definition d,
            Map<String, ApplicationCenter.Resource> resources) {
        for (var entry : SelectionFields.presentations(form.nodes()).entrySet()) {
            var presentation = entry.getValue() == null ? null : entry.getValue().selection();
            if (presentation == null || presentation.viewId() == null) continue;
            var name =
                    BusinessFields.fields(d).stream()
                            .filter(f -> f.id().equals(entry.getKey()))
                            .map(FieldDefinition::name)
                            .findFirst()
                            .orElse(entry.getKey());
            var relation = BusinessFields.relation(d, entry.getKey());
            if (relation == null) throw invalid("限定视图仅用于对象引用字段：" + name);
            var resource = resources.get(presentation.viewId());
            if (resource == null || !ApplicationResourceKindEnum.VIEW.matches(resource.kind()))
                throw invalid("限定视图不存在或已删除：" + name);
            if (!Objects.equals(
                    decode(resource.config(), ApplicationUi.View.class).objectId(),
                    relation.targetObjectId())) throw invalid("限定视图必须与引用目标对象一致：" + name);
        }
    }
}
