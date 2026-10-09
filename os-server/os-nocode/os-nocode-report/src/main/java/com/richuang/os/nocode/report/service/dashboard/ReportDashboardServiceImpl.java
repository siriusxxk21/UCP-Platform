package com.richuang.os.nocode.report.service.dashboard;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.*;
import com.richuang.os.nocode.report.service.authorization.*;

import jakarta.annotation.Resource;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 仪表板协作与查看沿用资源 ACL，所有受众仍执行数据集对象上限与成员策略。 */
@Service
public class ReportDashboardServiceImpl implements ReportDashboardService {
    @Resource private ReportDashboardMapper store;
    @Resource private ReportDashboardNavigation navigation;
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportResourceAccess access;
    @Resource private ReportAuthorizationMapper authorization;
    @Resource private ReportPrincipals principals;
    // 数据集受控查询依赖本服务；启用校验延迟解析其授权入口，避免构造环。
    @Resource @Lazy private ReportDatasetAuthorizationService dataAuthorization;
    @Resource private com.richuang.os.nocode.report.service.folder.ReportFolderService folders;

    @Resource
    private com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceService sources;

    @Resource private ReportJson json;
    @Resource private DraftValidator validator;
    @Resource private PermissionCommonApi permissions;
    @Resource private PlatformTransactionManager manager;

    private void permission(long actor, String action) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, "nocode:report:" + action))
            throw new AccessDeniedException("没有仪表板操作权限");
    }

    private ReportDashboardDO head(String id, boolean write) {
        ReportDashboardDO row = store.lock(validator.id(id, "仪表板"), write);
        if (row == null) throw new AccessDeniedException("仪表板不存在或无权访问");
        return row;
    }

    private void require(ReportDashboardDO row, long actor, ReportResourceActionEnum action) {
        access.require(
                ReportResourceKindEnum.DASHBOARD, row.getId(), row.getOwnerId(), actor, action);
    }

    private Set<ReportResourceActionEnum> actions(ReportDashboardDO row, long actor) {
        return access.actions(
                ReportResourceKindEnum.DASHBOARD,
                row.getId(),
                row.getOwnerId(),
                principals.current(actor).principals());
    }

    private void draftAccess(ReportDashboardDO row, long actor) {
        Set<ReportResourceActionEnum> actions = actions(row, actor);
        if (!actions.contains(ReportResourceActionEnum.EDIT)
                && !actions.contains(ReportResourceActionEnum.PUBLISH))
            throw new AccessDeniedException("没有仪表板草稿查看权限");
    }

    private com.richuang.os.framework.common.exception.ServiceException conflict() {
        return new com.richuang.os.framework.common.exception.ServiceException(
                CONFLICT, "仪表板已被修改，请刷新后重试");
    }

    private void revision(ReportDashboardDO row, int expected) {
        if (row.getLockVersion() != expected) throw conflict();
    }

    /** 资源头状态覆盖全部固定历史版本，也覆盖预览和导出取数。 */
    private void active(ReportDashboardDO row) {
        if (!ReportResourceStatusEnum.ACTIVE.matches(row.getStatus())) throw invalid("仪表板已停用");
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ReportDashboards.Content content(String text) {
        return json.read(text, new TypeReference<ReportDashboards.Content>() {});
    }

    private ReportDashboards.Detail detail(ReportDashboardDO row, long actor) {
        ReportDashboardVersionDO version =
                row.getPublishedVersion() == null
                        ? null
                        : store.version(row.getId(), row.getPublishedVersion());
        return new ReportDashboards.Detail(
                row.getId().toString(),
                row.getOwnerId().toString(),
                row.getLockVersion(),
                row.getPublishedVersion(),
                row.getDraftChecksum(),
                version == null || !version.getChecksum().equals(row.getDraftChecksum()),
                content(row.getDraftJson()),
                capabilities(actions(row, actor), actor),
                row.getStatus(),
                row.getFolderId() == null ? null : row.getFolderId().toString());
    }

    @Override
    public PageResult<ReportDashboards.Detail> page(int page, int size, String search, long actor) {
        permission(actor, "query");
        if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        String term = search == null ? "" : search.trim();
        if (term.length() > 80) throw invalid("搜索名称过长");
        com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardDO> result =
                store.page(
                        new Page<>(page, size),
                        actor,
                        "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        return new PageResult<>(
                result.getRecords().stream().map(row -> detail(row, actor)).toList(),
                result.getTotal());
    }

    @Override
    public PageResult<ReportDashboards.AvailableItem> availablePage(
            int page, int size, String search, long actor) {
        return availablePage(page, size, search, null, null, actor);
    }

    @Override
    public PageResult<ReportDashboards.AvailableItem> availablePage(
            int page, int size, String search, String folderId, String status, long actor) {
        permission(actor, "query");
        if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        String term = search == null ? "" : search.trim();
        if (term.length() > 80) throw invalid("搜索名称过长");
        Long folder =
                folderId == null ? null : "0".equals(folderId) ? 0L : validator.id(folderId, "目录");
        String state = status == null ? null : ReportResourceStatusEnum.fromCode(status).getCode();
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        PageResult<ReportDashboards.AvailableItem> result =
                new TransactionTemplate(manager)
                        .execute(
                                tx -> {
                                    datasets.designReadLock();
                                    com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardDO>
                                            found =
                                                    store.availablePage(
                                                            new Page<>(page, size),
                                                            actor,
                                                            identity.principals(),
                                                            permissions.hasAnyPermissions(
                                                                    actor, "nocode:report:manage"),
                                                            "%"
                                                                    + term.replace("!", "!!")
                                                                            .replace("%", "!%")
                                                                            .replace("_", "!_")
                                                                    + "%",
                                                            folder,
                                                            state);
                                    return new PageResult<>(
                                            found.getRecords().stream()
                                                    .map(row -> available(row, identity))
                                                    .toList(),
                                            found.getTotal());
                                });
        principals.unchanged(identity);
        return result;
    }

    private ReportDashboards.AvailableItem available(
            ReportDashboardDO row, ReportPrincipals.Snapshot identity) {
        Set<ReportResourceActionEnum> actions =
                access.actions(
                        ReportResourceKindEnum.DASHBOARD,
                        row.getId(),
                        row.getOwnerId(),
                        identity.principals());
        long actor = identity.actor();
        ReportDashboards.Capabilities capabilities = capabilities(actions, actor);
        boolean draft =
                actions.contains(ReportResourceActionEnum.EDIT)
                        || actions.contains(ReportResourceActionEnum.PUBLISH);
        ReportDashboardVersionDO version =
                row.getPublishedVersion() == null
                        ? null
                        : store.version(row.getId(), row.getPublishedVersion());
        if (!draft && version == null) throw invalid("仪表板发布版本不存在");
        ReportDashboards.Content definition =
                content(draft ? row.getDraftJson() : version.getDefinitionJson());
        return new ReportDashboards.AvailableItem(
                row.getId().toString(),
                definition.name(),
                row.getOwnerId().toString(),
                row.getPublishedVersion(),
                definition.charts().size(),
                draft && (version == null || !version.getChecksum().equals(row.getDraftChecksum())),
                draft ? row.getLockVersion() : null,
                capabilities,
                row.getStatus(),
                row.getFolderId() == null ? null : row.getFolderId().toString());
    }

    private ReportDashboards.Capabilities capabilities(
            Set<ReportResourceActionEnum> actions, long actor) {
        boolean canView =
                actions.contains(ReportResourceActionEnum.VIEW)
                        && permissions.hasAnyPermissions(actor, "nocode:report:query");
        return new ReportDashboards.Capabilities(
                canView,
                actions.contains(ReportResourceActionEnum.EDIT)
                        && permissions.hasAnyPermissions(actor, "nocode:report:update"),
                actions.contains(ReportResourceActionEnum.PUBLISH)
                        && permissions.hasAnyPermissions(actor, "nocode:report:publish"),
                actions.contains(ReportResourceActionEnum.GRANT)
                        && permissions.hasAnyPermissions(actor, "nocode:report:manage"),
                canView && actions.contains(ReportResourceActionEnum.EXPORT),
                actions.contains(ReportResourceActionEnum.DELETE)
                        && permissions.hasAnyPermissions(actor, "nocode:report:manage"));
    }

    @Override
    public ReportDashboards.AvailableItem publishedSummary(String id, long actor) {
        permission(actor, "query");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            require(row, actor, ReportResourceActionEnum.VIEW);
                            active(row);
                            ReportDashboards.Release published = currentRelease(row, null, null);
                            return new ReportDashboards.AvailableItem(
                                    row.getId().toString(),
                                    published.content().name(),
                                    row.getOwnerId().toString(),
                                    published.versionNo(),
                                    published.content().charts().size(),
                                    false,
                                    null,
                                    capabilities(actions(row, actor), actor),
                                    row.getStatus(),
                                    row.getFolderId() == null
                                            ? null
                                            : row.getFolderId().toString());
                        });
    }

    @Override
    public ReportAuthorization.ResourcePolicy resourcePolicy(String id, long actor) {
        permission(actor, "manage");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            require(row, actor, ReportResourceActionEnum.GRANT);
                            return access.policy(
                                    ReportResourceKindEnum.DASHBOARD.getCode(), row.getId());
                        });
    }

    @Override
    public ReportAuthorization.ResourcePolicy saveResource(
            ReportAuthorization.SaveDashboardResource request, long actor) {
        permission(actor, "manage");
        if (request == null) throw invalid("缺少仪表板资源授权配置");
        String reason = validator.text(request.reason(), "授权变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.GRANT);
                            ReportAuthorization.ResourcePolicy before =
                                    access.policy(
                                            ReportResourceKindEnum.DASHBOARD.getCode(),
                                            row.getId());
                            if (request.expectedRevision() < 0
                                    || request.expectedRevision() != before.revision())
                                throw conflict();
                            if (request.members() == null || request.members().size() > 200)
                                throw invalid("资源成员最多 200 项");
                            Set<String> unique = new HashSet<>();
                            Set<ReportResourceActionEnum> allowed =
                                    EnumSet.of(
                                            ReportResourceActionEnum.VIEW,
                                            ReportResourceActionEnum.EXPORT,
                                            ReportResourceActionEnum.EDIT,
                                            ReportResourceActionEnum.PUBLISH,
                                            ReportResourceActionEnum.GRANT,
                                            ReportResourceActionEnum.DELETE);
                            for (ReportAuthorization.ResourceMember member : request.members()) {
                                if (member == null
                                        || !unique.add(
                                                principals.validate(
                                                        member.principalKind(),
                                                        member.principalId())))
                                    throw invalid("资源成员为空或重复");
                                if (member.actions() == null || member.actions().isEmpty())
                                    throw invalid("资源操作权限不能为空");
                                for (String action : member.actions())
                                    if (!allowed.contains(
                                            ReportResourceActionEnum.fromCode(action)))
                                        throw invalid("此操作不属于仪表板资源权限");
                                if (member.actions()
                                                .contains(ReportResourceActionEnum.EXPORT.getCode())
                                        && !member.actions()
                                                .contains(ReportResourceActionEnum.VIEW.getCode()))
                                    throw invalid("仪表板导出授权同时需要查看权限");
                            }
                            authorization.saveAcl(
                                    ReportResourceKindEnum.DASHBOARD.getCode(),
                                    row.getId(),
                                    json.write(request.members()),
                                    Long.toString(actor));
                            ReportAuthorization.ResourcePolicy after =
                                    access.policy(
                                            ReportResourceKindEnum.DASHBOARD.getCode(),
                                            row.getId());
                            ReportOperationLogDO log = new ReportOperationLogDO();
                            log.setResourceKind(ReportResourceKindEnum.DASHBOARD.getCode());
                            log.setResourceId(row.getId());
                            log.setAction(ReportAuditOperationEnum.AUTHORIZE.getCode());
                            log.setRevision(after.revision());
                            log.setBeforeJson(json.write(before));
                            log.setAfterJson(json.write(after));
                            log.setReason(reason);
                            datasets.audit(log, Long.toString(actor));
                            return after;
                        });
    }

    @Override
    public ReportDashboards.Detail get(String id, long actor) {
        permission(actor, "query");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            draftAccess(row, actor);
                            return detail(row, actor);
                        });
    }

    @Override
    public ReportDashboards.Detail save(ReportDashboards.Save request, long actor) {
        if (request == null) throw invalid("缺少仪表板配置");
        permission(actor, request.id() == null ? "create" : "update");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row;
                            if (request.id() == null) {
                                if (request.expectedRevision() != 0) throw conflict();
                                row = new ReportDashboardDO();
                                row.setOwnerId(actor);
                                row.setFolderId(
                                        folders.destination(
                                                request.folderId(),
                                                actor,
                                                ReportResourceKindEnum.DASHBOARD));
                            } else {
                                row = head(request.id(), true);
                                require(row, actor, ReportResourceActionEnum.EDIT);
                                revision(row, request.expectedRevision());
                                if (request.folderId() != null
                                        && !Objects.equals(
                                                request.folderId(),
                                                row.getFolderId() == null
                                                        ? null
                                                        : row.getFolderId().toString()))
                                    throw invalid("请通过移动操作修改目录");
                            }
                            ReportDashboards.Content definition =
                                    validate(request.content(), actor, false);
                            row.setName(definition.name());
                            row.setDraftJson(json.write(definition));
                            row.setDraftChecksum(digest(row.getDraftJson()));
                            if (row.getId() == null) store.create(row, Long.toString(actor));
                            else if (store.save(
                                            row, request.expectedRevision(), Long.toString(actor))
                                    != 1) throw conflict();
                            dependencies(row, definition, 0, actor);
                            audit(
                                    store.lock(row.getId(), false),
                                    ReportAuditOperationEnum.SAVE,
                                    actor);
                            return detail(store.lock(row.getId(), false), actor);
                        });
    }

    @Override
    public ReportDashboards.Release publish(ReportDashboards.Publish request, long actor) {
        permission(actor, "publish");
        if (request == null) throw invalid("缺少发布参数");
        String key = validator.text(request.requestId(), "发布请求标识", 80);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.PUBLISH);
                            ReportDashboardVersionDO previous =
                                    store.request(row.getId(), Long.toString(actor), key);
                            if (previous != null) {
                                if (!previous.getChecksum().equals(row.getDraftChecksum()))
                                    throw conflict();
                                return release(previous);
                            }
                            revision(row, request.expectedRevision());
                            ReportDashboards.Content definition =
                                    validate(content(row.getDraftJson()), actor, true);
                            ReportDashboardVersionDO version = new ReportDashboardVersionDO();
                            version.setDashboardId(row.getId());
                            version.setVersionNo(
                                    row.getPublishedVersion() == null
                                            ? 1
                                            : row.getPublishedVersion() + 1);
                            version.setDefinitionJson(row.getDraftJson());
                            version.setChecksum(row.getDraftChecksum());
                            version.setRequestId(key);
                            store.createVersion(version, Long.toString(actor));
                            store.publish(
                                    row.getId(), version.getVersionNo(), Long.toString(actor));
                            dependencies(row, definition, version.getVersionNo(), actor);
                            navigation.synchronize(
                                    row.getId().toString(), definition.navigation(), actor);
                            audit(
                                    store.lock(row.getId(), false),
                                    ReportAuditOperationEnum.PUBLISH,
                                    actor);
                            return release(version);
                        });
    }

    /** 复制只重建草稿身份，固定数据集和字段身份保留；不复制授权、发布版本或个人偏好。 */
    @Override
    public ReportDashboards.Detail copy(ReportDashboards.Copy request, long actor) {
        permission(actor, "create");
        permission(actor, "query");
        if (request == null) throw invalid("缺少复制配置");
        String name = validator.text(request.name(), "仪表板名称", 80);
        String reason = validator.text(request.reason(), "复制说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO source = head(request.id(), false);
                            draftAccess(source, actor);
                            revision(source, request.expectedRevision());
                            ReportDashboards.Detail before = detail(source, actor);
                            ReportDashboards.Content copied =
                                    validate(copyContent(before.draft(), name), actor, false);
                            ReportDashboardDO row = new ReportDashboardDO();
                            row.setOwnerId(actor);
                            row.setFolderId(
                                    folders.destination(
                                            before.folderId(),
                                            actor,
                                            ReportResourceKindEnum.DASHBOARD));
                            setDraft(row, copied);
                            store.create(row, Long.toString(actor));
                            dependencies(row, copied, 0, actor);
                            ReportDashboards.Detail after =
                                    detail(store.lock(row.getId(), false), actor);
                            lifecycleAudit(
                                    row.getId(),
                                    ReportAuditOperationEnum.COPY,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    private ReportDashboards.Content copyContent(ReportDashboards.Content source, String name) {
        Map<String, String> chartIds = new LinkedHashMap<>();
        source.charts().forEach(chart -> chartIds.put(chart.id(), UUID.randomUUID().toString()));
        List<ReportDashboards.Chart> charts = new ArrayList<>();
        for (ReportDashboards.Chart chart : source.charts()) {
            List<ReportDashboards.Link> links =
                    chart.links() == null
                            ? null
                            : chart.links().stream()
                                    .map(
                                            link ->
                                                    new ReportDashboards.Link(
                                                            chartIds.get(link.targetChartId()),
                                                            link.sourceFieldId(),
                                                            link.targetFieldId()))
                                    .toList();
            charts.add(
                    new ReportDashboards.Chart(
                            chartIds.get(chart.id()),
                            chart.title(),
                            chart.display(),
                            chart.dataset(),
                            chart.dimensions(),
                            chart.metricIds(),
                            chart.x(),
                            chart.y(),
                            chart.w(),
                            chart.h(),
                            chart.columnDimensions(),
                            chart.pivot(),
                            chart.drillDimensions(),
                            links));
        }
        List<ReportDashboards.Filter> filters =
                source.filters() == null
                        ? null
                        : source.filters().stream()
                                .map(
                                        filter ->
                                                new ReportDashboards.Filter(
                                                        UUID.randomUUID().toString(),
                                                        filter.name(),
                                                        filter.kind(),
                                                        filter.mappings().stream()
                                                                .map(
                                                                        mapping ->
                                                                                new ReportDashboards
                                                                                        .Mapping(
                                                                                        chartIds
                                                                                                .get(
                                                                                                        mapping
                                                                                                                .chartId()),
                                                                                        mapping
                                                                                                .fieldId()))
                                                                .toList(),
                                                        filter.defaultValue()))
                                .toList();
        return new ReportDashboards.Content(
                source.schemaVersion(), name, source.description(), List.copyOf(charts), filters);
    }

    private void setDraft(ReportDashboardDO row, ReportDashboards.Content definition) {
        row.setName(definition.name());
        row.setDraftJson(json.write(definition));
        row.setDraftChecksum(digest(row.getDraftJson()));
    }

    @Override
    public ReportDashboards.Detail move(ReportDashboards.Move request, long actor) {
        permission(actor, "update");
        if (request == null) throw invalid("缺少移动配置");
        String reason = validator.text(request.reason(), "移动说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.EDIT);
                            revision(row, request.expectedRevision());
                            Long folder =
                                    folders.destination(
                                            request.folderId(),
                                            actor,
                                            ReportResourceKindEnum.DASHBOARD);
                            ReportDashboards.Detail before = detail(row, actor);
                            if (store.move(
                                            row.getId(),
                                            request.expectedRevision(),
                                            folder,
                                            Long.toString(actor))
                                    != 1) throw conflict();
                            ReportDashboards.Detail after =
                                    detail(store.lock(row.getId(), false), actor);
                            lifecycleAudit(
                                    row.getId(),
                                    ReportAuditOperationEnum.MOVE,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    /** 启用检查当前发布固定依赖和实时共享上限，不用未发布草稿替代发布配置。 */
    @Override
    public ReportDashboards.Detail status(ReportDashboards.ChangeStatus request, long actor) {
        permission(actor, "manage");
        if (request == null) throw invalid("缺少状态配置");
        ReportResourceStatusEnum status = ReportResourceStatusEnum.fromCode(request.status());
        String reason = validator.text(request.reason(), "状态变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.PUBLISH);
                            revision(row, request.expectedRevision());
                            ReportDashboards.Detail before = detail(row, actor);
                            if (status == ReportResourceStatusEnum.ACTIVE) {
                                if (row.getPublishedVersion() == null) throw invalid("仪表板尚未发布");
                                ReportDashboardVersionDO published =
                                        requiredVersion(row.getId(), row.getPublishedVersion());
                                ReportDashboards.Content definition =
                                        validate(
                                                content(published.getDefinitionJson()),
                                                actor,
                                                true);
                                Set<ReportDashboards.Dataset> references = new LinkedHashSet<>();
                                definition
                                        .charts()
                                        .forEach(chart -> references.add(chart.dataset()));
                                for (ReportDashboards.Dataset reference : references) {
                                    long dataset = validator.id(reference.id(), "数据集");
                                    ReportDatasetVersionDO fixed =
                                            datasets.version(dataset, reference.versionNo());
                                    ReportDatasets.Content configuration =
                                            json.read(
                                                    fixed.getDefinitionJson(),
                                                    new TypeReference<ReportDatasets.Content>() {});
                                    dataAuthorization.requireCeilings(
                                            dataset, configuration.source());
                                }
                            }
                            if (store.status(
                                            row.getId(),
                                            request.expectedRevision(),
                                            status.getCode(),
                                            Long.toString(actor))
                                    != 1) throw conflict();
                            ReportDashboards.Detail after =
                                    detail(store.lock(row.getId(), false), actor);
                            lifecycleAudit(
                                    row.getId(),
                                    ReportAuditOperationEnum.STATUS,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public PageResult<ReportDashboards.Release> releases(
            String id, int page, int size, long actor) {
        permission(actor, "query");
        if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            draftAccess(row, actor);
                            com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardVersionDO>
                                    found = store.versions(new Page<>(page, size), row.getId());
                            return new PageResult<>(
                                    found.getRecords().stream().map(this::release).toList(),
                                    found.getTotal());
                        });
    }

    private ReportDashboardVersionDO requiredVersion(long id, int version) {
        ReportDashboardVersionDO found = store.version(id, version);
        if (found == null) throw invalid("仪表板历史版本不存在");
        return found;
    }

    /** 恢复增加新草稿修订，发布指针和历史快照保持原值。 */
    @Override
    public ReportDashboards.Detail restore(ReportDashboards.Restore request, long actor) {
        permission(actor, "update");
        if (request == null || request.versionNo() < 1) throw invalid("恢复版本无效");
        String reason = validator.text(request.reason(), "恢复说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.EDIT);
                            revision(row, request.expectedRevision());
                            ReportDashboards.Detail before = detail(row, actor);
                            ReportDashboards.Content restored =
                                    validate(
                                            content(
                                                    requiredVersion(
                                                                    row.getId(),
                                                                    request.versionNo())
                                                            .getDefinitionJson()),
                                            actor,
                                            false);
                            setDraft(row, restored);
                            if (store.save(row, request.expectedRevision(), Long.toString(actor))
                                    != 1) throw conflict();
                            dependencies(row, restored, 0, actor);
                            ReportDashboards.Detail after =
                                    detail(store.lock(row.getId(), false), actor);
                            lifecycleAudit(
                                    row.getId(),
                                    ReportAuditOperationEnum.RESTORE,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportDashboards.DeletePreview deletePreview(String id, long actor) {
        permission(actor, "manage");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designReadLock();
                            ReportDashboardDO row = head(id, false);
                            require(row, actor, ReportResourceActionEnum.DELETE);
                            long references = store.incomingReferences(row.getId());
                            return new ReportDashboards.DeletePreview(
                                    row.getId().toString(),
                                    row.getLockVersion(),
                                    references == 0,
                                    references);
                        });
    }

    /** 设计锁保护全部草稿及历史引用检查；只释放本看板发出的依赖，保留版本、授权和审计。 */
    @Override
    public ReportDashboards.Deleted delete(ReportDashboards.Delete request, long actor) {
        permission(actor, "manage");
        if (request == null) throw invalid("缺少删除配置");
        String reason = validator.text(request.reason(), "删除说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDashboardDO row = head(request.id(), true);
                            require(row, actor, ReportResourceActionEnum.DELETE);
                            revision(row, request.expectedRevision());
                            if (store.incomingReferences(row.getId()) > 0)
                                throw new com.richuang.os.framework.common.exception
                                        .ServiceException(
                                        CONFLICT, "仪表板仍被其他资源引用，请解除全部草稿及历史版本引用后重试");
                            ReportDashboards.Detail before = detail(row, actor);
                            if (store.remove(
                                            row.getId(),
                                            request.expectedRevision(),
                                            Long.toString(actor))
                                    != 1) throw conflict();
                            navigation.remove(row.getId().toString(), actor);
                            store.clearDependencies(row.getId(), Long.toString(actor));
                            ReportDashboards.Deleted after =
                                    new ReportDashboards.Deleted(
                                            row.getId().toString(),
                                            request.expectedRevision() + 1,
                                            true);
                            lifecycleAudit(
                                    row.getId(),
                                    ReportAuditOperationEnum.DELETE,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    private void lifecycleAudit(
            long id,
            ReportAuditOperationEnum operation,
            Object before,
            Object after,
            int revision,
            String reason,
            long actor) {
        ReportOperationLogDO log = new ReportOperationLogDO();
        log.setResourceKind(ReportResourceKindEnum.DASHBOARD.getCode());
        log.setResourceId(id);
        log.setAction(operation.getCode());
        log.setRevision(revision);
        log.setBeforeJson(json.write(before));
        log.setAfterJson(json.write(after));
        log.setReason(reason);
        datasets.audit(log, Long.toString(actor));
    }

    private ReportDashboards.Release release(ReportDashboardVersionDO row) {
        return new ReportDashboards.Release(
                row.getDashboardId().toString(),
                row.getVersionNo(),
                row.getChecksum(),
                content(row.getDefinitionJson()));
    }

    @Override
    public ReportDashboards.Release published(
            String id, Integer number, String checksum, long actor) {
        permission(actor, "query");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            require(row, actor, ReportResourceActionEnum.VIEW);
                            active(row);
                            return currentRelease(row, number, checksum);
                        });
    }

    /** 先资源 ACL/状态，再版本守卫；客户端不能用旧版或旧摘要继续执行当前入口。 */
    private ReportDashboards.Release currentRelease(
            ReportDashboardDO row, Integer version, String checksum) {
        if (row.getPublishedVersion() == null) throw invalid("仪表板发布版本不存在");
        ReportDashboardVersionDO current = requiredVersion(row.getId(), row.getPublishedVersion());
        if (version != null && !version.equals(row.getPublishedVersion())
                || checksum != null && !checksum.equals(current.getChecksum()))
            throw versionChanged("仪表板发布版本或校验和已变化，请刷新后重试");
        return release(current);
    }

    private ReportDashboards.Release fixedRelease(
            ReportDashboardDO row, int version, String checksum) {
        if (version < 1 || checksum == null || checksum.isBlank())
            throw invalid("固定引用必须指定发布版本及校验和");
        ReportDashboardVersionDO fixed = requiredVersion(row.getId(), version);
        if (!checksum.equals(fixed.getChecksum())) throw invalid("仪表板固定版本校验和失效");
        return release(fixed);
    }

    @Override
    public ReportDashboards.Release publishedFixed(
            String id, int version, String checksum, long actor) {
        if (actor <= 0) throw invalid("请先登录");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(id, false);
                            require(row, actor, ReportResourceActionEnum.VIEW);
                            active(row);
                            return fixedRelease(row, version, checksum);
                        });
    }

    @Override
    public ReportDashboards.Resolved resolve(ReportDashboards.Query request, long actor) {
        return resolve(request, actor, false);
    }

    @Override
    public ReportDashboards.Resolved resolve(
            ReportDashboards.Query request, long actor, boolean exporting) {
        return resolve(
                request,
                actor,
                exporting,
                request != null && request.preview()
                        ? ReportDashboardEntryEnum.PREVIEW
                        : ReportDashboardEntryEnum.CURRENT);
    }

    @Override
    public ReportDashboards.Resolved resolveFixed(
            ReportDashboards.Query request, long actor, boolean exporting) {
        if (request == null || request.preview()) throw invalid("内部固定版本入口不接受草稿预览");
        return resolve(request, actor, exporting, ReportDashboardEntryEnum.APPLICATION_FIXED);
    }

    @Override
    public ReportDashboards.Resolved refresh(
            ReportDashboards.Resolved resolved, long actor, boolean exporting) {
        if (resolved == null || resolved.request() == null) throw invalid("缺少仪表板受控查询上下文");
        if (resolved.entry() == ReportDashboardEntryEnum.APPLICATION_FIXED)
            return resolveFixed(resolved.request(), actor, exporting);
        return resolve(resolved.request(), actor, exporting);
    }

    private ReportDashboards.Resolved resolve(
            ReportDashboards.Query request,
            long actor,
            boolean exporting,
            ReportDashboardEntryEnum entry) {
        if (request == null) throw invalid("缺少图表查询参数");
        if (entry == ReportDashboardEntryEnum.APPLICATION_FIXED) {
            if (actor <= 0) throw invalid("请先登录");
        } else permission(actor, "query");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDashboardDO row = head(request.id(), false);
                            if (exporting) require(row, actor, ReportResourceActionEnum.EXPORT);
                            ReportDashboards.Content definition;
                            String stamp;
                            if (request.preview()) {
                                draftAccess(row, actor);
                                active(row);
                                if (request.versionNo() != null || request.checksum() != null)
                                    throw invalid("草稿预览不能指定发布版本");
                                definition = content(row.getDraftJson());
                                stamp = row.getDraftChecksum();
                            } else {
                                if (request.versionNo() == null || request.checksum() == null)
                                    throw invalid("运行图表必须指定发布版本");
                                require(row, actor, ReportResourceActionEnum.VIEW);
                                active(row);
                                ReportDashboards.Release release =
                                        entry == ReportDashboardEntryEnum.APPLICATION_FIXED
                                                ? fixedRelease(
                                                        row,
                                                        request.versionNo(),
                                                        request.checksum())
                                                : currentRelease(
                                                        row,
                                                        request.versionNo(),
                                                        request.checksum());
                                definition = release.content();
                                stamp = release.checksum();
                            }
                            ReportDashboards.Chart chart =
                                    definition.charts().stream()
                                            .filter(c -> c.id().equals(request.chartId()))
                                            .findFirst()
                                            .orElseThrow(() -> invalid("图表不属于仪表板"));
                            return new ReportDashboards.Resolved(
                                    request, row.getLockVersion(), stamp, chart, definition, entry);
                        });
    }

    @Override
    public void recheck(ReportDashboards.Resolved resolved, long actor) {
        recheck(resolved, actor, false);
    }

    @Override
    public void recheck(ReportDashboards.Resolved resolved, long actor, boolean exporting) {
        ReportDashboards.Resolved current = refresh(resolved, actor, exporting);
        if (!current.stamp().equals(resolved.stamp()) || !current.chart().equals(resolved.chart()))
            throw conflict();
    }

    @Override
    public ReportDashboards.Chart previewChart(ReportDashboards.Chart chart, long actor) {
        permission(actor, "query");
        if (chart == null) throw invalid("缺少图表配置");
        // 单图结果不执行看板交互；布局和交互由应用到画布及保存时再次校验。
        ReportDashboards.Chart isolated =
                new ReportDashboards.Chart(
                        chart.id(),
                        chart.title(),
                        chart.display(),
                        chart.dataset(),
                        chart.dimensions(),
                        chart.metricIds(),
                        0,
                        0,
                        12,
                        6,
                        chart.columnDimensions(),
                        chart.pivot());
        return new TransactionTemplate(manager)
                .execute(
                        status ->
                                validate(
                                                new ReportDashboards.Content(
                                                        1,
                                                        "配置预览",
                                                        "",
                                                        List.of(isolated),
                                                        List.of()),
                                                actor,
                                                true)
                                        .charts()
                                        .getFirst());
    }

    private ReportDashboards.Content validate(
            ReportDashboards.Content input, long actor, boolean publishing) {
        if (input == null
                || input.schemaVersion() != 1
                || input.charts() == null
                || input.charts().size() > 30
                || publishing && input.charts().isEmpty()) throw invalid("仪表板需配置1至30个组件后发布");
        String name = validator.text(input.name(), "仪表板名称", 80);
        String description = input.description() == null ? "" : input.description();
        if (description.length() > 1000) throw invalid("仪表板说明过长");
        Set<String> ids = new HashSet<>();
        Map<String, Map<String, ReportDatasets.ResolvedField>> interactionFields =
                new LinkedHashMap<>();
        for (ReportDashboards.Chart chart : input.charts()) {
            if (chart == null
                    || chart.id() == null
                    || !chart.id().matches("[A-Za-z0-9_-]{1,80}")
                    || !ids.add(chart.id())
                    || chart.dataset() == null
                    || chart.dimensions() == null
                    || chart.metricIds() == null
                    || chart.metricIds().isEmpty()
                    || chart.metricIds().size() > 10
                    || new HashSet<>(chart.metricIds()).size() != chart.metricIds().size())
                throw invalid("图表标识或指标配置无效");
            validator.text(chart.title(), "图表名称", 80);
            ReportDisplayEnum display = ReportDisplayEnum.fromCode(chart.display());
            List<ReportDatasetQueries.Dimension> allDimensions =
                    new ArrayList<>(chart.dimensions());
            if (display == ReportDisplayEnum.PIVOT) {
                if (chart.columnDimensions() != null)
                    allDimensions.addAll(chart.columnDimensions());
                ApplicationReports.Pivot pivot =
                        chart.pivot() == null
                                ? ApplicationReports.Pivot.defaults()
                                : chart.pivot().withDefaults();
                ReportPivotPercentEnum.fromCode(pivot.percent());
                if (allDimensions.size() > ApplicationReports.MAX_PIVOT_DIMENSIONS
                        || pivot.maxColumnGroups() < 1
                        || pivot.maxColumnGroups() > 100) throw invalid("透视维度或列组数量超出范围");
            } else if (chart.columnDimensions() != null && !chart.columnDimensions().isEmpty()
                    || chart.pivot() != null) throw invalid("仅透视组件接受列维度及透视设置");
            List<ReportDatasetQueries.Dimension> drill = chart.drillDimensions();
            if (drill != null && !drill.isEmpty()) {
                if (chart.dimensions().size() != 1
                        || drill.size() > 2
                        || !Set.of(
                                        ReportDisplayEnum.BAR,
                                        ReportDisplayEnum.LINE,
                                        ReportDisplayEnum.PIE,
                                        ReportDisplayEnum.TABLE)
                                .contains(display)) throw invalid("钻取仅支持单基础维度图表，最多三层");
                Set<ReportDatasetQueries.Dimension> levels = new HashSet<>(allDimensions);
                for (ReportDatasetQueries.Dimension dimension : drill)
                    if (dimension == null || !levels.add(dimension)) throw invalid("钻取层级为空或重复");
                allDimensions.addAll(drill);
            }
            if (chart.x() < 0
                    || chart.y() < 0
                    || chart.y() > 200
                    || chart.w() < 3
                    || chart.w() > 12
                    || chart.x() + chart.w() > 12
                    || chart.h() < 2
                    || chart.h() > 12) throw invalid("图表布局超出网格范围");
            if (display != ReportDisplayEnum.PIVOT
                            && chart.dimensions().size()
                                    > (display == ReportDisplayEnum.TABLE ? 3 : 2)
                    || display == ReportDisplayEnum.METRIC && !chart.dimensions().isEmpty()
                    || display != ReportDisplayEnum.METRIC && chart.dimensions().isEmpty()
                    || display == ReportDisplayEnum.PIE
                            && (chart.dimensions().size() != 1 || chart.metricIds().size() != 1))
                throw invalid("图表维度或指标数量不匹配");
            ReportDatasetDO dataset =
                    datasets.lock(validator.id(chart.dataset().id(), "数据集"), false);
            access.require(dataset, actor, ReportResourceActionEnum.USE);
            ReportDatasetVersionDO version =
                    datasets.version(dataset.getId(), chart.dataset().versionNo());
            if (version == null || !version.getChecksum().equals(chart.dataset().checksum()))
                throw invalid("图表数据集固定版本失效");
            if (publishing && !ReportResourceStatusEnum.ACTIVE.matches(dataset.getStatus()))
                throw invalid("请先启用图表使用的数据集");
            ReportDatasets.Content configuration =
                    json.read(
                            version.getDefinitionJson(),
                            new TypeReference<ReportDatasets.Content>() {});
            if (configuration.analysis() == null
                    || !configuration.analysis().metrics().stream()
                            .map(ReportDatasetQueries.Metric::id)
                            .toList()
                            .containsAll(chart.metricIds())) throw invalid("指标不属于数据集发布版本");
            ReportDatasets.ResolvedSource resolvedSource = sources.resolve(configuration.source());
            Map<String, ReportDatasets.ResolvedField> fields = new LinkedHashMap<>();
            resolvedSource.fields().forEach(field -> fields.put(field.id(), field));
            interactionFields.put(chart.id(), fields);
            Set<ReportDatasetQueries.Dimension> dimensions = new HashSet<>();
            Set<String> mainFields = new HashSet<>();
            List<ReportDatasetQueries.Dimension> baseDimensions =
                    new ArrayList<>(chart.dimensions());
            if (chart.columnDimensions() != null) baseDimensions.addAll(chart.columnDimensions());
            for (ReportDatasetQueries.Dimension dimension : baseDimensions)
                if (dimension == null || !mainFields.add(dimension.fieldId()))
                    throw invalid("图表维度重复或为空");
            for (ReportDatasetQueries.Dimension dimension : allDimensions) {
                if (dimension == null || !dimensions.add(dimension)) throw invalid("图表维度重复或为空");
                ReportDatasets.Field field =
                        configuration.source().fields().stream()
                                .filter(
                                        f ->
                                                f.id().equals(dimension.fieldId())
                                                        && ReportDatasetFieldRoleEnum.DIMENSION
                                                                .matches(f.role()))
                                .findFirst()
                                .orElseThrow(() -> invalid("图表分组字段不属于数据集维度"));
                ReportBucketEnum bucket = ReportBucketEnum.fromCode(dimension.bucket());
                String type =
                        resolvedSource.fields().stream()
                                .filter(f -> f.id().equals(field.id()))
                                .findFirst()
                                .orElseThrow()
                                .type();
                if (bucket != ReportBucketEnum.VALUE
                        && !Set.of(FieldTypeEnum.DATE.getCode(), FieldTypeEnum.DATETIME.getCode())
                                .contains(type)) throw invalid("日期分组必须选择日期字段");
            }
        }
        navigation.validate(input.navigation());
        validateInteractions(input, interactionFields);
        for (int i = 0; i < input.charts().size(); i++)
            for (int j = i + 1; j < input.charts().size(); j++) {
                ReportDashboards.Chart a = input.charts().get(i), b = input.charts().get(j);
                if (a.x() < b.x() + b.w()
                        && b.x() < a.x() + a.w()
                        && a.y() < b.y() + b.h()
                        && b.y() < a.y() + a.h()) throw invalid("图表网格不能重叠");
            }
        return new ReportDashboards.Content(
                1,
                name,
                description,
                List.copyOf(input.charts()),
                input.filters() == null ? null : List.copyOf(input.filters()),
                input.navigation());
    }

    /** 配置身份、字段类型和所有跨组件映射在保存及发布时统一验证。 */
    private void validateInteractions(
            ReportDashboards.Content content,
            Map<String, Map<String, ReportDatasets.ResolvedField>> fields) {
        Map<String, ReportDashboards.Chart> charts = new LinkedHashMap<>();
        content.charts().forEach(chart -> charts.put(chart.id(), chart));
        List<ReportDashboards.Filter> filters =
                content.filters() == null ? List.of() : content.filters();
        if (filters.size() > 10) throw invalid("公共筛选最多十项");
        Set<String> ids = new HashSet<>();
        for (ReportDashboards.Filter filter : filters) {
            if (filter == null
                    || filter.id() == null
                    || !filter.id().matches("[A-Za-z0-9_-]{1,80}")
                    || !ids.add(filter.id())
                    || filter.mappings() == null
                    || filter.mappings().isEmpty()
                    || filter.mappings().size() > 30) throw invalid("公共筛选标识或映射无效");
            validator.text(filter.name(), "公共筛选名称", 80);
            ReportDashboardFilterKindEnum kind =
                    ReportDashboardFilterKindEnum.fromCode(filter.kind());
            validateDefault(filter.defaultValue(), kind);
            Set<String> mapped = new HashSet<>();
            FieldTypeEnum previous = null;
            for (ReportDashboards.Mapping mapping : filter.mappings()) {
                if (mapping == null || !mapped.add(mapping.chartId()))
                    throw invalid("同一公共筛选对组件只能映射一次");
                FieldTypeEnum type = interactionType(fields, mapping.chartId(), mapping.fieldId());
                boolean allowed =
                        switch (kind) {
                            case TEXT ->
                                    Set.of(
                                                    FieldTypeEnum.TEXT,
                                                    FieldTypeEnum.TEXTAREA,
                                                    FieldTypeEnum.AUTO_NUMBER,
                                                    FieldTypeEnum.UUID)
                                            .contains(type);
                            case SELECT, MULTISELECT -> type.supportsReportGrouping();
                            case NUMBER_RANGE -> type.isNumeric();
                            case DATE_RANGE ->
                                    type == FieldTypeEnum.DATE || type == FieldTypeEnum.DATETIME;
                        };
                if (!allowed) throw invalid("公共筛选类型与映射字段不匹配");
                if (previous != null && !compatible(previous, type)) throw invalid("公共筛选映射字段类型不相容");
                validateDefaultForType(filter.defaultValue(), kind, type);
                previous = type;
            }
        }
        Map<String, Integer> incoming = new HashMap<>();
        for (ReportDashboards.Chart chart : content.charts()) {
            List<ReportDashboards.Link> links = chart.links() == null ? List.of() : chart.links();
            if (links.size() > 30) throw invalid("组件联动配置最多三十项");
            Set<ReportDashboards.Link> unique = new HashSet<>();
            for (ReportDashboards.Link link : links) {
                if (link == null
                        || !unique.add(link)
                        || chart.id().equals(link.targetChartId())
                        || !charts.containsKey(link.targetChartId()))
                    throw invalid("联动目标不存在、自引用或重复");
                boolean source =
                        chart.dimensions().stream()
                                .anyMatch(
                                        d ->
                                                d.fieldId().equals(link.sourceFieldId())
                                                        && ReportBucketEnum.VALUE.matches(
                                                                d.bucket()));
                if (!source || ReportDisplayEnum.PIVOT.matches(chart.display()))
                    throw invalid("联动来源必须是基础原值维度");
                FieldTypeEnum from = interactionType(fields, chart.id(), link.sourceFieldId());
                FieldTypeEnum to =
                        interactionType(fields, link.targetChartId(), link.targetFieldId());
                if (!from.supportsReportGrouping()
                        || !to.supportsReportGrouping()
                        || !compatible(from, to)) throw invalid("联动字段类型不相容");
                if (incoming.merge(link.targetChartId(), 1, Integer::sum) > 30)
                    throw invalid("组件接收的联动条件最多三十项");
            }
        }
    }

    /** 默认条件必须产生真实筛选；取消默认条件使用 null，显式 NULL 选择仍保留原键。 */
    private void validateDefault(
            ReportDashboards.FilterDefault value, ReportDashboardFilterKindEnum kind) {
        if (value == null) return;
        List<String> items = value.values() == null ? List.of() : value.values();
        if (items.size() > (kind == ReportDashboardFilterKindEnum.MULTISELECT ? 100 : 1)
                || new HashSet<>(items).size() != items.size()) throw invalid("公共筛选默认值数量超出范围或重复");
        items.forEach(this::defaultKey);
        boolean range =
                kind == ReportDashboardFilterKindEnum.NUMBER_RANGE
                        || kind == ReportDashboardFilterKindEnum.DATE_RANGE;
        if (range && !items.isEmpty() || !range && (value.from() != null || value.to() != null))
            throw invalid("公共筛选默认值与配置类型不匹配");
        if (!range) {
            if (items.isEmpty()
                    || kind == ReportDashboardFilterKindEnum.TEXT
                            && (items.getFirst() == null || items.getFirst().isBlank()))
                throw invalid("公共筛选默认值不能为空");
            return;
        }
        if (value.from() == null && value.to() == null) throw invalid("公共筛选默认范围至少填写一端");
        defaultKey(value.from());
        defaultKey(value.to());
        try {
            if (kind == ReportDashboardFilterKindEnum.NUMBER_RANGE) {
                BigDecimal from = value.from() == null ? null : new BigDecimal(value.from());
                BigDecimal to = value.to() == null ? null : new BigDecimal(value.to());
                if (from != null && to != null && from.compareTo(to) > 0)
                    throw new IllegalArgumentException();
            } else {
                java.time.Instant from = defaultDate(value.from()), to = defaultDate(value.to());
                if (from != null && to != null && from.compareTo(to) > 0)
                    throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw invalid("公共筛选默认范围格式无效或起点晚于终点");
        }
    }

    /** 仅验证固定字段的原值格式，不以当前业务数据或制作人的取数权限限定默认候选。 */
    private void validateDefaultForType(
            ReportDashboards.FilterDefault value,
            ReportDashboardFilterKindEnum kind,
            FieldTypeEnum type) {
        if (value == null || kind == ReportDashboardFilterKindEnum.TEXT) return;
        List<String> keys =
                kind == ReportDashboardFilterKindEnum.NUMBER_RANGE
                                || kind == ReportDashboardFilterKindEnum.DATE_RANGE
                        ? Arrays.asList(value.from(), value.to())
                        : value.values();
        try {
            for (String key : keys) {
                if (key == null) continue;
                switch (type) {
                    case INTEGER -> new BigInteger(key).longValueExact();
                    case DECIMAL, MONEY, PERCENT -> {
                        BigDecimal number = new BigDecimal(key).stripTrailingZeros();
                        if (number.scale() < -38
                                || number.scale() > 38
                                || (long) number.precision() - number.scale() > 38)
                            throw new IllegalArgumentException();
                    }
                    case BOOLEAN -> {
                        if (!Set.of("true", "false").contains(key))
                            throw new IllegalArgumentException();
                    }
                    case DATE -> java.time.LocalDate.parse(key);
                    case DATETIME -> {
                        if (kind != ReportDashboardFilterKindEnum.DATE_RANGE && key.length() == 10)
                            throw new IllegalArgumentException();
                        defaultDate(key);
                    }
                    case TIME -> java.time.LocalTime.parse(key);
                    case UUID -> {
                        if (!key.matches(
                                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                            throw new IllegalArgumentException();
                        UUID.fromString(key);
                    }
                    default -> {
                        // 文本、选项及关系原键不推测当前候选，也不改写发布配置中的原值。
                    }
                }
            }
        } catch (RuntimeException error) {
            throw invalid("公共筛选默认值与映射字段原值类型不匹配");
        }
    }

    private void defaultKey(String value) {
        if (value != null && (value.length() > 2000 || value.indexOf(0) >= 0))
            throw invalid("公共筛选默认原键格式无效");
    }

    private java.time.Instant defaultDate(String value) {
        if (value == null) return null;
        if (value.length() == 10)
            return java.time.LocalDate.parse(value)
                    .atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC);
        String normalized = value.replace(" ", "T");
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(normalized).toInstant(java.time.ZoneOffset.UTC);
        }
    }

    private FieldTypeEnum interactionType(
            Map<String, Map<String, ReportDatasets.ResolvedField>> fields,
            String chart,
            String field) {
        if (chart == null || field == null) throw invalid("交互映射组件及字段不能为空");
        ReportDatasets.ResolvedField found = fields.getOrDefault(chart, Map.of()).get(field);
        if (found == null) throw invalid("交互映射字段不属于组件数据集固定版本");
        return FieldTypeEnum.fromCode(found.type());
    }

    private boolean compatible(FieldTypeEnum a, FieldTypeEnum b) {
        return a == b
                || a.isNumeric() && b.isNumeric()
                || Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA, FieldTypeEnum.AUTO_NUMBER)
                                .contains(a)
                        && Set.of(
                                        FieldTypeEnum.TEXT,
                                        FieldTypeEnum.TEXTAREA,
                                        FieldTypeEnum.AUTO_NUMBER)
                                .contains(b);
    }

    private void dependencies(
            ReportDashboardDO row, ReportDashboards.Content definition, int number, long actor) {
        if (number == 0) store.clearDraftDependencies(row.getId(), Long.toString(actor));
        Map<ReportDashboards.Dataset, Set<String>> refs = new LinkedHashMap<>();
        for (ReportDashboards.Chart chart : definition.charts()) {
            Set<String> fields = refs.computeIfAbsent(chart.dataset(), key -> new TreeSet<>());
            chart.dimensions().forEach(d -> fields.add(d.fieldId()));
            if (chart.columnDimensions() != null)
                chart.columnDimensions().forEach(d -> fields.add(d.fieldId()));
            if (chart.drillDimensions() != null)
                chart.drillDimensions().forEach(d -> fields.add(d.fieldId()));
            fields.addAll(chart.metricIds());
            if (chart.links() != null)
                chart.links()
                        .forEach(
                                link -> {
                                    fields.add(link.sourceFieldId());
                                    ReportDashboards.Chart target =
                                            definition.charts().stream()
                                                    .filter(
                                                            c ->
                                                                    c.id().equals(
                                                                                    link
                                                                                            .targetChartId()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    refs.computeIfAbsent(target.dataset(), key -> new TreeSet<>())
                                            .add(link.targetFieldId());
                                });
        }
        if (definition.filters() != null)
            definition
                    .filters()
                    .forEach(
                            filter ->
                                    filter.mappings()
                                            .forEach(
                                                    mapping -> {
                                                        ReportDashboards.Chart chart =
                                                                definition.charts().stream()
                                                                        .filter(
                                                                                c ->
                                                                                        c.id().equals(
                                                                                                        mapping
                                                                                                                .chartId()))
                                                                        .findFirst()
                                                                        .orElseThrow();
                                                        refs.computeIfAbsent(
                                                                        chart.dataset(),
                                                                        key -> new TreeSet<>())
                                                                .add(mapping.fieldId());
                                                    }));
        refs.forEach(
                (ref, fields) ->
                        store.dependency(
                                row.getId(),
                                number,
                                Long.parseLong(ref.id()),
                                ref.versionNo(),
                                json.write(fields),
                                Long.toString(actor)));
    }

    private void audit(ReportDashboardDO row, ReportAuditOperationEnum operation, long actor) {
        ReportOperationLogDO log = new ReportOperationLogDO();
        log.setResourceKind(ReportResourceKindEnum.DASHBOARD.getCode());
        log.setResourceId(row.getId());
        log.setAction(operation.getCode());
        log.setRevision(row.getLockVersion() == null ? 1 : row.getLockVersion());
        log.setAfterJson(row.getDraftJson());
        log.setReason("");
        datasets.audit(log, Long.toString(actor));
    }
}
