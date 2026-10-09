package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.dal.query.*;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 固定应用版本与关联页面上下文解析；当前记录授权不替代目标记录授权。 */
@Component
public class RecordContextResolver {
    @Resource private RecordPersistence persistence;
    @Resource private ApplicationService applications;

    @Resource
    private com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator
            resourceValidator;

    @Resource private ApplicationRuntimePolicy policy;
    @Resource private DataObjectApi objects;
    @Resource private com.richuang.os.nocode.application.service.sharing.ImpliedObjects implied;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordRelations relations;
    @Resource private RecordMapper records;

    @Resource
    private com.richuang.os.nocode.application.service.resource.ApplicationPageBindings
            pageBindings;

    DataCenter.Definition definition(String app, String object, long actor) {
        if (com.richuang.os.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                app, actor)) return objects.getPublished(object);
        policy.requireEntry(app, actor);
        var release = applications.published(app);
        var ref =
                release.definition().objects().stream()
                        .filter(r -> r.objectId().equals(object))
                        .findFirst()
                        .orElseThrow(() -> invalid("该对象不属于应用的已发布版本"));
        var version = objects.getVersion(object, ref.versionNo());
        if (!version.checksum().equals(ref.checksum())) throw invalid("对象版本校验失败");
        return version.definition();
    }

    /**
     * 解析「别的对象的关系目标、规则来源、计算来源」时用：显式引用 ⇒ 应用固定的版本（与 {@link #definition} 相同）；应用没有引用、但因关联而隐式可读 ⇒
     * 对象的最新发布版；否则按 definition 的原文案拒绝。
     *
     * <p>只给「读别的对象来完成本对象的配置」的调用点用（引用候选、名称回显、保存时核对所选记录、联动与引用筛选取数）。把对象当它自己用的入口
     * （列表、详情、保存、删除、导入导出、统计主对象、子表、关联录入）一律走 definition：没被引用的对象不能被当成独立对象读写。
     */
    DataCenter.Definition readable(String app, String object, long actor) {
        if (com.richuang.os.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                app, actor)) return objects.getPublished(object);
        policy.requireEntry(app, actor);
        ApplicationCenter.Published release = applications.published(app);
        ApplicationCenter.ObjectReference ref =
                release.definition().objects().stream()
                        .filter(r -> r.objectId().equals(object))
                        .findFirst()
                        .orElse(null);
        if (ref != null) {
            DataObjectApi.PublishedObject version = objects.getVersion(object, ref.versionNo());
            if (!version.checksum().equals(ref.checksum())) throw invalid("对象版本校验失败");
            return version.definition();
        }
        DataCenter.Definition latest = implied.implied(app, object) ? implied.latest(object) : null;
        if (latest == null) throw invalid("该对象不属于应用的已发布版本");
        return latest;
    }

    record PageRecord(
            com.richuang.os.nocode.application.service.resource.ApplicationPageBindings.Resolved
                    binding,
            Row current) {}

    /** 请求只提供页面/节点/记录标识，关系和目标取自发布版本；读取根记录不等于授予目标数据权限。 */
    PageRecord context(
            String app, String object, String viewId, Context context, long actor, boolean lock) {
        return context(app, object, viewId, context, actor, lock, false);
    }

    PageRecord context(
            String app,
            String object,
            String viewId,
            Context context,
            long actor,
            boolean lock,
            boolean reporting) {
        if (context == null) return null;
        var resources = applications.published(app).definition().resources();
        var composed =
                resources.stream()
                        .filter(
                                r ->
                                        Objects.equals(r.id(), context.pageId())
                                                && ApplicationResourceKindEnum.VIEW.matches(
                                                        r.kind()))
                        .findFirst();
        if (composed.isPresent()) {
            var view = resourceValidator.decode(composed.get().config(), ApplicationUi.View.class);
            if (view.composition() == null) throw invalid("视图未配置关联子表");
            var section =
                    view.composition().sections().stream()
                            .filter(s -> s.id().equals(context.nodeId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("视图关联子表不存在"));
            if (section.detailId() != null
                    || !Objects.equals(section.objectId(), object)
                    || viewId != null && !Objects.equals(section.viewId(), viewId))
                throw invalid("关联子表上下文不匹配");
            var binding =
                    pageBindings.resolve(
                            view.objectId(),
                            object,
                            section.binding(),
                            id -> definition(app, id, actor));
            var current =
                    persistence.authorizedRead(
                            schemas.main(binding.current()),
                            context.recordId(),
                            actor,
                            lock,
                            policy.access(app, binding.current(), actor),
                            ApplicationActionEnum.READ);
            return new PageRecord(binding, current);
        }
        var resource =
                resources.stream()
                        .filter(
                                r ->
                                        Objects.equals(r.id(), context.pageId())
                                                && ApplicationResourceKindEnum.PAGE.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联页面未发布或不存在"));
        var page = resourceValidator.decode(resource.config(), ApplicationUi.Page.class);
        var node = pageBindings.node(page.nodes(), context.nodeId());
        if (node == null
                || !(ApplicationNodeKindEnum.RELATED.matches(node.type())
                        || reporting && ApplicationNodeKindEnum.REPORT.matches(node.type())))
            throw invalid("页面关联区块不存在");
        if (viewId != null && !Objects.equals(viewId, node.resourceId()))
            throw invalid("关联区块与查询视图不匹配");
        var viewResource =
                resources.stream()
                        .filter(
                                r ->
                                        Objects.equals(r.id(), node.resourceId())
                                                && (ApplicationResourceKindEnum.VIEW.matches(
                                                                r.kind())
                                                        || reporting
                                                                && ApplicationResourceKindEnum
                                                                        .REPORT
                                                                        .matches(r.kind())))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联列表视图不存在"));
        String targetObject =
                ApplicationResourceKindEnum.REPORT.matches(viewResource.kind())
                        ? resourceValidator
                                .decode(viewResource.config(), ApplicationReports.Config.class)
                                .objectId()
                        : resourceValidator
                                .decode(viewResource.config(), ApplicationUi.View.class)
                                .objectId();
        if (!Objects.equals(targetObject, object)) throw invalid("关联区块的目标对象不匹配");
        var binding =
                pageBindings.resolve(
                        page.contextObjectId(),
                        object,
                        node.binding(),
                        id -> definition(app, id, actor));
        var current =
                persistence.authorizedRead(
                        schemas.main(binding.current()),
                        context.recordId(),
                        actor,
                        lock,
                        policy.access(app, binding.current(), actor),
                        ApplicationActionEnum.READ);
        return new PageRecord(binding, current);
    }

    RecordStatement scope(
            RecordStatement sql,
            PageRecord context,
            ApplicationRuntimePolicy.Access targetAccess,
            long actor) {
        var binding = context.binding();
        var relation = binding.relation();
        boolean incoming = binding.direction() == RelationDirectionEnum.INCOMING;
        if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
            var caps =
                    incoming
                            ? targetAccess.forRow(
                                    targetAccess.all(ApplicationActionEnum.READ)
                                            ? null
                                            : Long.toString(actor))
                            : context.current().permissions();
            if (!caps.readRelations().contains(relation.id())) throw invalid("没有此关联关系的查看权限");
            var link =
                    relations.statement(
                            binding.source(),
                            relation,
                            incoming ? null : context.current().id(),
                            incoming ? context.current().id() : null,
                            actor);
            return sql.scope(
                    null,
                    null,
                    null,
                    new RelationScope(
                            link.schema(),
                            link.table(),
                            incoming ? "source_id" : "target_id",
                            incoming ? "target_id" : "source_id",
                            context.current().id()));
        }
        if (incoming) {
            if (!targetAccess.queryFields().contains(relation.fieldId()))
                throw invalid("没有关联字段的查看权限");
            var column = schemas.main(binding.target()).columns().get(relation.fieldId());
            if (column == null) throw invalid("关联字段未映射到物理列");
            return sql.scope(null, column, context.current().id(), null);
        }
        if (!context.current().permissions().readFields().contains(relation.fieldId()))
            throw invalid("没有当前记录关联字段的查看权限");
        return sql.scope(
                Objects.toString(context.current().values().get(relation.fieldId()), ""),
                null,
                null,
                null);
    }

    /** 报表复用页面关系及当前记录检查；业务保存仍只接受关联列表上下文。 */
    RecordStatement reportScope(
            RecordStatement sql,
            String app,
            String object,
            String report,
            Context supplied,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        var current = context(app, object, report, supplied, actor, false, true);
        return current == null ? sql : scope(sql, current, access, actor);
    }

    Map<String, Object> contextualValues(
            Save command,
            RuntimeSchema.Table table,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        if (command.values() == null) return null;
        var context =
                context(
                        command.applicationId(),
                        command.objectId(),
                        null,
                        command.context(),
                        actor,
                        true);
        if (context == null) return command.values();
        var relation = context.binding().relation();
        var input = new LinkedHashMap<>(command.values());
        if (command.id() != null) {
            var scoped =
                    scope(
                            table.statement(command.id(), null, Long.toString(actor), false),
                            context,
                            access,
                            actor);
            if (records.count(scoped) != 1) throw invalid("记录不属于当前关联列表");
        } else if (context.binding().direction() != RelationDirectionEnum.INCOMING
                || RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
            throw invalid("此关系请通过主记录的关联选择维护，不能隐式新建目标");
        }
        if (context.binding().direction() == RelationDirectionEnum.INCOMING
                && !RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
            if (input.containsKey(relation.fieldId())
                    && !Objects.equals(
                            Objects.toString(input.get(relation.fieldId()), null),
                            context.current().id())) throw invalid("不能在当前关联列表中修改记录归属");
            if (command.id() == null) input.put(relation.fieldId(), context.current().id());
        }
        return input;
    }
}
