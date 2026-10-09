package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.runtime.dal.query.RecordStatement;
import com.lingan.ucp.nocode.runtime.dal.query.TaskQueryScope;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 已发布任务视图与当前记录范围分开解析；固定条件只接受发布快照，始终与临时条件相交。 */
@Component
public class TaskPageQueries {
    @Resource private ApplicationPublishedService published;
    @Resource private TaskBusiness business;
    @Resource private RecordQueryAccess records;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ObjectMapper json;

    public TaskQueryScope resolve(PageQuery command, long actor, boolean candidates) {
        if (command == null) throw invalid("缺少页面上下文");
        ApplicationCenter.Published app = published.getCurrent(command.applicationId());
        policy.requireEntry(command.applicationId(), actor);
        ApplicationCenter.Resource pageResource = resource(app, command.pageId(), "PAGE");
        ApplicationUi.Page page =
                json.convertValue(pageResource.config(), ApplicationUi.Page.class);
        ApplicationUi.Node node = find(page.nodes(), command.nodeId());
        if (node == null || !"TASKS".equals(node.type())) throw invalid("页面任务区块不存在");
        String resourceId = TaskGraph.blank(node.resourceId());
        BusinessRef contextForm = resourceId == null ? null : form(app, resourceId, actor);
        String contextObjectId = TaskGraph.blank(page.contextObjectId());
        if (contextObjectId != null
                && contextForm != null
                && !contextObjectId.equals(contextForm.object().objectId()))
            throw invalid("当前记录区块必须绑定页面当前对象的表单");
        // 当前记录对象只信任发布页面；旧普通页带表单的记录查询仍沿用原对象定位。
        if (contextObjectId == null && contextForm != null)
            contextObjectId = contextForm.object().objectId();
        String recordId = TaskGraph.blank(command.recordId());
        if ((page.contextObjectId() != null || candidates) && recordId == null)
            throw invalid("此任务区块需要当前业务记录，不能展示全部任务");
        if (recordId != null && contextObjectId == null) throw invalid("此任务区块没有当前记录对象");
        RecordRef context =
                recordId == null
                        ? null
                        : new RecordRef(
                                app.application().id(),
                                contextObjectId,
                                recordId,
                                "记录 " + recordId);
        if (context != null) business.project(context, actor);
        ApplicationUi.TaskView view = node.taskView();
        String businessFormId = view == null ? null : TaskGraph.blank(view.businessFormId());
        if (businessFormId == null && context == null) businessFormId = resourceId;
        BusinessRef target = businessFormId == null ? null : form(app, businessFormId, actor);
        DynamicConditionDTO conditions =
                combine(view == null ? null : view.conditions(), command.conditions());
        ApplicationUi.TaskSort sorting = view == null ? null : view.sort();
        // 紧急程度已退出任务界面，旧发布配置不再隐式排序或过滤不可见字段。
        boolean retiredUrgencySort = sorting != null && "urgency".equals(sorting.field());
        String sort = sorting == null || retiredUrgencySort ? "expectedEnd" : sorting.field();
        boolean descending = sorting != null && !retiredUrgencySort && sorting.descending();
        String businessSort =
                sort != null && sort.startsWith("business:") ? sort.substring(9) : null;
        if (businessSort == null
                && !Set.of("title", "createdAt", "expectedEnd", "priority").contains(sort))
            throw invalid("任务排序字段无效");
        if ((conditions != null || businessSort != null) && target == null)
            throw invalid("此任务区块需配置业务表单后才能筛选或排序业务字段");
        RecordStatement statement = null;
        if (conditions != null || businessSort != null) {
            BusinessRef fixed = target;
            statement =
                    published.withVersion(
                            fixed.resource(),
                            () ->
                                    records.queryPlan(
                                                    new ApplicationRecords.Query(
                                                            app.application().id(),
                                                            fixed.object().objectId(),
                                                            1,
                                                            1,
                                                            null,
                                                            Map.of(),
                                                            businessSort,
                                                            descending,
                                                            null,
                                                            null,
                                                            conditions),
                                                    actor,
                                                    ApplicationActionEnum.READ,
                                                    null,
                                                    null)
                                            .statement());
        }
        ApplicationUi.TaskFilter filter = view == null ? null : view.taskFilter();
        // 普通页面始终限定所属应用；绑定业务表单只增加对象条件，不能把查询扩大到共享该对象的其他应用。
        // 记录页面继续按项目、业务记录和显式关联匹配，保留跨应用关联已有任务的能力。
        return new TaskQueryScope(
                context,
                context == null ? app.application().id() : null,
                target == null ? null : target.object().objectId(),
                statement,
                conditions != null,
                filter == null ? List.of() : list(filter.statuses()),
                List.of(),
                filter == null ? List.of() : list(filter.priorities()),
                filter == null ? null : filter.category(),
                view == null ? List.of() : list(view.templateIds()),
                businessSort == null ? sort : "BUSINESS",
                descending,
                candidates);
    }

    public RecordRef context(RecordContext context, long actor) {
        if (context == null) throw invalid("缺少关联记录上下文");
        TaskQueryScope scope =
                resolve(
                        new PageQuery(
                                context.applicationId(),
                                context.pageId(),
                                context.nodeId(),
                                context.recordId(),
                                null),
                        actor,
                        true);
        return scope.context();
    }

    private BusinessRef form(ApplicationCenter.Published app, String id, long actor) {
        ApplicationCenter.Resource resource = resource(app, id, "FORM");
        ApplicationUi.Form form = json.convertValue(resource.config(), ApplicationUi.Form.class);
        ApplicationCenter.ObjectReference object =
                app.definition().objects().stream()
                        .filter(ref -> ref.objectId().equals(form.objectId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("表单对象未引用到应用"));
        BusinessRef ref =
                new BusinessRef(
                        new PublishedResourceRef(
                                app.application().id(),
                                app.versionNo(),
                                app.checksum(),
                                resource.id(),
                                "FORM"),
                        object,
                        null,
                        null);
        business.model(ref, new Binding(app.application().id(), resource.id(), null), actor);
        return ref;
    }

    private ApplicationCenter.Resource resource(
            ApplicationCenter.Published app, String id, String kind) {
        return app.definition().resources().stream()
                .filter(r -> r.id().equals(id) && kind.equals(r.kind()))
                .findFirst()
                .orElseThrow(() -> invalid("已发布页面或表单不存在"));
    }

    private ApplicationUi.Node find(List<ApplicationUi.Node> nodes, String id) {
        if (nodes == null) return null;
        for (ApplicationUi.Node node : nodes) {
            if (Objects.equals(node.id(), id)) return node;
            ApplicationUi.Node child = find(node.children(), id);
            if (child != null) return child;
        }
        return null;
    }

    private List<String> list(List<String> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private DynamicConditionDTO combine(DynamicConditionDTO fixed, DynamicConditionDTO temporary) {
        if (fixed == null) return temporary;
        if (temporary == null) return fixed;
        DynamicConditionDTO result = new DynamicConditionDTO();
        result.setLogic(DynamicConditionDTO.Logic.AND);
        result.setItems(List.of(group(fixed), group(temporary)));
        return result;
    }

    private DynamicConditionDTO.Item group(DynamicConditionDTO source) {
        DynamicConditionDTO.Item result = new DynamicConditionDTO.Item();
        result.setType("group");
        result.setGroupLogic(source.getLogic());
        result.setGroupItems(source.getItems());
        return result;
    }
}
