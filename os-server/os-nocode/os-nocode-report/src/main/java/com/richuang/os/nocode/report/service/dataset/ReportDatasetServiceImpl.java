package com.richuang.os.nocode.report.service.dataset;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.ReportDatasetMapper;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 草稿、快照、依赖和审计同事务写入；先全局设计锁，再资源头锁，兼容对象发布锁序。 */
@Service
public class ReportDatasetServiceImpl implements ReportDatasetService {
    @Resource private ReportDatasetMapper store;
    @Resource private com.richuang.os.nocode.report.dal.mapper.ReportAuthorizationMapper grants;
    @Resource private ReportDatasetUsage usage;
    @Resource private com.richuang.os.nocode.report.service.folder.ReportFolderService folders;

    @Resource
    private com.richuang.os.nocode.report.service.authorization.ReportResourceAccess resourceAccess;

    @Resource
    private com.richuang.os.nocode.report.service.authorization.ReportPrincipals principals;

    @Resource
    private com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService
            authorization;

    @Resource private ReportDatasetSourceService sources;
    @Resource private ReportDatasetAnalysis analysis;
    @Resource private DataObjectApi objects;
    @Resource private DraftValidator validator;
    @Resource private PermissionCommonApi permissions;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    @Override
    public ReportDatasets.Detail save(ReportDatasets.Save command, long actor) {
        if (command == null) throw invalid("缺少数据集配置");
        boolean creating = command.id() == null;
        permission(actor, creating ? "nocode:report:create" : "nocode:report:update");
        if (command.expectedRevision() < 0 || creating && command.expectedRevision() != 0)
            throw invalid("数据集修订号无效");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row;
                            ReportDatasets.Detail before = null;
                            if (creating) {
                                row = new ReportDatasetDO();
                                row.setOwnerId(actor);
                                row.setFolderId(folders.destination(command.folderId(), actor));
                            } else {
                                row =
                                        accessible(
                                                command.id(),
                                                actor,
                                                true,
                                                ReportResourceActionEnum.EDIT);
                                revision(row, command.expectedRevision());
                                before = detail(row);
                                if (command.folderId() != null
                                        && !Objects.equals(command.folderId(), before.folderId()))
                                    throw invalid("请通过移动操作修改目录");
                            }
                            ReportDatasets.Content content =
                                    content(
                                            command.name(),
                                            command.description(),
                                            command.source(),
                                            command.analysis() == null && before != null
                                                    ? before.draft().analysis()
                                                    : command.analysis(),
                                            actor);
                            draft(row, content);
                            if (creating) store.create(row, Long.toString(actor));
                            else if (store.save(
                                            row, command.expectedRevision(), Long.toString(actor))
                                    != 1) throw conflict();
                            dependencies(row.getId(), "draft", content, actor);
                            ReportDatasets.Detail after = detail(store.lock(row.getId(), true));
                            audit(
                                    row.getId(),
                                    creating
                                            ? ReportAuditOperationEnum.CREATE
                                            : ReportAuditOperationEnum.SAVE,
                                    before,
                                    after,
                                    "",
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportDatasets.Detail copy(ReportDatasets.Copy command, long actor) {
        permission(actor, "nocode:report:create");
        permission(actor, "nocode:report:query");
        if (command == null) throw invalid("缺少复制配置");
        String reason = validator.text(command.reason(), "复制说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO original =
                                    accessible(
                                            command.id(),
                                            actor,
                                            false,
                                            ReportResourceActionEnum.VIEW_META);
                            resourceAccess.require(original, actor, ReportResourceActionEnum.USE);
                            revision(original, command.expectedRevision());
                            ReportDatasets.Detail before = detail(original);
                            ReportDatasets.Content copied =
                                    content(
                                            command.name(),
                                            before.draft().description(),
                                            before.draft().source(),
                                            before.draft().analysis(),
                                            actor);
                            ReportDatasetDO row = new ReportDatasetDO();
                            row.setOwnerId(actor);
                            row.setFolderId(original.getFolderId());
                            draft(row, copied);
                            store.create(row, Long.toString(actor));
                            dependencies(row.getId(), "draft", copied, actor);
                            ReportDatasets.Detail after = detail(store.lock(row.getId(), true));
                            audit(
                                    row.getId(),
                                    ReportAuditOperationEnum.COPY,
                                    before,
                                    after,
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportDatasets.DeletePreview deletePreview(String id, long actor) {
        permission(actor, "nocode:report:manage");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designReadLock();
                            ReportDatasetDO row =
                                    accessible(id, actor, false, ReportResourceActionEnum.DELETE);
                            long count = store.incomingReferences(row.getId());
                            return new ReportDatasets.DeletePreview(
                                    id, row.getLockVersion(), count, count == 0);
                        });
    }

    /** 删除只释放本资源发出的对象依赖，保留历史快照和审计；外部引用包含草稿及历史版本。 */
    @Override
    public ReportDatasets.Deleted delete(ReportDatasets.Delete command, long actor) {
        permission(actor, "nocode:report:manage");
        if (command == null) throw invalid("缺少删除配置");
        String reason = validator.text(command.reason(), "删除说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row =
                                    accessible(
                                            command.id(),
                                            actor,
                                            true,
                                            ReportResourceActionEnum.DELETE);
                            revision(row, command.expectedRevision());
                            if (store.incomingReferences(row.getId()) > 0)
                                throw new ServiceException(CONFLICT, "数据集仍被其他资源引用，请解除全部引用后重试");
                            ReportDatasets.Detail before = detail(row);
                            if (store.remove(
                                            row.getId(),
                                            command.expectedRevision(),
                                            Long.toString(actor))
                                    != 1) throw conflict();
                            Set<String> stages = new LinkedHashSet<>(List.of("draft", "policy"));
                            store.versionNumbers(row.getId())
                                    .forEach(number -> stages.add("v" + number));
                            grants.ceilings(row.getId())
                                    .forEach(grant -> stages.add("ceiling:" + grant.getObjectId()));
                            for (String stage : stages)
                                objects.removeDependencies(
                                        DependencyKindEnum.DATASET.getCode(),
                                        row.getId() + ":" + stage,
                                        actor);
                            ReportDatasets.Deleted after =
                                    new ReportDatasets.Deleted(
                                            command.id(), command.expectedRevision() + 1, true);
                            ReportOperationLogDO log = new ReportOperationLogDO();
                            log.setResourceKind(ReportResourceKindEnum.DATASET.getCode());
                            log.setResourceId(row.getId());
                            log.setAction(ReportAuditOperationEnum.DELETE.getCode());
                            log.setRevision(after.revision());
                            log.setBeforeJson(encode(before));
                            log.setAfterJson(encode(after));
                            log.setReason(reason);
                            store.audit(log, Long.toString(actor));
                            return after;
                        });
    }

    @Override
    public ReportDatasets.Detail get(String id, long actor) {
        permission(actor, "nocode:report:query");
        return new TransactionTemplate(manager)
                .execute(
                        tx ->
                                detail(
                                        accessible(
                                                id,
                                                actor,
                                                false,
                                                ReportResourceActionEnum.VIEW_META)));
    }

    @Override
    public PageResult<ReportDatasets.Detail> page(
            int pageNo, int pageSize, String search, long actor) {
        return page(pageNo, pageSize, search, null, actor);
    }

    @Override
    public PageResult<ReportDatasets.Detail> page(
            int pageNo, int pageSize, String search, String folderId, long actor) {
        permission(actor, "nocode:report:query");
        Long folder =
                folderId == null ? null : "0".equals(folderId) ? 0L : validator.id(folderId, "目录");
        pageBounds(pageNo, pageSize);
        String text = search == null ? "" : search.trim();
        if (text.length() > 80) throw invalid("数据集搜索词过长");
        String pattern = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        IPage<ReportDatasetDO> rows =
                store.page(
                        new Page<>(pageNo, pageSize),
                        actor,
                        pattern,
                        principals.snapshot(actor).principals(),
                        folder);
        return new PageResult<>(
                rows.getRecords().stream().map(this::detail).toList(), rows.getTotal());
    }

    @Override
    public ReportDatasets.Release publish(ReportDatasets.Publish command, long actor) {
        permission(actor, "nocode:report:publish");
        if (command == null
                || command.expectedRevision() < 1
                || command.requestId() == null
                || !command.requestId().matches("[A-Za-z0-9_-]{1,80}"))
            throw invalid("发布请求标识或修订号无效");
        String reason = validator.text(command.reason(), "发布说明", 1000);
        String requestHash = checksum(encode(command));
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row =
                                    accessible(
                                            command.id(),
                                            actor,
                                            true,
                                            ReportResourceActionEnum.PUBLISH);
                            ReportDatasetVersionDO previous =
                                    store.request(
                                            row.getId(), Long.toString(actor), command.requestId());
                            if (previous != null) {
                                if (!previous.getRequestHash().equals(requestHash))
                                    throw new ServiceException(CONFLICT, "发布请求标识已用于不同请求");
                                return release(previous);
                            }
                            revision(row, command.expectedRevision());
                            ReportDatasets.Detail before = detail(row);
                            ReportDatasets.Content content = before.draft();
                            if (content.source() == null) throw invalid("请先配置数据集来源");
                            analysis.validate(
                                    content.analysis(), sources.resolve(content.source()));
                            authorization.requireCeilings(row.getId(), content.source());
                            int number =
                                    row.getPublishedVersion() == null
                                            ? 1
                                            : row.getPublishedVersion() + 1;
                            ReportDatasetVersionDO version = new ReportDatasetVersionDO();
                            version.setDatasetId(row.getId());
                            version.setVersionNo(number);
                            version.setDefinitionJson(encode(content));
                            version.setChecksum(row.getDraftChecksum());
                            version.setReason(reason);
                            version.setRequestId(command.requestId());
                            version.setRequestHash(requestHash);
                            store.createVersion(version, Long.toString(actor));
                            dependencies(row.getId(), "v" + number, content, actor);
                            store.publish(row.getId(), number, Long.toString(actor));
                            audit(
                                    row.getId(),
                                    ReportAuditOperationEnum.PUBLISH,
                                    before,
                                    detail(store.lock(row.getId(), true)),
                                    reason,
                                    actor);
                            return release(store.version(row.getId(), number));
                        });
    }

    @Override
    public PageResult<ReportDatasets.Release> releases(
            String id, int pageNo, int pageSize, long actor) {
        permission(actor, "nocode:report:query");
        pageBounds(pageNo, pageSize);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            ReportDatasetDO row =
                                    accessible(
                                            id, actor, false, ReportResourceActionEnum.VIEW_META);
                            IPage<ReportDatasetVersionDO> rows =
                                    store.versions(new Page<>(pageNo, pageSize), row.getId());
                            return new PageResult<>(
                                    rows.getRecords().stream().map(this::release).toList(),
                                    rows.getTotal());
                        });
    }

    @Override
    public ReportDatasets.Detail restore(ReportDatasets.Restore command, long actor) {
        permission(actor, "nocode:report:update");
        if (command == null || command.versionNo() < 1) throw invalid("恢复版本无效");
        String reason = validator.text(command.reason(), "恢复说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row =
                                    accessible(
                                            command.id(),
                                            actor,
                                            true,
                                            ReportResourceActionEnum.EDIT);
                            revision(row, command.expectedRevision());
                            ReportDatasets.Detail before = detail(row);
                            ReportDatasets.Content old =
                                    release(requiredVersion(row.getId(), command.versionNo()))
                                            .definition();
                            ReportDatasets.Content restored =
                                    content(
                                            old.name(),
                                            old.description(),
                                            old.source(),
                                            old.analysis(),
                                            actor);
                            draft(row, restored);
                            if (store.save(row, command.expectedRevision(), Long.toString(actor))
                                    != 1) throw conflict();
                            dependencies(row.getId(), "draft", restored, actor);
                            ReportDatasets.Detail after = detail(store.lock(row.getId(), true));
                            audit(
                                    row.getId(),
                                    ReportAuditOperationEnum.RESTORE,
                                    before,
                                    after,
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportDatasets.Detail move(ReportDatasets.Move command, long actor) {
        permission(actor, "nocode:report:update");
        if (command == null) throw invalid("缺少移动配置");
        String reason = validator.text(command.reason(), "移动说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row =
                                    accessible(
                                            command.id(),
                                            actor,
                                            true,
                                            ReportResourceActionEnum.EDIT);
                            revision(row, command.expectedRevision());
                            Long folder = folders.destination(command.folderId(), actor);
                            ReportDatasets.Detail before = detail(row);
                            if (store.move(
                                            row.getId(),
                                            command.expectedRevision(),
                                            folder,
                                            Long.toString(actor))
                                    != 1) throw conflict();
                            ReportDatasets.Detail after = detail(store.lock(row.getId(), true));
                            audit(
                                    row.getId(),
                                    ReportAuditOperationEnum.MOVE,
                                    before,
                                    after,
                                    reason,
                                    actor);
                            return after;
                        });
    }

    @Override
    public ReportDatasets.Detail status(ReportDatasets.ChangeStatus command, long actor) {
        permission(actor, "nocode:report:manage");
        if (command == null) throw invalid("缺少状态配置");
        ReportResourceStatusEnum state = ReportResourceStatusEnum.fromCode(command.status());
        String reason = validator.text(command.reason(), "状态变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            store.designLock();
                            ReportDatasetDO row =
                                    accessible(
                                            command.id(),
                                            actor,
                                            true,
                                            ReportResourceActionEnum.PUBLISH);
                            revision(row, command.expectedRevision());
                            ReportDatasets.Detail before = detail(row);
                            if (state == ReportResourceStatusEnum.ACTIVE) {
                                if (row.getPublishedVersion() == null) throw invalid("数据集尚未发布");
                                ReportDatasets.Content published =
                                        release(
                                                        requiredVersion(
                                                                row.getId(),
                                                                row.getPublishedVersion()))
                                                .definition();
                                analysis.validate(
                                        published.analysis(), sources.resolve(published.source()));
                                authorization.requireCeilings(
                                        row.getId(),
                                        release(
                                                        requiredVersion(
                                                                row.getId(),
                                                                row.getPublishedVersion()))
                                                .definition()
                                                .source());
                            }
                            store.status(row.getId(), state.getCode(), Long.toString(actor));
                            ReportDatasets.Detail after = detail(store.lock(row.getId(), true));
                            audit(
                                    row.getId(),
                                    ReportAuditOperationEnum.STATUS,
                                    before,
                                    after,
                                    reason,
                                    actor);
                            return after;
                        });
    }

    private ReportDatasets.Content content(
            String name,
            String description,
            ReportDatasets.Source source,
            ReportDatasets.Analysis configuration,
            long actor) {
        String title = validator.text(name, "数据集名称", 80);
        String note = description == null ? "" : description.trim();
        if (note.length() > 1000) throw invalid("数据集说明最多 1000 个字符");
        ReportDatasets.ResolvedSource resolved = null;
        if (source != null) {
            permission(actor, "nocode:object:query");
            resolved = sources.resolve(source);
            source = resolved.source();
        }
        analysis.validate(configuration, resolved);
        return new ReportDatasets.Content(title, note, source, configuration);
    }

    /** 仅替换草稿依赖；不可变版本依赖独立保留，不能用当前草稿覆盖历史保护。 */
    private void dependencies(long id, String stage, ReportDatasets.Content content, long actor) {
        String key = id + ":" + stage;
        objects.removeDependencies(DependencyKindEnum.DATASET.getCode(), key, actor);
        if (content.source() == null) return;
        ReportDatasets.ResolvedSource resolved = sources.resolve(content.source());
        Map<String, Set<String>> fields = usage.fields(resolved);
        for (Map.Entry<String, Set<String>> entry : fields.entrySet()) {
            objects.registerDependency(
                    new DataCenter.Dependency(
                            DependencyKindEnum.DATASET.getCode(),
                            key,
                            content.name() + " [" + stage + "]",
                            entry.getKey(),
                            List.copyOf(entry.getValue())),
                    actor);
        }
    }

    private void draft(ReportDatasetDO row, ReportDatasets.Content content) {
        String value = encode(content);
        if (value.getBytes(StandardCharsets.UTF_8).length > 1_000_000) throw invalid("数据集配置过大");
        row.setName(content.name());
        row.setDescription(content.description());
        row.setDraftJson(value);
        row.setDraftChecksum(checksum(value));
    }

    private ReportDatasetDO accessible(
            String id, long actor, boolean write, ReportResourceActionEnum action) {
        ReportDatasetDO row = store.lock(validator.id(id, "数据集"), write);
        resourceAccess.require(row, actor, action);
        return row;
    }

    private void permission(long actor, String permission) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, permission))
            throw new AccessDeniedException("没有数据集操作权限");
    }

    private void revision(ReportDatasetDO row, int expected) {
        if (expected < 1 || row.getLockVersion() != expected) throw conflict();
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "数据集已被修改，请刷新后重试");
    }

    private ReportDatasets.Detail detail(ReportDatasetDO row) {
        ReportDatasetVersionDO published =
                row.getPublishedVersion() == null
                        ? null
                        : requiredVersion(row.getId(), row.getPublishedVersion());
        return new ReportDatasets.Detail(
                row.getId().toString(),
                row.getOwnerId().toString(),
                row.getStatus(),
                row.getLockVersion(),
                row.getPublishedVersion(),
                row.getDraftChecksum(),
                published == null || !row.getDraftChecksum().equals(published.getChecksum()),
                decode(row.getDraftJson()),
                row.getFolderId() == null ? null : row.getFolderId().toString());
    }

    private ReportDatasetVersionDO requiredVersion(long id, int number) {
        ReportDatasetVersionDO version = store.version(id, number);
        if (version == null) throw invalid("数据集发布版本不存在");
        return version;
    }

    private ReportDatasets.Release release(ReportDatasetVersionDO version) {
        return new ReportDatasets.Release(
                version.getDatasetId().toString(),
                version.getVersionNo(),
                version.getChecksum(),
                decode(version.getDefinitionJson()),
                version.getReason(),
                version.getCreateTime());
    }

    private void audit(
            long id,
            ReportAuditOperationEnum operation,
            ReportDatasets.Detail before,
            ReportDatasets.Detail after,
            String reason,
            long actor) {
        ReportOperationLogDO log = new ReportOperationLogDO();
        log.setResourceKind(ReportResourceKindEnum.DATASET.getCode());
        log.setResourceId(id);
        log.setAction(operation.getCode());
        log.setRevision(after.revision());
        log.setBeforeJson(encode(before));
        log.setAfterJson(encode(after));
        log.setReason(reason);
        store.audit(log, Long.toString(actor));
    }

    private ReportDatasets.Content decode(String value) {
        try {
            return json.readValue(value, ReportDatasets.Content.class);
        } catch (JsonProcessingException error) {
            throw invalid("数据集配置无法读取");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw invalid("数据集配置无法保存");
        }
    }

    private String checksum(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private void pageBounds(int pageNo, int pageSize) {
        if (pageNo < 1 || pageNo > 100000 || pageSize < 1 || pageSize > 100)
            throw invalid("分页参数无效");
    }
}
