package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.ObjectDraft;
import com.lingan.ucp.nocode.api.ObjectSummary;
import com.lingan.ucp.nocode.api.RecordTitles;
import com.lingan.ucp.nocode.api.SaveObjectDraft;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectTableDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectVersionDO;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 对象草稿应用服务：组织领域校验和完整保存，不创建连接或直接执行 SQL。
 *
 * <p>Mapper、数据源、分页插件和事务管理器全部使用底座配置。使用底座事务管理器创建 TransactionTemplate，以便把提交时才触发的延迟唯一约束也转换为统一业务异常。
 */
@Service
public class ObjectDraftService {
    @Resource private PlatformTransactionManager transactionManager;

    @Resource private ObjectDraftMapper draftMapper;
    @Resource private DatabaseMetadataReader databaseMetadata;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper jsonMapper;
    private TransactionTemplate transaction;

    /** 初始化对象草稿的 JSON 编解码与事务模板，草稿写入复用底座事务管理器。 */
    @PostConstruct
    void initialize() {
        this.jsonMapper = jsonMapper.copy().findAndRegisterModules();
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 对象管理分页；百分号和下划线按普通查询字符处理。 */
    public PageResult<ObjectSummary> page(int pageNo, int pageSize, String name, String code) {
        if (pageNo < 1 || pageNo > 1_000_000 || pageSize < 1 || pageSize > 100) {
            throw invalid("分页范围无效");
        }
        if ((name != null && name.length() > 128) || (code != null && code.length() > 64)) {
            throw invalid("查询条件过长");
        }
        Page<ObjectDraftHeadDO> page = new Page<>(pageNo, pageSize);
        draftMapper.selectObjectPage(page, like(name), like(code));
        List<ObjectSummary> rows =
                page.getRecords().stream()
                        .map(
                                head ->
                                        new ObjectSummary(
                                                head.getId().toString(),
                                                head.getObjectCode(),
                                                head.getObjectName(),
                                                head.getTableName(),
                                                head.getStatus(),
                                                head.getLockVersion(),
                                                head.getFieldCount(),
                                                head.getUpdateTime()
                                                        .atZone(
                                                                java.time.ZoneId.of(
                                                                        "Asia/Shanghai"))
                                                        .toOffsetDateTime()))
                        .toList();
        return new PageResult<>(rows, page.getTotal());
    }

    /** 共享锁保证读取字段期间不会混入另一修订的对象头。 */
    public ObjectDraft get(String id) {
        long objectId = validator.id(id, "对象 ID");
        return transaction.execute(status -> load(objectId));
    }

    public ObjectDraft create(SaveObjectDraft request, long actorId, UUID traceId) {
        return save(request, actorId, traceId, true);
    }

    public ObjectDraft update(SaveObjectDraft request, long actorId, UUID traceId) {
        return save(request, actorId, traceId, false);
    }

    private ObjectDraft load(long objectId) {
        return toDraft(requireHead(objectId, false));
    }

    private ObjectDraftHeadDO requireHead(long objectId, boolean writeLock) {
        ObjectDraftHeadDO head = draftMapper.selectHead(objectId, writeLock);
        // 排在「开新草稿」后面等对象头行锁时，对方提交改了最新版本号，PostgreSQL 重新核对联表条件会丢掉这一行；
        // 这不是对象不存在：同一事务里用一条新语句（新快照）重读一次，锁的对象、类型和取锁顺序都不变。
        if (head == null) head = draftMapper.selectHead(objectId, writeLock);
        if (head == null || Boolean.TRUE.equals(head.getDeleted())) {
            throw new ServiceException(NOT_FOUND, "数据对象不存在");
        }
        return head;
    }

    private ObjectDraft toDraft(ObjectDraftHeadDO head) {
        return new ObjectDraft(
                head.getId().toString(),
                head.getObjectCode(),
                head.getObjectName(),
                head.getDescription(),
                head.getTableName(),
                head.getTitleFieldStableId().toString(),
                head.getVersionState(),
                head.getLockVersion(),
                head.getLatestVersionNo(),
                head.getUpdateTime()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toOffsetDateTime(),
                draftMapper.selectFields(head.getVersionId()),
                head.getCategory());
    }

    private ObjectDraft save(SaveObjectDraft request, long actorId, UUID traceId, boolean create) {
        if (actorId <= 0 || traceId == null) {
            throw invalid("必须使用受信操作上下文");
        }
        try {
            return transaction.execute(
                    status -> {
                        draftMapper.lockTableName("nocode-design-write");
                        ObjectDraftHeadDO current =
                                create
                                        ? null
                                        : requireHead(validator.id(request.id(), "对象 ID"), true);
                        validator.basic(
                                request,
                                create,
                                current != null
                                        && ObjectSourceEnum.ADOPTED.matches(
                                                current.getSourceType()),
                                current == null ? null : current.getTableName());
                        return persist(request, actorId, traceId, create);
                    });
        } catch (DataIntegrityViolationException exception) {
            var error = new ServiceException(DUPLICATE, "对象编码或字段编码/物理列名冲突，请检查后重试");
            error.initCause(exception);
            throw error;
        }
    }

    /** 字段、Schema 和审计共同提交；任一步失败均由同一个事务回滚。 */
    private ObjectDraft persist(
            SaveObjectDraft request, long actorId, UUID traceId, boolean create) {
        ObjectDraftHeadDO head =
                create ? null : requireHead(validator.id(request.id(), "对象 ID"), true);
        if (head != null) {
            if (!VersionStateEnum.DRAFT.matches(head.getVersionState())
                    || Objects.equals(
                            head.getLatestVersionNo(), head.getCurrentPublishedVersionNo())
                    || !Set.of(ObjectStatusEnum.DRAFT.getCode(), ObjectStatusEnum.ACTIVE.getCode())
                            .contains(head.getStatus())) {
                throw new ServiceException(UNSUPPORTED_STATE, "请先为有效对象创建可编辑草稿");
            }
            if (!head.getLockVersion().equals(request.expectedLockVersion())) {
                throw new ServiceException(CONFLICT, "草稿已被其他人修改，请刷新后合并");
            }
            if ((head.getCurrentPublishedVersionNo() != null
                            || ObjectSourceEnum.ADOPTED.matches(head.getSourceType()))
                    && (!head.getTableName().equals(request.tableName())
                            || !head.getObjectCode().equals(request.objectCode()))) {
                throw invalid("已发布或纳管对象的编码和物理表名不可修改");
            }
        }
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        if (head != null) {
            draftMapper
                    .selectFields(head.getVersionId())
                    .forEach(field -> fields.put(field.id(), field));
        }
        Set<String> removals = validateRemovals(request, fields);
        Map<String, String> keyToId = mergeFields(request.fields(), removals, fields);
        removals.forEach(fields::remove);
        String titleId =
                validateTitleAndCodes(
                        request.titleFieldKey(), request.titleTemplate(), fields, keyToId);

        // 名称规范化后再锁定，防止不同空格写法绕过候选物理表名互斥。
        String tableName = request.tableName().trim();
        Long objectId = head == null ? null : head.getId();
        draftMapper.lockTableName("nocode-table:" + tableName);
        if ((head == null
                        || (head.getCurrentPublishedVersionNo() == null
                                && !ObjectSourceEnum.ADOPTED.matches(head.getSourceType())))
                && databaseMetadata.relationExists("public", tableName)) {
            throw new ServiceException(DUPLICATE, "目标物理表已存在，请在后续纳管功能中处理");
        }
        if (draftMapper.tableNameClaimed(tableName, objectId)) {
            throw new ServiceException(DUPLICATE, "候选物理表名已被其他对象占用");
        }

        int revision = head == null ? 0 : head.getLockVersion() + 1;
        NocodeObjectDO object = objectToSave(request, actorId, titleId, objectId);
        object.setCategory(
                com.lingan.ucp.nocode.api.ManagementCategories.normalize(
                        request.category() == null && head != null
                                ? head.getCategory()
                                : request.category()));
        if (create) {
            object.setPhysicalNameSeed(UUID.randomUUID().toString().replace("-", ""));
            object.setCreator(Long.toString(actorId));
            draftMapper.insert(object);
            objectId = object.getId();
        } else if (draftMapper.updateDraftHead(object, request.expectedLockVersion()) != 1) {
            throw new ServiceException(CONFLICT, "草稿版本冲突");
        }

        List<FieldDefinition> ordered =
                fields.values().stream()
                        .sorted(
                                Comparator.comparing(FieldDefinition::sort)
                                        .thenComparing(field -> Long.parseLong(field.id())))
                        .toList();
        String schema = schema(request, objectId, titleId, tableName, ordered);
        long versionId;
        long tableId;
        if (create) {
            NocodeObjectVersionDO version = new NocodeObjectVersionDO();
            version.setObjectId(objectId);
            version.setSchemaJson(schema);
            version.setSchemaChecksum(DigestUtil.sha256Hex(schema));
            version.setCreator(Long.toString(actorId));
            draftMapper.insertVersion(version);
            versionId = version.getId();
            NocodeObjectTableDO table = new NocodeObjectTableDO();
            table.setObjectVersionId(versionId);
            table.setStableTableId(draftMapper.nextStableId());
            table.setTableName(tableName);
            draftMapper.insertMainTable(table);
            tableId = table.getId();
        } else {
            versionId = head.getVersionId();
            tableId = head.getTableId();
            draftMapper.updateVersion(versionId, schema, DigestUtil.sha256Hex(schema), revision);
            draftMapper.updateMainTable(tableId, tableName);
        }
        for (String removed : removals) {
            draftMapper.deleteField(versionId, Long.parseLong(removed));
        }
        for (FieldDefinition field : ordered) {
            draftMapper.upsertField(versionId, tableId, field);
        }
        draftMapper.insertAudit(
                traceId.toString(),
                actorId,
                create
                        ? AuditOperationEnum.OBJECT_CREATE.getCode()
                        : AuditOperationEnum.OBJECT_DRAFT_SAVE.getCode(),
                objectId,
                json(
                        Map.of(
                                "fromRevision",
                                head == null ? -1 : head.getLockVersion(),
                                "toRevision",
                                revision,
                                "upsertFieldIds",
                                keyToId.values(),
                                "removedFieldIds",
                                removals)));
        return load(objectId);
    }

    /** 省略字段表示保留；只有 removedFieldIds 才能请求删除。 */
    private Set<String> validateRemovals(
            SaveObjectDraft request, Map<String, FieldDefinition> fields) {
        Set<String> removals = new HashSet<>();
        for (String id :
                request.removedFieldIds() == null ? List.<String>of() : request.removedFieldIds()) {
            validator.id(id, "删除字段 ID");
            if (!removals.add(id)) {
                throw invalid("删除字段 ID 重复");
            }
            if (!fields.containsKey(id)) {
                throw new ServiceException(FOREIGN_FIELD, "删除字段不属于当前对象");
            }
        }
        return removals;
    }

    /** 将新增临时 key 转为稳定 ID，禁止外来字段和同时修改/删除同一字段。 */
    private Map<String, String> mergeFields(
            List<FieldDefinition> changes,
            Set<String> removals,
            Map<String, FieldDefinition> fields) {
        Set<String> keys = new HashSet<>();
        Set<String> upsertIds = new HashSet<>();
        Map<String, String> keyToId = new HashMap<>();
        for (FieldDefinition field : changes) {
            if (field == null) {
                throw invalid("字段不能为空");
            }
            String key = validator.text(field.key(), "字段 key", 100);
            if (!keys.add(key)) {
                throw invalid("字段 key 重复");
            }
            String id = field.id();
            if (id != null) {
                validator.id(id, "字段 ID");
                if (!id.equals(key)) {
                    throw invalid("既有字段 key 必须等于其稳定 ID");
                }
                if (!fields.containsKey(id)) {
                    throw new ServiceException(FOREIGN_FIELD, "字段不属于当前对象");
                }
                if (!upsertIds.add(id) || removals.contains(id)) {
                    throw invalid("同一字段不能重复修改或同时删除");
                }
            } else {
                if (key.matches("[0-9]+")) {
                    throw invalid("新增字段 key 应使用临时标识");
                }
                id = String.valueOf(draftMapper.nextStableId());
            }
            keyToId.put(key, id);
            fields.put(id, validator.field(field, id));
        }
        return keyToId;
    }

    private String validateTitleAndCodes(
            String titleKey,
            String template,
            Map<String, FieldDefinition> fields,
            Map<String, String> keyToId) {
        if (fields.isEmpty() || fields.size() > 200) {
            throw invalid("对象必须有 1–200 个字段");
        }
        Set<String> codes = new HashSet<>();
        for (FieldDefinition field : fields.values()) {
            if (!codes.add(field.code())) {
                throw new ServiceException(DUPLICATE, "字段编码重复：" + field.code());
            }
        }
        // fields 已合并新增、修改与显式删除；省略的既有字段仍可供标题模板引用。
        RecordTitles.validate(template, List.copyOf(fields.values()));
        String titleId = keyToId.getOrDefault(titleKey, titleKey);
        FieldDefinition title = fields.get(titleId);
        if (title == null
                || (template == null || template.isBlank())
                        && !FieldTypeEnum.TEXT.matches(title.type())) {
            throw invalid("记录标题必须选择当前对象的单行文本字段");
        }
        if (template != null && !template.isBlank()) {
            var references =
                    java.util.regex.Pattern.compile("\\{\\{([a-z][a-z0-9_]*)}}").matcher(template);
            Set<String> referenced = new HashSet<>();
            while (references.find()) referenced.add(references.group(1));
            if (referenced.isEmpty()
                    || !codes.containsAll(referenced)
                    || references.reset().replaceAll("").contains("{{"))
                throw invalid("标题模板应使用当前字段，如 {{name}}，不允许脚本");
        }
        return titleId;
    }

    private NocodeObjectDO objectToSave(
            SaveObjectDraft request, long actorId, String titleId, Long objectId) {
        NocodeObjectDO object = new NocodeObjectDO();
        object.setId(objectId);
        object.setObjectCode(request.objectCode().trim());
        object.setObjectName(request.objectName().trim());
        object.setDescription(request.description());
        object.setTitleFieldStableId(Long.parseLong(titleId));
        object.setUpdater(Long.toString(actorId));
        return object;
    }

    /** 有序 Map 和字段排序确保同一结构得到可复现的摘要。 */
    private String schema(
            SaveObjectDraft request,
            long objectId,
            String titleId,
            String tableName,
            List<FieldDefinition> fields) {
        Map<String, Object> schema = new TreeMap<>();
        schema.put("schemaVersion", 1);
        schema.put("objectId", String.valueOf(objectId));
        schema.put("objectCode", request.objectCode().trim());
        schema.put("objectName", request.objectName().trim());
        schema.put("description", request.description());
        schema.put("tableName", tableName);
        schema.put("titleFieldId", titleId);
        schema.put("fields", fields);
        return json(schema);
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode object schema", exception);
        }
    }

    private String like(String input) {
        return "%"
                + Objects.toString(input, "")
                        .trim()
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                + "%";
    }
}
