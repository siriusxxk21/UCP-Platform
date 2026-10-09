package com.lingan.ucp.nocode.runtime.service.maintenance;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper;
import com.lingan.ucp.nocode.runtime.service.record.DocumentReceipts;
import com.lingan.ucp.nocode.runtime.service.record.RecordMaintenanceChecks;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import jakarta.annotation.*;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** 管理授权只在本入口生效；查询、写入、计算、状态保护和自动更新均沿用公共记录服务。 */
@Service
public class ObjectDataMaintenanceServiceImpl implements ObjectDataMaintenanceService {
    @Resource private PermissionCommonApi permissions;
    @Resource private DataObjectApi objects;
    @Resource private DatabaseMetadataReader database;
    @Resource private RecordService records;
    @Resource private DocumentReceipts receipts;
    @Resource private RecordMaintenanceChecks deletionChecks;
    @Resource private ObjectColumnMaintenance columns;
    @Resource private ObjectMaintenanceMapper locks;
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    @Override
    public <T> T read(long actor, Supplier<T> work) {
        requireMaintenance(actor);
        return transaction.execute(status -> ObjectMaintenanceScope.run(actor, work));
    }

    private <T> T authorized(long actor, Supplier<T> work) {
        requireMaintenance(actor);
        return transaction.execute(
                status -> {
                    catalog.lock(false);
                    locks.lockDefinitions();
                    return ObjectMaintenanceScope.run(actor, work);
                });
    }

    /** 维护上下文只接受已通过管理权限验证的账号；读入口不获取结构锁，写入口仍需独占锁。 */
    private void requireMaintenance(long actor) {
        if (actor <= 0
                || !permissions.hasAnyPermissions(actor, "nocode:object:query")
                || !permissions.hasAnyPermissions(actor, "nocode:object:manage"))
            throw new AccessDeniedException("需要数据对象管理权限才能维护对象数据");
    }

    private DataObjectApi.PublishedObject version(String objectId) {
        if (objectId == null || !objectId.matches("[1-9][0-9]*")) throw invalid("数据对象标识无效");
        return objects.getVersion(objectId, null);
    }

    private void requireVersion(String objectId, int versionNo, String checksum) {
        DataObjectApi.PublishedObject current = version(objectId);
        if (current.versionNo() != versionNo || !Objects.equals(current.checksum(), checksum))
            throw new com.lingan.ucp.framework.common.exception.ServiceException(
                    CONFLICT, "对象已发布新版本，请刷新对象数据后重新操作；尚未保存的输入可先保留");
    }

    @Override
    public ObjectDataMaintenance.Model model(String objectId, long actor) {
        return authorized(
                actor,
                () -> {
                    DataObjectApi.PublishedObject published = version(objectId);
                    ApplicationRecords.Model model = records.model(null, objectId, actor);
                    Map<String, String> reasons = new LinkedHashMap<>();
                    for (FieldDefinition field : model.object().fields()) {
                        DataCenter.FieldOptions option =
                                model.object()
                                        .fieldOptions()
                                        .getOrDefault(
                                                field.id(), DataCenter.FieldOptions.defaults());
                        if (FieldTypeEnum.fromCode(field.type()).isComputed())
                            reasons.put(field.id(), "由计算规则生成");
                        else if (FieldTypeEnum.AUTO_NUMBER.matches(field.type())
                                || option.autoNumber() != null)
                            reasons.put(field.id(), "由对象自动编号规则生成");
                        else if (option.generated()
                                        && model.object().relations().stream()
                                                .noneMatch(
                                                        relation ->
                                                                Objects.equals(
                                                                        relation.fieldId(),
                                                                        field.id()))
                                || model.generatedKey()
                                        && Objects.equals(model.keyFieldId(), field.id()))
                            reasons.put(field.id(), "由系统或数据库生成");
                        else if (Objects.equals(model.keyFieldId(), field.id()))
                            reasons.put(field.id(), "主键创建后不能修改");
                    }
                    model.managedFieldIds().forEach(id -> reasons.put(id, "由已发布自动更新规则维护"));
                    DocumentPolicy policy = DocumentPolicies.policy(model.object());
                    if (policy != null && policy.lifecycle() != null)
                        reasons.put(policy.lifecycle().fieldId(), "状态由业务动作维护");
                    return new ObjectDataMaintenance.Model(
                            published.versionNo(),
                            published.checksum(),
                            model,
                            reasons,
                            model.writable() ? null : "当前对象绑定为只读，请先检查对象的表绑定及写入能力",
                            columnTypes(model.object()));
                });
    }

    /** 按已发布绑定读取真实数据库类型；草稿类型不能冒充已部署列，缺失时不推测。 */
    private Map<String, String> columnTypes(DataCenter.Definition definition) {
        Map<String, String> result = new LinkedHashMap<>();
        TableBinding main = ObjectTables.main(definition);
        Map<String, String> mainColumns = physicalTypes(main.schemaName(), definition.tableName());
        result.put("__id", mainColumns.getOrDefault(main.keyColumn(), "物理列未找到"));
        appendColumnTypes(result, definition.fields(), definition.fieldOptions(), mainColumns);
        for (DataCenter.Detail detail : definition.details()) {
            if (!MemberStateEnum.ACTIVE.matches(detail.state())) continue;
            TableBinding binding = ObjectTables.detail(definition, detail);
            appendColumnTypes(
                    result,
                    detail.fields(),
                    detail.fieldOptions(),
                    physicalTypes(binding.schemaName(), detail.tableName()));
        }
        return result;
    }

    private Map<String, String> physicalTypes(String schema, String tableName) {
        Map<String, String> result = new LinkedHashMap<>();
        database.readTable(schema, tableName)
                .ifPresent(
                        table -> {
                            for (DatabaseMetadata.Column column : table.columns())
                                result.put(column.name(), column.nativeType());
                        });
        return result;
    }

    private void appendColumnTypes(
            Map<String, String> result,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            Map<String, String> physical) {
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions option = options.get(field.id());
            String column =
                    option == null || option.columnName() == null
                            ? field.code()
                            : option.columnName();
            result.put(field.id(), physical.getOrDefault(column, "物理列未找到"));
        }
    }

    @Override
    public PageResult<ApplicationRecords.Row> page(ObjectDataMaintenance.Query query, long actor) {
        return authorized(
                actor,
                () -> {
                    if (query == null
                            || query.pageNo() < 1
                            || query.pageSize() < 1
                            || query.pageSize() > 100
                            || query.pageNo() > 100000) throw invalid("分页参数无效");
                    version(query.objectId());
                    if (query.recordIds() != null && !query.recordIds().isEmpty()) {
                        if (query.recordIds().size() > 100) throw invalid("一次最多定位100条检查记录");
                        if (query.search() != null && !query.search().isBlank()
                                || query.equal() != null && !query.equal().isEmpty()
                                || query.conditions() != null
                                || query.sortFieldId() != null)
                            throw invalid("定位检查记录时请先清除定位范围再使用筛选或排序");
                        List<String> ids = query.recordIds().stream().distinct().toList();
                        List<ApplicationRecords.Row> found = new ArrayList<>();
                        for (String id : ids) {
                            try {
                                found.add(records.get(null, query.objectId(), id, actor).record());
                            } catch (
                                    com.lingan.ucp.framework.common.exception.ServiceException
                                            missing) {
                                if (missing.getCode() != NOT_FOUND) throw missing;
                            }
                        }
                        int start = Math.min(found.size(), (query.pageNo() - 1) * query.pageSize());
                        return new PageResult<>(
                                found.subList(
                                        start, Math.min(found.size(), start + query.pageSize())),
                                (long) found.size());
                    }
                    return records.page(
                            new ApplicationRecords.Query(
                                    null,
                                    query.objectId(),
                                    query.pageNo(),
                                    query.pageSize(),
                                    query.search(),
                                    query.equal(),
                                    query.sortFieldId(),
                                    query.descending(),
                                    null,
                                    null,
                                    query.conditions()),
                            actor);
                });
    }

    @Override
    public ApplicationRecords.Aggregate get(String objectId, String id, long actor) {
        return authorized(
                actor,
                () -> {
                    version(objectId);
                    return records.get(null, objectId, id, actor);
                });
    }

    @Override
    public ApplicationRecords.Aggregate save(ObjectDataMaintenance.Save request, long actor) {
        return authorized(
                actor,
                () -> {
                    if (request == null) throw invalid("缺少保存内容");
                    if (request.requestKey() == null || request.requestKey().isBlank())
                        throw invalid("缺少保存请求标识，请刷新页面后重试");
                    // 已成功请求先由公共保存核对摘要并恢复收据，避免响应丢失后新发布阻断幂等恢复。
                    // 无成功收据的新写入仍必须匹配当前发布结构。
                    if (receipts.find(null, request.objectId(), request.requestKey(), actor)
                            == null)
                        requireVersion(request.objectId(), request.versionNo(), request.checksum());
                    return records.save(
                            new ApplicationRecords.Save(
                                    null,
                                    request.objectId(),
                                    request.id(),
                                    request.expectedRevision(),
                                    request.values(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    request.requestKey(),
                                    null,
                                    null),
                            actor);
                });
    }

    @Override
    public SelectionFields.Result selection(ObjectDataMaintenance.Selection request, long actor) {
        return authorized(
                actor,
                () -> {
                    if (request == null) throw invalid("缺少候选查询内容");
                    version(request.objectId());
                    return records.selection(
                            new SelectionFields.Query(
                                    null,
                                    request.objectId(),
                                    request.detailId(),
                                    request.fieldId(),
                                    request.search(),
                                    request.pageNo(),
                                    request.pageSize(),
                                    request.selected(),
                                    request.recordId(),
                                    null,
                                    null,
                                    request.creating(),
                                    null),
                            actor);
                });
    }

    @Override
    public ObjectDataMaintenance.DeletePreview previewDelete(
            ObjectDataMaintenance.Delete request, long actor) {
        return authorized(
                actor,
                () -> {
                    if (request == null) throw invalid("缺少删除记录");
                    requireVersion(request.objectId(), request.versionNo(), request.checksum());
                    return deletionChecks.preview(request, actor);
                });
    }

    @Override
    public void delete(ObjectDataMaintenance.Delete request, long actor) {
        authorized(
                actor,
                () -> {
                    if (request == null) throw invalid("缺少删除记录");
                    requireVersion(request.objectId(), request.versionNo(), request.checksum());
                    ObjectDataMaintenance.DeletePreview preview =
                            deletionChecks.preview(request, actor);
                    if (!preview.allowed()) throw invalid(preview.message());
                    if (request.impactToken() == null
                            || !Objects.equals(request.impactToken(), preview.impactToken()))
                        throw new com.lingan.ucp.framework.common.exception.ServiceException(
                                CONFLICT, "删除影响已变化，请重新检查并确认");
                    records.delete(
                            new ApplicationRecords.Delete(
                                    null,
                                    request.objectId(),
                                    request.id(),
                                    request.expectedRevision()),
                            actor);
                    return null;
                });
    }

    @Override
    public ObjectDataMaintenance.ClearColumnPreview previewClearColumn(
            ObjectDataMaintenance.ClearColumn request, long actor) {
        return authorized(
                actor,
                () -> {
                    if (request == null) throw invalid("缺少清空字段内容");
                    requireVersion(request.objectId(), request.versionNo(), request.checksum());
                    return columns.preview(request, actor);
                });
    }

    @Override
    public ObjectDataMaintenance.ClearColumnResult clearColumn(
            ObjectDataMaintenance.ClearColumn request, long actor) {
        return authorized(
                actor,
                () -> {
                    if (request == null
                            || request.impactToken() == null
                            || request.impactToken().isBlank()) throw invalid("请先检查清空影响并确认");
                    requireVersion(request.objectId(), request.versionNo(), request.checksum());
                    return columns.clear(request, actor);
                });
    }
}
