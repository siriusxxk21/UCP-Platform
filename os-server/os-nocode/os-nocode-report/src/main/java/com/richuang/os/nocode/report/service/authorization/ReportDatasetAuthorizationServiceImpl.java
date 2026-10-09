package com.richuang.os.nocode.report.service.authorization;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.*;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceService;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;

/** 资源协作权限始终校验；来源权限默认只读开放，显式兼容模式仍逐条保留行／字段绑定求交。 */
@Service
public class ReportDatasetAuthorizationServiceImpl implements ReportDatasetAuthorizationService {
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportSourcePermissions sourcePermissions;
    @Resource private ReportDashboardMapper dashboardStore;
    @Resource private ReportAuthorizationMapper store;
    @Resource private ReportResourceAccess resources;

    @Resource
    private com.richuang.os.nocode.report.service.dashboard.ReportDashboardService dashboards;

    @Resource private ReportAuthorizationDependencies dependencies;
    @Resource private ReportPrincipals principals;
    @Resource private ReportJson json;
    @Resource private DataObjectApi objects;
    @Resource private ReportDatasetSourceService sources;
    @Resource private com.richuang.os.nocode.report.service.dataset.ReportDatasetUsage usage;
    @Resource private DraftValidator validator;
    @Resource private PermissionCommonApi permissions;
    @Resource private PlatformTransactionManager manager;
    private final ObjectGrantRules rules = new ObjectGrantRules();

    @Override
    public ReportAuthorization.ResourcePolicy resourcePolicy(String id, long actor) {
        permission(actor, "nocode:report:manage");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDatasetDO dataset = head(id, false);
                            resources.require(dataset, actor, ReportResourceActionEnum.GRANT);
                            return resources.policy(
                                    ReportResourceKindEnum.DATASET.getCode(), dataset.getId());
                        });
    }

    @Override
    public ReportAuthorization.ResourcePolicy saveResource(
            ReportAuthorization.SaveResource request, long actor) {
        permission(actor, "nocode:report:manage");
        if (request == null) throw invalid("缺少资源授权配置");
        String reason = validator.text(request.reason(), "授权变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDatasetDO dataset = head(request.datasetId(), true);
                            resources.require(dataset, actor, ReportResourceActionEnum.GRANT);
                            ReportAuthorization.ResourcePolicy before =
                                    resources.policy(
                                            ReportResourceKindEnum.DATASET.getCode(),
                                            dataset.getId());
                            revision(before.revision(), request.expectedRevision());
                            if (request.members() == null || request.members().size() > 200)
                                throw invalid("资源成员最多 200 项");
                            Set<String> unique = new HashSet<>();
                            Set<ReportResourceActionEnum> allowed =
                                    EnumSet.of(
                                            ReportResourceActionEnum.VIEW_META,
                                            ReportResourceActionEnum.EDIT,
                                            ReportResourceActionEnum.PUBLISH,
                                            ReportResourceActionEnum.USE,
                                            ReportResourceActionEnum.AUTHORIZE_DATA,
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
                                        throw invalid("此操作不属于数据集资源权限");
                            }
                            store.saveAcl(
                                    ReportResourceKindEnum.DATASET.getCode(),
                                    dataset.getId(),
                                    json.write(request.members()),
                                    Long.toString(actor));
                            ReportAuthorization.ResourcePolicy after =
                                    resources.policy(
                                            ReportResourceKindEnum.DATASET.getCode(),
                                            dataset.getId());
                            audit(dataset.getId(), before, after, after.revision(), reason, actor);
                            return after;
                        });
    }

    @Override
    public List<ReportAuthorization.ObjectCeiling> ceilings(String id, long actor) {
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDatasetDO dataset = head(id, false);
                            if (!has(actor, "nocode:object:share")) {
                                permission(actor, "nocode:report:query");
                                resources.require(
                                        dataset, actor, ReportResourceActionEnum.VIEW_META);
                            }
                            return store.ceilings(dataset.getId()).stream()
                                    .map(this::ceiling)
                                    .toList();
                        });
    }

    @Override
    public ReportAuthorization.ObjectCeiling saveCeiling(
            ReportAuthorization.SaveCeiling request, long actor) {
        permission(actor, "nocode:object:share");
        if (request == null) throw invalid("缺少对象共享上限");
        String reason = validator.text(request.reason(), "共享变更说明", 1000);
        long object = validator.id(request.objectId(), "对象");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDatasetDO dataset = head(request.datasetId(), true);
                            ReportObjectGrantDO row = store.ceiling(dataset.getId(), object);
                            revision(
                                    row == null ? 0 : row.getLockVersion(),
                                    request.expectedRevision());
                            if (request.permission() == null && row == null)
                                throw invalid("尚无可撤销的对象授权");
                            if (request.permission() != null) {
                                requireReference(dataset.getId(), request.objectId());
                                readOnlyGrant(
                                        request.permission(),
                                        objects.getVersion(request.objectId(), null).definition());
                                if (!request.objectId().equals(request.permission().objectId()))
                                    throw invalid("上限授权对象不匹配");
                            }
                            // 撤权不能因对象停用、来源移除或旧字段失效而被阻止。
                            store.saveCeiling(
                                    dataset.getId(),
                                    object,
                                    json.write(request.permission()),
                                    reason,
                                    Long.toString(actor));
                            dependencies.ceiling(
                                    dataset, request.objectId(), request.permission(), actor);
                            ReportAuthorization.ObjectCeiling after =
                                    ceiling(store.ceiling(dataset.getId(), object));
                            audit(
                                    dataset.getId(),
                                    row == null ? null : ceiling(row),
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportAuthorization.DataPolicy dataPolicy(String id, long actor) {
        permission(actor, "nocode:report:authorize");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDatasetDO dataset = head(id, false);
                            resources.require(
                                    dataset, actor, ReportResourceActionEnum.AUTHORIZE_DATA);
                            return policy(dataset.getId());
                        });
    }

    @Override
    public ReportAuthorization.DataPolicy saveDataPolicy(
            ReportAuthorization.SaveDataPolicy request, long actor) {
        permission(actor, "nocode:report:authorize");
        if (request == null) throw invalid("缺少成员数据策略");
        String reason = validator.text(request.reason(), "策略变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportDatasetDO dataset = head(request.datasetId(), true);
                            resources.require(
                                    dataset, actor, ReportResourceActionEnum.AUTHORIZE_DATA);
                            ReportAuthorization.DataPolicy before = policy(dataset.getId());
                            revision(before.revision(), request.expectedRevision());
                            if (request.members() == null || request.members().size() > 200)
                                throw invalid("数据成员最多 200 项");
                            Set<String> unique = new HashSet<>();
                            for (Member member : request.members()) {
                                if (member == null
                                        || !unique.add(
                                                principals.validate(
                                                        member.principalKind(),
                                                        member.principalId())))
                                    throw invalid("数据成员为空或重复");
                                if (member.objects() == null || member.objects().size() > 100)
                                    throw invalid("成员对象授权最多 100 项");
                                Set<String> objectIds = new HashSet<>();
                                for (ObjectGrant grant : member.objects()) {
                                    if (grant == null || !objectIds.add(grant.objectId()))
                                        throw invalid("成员对象授权为空或重复");
                                    long objectId = validator.id(grant.objectId(), "授权对象");
                                    requireReference(dataset.getId(), grant.objectId());
                                    readOnlyGrant(
                                            grant,
                                            objects.getVersion(grant.objectId(), null)
                                                    .definition());
                                    rules.within(
                                            grant,
                                            decode(store.ceiling(dataset.getId(), objectId)),
                                            "数据集");
                                }
                            }
                            store.savePolicy(
                                    dataset.getId(),
                                    json.write(request.members()),
                                    Long.toString(actor));
                            dependencies.policy(dataset, request.members(), actor);
                            ReportAuthorization.DataPolicy after = policy(dataset.getId());
                            audit(dataset.getId(), before, after, after.revision(), reason, actor);
                            return after;
                        });
    }

    @Override
    public void requireCeilings(long datasetId, ReportDatasets.Source source) {
        ReportDatasets.ResolvedSource resolved = sources.resolve(source);
        if (!sourcePermissions.enabled()) return;
        Map<String, Set<String>> required = usage.fields(resolved);
        for (ReportDatasets.ObjectReference ref : resolved.objects()) {
            ObjectGrant ceiling =
                    decode(store.ceiling(datasetId, validator.id(ref.objectId(), "对象")));
            if (ceiling == null
                    || !ceiling.actions().contains(ApplicationActionEnum.READ.getCode()))
                throw invalid("来源对象尚未授权给数据集：" + ref.objectId());
            if (!ceiling.readFields().containsAll(required.get(ref.objectId())))
                throw invalid("对象共享上限未包含数据集字段或关联连接键：" + ref.objectId());
        }
    }

    @Override
    public <T> T withDataAccess(
            String id,
            Integer versionNo,
            String checksum,
            boolean preview,
            long actor,
            Function<Context, T> action) {
        return withAccess(id, versionNo, checksum, preview, actor, action, false, null, null);
    }

    @Override
    public <T> T withExportAccess(
            String id,
            Integer versionNo,
            String checksum,
            boolean preview,
            long actor,
            Function<Context, T> action) {
        return withAccess(id, versionNo, checksum, preview, actor, action, true, null, null);
    }

    @Override
    public <T> T withDashboardDataAccess(
            ReportDashboards.Resolved board, long actor, Function<Context, T> action) {
        return withDashboardAccess(board, actor, action, false);
    }

    @Override
    public <T> T withDashboardExportAccess(
            ReportDashboards.Resolved board, long actor, Function<Context, T> action) {
        return withDashboardAccess(board, actor, action, true);
    }

    private <T> T withDashboardAccess(
            ReportDashboards.Resolved board,
            long actor,
            Function<Context, T> action,
            boolean exporting) {
        if (board == null || board.request() == null || board.chart() == null)
            throw invalid("缺少仪表板受控查询上下文");
        if (board.entry() == ReportDashboardEntryEnum.APPLICATION_FIXED)
            throw invalid("应用固定入口必须携带可信应用授权门卫");
        ReportDashboards.Dataset ref = board.chart().dataset();
        return withAccess(
                ref.id(),
                ref.versionNo(),
                ref.checksum(),
                false,
                actor,
                action,
                exporting,
                board,
                null);
    }

    @Override
    public <T> T withApplicationDashboardAccess(
            ReportDashboards.Resolved board,
            ApplicationGate gate,
            long actor,
            Function<Context, T> action,
            boolean exporting) {
        if (board == null
                || board.request() == null
                || board.chart() == null
                || gate == null
                || board.entry() != ReportDashboardEntryEnum.APPLICATION_FIXED)
            throw invalid("缺少可信应用固定入口");
        ReportDashboards.Dataset ref = board.chart().dataset();
        return withAccess(
                ref.id(),
                ref.versionNo(),
                ref.checksum(),
                false,
                actor,
                action,
                exporting,
                board,
                gate);
    }

    private <T> T withAccess(
            String id,
            Integer versionNo,
            String checksum,
            boolean preview,
            long actor,
            Function<Context, T> action,
            boolean exporting,
            ReportDashboards.Resolved board,
            ApplicationGate gate) {
        // 身份快照在取数事务前后读取；不持查询连接再申请第二个连接，避免并发耗尽连接池。
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        TransactionTemplate queryTransaction = new TransactionTemplate(manager);
        queryTransaction.setTimeout(25);
        T result =
                queryTransaction.execute(
                        tx -> {
                            // 应用固定入口先目录，再设计、应用、看板、数据集及来源对象；不从回调反序取锁。
                            if (gate != null) gate.lockCatalog();
                            datasets.designReadLock();
                            if (gate != null) gate.lockApplication(board, actor);
                            if (board != null) {
                                // 与授权写入同序：设计共享锁→仪表板共享锁→数据集共享锁→来源对象。
                                // resolve 加入当前事务；仪表板服务不能在取数外层另包事务。
                                ReportDashboards.Resolved current =
                                        dashboards.refresh(board, actor, exporting);
                                if (!current.stamp().equals(board.stamp())
                                        || !current.chart().equals(board.chart()))
                                    throw invalid("仪表板配置已变化，请重新查询");
                            }
                            ReportDatasetDO dataset = head(id, false);
                            if (board == null) {
                                resources.require(
                                        dataset,
                                        identity.principals(),
                                        ReportResourceActionEnum.USE);
                            }
                            ReportDatasets.Content content;
                            if (preview) {
                                permission(actor, "nocode:report:query");
                                resources.require(
                                        dataset,
                                        identity.principals(),
                                        ReportResourceActionEnum.EDIT);
                                if (versionNo != null || checksum != null)
                                    throw invalid("草稿预览不接收发布版本");
                                content =
                                        json.read(
                                                dataset.getDraftJson(),
                                                new TypeReference<ReportDatasets.Content>() {});
                            } else {
                                if (!ReportResourceStatusEnum.ACTIVE.matches(dataset.getStatus()))
                                    throw invalid("数据集已停用");
                                if (versionNo == null
                                        || versionNo < 1
                                        || checksum == null
                                        || checksum.isBlank()) throw invalid("必须指定数据集版本及校验和");
                                ReportDatasetVersionDO version =
                                        datasets.version(dataset.getId(), versionNo);
                                if (version == null || !checksum.equals(version.getChecksum()))
                                    throw invalid("数据集版本或校验和失效");
                                content =
                                        json.read(
                                                version.getDefinitionJson(),
                                                new TypeReference<ReportDatasets.Content>() {});
                            }
                            ReportDatasets.ResolvedSource source =
                                    sources.resolve(content.source());
                            Map<String, List<ObjectGrant>> effective = new LinkedHashMap<>();
                            if (!sourcePermissions.enabled()) {
                                effective.putAll(sourcePermissions.defaults(source));
                            } else {
                                ReportAuthorization.DataPolicy policy = policy(dataset.getId());
                                for (ReportDatasets.ObjectReference ref : source.objects()) {
                                    ObjectGrant ceiling =
                                            decode(
                                                    store.ceiling(
                                                            dataset.getId(),
                                                            validator.id(ref.objectId(), "对象")));
                                    List<ObjectGrant> grants = new ArrayList<>();
                                    if (ceiling != null)
                                        for (Member member : policy.members()) {
                                            if (!identity.principals()
                                                    .contains(
                                                            member.principalKind()
                                                                    + ":"
                                                                    + member.principalId()))
                                                continue;
                                            for (ObjectGrant grant : member.objects())
                                                if (ref.objectId().equals(grant.objectId())) {
                                                    ObjectGrant narrowed =
                                                            rules.intersect(grant, ceiling);
                                                    if (narrowed.actions()
                                                            .contains(
                                                                    ApplicationActionEnum.READ
                                                                            .getCode()))
                                                        grants.add(narrowed);
                                                }
                                        }
                                    if (grants.isEmpty())
                                        throw new AccessDeniedException(
                                                "没有数据集来源对象的数据查看权限：" + ref.objectId());
                                    effective.put(ref.objectId(), List.copyOf(grants));
                                }
                            }
                            Map<String, List<ObjectGrant>> applicationGrants = null;
                            if (gate != null) {
                                applicationGrants = gate.grants(source, actor);
                                if (applicationGrants == null) throw invalid("应用固定入口未返回有效授权集合");
                                applicationGrants = Map.copyOf(applicationGrants);
                            }
                            // 能力探测不能在 REQUIRED 子事务中捕获拒绝，否则外层被标记为 rollback-only。
                            // 当前仪表板头已被共享锁保护，直接按同一身份快照检查 ACL，实际导出仍重新授权。
                            boolean canExport =
                                    board == null
                                            || dashboardCanExport(board, identity.principals());
                            return action.apply(
                                    new Context(
                                            id,
                                            versionNo,
                                            content,
                                            source,
                                            Map.copyOf(effective),
                                            identity.scopeContext(),
                                            canExport,
                                            applicationGrants));
                        });
        principals.unchanged(identity);
        if (gate != null) gate.recheck(actor);
        if (board != null) dashboards.recheck(board, actor, exporting);
        return result;
    }

    private boolean dashboardCanExport(ReportDashboards.Resolved board, Set<String> identities) {
        ReportDashboardDO row =
                dashboardStore.lock(validator.id(board.request().id(), "仪表板"), false);
        if (row == null) return false;
        Set<ReportResourceActionEnum> actions =
                resources.actions(
                        ReportResourceKindEnum.DASHBOARD,
                        row.getId(),
                        row.getOwnerId(),
                        identities);
        return actions.contains(ReportResourceActionEnum.VIEW)
                && actions.contains(ReportResourceActionEnum.EXPORT);
    }

    private void readOnlyGrant(ObjectGrant grant, DataCenter.Definition definition) {
        rules.validate(grant, definition);
        if (!Set.of(ApplicationActionEnum.READ.getCode(), ApplicationActionEnum.EXPORT.getCode())
                        .containsAll(grant.actions())
                || !grant.writeFields().isEmpty()
                || !grant.writeDetails().isEmpty()
                || !grant.writeRelations().isEmpty()
                || !grant.computeFields().isEmpty()) throw invalid("数据集仅支持读和导出授权，不允许写入或额外计算取数");
    }

    private ReportAuthorization.DataPolicy policy(long id) {
        ReportDatasetPolicyDO row = store.policy(id);
        return row == null
                ? new ReportAuthorization.DataPolicy(0, List.of())
                : new ReportAuthorization.DataPolicy(
                        row.getLockVersion(),
                        json.read(row.getMembersJson(), new TypeReference<List<Member>>() {}));
    }

    private ObjectGrant decode(ReportObjectGrantDO row) {
        return row == null || row.getGrantJson() == null
                ? null
                : json.read(row.getGrantJson(), new TypeReference<ObjectGrant>() {});
    }

    private ReportAuthorization.ObjectCeiling ceiling(ReportObjectGrantDO row) {
        return new ReportAuthorization.ObjectCeiling(
                row.getObjectId().toString(), row.getLockVersion(), decode(row), row.getReason());
    }

    private ReportDatasetDO head(String id, boolean write) {
        ReportDatasetDO dataset = datasets.lock(validator.id(id, "数据集"), write);
        if (dataset == null) throw new AccessDeniedException("数据集不存在或无权访问");
        return dataset;
    }

    private void requireReference(long id, String object) {
        if (!store.referencesObject(id, object)) throw invalid("对象尚未引用到数据集草稿或版本");
    }

    private boolean has(long actor, String code) {
        return actor > 0 && permissions.hasAnyPermissions(actor, code);
    }

    private void permission(long actor, String code) {
        if (!has(actor, code)) throw new AccessDeniedException("没有报表授权管理权限");
    }

    private void revision(int actual, int expected) {
        if (expected < 0 || actual != expected)
            throw new ServiceException(CONFLICT, "授权已被修改，请刷新后重试");
    }

    private void audit(
            long id, Object before, Object after, int revision, String reason, long actor) {
        ReportOperationLogDO row = new ReportOperationLogDO();
        row.setResourceKind(ReportResourceKindEnum.DATASET.getCode());
        row.setResourceId(id);
        row.setAction(ReportAuditOperationEnum.AUTHORIZE.getCode());
        row.setRevision(revision);
        row.setBeforeJson(json.write(before));
        row.setAfterJson(json.write(after));
        row.setReason(reason);
        datasets.audit(row, Long.toString(actor));
    }
}
