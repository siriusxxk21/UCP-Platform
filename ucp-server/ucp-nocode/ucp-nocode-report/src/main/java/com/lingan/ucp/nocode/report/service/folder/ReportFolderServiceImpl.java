package com.lingan.ucp.nocode.report.service.folder;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.ReportFolders;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.report.dal.dataobject.*;
import com.lingan.ucp.nocode.report.dal.mapper.*;
import com.lingan.ucp.nocode.report.service.authorization.*;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 管理权限只管理分类树；普通用户根据资源 ACL 发现目录及祖先，不继承目录内资源权限。 */
@Service
public class ReportFolderServiceImpl implements ReportFolderService {
    @Resource private ReportFolderMapper folders;
    @Resource private ReportDatasetMapper datasets;
    @Resource private PermissionCommonApi permissions;
    @Resource private ReportPrincipals principals;
    @Resource private DraftValidator validator;
    @Resource private ReportJson json;
    @Resource private PlatformTransactionManager manager;

    @Override
    public List<ReportFolders.Item> tree(long actor) {
        return tree(null, actor);
    }

    @Override
    public List<ReportFolders.Item> tree(String resourceKind, long actor) {
        ReportResourceKindEnum kind = kind(resourceKind);
        permission(actor, "nocode:report:query");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designReadLock();
                            Map<Long, ReportFolderDO> rows = rows(kind);
                            Set<Long> visible = visible(rows, actor, kind);
                            return rows.values().stream()
                                    .filter(row -> visible.contains(row.getId()))
                                    .map(this::item)
                                    .toList();
                        });
    }

    @Override
    public Long destination(String id, long actor) {
        return destination(id, actor, ReportResourceKindEnum.DATASET);
    }

    @Override
    public Long destination(String id, long actor, ReportResourceKindEnum kind) {
        if (kind != ReportResourceKindEnum.DATASET && kind != ReportResourceKindEnum.DASHBOARD)
            throw invalid("目录只支持数据集或仪表板类别");
        if (id == null) return null;
        permission(actor, "nocode:report:query");
        long value = validator.id(id, "目录");
        Map<Long, ReportFolderDO> rows = rows(kind);
        if (!visible(rows, actor, kind).contains(value))
            throw new AccessDeniedException("目录不存在或无权使用");
        return value;
    }

    @Override
    public ReportFolders.Item save(ReportFolders.Save request, long actor) {
        permission(actor, "nocode:report:manage");
        if (request == null) throw invalid("缺少目录配置");
        ReportResourceKindEnum kind = kind(request.resourceKind());
        String name = validator.text(request.name(), "目录名称", 80);
        String reason = validator.text(request.reason(), "目录变更说明", 1000);
        if (request.sortNo() < 0 || request.sortNo() > 9999) throw invalid("目录排序须为 0–9999");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            Map<Long, ReportFolderDO> rows = rows(kind);
                            boolean creating = request.id() == null;
                            if (creating && rows.size() >= 2000) throw invalid("每类报表目录最多 2000 个");
                            ReportFolderDO row =
                                    creating
                                            ? new ReportFolderDO()
                                            : required(rows, validator.id(request.id(), "目录"));
                            revision(
                                    creating ? 0 : row.getLockVersion(),
                                    request.expectedRevision());
                            ReportFolders.Item before = creating ? null : item(row);
                            Long parent =
                                    request.parentId() == null
                                            ? null
                                            : validator.id(request.parentId(), "父目录");
                            if (parent != null) required(rows, parent);
                            for (ReportFolderDO sibling : rows.values()) {
                                if (!Objects.equals(sibling.getId(), row.getId())
                                        && Objects.equals(sibling.getParentId(), parent)
                                        && sibling.getName().equalsIgnoreCase(name))
                                    throw new ServiceException(DUPLICATE, "同一目录下名称已存在");
                            }
                            row.setParentId(parent);
                            row.setName(name);
                            row.setSortNo(request.sortNo());
                            row.setResourceKind(kind.getCode());
                            // 全树深度校验同时约束移动后的所有后代，不能只验证目标父节点。
                            Map<Long, ReportFolderDO> proposed = new LinkedHashMap<>(rows);
                            proposed.put(creating ? -1L : row.getId(), row);
                            for (Long node : proposed.keySet()) depth(node, proposed);
                            if (creating) folders.create(row, Long.toString(actor));
                            else if (folders.save(
                                            row, request.expectedRevision(), Long.toString(actor))
                                    != 1) throw conflict();
                            row.setLockVersion(request.expectedRevision() + 1);
                            ReportFolders.Item after = item(row);
                            audit(
                                    row.getId(),
                                    creating
                                            ? ReportAuditOperationEnum.CREATE
                                            : ReportAuditOperationEnum.SAVE,
                                    before,
                                    after,
                                    after.revision(),
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportFolders.Deleted delete(ReportFolders.Delete request, long actor) {
        permission(actor, "nocode:report:manage");
        if (request == null) throw invalid("缺少删除目录配置");
        ReportResourceKindEnum kind = kind(request.resourceKind());
        String reason = validator.text(request.reason(), "目录删除说明", 1000);
        long id = validator.id(request.id(), "目录");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designLock();
                            ReportFolderDO row = required(rows(kind), id);
                            revision(row.getLockVersion(), request.expectedRevision());
                            // 只返回非空结论，不泄漏目录内无权资源的名称或数量。
                            if (folders.occupied(id, kind.getCode()))
                                throw invalid("目录非空，请先移出资源并删除子目录");
                            if (folders.remove(
                                            id,
                                            request.expectedRevision(),
                                            Long.toString(actor),
                                            kind.getCode())
                                    != 1) throw conflict();
                            ReportFolders.Deleted result =
                                    new ReportFolders.Deleted(
                                            request.id(), request.expectedRevision() + 1);
                            audit(
                                    id,
                                    ReportAuditOperationEnum.DELETE,
                                    item(row),
                                    result,
                                    result.revision(),
                                    reason,
                                    actor);
                            return result;
                        });
    }

    private ReportResourceKindEnum kind(String value) {
        if (value == null || ReportResourceKindEnum.DATASET.getCode().equals(value))
            return ReportResourceKindEnum.DATASET;
        if (ReportResourceKindEnum.DASHBOARD.getCode().equals(value))
            return ReportResourceKindEnum.DASHBOARD;
        throw invalid("目录只支持数据集或仪表板类别");
    }

    private Map<Long, ReportFolderDO> rows(ReportResourceKindEnum kind) {
        Map<Long, ReportFolderDO> result = new LinkedHashMap<>();
        folders.all(kind.getCode()).forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private Set<Long> visible(
            Map<Long, ReportFolderDO> rows, long actor, ReportResourceKindEnum kind) {
        Set<String> identities = principals.current(actor).principals();
        if (permissions.hasAnyPermissions(actor, "nocode:report:manage")) return rows.keySet();
        Set<Long> result = new HashSet<>();
        for (Long seed : folders.visibleSeeds(actor, identities, kind.getCode())) {
            Long id = seed;
            while (id != null && result.add(id)) {
                ReportFolderDO row = rows.get(id);
                id = row == null ? null : row.getParentId();
            }
        }
        result.retainAll(rows.keySet());
        return result;
    }

    private int depth(Long id, Map<Long, ReportFolderDO> rows) {
        Set<Long> visited = new HashSet<>();
        while (id != null) {
            if (!visited.add(id)) throw invalid("目录不能移动到自身或后代目录");
            if (visited.size() > 8) throw invalid("目录层级最多 8 层");
            id = required(rows, id).getParentId();
        }
        return visited.size();
    }

    private ReportFolderDO required(Map<Long, ReportFolderDO> rows, Long id) {
        ReportFolderDO row = rows.get(id);
        if (row == null) throw new AccessDeniedException("目录不存在或无权使用");
        return row;
    }

    private ReportFolders.Item item(ReportFolderDO row) {
        return new ReportFolders.Item(
                row.getId().toString(),
                row.getParentId() == null ? null : row.getParentId().toString(),
                row.getName(),
                row.getSortNo(),
                row.getLockVersion());
    }

    private void permission(long actor, String code) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, code))
            throw new AccessDeniedException("没有报表目录操作权限");
    }

    private void revision(int actual, int expected) {
        if (expected < 0 || actual != expected) throw conflict();
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "目录已被修改，请刷新后重试");
    }

    private void audit(
            long id,
            ReportAuditOperationEnum action,
            Object before,
            Object after,
            int revision,
            String reason,
            long actor) {
        ReportOperationLogDO row = new ReportOperationLogDO();
        row.setResourceKind(ReportResourceKindEnum.FOLDER.getCode());
        row.setResourceId(id);
        row.setAction(action.getCode());
        row.setRevision(revision);
        row.setBeforeJson(json.write(before));
        row.setAfterJson(json.write(after));
        row.setReason(reason);
        datasets.audit(row, Long.toString(actor));
    }
}
