package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 页面/表单是受控结构，前端开源设计器规则经适配后转换为这些稳定契约。 */
public final class ApplicationUi {
    private ApplicationUi() {}

    public record Node(
            String id,
            String type,
            String fieldId,
            String resourceId,
            String text,
            Integer span,
            List<Node> children,
            RelationBinding binding,
            FieldPresentation presentation,
            PageStyle style,
            PageDisplay display,
            PageAction action,
            InternalDetailBinding detail,
            TaskView taskView,
            @JsonInclude(JsonInclude.Include.NON_NULL) EngineBlock engine) {
        /** 保留旧节点构造与 JSON 语义；设计引擎配置仅由新增 engine 属性声明。 */
        public Node(
                String id,
                String type,
                String fieldId,
                String resourceId,
                String text,
                Integer span,
                List<Node> children,
                RelationBinding binding,
                FieldPresentation presentation,
                PageStyle style,
                PageDisplay display,
                PageAction action,
                InternalDetailBinding detail,
                TaskView taskView) {
            this(
                    id,
                    type,
                    fieldId,
                    resourceId,
                    text,
                    span,
                    children,
                    binding,
                    presentation,
                    style,
                    display,
                    action,
                    detail,
                    taskView,
                    null);
        }

        public Node(
                String id,
                String type,
                String fieldId,
                String resourceId,
                String text,
                Integer span,
                List<Node> children,
                RelationBinding binding,
                FieldPresentation presentation,
                PageStyle style,
                PageDisplay display,
                PageAction action,
                InternalDetailBinding detail) {
            this(
                    id,
                    type,
                    fieldId,
                    resourceId,
                    text,
                    span,
                    children,
                    binding,
                    presentation,
                    style,
                    display,
                    action,
                    detail,
                    null);
        }

        /** 保留旧节点构造与 JSON 语义；内部明细仅由新增 detail 属性声明。 */
        public Node(
                String id,
                String type,
                String fieldId,
                String resourceId,
                String text,
                Integer span,
                List<Node> children,
                RelationBinding binding,
                FieldPresentation presentation,
                PageStyle style,
                PageDisplay display,
                PageAction action) {
            this(
                    id,
                    type,
                    fieldId,
                    resourceId,
                    text,
                    span,
                    children,
                    binding,
                    presentation,
                    style,
                    display,
                    action,
                    null);
        }

        public Node(
                String id,
                String type,
                String fieldId,
                String resourceId,
                String text,
                Integer span,
                List<Node> children,
                RelationBinding binding,
                FieldPresentation presentation) {
            this(
                    id,
                    type,
                    fieldId,
                    resourceId,
                    text,
                    span,
                    children,
                    binding,
                    presentation,
                    null,
                    null,
                    null);
        }

        public Node(
                String id,
                String type,
                String fieldId,
                String resourceId,
                String text,
                Integer span,
                List<Node> children) {
            this(id, type, fieldId, resourceId, text, span, children, null, null);
        }
    }

    /**
     * 设计引擎区块配置（laneEG）。区块固定绑定页面当前记录；引擎地址只收站内相对路径（空 = 部署默认）。
     *
     * @param engineUrl 引擎站内路径，如 /engine01/；空表示 os.nocode.engine.default-url
     * @param materials 材料库：对象 + 字段映射（键见 EngineLibrary）
     * @param components 构件库：同上
     * @param bom 材料清单 / 工程量写回目标
     */
    public record EngineBlock(
            String engineUrl,
            EngineLibrary materials,
            EngineLibrary components,
            EngineWriteback bom) {}

    /**
     * 引擎只读的库对象。fields
     * 的键：name（必填）、manufacturer、model、specification、unit、unitPrice、methodCode；值为该对象的字段 ID。
     */
    public record EngineLibrary(String objectId, Map<String, String> fields) {}

    /**
     * 写回对象。recordFieldId 指向页面当前对象的引用字段（定位本工事）；keyFieldId 存引擎行键（幂等更新用）。fields 的键：quantity（必填）、
     * material（引用材料库）、name、unit、unitPrice、methodCode、basis、space。
     */
    public record EngineWriteback(
            String objectId, String recordFieldId, String keyFieldId, Map<String, String> fields) {}

    /** 只引用已有持久关系，方向相对页面当前记录；不接收物理表名或客户端 SQL。 */
    public record RelationBinding(String relationId, String direction) {}

    /** 内部明细仅引用当前对象明细；列规则仍保存在 Form.detailNodes，不嵌入节点 children。 */
    public record InternalDetailBinding(String detailId, String mode) {}

    /** 发布的任务业务视图；当前记录范围仍由节点表单与页面上下文确定。 */
    public record TaskView(
            String businessFormId,
            List<String> columnKeys,
            com.richuang.os.common.dto.DynamicConditionDTO conditions,
            TaskFilter taskFilter,
            List<String> templateIds,
            TaskSort sort) {}

    public record TaskFilter(
            List<String> statuses,
            List<String> urgencies,
            List<String> priorities,
            String category) {}

    public record TaskSort(String field, boolean descending) {}

    /** 静态外观仅保存有界数值和纯色，不能保存 CSS 文本、URL 或表达式。 */
    public record PageStyle(
            Integer padding,
            Integer gap,
            Integer marginBottom,
            Integer minHeight,
            Integer radius,
            String background,
            String color,
            Boolean border,
            String align,
            String direction) {}

    public record PageDisplay(
            Integer headingLevel,
            String alertType,
            String buttonType,
            String imageFileId,
            String imageAlt,
            String imageFit,
            Integer imageHeight) {}

    /** 当前页面节点 ID 指向已发布业务块；关联条件由目标块复用，客户端不另传关系定义。 */
    public record PageAction(
            String kind,
            String targetNodeId,
            String resourceId,
            String openMode,
            String confirmText) {}

    /** 表单呈现不会改变对象约束或字段权限。只读设置只能进一步收紧可编辑性； 保存位置提示只是附件控件的显示开关，不改变文件归属与目录生成。 */
    public record FieldPresentation(
            String label,
            String placeholder,
            String help,
            Boolean readOnly,
            SelectionFields.Presentation selection,
            FieldBehavior behavior,
            FormFills.Binding fill,
            Boolean showBusinessPath) {
        public FieldPresentation(
                String label,
                String placeholder,
                String help,
                Boolean readOnly,
                SelectionFields.Presentation selection,
                FieldBehavior behavior,
                FormFills.Binding fill) {
            this(label, placeholder, help, readOnly, selection, behavior, fill, null);
        }

        public FieldPresentation(
                String label,
                String placeholder,
                String help,
                Boolean readOnly,
                SelectionFields.Presentation selection,
                FieldBehavior behavior) {
            this(label, placeholder, help, readOnly, selection, behavior, null);
        }

        public FieldPresentation(
                String label,
                String placeholder,
                String help,
                Boolean readOnly,
                SelectionFields.Presentation selection) {
            this(label, placeholder, help, readOnly, selection, null);
        }

        public FieldPresentation(String label, String placeholder, String help, Boolean readOnly) {
            this(label, placeholder, help, readOnly, null);
        }
    }

    /** 条件只引用同一表单可读字段；隐藏默认保留值，清空须显式配置。 */
    public record FieldBehavior(
            DocumentPolicy.Expression showWhen,
            DocumentPolicy.Expression requiredWhen,
            DocumentPolicy.Expression readOnlyWhen,
            boolean clearWhenHidden) {}

    public record View(
            String objectId,
            List<String> fieldIds,
            Map<String, Object> equal,
            String sortFieldId,
            boolean descending,
            int pageSize,
            String formId,
            Map<String, String> filterDictionaries,
            String detailPageId,
            ViewInteraction interaction,
            ViewList list,
            ViewQueryOptions query,
            DataViews.Composition composition) {
        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId,
                Map<String, String> filterDictionaries,
                String detailPageId,
                ViewInteraction interaction,
                ViewList list,
                ViewQueryOptions query) {
            this(
                    objectId,
                    fieldIds,
                    equal,
                    sortFieldId,
                    descending,
                    pageSize,
                    formId,
                    filterDictionaries,
                    detailPageId,
                    interaction,
                    list,
                    query,
                    null);
        }

        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId,
                Map<String, String> filterDictionaries,
                String detailPageId,
                ViewInteraction interaction,
                ViewList list) {
            this(
                    objectId,
                    fieldIds,
                    equal,
                    sortFieldId,
                    descending,
                    pageSize,
                    formId,
                    filterDictionaries,
                    detailPageId,
                    interaction,
                    list,
                    null);
        }

        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId,
                Map<String, String> filterDictionaries,
                String detailPageId,
                ViewInteraction interaction) {
            this(
                    objectId,
                    fieldIds,
                    equal,
                    sortFieldId,
                    descending,
                    pageSize,
                    formId,
                    filterDictionaries,
                    detailPageId,
                    interaction,
                    null);
        }

        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId,
                Map<String, String> filterDictionaries,
                String detailPageId) {
            this(
                    objectId,
                    fieldIds,
                    equal,
                    sortFieldId,
                    descending,
                    pageSize,
                    formId,
                    filterDictionaries,
                    detailPageId,
                    null);
        }

        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId,
                Map<String, String> filterDictionaries) {
            this(
                    objectId,
                    fieldIds,
                    equal,
                    sortFieldId,
                    descending,
                    pageSize,
                    formId,
                    filterDictionaries,
                    null);
        }

        public View(
                String objectId,
                List<String> fieldIds,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                int pageSize,
                String formId) {
            this(objectId, fieldIds, equal, sortFieldId, descending, pageSize, formId, Map.of());
        }
    }

    /** 可见按钮只是应用界面选择；实际操作与字段权限仍在公共后端校验。 */
    public record ViewList(
            List<String> queryFieldIds,
            List<String> advancedFieldIds,
            Map<String, Integer> columnWidths,
            boolean batchDelete,
            /**
             * 内容超出列宽时的显示方式：目前只有 ELLIPSIS（自动截断）；null 表示没有配置，沿用按字段类型的既有显示。
             * 没有配置时不写这个键，已保存、已发布的旧定义读出再写回逐字不变。不留少一个参数的兼容构造器：每个构造点都必须显式带上它。
             */
            @JsonInclude(JsonInclude.Include.NON_NULL) String overflow) {}

    /** 可见按钮只是应用界面选择；实际操作与字段权限仍在公共后端校验。 */
    public record ViewInteraction(
            List<String> buttons, List<String> actionIds, String editMode, String detailMode) {}

    public record Form(
            String objectId,
            List<Node> nodes,
            List<String> detailIds,
            FormOptions options,
            Map<String, List<Node>> detailNodes,
            List<RelatedForms.Binding> relatedForms) {
        public Form(
                String objectId,
                List<Node> nodes,
                List<String> detailIds,
                FormOptions options,
                Map<String, List<Node>> detailNodes) {
            this(objectId, nodes, detailIds, options, detailNodes, List.of());
        }

        public Form(
                String objectId, List<Node> nodes, List<String> detailIds, FormOptions options) {
            this(objectId, nodes, detailIds, options, Map.of());
        }

        public Form(String objectId, List<Node> nodes, List<String> detailIds) {
            this(objectId, nodes, detailIds, null);
        }
    }

    /** 默认身份只属于当前应用对象；缺省沿用旧表单语义，不按名称或资源顺序推断。 */
    public record FormOptions(
            String layout,
            String submitText,
            Boolean readOnly,
            Boolean relationLayout,
            Boolean defaultForObject) {
        public FormOptions {
            defaultForObject = Boolean.TRUE.equals(defaultForObject);
        }

        public FormOptions(
                String layout, String submitText, Boolean readOnly, Boolean relationLayout) {
            this(layout, submitText, readOnly, relationLayout, false);
        }

        public FormOptions(String layout, String submitText, Boolean readOnly) {
            this(layout, submitText, readOnly, false);
        }

        public FormOptions(String layout, String submitText) {
            this(layout, submitText, false);
        }
    }

    public record Page(
            List<Node> nodes,
            String contextObjectId,
            Integer protocolVersion,
            List<ApplicationReports.Filter> filters) {
        public Page(List<Node> nodes, String contextObjectId, Integer protocolVersion) {
            this(nodes, contextObjectId, protocolVersion, null);
        }

        public Page(List<Node> nodes) {
            this(nodes, null, null);
        }
    }

    /**
     * 页面入口设置；未带版本的旧菜单继续按原应用内导航解释。版本 2 只在应用发布时同步平台入口。 platformParentId 使用字符串传递底座长整数 ID，避免浏览器丢失精度。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Menu(
            String targetId,
            Integer navigationVersion,
            Boolean showInMenu,
            String platformParentId,
            String menuName,
            String icon,
            Integer sort,
            Boolean defaultHome) {
        public Menu(String targetId) {
            this(targetId, null, null, null, null, null, null, null);
        }
    }
}
