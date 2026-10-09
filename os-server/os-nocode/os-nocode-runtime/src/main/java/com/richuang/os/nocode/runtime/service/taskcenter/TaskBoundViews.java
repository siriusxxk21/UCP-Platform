package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 任务办理绑定普通主记录视图；固定范围随发布快照保留，并与当前范围相交。 */
@Component
public class TaskBoundViews {
    @Resource private ApplicationPublishedService published;
    @Resource private ObjectMapper json;

    public ApplicationUi.View view(
            Binding binding, ApplicationCenter.Published release, String objectId) {
        if (binding == null || binding.viewId() == null) return null;
        ApplicationCenter.Resource resource =
                release.definition().resources().stream()
                        .filter(r -> binding.viewId().equals(r.id()) && "VIEW".equals(r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务绑定的业务视图不存在或已撤下"));
        ApplicationUi.View view = json.convertValue(resource.config(), ApplicationUi.View.class);
        if (!Objects.equals(view.objectId(), objectId)
                || !Objects.equals(view.formId(), binding.formId()))
            throw invalid("业务视图与办理表单不匹配，请重新选择视图");
        if (view.composition() != null) throw invalid("请选择普通主记录视图，复合或聚合视图不能作为办理入口");
        ApplicationCenter.Resource formResource =
                release.definition().resources().stream()
                        .filter(r -> binding.formId().equals(r.id()) && "FORM".equals(r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务办理表单已撤下"));
        ApplicationUi.Form form =
                json.convertValue(formResource.config(), ApplicationUi.Form.class);
        if (form.relatedForms() != null && !form.relatedForms().isEmpty())
            throw invalid("视图办理暂不支持跨对象联合录入表单，请把关联对象配置为独立办理项");
        return view;
    }

    public DataScope scope(ApplicationUi.View view, DataCenter.Definition definition) {
        if (view == null) return null;
        List<DataScope.Condition> equal = new ArrayList<>();
        if (view.equal() != null)
            view.equal()
                    .forEach(
                            (key, value) ->
                                    equal.add(
                                            new DataScope.Condition(
                                                    key, value == null ? "isNull" : "eq", value)));
        DataScope scope = equal.isEmpty() ? null : new DataScope("AND", equal, List.of());
        if (view.query() != null) scope = DataScope.and(scope, view.query().scope());
        if (dynamic(scope)) throw invalid("任务办理暂不支持当前用户或部门等动态视图范围，请使用固定条件视图");
        if (scope != null) scope.validateEffective(definition);
        return scope;
    }

    private boolean dynamic(DataScope scope) {
        return scope != null
                && (scope.conditions().stream()
                                .anyMatch(
                                        c ->
                                                c.valueSource() != null
                                                        && !"CONSTANT".equals(c.valueSource()))
                        || scope.groups().stream().anyMatch(this::dynamic));
    }

    public DataScope scope(Binding binding, BusinessRef ref, DataCenter.Definition definition) {
        if (binding == null || binding.viewId() == null) return null;
        ApplicationCenter.Published frozen =
                published.getVersion(binding.applicationId(), ref.resource().applicationVersion());
        ApplicationCenter.Published current = published.getCurrent(binding.applicationId());
        return DataScope.and(
                scope(view(binding, frozen, ref.object().objectId()), definition),
                scope(view(binding, current, ref.object().objectId()), definition));
    }

    public void requireRecord(
            Binding binding,
            BusinessRef ref,
            ApplicationRecords.Aggregate record,
            DataCenter.Definition definition) {
        DataScope limit = scope(binding, ref, definition);
        if (limit != null
                && record != null
                && !limit.matches(definition, record.record().values(), Map.of()))
            throw invalid("此记录不在当前办理视图的数据范围内");
    }

    /** 表单控制办理字段，视图控制列表列；二者都不能恢复已撤销的应用字段权限。 */
    public Set<String> readable(
            Binding binding, ApplicationCenter.Published release, String objectId) {
        ApplicationUi.View view = view(binding, release, objectId);
        if (view == null) return null;
        Set<String> fields = new HashSet<>(view.fieldIds());
        fields.addAll(formFields(binding, release, false));
        return fields;
    }

    public Set<String> writable(Binding binding, ApplicationCenter.Published release) {
        return binding == null || binding.viewId() == null
                ? null
                : formFields(binding, release, true);
    }

    private Set<String> formFields(
            Binding binding, ApplicationCenter.Published release, boolean write) {
        return formParts(binding, release, write, "FIELD");
    }

    public Set<String> details(
            Binding binding, ApplicationCenter.Published release, boolean write) {
        return formParts(binding, release, write, "DETAIL");
    }

    public Set<String> relations(
            Binding binding, ApplicationCenter.Published release, boolean write) {
        return formParts(binding, release, write, "RELATION");
    }

    private Set<String> formParts(
            Binding binding, ApplicationCenter.Published release, boolean write, String part) {
        ApplicationCenter.Resource resource =
                release.definition().resources().stream()
                        .filter(r -> binding.formId().equals(r.id()) && "FORM".equals(r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务办理表单已撤下"));
        ApplicationUi.Form form = json.convertValue(resource.config(), ApplicationUi.Form.class);
        Set<String> fields = new HashSet<>();
        if (!write || form.options() == null || !Boolean.TRUE.equals(form.options().readOnly()))
            collect(form.nodes(), fields, write, part);
        return fields;
    }

    private void collect(
            List<ApplicationUi.Node> nodes, Set<String> fields, boolean write, String part) {
        if (nodes == null) return;
        for (ApplicationUi.Node node : nodes) {
            if (!write
                    || node.presentation() == null
                    || !Boolean.TRUE.equals(node.presentation().readOnly())) {
                if ("FIELD".equals(part) && node.fieldId() != null) fields.add(node.fieldId());
                if ("DETAIL".equals(part) && node.detail() != null)
                    fields.add(node.detail().detailId());
                if ("RELATION".equals(part) && node.binding() != null)
                    fields.add(node.binding().relationId());
                collect(node.children(), fields, write, part);
            }
        }
    }

    public ApplicationAuthorization.Capabilities capabilities(
            Binding binding, BusinessRef ref, ApplicationAuthorization.Capabilities source) {
        if (binding == null || binding.viewId() == null || source == null) return source;
        ApplicationCenter.Published frozen =
                published.getVersion(binding.applicationId(), ref.resource().applicationVersion());
        ApplicationCenter.Published current = published.getCurrent(binding.applicationId());
        Set<String> read = new HashSet<>(source.readFields());
        read.retainAll(readable(binding, frozen, ref.object().objectId()));
        read.retainAll(readable(binding, current, ref.object().objectId()));
        Set<String> write = new HashSet<>(source.writeFields());
        write.retainAll(writable(binding, frozen));
        write.retainAll(writable(binding, current));
        write.retainAll(read);
        Set<String> actions = new HashSet<>(source.actions());
        if (write.isEmpty()) {
            actions.remove("CREATE");
            actions.remove("UPDATE");
        }
        return new ApplicationAuthorization.Capabilities(
                actions,
                read,
                write,
                intersect(
                        source.readDetails(),
                        details(binding, frozen, false),
                        details(binding, current, false)),
                intersect(
                        source.writeDetails(),
                        details(binding, frozen, true),
                        details(binding, current, true)),
                intersect(
                        source.readRelations(),
                        relations(binding, frozen, false),
                        relations(binding, current, false)),
                intersect(
                        source.writeRelations(),
                        relations(binding, frozen, true),
                        relations(binding, current, true)));
    }

    private Set<String> intersect(Set<String> source, Set<String> frozen, Set<String> current) {
        Set<String> result = new HashSet<>(source);
        result.retainAll(frozen);
        result.retainAll(current);
        return result;
    }

    public ApplicationRecords.Aggregate project(
            Binding binding, BusinessRef ref, ApplicationRecords.Aggregate record) {
        if (record == null || binding == null || binding.viewId() == null) return record;
        ApplicationRecords.Row main = project(binding, ref, record.record());
        Map<String, List<ApplicationRecords.Row>> details = new LinkedHashMap<>(record.details());
        details.keySet().retainAll(main.permissions().readDetails());
        Map<String, List<String>> relations = new LinkedHashMap<>(record.relations());
        relations.keySet().retainAll(main.permissions().readRelations());
        return new ApplicationRecords.Aggregate(main, details, record.processes(), relations);
    }

    public ApplicationRecords.Row project(
            Binding binding, BusinessRef ref, ApplicationRecords.Row record) {
        if (record == null || binding == null || binding.viewId() == null) return record;
        ApplicationAuthorization.Capabilities caps =
                capabilities(binding, ref, record.permissions());
        Map<String, Object> values = new LinkedHashMap<>(record.values());
        values.keySet().retainAll(caps.readFields());
        Map<String, String> displays = new LinkedHashMap<>(record.displayValues());
        displays.keySet().retainAll(caps.readFields());
        return new ApplicationRecords.Row(record.id(), record.revision(), values, caps, displays);
    }
}
