package com.lingan.ucp.nocode.runtime.service.bizfile;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.NOT_FOUND;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizFileContent;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.BusinessFileLabelStatusEnum;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.runtime.dal.mapper.BizFileBrowseMapper;
import com.lingan.ucp.nocode.runtime.dal.query.BizFileStatement;
import com.lingan.ucp.nocode.runtime.dal.query.RecordStatement;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.record.RecordCalculations;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

/**
 * 业务文件浏览 Service
 *
 * <p>单入口业务文件读取：目录、文件与统计都先在数据库按记录授权和字段/明细安全集过滤，再返回内容与数量，
 * 不先取回未授权行再丢弃。目录名称按当前访问者授权重新解析，名称来源不可读时只返回受限标签， 不返回后台保存时的目录名称；应用入口与数据维护入口各自沿用完整授权链，不相互拼接。
 *
 * <p>内容读取同样按位置身份与安全集定位绑定行后，再经常用节点侧校验打开内容流；任何一次读取都在请求内重验， 不凭节点编号或文件编号直接放行。
 *
 * <p>定位链与收藏/最近访问列表共用同一套编译：定位链只承载导航身份与按当前位置重解析的展示名；标记列表 的节点身份即使来自用户标记，也仍在入口授权链内重新过滤。
 */
@Service
public class BizFileBrowseService {

    /** 目录与文件分页上限，与记录查询保持一致 */
    private static final int MAX_PAGE_SIZE = 100;

    private static final int MAX_PAGE_NO = 10000;

    /** 文件名及业务标题搜索长度上限 */
    private static final int MAX_SEARCH_LENGTH = 200;

    /** 分组导航键长度上限；绑定表 group_keys 为 varchar(512) */
    private static final int MAX_GROUP_KEY_LENGTH = 512;

    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordQueryAccess records;
    @Resource private RuntimeSchema schemas;
    @Resource private BizFileBrowseMapper mapper;
    @Resource private BizFileDirectoryNamer namer;
    @Resource private RecordCalculations calculations;
    @Resource private BizFileBindingService bindings;
    @Resource private DriveBizFileApi driveFiles;
    @Resource private ObjectMapper json;
    @Resource private ObjectDataMaintenanceService maintenance;
    @Resource private ApplicationService applications;
    @Resource private DataObjectApi objects;
    @Resource private PlatformTransactionManager manager;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    /** 入口内业务空间：只列出当前授权下确有可见文件的对象；未接入或已全部无文件的对象不占入口 */
    public List<BusinessFiles.Space> spaces(BusinessFiles.EntryQuery request, long actor) {
        String applicationId = request == null ? null : request.applicationId();
        return read(applicationId, actor, () -> spaces(applicationId, actor));
    }

    /** 目录浏览：版本 → 分组 → 记录 → 附件字段/明细区 → 明细行 → 附件字段 */
    public PageResult<BusinessFiles.Directory> directories(
            BusinessFiles.DirectoryQuery request, long actor) {
        if (request == null) throw invalid("业务文件目录请求不能为空");
        requireObjectId(request.objectId());
        requirePage(request.pageNo(), request.pageSize());
        requireRuleVersion(request.ruleVersion());
        requireLocation(
                request.groupKeys(), request.recordId(), request.detailId(), request.rowId());
        return read(request.applicationId(), actor, () -> browse(request, actor));
    }

    /** 文件列表与可读业务标题/文件名搜索；范围与目录浏览一致，未选满分组层时在当前节点内搜索 */
    public PageResult<BusinessFiles.File> files(BusinessFiles.FileQuery request, long actor) {
        if (request == null) throw invalid("业务文件查询不能为空");
        requireObjectId(request.objectId());
        requirePage(request.pageNo(), request.pageSize());
        requireRuleVersion(request.ruleVersion());
        requireLocation(
                request.groupKeys(), request.recordId(), request.detailId(), request.rowId());
        return read(request.applicationId(), actor, () -> list(request, actor));
    }

    /** 内容读取：绑定位、记录授权、字段/明细安全集与节点身份全部通过后返回内容元信息 */
    public BusinessFiles.Content content(BusinessFiles.ContentQuery request, long actor) {
        if (request == null) throw invalid("业务文件内容请求不能为空");
        requireObjectId(request.objectId());
        requireContentLocation(request);
        return read(request.applicationId(), actor, () -> resolveContent(request, actor));
    }

    /** 打开受权内容流：授权链已由 content(...) 校验，节点侧再确认受管业务文件身份；调用方负责关闭 */
    public InputStream contentStream(BusinessFiles.Content content, long offset) {
        return driveFiles.openContent(content.entryId(), offset);
    }

    /**
     * 定位链：按内容读取同一位置身份返回从规则版本到附件字段的导航路径
     *
     * <p>供「在网盘中查看」等深链在当前入口内建立面包屑并逐级下钻；链上只承载导航身份与按当前访问者 解析的展示名，不重复统计数量（数量字段为 0），也不返回后台保存时的目录名称。
     */
    public List<BusinessFiles.Directory> locate(BusinessFiles.ContentQuery request, long actor) {
        if (request == null) throw invalid("业务文件定位请求不能为空");
        requireObjectId(request.objectId());
        requireContentLocation(request);
        return read(request.applicationId(), actor, () -> locatePath(request, actor));
    }

    /** 收藏与最近访问登记前的绑定重验：位置身份与内容读取一致，只确认可见绑定行，不打开内容 */
    public void requireBinding(BusinessFiles.ContentQuery request, long actor) {
        if (request == null) throw invalid("业务文件标记请求不能为空");
        requireObjectId(request.objectId());
        requireContentLocation(request);
        read(
                request.applicationId(),
                actor,
                () -> {
                    bindingRow(request, actor);
                    return Boolean.TRUE;
                });
    }

    /**
     * 按节点集合取当前可见文件
     *
     * <p>收藏/最近访问列表使用：节点身份来自用户标记，可能跨规则版本；可见性、标签与位置仍由入口完整 授权链重新过滤，失去授权或已移除的附件自然不返回。节点集合上限与分页上限一致。
     */
    public List<BusinessFiles.File> filesByEntries(
            String applicationId, String objectId, List<Long> entryIds, long actor) {
        if (entryIds == null || entryIds.isEmpty()) return List.of();
        List<Long> targets = entryIds.stream().distinct().limit(MAX_PAGE_SIZE).toList();
        return read(
                applicationId,
                actor,
                () -> {
                    FileScope files = scope(applicationId, objectId, actor);
                    List<BizFileStatement.Projected> labels = allLabelFields(files);
                    BizFileStatement statement =
                            statement(
                                            objectId,
                                            files.base(),
                                            null,
                                            List.of(),
                                            0,
                                            false,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            labels,
                                            files.fields(),
                                            files.details(),
                                            targets.size(),
                                            1)
                                    .withEntryIds(targets);
                    List<BusinessFiles.File> items = new ArrayList<>();
                    for (JsonNode row :
                            labelRows(
                                    mapper.files(statement),
                                    files.definition(),
                                    labels,
                                    applicationId,
                                    actor)) {
                        items.add(
                                file(
                                        row,
                                        files.definition(),
                                        files.policies().get(row.path("ruleVersion").asInt()),
                                        labels,
                                        applicationId,
                                        actor));
                    }
                    return items;
                });
    }

    /** 维护入口沿用数据对象管理授权，应用入口使用应用发布版本与运行授权 */
    private <T> T read(String applicationId, long actor, Supplier<T> work) {
        if (applicationId == null || applicationId.isBlank())
            return maintenance.read(actor, () -> transaction.execute(status -> work.get()));
        return transaction.execute(status -> work.get());
    }

    private List<BusinessFiles.Space> spaces(String applicationId, long actor) {
        policy.requireEntry(applicationId, actor);
        LinkedHashSet<String> objectIds = new LinkedHashSet<>();
        if (applicationId == null || applicationId.isBlank()) {
            objectIds.addAll(mapper.boundObjects());
        } else {
            applications.published(applicationId).definition().objects().stream()
                    .map(ApplicationCenter.ObjectReference::objectId)
                    .forEach(objectIds::add);
        }
        List<BusinessFiles.Space> result = new ArrayList<>();
        for (String objectId : objectIds) {
            BusinessFiles.Space space = space(applicationId, objectId, actor);
            if (space != null) result.add(space);
        }
        return result;
    }

    /** 单对象业务空间：数量为当前入口授权过滤后的可见值，规则已停用时沿用最近绑定版本展示 */
    private BusinessFiles.Space space(String applicationId, String objectId, long actor) {
        if (!policy.canRead(applicationId, objectId, actor)) return null;
        boolean maintenance = applicationId == null || applicationId.isBlank();
        DataCenter.Definition definition;
        try {
            definition = records.definition(applicationId, objectId, actor);
        } catch (ServiceException missing) {
            // 维护入口按已有绑定枚举对象，对象已删除或缺少可用版本时跳过
            if (maintenance) return null;
            throw missing;
        }
        ApplicationRuntimePolicy.Access access = policy.access(applicationId, definition, actor);
        RuntimeSchema.Table table = schemas.main(definition);
        Set<String> fields = access.queryFields(), details = access.queryDetails();
        JsonNode stats =
                node(
                        mapper.stats(
                                statement(
                                        objectId,
                                        base(table, access, actor),
                                        null,
                                        List.of(),
                                        0,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        fields,
                                        details,
                                        1,
                                        1)));
        int fileCount = stats.path("fileCount").asInt();
        if (fileCount == 0) return null;
        DataCenter.BusinessFilePolicy current = bindings.policyOf(definition);
        Integer currentRuleVersion = null;
        String spaceName = definition.objectName();
        List<String> fixedPath = List.of();
        if (DataCenter.BusinessFilePolicy.enabled(current)) {
            currentRuleVersion = currentVersion(objectId);
            spaceName = current.spaceName();
            fixedPath = current.fixedPath() == null ? List.of() : current.fixedPath();
        } else {
            DataCenter.BusinessFilePolicy latest = latestPinned(definition, access, actor);
            if (latest != null) {
                spaceName = latest.spaceName();
                fixedPath = latest.fixedPath() == null ? List.of() : latest.fixedPath();
            }
        }
        return new BusinessFiles.Space(
                objectId,
                definition.objectName(),
                spaceName,
                fixedPath,
                currentRuleVersion,
                fileCount,
                stats.path("totalSize").asLong(),
                stats.path("recordCount").asInt());
    }

    private PageResult<BusinessFiles.Directory> browse(
            BusinessFiles.DirectoryQuery request, long actor) {
        String applicationId = request.applicationId();
        String objectId = request.objectId();
        DataCenter.Definition definition = records.definition(applicationId, objectId, actor);
        ApplicationRuntimePolicy.Access access = policy.access(applicationId, definition, actor);
        RuntimeSchema.Table table = schemas.main(definition);
        RecordStatement base = base(table, access, actor);
        Set<String> fields = access.queryFields(), details = access.queryDetails();
        if (request.ruleVersion() == null)
            return versions(request, objectId, base, fields, details);
        DataCenter.BusinessFilePolicy pinned = pinnedPolicy(objectId, request.ruleVersion());
        List<DataCenter.BusinessFileGroup> groups =
                pinned.groups() == null ? List.of() : pinned.groups();
        List<String> keys = request.groupKeys() == null ? List.of() : request.groupKeys();
        if (keys.size() > groups.size()) throw invalid("业务分组导航位置无效");
        List<BizFileStatement.GroupFilter> filters = groupFilters(groups, keys, fields);
        if (request.recordId() == null) {
            if (keys.size() < groups.size())
                return groupLevel(
                        request, definition, groups, filters, base, fields, details, actor);
            return recordLevel(
                    request, definition, pinned, filters, table, base, fields, details, actor);
        }
        if (request.detailId() == null)
            return recordChildren(request, definition, filters, base, fields, details);
        if (request.rowId() == null) return rowLevel(request, filters, base, fields, details);
        return rowChildren(request, definition, filters, base, fields, details);
    }

    /** 规则版本根节点：客户端按 currentRuleVersion 直接下钻，多版本并存时才需要选择 */
    private PageResult<BusinessFiles.Directory> versions(
            BusinessFiles.DirectoryQuery request,
            String objectId,
            RecordStatement base,
            Set<String> fields,
            Set<String> details) {
        Integer current = currentVersion(objectId);
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (String raw :
                mapper.ruleVersions(
                        statement(
                                objectId, base, null, List.of(), 0, false, null, null, null, null,
                                null, List.of(), fields, details, 1, 1))) {
            JsonNode row = node(raw);
            int ruleVersion = row.path("ruleVersion").asInt();
            String label =
                    current != null && current == ruleVersion
                            ? "当前规则（v" + ruleVersion + "）"
                            : "历史规则（v" + ruleVersion + "）";
            items.add(
                    new BusinessFiles.Directory(
                            "VERSION",
                            ruleVersion,
                            null,
                            null,
                            null,
                            null,
                            null,
                            label,
                            false,
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            row.path("recordCount").asInt()));
        }
        return slice(items, request.pageNo(), request.pageSize());
    }

    /** 业务分组层：受限层级只返回与匹配摘要令牌，标签不能还原来源名称 */
    private PageResult<BusinessFiles.Directory> groupLevel(
            BusinessFiles.DirectoryQuery request,
            DataCenter.Definition definition,
            List<DataCenter.BusinessFileGroup> groups,
            List<BizFileStatement.GroupFilter> filters,
            RecordStatement base,
            Set<String> fields,
            Set<String> details,
            long actor) {
        int level = request.groupKeys().size() + 1;
        DataCenter.BusinessFileGroup group = groups.get(level - 1);
        boolean hashed = !fields.contains(group.fieldId());
        BizFileStatement statement =
                statement(
                        request.objectId(),
                        base,
                        request.ruleVersion(),
                        filters,
                        level,
                        hashed,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        fields,
                        details,
                        request.pageSize(),
                        request.pageNo());
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (String raw : mapper.groupDirectories(statement)) {
            JsonNode row = node(raw);
            String key = text(row, "groupKey");
            BizFileDirectoryNamer.Label label =
                    namer.groupView(definition, group, key, hashed, request.applicationId(), actor);
            items.add(
                    new BusinessFiles.Directory(
                            "GROUP",
                            request.ruleVersion(),
                            key,
                            null,
                            null,
                            null,
                            null,
                            label.text(),
                            label.restricted(),
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            row.path("recordCount").asInt()));
        }
        return new PageResult<>(items, mapper.countGroupDirectories(statement));
    }

    /** 记录目录层：标签来源只投影当前安全集可读字段，其余退化为受限标签 */
    private PageResult<BusinessFiles.Directory> recordLevel(
            BusinessFiles.DirectoryQuery request,
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy pinned,
            List<BizFileStatement.GroupFilter> filters,
            RuntimeSchema.Table table,
            RecordStatement base,
            Set<String> fields,
            Set<String> details,
            long actor) {
        List<BizFileStatement.Projected> labels = labelFields(definition, pinned, table, fields);
        BizFileStatement statement =
                statement(
                        request.objectId(),
                        base,
                        request.ruleVersion(),
                        filters,
                        0,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        labels,
                        fields,
                        details,
                        request.pageSize(),
                        request.pageNo());
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (JsonNode row :
                labelRows(
                        mapper.recordDirectories(statement),
                        definition,
                        labels,
                        request.applicationId(),
                        actor)) {
            String recordId = text(row, "recordId");
            BizFileDirectoryNamer.Label label =
                    recordLabel(
                            definition,
                            pinned,
                            row,
                            labels,
                            recordId,
                            request.applicationId(),
                            actor);
            items.add(
                    new BusinessFiles.Directory(
                            "RECORD",
                            request.ruleVersion(),
                            null,
                            recordId,
                            null,
                            null,
                            null,
                            label.text(),
                            label.restricted(),
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            1,
                            label.status().getCode()));
        }
        return new PageResult<>(items, mapper.countRecordDirectories(statement));
    }

    /** 记录内目录：附件字段目录与明细区目录；字段或明细已失效时不再显示节点 */
    private PageResult<BusinessFiles.Directory> recordChildren(
            BusinessFiles.DirectoryQuery request,
            DataCenter.Definition definition,
            List<BizFileStatement.GroupFilter> filters,
            RecordStatement base,
            Set<String> fields,
            Set<String> details) {
        BizFileStatement statement =
                statement(
                        request.objectId(),
                        base,
                        request.ruleVersion(),
                        filters,
                        0,
                        false,
                        request.recordId(),
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        fields,
                        details,
                        1,
                        1);
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (String raw : mapper.recordChildren(statement)) {
            JsonNode row = node(raw);
            String kind = text(row, "kind"), nodeId = text(row, "nodeId");
            boolean region = "REGION".equals(kind);
            String label =
                    region ? detailLabel(definition, nodeId) : fieldLabel(definition, nodeId);
            if (label == null) continue;
            items.add(
                    new BusinessFiles.Directory(
                            kind,
                            request.ruleVersion(),
                            null,
                            request.recordId(),
                            region ? nodeId : null,
                            null,
                            region ? null : nodeId,
                            label,
                            false,
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            1));
        }
        return new PageResult<>(items, (long) items.size());
    }

    /** 明细行层：行目录按持久行 ID 命名，排序变化不改变身份 */
    private PageResult<BusinessFiles.Directory> rowLevel(
            BusinessFiles.DirectoryQuery request,
            List<BizFileStatement.GroupFilter> filters,
            RecordStatement base,
            Set<String> fields,
            Set<String> details) {
        BizFileStatement statement =
                statement(
                        request.objectId(),
                        base,
                        request.ruleVersion(),
                        filters,
                        0,
                        false,
                        request.recordId(),
                        request.detailId(),
                        null,
                        null,
                        null,
                        List.of(),
                        fields,
                        details,
                        request.pageSize(),
                        request.pageNo());
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (String raw : mapper.regionChildren(statement)) {
            JsonNode row = node(raw);
            String rowId = text(row, "nodeId");
            items.add(
                    new BusinessFiles.Directory(
                            "ROW",
                            request.ruleVersion(),
                            null,
                            request.recordId(),
                            request.detailId(),
                            rowId,
                            null,
                            namer.rowLabel(rowId),
                            false,
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            1));
        }
        return new PageResult<>(items, mapper.countRegionChildren(statement));
    }

    /** 明细行内附件字段目录：明细字段按明细粒度授权，字段名仍取对象契约 */
    private PageResult<BusinessFiles.Directory> rowChildren(
            BusinessFiles.DirectoryQuery request,
            DataCenter.Definition definition,
            List<BizFileStatement.GroupFilter> filters,
            RecordStatement base,
            Set<String> fields,
            Set<String> details) {
        BizFileStatement statement =
                statement(
                        request.objectId(),
                        base,
                        request.ruleVersion(),
                        filters,
                        0,
                        false,
                        request.recordId(),
                        request.detailId(),
                        request.rowId(),
                        null,
                        null,
                        List.of(),
                        fields,
                        details,
                        1,
                        1);
        List<BusinessFiles.Directory> items = new ArrayList<>();
        for (String raw : mapper.rowChildren(statement)) {
            JsonNode row = node(raw);
            String fieldId = text(row, "nodeId");
            String label = detailFieldLabel(definition, request.detailId(), fieldId);
            if (label == null) continue;
            items.add(
                    new BusinessFiles.Directory(
                            "FIELD",
                            request.ruleVersion(),
                            null,
                            request.recordId(),
                            request.detailId(),
                            request.rowId(),
                            fieldId,
                            label,
                            false,
                            row.path("fileCount").asInt(),
                            row.path("totalSize").asLong(),
                            1));
        }
        return new PageResult<>(items, (long) items.size());
    }

    private PageResult<BusinessFiles.File> list(BusinessFiles.FileQuery request, long actor) {
        String applicationId = request.applicationId();
        String objectId = request.objectId();
        FileScope files = scope(applicationId, objectId, actor);
        Integer ruleVersion = request.ruleVersion();
        List<String> keys = request.groupKeys() == null ? List.of() : request.groupKeys();
        DataCenter.BusinessFilePolicy display;
        List<DataCenter.BusinessFileGroup> groups;
        if (ruleVersion == null) {
            // 记录侧按位置身份读取时不携带规则版本：跨版本列该位置的绑定，分组导航键依赖规则版本故不接受
            if (!keys.isEmpty()) throw invalid("业务分组导航必须指定规则版本");
            display = files.display();
            groups = List.of();
        } else {
            display = pinnedPolicy(objectId, ruleVersion);
            groups = display.groups() == null ? List.of() : display.groups();
        }
        if (keys.size() > groups.size()) throw invalid("业务分组导航位置无效");
        Set<String> fields = files.fields(), details = files.details();
        List<BizFileStatement.Projected> labels = allLabelFields(files);
        BizFileStatement statement =
                statement(
                                objectId,
                                files.base(),
                                ruleVersion,
                                groupFilters(groups, keys, fields),
                                0,
                                false,
                                request.recordId(),
                                request.detailId(),
                                request.rowId(),
                                request.fieldId(),
                                namePattern(request.search()),
                                labels,
                                fields,
                                details,
                                request.pageSize(),
                                request.pageNo())
                        .withSearchTitles(searchTitles(files));
        List<BusinessFiles.File> items = new ArrayList<>();
        for (JsonNode row :
                labelRows(
                        mapper.files(statement),
                        files.definition(),
                        labels,
                        applicationId,
                        actor)) {
            items.add(
                    file(
                            row,
                            files.definition(),
                            files.policies().get(row.path("ruleVersion").asInt()),
                            labels,
                            applicationId,
                            actor));
        }
        return new PageResult<>(items, mapper.countFiles(statement));
    }

    /** 定位单个绑定行并取节点侧内容信息：位置与安全集不匹配、或节点已不是受管业务文件时统一按不可访问处理， 不区分“不存在”与“无权限”，避免借探测枚举绑定身份。 */
    private BusinessFiles.Content resolveContent(BusinessFiles.ContentQuery request, long actor) {
        JsonNode row = bindingRow(request, actor);
        Long entryId = number(row, "entryId");
        Long fileId = number(row, "fileId");
        DriveBizFileContent drive;
        try {
            drive = driveFiles.contentInfo(entryId);
        } catch (ServiceException unavailable) {
            // 绑定行存在不代表节点仍可用：受管身份在读取时重新确认，失效一律按不可访问处理
            throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        }
        if (!Objects.equals(drive.fileId(), fileId)) {
            throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        }
        return new BusinessFiles.Content(
                entryId,
                fileId,
                StrUtil.blankToDefault(drive.name(), text(row, "name")),
                drive.size() > 0 ? drive.size() : row.path("size").asLong(),
                StrUtil.blankToDefault(drive.mimeType(), text(row, "mimeType")),
                drive.length());
    }

    /** 按位置身份定位绑定行：记录授权、字段/明细安全集与节点身份缺一不可，定位不到统一按不可访问处理 */
    private JsonNode bindingRow(BusinessFiles.ContentQuery request, long actor) {
        String objectId = request.objectId();
        FileScope files = scope(request.applicationId(), objectId, actor);
        BizFileStatement statement =
                statement(
                                objectId,
                                files.base(),
                                null,
                                List.of(),
                                0,
                                false,
                                request.recordId(),
                                request.detailId(),
                                request.rowId(),
                                request.fieldId(),
                                null,
                                List.of(),
                                files.fields(),
                                files.details(),
                                1,
                                1)
                        .boundTo(request.entryId());
        List<String> rows = mapper.files(statement);
        if (rows.isEmpty()) throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        return node(rows.getFirst());
    }

    /** 定位链装配：分组导航键按当前字段授权在原始值与服务端摘要间选择，与目录浏览的键口径一致 */
    private List<BusinessFiles.Directory> locatePath(
            BusinessFiles.ContentQuery request, long actor) {
        String applicationId = request.applicationId();
        String objectId = request.objectId();
        FileScope files = scope(applicationId, objectId, actor);
        List<BizFileStatement.Projected> labels = allLabelFields(files);
        BizFileStatement statement =
                statement(
                                objectId,
                                files.base(),
                                null,
                                List.of(),
                                0,
                                false,
                                request.recordId(),
                                request.detailId(),
                                request.rowId(),
                                request.fieldId(),
                                null,
                                labels,
                                files.fields(),
                                files.details(),
                                1,
                                1)
                        .boundTo(request.entryId());
        List<String> rows = mapper.files(statement);
        if (rows.isEmpty()) throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        JsonNode row = labelRows(rows, files.definition(), labels, applicationId, actor).getFirst();
        int ruleVersion = row.path("ruleVersion").asInt();
        DataCenter.BusinessFilePolicy pinned = pinnedPolicy(objectId, ruleVersion);
        List<BusinessFiles.Directory> path = new ArrayList<>();
        Integer current = currentVersion(objectId);
        path.add(
                new BusinessFiles.Directory(
                        "VERSION",
                        ruleVersion,
                        null,
                        null,
                        null,
                        null,
                        null,
                        current != null && current == ruleVersion
                                ? "当前规则（v" + ruleVersion + "）"
                                : "历史规则（v" + ruleVersion + "）",
                        false,
                        0,
                        0L,
                        0));
        List<DataCenter.BusinessFileGroup> groups =
                pinned.groups() == null ? List.of() : pinned.groups();
        String groupKeys = StrUtil.blankToDefault(text(row, "groupKeys"), "");
        String[] keys = groupKeys.isEmpty() ? new String[0] : groupKeys.split("\\|", -1);
        for (int i = 0; i < groups.size(); i++) {
            DataCenter.BusinessFileGroup group = groups.get(i);
            boolean hashed = !files.fields().contains(group.fieldId());
            String raw = i < keys.length ? keys[i] : "";
            String key = raw.isEmpty() || !hashed ? raw : DigestUtil.md5Hex(raw);
            BizFileDirectoryNamer.Label label =
                    namer.groupView(files.definition(), group, key, hashed, applicationId, actor);
            path.add(
                    new BusinessFiles.Directory(
                            "GROUP",
                            ruleVersion,
                            key,
                            null,
                            null,
                            null,
                            null,
                            label.text(),
                            label.restricted(),
                            0,
                            0L,
                            0));
        }
        BizFileDirectoryNamer.Label recordLabel =
                recordLabel(
                        files.definition(),
                        pinned,
                        row,
                        labels,
                        request.recordId(),
                        applicationId,
                        actor);
        path.add(
                new BusinessFiles.Directory(
                        "RECORD",
                        ruleVersion,
                        null,
                        request.recordId(),
                        null,
                        null,
                        null,
                        recordLabel.text(),
                        recordLabel.restricted(),
                        0,
                        0L,
                        1,
                        recordLabel.status().getCode()));
        if (request.detailId() != null) {
            String label = detailLabel(files.definition(), request.detailId());
            if (label == null) throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
            path.add(
                    new BusinessFiles.Directory(
                            "REGION",
                            ruleVersion,
                            null,
                            request.recordId(),
                            request.detailId(),
                            null,
                            null,
                            label,
                            false,
                            0,
                            0L,
                            1));
        }
        if (request.rowId() != null) {
            path.add(
                    new BusinessFiles.Directory(
                            "ROW",
                            ruleVersion,
                            null,
                            request.recordId(),
                            request.detailId(),
                            request.rowId(),
                            null,
                            namer.rowLabel(request.rowId()),
                            false,
                            0,
                            0L,
                            1));
        }
        String fieldLabel =
                request.detailId() == null
                        ? fieldLabel(files.definition(), request.fieldId())
                        : detailFieldLabel(
                                files.definition(), request.detailId(), request.fieldId());
        if (fieldLabel == null) throw new ServiceException(NOT_FOUND, "记录不存在或不可访问");
        path.add(
                new BusinessFiles.Directory(
                        "FIELD",
                        ruleVersion,
                        null,
                        request.recordId(),
                        request.detailId(),
                        request.rowId(),
                        request.fieldId(),
                        fieldLabel,
                        false,
                        0,
                        0L,
                        1));
        return path;
    }

    /** 文件行装配：记录名按当前访问者授权与展示策略重新解析，不使用后台保存时的目录名称 */
    private BusinessFiles.File file(
            JsonNode row,
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy display,
            List<BizFileStatement.Projected> labels,
            String applicationId,
            long actor) {
        String recordId = text(row, "recordId");
        BizFileDirectoryNamer.Label label =
                recordLabel(definition, display, row, labels, recordId, applicationId, actor);
        return new BusinessFiles.File(
                number(row, "fileId"),
                number(row, "entryId"),
                number(row, "spaceId"),
                text(row, "name"),
                row.path("size").asLong(),
                text(row, "mimeType"),
                recordId,
                label.text(),
                label.restricted(),
                text(row, "detailId"),
                text(row, "rowId"),
                text(row, "fieldId"),
                text(row, "submitter"),
                text(row, "uploadedAt"),
                label.status().getCode(),
                StrUtil.isBlank(text(row, "detailId"))
                        ? fieldLabel(definition, text(row, "fieldId"))
                        : detailFieldLabel(definition, text(row, "detailId"), text(row, "fieldId")),
                StrUtil.isBlank(text(row, "detailId"))
                        ? null
                        : detailLabel(definition, text(row, "detailId")),
                StrUtil.isBlank(text(row, "rowId")) ? null : namer.rowLabel(text(row, "rowId")));
    }

    /** 对象文件视角：定义、记录语句、安全集与展示策略；列表、标记与定位共用同一套编译前置 */
    private record FileScope(
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            RecordStatement base,
            Set<String> fields,
            Set<String> details,
            DataCenter.BusinessFilePolicy display,
            Map<Integer, DataCenter.BusinessFilePolicy> policies) {}

    private FileScope scope(String applicationId, String objectId, long actor) {
        DataCenter.Definition definition = records.definition(applicationId, objectId, actor);
        ApplicationRuntimePolicy.Access access = policy.access(applicationId, definition, actor);
        RuntimeSchema.Table table = schemas.main(definition);
        return new FileScope(
                definition,
                table,
                base(table, access, actor),
                access.queryFields(),
                access.queryDetails(),
                displayPolicy(definition, access, actor),
                boundPolicies(definition, table, access, actor));
    }

    /** 记录侧固定语句：范围条件与创建人收窄在数据库执行，只读投影不开放筛选与排序 */
    private RecordStatement base(
            RuntimeSchema.Table table, ApplicationRuntimePolicy.Access access, long actor) {
        RecordStatement raw = table.statement(null, null, Long.toString(actor), false);
        RecordStatement read =
                new RecordStatement(
                        raw.schema(),
                        raw.table(),
                        raw.keyColumn(),
                        raw.fields(),
                        List.of(),
                        raw.numericFields(),
                        raw.deletedColumn(),
                        null,
                        null,
                        null,
                        access.all(ApplicationActionEnum.READ) ? null : Long.toString(actor),
                        null,
                        List.of(),
                        "{}",
                        null,
                        false,
                        1,
                        0,
                        List.of(),
                        "{}",
                        raw.actor(),
                        false);
        return read.conditions(
                policy.conditions(access, table, null, ApplicationActionEnum.READ, "t"));
    }

    private BizFileStatement statement(
            String objectId,
            RecordStatement base,
            Integer ruleVersion,
            List<BizFileStatement.GroupFilter> groups,
            int directoryLevel,
            boolean directoryHashed,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            String nameLike,
            List<BizFileStatement.Projected> labels,
            Set<String> visibleFields,
            Set<String> visibleDetails,
            int pageSize,
            int pageNo) {
        return new BizFileStatement(
                objectId,
                base,
                ruleVersion,
                groups,
                directoryLevel,
                directoryHashed,
                recordId,
                detailId,
                rowId,
                fieldId,
                null,
                nameLike,
                labels,
                jsonArray(visibleFields),
                jsonArray(visibleDetails),
                pageSize,
                Math.max(0, pageNo - 1) * pageSize,
                null,
                List.of());
    }

    /** 已选分组层筛选：名称来源不可读时只接受目录接口返回的摘要令牌 */
    private static List<BizFileStatement.GroupFilter> groupFilters(
            List<DataCenter.BusinessFileGroup> groups, List<String> keys, Set<String> fields) {
        List<BizFileStatement.GroupFilter> filters = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (key == null || key.length() > MAX_GROUP_KEY_LENGTH) throw invalid("业务分组导航键无效");
            filters.add(
                    new BizFileStatement.GroupFilter(
                            i + 1, key, !fields.contains(groups.get(i).fieldId())));
        }
        return filters;
    }

    /** 记录标签投影列：显式组合或对象默认标题来源；仅投影契约内且当前可读字段。 */
    private List<BizFileStatement.Projected> labelFields(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            RuntimeSchema.Table table,
            Set<String> fields) {
        List<BizFileStatement.Projected> labels = new ArrayList<>();
        for (String fieldId : namer.recordFields(definition, policy)) {
            String column = table.columns().get(fieldId);
            if (column == null || !fields.contains(fieldId)) continue;
            labels.add(new BizFileStatement.Projected(fieldId, column));
        }
        return labels;
    }

    /** 跨版本列表一次投影各绑定规则所需的可读字段；单条仍按自己的规则解析。 */
    private List<BizFileStatement.Projected> allLabelFields(FileScope files) {
        Map<String, BizFileStatement.Projected> result = new LinkedHashMap<>();
        for (DataCenter.BusinessFilePolicy pinned : files.policies().values()) {
            for (BizFileStatement.Projected field :
                    labelFields(files.definition(), pinned, files.table(), files.fields())) {
                result.put(field.fieldId(), field);
            }
        }
        return new ArrayList<>(result.values());
    }

    private Map<Integer, DataCenter.BusinessFilePolicy> boundPolicies(
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        Map<Integer, DataCenter.BusinessFilePolicy> result = new LinkedHashMap<>();
        BizFileStatement versions =
                statement(
                        definition.objectId(),
                        base(table, access, actor),
                        null,
                        List.of(),
                        0,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        access.queryFields(),
                        access.queryDetails(),
                        1,
                        1);
        for (String raw : mapper.ruleVersions(versions)) {
            int version = node(raw).path("ruleVersion").asInt();
            result.put(version, pinnedPolicy(definition.objectId(), version));
        }
        return result;
    }

    /** 检索仅取可直接显示的主表字段；关联 ID 和选项编码不能作为业务名称被反向探测。 */
    private List<BizFileStatement.SearchTitle> searchTitles(FileScope files) {
        List<BizFileStatement.SearchTitle> result = new ArrayList<>();
        for (Map.Entry<Integer, DataCenter.BusinessFilePolicy> entry :
                files.policies().entrySet()) {
            List<BizFileStatement.Projected> fields =
                    labelFields(
                            files.definition(), entry.getValue(), files.table(), files.fields());
            List<BizFileStatement.Projected> searchable =
                    fields.stream()
                            .filter(
                                    p ->
                                            files.definition().fields().stream()
                                                    .anyMatch(
                                                            f ->
                                                                    f.id().equals(p.fieldId())
                                                                            && searchableType(f)))
                            .toList();
            List<BizFileStatement.TitlePart> parts = new ArrayList<>();
            String template =
                    files.definition().settings() == null
                            ? null
                            : files.definition().settings().titleTemplate();
            boolean custom =
                    entry.getValue().recordLabelFields() != null
                            && !entry.getValue().recordLabelFields().isEmpty();
            if (!custom && !StrUtil.isBlank(template)) {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}")
                                .matcher(template);
                int offset = 0;
                boolean complete = true;
                while (matcher.find()) {
                    parts.add(
                            new BizFileStatement.TitlePart(
                                    null, template.substring(offset, matcher.start())));
                    String code = matcher.group(1);
                    String id =
                            files.definition().fields().stream()
                                    .filter(f -> f.code().equals(code))
                                    .map(FieldDefinition::id)
                                    .findFirst()
                                    .orElse(null);
                    BizFileStatement.Projected field =
                            searchable.stream()
                                    .filter(f -> f.fieldId().equals(id))
                                    .findFirst()
                                    .orElse(null);
                    if (field == null) {
                        complete = false;
                        break;
                    }
                    parts.add(new BizFileStatement.TitlePart(field.column(), null));
                    offset = matcher.end();
                }
                if (complete)
                    parts.add(new BizFileStatement.TitlePart(null, template.substring(offset)));
                else {
                    parts.clear();
                    searchable = List.of();
                }
            } else {
                for (BizFileStatement.Projected field : searchable) {
                    if (!parts.isEmpty()) parts.add(new BizFileStatement.TitlePart(null, " · "));
                    parts.add(new BizFileStatement.TitlePart(field.column(), null));
                }
            }
            result.add(new BizFileStatement.SearchTitle(entry.getKey(), searchable, parts));
        }
        return result;
    }

    private boolean searchableType(FieldDefinition field) {
        return switch (FieldTypeEnum.fromCode(field.type())) {
            case TEXT,
                            TEXTAREA,
                            AUTO_NUMBER,
                            UUID,
                            INTEGER,
                            DECIMAL,
                            MONEY,
                            PERCENT,
                            DATE,
                            DATETIME,
                            TIME,
                            URL,
                            BOOLEAN ->
                    true;
            default -> false;
        };
    }

    /** 规则版本固定后的策略：版本不可读或未接入时拒绝，不静默退回当前规则 */
    private DataCenter.BusinessFilePolicy pinnedPolicy(String objectId, int ruleVersion) {
        DataCenter.BusinessFilePolicy pinned = bindings.pinnedPolicy(objectId, ruleVersion);
        if (!DataCenter.BusinessFilePolicy.enabled(pinned)) throw invalid("目录规则版本不存在或未接入业务文件");
        return pinned;
    }

    /** 未指定规则版本时的展示策略：当前规则优先，规则停用但历史绑定仍在时沿最近绑定版本，均不可用时无标签来源 */
    private DataCenter.BusinessFilePolicy displayPolicy(
            DataCenter.Definition definition, ApplicationRuntimePolicy.Access access, long actor) {
        DataCenter.BusinessFilePolicy current = bindings.policyOf(definition);
        if (DataCenter.BusinessFilePolicy.enabled(current)) return current;
        return latestPinned(definition, access, actor);
    }

    /** 规则已停用但历史绑定仍在时，沿用最近绑定版本的空间展示配置 */
    private DataCenter.BusinessFilePolicy latestPinned(
            DataCenter.Definition definition, ApplicationRuntimePolicy.Access access, long actor) {
        List<String> versions =
                mapper.ruleVersions(
                        statement(
                                definition.objectId(),
                                base(schemas.main(definition), access, actor),
                                null,
                                List.of(),
                                0,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                access.queryFields(),
                                access.queryDetails(),
                                1,
                                1));
        if (versions.isEmpty()) return null;
        return bindings.pinnedPolicy(
                definition.objectId(), node(versions.getFirst()).path("ruleVersion").asInt());
    }

    private Integer currentVersion(String objectId) {
        try {
            return objects.getVersion(objectId, null).versionNo();
        } catch (RuntimeException missing) {
            return null;
        }
    }

    /** 附件字段目录名：字段已从对象契约移除时不再显示节点，不猜测名称 */
    private String fieldLabel(DataCenter.Definition definition, String fieldId) {
        return definition.fields().stream()
                .filter(f -> f.id().equals(fieldId))
                .map(FieldDefinition::name)
                .filter(name -> !StrUtil.isBlank(name))
                .map(namer::sanitize)
                .findFirst()
                .orElse(null);
    }

    /** 明细区目录名：明细已从对象契约移除时不再显示节点 */
    private String detailLabel(DataCenter.Definition definition, String detailId) {
        return definition.details().stream()
                .filter(t -> t.id().equals(detailId))
                .map(DataCenter.Detail::name)
                .filter(name -> !StrUtil.isBlank(name))
                .map(namer::sanitize)
                .findFirst()
                .orElse(null);
    }

    /** 明细行长内附件字段目录名：字段已从明细契约移除时不再显示节点 */
    private String detailFieldLabel(
            DataCenter.Definition definition, String detailId, String fieldId) {
        return definition.details().stream()
                .filter(t -> t.id().equals(detailId))
                .findFirst()
                .flatMap(t -> t.fields().stream().filter(f -> f.id().equals(fieldId)).findFirst())
                .map(FieldDefinition::name)
                .filter(name -> !StrUtil.isBlank(name))
                .map(namer::sanitize)
                .orElse(null);
    }

    /** 名称计算失败单独降级；附件是否可读仍由已有绑定授权判定。 */
    private BizFileDirectoryNamer.Label recordLabel(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            JsonNode row,
            List<BizFileStatement.Projected> labels,
            String recordId,
            String applicationId,
            long actor) {
        if (row.path("labelEvaluationFailed").asBoolean(false)) {
            return new BizFileDirectoryNamer.Label(
                    "记录 " + recordId, false, BusinessFileLabelStatusEnum.INVALID);
        }
        return namer.recordView(
                definition, policy, labelValues(row, labels), recordId, applicationId, actor);
    }

    /** 当前页按记录去重后复用运行时实时公式求值，避免读取 LIVE 列的旧快照或空值。 */
    private List<JsonNode> labelRows(
            List<String> raw,
            DataCenter.Definition definition,
            List<BizFileStatement.Projected> labels,
            String applicationId,
            long actor) {
        List<JsonNode> rows = raw.stream().map(this::node).toList();
        if (rows.isEmpty()
                || labels.stream()
                        .noneMatch(
                                label ->
                                        Calculations.live(
                                                definition.fieldOptions().get(label.fieldId()))))
            return rows;
        Map<String, ApplicationRecords.Row> recordsById = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String id = text(row, "recordId");
            recordsById.putIfAbsent(
                    id,
                    new ApplicationRecords.Row(id, null, labelValues(row, labels), null, Map.of()));
        }
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        try {
            for (ApplicationRecords.Row row :
                    calculations.enrich(
                            applicationId,
                            definition,
                            new ArrayList<>(recordsById.values()),
                            actor)) {
                resolved.put(row.id(), row.values());
            }
        } catch (ServiceException unavailable) {
            // 公式取数权限或配置失效不阻断附件读取，也不能回退物理列中的旧计算值。
            for (JsonNode row : rows) {
                ((ObjectNode) row).put("labelEvaluationFailed", true);
                for (int i = 0; i < labels.size(); i++) ((ObjectNode) row).remove("lf" + i);
            }
            return rows;
        }
        for (JsonNode row : rows) {
            Map<String, Object> values = resolved.get(text(row, "recordId"));
            for (int i = 0; i < labels.size(); i++) {
                Object value = values.get(labels.get(i).fieldId());
                String text =
                        value instanceof java.math.BigDecimal number
                                ? number.stripTrailingZeros().toPlainString()
                                : value == null ? null : value.toString();
                ((ObjectNode) row).put("lf" + i, text);
            }
        }
        return rows;
    }

    private static Map<String, Object> labelValues(
            JsonNode row, List<BizFileStatement.Projected> labels) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            String value = text(row, "lf" + i);
            values.put(labels.get(i).fieldId(), value);
        }
        return values;
    }

    private static <T> PageResult<T> slice(List<T> all, int pageNo, int pageSize) {
        int start = Math.min((pageNo - 1) * pageSize, all.size());
        int end = Math.min(start + pageSize, all.size());
        return new PageResult<>(new ArrayList<>(all.subList(start, end)), (long) all.size());
    }

    /** 对象标识校验；同包的业务文件标记服务共用 */
    static void requireObjectId(String objectId) {
        if (objectId == null || !objectId.matches("[1-9][0-9]*")) throw invalid("数据对象标识无效");
    }

    private static void requirePage(int pageNo, int pageSize) {
        if (pageNo < 1 || pageNo > MAX_PAGE_NO || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
            throw invalid("业务文件分页参数无效");
    }

    private static void requireRuleVersion(Integer ruleVersion) {
        if (ruleVersion != null && ruleVersion <= 0) throw invalid("目录规则版本无效");
    }

    private static void requireLocation(
            List<String> groupKeys, String recordId, String detailId, String rowId) {
        if (groupKeys != null && groupKeys.size() > 2) throw invalid("业务分组导航位置无效");
        if (detailId != null && recordId == null) throw invalid("明细区目录必须指定所属记录");
        if (rowId != null && detailId == null) throw invalid("明细行目录必须指定所属明细区");
    }

    /** 内容读取必须提供完整位置身份：记录、字段与节点缺一不可，不接受只凭文件或节点编号读取；同包的标记服务共用 */
    static void requireContentLocation(BusinessFiles.ContentQuery request) {
        if (StrUtil.isBlank(request.recordId())) throw invalid("业务文件内容必须指定所属记录");
        if (StrUtil.isBlank(request.fieldId())) throw invalid("业务文件内容必须指定所属字段");
        if (request.entryId() == null || request.entryId() <= 0) throw invalid("业务文件节点身份无效");
        if (request.rowId() != null && request.detailId() == null) {
            throw invalid("明细行附件必须指定所属明细区");
        }
    }

    /** 文件名模糊匹配：转义 LIKE 通配符，最多 200 字 */
    private static String namePattern(String search) {
        if (search == null || search.isBlank()) return null;
        String trimmed = search.trim();
        if (trimmed.length() > MAX_SEARCH_LENGTH) throw invalid("搜索内容不能超过 200 字");
        return "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private String jsonArray(Set<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("业务文件权限安全集序列化失败");
        }
    }

    private JsonNode node(String raw) {
        try {
            return json.readTree(raw);
        } catch (java.io.IOException e) {
            throw invalid("业务文件目录读取失败");
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long number(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || !value.matches("[1-9][0-9]*")) throw invalid("业务文件身份无效");
        return Long.valueOf(value);
    }
}
