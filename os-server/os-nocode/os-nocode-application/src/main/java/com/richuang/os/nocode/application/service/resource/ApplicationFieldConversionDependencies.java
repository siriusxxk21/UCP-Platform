package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.FieldConversionCompatibility.Field;
import com.richuang.os.nocode.application.dal.dataobject.*;
import com.richuang.os.nocode.application.dal.mapper.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.ObjectFieldConversionDependencies;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 只检查已生效资源的具体字段用法；应用引用对象或只展示字段本身不产生阻断。 */
@Component
public class ApplicationFieldConversionDependencies implements FieldConversionDependencyInspector {
    @Resource private ApplicationMapper applications;
    @Resource private ApplicationAccessMapper access;
    @Resource private TaskEntryAccessMapper entryAccess;
    @Resource private ObjectApplicationGrantMapper sharing;
    @Resource private DataObjectApi objects;
    @Resource private ObjectMapper json;

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
        return inspectRaw(previous, proposed, fieldIds, clearedFieldIds).stream()
                .map(
                        impact ->
                                new Impact(
                                        impact.fieldId(),
                                        impact.sourceKind(),
                                        impact.sourceId(),
                                        impact.sourceName(),
                                        impact.location(),
                                        impact.message() + "；可先保存草稿，发布时检查是否需要暂停此应用",
                                        impact.route(),
                                        false))
                .toList();
    }

    /** 维护计划读取原始不兼容项，按应用汇总后再决定是否能暂停；不丢失资源位置。 */
    public List<Impact> inspectRaw(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Set<String> clearedFieldIds) {
        List<Impact> result = new ArrayList<>();
        for (String appId : applications.runnableIds()) {
            NocodeApplicationDO app = applications.selectById(Long.parseLong(appId));
            if (app == null || app.getPublishedVersion() == null) continue;
            NocodeApplicationVersionDO version =
                    applications.version(app.getId(), app.getPublishedVersion());
            if (version == null) throw invalid("应用发布版本不存在，无法核对字段转换依赖：" + app.getAppName());
            try {
                ApplicationCenter.Snapshot snapshot =
                        json.readValue(
                                version.getDefinitionJson(), ApplicationCenter.Snapshot.class);
                Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
                for (ApplicationCenter.ObjectReference ref : snapshot.definition().objects())
                    definitions.put(
                            ref.objectId(),
                            objects.getVersion(ref.objectId(), ref.versionNo()).definition());
                if (!definitions.containsKey(proposed.objectId())) continue;
                result.addAll(
                        inspectDefinition(
                                json,
                                appId,
                                snapshot.name(),
                                snapshot.definition(),
                                previous,
                                proposed,
                                fieldIds,
                                definitions,
                                clearedFieldIds));
                Scanner scanner =
                        new Scanner(
                                json,
                                appId,
                                snapshot.name(),
                                previous,
                                proposed,
                                fieldIds,
                                definitions,
                                result,
                                clearedFieldIds);
                NocodeApplicationAccessDO policy = access.policy(app.getId());
                if (policy != null) {
                    List<ApplicationAuthorization.Member> members =
                            json.readValue(
                                    policy.getPolicyJson(),
                                    new TypeReference<List<ApplicationAuthorization.Member>>() {});
                    for (ApplicationAuthorization.Member member : members)
                        scanner.liveGrants(
                                member.objects(),
                                "成员授权 / " + member.principalKind() + " " + member.principalId());
                }
                for (NocodeObjectApplicationGrantDO grant : sharing.forApplication(app.getId())) {
                    ApplicationAuthorization.ObjectGrant permission =
                            json.readValue(
                                    grant.getGrantJson(),
                                    ApplicationAuthorization.ObjectGrant.class);
                    if (permission != null)
                        scanner.liveGrants(
                                List.of(permission), "对象共享授权 / " + permission.objectId());
                }
                for (ApplicationCenter.Resource resource : snapshot.definition().resources()) {
                    if (!ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind())) continue;
                    TaskEntryAccessDO policyRow = entryAccess.find(app.getId(), resource.id());
                    if (policyRow == null || !Boolean.TRUE.equals(policyRow.getEnabled())) continue;
                    List<ApplicationAuthorization.Member> members =
                            json.readValue(
                                    policyRow.getPolicyJson(),
                                    new TypeReference<List<ApplicationAuthorization.Member>>() {});
                    for (ApplicationAuthorization.Member member : members)
                        scanner.liveGrants(
                                member.objects(),
                                "业务入口 / "
                                        + resource.name()
                                        + " / 成员授权 / "
                                        + member.principalKind()
                                        + " "
                                        + member.principalId());
                }
            } catch (java.io.IOException exception) {
                throw invalid("应用配置无法读取，无法核对字段转换依赖：" + app.getAppName());
            }
        }
        return result.stream().distinct().toList();
    }

    /** 结构化配置检查入口；不搜索 JSON 字符串中的偶然同名值。 */
    public static List<Impact> inspectDefinition(
            ObjectMapper json,
            String appId,
            String appName,
            ApplicationCenter.Definition application,
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Map<String, DataCenter.Definition> definitions) {
        return inspectDefinition(
                json,
                appId,
                appName,
                application,
                previous,
                proposed,
                fieldIds,
                definitions,
                Set.of());
    }

    public static List<Impact> inspectDefinition(
            ObjectMapper json,
            String appId,
            String appName,
            ApplicationCenter.Definition application,
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Map<String, DataCenter.Definition> definitions,
            Set<String> clearedFieldIds) {
        List<Impact> result = new ArrayList<>();
        Scanner scanner =
                new Scanner(
                        json,
                        appId,
                        appName,
                        previous,
                        proposed,
                        fieldIds,
                        definitions,
                        result,
                        clearedFieldIds);
        for (ApplicationCenter.Resource resource : application.resources())
            scanner.resources.put(resource.id(), resource);
        for (ApplicationCenter.Resource resource : application.resources())
            scanner.resource(resource);
        return result.stream().distinct().toList();
    }

    private static final class Scanner {
        private final ObjectMapper json;
        private final String appId;
        private final String appName;
        private final DataCenter.Definition proposed;
        private final Map<String, DataCenter.Definition> definitions;
        private final Map<String, Field> before;
        private final Map<String, Field> after;
        private final Set<String> fields;
        private final Set<String> clearedFields;
        private final List<Impact> result;
        private final Map<String, ApplicationCenter.Resource> resources = new HashMap<>();
        private boolean liveAuthorization;

        private Scanner(
                ObjectMapper json,
                String appId,
                String appName,
                DataCenter.Definition previous,
                DataCenter.Definition proposed,
                Set<String> fields,
                Map<String, DataCenter.Definition> definitions,
                List<Impact> result,
                Set<String> clearedFields) {
            this.json = json;
            this.appId = appId;
            this.appName = appName;
            this.proposed = proposed;
            this.fields = fields;
            this.clearedFields = clearedFields;
            this.definitions = definitions;
            this.result = result;
            this.before =
                    FieldConversionCompatibility.fields(
                            definitions.getOrDefault(proposed.objectId(), previous));
            this.after = FieldConversionCompatibility.fields(proposed);
        }

        private void resource(ApplicationCenter.Resource resource) {
            String location = resource.kind() + " / " + resource.name();
            switch (ApplicationResourceKindEnum.fromCode(resource.kind())) {
                case FORM -> form(decode(resource, ApplicationUi.Form.class), location);
                case VIEW -> view(decode(resource, ApplicationUi.View.class), location);
                case REPORT -> report(decode(resource, ApplicationReports.Config.class), location);
                case REPORT_DASHBOARD -> {
                    ApplicationDashboards.Config dashboard =
                            decode(resource, ApplicationDashboards.Config.class);
                    for (ApplicationDashboards.InputBinding input : list(dashboard.inputBindings()))
                        if (ApplicationDashboardInputSourceEnum.RECORD_FIELD.matches(
                                input.source()))
                            use(
                                    dashboard.contextObjectId(),
                                    input.fieldId(),
                                    location + " / 看板记录输入 " + input.filterId(),
                                    false);
                }
                case ACTION -> action(decode(resource, ApplicationBusiness.Action.class), location);
                case AUTOMATION ->
                        automation(decode(resource, ApplicationAutomations.Config.class), location);
                case NUMBER_RULE -> {
                    ApplicationBusiness.NumberRule rule =
                            decode(resource, ApplicationBusiness.NumberRule.class);
                    use(rule.objectId(), rule.fieldId(), location + " / 自动编号写入", true);
                    if (Objects.equals(rule.objectId(), proposed.objectId())
                            && fields.contains(rule.fieldId())) {
                        Field next = after.get(rule.fieldId());
                        if (next == null
                                || !FieldTypeEnum.TEXT.matches(next.definition().type())
                                || Boolean.TRUE.equals(next.options().generated()))
                            blocked(rule.fieldId(), location + " / 编号要求可写单行文本", true);
                    }
                }
                case TASK_ENTRY ->
                        grants(
                                decode(resource, TaskEntries.Config.class).limits(),
                                location + " / 入口范围");
                case PAGE -> {
                    ApplicationUi.Page page = decode(resource, ApplicationUi.Page.class);
                    taskViews(page.nodes(), location);
                    for (ApplicationReports.Filter filter : list(page.filters()))
                        if (filter.dateRange())
                            use(
                                    filter.objectId(),
                                    filter.fieldId(),
                                    location + " / 日期筛选 " + filter.name(),
                                    false);
                }
                default -> {}
            }
        }

        private <T> T decode(ApplicationCenter.Resource resource, Class<T> type) {
            return json.convertValue(resource.config(), type);
        }

        /** 任务业务视图的固定条件和排序也参与字段变更影响分析，避免绕过数据中心兼容检查。 */
        private void taskViews(List<ApplicationUi.Node> nodes, String location) {
            for (ApplicationUi.Node node : list(nodes)) {
                ApplicationUi.TaskView view = node.taskView();
                if (view != null) {
                    String formId =
                            view.businessFormId() == null
                                    ? node.resourceId()
                                    : view.businessFormId();
                    ApplicationCenter.Resource resource = resources.get(formId);
                    if (resource != null
                            && ApplicationResourceKindEnum.FORM.matches(resource.kind())) {
                        String object = decode(resource, ApplicationUi.Form.class).objectId();
                        dynamic(object, view.conditions(), location + " / 任务固定条件 " + node.id());
                        if (view.sort() != null
                                && view.sort().field() != null
                                && view.sort().field().startsWith("business:"))
                            use(
                                    object,
                                    view.sort().field().substring(9),
                                    location + " / 任务排序 " + node.id(),
                                    false);
                    }
                }
                taskViews(node.children(), location);
            }
        }

        private void form(ApplicationUi.Form form, String location) {
            boolean readOnly =
                    form.options() != null && Boolean.TRUE.equals(form.options().readOnly());
            nodes(form.objectId(), form.nodes(), location + " / 主表", readOnly);
            if (form.detailNodes() != null)
                form.detailNodes()
                        .forEach(
                                (detail, nodes) ->
                                        nodes(
                                                form.objectId(),
                                                nodes,
                                                location + " / 明细 " + detail,
                                                readOnly));
        }

        private void nodes(
                String object, List<ApplicationUi.Node> nodes, String location, boolean readOnly) {
            for (ApplicationUi.Node node : list(nodes)) {
                String where = location + " / 节点 " + node.id();
                ApplicationUi.FieldPresentation p = node.presentation();
                if (!readOnly && (p == null || !Boolean.TRUE.equals(p.readOnly())))
                    use(object, node.fieldId(), where + " / 字段写入", true);
                if (p != null) {
                    if (p.behavior() != null) {
                        expression(object, p.behavior().showWhen(), where + " / 显隐条件");
                        expression(object, p.behavior().requiredWhen(), where + " / 必填条件");
                        expression(object, p.behavior().readOnlyWhen(), where + " / 只读条件");
                    }
                    if (p.selection() != null) {
                        SelectionFields.Presentation selection = p.selection();
                        if (selection.defaultValue() != null
                                || !list(selection.rootIds()).isEmpty())
                            use(object, node.fieldId(), where + " / 选择默认值或候选范围", false);
                        use(object, selection.linkFieldId(), where + " / 联动来源", false);
                        String target = relationTarget(object, node.fieldId());
                        use(target, selection.linkTargetFieldId(), where + " / 联动匹配字段", false);
                        link(
                                object,
                                selection.linkFieldId(),
                                target,
                                selection.linkTargetFieldId(),
                                where + " / 联动两侧类型",
                                false);
                    }
                    if (p.fill() != null) {
                        use(object, p.fill().sourceFieldId(), where + " / 关联带入来源", false);
                        use(object, node.fieldId(), where + " / 关联带入目标", true);
                        use(
                                relationTarget(object, p.fill().sourceFieldId()),
                                p.fill().valueFieldId(),
                                where + " / 关联带入取值",
                                false);
                        link(
                                relationTarget(object, p.fill().sourceFieldId()),
                                p.fill().valueFieldId(),
                                object,
                                node.fieldId(),
                                where + " / 带入两侧类型",
                                false);
                    }
                }
                nodes(object, node.children(), where, readOnly);
            }
        }

        private String relationTarget(String object, String field) {
            DataCenter.Definition owner = definitions.get(object);
            if (owner == null) return null;
            return owner.relations().stream()
                    .filter(r -> Objects.equals(field, r.fieldId()))
                    .map(DataCenter.Relation::targetObjectId)
                    .findFirst()
                    .orElse(null);
        }

        private void view(ApplicationUi.View view, String location) {
            for (String field : map(view.equal()).keySet())
                use(view.objectId(), field, location + " / 固定筛选", false);
            for (String field : map(view.filterDictionaries()).keySet())
                use(view.objectId(), field, location + " / 筛选字典", false);
            if (view.query() != null) {
                scope(view.objectId(), view.query().scope(), location + " / 固定范围");
                for (String field : view.query().defaults().keySet())
                    use(view.objectId(), field, location + " / 默认查询", false);
                for (String field : view.query().candidates().keySet())
                    use(view.objectId(), field, location + " / 候选范围", false);
            }
            if (view.composition() != null) {
                for (DataViews.Section section : list(view.composition().sections()))
                    dynamic(
                            section.objectId() == null ? view.objectId() : section.objectId(),
                            section.conditions(),
                            location + " / 子表 " + section.name());
            }
        }

        private void report(ApplicationReports.Config report, String location) {
            for (ApplicationReports.Dimension dimension : list(report.dimensions()))
                reportDimension(report.objectId(), dimension, location);
            for (ApplicationReports.Dimension dimension : list(report.columnDimensions()))
                reportDimension(report.objectId(), dimension, location + " / 透视列维度");
            for (ApplicationReports.Metric metric : list(report.metrics())) {
                if (!"COUNT".equalsIgnoreCase(metric.operation()))
                    useKey(
                            metricObject(report, metric),
                            metric.fieldId(),
                            location + " / 指标 " + metric.name());
                dynamic(
                        metricObject(report, metric),
                        metric.conditions(),
                        location + " / 指标条件 " + metric.name());
            }
            for (String field : map(report.equal()).keySet())
                useKey(report.objectId(), field, location + " / 固定筛选");
            useKey(report.objectId(), report.dateFieldId(), location + " / 日期范围");
            dynamic(report.objectId(), report.conditions(), location + " / 统计条件");
            if (report.multiSource())
                for (ApplicationReports.Source source : report.extraSources())
                    reportSource(report, source, location + " / 来源 " + source.name());
        }

        /** 基础指标所在的对象：多个数据来源时按 sourceId 找来源（空 = 来源 1）。 */
        private String metricObject(
                ApplicationReports.Config report, ApplicationReports.Metric metric) {
            if (metric.sourceId() == null || !report.multiSource()) return report.objectId();
            return report.extraSources().stream()
                    .filter(s -> Objects.equals(s.id(), metric.sourceId()))
                    .map(ApplicationReports.Source::objectId)
                    .findFirst()
                    .orElse(report.objectId());
        }

        /**
         * 附加来源按它自己的对象扫：维度（与来源 1 同一判据）、固定条件、日期范围、筛选对应；另外对齐的维度字段换型后若与来源 1 同位维度不再相容 ⇒ 阻断
         * （与分组字段不能换成非标量同一级别）。
         */
        private void reportSource(
                ApplicationReports.Config report,
                ApplicationReports.Source source,
                String location) {
            List<ApplicationReports.Dimension> rows = list(report.dimensions());
            List<ApplicationReports.Dimension> columns = list(report.columnDimensions());
            List<ApplicationReports.Dimension> own = list(source.dimensions());
            List<ApplicationReports.Dimension> ownColumns = list(source.columnDimensions());
            for (int i = 0; i < own.size(); i++) {
                reportDimension(source.objectId(), own.get(i), location);
                if (i < rows.size())
                    aligned(
                            report.objectId(),
                            rows.get(i),
                            source.objectId(),
                            own.get(i),
                            location);
            }
            for (int i = 0; i < ownColumns.size(); i++) {
                reportDimension(source.objectId(), ownColumns.get(i), location);
                if (i < columns.size())
                    aligned(
                            report.objectId(),
                            columns.get(i),
                            source.objectId(),
                            ownColumns.get(i),
                            location);
            }
            dynamic(source.objectId(), source.conditions(), location + " / 统计条件");
            useKey(source.objectId(), source.dateFieldId(), location + " / 日期范围");
            for (String field : map(source.filterTargets()).values())
                useKey(source.objectId(), field, location + " / 筛选对应");
        }

        /** 对齐的两个维度有一个是这次换型的字段时，按换型后的定义重判相容性（ReportSourceAlignment，与保存时同一判据）。 */
        private void aligned(
                String mainObject,
                ApplicationReports.Dimension main,
                String object,
                ApplicationReports.Dimension dimension,
                String location) {
            String mainOwner = pathObject(mainObject, main.relationPath());
            String owner = pathObject(object, dimension.relationPath());
            boolean changed =
                    Objects.equals(mainOwner, proposed.objectId())
                                    && fields.contains(main.fieldId())
                            || Objects.equals(owner, proposed.objectId())
                                    && fields.contains(dimension.fieldId());
            if (!changed) return;
            ApplicationReportValidator.Resolved first = resolvedAfter(mainOwner, main.fieldId());
            ApplicationReportValidator.Resolved other = resolvedAfter(owner, dimension.fieldId());
            String field =
                    Objects.equals(owner, proposed.objectId())
                                    && fields.contains(dimension.fieldId())
                            ? dimension.fieldId()
                            : main.fieldId();
            if (first == null
                    || other == null
                    || ReportSourceAlignment.compare(
                                            first,
                                            other,
                                            ReportBucketEnum.fromCode(main.bucket()),
                                            id -> id)
                                    .kind()
                            == null)
                blocked(field, location + " / 对齐维度 " + dimension.fieldId(), false);
        }

        /** 换型后（这次提议的定义）字段所在的定义与字段；主表与明细里都找，找不到返回 null。 */
        private ApplicationReportValidator.Resolved resolvedAfter(String object, String fieldId) {
            DataCenter.Definition owner =
                    Objects.equals(object, proposed.objectId())
                            ? proposed
                            : definitions.get(object);
            if (owner == null) return null;
            for (FieldDefinition f : owner.fields())
                if (Objects.equals(f.id(), fieldId))
                    return new ApplicationReportValidator.Resolved(owner, f);
            if (owner.details() != null)
                for (DataCenter.Detail detail : owner.details())
                    for (FieldDefinition f : detail.fields())
                        if (Objects.equals(f.id(), fieldId))
                            return new ApplicationReportValidator.Resolved(
                                    com.richuang.os.nocode.metadata.service.form.DetailForms
                                            .definition(owner, detail),
                                    f);
            return null;
        }

        private void reportDimension(
                String object, ApplicationReports.Dimension dimension, String location) {
            String target = pathObject(object, dimension.relationPath());
            if (!Objects.equals(target, proposed.objectId())
                    || !fields.contains(dimension.fieldId())) return;
            Field next = after.get(dimension.fieldId());
            ApplicationReportValidator types = new ApplicationReportValidator();
            boolean dateBucket =
                    dimension.bucket() != null
                            && !ReportBucketEnum.VALUE.matches(dimension.bucket());
            if (next == null
                    || !types.scalar(next.definition())
                    || dateBucket && !types.date(next.definition()))
                blocked(dimension.fieldId(), location + " / 分组 " + dimension.fieldId(), false);
        }

        private void action(ApplicationBusiness.Action action, String location) {
            for (String id : map(action.values()).keySet())
                use(action.objectId(), id, location + " / 更新字段", true);
            for (String id : map(action.variables()).values())
                use(action.objectId(), id, location + " / 流程变量", false);
            for (Map.Entry<String, String> capture : action.captures().entrySet()) {
                use(action.objectId(), capture.getKey(), location + " / 留存写入", true);
                use(action.objectId(), capture.getValue(), location + " / 留存来源", false);
            }
        }

        private void automation(ApplicationAutomations.Config config, String location) {
            if (Boolean.FALSE.equals(config.enabled())) return;
            boolean maintaining = AutomationModeEnum.MAINTAIN.matches(config.mode());
            scope(config.objectId(), config.conditions(), location + " / 触发条件");
            // 按日期自动执行按这个字段筛选到日子的来源记录，字段换类型或清空都会让规则失效。
            use(config.objectId(), config.dateFieldId(), location + " / 按日期执行的日期字段", false);
            if (maintaining) {
                maintainedScope(config.objectId(), config.conditions(), location + " / 维护范围条件");
                if (config.binding() != null) {
                    String bindingOwner =
                            RelationDirectionEnum.INCOMING.matches(config.binding().direction())
                                    ? config.targetObjectId()
                                    : config.objectId();
                    DataCenter.Definition owner = definitions.get(bindingOwner);
                    if (owner != null)
                        owner.relations().stream()
                                .filter(r -> r.id().equals(config.binding().relationId()))
                                .forEach(
                                        r ->
                                                maintainedUse(
                                                        bindingOwner,
                                                        r.fieldId(),
                                                        location + " / 维护关系"));
                }
            }
            for (ApplicationAutomations.Assignment assignment : list(config.assignments())) {
                use(config.targetObjectId(), assignment.fieldId(), location + " / 自动更新目标", true);
                use(config.objectId(), assignment.sourceFieldId(), location + " / 自动更新来源", false);
                if (maintaining) {
                    maintainedUse(
                            config.objectId(), assignment.sourceFieldId(), location + " / 自动维护来源");
                    maintainedUse(
                            config.targetObjectId(), assignment.fieldId(), location + " / 自动维护目标");
                }
                link(
                        config.objectId(),
                        assignment.sourceFieldId(),
                        config.targetObjectId(),
                        assignment.fieldId(),
                        location + " / 自动更新两侧类型",
                        true);
            }
        }

        private void maintainedScope(String object, DataScope scope, String location) {
            if (scope == null) return;
            for (DataScope.Condition condition : scope.conditions())
                maintainedUse(object, condition.fieldId(), location);
            for (DataScope group : scope.groups()) maintainedScope(object, group, location);
        }

        private void maintainedUse(String object, String field, String location) {
            if (field == null
                    || !Objects.equals(object, proposed.objectId())
                    || !clearedFields.contains(field)) return;
            result.add(
                    new Impact(
                            field,
                            SourceKind.APPLICATION.name(),
                            appId,
                            appName,
                            location,
                            "直接清空字段不会触发业务自动维护，可能使已保存的维护结果不一致；请先停用或调整该规则并发布应用",
                            "/nocode-app/workspace?id=" + appId,
                            true));
        }

        /** 联动和赋值还须比较两端；各自仍属于数值家族不保证跨端写入兼容。 */
        private void link(
                String fromObject,
                String fromField,
                String targetObject,
                String targetField,
                String location,
                boolean rejectDecimalToInteger) {
            if (fromField == null || targetField == null) return;
            boolean fromChanged =
                    Objects.equals(fromObject, proposed.objectId()) && fields.contains(fromField);
            boolean targetChanged =
                    Objects.equals(targetObject, proposed.objectId())
                            && fields.contains(targetField);
            if (!fromChanged && !targetChanged) return;
            DataCenter.Definition source = fieldOwner(fromObject, fromField);
            DataCenter.Definition target = fieldOwner(targetObject, targetField);
            if (source == null || target == null) return;
            FieldDefinition from =
                    source.fields().stream()
                            .filter(f -> fromField.equals(f.id()))
                            .findFirst()
                            .orElse(null);
            FieldDefinition to =
                    target.fields().stream()
                            .filter(f -> targetField.equals(f.id()))
                            .findFirst()
                            .orElse(null);
            boolean compatible =
                    from != null
                            && to != null
                            && SelectionFields.linkCompatible(source, from, target, to)
                            && !(rejectDecimalToInteger
                                    && FieldTypeEnum.fromCode(from.type()).isDecimal()
                                    && FieldTypeEnum.INTEGER.matches(to.type()));
            if (!compatible) {
                if (fromChanged) blocked(fromField, location, false);
                if (targetChanged) blocked(targetField, location, true);
            }
        }

        private DataCenter.Definition fieldOwner(String object, String field) {
            DataCenter.Definition owner =
                    Objects.equals(object, proposed.objectId())
                            ? proposed
                            : definitions.get(object);
            if (owner == null || owner.fields().stream().anyMatch(f -> field.equals(f.id())))
                return owner;
            for (DataCenter.Detail detail : owner.details())
                if (detail.fields().stream().anyMatch(f -> field.equals(f.id())))
                    return com.richuang.os.nocode.metadata.service.form.DetailForms.definition(
                            owner, detail);
            return owner;
        }

        private void grants(List<ApplicationAuthorization.ObjectGrant> grants, String location) {
            for (ApplicationAuthorization.ObjectGrant grant : list(grants))
                grant.actionScopes()
                        .forEach(
                                (action, scope) ->
                                        scope(grant.objectId(), scope, location + " / " + action));
        }

        /** 成员/共享授权独立生效，解除依赖只需保存授权，无需重新发布应用。 */
        private void liveGrants(
                List<ApplicationAuthorization.ObjectGrant> grants, String location) {
            liveAuthorization = true;
            try {
                grants(grants, location);
            } finally {
                liveAuthorization = false;
            }
        }

        private void scope(String object, DataScope scope, String location) {
            if (scope == null) return;
            for (DataScope.Condition condition : scope.conditions())
                if (!Set.of("isNull", "notNull").contains(condition.operator()))
                    use(object, condition.fieldId(), location, false);
            for (DataScope group : scope.groups()) scope(object, group, location);
        }

        private void expression(
                String object, DocumentPolicy.Expression expression, String location) {
            if (!Objects.equals(object, proposed.objectId()) || expression == null) return;
            for (String id : fields)
                if (ObjectFieldConversionDependencies.expressionUses(expression, id))
                    use(object, id, location, false);
        }

        private void dynamic(String object, DynamicConditionDTO tree, String location) {
            if (tree != null) dynamicItems(object, tree.getItems(), location);
        }

        private void dynamicItems(
                String object, List<DynamicConditionDTO.Item> items, String location) {
            for (DynamicConditionDTO.Item item : list(items))
                if (item.isGroup()) dynamicItems(object, item.getGroupItems(), location);
                else if (!Set.of("isNull", "notNull")
                        .contains(Objects.toString(item.getOperator(), "")))
                    useKey(object, item.getField(), location);
        }

        private void useKey(String object, String key, String location) {
            if (key == null) return;
            int delimiter = key.lastIndexOf(':');
            usePath(
                    object,
                    delimiter < 0 ? null : key.substring(0, delimiter),
                    delimiter < 0 ? key : key.substring(delimiter + 1),
                    location);
        }

        private void usePath(String object, String path, String field, String location) {
            use(pathObject(object, path), field, location, false);
        }

        private String pathObject(String object, String path) {
            String target = object;
            if (path != null && !path.isBlank()) {
                for (String relation : path.split("/")) {
                    DataCenter.Definition owner = definitions.get(target);
                    if (owner == null) return null;
                    target =
                            owner.relations().stream()
                                    .filter(r -> r.id().equals(relation))
                                    .map(DataCenter.Relation::targetObjectId)
                                    .findFirst()
                                    .orElse(null);
                    if (target == null) return null;
                }
            }
            return target;
        }

        private void use(String object, String field, String location, boolean writing) {
            if (field == null
                    || !Objects.equals(object, proposed.objectId())
                    || !fields.contains(field)) return;
            if (!writing && clearedFields.contains(field)) {
                result.add(
                        new Impact(
                                field,
                                SourceKind.APPLICATION.name(),
                                appId,
                                appName,
                                location,
                                "清空该列会改变此筛选或取值配置的历史结果，请在应用维护后检查并发布",
                                "/nocode-app/workspace?id=" + appId,
                                true));
                return;
            }
            boolean compatible =
                    writing
                            ? FieldConversionCompatibility.writeCompatible(
                                    before.get(field), after.get(field))
                            : FieldConversionCompatibility.valueCompatible(
                                    before.get(field), after.get(field));
            if (compatible) return;
            blocked(field, location, writing);
        }

        private void blocked(String field, String location, boolean writing) {
            result.add(
                    new Impact(
                            field,
                            SourceKind.APPLICATION.name(),
                            appId,
                            appName,
                            location,
                            (liveAuthorization ? "当前授权" : "此配置")
                                    + "仍按原类型"
                                    + (writing ? "写入" : "使用")
                                    + "本列，与目标类型不兼容",
                            "/nocode-app/workspace?id=" + appId,
                            true));
        }

        private static <T> List<T> list(List<T> value) {
            return value == null ? List.of() : value;
        }

        private static <K, V> Map<K, V> map(Map<K, V> value) {
            return value == null ? Map.of() : value;
        }
    }
}
