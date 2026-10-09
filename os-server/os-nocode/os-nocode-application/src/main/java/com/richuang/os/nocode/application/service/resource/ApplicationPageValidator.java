package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 页面节点、上下文、动作和统计筛选配置校验，保留递归顺序与首个错误。 */
@Component
public class ApplicationPageValidator {
    @Resource private ApplicationResourceContext resourceContext;
    @Resource private DraftValidator validator;
    @Resource private ApplicationPageBindings pageBindings;
    @Resource private ApplicationReportValidator reports;

    /** 报错里用的节点名称，与页面设计器物料名一致。 */
    private static final Map<String, String> LABELS =
            Map.ofEntries(
                    Map.entry("ROW", "分栏容器"),
                    Map.entry("COLUMN", "分栏"),
                    Map.entry("CARD", "卡片"),
                    Map.entry("TABS", "页签容器"),
                    Map.entry("TAB", "页签"),
                    Map.entry("FLEX", "弹性布局"),
                    Map.entry("SPACER", "留白"),
                    Map.entry("TEXT", "说明文字"),
                    Map.entry("DIVIDER", "分隔线"),
                    Map.entry("HEADING", "标题"),
                    Map.entry("IMAGE", "图片"),
                    Map.entry("ALERT", "提示信息"),
                    Map.entry("BUTTON", "操作按钮"),
                    Map.entry("VIEW", "数据列表"),
                    Map.entry("REPORT", "统计报表"),
                    Map.entry("FORM", "业务表单"),
                    Map.entry("DETAIL", "记录详情"),
                    Map.entry("RELATED", "相关列表"),
                    Map.entry("ATTACHMENTS", "记录附件"),
                    Map.entry("PROCESSES", "审批记录"),
                    Map.entry("METRIC", "记录统计"),
                    Map.entry("ENGINE", "设计引擎"));

    private static String label(String type) {
        return LABELS.getOrDefault(type, type);
    }

    void nodes(
            List<ApplicationUi.Node> nodes,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, FieldDefinition> fields,
            Set<String> fieldIds,
            boolean form,
            int depth,
            Set<String> ids) {
        nodes(nodes, resources, fields, fieldIds, form, depth, ids, form ? "表单" : "业务页面");
    }

    void nodes(
            List<ApplicationUi.Node> nodes,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, FieldDefinition> fields,
            Set<String> fieldIds,
            boolean form,
            int depth,
            Set<String> ids,
            String location) {
        if (nodes == null || depth > 8) throw invalid("页面节点为空或布局嵌套超过 8 层");
        for (var n : nodes) {
            if (n == null) throw invalid("页面节点不能为空");
            resourceContext.identifier(n.id());
            if (!ids.add(n.id()) || ids.size() > 200) throw invalid("页面节点重复或超过 200 个");
            var type = ApplicationNodeKindEnum.fromCode(n.type());
            pageAppearance(n, type, form);
            if (n.text() != null && n.text().length() > 2000) throw invalid("页面文字最多 2000 字符");
            if (n.span() != null && (n.span() < 1 || n.span() > 24)) throw invalid("栅格宽度应为 1 到 24");
            var children = n.children() == null ? List.<ApplicationUi.Node>of() : n.children();
            switch (type) {
                case ROW -> {
                    if (children.stream()
                            .anyMatch(
                                    c ->
                                            c == null
                                                    || !ApplicationNodeKindEnum.COLUMN.matches(
                                                            c.type()))) throw invalid("行布局中只能包含列");
                    nodes(children, resources, fields, fieldIds, form, depth + 1, ids, location);
                }
                case TABS -> {
                    if (children.isEmpty()
                            || children.stream()
                                    .anyMatch(
                                            c ->
                                                    c == null
                                                            || !ApplicationNodeKindEnum.TAB.matches(
                                                                    c.type())))
                        // 规则不变；报错补上是哪个页面的哪个节点、里面直接放了什么（业务方拖拽后保存失败，看不出改哪里）。
                        throw invalid(
                                location
                                        + "，节点“"
                                        + n.id()
                                        + "”：页签容器只能包含至少一个页签"
                                        + children.stream()
                                                .filter(
                                                        c ->
                                                                c != null
                                                                        && !ApplicationNodeKindEnum
                                                                                .TAB
                                                                                .matches(c.type()))
                                                .map(
                                                        c ->
                                                                "（不能直接放「"
                                                                        + label(c.type())
                                                                        + "」，请放进某个页签里）")
                                                .findFirst()
                                                .orElse(""));
                    nodes(children, resources, fields, fieldIds, form, depth + 1, ids, location);
                }
                case COLUMN, CARD, TAB, FLEX ->
                        nodes(
                                children, resources, fields, fieldIds, form, depth + 1, ids,
                                location);
                case FIELD -> {
                    String fieldLocation =
                            location + "，节点“" + n.id() + "”的字段（ID：" + n.fieldId() + "）";
                    if (!form) throw invalid(fieldLocation + "不允许直接放入业务页面，请使用业务表单区块");
                    if (!fields.containsKey(n.fieldId()))
                        throw invalid(fieldLocation + "在当前对象固定版本中不存在或已停用");
                    if (!fieldIds.add(n.fieldId())) throw invalid(fieldLocation + "重复配置");
                    if (!children.isEmpty()) throw invalid("字段不能包含子节点");
                }
                case INTERNAL_DETAIL -> {
                    if (!form) throw invalid("内部明细节点仅用于对象表单，不能放入业务页面");
                    if (n.detail() == null) throw invalid("内部明细节点缺少明细绑定");
                    InternalDetailModeEnum.fromCode(n.detail().mode());
                    if (!children.isEmpty()) throw invalid("内部明细节点不能包含子节点");
                }
                case VIEW,
                        METRIC,
                        REPORT,
                        REPORT_DASHBOARD,
                        FORM,
                        DETAIL,
                        RELATED,
                        ATTACHMENTS,
                        PROCESSES,
                        TASKS -> {
                    if (form) throw invalid("字段表单不能嵌套业务页面块");
                    // 任务范围可直接沿用应用或页面当前记录；存量表单配置仍校验资源类型和对象匹配。
                    if (type != ApplicationNodeKindEnum.TASKS
                            || n.resourceId() != null && !n.resourceId().isBlank())
                        resourceContext.resource(
                                resources,
                                n.resourceId(),
                                type == ApplicationNodeKindEnum.FORM
                                                || type == ApplicationNodeKindEnum.DETAIL
                                                || type == ApplicationNodeKindEnum.ATTACHMENTS
                                                || type == ApplicationNodeKindEnum.PROCESSES
                                                || type == ApplicationNodeKindEnum.TASKS
                                        ? ApplicationResourceKindEnum.FORM
                                        : type == ApplicationNodeKindEnum.REPORT
                                                ? ApplicationResourceKindEnum.REPORT
                                                : type == ApplicationNodeKindEnum.REPORT_DASHBOARD
                                                        ? ApplicationResourceKindEnum
                                                                .REPORT_DASHBOARD
                                                        : ApplicationResourceKindEnum.VIEW);
                    if (!children.isEmpty()) throw invalid("业务块不能包含子节点");
                }
                case ENGINE -> {
                    // 设计引擎：固定绑定页面当前记录，不绑资源；当前对象与库/写回对象在 pageContext 里核对。
                    if (form) throw invalid("字段表单不能放设计引擎区块");
                    if (!children.isEmpty()) throw invalid("业务块不能包含子节点");
                    ApplicationEngineValidator.node(n);
                }
                case TEXT, DIVIDER, HEADING, IMAGE, ALERT, BUTTON, SPACER -> {
                    if (!children.isEmpty()) throw invalid("文字不能包含子节点");
                }
            }
            if (type != ApplicationNodeKindEnum.FIELD && n.fieldId() != null)
                throw invalid("非字段节点不能绑定字段");
            if (type != ApplicationNodeKindEnum.INTERNAL_DETAIL && n.detail() != null)
                throw invalid("仅内部明细节点允许配置明细绑定");
            if (type != ApplicationNodeKindEnum.TASKS && n.taskView() != null)
                throw invalid("仅任务区块允许配置任务业务视图");
            if (type != ApplicationNodeKindEnum.ENGINE && n.engine() != null)
                throw invalid("仅设计引擎区块允许配置设计引擎");
            if (type != ApplicationNodeKindEnum.RELATED
                    && type != ApplicationNodeKindEnum.REPORT
                    && n.binding() != null) throw invalid("仅关联列表和统计报表允许绑定关系");
            if (type != ApplicationNodeKindEnum.FIELD && n.presentation() != null)
                throw invalid("仅表单字段允许设置字段呈现");
            if (n.presentation() != null) {
                var p = n.presentation();
                for (String text : Arrays.asList(p.label(), p.placeholder(), p.help()))
                    if (text != null && text.length() > 500) throw invalid("字段呈现文字最多 500 字符");
                // 保存位置提示只对附件/图片控件有意义，其他字段显式设置时说明配置来源异常
                if (p.showBusinessPath() != null) {
                    FieldDefinition presented = fields.get(n.fieldId());
                    if (presented == null
                            || (!FieldTypeEnum.ATTACHMENT.matches(presented.type())
                                    && !FieldTypeEnum.IMAGE.matches(presented.type()))) {
                        throw invalid("只有附件或图片字段可以设置保存位置提示");
                    }
                }
            }
            if (type != ApplicationNodeKindEnum.VIEW
                    && type != ApplicationNodeKindEnum.FORM
                    && type != ApplicationNodeKindEnum.DETAIL
                    && type != ApplicationNodeKindEnum.RELATED
                    && type != ApplicationNodeKindEnum.ATTACHMENTS
                    && type != ApplicationNodeKindEnum.PROCESSES
                    && type != ApplicationNodeKindEnum.TASKS
                    && type != ApplicationNodeKindEnum.METRIC
                    && type != ApplicationNodeKindEnum.REPORT
                    && type != ApplicationNodeKindEnum.REPORT_DASHBOARD
                    && n.resourceId() != null) throw invalid("当前节点不能绑定业务资源");
        }
    }

    void pageContext(
            ApplicationUi.Page page,
            List<ApplicationUi.Node> nodes,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, DataCenter.Definition> definitions) {
        for (var node : nodes) {
            var kind = ApplicationNodeKindEnum.fromCode(node.type());
            if (kind == ApplicationNodeKindEnum.TASKS && node.taskView() != null)
                taskView(page, node, resources, definitions);
            if (kind == ApplicationNodeKindEnum.ENGINE)
                ApplicationEngineValidator.context(resourceContext, page, node, definitions);
            if (kind == ApplicationNodeKindEnum.REPORT_DASHBOARD) {
                ApplicationDashboards.Config dashboard =
                        resourceContext.decode(
                                resourceContext
                                        .resource(
                                                resources,
                                                node.resourceId(),
                                                ApplicationResourceKindEnum.REPORT_DASHBOARD)
                                        .config(),
                                ApplicationDashboards.Config.class);
                if (dashboard.contextObjectId() != null
                        && !dashboard.contextObjectId().isBlank()
                        && !Objects.equals(page.contextObjectId(), dashboard.contextObjectId()))
                    throw invalid("仪表板上下文必须与业务页面当前对象一致");
            }
            if (kind == ApplicationNodeKindEnum.REPORT && node.binding() != null) {
                var report =
                        resourceContext.decode(
                                resourceContext
                                        .resource(
                                                resources,
                                                node.resourceId(),
                                                ApplicationResourceKindEnum.REPORT)
                                        .config(),
                                ApplicationReports.Config.class);
                if (report.multiSource()) throw invalid(ReportSourceMessages.RECORD_PAGE);
                pageBindings.resolve(
                        page.contextObjectId(),
                        report.objectId(),
                        node.binding(),
                        definitions::get);
            }
            if (kind == ApplicationNodeKindEnum.RELATED) {
                var view =
                        resourceContext.decode(
                                resourceContext
                                        .resource(
                                                resources,
                                                node.resourceId(),
                                                ApplicationResourceKindEnum.VIEW)
                                        .config(),
                                ApplicationUi.View.class);
                pageBindings.resolve(
                        page.contextObjectId(), view.objectId(), node.binding(), definitions::get);
            }
            if (kind == ApplicationNodeKindEnum.DETAIL
                    || kind == ApplicationNodeKindEnum.ATTACHMENTS
                    || kind == ApplicationNodeKindEnum.PROCESSES
                    || (kind == ApplicationNodeKindEnum.FORM
                                    || kind == ApplicationNodeKindEnum.TASKS
                                            && node.resourceId() != null
                                            && !node.resourceId().isBlank())
                            && page.contextObjectId() != null) {
                var form =
                        resourceContext.decode(
                                resourceContext
                                        .resource(
                                                resources,
                                                node.resourceId(),
                                                ApplicationResourceKindEnum.FORM)
                                        .config(),
                                ApplicationUi.Form.class);
                if (page.contextObjectId() == null
                        || !page.contextObjectId().equals(form.objectId()))
                    throw invalid("当前记录区块必须绑定页面当前对象的表单");
            }
            if (node.children() != null) pageContext(page, node.children(), resources, definitions);
        }
    }

    void reportFilters(
            ApplicationUi.Page page,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, DataCenter.Definition> definitions) {
        if (page.filters() == null) return;
        if (page.filters().size() > 10) throw invalid("页面统一筛选最多 10 项");
        Set<String> ids = new HashSet<>(), bound = new HashSet<>();
        for (var filter : page.filters()) {
            if (filter == null) throw invalid("页面筛选不能为空");
            resourceContext.identifier(filter.id());
            if (!ids.add(filter.id())) throw invalid("页面筛选 ID 重复");
            validator.text(filter.name(), "页面筛选名称", 60);
            var field =
                    reports.field(
                            resourceContext.object(definitions, filter.objectId()),
                            filter.fieldId());
            if (!reports.scalar(field) || filter.dateRange() && !reports.date(field))
                throw invalid("页面筛选字段类型不匹配");
            if (filter.targets() == null
                    || filter.targets().isEmpty()
                    || filter.targets().size() > 30) throw invalid("请绑定需要联动的统计视图");
            for (var target : filter.targets().entrySet()) {
                var report =
                        resourceContext.decode(
                                resourceContext
                                        .resource(
                                                resources,
                                                target.getKey(),
                                                ApplicationResourceKindEnum.REPORT)
                                        .config(),
                                ApplicationReports.Config.class);
                // 目标字段按那张统计的粒度解析：绑定到明细粒度统计时可以是所选明细的字段。
                DataCenter.Definition reportRoot =
                        resourceContext.object(definitions, report.objectId());
                var resolved =
                        reports.resolve(
                                resourceContext.object(definitions, report.objectId()),
                                target.getValue(),
                                definitions,
                                reports.grainDetail(report, reportRoot));
                var targetField = resolved.field();
                if (filter.dateRange()
                        ? !Objects.equals(report.dateFieldId(), target.getValue())
                        : report.filterFieldIds() == null
                                || !report.filterFieldIds().contains(target.getValue()))
                    throw invalid("页面筛选必须绑定统计视图开放的筛选字段");
                // 多个数据来源：目标键是来源 1 的键，每个附加来源都要能映射到自己的字段（与保存时 L14 同一判定）。
                if (report.multiSource() && !filter.dateRange())
                    for (ApplicationReports.Source source : report.extraSources())
                        if (reports.filterKey(report, source, target.getValue()) == null)
                            throw invalid(
                                    ReportSourceMessages.filterMissing(
                                            targetField.name(), source.name()));
                if (!Objects.equals(field.type(), targetField.type())) throw invalid("联动字段类型必须一致");
                if (!bound.add(
                        target.getKey() + ":" + (filter.dateRange() ? "date" : target.getValue())))
                    throw invalid("同一个统计筛选不能重复绑定");
                // 关系外键可能采用 INTEGER/UUID 等物理类型，不能仅用 REFERENCE 字段类型识别。
                var source =
                        resourceContext.object(definitions, filter.objectId()).relations().stream()
                                .filter(r -> Objects.equals(r.fieldId(), field.id()))
                                .findFirst()
                                .orElse(null);
                var dest =
                        resolved.owner().relations().stream()
                                .filter(r -> Objects.equals(r.fieldId(), targetField.id()))
                                .findFirst()
                                .orElse(null);
                if (source != null
                        || dest != null
                        || FieldTypeEnum.REFERENCE.matches(field.type())) {
                    if (source == null || dest == null) throw invalid("引用筛选缺少对应关系");
                    if (!Objects.equals(source.targetObjectId(), dest.targetObjectId()))
                        throw invalid("联动引用必须指向同一对象");
                }
            }
        }
    }

    /** 发布时约束任务视图的字段和结构，运行时还须与当前数据权限取交集。 */
    private void taskView(
            ApplicationUi.Page page,
            ApplicationUi.Node node,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, DataCenter.Definition> definitions) {
        ApplicationUi.TaskView view = node.taskView();
        boolean needsBusiness =
                view.conditions() != null
                        || view.columnKeys() != null
                                && view.columnKeys().stream()
                                        .anyMatch(k -> k != null && k.startsWith("business:"))
                        || view.sort() != null
                                && view.sort().field() != null
                                && view.sort().field().startsWith("business:");
        if (page.contextObjectId() != null && view.businessFormId() == null && needsBusiness)
            throw invalid("当前记录任务区块请先选择任务业务表单，再配置业务字段");
        String formId = view.businessFormId() == null ? node.resourceId() : view.businessFormId();
        if (formId != null && formId.isBlank()) formId = null;
        if (formId == null && needsBusiness) throw invalid("请先选择任务业务表单，再配置业务字段");
        DataCenter.Definition definition = null;
        Set<String> fields = new HashSet<>();
        if (formId != null) {
            ApplicationUi.Form form =
                    resourceContext.decode(
                            resourceContext
                                    .resource(resources, formId, ApplicationResourceKindEnum.FORM)
                                    .config(),
                            ApplicationUi.Form.class);
            definition = resourceContext.object(definitions, form.objectId());
            definition.fields().forEach(field -> fields.add(field.id()));
        }
        if (view.columnKeys() != null) {
            if (view.columnKeys().size() > 60
                    || new HashSet<>(view.columnKeys()).size() != view.columnKeys().size())
                throw invalid("任务视图列重复或超过 60 列");
            for (String key : view.columnKeys()) taskColumn(key, fields, false);
        }
        if (view.sort() != null) taskColumn(view.sort().field(), fields, true);
        if (view.templateIds() != null) {
            if (view.templateIds().size() > 100
                    || new HashSet<>(view.templateIds()).size() != view.templateIds().size())
                throw invalid("任务模板范围重复或超过 100 项");
            for (String id : view.templateIds()) {
                if (id == null || !id.matches("[a-f0-9-]{36}")) throw invalid("任务模板身份无效");
            }
        }
        if (definition != null)
            reports.validateConditions(view.conditions(), definition, definitions, fields);
        ApplicationUi.TaskFilter filter = view.taskFilter();
        if (filter != null) {
            taskValues(filter.statuses(), TaskCenter.State.class);
            taskValues(filter.urgencies(), TaskCenter.Urgency.class);
            taskValues(filter.priorities(), TaskCenter.Priority.class);
            if (filter.category() != null)
                taskValues(List.of(filter.category()), TaskCenter.Category.class);
        }
    }

    private void taskColumn(String key, Set<String> fields, boolean sort) {
        Set<String> common =
                sort
                        ? Set.of("title", "createdAt", "expectedEnd", "priority", "urgency")
                        : Set.of("title", "owner", "status", "time", "priority");
        if (key == null
                || !common.contains(key)
                        && !(key.startsWith("business:") && fields.contains(key.substring(9))))
            throw invalid(sort ? "任务视图排序字段无效" : "任务视图列字段无效");
    }

    private <T extends Enum<T>> void taskValues(List<String> values, Class<T> type) {
        if (values == null) return;
        if (values.size() > type.getEnumConstants().length
                || new HashSet<>(values).size() != values.size()) throw invalid("任务固定筛选重复或过多");
        for (String value : values) {
            try {
                Enum.valueOf(type, value);
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw invalid("任务固定筛选取值无效");
            }
        }
    }

    void pageAppearance(ApplicationUi.Node node, ApplicationNodeKindEnum type, boolean form) {
        var pageOnly =
                Set.of(
                        ApplicationNodeKindEnum.HEADING,
                        ApplicationNodeKindEnum.IMAGE,
                        ApplicationNodeKindEnum.ALERT,
                        ApplicationNodeKindEnum.BUTTON,
                        ApplicationNodeKindEnum.FLEX,
                        ApplicationNodeKindEnum.SPACER);
        if (form
                && (pageOnly.contains(type)
                        || node.style() != null
                        || node.display() != null
                        || node.action() != null)) throw invalid("页面外观与动作仅用于业务页面，字段表单使用原表单呈现配置");
        if (node.action() != null && type != ApplicationNodeKindEnum.BUTTON)
            throw invalid("仅按钮允许配置页面动作");
        var s = node.style();
        if (s != null) {
            range(s.padding(), 0, 48, "内边距");
            range(s.gap(), 0, 48, "区块间距");
            range(s.marginBottom(), 0, 48, "下边距");
            range(s.minHeight(), 0, 800, "最小高度");
            range(s.radius(), 0, 24, "圆角");
            for (String color : Arrays.asList(s.background(), s.color()))
                if (color != null && !color.matches("#[0-9a-fA-F]{6}"))
                    throw invalid("页面颜色仅允许六位十六进制纯色");
            if (s.align() != null) PageAlignEnum.fromCode(s.align());
            if (s.direction() != null) {
                if (type != ApplicationNodeKindEnum.FLEX) throw invalid("排列方向仅用于弹性容器");
                PageDirectionEnum.fromCode(s.direction());
            }
        }
        var d = node.display();
        if (type == ApplicationNodeKindEnum.IMAGE && (d == null || d.imageFileId() == null))
            throw invalid("图片区块需要选择展示图片");
        if (d == null) return;
        if (d.headingLevel() != null) {
            if (type != ApplicationNodeKindEnum.HEADING) throw invalid("标题层级仅用于标题");
            range(d.headingLevel(), 2, 5, "标题层级");
        }
        if (d.alertType() != null) {
            if (type != ApplicationNodeKindEnum.ALERT) throw invalid("提示类型仅用于提示区块");
            PageAlertTypeEnum.fromCode(d.alertType());
        }
        if (d.buttonType() != null) {
            if (type != ApplicationNodeKindEnum.BUTTON) throw invalid("按钮样式仅用于按钮");
            PageButtonTypeEnum.fromCode(d.buttonType());
        }
        if (d.imageFileId() != null
                || d.imageAlt() != null
                || d.imageFit() != null
                || d.imageHeight() != null) {
            if (type != ApplicationNodeKindEnum.IMAGE) throw invalid("图片设置仅用于图片区块");
            if (d.imageFileId() != null && !d.imageFileId().matches("[1-9][0-9]{0,18}"))
                throw invalid("图片必须引用底座文件 ID");
            if (d.imageAlt() != null && d.imageAlt().length() > 200) throw invalid("图片说明最多 200 字");
            if (d.imageFit() != null) PageImageFitEnum.fromCode(d.imageFit());
            range(d.imageHeight(), 32, 600, "图片高度");
        }
    }

    void range(Integer value, int min, int max, String label) {
        if (value != null && (value < min || value > max))
            throw invalid(label + "应为 " + min + " 到 " + max);
    }

    /** 发布时验证按钮与本页业务块/应用资源的依赖。运行写入仍走公共服务，不能只信任按钮配置。 */
    void pageActions(
            ApplicationUi.Page page,
            List<ApplicationUi.Node> nodes,
            Map<String, ApplicationCenter.Resource> resources,
            Map<String, DataCenter.Definition> definitions) {
        for (var node : nodes) {
            if (ApplicationNodeKindEnum.BUTTON.matches(node.type())) {
                var action = node.action();
                if (action == null) throw invalid("页面按钮需要配置点击动作");
                validator.text(node.text(), "按钮名称", 60);
                var kind = PageActionKindEnum.fromCode(action.kind());
                if (action.openMode() != null) RecordOpenModeEnum.fromCode(action.openMode());
                if (action.confirmText() != null && action.confirmText().length() > 200)
                    throw invalid("按钮确认文字最多 200 字");
                boolean blockAction =
                        Set.of(
                                        PageActionKindEnum.CREATE,
                                        PageActionKindEnum.EDIT,
                                        PageActionKindEnum.VIEW,
                                        PageActionKindEnum.REFRESH)
                                .contains(kind);
                if (blockAction) {
                    if (action.resourceId() != null) throw invalid("区块动作不能另行指定业务资源");
                    var target = pageBindings.node(page.nodes(), action.targetNodeId());
                    if (kind == PageActionKindEnum.REFRESH && action.targetNodeId() == null)
                        continue;
                    if (target == null) throw invalid("按钮目标业务区块不存在");
                    ApplicationNodeKindEnum targetKind =
                            ApplicationNodeKindEnum.fromCode(target.type());
                    if ((target.resourceId() == null || target.resourceId().isBlank())
                            && !(kind == PageActionKindEnum.REFRESH
                                    && targetKind == ApplicationNodeKindEnum.TASKS))
                        throw invalid("按钮目标业务区块不存在");
                    if (kind == PageActionKindEnum.REFRESH
                            && !Set.of(
                                            ApplicationNodeKindEnum.VIEW,
                                            ApplicationNodeKindEnum.RELATED,
                                            ApplicationNodeKindEnum.DETAIL,
                                            ApplicationNodeKindEnum.METRIC,
                                            ApplicationNodeKindEnum.REPORT,
                                            ApplicationNodeKindEnum.REPORT_DASHBOARD,
                                            ApplicationNodeKindEnum.ATTACHMENTS,
                                            ApplicationNodeKindEnum.PROCESSES,
                                            ApplicationNodeKindEnum.TASKS)
                                    .contains(targetKind))
                        throw invalid("刷新按钮只能绑定列表、详情、统计、附件、审批或任务区块");
                    if (kind == PageActionKindEnum.CREATE) {
                        if (!Set.of(ApplicationNodeKindEnum.VIEW, ApplicationNodeKindEnum.RELATED)
                                .contains(targetKind)) throw invalid("新增按钮必须绑定数据列表或相关列表");
                        var view =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        target.resourceId(),
                                                        ApplicationResourceKindEnum.VIEW)
                                                .config(),
                                        ApplicationUi.View.class);
                        if (resourceContext.viewForm(resources.values(), view) == null)
                            throw invalid("新增按钮的目标列表需要配置业务表单或对象默认表单");
                        if (targetKind == ApplicationNodeKindEnum.RELATED) {
                            var relation =
                                    pageBindings.resolve(
                                            page.contextObjectId(),
                                            view.objectId(),
                                            target.binding(),
                                            definitions::get);
                            if (relation.direction() != RelationDirectionEnum.INCOMING
                                    || RelationTypeEnum.MANY_TO_MANY.matches(
                                            relation.relation().kind()))
                                throw invalid("关联新增仅支持目标记录通过引用字段归属当前记录");
                        }
                    }
                    if (kind == PageActionKindEnum.EDIT || kind == PageActionKindEnum.VIEW) {
                        if (!Set.of(ApplicationNodeKindEnum.DETAIL, ApplicationNodeKindEnum.FORM)
                                .contains(targetKind)) throw invalid("编辑/查看按钮必须绑定当前记录表单或详情区块");
                        var form =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        target.resourceId(),
                                                        ApplicationResourceKindEnum.FORM)
                                                .config(),
                                        ApplicationUi.Form.class);
                        if (page.contextObjectId() == null
                                || !page.contextObjectId().equals(form.objectId()))
                            throw invalid("编辑/查看按钮缺少同对象的当前记录");
                    }
                } else {
                    if (action.targetNodeId() != null) throw invalid("资源动作不能同时指定目标区块");
                    if (kind == PageActionKindEnum.EXECUTE_ACTION) {
                        var business =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        action.resourceId(),
                                                        ApplicationResourceKindEnum.ACTION)
                                                .config(),
                                        ApplicationBusiness.Action.class);
                        if (page.contextObjectId() == null
                                || !page.contextObjectId().equals(business.objectId()))
                            throw invalid("业务动作必须绑定页面当前对象");
                    } else if (kind == PageActionKindEnum.OPEN_PAGE) {
                        var target =
                                resourceContext.decode(
                                        resourceContext
                                                .resource(
                                                        resources,
                                                        action.resourceId(),
                                                        ApplicationResourceKindEnum.PAGE)
                                                .config(),
                                        ApplicationUi.Page.class);
                        if (target.contextObjectId() != null
                                && !Objects.equals(
                                        page.contextObjectId(), target.contextObjectId()))
                            throw invalid("打开详情页面必须具有相同当前对象");
                    } else if (kind == PageActionKindEnum.NAVIGATE) {
                        ApplicationCenter.Resource target =
                                resourceContext.resource(
                                        resources,
                                        action.resourceId(),
                                        ApplicationResourceKindEnum.MENU,
                                        ApplicationResourceKindEnum.PAGE,
                                        ApplicationResourceKindEnum.VIEW,
                                        ApplicationResourceKindEnum.REPORT_DASHBOARD);
                        if (ApplicationResourceKindEnum.MENU.matches(target.kind())) {
                            ApplicationUi.Menu menu =
                                    resourceContext.decode(
                                            target.config(), ApplicationUi.Menu.class);
                            target =
                                    resourceContext.resource(
                                            resources,
                                            menu.targetId(),
                                            ApplicationResourceKindEnum.PAGE,
                                            ApplicationResourceKindEnum.VIEW,
                                            ApplicationResourceKindEnum.REPORT_DASHBOARD);
                        }
                        if (ApplicationResourceKindEnum.PAGE.matches(target.kind())
                                && resourceContext
                                                .decode(target.config(), ApplicationUi.Page.class)
                                                .contextObjectId()
                                        != null) throw invalid("导航跳转仅用于不依赖当前记录的入口");
                        if (ApplicationResourceKindEnum.REPORT_DASHBOARD.matches(target.kind())
                                && resourceContext
                                                .decode(
                                                        target.config(),
                                                        ApplicationDashboards.Config.class)
                                                .contextObjectId()
                                        != null) throw invalid("导航跳转不能打开依赖当前记录的仪表板");
                    }
                }
            }
            if (node.children() != null) pageActions(page, node.children(), resources, definitions);
        }
    }
}
