package com.lingan.ucp.nocode.runtime.service.record;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.runtime.dal.query.RecordStatement;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视图和报表共用的记录查询契约，仅供后端实现模块调用。
 *
 * <p>复用记录服务已有的模型、授权、范围和结果投影过程，不另设查询规则或事务。 记录写入及内部协作者仍由 RecordService 封装，不通过此入口暴露。
 */
@Service
public class RecordQueryAccess {
    @Resource private RecordContextResolver contexts;
    @Resource private RecordQueryService queries;
    @Resource private RecordReadService reader;
    @Resource private RecordSelectionSupport selections;
    @Resource private RecordTransactions transactions;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordPersistence persistence;
    @Resource private RuntimeSchema schemas;
    @Resource private DataObjectApi objects;
    @Resource private RecordLinkageSync linkageSync;

    /** 读取与普通记录入口相同的授权模型，供多对象视图组织分区。 */
    public Model model(String app, String object, long actor) {
        return queries.model(app, object, actor);
    }

    /** 授权、固定条件与用户筛选共用同一查询准备过程，供普通和多对象视图复用。 */
    public record QueryPlan(
            DataCenter.Definition definition,
            ApplicationRuntimePolicy.Access access,
            RecordStatement statement) {}

    /** 取得当前应用可见的固定对象版本。 */
    public DataCenter.Definition definition(String app, String object, long actor) {
        return contexts.definition(app, object, actor);
    }

    /** 按当前目录权限补充主表或明细行的选择标签。 */
    public List<Row> selectionLabels(
            String app, DataCenter.Definition d, List<Row> rows, long actor, String detailId) {
        return selections.selectionLabels(app, d, rows, actor, detailId);
    }

    /**
     * 单值引用的目标记录名称（记录 ID → 显示文本），与列表里显示引用名称同一条路：引用访问 + 关系配置的显示字段——目标对象不必被应用引用， 成员也不必有它的授权。取不到的 ID
     * 不在返回里；整体取不到（如目标对象已不可读）返回空。供多个数据来源的统计把引用字段维度显示成名称。
     */
    public Map<String, String> referenceLabels(
            String app,
            DataCenter.Definition owner,
            DataCenter.Relation relation,
            Collection<String> ids,
            long actor) {
        if (ids.isEmpty()) return Map.of();
        Map<String, String> labels = new LinkedHashMap<>();
        try {
            selections
                    .referenceLabels(
                            app,
                            owner,
                            relation,
                            contexts.readable(app, relation.targetObjectId(), actor),
                            ids,
                            actor)
                    .forEach((id, option) -> labels.put(id, option.label()));
        } catch (ServiceException e) {
            return Map.of();
        }
        return labels;
    }

    /** 合并对象模型、操作授权、上下文和固定视图条件，沿用统一的查询准备过程。 */
    public QueryPlan queryPlan(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView) {
        return queries.queryPlan(request, actor, operation, previewDefinition, fixedView);
    }

    /** 组合视图只把可信看板范围附到根记录；关联区块仍沿用原查询和授权。 */
    public QueryPlan queryPlan(
            Query request,
            long actor,
            ApplicationActionEnum operation,
            DataCenter.Definition previewDefinition,
            ApplicationUi.View fixedView,
            ReportStatement dashboardSource) {
        return queries.queryPlan(
                request,
                actor,
                operation,
                previewDefinition,
                fixedView,
                null,
                null,
                dashboardSource);
    }

    /** 按查询计划投影结果并裁剪字段；预览沿用原只读分支。 */
    public List<Row> queryRows(
            String app, QueryPlan plan, List<String> raw, long actor, boolean preview) {
        return queries.queryRows(app, plan, raw, actor, preview);
    }

    /** 报表复用页面关系及当前记录检查；业务保存仍只接受关联列表上下文。 */
    public RecordStatement reportScope(
            RecordStatement sql,
            String app,
            String object,
            String report,
            Context supplied,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        return contexts.reportScope(sql, app, object, report, supplied, access, actor);
    }

    /** 报表明细复用记录读取时的字段权限和结果转换。 */
    public List<Row> reportRows(
            String app,
            DataCenter.Definition d,
            List<String> raw,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        return reader.reportRows(app, d, raw, access, actor);
    }

    /**
     * 文件夹入口用：按当前操作者的授权读取一条记录。看不到时抛「记录不存在或不可访问」。 返回的 permissions().actions()
     * 反映这条记录上的真实授权，不叠加流程保护（业务方裁定：文件夹操作与审批状态无关）。
     *
     * <p>入口本身不通过（不是应用成员、对象不属于应用、没有对象的查看权限、不在数据维护上下文里）时沿用入口的原文案。
     */
    public Row authorizedRow(String app, String object, String id, long actor) {
        return transactions.tx(
                () -> {
                    var d = contexts.definition(app, object, actor);
                    var access = policy.access(app, d, actor);
                    try {
                        return persistence.authorizedRead(
                                schemas.main(d),
                                id,
                                actor,
                                false,
                                access,
                                ApplicationActionEnum.READ);
                    } catch (ServiceException hidden) {
                        // 记录不存在与「存在但这个人看不到」对外是同一句话，不透露是哪一种。
                        throw new ServiceException(NocodeErrorCodes.NOT_FOUND, "记录不存在或不可访问");
                    }
                });
    }

    /** 文件夹解析与命名用：按对象当前发布版本读取一条记录的存储值，不做授权裁剪；记录不存在返回 null。 ⛔ 调用方必须已凭别的记录完成授权；⛔ 返回值不得直接交给客户端。 */
    public Row storedRow(String object, String id, long actor) {
        var d = objects.getPublished(object);
        try {
            return persistence.read(schemas.main(d), id, null, actor, false);
        } catch (ServiceException missing) {
            if (missing.getCode() != null && missing.getCode() == NocodeErrorCodes.NOT_FOUND)
                return null;
            throw missing;
        }
    }

    /**
     * 补建与整对象补扫用：按对象当前发布版本、按主键升序取游标之后的 limit + 1 条存储值（多取的一条只用来判断还有没有下一页）。 系统读，不带操作者范围。⛔ 返回值不得交给客户端。
     */
    public List<Row> scanStored(String object, String cursor, int limit, long actor) {
        return transactions.tx(
                () -> linkageSync.scan(objects.getPublished(object), cursor, limit, actor));
    }
}
