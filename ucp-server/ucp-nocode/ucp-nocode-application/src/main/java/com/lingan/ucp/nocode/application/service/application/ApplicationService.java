package com.lingan.ucp.nocode.application.service.application;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.enums.permission.RoleCodeEnum;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.api.ApplicationDashboards;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.ObjectContracts;
import com.lingan.ucp.nocode.api.ReportCatalogApi;
import com.lingan.ucp.nocode.application.dal.dataobject.*;
import com.lingan.ucp.nocode.application.dal.mapper.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationDashboardValidator;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 应用草稿、固定对象引用与发布聚合；写入和依赖登记在同一事务内完成。 */
@Service
public class ApplicationService {
    @Resource
    private com.lingan.ucp.nocode.application.service.published.ApplicationVersionContext
            versionContext;

    @Resource private ApplicationMapper store;
    @Resource private RecordProcessMapper processes;
    @Resource private DataObjectApi objects;
    @Resource private ObjectDraftMapper objectLocks;
    @Resource private DraftValidator validator;
    @Resource private ApplicationResourceValidator resourcesValidator;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationNavigation navigation;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationRuleWriteExclusion
            ruleWrites;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog
            automations;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationLinkageTriggers
            linkageTriggers;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationDateTriggers dateTriggers;

    @Resource private ApplicationObjectContract objectContract;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;
    @Resource private ObjectSharingService sharing;

    /** 报表目录为可选模块；旧应用不要求装配，固定看板引用必须通过该受信边界。 */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<ReportCatalogApi> reportCatalog;

    @Resource
    private com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator grantValidator;

    @Resource
    private com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects impliedObjects;

    @Resource private ApplicationFollowService follows;

    /** 运行层选择目录：挑取值来源字段的公共字典项由它解析；未装配时只认局部选项。 */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.lingan.ucp.nocode.api.SelectionTargetValidator>
            selectionTargets;

    @Resource private PermissionCommonApi permissions;
    @Resource private com.lingan.ucp.module.system.api.user.AdminUserApi users;

    @Resource
    private com.lingan.ucp.module.bpm.api.definition.BpmProcessDefinitionApi processDefinitions;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
        json =
                json.copy()
                        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public PageResult<Row> page(int number, int size, String search) {
        return page(number, size, search, null);
    }

    public PageResult<Row> page(int number, int size, String search, Long actor) {
        return page(number, size, search, null, actor);
    }

    public PageResult<Row> page(int number, int size, String search, String category, Long actor) {
        if (number < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        String value = Objects.toString(search, "").trim();
        if (value.length() > 160) throw invalid("搜索内容过长");
        IPage<NocodeApplicationDO> result =
                store.page(
                        new Page<>(number, size),
                        "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%",
                        actor == null || isPlatformAdmin(actor) ? null : Long.toString(actor),
                        com.lingan.ucp.nocode.api.ManagementCategories.filter(category));
        return new PageResult<>(
                result.getRecords().stream().map(this::row).toList(), result.getTotal());
    }

    /** 目录与管理列表共享创建者或平台管理员的权限范围。 */
    public List<String> categories(long actor) {
        return store.categories(isPlatformAdmin(actor) ? null : Long.toString(actor));
    }

    /** 回收站按创建者隔离；超级管理员复用底座身份。 */
    public PageResult<RecycleRow> recyclePage(int number, int size, String search, long actor) {
        if (actor <= 0) throw invalid("缺少受信操作者");
        if (number < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        String value = Objects.toString(search, "").trim();
        if (value.length() > 160) throw invalid("搜索内容过长");
        IPage<NocodeApplicationDO> result =
                store.recyclePage(
                        new Page<>(number, size),
                        "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%",
                        isPlatformAdmin(actor) ? null : Long.toString(actor));
        return new PageResult<>(
                result.getRecords().stream()
                        .map(
                                app -> {
                                    com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO
                                            user =
                                                    app.getDeletedBy() != null
                                                                    && app.getDeletedBy()
                                                                            .matches(
                                                                                    "[1-9][0-9]{0,18}")
                                                            ? users.getUser(
                                                                    Long.parseLong(
                                                                            app.getDeletedBy()))
                                                            : null;
                                    return new RecycleRow(
                                            app.getId().toString(),
                                            app.getAppCode(),
                                            app.getAppName(),
                                            app.getDescription(),
                                            app.getIcon(),
                                            app.getStatus(),
                                            app.getLockVersion(),
                                            app.getPublishedVersion(),
                                            app.getUpdateTime(),
                                            app.getCategory(),
                                            Boolean.TRUE.equals(app.getRecoveryPending()),
                                            Boolean.TRUE.equals(app.getRecoveryNeedsEdit()),
                                            app.getDeletedAt(),
                                            app.getDeletedBy(),
                                            user == null ? app.getDeletedBy() : user.getNickname());
                                })
                        .toList(),
                result.getTotal());
    }

    public DeletePreview deletePreview(String id, long actor) {
        requireDesigner(id, actor);
        return transaction.execute(s -> deletePreview(head(id, false)));
    }

    private DeletePreview deletePreview(NocodeApplicationDO app) {
        Definition draft = read(app.getDesignJson());
        Set<String> objectIds = new HashSet<>();
        Map<String, com.lingan.ucp.nocode.api.ApplicationCenter.Resource> resourceIds =
                new HashMap<>();
        draft.objects().forEach(ref -> objectIds.add(ref.objectId()));
        draft.resources().forEach(resource -> resourceIds.put(resource.id(), resource));
        if (app.getPublishedVersion() != null) {
            try {
                NocodeApplicationVersionDO version =
                        store.version(app.getId(), app.getPublishedVersion());
                Definition published =
                        json.readValue(version.getDefinitionJson(), Snapshot.class).definition();
                published.objects().forEach(ref -> objectIds.add(ref.objectId()));
                published.resources().forEach(resource -> resourceIds.put(resource.id(), resource));
            } catch (java.io.IOException ex) {
                throw invalid("应用发布快照无法读取");
            }
        }
        ArrayList<String> blockers = new ArrayList<String>();
        if (!processes.active(app.getId()).isEmpty()) blockers.add("应用还有运行中的流程，请先完成或终止流程");
        if (store.unresolvedHandling(
                        app.getId(),
                        List.of(
                                HandlingStateEnum.PENDING.getCode(),
                                HandlingStateEnum.APPLY_PENDING.getCode(),
                                HandlingStateEnum.APPLY_FAILED.getCode()))
                > 0) blockers.add("应用还有未完成的业务办理申请（审批中、待生效或生效失败待重试），请先处理申请");
        for (com.lingan.ucp.module.bpm.api.definition.dto.BpmBusinessBindingDTO binding :
                processDefinitions.getEffectiveBusinessBindings(
                        com.lingan.ucp.nocode.api.workflow.FlowTasks.HANDLER)) {
            try {
                JsonNode configuration = json.readTree(binding.configuration());
                if (configuration != null
                        && app.getId()
                                .toString()
                                .equals(
                                        configuration
                                                .path("resource")
                                                .path("applicationId")
                                                .asText()))
                    blockers.add(
                            "流程“"
                                    + binding.processName()
                                    + "”V"
                                    + binding.processVersion()
                                    + "的节点“"
                                    + Objects.toString(binding.nodeName(), binding.nodeId())
                                    + "”仍引用该应用，请先停用对应流程并处理运行实例");
            } catch (java.io.IOException ex) {
                throw invalid("流程业务绑定配置无法读取，请先检查流程定义");
            }
        }
        return new DeletePreview(
                row(app),
                objectIds.size(),
                resourceIds.size(),
                (int)
                        resourceIds.values().stream()
                                .filter(
                                        resource ->
                                                ApplicationResourceKindEnum.TASK_ENTRY.matches(
                                                        resource.kind()))
                                .count(),
                List.copyOf(blockers));
    }

    /** 应用头写锁与运行端的共享锁互斥；仅移入回收站，保留共享数据及依赖保护。 */
    public boolean delete(Revision request, long actor) {
        if (request == null) throw invalid("应用修订不能为空");
        requireDesigner(request.id(), actor);
        return Boolean.TRUE.equals(
                transaction.execute(
                        s -> {
                            automations.lock(true);
                            objectLocks.lockTableName("nocode-design-write");
                            NocodeApplicationDO app = checkedHead(request);
                            String reason = validator.text(request.reason(), "删除说明", 1000);
                            DeletePreview preview = deletePreview(app);
                            if (!preview.blockers().isEmpty())
                                throw invalid(String.join("；", preview.blockers()));
                            if (store.recycle(app.getId(), reason, Long.toString(actor)) != 1)
                                throw conflict();
                            // 移入回收站的应用不再执行；恢复后发布启用时从当天重新起算。
                            dateTriggers.disarm(app.getId().toString(), actor);
                            return true;
                        }));
    }

    /** 回收站恢复只恢复可编辑性，原发布指针保持供审计，但不能重新开放旧入口。 */
    public Detail restoreDeleted(Revision request, long actor) {
        if (request == null || actor <= 0) throw invalid("缺少应用修订或受信操作者");
        return transaction.execute(
                s -> {
                    automations.lock(true);
                    objectLocks.lockTableName("nocode-design-write");
                    NocodeApplicationDO app =
                            store.lockDeleted(validator.id(request.id(), "应用 ID"));
                    if (app == null) throw new ServiceException(NOT_FOUND, "回收站中不存在该应用");
                    if (!isPlatformAdmin(actor) && !Long.toString(actor).equals(app.getCreator()))
                        throw new org.springframework.security.access.AccessDeniedException(
                                "只能恢复自己创建的应用");
                    checkRevision(app, request.expectedRevision());
                    String reason = validator.text(request.reason(), "恢复说明", 1000);
                    if (store.restoreDeleted(app.getId(), reason, Long.toString(actor)) != 1)
                        throw conflict();
                    return detail(head(request.id(), false));
                });
    }

    /** 人工保存后的恢复应用原子发布并启用；任一版本、共享授权或结构校验失败整笔回滚。 */
    public Detail publishAndEnable(Revision request, long actor) {
        if (request == null) throw invalid("应用修订不能为空");
        requireDesigner(request.id(), actor);
        return transaction.execute(
                s -> {
                    automations.lock(true);
                    objectLocks.lockTableName("nocode-design-write");
                    NocodeApplicationDO app = checkedHead(request);
                    validateEffectiveFlowBindings(app, null);
                    if (Boolean.TRUE.equals(app.getRecoveryPending())) {
                        if (Boolean.TRUE.equals(app.getRecoveryNeedsEdit()))
                            throw invalid("请先人工编辑并保存应用草稿，再发布启用");
                        if (store.completeRecovery(app.getId(), Long.toString(actor)) != 1)
                            throw conflict();
                        app.setRecoveryPending(false);
                        app.setStatus(ApplicationStatusEnum.ACTIVE.getCode());
                    } else if (!ApplicationStatusEnum.DISABLED.matches(app.getStatus())) {
                        throw invalid("仅停用或从回收站恢复的应用需要发布启用");
                    }
                    // 激活只存在当前未提交事务内；发布失败会连同状态变更一起回滚。
                    if (!ApplicationStatusEnum.ACTIVE.matches(app.getStatus())) {
                        store.status(
                                app.getId(),
                                ApplicationStatusEnum.ACTIVE.getCode(),
                                Long.toString(actor));
                        app.setStatus(ApplicationStatusEnum.ACTIVE.getCode());
                    }
                    return publishSnapshot(
                            app,
                            new Snapshot(
                                    app.getAppCode(),
                                    app.getAppName(),
                                    app.getDescription(),
                                    app.getIcon(),
                                    read(app.getDesignJson())),
                            request.reason(),
                            actor);
                });
    }

    /** 仅供运行授权服务筛选；不向未授权客户端直接暴露应用列表。 */
    public List<String> runnableIds() {
        return store.runnableIds();
    }

    /**
     * 应用详情：草稿 + 草稿固定版本与对象最新结构的兼容差异。
     *
     * <p>这一笔先读应用头（共享行锁）、再逐个读草稿引用的对象头（共享行锁）。对象发布是「目录独占锁 → 对象头排他行锁 → 应用头排他行锁」 （暂停应用 /
     * 自动跟随都在对象发布那一笔里改应用头），所以这里进事务先取目录共享锁，与运行端同一个顺序：
     * 不先取，就会握着应用头去等对象头、对方握着对象头等应用头，数据库判死锁，被杀的多半是自动跟随那一步。
     */
    public Detail get(String id) {
        return transaction.execute(
                s -> {
                    automations.lock(false);
                    return detail(head(id, false));
                });
    }

    /** 发布记录按版本号倒序分页；版本与发布页按需加载，避免草稿读写附带全量版本快照。 */
    public PageResult<Release> releases(String id, int number, int size) {
        if (number < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        NocodeApplicationDO app = head(id, false);
        IPage<NocodeApplicationVersionDO> result =
                store.versionPage(new Page<>(number, size), app.getId());
        return new PageResult<>(
                result.getRecords().stream()
                        .map(
                                v ->
                                        new Release(
                                                v.getVersionNo(),
                                                v.getChecksum(),
                                                v.getReason(),
                                                v.getCreator(),
                                                v.getCreateTime()))
                        .toList(),
                result.getTotal());
    }

    /** 不接受调用方提供审计身份；actor 只能由受信控制器或内部服务提供。 */
    public Detail save(Save request, long actor) {
        if (request == null || actor <= 0) throw invalid("缺少应用或受信操作者");
        if (request.id() != null) requireDesigner(request.id(), actor);
        String code = validator.code(request.code(), "应用编码", 64, false);
        String name = validator.text(request.name(), "应用名称", 160);
        if (request.description() != null && request.description().length() > 2000)
            throw invalid("应用说明最多 2000 字符");
        if (request.icon() != null && !request.icon().matches("[A-Za-z][A-Za-z0-9_-]{0,79}"))
            throw invalid("应用图标编码无效");
        try {
            return transaction.execute(
                    s -> {
                        // 与数据中心保持相同锁顺序，避免发布/引用与字段停用相互穿透。
                        automations.lock(true);
                        objectLocks.lockTableName("nocode-design-write");
                        NocodeApplicationDO app;
                        if (request.id() == null) {
                            if (request.expectedRevision() != null) throw invalid("新应用不能指定修订号");
                            app = new NocodeApplicationDO();
                            app.setAppCode(code);
                            app.setStatus(ApplicationStatusEnum.ACTIVE.getCode());
                            app.setLockVersion(0);
                        } else {
                            app = head(request.id(), true);
                            checkRevision(app, request.expectedRevision());
                            if (!app.getAppCode().equals(code)) throw invalid("应用编码创建后不可修改");
                        }
                        Definition previousDefinition =
                                request.id() == null
                                        ? Definition.empty()
                                        : read(app.getDesignJson());
                        resourcesValidator.validateLegacyTaskEntries(
                                previousDefinition, request.definition());
                        Definition definition = normalize(request.definition(), false);
                        List<ApplicationDashboards.Reference> dashboardReferences =
                                reportReferences(definition, actor);
                        if (request.id() != null)
                            resourcesValidator.validateDefaultFormRemoval(
                                    previousDefinition, definition);
                        app.setCategory(
                                com.lingan.ucp.nocode.api.ManagementCategories.normalize(
                                        request.category() == null
                                                ? app.getCategory()
                                                : request.category()));
                        app.setAppName(name);
                        app.setDescription(request.description());
                        app.setIcon(request.icon());
                        // 引用即授权：与上一份草稿对比，找出新增或版本/校验和变化的对象，保存后补默认上限。
                        Map<String, ObjectReference> previous = new HashMap<>();
                        if (request.id() != null
                                && app.getDesignJson() != null
                                && !app.getDesignJson().isBlank())
                            for (ObjectReference ref : read(app.getDesignJson()).objects())
                                previous.put(ref.objectId(), ref);
                        app.setDesignJson(write(definition));
                        if (request.id() == null) store.create(app, Long.toString(actor));
                        else if (store.updateDraft(
                                        app, request.expectedRevision(), Long.toString(actor))
                                != 1) throw conflict();
                        dependencies(app, definition, "draft", actor);
                        ReportCatalogApi catalog = reportCatalog.getIfAvailable();
                        if (catalog != null)
                            catalog.registerApplicationDraft(
                                    app.getId().toString(),
                                    dashboardReferences,
                                    definition.objects(),
                                    actor);
                        for (ObjectReference ref : definition.objects()) {
                            ObjectReference before = previous.get(ref.objectId());
                            boolean changed =
                                    before == null
                                            || before.versionNo() != ref.versionNo()
                                            || !Objects.equals(before.checksum(), ref.checksum());
                            if (!changed) continue;
                            sharing.applyDefaultGrant(
                                    ref.objectId(),
                                    app.getId().toString(),
                                    objects.getVersion(ref.objectId(), ref.versionNo())
                                            .definition(),
                                    actor);
                        }
                        return detail(head(app.getId().toString(), false));
                    });
        } catch (DataIntegrityViolationException ex) {
            throw new ServiceException(DUPLICATE, "应用编码已存在或配置不符合数据库约束");
        }
    }

    public Detail publish(Revision request, long actor) {
        requireDesigner(request.id(), actor);
        return transaction.execute(
                s -> {
                    automations.lock(true);
                    objectLocks.lockTableName("nocode-design-write");
                    NocodeApplicationDO app = checkedHead(request);
                    return publishSnapshot(
                            app,
                            new Snapshot(
                                    app.getAppCode(),
                                    app.getAppName(),
                                    app.getDescription(),
                                    app.getIcon(),
                                    read(app.getDesignJson())),
                            request.reason(),
                            actor);
                });
    }

    /** 历史配置重新发布为新版本；草稿、业务数据和实时成员授权保持各自的生命周期。 */
    public Detail restore(Restore request, long actor) {
        if (request == null || request.sourceVersion() < 1) throw invalid("请选择历史发布版本");
        requireDesigner(request.id(), actor);
        return transaction.execute(
                s -> {
                    automations.lock(true);
                    objectLocks.lockTableName("nocode-design-write");
                    NocodeApplicationDO app =
                            checkedHead(
                                    new Revision(
                                            request.id(),
                                            request.expectedRevision(),
                                            request.reason()));
                    NocodeApplicationVersionDO version =
                            store.version(app.getId(), request.sourceVersion());
                    if (version == null) throw invalid("历史发布版本不存在");
                    // JSONB 会规范化键顺序和空白；历史摘要不是数据库返回文本的字节摘要。
                    // 恢复仍须重新校验快照结构、固定对象版本及当前物理表契约。
                    String reason = validator.text(request.reason(), "恢复说明", 900);
                    try {
                        Snapshot snapshot =
                                json.readValue(version.getDefinitionJson(), Snapshot.class);
                        return publishSnapshot(
                                app,
                                snapshot,
                                "恢复 V" + request.sourceVersion() + "：" + reason,
                                actor);
                    } catch (java.io.IOException e) {
                        throw invalid("历史快照无法读取");
                    }
                });
    }

    /** 发布和恢复共用校验、流程保护、依赖登记与版本事务，不能只移动发布指针绕过校验。 */
    private Detail publishSnapshot(
            NocodeApplicationDO app, Snapshot snapshot, String reason, long actor) {
        if (Boolean.TRUE.equals(app.getRecoveryPending())) throw invalid("恢复后的应用须人工编辑保存后使用“发布启用”");
        if (!ApplicationStatusEnum.ACTIVE.matches(app.getStatus())) throw invalid("停用应用不能发布");
        // 开着自动跟随的对象引用落后于最新发布版时，先提版再校验：否则人工发布会把应用退回旧对象版本，和自动跟随打架。
        ApplicationFollowService.Lift lift = follows.lift(app, snapshot);
        snapshot = lift.snapshot();
        Definition definition;
        try {
            definition = validateSnapshot(app, snapshot);
        } catch (ServiceException rejected) {
            if (lift.prefix() == null) throw rejected;
            throw new ServiceException(rejected.getCode(), lift.prefix() + rejected.getMessage());
        }
        List<ApplicationDashboards.Reference> dashboardReferences =
                reportReferences(definition, actor);
        String description = validator.text(reason, "发布说明", 1000);
        List<NocodeApplicationVersionDO> previous = store.versions(app.getId());
        int number = previous.isEmpty() ? 1 : previous.getFirst().getVersionNo() + 1;
        NocodeApplicationVersionDO version = new NocodeApplicationVersionDO();
        version.setApplicationId(app.getId());
        version.setVersionNo(number);
        version.setDefinitionJson(
                write(
                        new Snapshot(
                                snapshot.code(),
                                snapshot.name(),
                                snapshot.description(),
                                snapshot.icon(),
                                definition)));
        version.setChecksum(DigestUtil.sha256Hex(version.getDefinitionJson()));
        version.setReason(description);
        store.createVersion(version, Long.toString(actor));
        dependencies(app, definition, "published", actor);
        ReportCatalogApi catalog = reportCatalog.getIfAvailable();
        if (catalog != null)
            catalog.registerApplicationVersion(
                    app.getId().toString(),
                    number,
                    dashboardReferences,
                    definition.objects(),
                    actor);
        // 数据联动自动更新的反向索引：按「应用 × 新发布版本」登记，与发布指针同一事务。
        linkageTriggers.register(app.getId().toString(), number, definition, actor);
        navigation.synchronize(app.getId().toString(), definition, actor);
        store.publish(app.getId(), number, Long.toString(actor));
        // 按日期自动执行的账本：新生效的规则从今天起算（不回溯发布前的日期），一直生效的继续，拿掉或停用的失效。
        dateTriggers.arm(app.getId().toString(), definition, actor);
        // 提过版的记入跟随状态与日志，并把草稿里落后的引用同步到刚发布的版本。
        follows.published(app, definition, lift, number, actor);
        return detail(head(app.getId().toString(), false));
    }

    /** 启用旧快照与新发布共用严格版本、物理表、资源、共享上限和流程校验。 */
    private Definition validateSnapshot(NocodeApplicationDO app, Snapshot snapshot) {
        Definition definition = normalize(snapshot.definition());
        automations.validate(app.getId().toString(), definition);
        if (definition.objects().isEmpty()) throw invalid("请先引用一个已发布数据对象");
        Set<String> objectIds =
                definition.objects().stream()
                        .map(ObjectReference::objectId)
                        .collect(java.util.stream.Collectors.toSet());
        Map<String, DataCenter.Definition> referencedObjects = new LinkedHashMap<>();
        for (ObjectReference ref : definition.objects()) {
            sharing.requireReference(ref.objectId(), app.getId().toString());
            DataCenter.Definition d =
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition();
            referencedObjects.put(ref.objectId(), d);
            objectContract.validate(d);
            // 关系的目标对象不必被应用引用：系统自动放行应用对它的只读（关联对象隐式只读）。只要求它已发布且未停用。
            for (DataCenter.Relation relation : d.relations())
                if (!objectIds.contains(relation.targetObjectId())
                        && impliedObjects.latest(relation.targetObjectId()) == null)
                    throw invalid(
                            unreadableTarget(d, "关系", relation.name(), relation.targetObjectId()));
        }
        // 对象规则从固定版本读取，字段与类型按固定版本重新校验（含内部明细规则）；来源对象没被应用引用时按它的最新发布版校验。
        com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions readable =
                new com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions();
        readable.putAll(referencedObjects);
        impliedObjects.complete(readable);
        var catalog = selectionTargets.getIfAvailable();
        java.util.function.BiFunction<
                        com.lingan.ucp.nocode.api.FieldDefinition,
                        DataCenter.FieldOptions,
                        List<com.lingan.ucp.nocode.api.SelectionFields.Option>>
                choices = catalog == null ? null : catalog::options;
        for (DataCenter.Definition root : referencedObjects.values()) {
            java.util.function.Function<String, DataCenter.Definition> lookup =
                    id -> id.equals(root.objectId()) ? root : readable.get(id);
            if (choices == null)
                FieldRuleValidator.validate(root, lookup, this::ruleSourceUnavailable);
            else FieldRuleValidator.validate(root, lookup, this::ruleSourceUnavailable, choices);
        }
        // 同一字段只能由一处写入：自动更新、留存动作的目标字段不能同时配了数据联动或公式默认值。
        ruleWrites.validatePublish(
                app.getId().toString(),
                definition,
                referencedObjects,
                id -> objects.getVersion(id, null).definition());
        // 数据联动自动更新不能在字段级成环：范围是本应用固定的全部对象版本（联动的来源对象必须在本应用里，集合闭合）。
        List<com.lingan.ucp.nocode.api.ApplicationAutomations.Config> automationConfigs =
                new ArrayList<>();
        for (com.lingan.ucp.nocode.api.ApplicationCenter.Resource resource : definition.resources())
            if (ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind()))
                automationConfigs.add(automations.config(resource));
        com.lingan.ucp.nocode.application.service.resource.LinkageCycles.check(
                referencedObjects, automationConfigs);
        // 配置产生的间接写入在发布时先检查共享上限；运行时仍按当前用户的有效权限再次校验。
        for (com.lingan.ucp.nocode.api.ApplicationCenter.Resource resource :
                definition.resources()) {
            if (!ApplicationResourceKindEnum.NUMBER_RULE.matches(resource.kind())) continue;
            com.lingan.ucp.nocode.api.ApplicationBusiness.NumberRule rule =
                    resourcesValidator.decode(
                            resource.config(),
                            com.lingan.ucp.nocode.api.ApplicationBusiness.NumberRule.class);
            com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant grant =
                    sharing.storedPermission(rule.objectId(), app.getId().toString());
            List<String> missing = new ArrayList<>();
            DataCenter.Definition target = referencedObjects.get(rule.objectId());
            // 共享上限里的「全部」对着本应用固定的对象版本展开后再判断字段。
            if (grant != null) grant = grantValidator.resolve(grant, target);
            String fieldName =
                    target.fields().stream()
                            .filter(field -> field.id().equals(rule.fieldId()))
                            .map(field -> field.name())
                            .findFirst()
                            .orElse("编号字段");
            if (grant == null || !grant.actions().contains(ApplicationActionEnum.CREATE.getCode()))
                missing.add("“新增”操作权限");
            if (grant == null || !grant.writeFields().contains(rule.fieldId()))
                missing.add("“" + fieldName + "”字段的填写和修改权限");
            if (!missing.isEmpty())
                throw invalid(
                        "自动编号“"
                                + resource.name()
                                + "”无法使用：数据对象“"
                                + target.objectName()
                                + "”缺少"
                                + String.join("、", missing)
                                + "。"
                                + "\n如何修正：进入“已引用对象” → “"
                                + target.objectName()
                                + "”对象行 → 配置数据权限，为当前应用“"
                                + app.getAppName()
                                + "”，补齐上述权限后保存。也可在“业务配置”中修正自动编号的目标对象和字段。");
        }
        for (NocodeRecordProcessDO process : processes.active(app.getId())) {
            if (definition.objects().stream()
                    .noneMatch(
                            r ->
                                    r.objectId().equals(process.getObjectId().toString())
                                            && r.versionNo() == process.getObjectVersion()))
                throw invalid("应用还有运行中的流程，不能移除或切换其业务对象版本");
        }
        return definition;
    }

    /** 已部署或仍有实例的业务节点可固定历史应用版本；重新启用前逐版检查其真实契约。 */
    private void validateEffectiveFlowBindings(NocodeApplicationDO app, Integer alreadyChecked) {
        Set<Integer> checked = new HashSet<>();
        for (com.lingan.ucp.module.bpm.api.definition.dto.BpmBusinessBindingDTO binding :
                processDefinitions.getEffectiveBusinessBindings(
                        com.lingan.ucp.nocode.api.workflow.FlowTasks.HANDLER)) {
            com.fasterxml.jackson.databind.JsonNode configuration;
            try {
                configuration = json.readTree(binding.configuration());
            } catch (java.io.IOException ex) {
                throw invalid("流程业务绑定配置无法读取，请先检查流程定义");
            }
            if (configuration == null) continue;
            com.fasterxml.jackson.databind.JsonNode resource = configuration.path("resource");
            if (!app.getId().toString().equals(resource.path("applicationId").asText())) continue;
            int versionNo = resource.path("applicationVersion").asInt(0);
            String location =
                    "流程“"
                            + binding.processName()
                            + "”V"
                            + binding.processVersion()
                            + " 的节点“"
                            + Objects.toString(binding.nodeName(), binding.nodeId())
                            + "”";
            if (versionNo < 1) throw invalid(location + "缺少固定应用版本，请修正流程绑定");
            if (Objects.equals(versionNo, alreadyChecked) || !checked.add(versionNo)) continue;
            NocodeApplicationVersionDO release = store.version(app.getId(), versionNo);
            if (release == null) throw invalid(location + "引用的应用 V" + versionNo + "已不存在");
            Snapshot snapshot;
            try {
                snapshot = json.readValue(release.getDefinitionJson(), Snapshot.class);
            } catch (java.io.IOException ex) {
                throw invalid(location + "的应用快照无法读取");
            }
            if (!DigestUtil.sha256Hex(write(snapshot)).equals(release.getChecksum())
                    || !release.getChecksum().equals(resource.path("applicationChecksum").asText()))
                throw invalid(location + "的应用快照校验和不匹配");
            try {
                validateSnapshot(app, snapshot);
            } catch (ServiceException ex) {
                throw invalid(location + "引用的应用 V" + versionNo + "已不兼容：" + ex.getMessage());
            }
        }
    }

    /** 规则的来源对象不必被应用引用；到这里说明它取不到最新发布版。 */
    private String ruleSourceUnavailable(String objectId) {
        String name = objectName(objectId);
        return "“" + name + "”未发布或已停用，应用无法读取它。请先在数据中心发布或启用“" + name + "”。";
    }

    private String objectName(String objectId) {
        com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO head =
                objectId != null && objectId.matches("[1-9][0-9]{0,18}")
                        ? objectLocks.selectById(Long.parseLong(objectId))
                        : null;
        return head == null ? "ID " + objectId : head.getObjectName();
    }

    /** 隐式可读的前提是目标对象有最新发布版；没有（未发布或已停用）时说清是哪个对象的哪条配置指过去的。 */
    private String unreadableTarget(
            DataCenter.Definition owner, String kind, String name, String targetObjectId) {
        String target = objectName(targetObjectId);
        return "对象“"
                + owner.objectName()
                + "”的"
                + kind
                + "“"
                + name
                + "”指向的对象“"
                + target
                + "”未发布或已停用，应用无法读取它。请先在数据中心发布或启用“"
                + target
                + "”。";
    }

    /** 运行端只读取发布指针指向的不可变快照；草稿保存不改变此结果。 */
    public Published published(String id) {
        return published(id, versionContext.version(id));
    }

    /** 指定版本用于受控任务办理；应用状态实时检查，不修改当前发布指针。只读作用域内同一事务（或同一请求的无事务段）只取锁、读取、解析一次。 */
    public Published published(String id, Integer requestedVersion) {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("application.published", id, requestedVersion),
                () -> readPublished(id, requestedVersion));
    }

    private Published readPublished(String id, Integer requestedVersion) {
        if (requestedVersion != null && requestedVersion < 1) throw invalid("应用发布版本无效");
        return transaction.execute(
                s -> {
                    // 运行端先取得目录共享锁，再锁应用头；发布/暂停持独占锁，不能倒序。
                    automations.lock(false);
                    var app = sharedHead(id);
                    if (!ApplicationStatusEnum.ACTIVE.matches(app.getStatus())
                            || app.getPublishedVersion() == null) throw invalid("应用未发布或已停用");
                    int versionNo =
                            requestedVersion == null ? app.getPublishedVersion() : requestedVersion;
                    NocodeApplicationVersionDO version = store.version(app.getId(), versionNo);
                    if (version == null) throw invalid("应用发布版本不存在");
                    try {
                        Snapshot snapshot =
                                json.readValue(version.getDefinitionJson(), Snapshot.class);
                        if (requestedVersion != null
                                && !DigestUtil.sha256Hex(write(snapshot))
                                        .equals(version.getChecksum()))
                            throw invalid("应用发布快照完整性校验失败");
                        Row presentation =
                                new Row(
                                        app.getId().toString(),
                                        snapshot.code(),
                                        snapshot.name(),
                                        snapshot.description(),
                                        snapshot.icon(),
                                        app.getStatus(),
                                        app.getLockVersion(),
                                        app.getPublishedVersion(),
                                        version.getCreateTime());
                        return new Published(
                                presentation,
                                version.getVersionNo(),
                                version.getChecksum(),
                                snapshot.definition(),
                                List.of());
                    } catch (java.io.IOException ex) {
                        throw invalid("应用发布快照无法读取");
                    }
                });
    }

    public Detail status(Revision request, String status, long actor) {
        requireDesigner(request.id(), actor);
        ApplicationStatusEnum.fromCode(status);
        return transaction.execute(
                s -> {
                    automations.lock(true);
                    objectLocks.lockTableName("nocode-design-write");
                    NocodeApplicationDO app = checkedHead(request);
                    validator.text(request.reason(), "操作说明", 1000);
                    if (ApplicationStatusEnum.ACTIVE.matches(status)
                            && Boolean.TRUE.equals(app.getRecoveryPending()))
                        throw invalid("恢复后的应用须人工编辑保存后使用“发布启用”，不能直接启用旧版本");
                    if (!ApplicationStatusEnum.ACTIVE.matches(status)
                            && !processes.active(app.getId()).isEmpty())
                        throw invalid("应用还有运行中的流程，暂不能停用");
                    Definition enabled = null;
                    if (ApplicationStatusEnum.ACTIVE.matches(status)) {
                        if (app.getPublishedVersion() == null) throw invalid("应用尚未发布，请发布应用后启用");
                        NocodeApplicationVersionDO version =
                                store.version(app.getId(), app.getPublishedVersion());
                        if (version == null) throw invalid("应用发布版本不存在，请重新发布");
                        Snapshot snapshot;
                        try {
                            snapshot = json.readValue(version.getDefinitionJson(), Snapshot.class);
                        } catch (java.io.IOException ex) {
                            throw invalid("应用发布快照无法读取");
                        }
                        if (!DigestUtil.sha256Hex(write(snapshot)).equals(version.getChecksum()))
                            throw invalid("应用发布快照完整性校验失败");
                        enabled = validateSnapshot(app, snapshot);
                        reportReferences(snapshot.definition(), actor);
                        validateEffectiveFlowBindings(app, app.getPublishedVersion());
                    }
                    store.status(app.getId(), status, Long.toString(actor));
                    // 按日期自动执行：停用期间的日期不补，重新启用从当天起算。
                    if (enabled != null) dateTriggers.arm(app.getId().toString(), enabled, actor);
                    else dateTriggers.disarm(app.getId().toString(), actor);
                    return detail(head(request.id(), false));
                });
    }

    /** 是否为创建人供上层授权使用；不把草稿中的字段当作身份依据。 */
    public boolean isOwner(String id, long actor) {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("application.owner", id, actor),
                () ->
                        transaction.execute(
                                s -> Long.toString(actor).equals(sharedHead(id).getCreator())));
    }

    /** 复用底座超级管理员角色；普通搭建者只能管理自己创建的应用。 */
    public boolean isPlatformAdmin(long actor) {
        return actor > 0 && permissions.hasAnyRoles(actor, RoleCodeEnum.SUPER_ADMIN.getCode());
    }

    public void requireDesigner(String id, long actor) {
        if (actor <= 0 || !isPlatformAdmin(actor) && !isOwner(id, actor))
            throw new org.springframework.security.access.AccessDeniedException("只能管理自己创建的应用");
    }

    /** 草稿保存允许保留不兼容的对象版本引用；发布入口使用严格校验，运行前必须处理差异。 */
    public Definition normalize(Definition definition) {
        return normalize(definition, true);
    }

    /** 引用校验不补对象或授权；草稿复制通过保存路径登记新应用自己的依赖。 */
    private List<ApplicationDashboards.Reference> reportReferences(
            Definition definition, long actor) {
        return ApplicationDashboardValidator.validate(
                definition, reportCatalog.getIfAvailable(), resourcesValidator, objects, actor);
    }

    /** 严格模式用于发布与恢复，宽松模式用于草稿保存：不兼容引用登记为待修复项，不阻断保存。 */
    public Definition normalize(Definition definition, boolean strict) {
        if (definition == null) return Definition.empty();
        List<ObjectReference> references =
                definition.objects() == null ? List.<ObjectReference>of() : definition.objects();
        List<ApplicationCenter.Resource> resources =
                definition.resources() == null
                        ? List.<com.lingan.ucp.nocode.api.ApplicationCenter.Resource>of()
                        : definition.resources();
        if (references.size() > 100 || resources.size() > 500)
            throw invalid("应用最多引用 100 个对象、配置 500 个资源");
        Set<String> ids = new HashSet<>();
        for (ObjectReference reference : references) {
            if (reference == null) throw invalid("对象引用不能为空");
            validator.id(reference.objectId(), "引用对象");
            if (!ids.add(reference.objectId())) throw invalid("同一应用不能重复引用对象");
            DataObjectApi.PublishedObject version =
                    objects.getVersion(reference.objectId(), reference.versionNo());
            if (!Objects.equals(reference.checksum(), version.checksum()))
                throw invalid("引用对象版本校验和不匹配，请重新选择版本");
            if (!strict) continue;
            DataObjectApi.PublishedObject current = objects.getVersion(reference.objectId(), null);
            List<String> incompatible =
                    ObjectContracts.breakingChanges(version.definition(), current.definition());
            if (!incompatible.isEmpty())
                throw invalid(
                        "对象“"
                                + version.definition().objectName()
                                + "”的固定版本已不兼容当前结构："
                                + String.join("；", incompatible)
                                + "。请同步对象版本并调整相关资源后重新发布。");
        }
        HashMap<String, DataCenter.Definition> calculationDefinitions =
                new HashMap<String, DataCenter.Definition>();
        for (ObjectReference ref : references)
            calculationDefinitions.put(
                    ref.objectId(),
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition());
        calculationDefinitions
                .values()
                .forEach(
                        d ->
                                com.lingan.ucp.nocode.metadata.service.formula.Calculations
                                        .validate(
                                                d,
                                                id -> {
                                                    var target = calculationDefinitions.get(id);
                                                    // 计算来源没被应用引用：按它的最新发布版校验（隐式只读）。
                                                    if (target == null)
                                                        target = impliedObjects.latest(id);
                                                    if (target == null)
                                                        throw invalid(
                                                                unreadableTarget(
                                                                        d,
                                                                        "计算",
                                                                        calculationField(d, id),
                                                                        id));
                                                    return target;
                                                }));
        return new Definition(
                List.copyOf(references), resourcesValidator.normalize(references, resources));
    }

    /** d 里计算来源指向 targetObjectId 的第一个字段的名称；找不到时用对象名兜底。 */
    private String calculationField(DataCenter.Definition d, String targetObjectId) {
        for (com.lingan.ucp.nocode.api.FieldDefinition field : d.fields()) {
            DataCenter.FieldOptions options = d.fieldOptions().get(field.id());
            if (options == null || options.calculation() == null) continue;
            try {
                if (targetObjectId.equals(
                        com.lingan.ucp.nocode.metadata.service.formula.Calculations.target(
                                d, options.calculation()))) return field.name();
            } catch (ServiceException unresolved) {
                // 计算指向的关系已不存在：由计算校验点名报错。
            }
        }
        return d.objectName();
    }

    private void dependencies(
            NocodeApplicationDO app, Definition definition, String scope, long actor) {
        String sourceKey = "application:" + app.getId() + ":" + scope;
        objects.removeDependencies(DependencyKindEnum.APP.getCode(), sourceKey, actor);
        for (ObjectReference reference : definition.objects()) {
            DataCenter.Definition object =
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition();
            List<String> fields = dependencyFields(object);
            // 草稿可保留旧版本引用；依赖登记仅保护当前仍存在的字段，不改写草稿或放宽发布校验。
            if ("draft".equals(scope)) {
                HashSet<String> available =
                        new HashSet<>(dependencyFields(objects.getPublished(reference.objectId())));
                fields.removeIf(field -> !available.contains(field));
            }
            objects.registerDependency(
                    new DataCenter.Dependency(
                            DependencyKindEnum.APP.getCode(),
                            sourceKey,
                            app.getAppName(),
                            reference.objectId(),
                            fields),
                    actor);
        }
    }

    private List<String> dependencyFields(DataCenter.Definition object) {
        ArrayList<String> fields = new ArrayList<String>();
        object.fields().forEach(f -> fields.add(f.id()));
        object.details().stream()
                .filter(d -> MemberStateEnum.ACTIVE.matches(d.state()))
                .forEach(d -> d.fields().forEach(f -> fields.add(f.id())));
        return fields;
    }

    private NocodeApplicationDO checkedHead(Revision request) {
        if (request == null) throw invalid("应用修订不能为空");
        NocodeApplicationDO app = head(request.id(), true);
        checkRevision(app, request.expectedRevision());
        return app;
    }

    private void checkRevision(NocodeApplicationDO app, Integer expected) {
        if (!Objects.equals(app.getLockVersion(), expected)) throw conflict();
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "应用已被其他操作修改，请刷新后重试");
    }

    /** 运行端只读入口共用的应用头：只读作用域内同一事务只取一次共享锁，锁保持到事务结束；返回对象只读不改。 */
    private NocodeApplicationDO sharedHead(String id) {
        return ReadRequestMemo.onceInTransaction(
                ReadRequestMemo.key("application.head", id), () -> head(id, false));
    }

    private NocodeApplicationDO head(String id, boolean write) {
        NocodeApplicationDO app = store.lock(validator.id(id, "应用 ID"), write);
        if (app == null) throw new ServiceException(NOT_FOUND, "应用不存在");
        return app;
    }

    private Detail detail(NocodeApplicationDO app) {
        Definition draft = read(app.getDesignJson());
        return new Detail(row(app), draft, issues(draft));
    }

    /** 草稿固定版本与对象最新结构的兼容差异；工作区提示待修复项，发布入口重新严格校验。 */
    private List<ObjectIssue> issues(Definition draft) {
        ArrayList<ObjectIssue> result = new ArrayList<ObjectIssue>();
        for (ObjectReference reference : draft.objects()) {
            DataObjectApi.PublishedObject pinned =
                    objects.getVersion(reference.objectId(), reference.versionNo());
            DataObjectApi.PublishedObject latest = objects.getVersion(reference.objectId(), null);
            if (latest.versionNo() == pinned.versionNo()) continue;
            List<String> messages =
                    ObjectContracts.breakingChanges(pinned.definition(), latest.definition());
            if (messages.isEmpty()) continue;
            result.add(
                    new ObjectIssue(
                            reference.objectId(),
                            pinned.definition().objectName(),
                            reference.versionNo(),
                            latest.versionNo(),
                            messages));
        }
        return List.copyOf(result);
    }

    private Row row(NocodeApplicationDO app) {
        return new Row(
                app.getId().toString(),
                app.getAppCode(),
                app.getAppName(),
                app.getDescription(),
                app.getIcon(),
                app.getStatus(),
                app.getLockVersion(),
                app.getPublishedVersion(),
                app.getUpdateTime(),
                app.getCategory(),
                Boolean.TRUE.equals(app.getRecoveryPending()),
                Boolean.TRUE.equals(app.getRecoveryNeedsEdit()));
    }

    public Definition read(String value) {
        try {
            return json.readValue(value, Definition.class);
        } catch (java.io.IOException ex) {
            throw invalid("应用配置无法读取");
        }
    }

    private String write(Object value) {
        try {
            String result = json.writeValueAsString(value);
            if (result.length() > 2_000_000) throw invalid("应用配置过大，请拆分页面");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw invalid("应用配置无法保存");
        }
    }

    /** 草稿引用同步的结果；synced 为假时 reason 是没同步上的原因（草稿原样未动）。 */
    public record DraftSync(boolean synced, String reason) {}

    /**
     * 自动跟随：把已发布快照里对 objectId 的引用提到新版本并发布成应用新版本。
     *
     * <p>仅供 {@link ApplicationFollowService} 在已持目录独占锁与设计写锁的事务内调用。不做「是否应用设计者」检查——开关开着就是设计者预先给的授权。
     * 发布的永远是「旧发布快照 + 升级」，不是草稿：别人没发布的改动不会被顺带发出去。完整经过发布校验、依赖登记、建新版本、移动发布指针，与人工发布走同一条路。
     */
    public Detail followObject(
            String applicationId,
            String objectId,
            int toVersion,
            String toChecksum,
            String reason,
            long actor) {
        return transaction.execute(
                s -> {
                    NocodeApplicationDO app = head(applicationId, true);
                    if (app.getPublishedVersion() == null) throw invalid("应用尚未发布，无法跟随");
                    NocodeApplicationVersionDO version =
                            store.version(app.getId(), app.getPublishedVersion());
                    if (version == null) throw invalid("应用发布版本不存在");
                    Snapshot snapshot;
                    try {
                        snapshot = json.readValue(version.getDefinitionJson(), Snapshot.class);
                    } catch (java.io.IOException ex) {
                        throw invalid("应用发布快照无法读取");
                    }
                    ObjectReference pinned =
                            snapshot.definition().objects().stream()
                                    .filter(reference -> reference.objectId().equals(objectId))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("应用的已发布版本没有引用这个对象"));
                    Definition upgraded =
                            ApplicationUpgrader.apply(
                                    snapshot.definition(),
                                    objectId,
                                    toVersion,
                                    toChecksum,
                                    objects.getVersion(objectId, pinned.versionNo()).definition(),
                                    objects.getVersion(objectId, toVersion).definition());
                    return publishSnapshot(
                            app,
                            new Snapshot(
                                    snapshot.code(),
                                    snapshot.name(),
                                    snapshot.description(),
                                    snapshot.icon(),
                                    upgraded),
                            reason,
                            actor);
                });
    }

    /**
     * 把草稿里落后的 objectId 引用提到 toVersion；提版后通不过草稿校验就不动草稿，返回原因。草稿没有引用这个对象、或已不落后，视为已同步。
     *
     * <p>调用方同 {@link #followObject}。修订号加一，草稿依赖重新登记。
     */
    public DraftSync syncDraft(
            String applicationId, String objectId, int toVersion, String toChecksum, long actor) {
        // 用保存点包住：草稿校验是在别的服务自带的参与事务里抛错的，那会把外层事务标成只能回滚；只有回滚到保存点才清得掉这个标记。
        TransactionTemplate savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        try {
            return savepoint.execute(
                    s -> {
                        NocodeApplicationDO app = head(applicationId, true);
                        Definition draft = read(app.getDesignJson());
                        ObjectReference pinned =
                                draft.objects().stream()
                                        .filter(reference -> reference.objectId().equals(objectId))
                                        .findFirst()
                                        .orElse(null);
                        if (pinned == null || pinned.versionNo() >= toVersion)
                            return new DraftSync(true, null);
                        Definition synced =
                                normalize(
                                        ApplicationUpgrader.apply(
                                                draft,
                                                objectId,
                                                toVersion,
                                                toChecksum,
                                                objects.getVersion(objectId, pinned.versionNo())
                                                        .definition(),
                                                objects.getVersion(objectId, toVersion)
                                                        .definition()),
                                        false);
                        app.setDesignJson(write(synced));
                        if (store.updateDraft(app, app.getLockVersion(), Long.toString(actor)) != 1)
                            throw conflict();
                        dependencies(app, synced, "draft", actor);
                        return new DraftSync(true, null);
                    });
        } catch (ServiceException rejected) {
            return new DraftSync(false, rejected.getMessage());
        }
    }
}
