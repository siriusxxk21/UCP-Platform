package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 已发布应用中的公共业务记录入口。普通应用、任务和流程适配沿用相同契约。 查询、读取、写入和删除交由职责组件处理；原事务、锁、版本和收据边界在组件中保持。 */
@Service("nocodeRecordService")
public class RecordService {
    @Resource private RecordContextResolver contexts;
    @Resource private RecordDeletionService deletions;
    @Resource private RecordQueryService queries;
    @Resource private RecordReadService reader;
    @Resource private RecordWriteService writer;

    @Resource private com.lingan.ucp.nocode.runtime.service.rules.FieldRuleService fieldRules;

    /** 写入与导入的选项校验里，挑取值来源按本应用固定版本解析。 */
    @Resource
    private com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog selectionCatalog;

    DataCenter.Definition definition(String app, String object, long actor) {
        return contexts.definition(app, object, actor);
    }

    /** 读取固定应用版本的运行模型，叠加当前完整性规则和实际字段/明细能力。 */
    public Model model(String app, String object, long actor) {
        return queries.model(app, object, actor);
    }

    /** 仅返回发布表单声明的关联填充值；来源记录和目标字段按当前行、字段授权裁剪。 */
    public Map<String, Object> formFill(FormFills.Query query, long actor) {
        return queries.formFill(query, actor);
    }

    /** 设计预览使用草稿固定的对象版本与未发布表单，来源记录和目标字段仍按实时授权裁剪。 */
    public Map<String, Object> previewFormFill(FormFills.PreviewQuery request, long actor) {
        return queries.previewFormFill(request, actor);
    }

    /** 数据联动与公式默认值求值：规则只取应用固定的对象版本，请求不接受任何配置；非 APPLIED 回状态与说明，不抛 500。 */
    public FieldRules.Evaluation evaluateRules(FieldRules.EvaluateQuery query, long actor) {
        return fieldRules.evaluate(query, actor);
    }

    /** 设计预览求值：对象版本取自服务端核验的草稿引用，权限仍按实时应用授权。 */
    public FieldRules.Evaluation previewRules(FieldRules.EvaluatePreview request, long actor) {
        return fieldRules.preview(request, actor);
    }

    /** 在表单与记录上下文中读取候选，保留固定范围、引用约束和当前选择权限。来源固定为授权应用版本，明细字段同时校验所属明细权限。 */
    public SelectionFields.Result selection(SelectionFields.Query query, long actor) {
        return queries.selection(query, actor);
    }

    /** 草稿预览只读；来源由服务端核验引用快照，权限仍使用实时应用授权与对象共享上限。 */
    public SelectionFields.Result previewSelection(
            SelectionFields.PreviewQuery request, long actor) {
        return queries.previewSelection(request, actor);
    }

    /** 分页读取可见记录，客户端条件与视图固定范围、授权条件共同生效。 */
    public PageResult<Row> page(Query request, long actor) {
        return queries.page(request, actor);
    }

    /** 统计下钻到数据视图：与 report-details 同一记录集，再叠加视图自身条件；另返回仅按下钻条件的条数。 */
    public ApplicationRecords.DrillPage drillPage(Query request, long actor) {
        return queries.drillPage(request, actor);
    }

    /** 应用固定看板下钻额外返回完整看板范围总数，用于解释视图自身条件造成的数量差异。 */
    public ApplicationRecords.DashboardDrillPage dashboardDrillPage(Query request, long actor) {
        return queries.dashboardDrillPage(request, actor);
    }

    /** 导出必须单独授权，查询范围在 SQL 内按 EXPORT 约束，不能先查 READ 再在客户端筛选。 */
    public List<Row> export(Query request, long actor) {
        return queries.export(request, actor);
    }

    /** 导入模型使用 IMPORT 操作授权，保留字段和明细能力限制。 */
    public Model importModel(String app, String object, long actor) {
        return queries.importModel(app, object, actor);
    }

    /** 首版导入新增主记录，整批同一事务。任何一行失败都回滚，用户修正原文件重试不会产生半批重复。 */
    public int importRecords(
            String app, String object, List<Map<String, Object>> rows, long actor) {
        return selectionCatalog.inApplication(
                app, null, () -> writer.importRecords(app, object, rows, actor));
    }

    public int importRecords(
            String app,
            String object,
            List<Map<String, Object>> rows,
            long actor,
            Context context) {
        return selectionCatalog.inApplication(
                app, null, () -> writer.importRecords(app, object, rows, actor, context));
    }

    /** 读取主记录及当前可见明细，返回实际可执行能力与记录修订。 */
    public Aggregate get(String app, String object, String id, long actor) {
        return reader.get(app, object, id, actor);
    }

    /** 内部编排读取可被删除的材料引用；复用完整的应用、对象、记录及字段授权，无独立 HTTP 入口。 */
    public Aggregate getIfPresent(String app, String object, String id, long actor) {
        return reader.getIfPresent(app, object, id, actor);
    }

    /**
     * 保存业务整单，已有记录必须满足预期修订。
     *
     * <p>存在独立关联区域输入时交由关联编排服务统一提交，各对象仍复用本服务的写入校验。 相同请求键按原收据恢复结果，不能重复生成业务记录、成功历史或已办。
     */
    public Aggregate save(Save command, long actor) {
        return selectionCatalog.inApplication(
                command == null ? null : command.applicationId(),
                null,
                () -> writer.save(command, actor));
    }

    /** 复用完整的写前校验。保存点回滚默认编号等预备写入，不触发业务写入和成功历史。 */
    public Aggregate prepareHandling(Save command, long actor) {
        return selectionCatalog.inApplication(
                command == null ? null : command.applicationId(),
                null,
                () -> writer.prepareHandling(command, actor));
    }

    /** 工作草稿复用正式写入的逐记录操作授权，不把 CREATE 的字段范围交叉用于 UPDATE。 */
    public ApplicationAuthorization.Capabilities workWriteCapabilities(
            String app, String object, String id, long actor) {
        return writer.workWriteCapabilities(app, object, id, actor);
    }

    /** 草稿只校验已经填写的引用和选项；不求默认值、不校验缺失必填项、不写业务表。 */
    public void validateWorkDraftReferences(
            String app,
            String object,
            String id,
            String formId,
            Map<String, Object> input,
            long actor) {
        selectionCatalog.inApplication(
                app,
                () -> writer.validateWorkDraftReferences(app, object, id, formId, input, actor));
    }

    /** 填写草稿可缺必填值，但不得伪造明细归属、版本或已填写的对象引用。 */
    public void validateWorkDraftDetails(
            String app, String object, String parent, Map<String, List<Row>> groups, long actor) {
        selectionCatalog.inApplication(
                app, () -> writer.validateWorkDraftDetails(app, object, parent, groups, actor));
    }

    /** 按当前操作者和原请求键读取保存收据，结果再次经过当前权限裁剪，不会再次写入。不存在收据时客户端应保留原键重试或继续查询。 */
    public SaveReceipt receipt(String app, String object, String key, long actor) {
        return reader.receipt(app, object, key, actor);
    }

    /** 导入转换复用授权后的对象字段来源，不接受客户端传入目录或字典配置。 */
    public Object selectionImportValue(
            String app, String object, FieldDefinition field, Object value, long actor) {
        return queries.selectionImportValue(app, object, field, value, actor);
    }

    /** 删除当前可操作记录，复用原事务中的流程保护、引用策略、修订和历史记录。 */
    public void delete(Delete command, long actor) {
        // 置空引用会走公共保存，挑取值校验需要本应用的固定版本上下文。
        selectionCatalog.inApplication(
                command == null ? null : command.applicationId(),
                () -> deletions.delete(command, actor));
    }

    /** 动作取自当前不可变发布版本，客户端只能指定动作与记录。动作产生的变化仍经过公共保存校验。 */
    public Aggregate execute(ApplicationBusiness.Execute command, long actor) {
        return selectionCatalog.inApplication(
                command == null ? null : command.applicationId(),
                null,
                () -> writer.execute(command, actor));
    }

    /** 流程业务键只能解析到当前有权读取的应用记录。 */
    public ProcessRecord processRecord(String businessKey, long actor) {
        return reader.processRecord(businessKey, actor);
    }
}
