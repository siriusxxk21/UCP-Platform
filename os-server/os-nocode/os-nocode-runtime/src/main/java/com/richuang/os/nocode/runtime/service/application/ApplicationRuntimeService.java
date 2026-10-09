package com.richuang.os.nocode.runtime.service.application;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationCenter.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;
import com.richuang.os.nocode.runtime.service.view.DataViewService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 运行门户只呈现有权使用的发布对象和资源，不要求成员获得平台设计菜单权限。 */
@Service
public class ApplicationRuntimeService {
    @Resource private DataViewService dataViews;
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resources;
    @Resource private DataObjectApi objects;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ApplicationReportService reports;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.richuang.os.nocode.runtime.service.report
                            .ApplicationDashboardRuntimeService>
            dashboards;

    @Resource private ObjectMapper json;

    public List<Row> mine(long actor) {
        if (actor <= 0) throw invalid("请先登录");
        List<Row> result = new ArrayList<>();
        for (String id : applications.runnableIds()) {
            ApplicationCenter.Published published = applications.published(id);
            if (published.definition().objects().stream()
                    .anyMatch(r -> policy.canRead(id, r.objectId(), actor)))
                result.add(applications.published(id).application());
        }
        return result;
    }

    /** 运行端下发的附加来源：与顶层一样不下发固定条件（置空），其余（含筛选对应、下钻明细视图）原样。 */
    private static List<ApplicationReports.Source> deliveredSources(
            List<ApplicationReports.Source> sources) {
        if (sources == null) return null;
        List<ApplicationReports.Source> delivered = new ArrayList<>();
        for (ApplicationReports.Source s : sources)
            delivered.add(
                    new ApplicationReports.Source(
                            s.id(),
                            s.name(),
                            s.objectId(),
                            s.grain(),
                            s.detailId(),
                            s.dimensions(),
                            s.columnDimensions(),
                            null,
                            s.dateFieldId(),
                            s.filterTargets(),
                            s.detailViewId(),
                            s.detailEditable()));
        return List.copyOf(delivered);
    }

    public Published application(String id, long actor) {
        policy.requireEntry(id, actor);
        ApplicationCenter.Published published = applications.published(id);
        List<String> warnings = compatibilityWarnings(published.definition());
        Map<String, ApplicationAuthorization.Capabilities> readable = new LinkedHashMap<>();
        for (ApplicationCenter.ObjectReference ref : published.definition().objects()) {
            if (!policy.canRead(id, ref.objectId(), actor)) continue;
            DataCenter.Definition d =
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition();
            readable.put(ref.objectId(), policy.access(id, d, actor).forRow(Long.toString(actor)));
        }
        if (readable.isEmpty()) throw invalid("当前发布版本没有可访问对象");
        List<ApplicationCenter.Resource> output = new ArrayList<>();
        for (ApplicationCenter.Resource r : published.definition().resources()) {
            ApplicationResourceKindEnum kind = ApplicationResourceKindEnum.fromCode(r.kind());
            if (kind == ApplicationResourceKindEnum.REPORT_DASHBOARD) {
                com.richuang.os.nocode.runtime.service.report.ApplicationDashboardRuntimeService
                        service = dashboards.getIfAvailable();
                if (service != null && service.available(id, r.id(), actor))
                    output.add(
                            resource(
                                    r,
                                    resources.decode(
                                            r.config(), ApplicationDashboards.Config.class)));
            } else if (kind == ApplicationResourceKindEnum.REPORT) {
                if (reports.available(id, r.id(), actor)) {
                    ApplicationReports.Config c =
                            resources.decode(r.config(), ApplicationReports.Config.class);
                    output.add(
                            resource(
                                    r,
                                    new ApplicationReports.Config(
                                            c.objectId(),
                                            c.dimensions(),
                                            c.metrics(),
                                            Map.of(),
                                            c.filterFieldIds(),
                                            c.dateFieldId(),
                                            c.timeZone(),
                                            c.display(),
                                            c.sortMetricId(),
                                            c.descending(),
                                            c.limit(),
                                            c.detailViewId(),
                                            null,
                                            null,
                                            c.columnDimensions(),
                                            c.pivot(),
                                            c.detailEditable(),
                                            c.sortBy(),
                                            c.grain(),
                                            c.detailId(),
                                            c.sourceName(),
                                            deliveredSources(c.extraSources()),
                                            c.dimensionLabels(),
                                            c.columnDimensionLabels())));
                }
            } else if (kind == ApplicationResourceKindEnum.VIEW) {
                ApplicationUi.View v = resources.decode(r.config(), ApplicationUi.View.class);
                ApplicationAuthorization.Capabilities cap = readable.get(v.objectId());
                if (cap != null)
                    output.add(
                            resource(
                                    r,
                                    new ApplicationUi.View(
                                            v.objectId(),
                                            v.fieldIds().stream()
                                                    .filter(cap.readFields()::contains)
                                                    .toList(),
                                            Map.of(),
                                            v.sortFieldId(),
                                            v.descending(),
                                            v.pageSize(),
                                            v.formId(),
                                            v.filterDictionaries(),
                                            v.detailPageId(),
                                            v.interaction(),
                                            viewList(v.list(), cap.readFields()),
                                            (v.query() == null
                                                            ? ViewQueryOptions.empty()
                                                            : v.query())
                                                    .visible(cap.readFields(), v.equal()),
                                            v.composition() == null
                                                    ? null
                                                    : dataViews
                                                            .model(id, v.objectId(), r.id(), actor)
                                                            .composition())));
            } else if (kind == ApplicationResourceKindEnum.FORM) {
                ApplicationUi.Form f = resources.decode(r.config(), ApplicationUi.Form.class);
                ApplicationAuthorization.Capabilities cap = readable.get(f.objectId());
                if (cap != null)
                    output.add(
                            resource(
                                    r,
                                    new ApplicationUi.Form(
                                            f.objectId(),
                                            nodes(
                                                    f.nodes(),
                                                    Set.of(),
                                                    cap.readFields(),
                                                    cap.readDetails()),
                                            f.detailIds().stream()
                                                    .filter(cap.readDetails()::contains)
                                                    .toList(),
                                            f.options(),
                                            com.richuang.os.nocode.metadata.service.form.DetailForms
                                                    .visible(f, cap.readDetails()),
                                            f.relatedForms())));
            }
        }
        Set<String> validIds = new HashSet<>();
        Set<String> dictionaryIds = new HashSet<>();
        for (ApplicationCenter.Resource resource : output)
            if (ApplicationResourceKindEnum.VIEW.matches(resource.kind())) {
                ApplicationUi.View view =
                        resources.decode(resource.config(), ApplicationUi.View.class);
                if (view.filterDictionaries() != null)
                    dictionaryIds.addAll(view.filterDictionaries().values());
            }
        for (ApplicationCenter.Resource resource : published.definition().resources()) {
            if (ApplicationResourceKindEnum.DICTIONARY.matches(resource.kind())
                    && dictionaryIds.contains(resource.id())) output.add(resource);
            if (ApplicationResourceKindEnum.ACTION.matches(resource.kind())) {
                ApplicationBusiness.Action action =
                        resources.decode(resource.config(), ApplicationBusiness.Action.class);
                if (readable.containsKey(action.objectId())) output.add(resource);
            }
        }
        output.forEach(r -> validIds.add(r.id()));
        // 页面按钮可以引用其他页面和应用内导航，先计算可见资源集合，避免受资源数组顺序影响。
        for (ApplicationCenter.Resource r : published.definition().resources()) {
            if (ApplicationResourceKindEnum.PAGE.matches(r.kind())) {
                ApplicationUi.Page page = resources.decode(r.config(), ApplicationUi.Page.class);
                if (page.contextObjectId() == null || readable.containsKey(page.contextObjectId()))
                    validIds.add(r.id());
            }
        }
        for (ApplicationCenter.Resource r : published.definition().resources())
            if (ApplicationResourceKindEnum.MENU.matches(r.kind())
                    && validIds.contains(
                            resources.decode(r.config(), ApplicationUi.Menu.class).targetId()))
                validIds.add(r.id());
        for (ApplicationCenter.Resource r : published.definition().resources())
            if (ApplicationResourceKindEnum.PAGE.matches(r.kind())) {
                ApplicationUi.Page page = resources.decode(r.config(), ApplicationUi.Page.class);
                if (page.contextObjectId() != null && !readable.containsKey(page.contextObjectId()))
                    continue;
                output.add(
                        resource(
                                r,
                                new ApplicationUi.Page(
                                        pageNodes(page.nodes(), validIds),
                                        page.contextObjectId(),
                                        page.protocolVersion(),
                                        page.filters() == null
                                                ? null
                                                : page.filters().stream()
                                                        .filter(
                                                                f ->
                                                                        readable.containsKey(
                                                                                        f
                                                                                                .objectId())
                                                                                && readable.get(
                                                                                                f
                                                                                                        .objectId())
                                                                                        .readFields()
                                                                                        .contains(
                                                                                                f
                                                                                                        .fieldId()))
                                                        .map(
                                                                f ->
                                                                        new ApplicationReports
                                                                                .Filter(
                                                                                f.id(),
                                                                                f.name(),
                                                                                f.objectId(),
                                                                                f.fieldId(),
                                                                                f.dateRange(),
                                                                                f
                                                                                        .targets()
                                                                                        .entrySet()
                                                                                        .stream()
                                                                                        .filter(
                                                                                                e ->
                                                                                                        validIds
                                                                                                                .contains(
                                                                                                                        e
                                                                                                                                .getKey()))
                                                                                        .collect(
                                                                                                java
                                                                                                        .util
                                                                                                        .stream
                                                                                                        .Collectors
                                                                                                        .toMap(
                                                                                                                Map
                                                                                                                                .Entry
                                                                                                                        ::getKey,
                                                                                                                Map
                                                                                                                                .Entry
                                                                                                                        ::getValue))))
                                                        .filter(f -> !f.targets().isEmpty())
                                                        .toList())));
            }
        output.forEach(r -> validIds.add(r.id()));
        for (ApplicationCenter.Resource r : published.definition().resources())
            if (ApplicationResourceKindEnum.MENU.matches(r.kind())
                    && validIds.contains(
                            resources.decode(r.config(), ApplicationUi.Menu.class).targetId()))
                output.add(r);
        return new Published(
                published.application(),
                published.versionNo(),
                published.checksum(),
                new Definition(
                        published.definition().objects().stream()
                                .filter(r -> readable.containsKey(r.objectId()))
                                .toList(),
                        output),
                warnings);
    }

    /** 固定版本与对象最新结构的差异只提示不阻断，运行入口不因对象调整直接失败。 */
    private List<String> compatibilityWarnings(ApplicationCenter.Definition definition) {
        List<String> warnings = new ArrayList<>();
        for (ApplicationCenter.ObjectReference ref : definition.objects()) {
            DataObjectApi.PublishedObject pinned =
                    objects.getVersion(ref.objectId(), ref.versionNo());
            DataObjectApi.PublishedObject latest = objects.getVersion(ref.objectId(), null);
            if (latest.versionNo() == pinned.versionNo()) continue;
            List<String> changes =
                    ObjectContracts.breakingChanges(pinned.definition(), latest.definition());
            if (changes.isEmpty()) continue;
            warnings.add(
                    "数据对象“"
                            + pinned.definition().objectName()
                            + "”已调整结构（最新 V"
                            + latest.versionNo()
                            + "），本应用仍按固定版本 V"
                            + ref.versionNo()
                            + " 运行："
                            + String.join("；", changes)
                            + "。如遇异常请联系应用管理员同步对象版本并重新发布。");
        }
        return List.copyOf(warnings);
    }

    private ApplicationCenter.Resource resource(ApplicationCenter.Resource source, Object config) {
        return new ApplicationCenter.Resource(
                source.id(),
                source.kind(),
                source.code(),
                source.name(),
                json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private List<ApplicationUi.Node> nodes(
            List<ApplicationUi.Node> nodes,
            Set<String> resources,
            Set<String> fields,
            Set<String> details) {
        List<ApplicationUi.Node> result = new ArrayList<>();
        for (ApplicationUi.Node n : nodes == null ? List.<ApplicationUi.Node>of() : nodes) {
            if (n.fieldId() != null && !fields.contains(n.fieldId())) continue;
            if (n.resourceId() != null && !resources.contains(n.resourceId())) continue;
            if (n.taskView() != null
                    && n.taskView().businessFormId() != null
                    && !resources.contains(n.taskView().businessFormId())) continue;
            if (n.detail() != null && !details.contains(n.detail().detailId())) continue;
            if (n.action() != null
                    && n.action().resourceId() != null
                    && !resources.contains(n.action().resourceId())) continue;
            result.add(
                    new ApplicationUi.Node(
                            n.id(),
                            n.type(),
                            n.fieldId(),
                            n.resourceId(),
                            n.text(),
                            n.span(),
                            nodes(n.children(), resources, fields, details),
                            n.binding(),
                            n.presentation(),
                            n.style(),
                            n.display(),
                            n.action(),
                            n.detail(),
                            n.taskView()));
        }
        return result;
    }

    private List<ApplicationUi.Node> pageNodes(
            List<ApplicationUi.Node> source, Set<String> resources) {
        List<ApplicationUi.Node> projected = nodes(source, resources, Set.of(), Set.of());
        Set<String> ids = new HashSet<>();
        collectNodes(projected, ids);
        return pruneButtons(projected, ids);
    }

    private ApplicationUi.ViewList viewList(ApplicationUi.ViewList list, Set<String> readable) {
        if (list == null) return null;
        return new ApplicationUi.ViewList(
                list.queryFieldIds().stream().filter(readable::contains).toList(),
                list.advancedFieldIds() == null
                        ? null
                        : list.advancedFieldIds().stream().filter(readable::contains).toList(),
                list.columnWidths().entrySet().stream()
                        .filter(e -> readable.contains(e.getKey()))
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue)),
                list.batchDelete(),
                list.overflow());
    }

    private void collectNodes(List<ApplicationUi.Node> source, Set<String> ids) {
        for (ApplicationUi.Node n : source) {
            ids.add(n.id());
            collectNodes(n.children(), ids);
        }
    }

    /** 对象被裁剪后，其按钮也一并裁剪，避免给无权对象留下失效操作入口。 */
    private List<ApplicationUi.Node> pruneButtons(
            List<ApplicationUi.Node> source, Set<String> ids) {
        return source.stream()
                .filter(
                        n ->
                                n.action() == null
                                        || n.action().targetNodeId() == null
                                        || ids.contains(n.action().targetNodeId()))
                .map(
                        n ->
                                new ApplicationUi.Node(
                                        n.id(),
                                        n.type(),
                                        n.fieldId(),
                                        n.resourceId(),
                                        n.text(),
                                        n.span(),
                                        pruneButtons(n.children(), ids),
                                        n.binding(),
                                        n.presentation(),
                                        n.style(),
                                        n.display(),
                                        n.action(),
                                        n.detail(),
                                        n.taskView()))
                .toList();
    }
}
