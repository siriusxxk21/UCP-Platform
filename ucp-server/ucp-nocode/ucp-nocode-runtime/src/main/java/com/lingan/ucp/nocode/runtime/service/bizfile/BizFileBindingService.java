package com.lingan.ucp.nocode.runtime.service.bizfile;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.core.util.StrUtil;

import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizEntryDTO;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizAttachmentBindingDO;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizDirectoryBindingDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.BizAttachmentBindingMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.BizDirectoryBindingMapper;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 业务文件绑定 Service
 *
 * <p>业务保存事务内的同步编排：按对象当前发布的业务文件规则建立/调整网盘受管目录， 绑定与解绑附件节点，并把身份映射写入绑定表。任何失败抛出异常使业务保存整体回滚，
 * 不使用事件后置补偿。规则版本随记录目录绑定固定：新规则只作用于新接入的记录。
 */
@Service
public class BizFileBindingService {

    /** 单个附件字段每行的文件数上界：与业务附件控件一致，服务端必须校验；对象规则收紧在后续版本补充 */
    private static final int MAX_FIELD_FILES = 100;

    @Resource private DriveBizFileApi driveFiles;
    @Resource private DataObjectApi objects;
    @Resource private BizDirectoryBindingMapper directories;
    @Resource private BizAttachmentBindingMapper attachments;
    @Resource private BizUploadSessionService uploadSessions;
    @Resource private BizFileDirectoryNamer namer;
    @Resource private SelectionCatalog selectionCatalog;

    /** 本次保存的明细行差集输入：retained 为保存后的行，removed 为被整行删除的行 */
    public record DetailRows(String detailId, List<RowValues> retained, List<String> removed) {}

    /** 单行的新旧值（均为字段 ID 键；新行为空 previous） */
    public record RowValues(
            String rowId, Map<String, Object> previous, Map<String, Object> values) {}

    /**
     * 业务保存后同步绑定：在调用方事务内建立记录目录、绑定新增附件、解绑移除附件
     *
     * <p>未接入对象直接返回；对象未发布时（草稿期保存）同样不绑定。
     */
    public void bindOnSave(
            String applicationId,
            String objectId,
            String recordId,
            Map<String, Object> mainPrevious,
            Map<String, Object> mainFinal,
            List<DetailRows> detailGroups,
            long actor) {
        DataCenter.Definition published;
        try {
            published = objects.getPublished(objectId);
        } catch (RuntimeException noPublished) {
            return;
        }
        DataCenter.BusinessFilePolicy currentPolicy = policyOf(published);
        // 已接入记录沿用建立时的规则版本；未接入记录按当前发布版本固化
        directories.lockIdentity(objectId, recordId, "", "", "");
        BizDirectoryBindingDO recordDir =
                directories.selectByIdentity(objectId, recordId, "", "", "");
        // 先按当前最终值回收已移除的旧绑定：即使规则关闭、字段/明细被移除，也不能把旧节点永久留在 ACTIVE。
        reconcileRemovedBindings(objectId, recordId, mainFinal, detailGroups, actor);
        if (recordDir == null && !DataCenter.BusinessFilePolicy.enabled(currentPolicy)) {
            return;
        }
        DataCenter.BusinessFilePolicy effectivePolicy = currentPolicy;
        int ruleVersion;
        if (recordDir != null && recordDir.getRuleVersion() != null) {
            ruleVersion = recordDir.getRuleVersion();
            DataCenter.BusinessFilePolicy pinned = pinnedPolicy(objectId, ruleVersion);
            if (DataCenter.BusinessFilePolicy.enabled(pinned)) {
                effectivePolicy = pinned;
            }
        } else {
            ruleVersion = objects.getVersion(objectId, null).versionNo();
        }

        // 规则关闭后只继续处理旧绑定的移除生命周期，不再新建/移动目录或增加绑定。
        if (!DataCenter.BusinessFilePolicy.enabled(currentPolicy)) {
            return;
        }
        Set<String> currentFields = new LinkedHashSet<>(currentPolicy.fieldIds());
        List<String> writableFields =
                effectivePolicy.fieldIds().stream().filter(currentFields::contains).toList();
        DataCenter.BusinessFilePolicy bindingPolicy =
                new DataCenter.BusinessFilePolicy(
                        effectivePolicy.spaceId(),
                        effectivePolicy.spaceName(),
                        effectivePolicy.fixedPath(),
                        effectivePolicy.groups(),
                        effectivePolicy.recordLabelFields(),
                        writableFields);
        if (recordDir == null && !hasParticipatingFiles(bindingPolicy, mainFinal, detailGroups)) {
            return;
        }

        Long spaceId =
                recordDir != null
                        ? recordDir.getSpaceId()
                        : driveFiles.ensureBusinessSpace(
                                effectivePolicy.spaceId(), effectivePolicy.spaceName(), actor);
        List<String> recordPath =
                namer.recordPath(
                        published, effectivePolicy, mainFinal, recordId, applicationId, actor);
        String groupKeys = namer.groupKeys(published, effectivePolicy, mainFinal);
        Long recordDirEntryId;
        if (recordDir == null) {
            recordDirEntryId = createIdentityDirectory(spaceId, recordPath, actor);
            insertDirectoryBinding(
                    objectId,
                    recordId,
                    "",
                    "",
                    "",
                    spaceId,
                    recordDirEntryId,
                    ruleVersion,
                    groupKeys,
                    actor);
        } else {
            recordDirEntryId = recordDir.getEntryId();
            // 幂等调整：分组或标题值变化时移动记录目录，无变化时由网盘侧直接返回
            driveFiles.moveDirectory(recordDirEntryId, recordPath, actor);
            if (!Objects.equals(recordDir.getGroupKeys(), groupKeys)) {
                BizDirectoryBindingDO update = new BizDirectoryBindingDO();
                update.setId(recordDir.getId());
                update.setGroupKeys(groupKeys);
                update.setUpdater(Long.toString(actor));
                directories.updateById(update);
            }
        }

        for (String fieldId : bindingPolicy.fieldIds()) {
            boolean mainField = published.fields().stream().anyMatch(f -> f.id().equals(fieldId));
            if (!mainField) {
                continue;
            }
            bindField(
                    published,
                    applicationId,
                    objectId,
                    recordId,
                    "",
                    "",
                    fieldId,
                    recordPath,
                    spaceId,
                    ruleVersion,
                    groupKeys,
                    mainPrevious,
                    mainFinal,
                    actor);
        }

        for (DetailRows group : detailGroups) {
            bindDetailGroup(
                    published,
                    bindingPolicy,
                    applicationId,
                    objectId,
                    recordId,
                    group,
                    recordPath,
                    spaceId,
                    ruleVersion,
                    groupKeys,
                    actor);
        }
    }

    /** 记录删除：全部附件绑定转 HISTORY 并移除网盘节点，再清理只含空目录的记录目录子树 */
    public void unbindRecord(String objectId, String recordId, long actor) {
        List<BizAttachmentBindingDO> bindings = attachments.selectListByRecord(objectId, recordId);
        List<Long> historyIds = new ArrayList<>();
        for (BizAttachmentBindingDO binding : bindings) {
            if (!BizAttachmentBindingDO.STATE_ACTIVE.equals(binding.getState())) {
                continue;
            }
            try {
                driveFiles.unbindFile(binding.getEntryId(), actor);
            } catch (RuntimeException missing) {
                // 节点已被并发移除时继续清理绑定，不阻断删除
            }
            historyIds.add(binding.getId());
        }
        if (!historyIds.isEmpty()) {
            attachments.markHistory(historyIds, Long.toString(actor));
        }
        List<BizDirectoryBindingDO> directoryBindings =
                directories.selectListByRecord(objectId, recordId);
        if (directoryBindings.isEmpty()) {
            return;
        }
        BizDirectoryBindingDO recordDir =
                directoryBindings.stream()
                        .filter(
                                b ->
                                        StrUtil.isEmpty(b.getDetailId())
                                                && StrUtil.isEmpty(b.getRowId()))
                        .filter(b -> StrUtil.isEmpty(b.getFieldId()))
                        .findFirst()
                        .orElse(null);
        if (recordDir != null) {
            try {
                driveFiles.removeDirectory(recordDir.getEntryId(), actor);
            } catch (RuntimeException conflict) {
                // 子树仍含文件节点说明存在并发绑定，保留目录待对账处理
            }
        }
        for (BizDirectoryBindingDO binding : directoryBindings) {
            directories.deleteById(binding.getId());
        }
    }

    private void bindDetailGroup(
            DataCenter.Definition published,
            DataCenter.BusinessFilePolicy policy,
            String applicationId,
            String objectId,
            String recordId,
            DetailRows group,
            List<String> recordPath,
            Long spaceId,
            int ruleVersion,
            String groupKeys,
            long actor) {
        DataCenter.Detail detail =
                published.details().stream()
                        .filter(x -> x.id().equals(group.detailId()))
                        .findFirst()
                        .orElse(null);
        if (detail == null) {
            return;
        }
        List<String> detailFields = new ArrayList<>();
        for (String fieldId : policy.fieldIds()) {
            if (detail.fields().stream().anyMatch(f -> f.id().equals(fieldId))) {
                detailFields.add(fieldId);
            }
        }
        if (detailFields.isEmpty() && group.removed().isEmpty()) {
            return;
        }
        List<String> regionPath = new ArrayList<>(recordPath);
        regionPath.add(namer.sanitize(detail.name()));
        for (String rowId : group.removed()) {
            unbindRow(objectId, recordId, group.detailId(), rowId, actor);
        }
        boolean hasRetainedFiles =
                group.retained().stream()
                        .anyMatch(
                                row ->
                                        detailFields.stream()
                                                .anyMatch(
                                                        fieldId ->
                                                                !fileIds(
                                                                                row.values() == null
                                                                                        ? null
                                                                                        : row.values()
                                                                                                .get(
                                                                                                        fieldId))
                                                                        .isEmpty()));
        if (!hasRetainedFiles) {
            return;
        }
        ensureDirectoryBinding(
                objectId,
                recordId,
                group.detailId(),
                "",
                "",
                regionPath,
                spaceId,
                ruleVersion,
                groupKeys,
                actor);
        for (RowValues row : group.retained()) {
            List<String> rowPath = new ArrayList<>(regionPath);
            rowPath.add(namer.rowLabel(row.rowId()));
            boolean rowHasFiles =
                    detailFields.stream()
                            .anyMatch(
                                    fieldId ->
                                            !fileIds(
                                                            row.values() == null
                                                                    ? null
                                                                    : row.values().get(fieldId))
                                                    .isEmpty());
            if (!rowHasFiles) {
                for (String fieldId : detailFields) {
                    bindField(
                            published,
                            applicationId,
                            objectId,
                            recordId,
                            group.detailId(),
                            row.rowId(),
                            fieldId,
                            rowPath,
                            spaceId,
                            ruleVersion,
                            groupKeys,
                            row.previous(),
                            row.values(),
                            actor);
                }
                continue;
            }
            ensureDirectoryBinding(
                    objectId,
                    recordId,
                    group.detailId(),
                    row.rowId(),
                    "",
                    rowPath,
                    spaceId,
                    ruleVersion,
                    groupKeys,
                    actor);
            for (String fieldId : detailFields) {
                bindField(
                        published,
                        applicationId,
                        objectId,
                        recordId,
                        group.detailId(),
                        row.rowId(),
                        fieldId,
                        rowPath,
                        spaceId,
                        ruleVersion,
                        groupKeys,
                        row.previous(),
                        row.values(),
                        actor);
            }
        }
    }

    /** 单字段差集处理：新增文件校验上传会话后绑定，移除文件解绑转 HISTORY */
    private void bindField(
            DataCenter.Definition published,
            String applicationId,
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            List<String> parentPath,
            Long spaceId,
            int ruleVersion,
            String groupKeys,
            Map<String, Object> previous,
            Map<String, Object> values,
            long actor) {
        Set<Long> oldIds = fileIds(previous == null ? null : previous.get(fieldId));
        Set<Long> newIds = fileIds(values == null ? null : values.get(fieldId));
        // 数量上界在保存时按最终字段值判定：上传阶段无法得知整单保存后的实际数量
        if (newIds.size() > MAX_FIELD_FILES) {
            throw invalid("单个附件字段最多 " + MAX_FIELD_FILES + " 个文件");
        }
        Set<Long> added = new LinkedHashSet<>(newIds);
        added.removeAll(oldIds);
        Set<Long> removed = new LinkedHashSet<>(oldIds);
        removed.removeAll(newIds);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        List<BizAttachmentBindingDO> existing =
                attachments.selectListByIdentity(objectId, recordId, detailId, rowId, fieldId);
        Map<Long, BizAttachmentBindingDO> byFile = new LinkedHashMap<>();
        for (BizAttachmentBindingDO binding : existing) {
            byFile.put(binding.getFileId(), binding);
        }
        if (!added.isEmpty()) {
            List<String> fieldPath = new ArrayList<>(parentPath);
            fieldPath.add(fieldLabel(published, detailId, fieldId));
            BizDirectoryBindingDO fieldDir =
                    ensureDirectoryBinding(
                            objectId,
                            recordId,
                            detailId,
                            rowId,
                            fieldId,
                            fieldPath,
                            spaceId,
                            ruleVersion,
                            groupKeys,
                            actor);
            // 新增文件必须来自当前用户在本对象字段上的有效上传会话（A12）
            List<Long> sessionIds =
                    uploadSessions.validateOwnership(
                            actor, objectId, fieldId, recordId, new ArrayList<>(added));
            for (Long fileId : added) {
                // 受管节点展示名与大小在绑定后不可变，随绑定写入浏览索引冗余列
                DriveBizEntryDTO entry =
                        driveFiles.bindFile(
                                spaceId, fieldDir.getEntryId(), fileId, applicationId, actor);
                upsertAttachmentBinding(
                        objectId,
                        recordId,
                        detailId,
                        rowId,
                        fieldId,
                        fileId,
                        entry,
                        spaceId,
                        applicationId,
                        byFile.get(fileId),
                        actor);
            }
            uploadSessions.markBound(sessionIds, actor);
        }
        List<Long> historyIds = new ArrayList<>();
        for (Long fileId : removed) {
            BizAttachmentBindingDO binding = byFile.get(fileId);
            if (binding == null
                    || !BizAttachmentBindingDO.STATE_ACTIVE.equals(binding.getState())) {
                continue;
            }
            driveFiles.unbindFile(binding.getEntryId(), actor);
            historyIds.add(binding.getId());
        }
        if (!historyIds.isEmpty()) {
            attachments.markHistory(historyIds, Long.toString(actor));
        }
    }

    /** 明细行删除：该行全部附件转 HISTORY，行目录子树（此时只含空目录）一并移除 */
    private void unbindRow(
            String objectId, String recordId, String detailId, String rowId, long actor) {
        List<BizAttachmentBindingDO> bindings =
                attachments.selectListByRow(objectId, recordId, detailId, rowId);
        List<Long> historyIds = new ArrayList<>();
        for (BizAttachmentBindingDO binding : bindings) {
            if (!BizAttachmentBindingDO.STATE_ACTIVE.equals(binding.getState())) {
                continue;
            }
            try {
                driveFiles.unbindFile(binding.getEntryId(), actor);
            } catch (RuntimeException missing) {
                // 节点已被并发移除时继续清理绑定
            }
            historyIds.add(binding.getId());
        }
        if (!historyIds.isEmpty()) {
            attachments.markHistory(historyIds, Long.toString(actor));
        }
        List<BizDirectoryBindingDO> directoryBindings =
                directories.selectListByRow(objectId, recordId, detailId, rowId);
        if (directoryBindings.isEmpty()) {
            return;
        }
        BizDirectoryBindingDO rowDir =
                directoryBindings.stream()
                        .filter(b -> StrUtil.isEmpty(b.getFieldId()))
                        .findFirst()
                        .orElse(null);
        if (rowDir != null) {
            try {
                driveFiles.removeDirectory(rowDir.getEntryId(), actor);
            } catch (RuntimeException conflict) {
                // 并发写入导致子树仍有文件时保留目录，待对账处理
            }
        }
        for (BizDirectoryBindingDO binding : directoryBindings) {
            directories.deleteById(binding.getId());
        }
    }

    private BizDirectoryBindingDO ensureDirectoryBinding(
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            List<String> path,
            Long spaceId,
            int ruleVersion,
            String groupKeys,
            long actor) {
        BizDirectoryBindingDO existing =
                directories.selectByIdentity(objectId, recordId, detailId, rowId, fieldId);
        if (existing != null) {
            return existing;
        }
        String safeDetail = StrUtil.emptyIfNull(detailId);
        String safeRow = StrUtil.emptyIfNull(rowId);
        String safeField = StrUtil.emptyIfNull(fieldId);
        directories.lockIdentity(objectId, recordId, safeDetail, safeRow, safeField);
        existing = directories.selectByIdentity(objectId, recordId, safeDetail, safeRow, safeField);
        if (existing != null) {
            return existing;
        }
        Long parentEntryId =
                parentDirectoryEntryId(objectId, recordId, safeDetail, safeRow, safeField);
        Long entryId =
                driveFiles.createManagedDirectory(spaceId, parentEntryId, path.getLast(), actor);
        return insertDirectoryBinding(
                objectId,
                recordId,
                detailId,
                rowId,
                fieldId,
                spaceId,
                entryId,
                ruleVersion,
                groupKeys,
                actor);
    }

    /** 记录目录的上层固定/分组路径可共享，末级记录身份必须独立建立。 */
    private Long createIdentityDirectory(Long spaceId, List<String> path, long actor) {
        if (path == null || path.isEmpty()) {
            throw invalid("业务目录路径不能为空");
        }
        List<String> parentPath = path.subList(0, path.size() - 1);
        Long parentEntryId =
                parentPath.isEmpty()
                        ? 0L // drive_entry 约定 0 为空间根目录
                        : driveFiles.ensureDirectory(spaceId, parentPath, actor);
        return driveFiles.createManagedDirectory(spaceId, parentEntryId, path.getLast(), actor);
    }

    /** 稳定身份层级的父目录从已落库绑定取回，不再按展示名反查。 */
    private Long parentDirectoryEntryId(
            String objectId, String recordId, String detailId, String rowId, String fieldId) {
        BizDirectoryBindingDO parent;
        if (!fieldId.isEmpty()) {
            parent =
                    detailId.isEmpty()
                            ? directories.selectByIdentity(objectId, recordId, "", "", "")
                            : directories.selectByIdentity(objectId, recordId, detailId, rowId, "");
        } else if (!rowId.isEmpty()) {
            parent = directories.selectByIdentity(objectId, recordId, detailId, "", "");
        } else if (!detailId.isEmpty()) {
            parent = directories.selectByIdentity(objectId, recordId, "", "", "");
        } else {
            throw invalid("记录目录必须通过记录身份建立");
        }
        if (parent == null) {
            throw invalid("业务目录父级绑定不存在");
        }
        return parent.getEntryId();
    }

    private BizDirectoryBindingDO insertDirectoryBinding(
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long spaceId,
            Long entryId,
            int ruleVersion,
            String groupKeys,
            long actor) {
        BizDirectoryBindingDO binding = new BizDirectoryBindingDO();
        binding.setObjectId(objectId);
        binding.setRecordId(recordId);
        binding.setDetailId(StrUtil.emptyIfNull(detailId));
        binding.setRowId(StrUtil.emptyIfNull(rowId));
        binding.setFieldId(StrUtil.emptyIfNull(fieldId));
        binding.setSpaceId(spaceId);
        binding.setEntryId(entryId);
        binding.setRuleVersion(ruleVersion);
        binding.setGroupKeys(groupKeys);
        binding.setCreator(Long.toString(actor));
        binding.setUpdater(Long.toString(actor));
        directories.insert(binding);
        return binding;
    }

    private void upsertAttachmentBinding(
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long fileId,
            DriveBizEntryDTO entry,
            Long spaceId,
            String sourceEntry,
            BizAttachmentBindingDO existing,
            long actor) {
        if (existing != null) {
            BizAttachmentBindingDO update = new BizAttachmentBindingDO();
            update.setId(existing.getId());
            update.setEntryId(entry.id());
            update.setSpaceId(spaceId);
            update.setFileName(entry.name());
            update.setFileSize(entry.size());
            update.setMimeType(entry.mimeType());
            update.setState(BizAttachmentBindingDO.STATE_ACTIVE);
            update.setSourceEntry(StrUtil.emptyIfNull(sourceEntry));
            update.setUpdater(Long.toString(actor));
            attachments.updateById(update);
            return;
        }
        BizAttachmentBindingDO binding = new BizAttachmentBindingDO();
        binding.setObjectId(objectId);
        binding.setRecordId(recordId);
        binding.setDetailId(StrUtil.emptyIfNull(detailId));
        binding.setRowId(StrUtil.emptyIfNull(rowId));
        binding.setFieldId(fieldId);
        binding.setFileId(fileId);
        binding.setEntryId(entry.id());
        binding.setSpaceId(spaceId);
        binding.setFileName(StrUtil.emptyIfNull(entry.name()));
        binding.setFileSize(entry.size());
        binding.setMimeType(StrUtil.emptyIfNull(entry.mimeType()));
        binding.setState(BizAttachmentBindingDO.STATE_ACTIVE);
        binding.setSourceEntry(StrUtil.emptyIfNull(sourceEntry));
        binding.setCreator(Long.toString(actor));
        binding.setUpdater(Long.toString(actor));
        attachments.insert(binding);
    }

    /**
     * 对已有 ACTIVE 绑定与本次最终业务值做差集。
     *
     * <p>该步骤不依赖当前规则仍启用，因此能处理规则关闭、参与字段移除与明细结构变更后的旧节点生命周期。
     */
    private void reconcileRemovedBindings(
            String objectId,
            String recordId,
            Map<String, Object> mainFinal,
            List<DetailRows> detailGroups,
            long actor) {
        Map<String, DetailRows> groups = new LinkedHashMap<>();
        if (detailGroups != null) {
            for (DetailRows group : detailGroups) {
                groups.put(group.detailId(), group);
            }
        }
        List<Long> historyIds = new ArrayList<>();
        for (BizAttachmentBindingDO binding : attachments.selectListByRecord(objectId, recordId)) {
            if (!BizAttachmentBindingDO.STATE_ACTIVE.equals(binding.getState())) {
                continue;
            }
            Set<Long> current;
            if (StrUtil.isEmpty(binding.getDetailId())) {
                current = fileIds(mainFinal == null ? null : mainFinal.get(binding.getFieldId()));
            } else {
                DetailRows group = groups.get(binding.getDetailId());
                if (group == null) {
                    // 本次写链路未携带该明细的最终值，不做否定推断。
                    continue;
                }
                RowValues row =
                        group.retained().stream()
                                .filter(item -> item.rowId().equals(binding.getRowId()))
                                .findFirst()
                                .orElse(null);
                current =
                        fileIds(
                                row == null || row.values() == null
                                        ? null
                                        : row.values().get(binding.getFieldId()));
            }
            if (current.contains(binding.getFileId())) {
                continue;
            }
            try {
                driveFiles.unbindFile(binding.getEntryId(), actor);
            } catch (RuntimeException missing) {
                // 节点已不存在时仍须收敛绑定状态，后续读取不再展示为当前附件。
            }
            historyIds.add(binding.getId());
        }
        if (!historyIds.isEmpty()) {
            attachments.markHistory(historyIds, Long.toString(actor));
        }
    }

    /** 首次接入只有在最终值真正含文件时才建立空间与记录目录。 */
    private boolean hasParticipatingFiles(
            DataCenter.BusinessFilePolicy policy,
            Map<String, Object> mainFinal,
            List<DetailRows> detailGroups) {
        for (String fieldId : policy.fieldIds()) {
            if (!fileIds(mainFinal == null ? null : mainFinal.get(fieldId)).isEmpty()) {
                return true;
            }
        }
        if (detailGroups == null) {
            return false;
        }
        for (DetailRows group : detailGroups) {
            for (RowValues row : group.retained()) {
                for (String fieldId : policy.fieldIds()) {
                    if (!fileIds(row.values() == null ? null : row.values().get(fieldId))
                            .isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 字段值中的文件 ID 列表（字段值以 fileId 数组存储） */
    private Set<Long> fileIds(Object value) {
        Set<Long> result = new LinkedHashSet<>();
        for (String id : selectionCatalog.ids(value)) {
            if (id != null && id.matches("[1-9][0-9]{0,18}")) {
                result.add(Long.valueOf(id));
            }
        }
        return result;
    }

    private String fieldLabel(DataCenter.Definition definition, String detailId, String fieldId) {
        List<com.lingan.ucp.nocode.api.FieldDefinition> fields =
                StrUtil.isEmpty(detailId)
                        ? definition.fields()
                        : definition.details().stream()
                                .filter(d -> d.id().equals(detailId))
                                .findFirst()
                                .map(DataCenter.Detail::fields)
                                .orElse(List.of());
        String name =
                fields.stream()
                        .filter(f -> f.id().equals(fieldId))
                        .map(com.lingan.ucp.nocode.api.FieldDefinition::name)
                        .findFirst()
                        .orElse("附件");
        return namer.sanitize(name);
    }

    /** 对象定义中的业务文件规则；未接入或历史定义缺失时为空 */
    public DataCenter.BusinessFilePolicy policyOf(DataCenter.Definition definition) {
        return definition.settings() == null ? null : definition.settings().businessFilePolicy();
    }

    /** 记录目录绑定的规则版本对应的规则；版本不可读时返回空，由调用方决定降级方式 */
    public DataCenter.BusinessFilePolicy pinnedPolicy(String objectId, int versionNo) {
        try {
            DataCenter.Definition pinned = objects.getVersion(objectId, versionNo).definition();
            return pinned.settings() == null ? null : pinned.settings().businessFilePolicy();
        } catch (RuntimeException missing) {
            // 版本不可读（历史版本清理等）时退化为当前规则
            return null;
        }
    }

    /** 供发布预检等场景判断对象是否接入业务文件 */
    public boolean integrated(DataCenter.Definition definition) {
        return DataCenter.BusinessFilePolicy.enabled(policyOf(definition));
    }

    /** 供发布预检等场景判断字段是否接入业务文件 */
    public boolean participatingField(DataCenter.Definition definition, String fieldId) {
        DataCenter.BusinessFilePolicy policy = policyOf(definition);
        return DataCenter.BusinessFilePolicy.enabled(policy) && policy.fieldIds().contains(fieldId);
    }
}
