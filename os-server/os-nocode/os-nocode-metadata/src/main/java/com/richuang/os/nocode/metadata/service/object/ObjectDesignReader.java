package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.metadata.service.table.TableBindingService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** 对象头、草稿定义、目录和固定版本读取；保留设计互斥锁与读取事务。 */
@Component
public class ObjectDesignReader {
    @Resource private ObjectDesignCodec designCodec;
    @Resource private ObjectDraftService drafts;
    @Resource private ObjectDraftMapper objects;
    @Resource private DataCenterMapper store;
    @Resource private DraftValidator validator;
    @Resource private TableBindingService bindings;
    @Resource private ObjectMapper json;
    private TransactionTemplate tx;
    @Resource private PlatformTransactionManager manager;

    @PostConstruct
    void initialize() {
        this.json = json.copy().findAndRegisterModules();
        this.tx = new TransactionTemplate(manager);
    }

    public ObjectDraftHeadDO head(String id, boolean lock) {
        if (lock) objects.lockTableName("nocode-design-write");
        var head = objects.selectHead(validator.id(id, "对象 ID"), lock);
        // 排在「开新草稿」后面等对象头行锁时，对方提交改了最新版本号，PostgreSQL 重新核对联表条件会丢掉这一行；
        // 这不是对象不存在：同一事务里用一条新语句（新快照）重读一次，锁的对象、类型和取锁顺序都不变。
        if (head == null) head = objects.selectHead(validator.id(id, "对象 ID"), lock);
        if (head == null || Boolean.TRUE.equals(head.getDeleted()))
            throw new ServiceException(NOT_FOUND, "数据对象不存在");
        return head;
    }

    public Design get(String id) {
        return tx.execute(s -> load(head(id, false)));
    }

    public com.richuang.os.framework.common.pojo.PageResult<ObjectRow> page(
            int pageNo,
            int pageSize,
            String name,
            String code,
            String status,
            String source,
            String owner,
            String category) {
        if (pageNo < 1 || pageNo > 10000 || pageSize < 1 || pageSize > 100) throw invalid("分页范围无效");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ObjectDraftHeadDO> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ObjectDraftHeadDO>(
                        pageNo, pageSize);
        store.selectDesignPage(
                page,
                like(name),
                like(code),
                status,
                source,
                owner,
                ManagementCategories.filter(category));
        return new com.richuang.os.framework.common.pojo.PageResult<>(
                page.getRecords().stream()
                        .map(
                                h ->
                                        new ObjectRow(
                                                h.getId().toString(),
                                                h.getObjectCode(),
                                                h.getObjectName(),
                                                h.getTableName(),
                                                h.getSchemaName(),
                                                h.getSourceType(),
                                                h.getStatus(),
                                                h.getCurrentPublishedVersionNo(),
                                                h.getFieldCount(),
                                                h.getDetailCount(),
                                                h.getRelationCount(),
                                                h.getLockVersion(),
                                                settings(h),
                                                h.getUpdateTime()
                                                        .atZone(
                                                                java.time.ZoneId.of(
                                                                        "Asia/Shanghai"))
                                                        .toOffsetDateTime(),
                                                h.getCategory()))
                        .toList(),
                page.getTotal());
    }

    /** 数据中心使用全局查询权限，目录沿用列表的可见边界。 */
    public List<String> categories() {
        return store.categories();
    }

    String like(String value) {
        if (value != null && value.length() > 128) throw invalid("查询条件过长");
        return "%"
                + Objects.toString(value, "")
                        .trim()
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                + "%";
    }

    Design load(ObjectDraftHeadDO h) {
        ObjectDraft draft = drafts.get(h.getId().toString());
        Map<String, FieldOptions> options = options(h.getVersionId());
        List<Detail> details = new ArrayList<>();
        for (com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Detail table :
                store.details(h.getVersionId())) {
            Map<String, Object> config =
                    designCodec.read(table.getConfigJson(), new TypeReference<>() {});
            List<FieldDefinition> fields = store.detailFields(table.getId());
            TreeMap<String, DataCenter.FieldOptions> detailOptions =
                    new TreeMap<String, FieldOptions>();
            fields.forEach(
                    f ->
                            detailOptions.put(
                                    f.id(), options.getOrDefault(f.id(), FieldOptions.defaults())));
            details.add(
                    new Detail(
                            table.getStableTableId().toString(),
                            table.getTableCode(),
                            Objects.toString(config.get("name"), table.getTableCode()),
                            table.getTableName(),
                            Objects.toString(config.get("state"), MemberStateEnum.ACTIVE.getCode()),
                            fields,
                            detailOptions,
                            List.of(),
                            Optional.ofNullable(bindings.read(table.getConfigJson()))
                                    .orElse(TableBinding.generated(h.getSchemaName(), true))));
        }
        Set<String> detailIds =
                details.stream()
                        .flatMap(d -> d.fields().stream())
                        .map(FieldDefinition::id)
                        .collect(Collectors.toSet());
        options.keySet()
                .retainAll(
                        draft.fields().stream()
                                .map(FieldDefinition::id)
                                .collect(Collectors.toSet()));
        return new Design(
                draft,
                settings(h),
                options,
                store.relations(h.getVersionId()),
                store.indexes(h.getVersionId()),
                h.getSourceType(),
                h.getSchemaName(),
                h.getCurrentPublishedVersionNo(),
                bindings.main(h).readOnly(),
                store.versions(h.getId()),
                store.dependencies(h.getId()),
                details,
                h.getStatus(),
                bindings.main(h));
    }

    Settings settings(ObjectDraftHeadDO h) {
        return h.getSettingsJson() == null
                ? Settings.defaults()
                : designCodec.read(h.getSettingsJson(), Settings.class);
    }

    Map<String, FieldOptions> options(long versionId) {
        Map<String, FieldOptions> result = new TreeMap<>();
        for (com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows.Field field :
                store.fieldOptions(versionId)) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        json.readTree(field.getConfigJson()).path("options");
                FieldOptions o =
                        node.isMissingNode()
                                ? FieldOptions.defaults()
                                : json.treeToValue(node, FieldOptions.class);
                // 列、分类、状态与生成标记以字段行为准，其余扩展配置（含 selection 与 rules）原样保留。
                result.put(
                        field.getStableFieldId().toString(),
                        FieldOptions.copyOf(o)
                                .columnName(field.getColumnName())
                                .classification(field.getDataClassification())
                                .state(field.getFieldState())
                                .options(o.options() == null ? List.of() : o.options())
                                .generated(
                                        field.getRelationGenerated()
                                                || Boolean.TRUE.equals(o.generated()))
                                .build());
            } catch (Exception ex) {
                throw new IllegalStateException("字段配置读取失败", ex);
            }
        }
        return result;
    }

    public ObjectDraftHeadDO requireRevision(Revision request) {
        if (request == null || request.expectedLockVersion() == null) throw invalid("对象及修订号必填");
        ObjectDraftHeadDO h = head(request.id(), true);
        if (!request.expectedLockVersion().equals(h.getLockVersion()))
            throw new ServiceException(CONFLICT, "对象已变化，请刷新后重试");
        return h;
    }

    public Definition definition(String id) {
        Design d = get(id);
        return new Definition(
                id,
                d.draft().objectCode(),
                d.draft().objectName(),
                d.draft().description(),
                d.schemaName(),
                d.draft().tableName(),
                d.source(),
                Boolean.TRUE.equals(d.readOnly()),
                d.draft().titleFieldId(),
                d.settings(),
                d.draft().fields(),
                d.fieldOptions(),
                d.relations(),
                d.indexes(),
                d.details(),
                d.mainBinding());
    }

    public Definition published(String id) {
        ObjectDraftHeadDO h = head(id, false);
        return h.getCurrentPublishedVersionNo() == null
                ? null
                : designCodec.read(
                        store.versionSchema(h.getId(), h.getCurrentPublishedVersionNo()),
                        Definition.class);
    }

    public String version(String id, int versionNo) {
        return tx.execute(
                s -> {
                    ObjectDraftHeadDO h = head(id, false);
                    String value = store.versionSchema(h.getId(), versionNo);
                    if (value == null) throw invalid("版本不存在");
                    return value;
                });
    }
}
