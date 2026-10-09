package com.richuang.os.module.drive.service.entry;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.util.collection.CollectionUtils;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryBreadcrumbRespVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryFolderCreateReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryInheritReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRenameReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRespVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntrySearchReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveTrashListReqVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryChainRow;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.dataobject.mark.DriveUserMarkDO;
import com.richuang.os.module.drive.dal.dataobject.share.DriveShareDO;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.mark.DriveUserMarkMapper;
import com.richuang.os.module.drive.dal.mysql.permission.DrivePermissionMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.enums.entry.DriveEntryTypeEnum;
import com.richuang.os.module.drive.enums.entry.DriveTrashStateEnum;
import com.richuang.os.module.drive.enums.mark.DriveMarkTypeEnum;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.enums.space.DriveSpaceTypeEnum;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.drive.service.space.DriveSpaceService;
import com.richuang.os.module.drive.service.storage.DriveStorageService;
import com.richuang.os.module.infra.api.file.FileApi;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网盘节点 Service 实现类
 *
 * @author os
 */
@Slf4j
@Service
@Validated
public class DriveEntryServiceImpl implements DriveEntryService {

    /** 单文件上限，与平台上传上限一致；受 infra_file.size 为 integer 的存储口径约束，暂按 1GiB 控制 */
    private static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;

    /** 名称长度上限，与 drive_entry.name 字段一致 */
    private static final int MAX_NAME_LENGTH = 255;

    /** 搜索返回条数上限 */
    private static final int MAX_SEARCH_LIMIT = 200;

    /** 搜索默认返回条数 */
    private static final int DEFAULT_SEARCH_LIMIT = 50;

    /** 自动改名时尝试的最大序号，避免极端冲突下无限循环 */
    private static final int MAX_RENAME_ATTEMPT = 1000;

    @Resource private DriveEntryMapper entryMapper;
    @Resource private DriveSpaceMapper spaceMapper;
    @Resource private DrivePermissionMapper permissionMapper;
    @Resource private DriveShareMapper shareMapper;
    @Resource private DriveShareSubjectMapper shareSubjectMapper;
    @Resource private DriveUserMarkMapper markMapper;
    @Resource private DriveSpaceService spaceService;
    @Resource private DrivePermissionService permissionService;
    @Resource private FileApi fileApi;
    @Resource private DriveStorageService storageService;

    @Override
    public List<DriveEntryDO> getEntryList(DriveEntryListReqVO reqVO, Long userId) {
        permissionService.validatePermission(
                reqVO.getSpaceId(), reqVO.getParentId(), userId, DrivePermissionRoleEnum.VIEWER);
        validParent(reqVO.getSpaceId(), reqVO.getParentId());
        // 业务空间的文件夹区只列普通节点：受管节点（附件归档区）不经普通网盘入口展示
        DriveSpaceDO space = spaceMapper.selectById(reqVO.getSpaceId());
        List<DriveEntryDO> entries =
                space != null && DriveSpaceTypeEnum.isBiz(space.getType())
                        ? entryMapper.selectOrdinaryListByParent(
                                reqVO.getSpaceId(), reqVO.getParentId())
                        : entryMapper.selectListByParent(reqVO.getSpaceId(), reqVO.getParentId());
        // 每个子节点单独判断权限，避免列出断开继承的未授权文件。
        return entries.stream()
                .filter(
                        entry ->
                                permissionService.getEffectiveRole(
                                                entry.getSpaceId(), entry.getId(), userId)
                                        != null)
                .toList();
    }

    @Override
    public List<DriveEntryDO> searchEntryList(DriveEntrySearchReqVO reqVO, Long userId) {
        if (!(permissionService.getAccessibleSpaceIds(userId).contains(reqVO.getSpaceId())
                || permissionService.getEffectiveRole(
                                reqVO.getSpaceId(), DriveEntryDO.PARENT_ID_ROOT, userId)
                        != null)) {
            throw exception(PERMISSION_DENIED);
        }
        int limit =
                reqVO.getLimit() != null && reqVO.getLimit() > 0
                        ? Math.min(reqVO.getLimit(), MAX_SEARCH_LIMIT)
                        : DEFAULT_SEARCH_LIMIT;
        // 先按名称取候选，再逐个按有效角色过滤，保证搜索结果不泄露无权节点
        List<DriveEntryDO> candidates =
                entryMapper.selectListByNameLike(reqVO.getSpaceId(), reqVO.getName(), limit);
        List<DriveEntryDO> result = new ArrayList<>();
        for (DriveEntryDO entry : candidates) {
            if (permissionService.getEffectiveRole(entry.getSpaceId(), entry.getId(), userId)
                    != null) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public DriveEntryDO getEntry(Long id, Long userId) {
        DriveEntryDO entry = validEntry(id);
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.VIEWER);
        return entry;
    }

    @Override
    public DriveEntryRespVO getEntryDetail(Long id, Long userId) {
        DriveEntryDO entry = getEntry(id, userId);
        DriveEntryRespVO vo = BeanUtils.toBean(entry, DriveEntryRespVO.class);
        DrivePermissionRoleEnum role =
                permissionService.getEffectiveRole(entry.getSpaceId(), entry.getId(), userId);
        vo.setRole(role != null ? role.getCode() : null);
        vo.setFavorite(
                markMapper.selectByUserAndEntryAndType(
                                userId, entry.getId(), DriveMarkTypeEnum.FAVORITE.getCode())
                        != null);
        return vo;
    }

    @Override
    public Map<Long, Boolean> getFavoriteMap(Long userId, Collection<Long> entryIds) {
        if (CollUtil.isEmpty(entryIds) || userId == null) {
            return Map.of();
        }
        List<DriveUserMarkDO> marks =
                markMapper.selectListByUserAndTypeAndEntryIds(
                        userId, DriveMarkTypeEnum.FAVORITE.getCode(), entryIds);
        Map<Long, Boolean> result = new LinkedHashMap<>();
        for (DriveUserMarkDO mark : marks) {
            result.put(mark.getEntryId(), Boolean.TRUE);
        }
        return result;
    }

    @Override
    public List<DriveEntryBreadcrumbRespVO> getBreadcrumbList(Long entryId, Long userId) {
        DriveEntryDO entry = validEntry(entryId);
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.VIEWER);
        List<DriveEntryChainRow> chain = entryMapper.selectPathChain(entry.getSpaceId(), entryId);
        List<DriveEntryBreadcrumbRespVO> path = new ArrayList<>(chain.size() + 1);
        DriveEntryBreadcrumbRespVO root = new DriveEntryBreadcrumbRespVO();
        root.setId(DriveEntryDO.PARENT_ID_ROOT);
        root.setName("全部文件");
        path.add(root);
        // 路径自节点向上返回，展示时需要自根向下
        List<DriveEntryChainRow> ordered = new ArrayList<>(chain);
        Collections.reverse(ordered);
        Map<Long, String> nameMap = resolveEntryNames(ordered);
        for (DriveEntryChainRow row : ordered) {
            DriveEntryBreadcrumbRespVO vo = new DriveEntryBreadcrumbRespVO();
            vo.setId(row.getId());
            vo.setName(nameMap.getOrDefault(row.getId(), StrUtil.EMPTY));
            path.add(vo);
        }
        return path;
    }

    @Override
    public Long createFolder(DriveEntryFolderCreateReqVO reqVO, Long userId) {
        spaceService.getSpace(reqVO.getSpaceId());
        permissionService.validatePermission(
                reqVO.getSpaceId(), reqVO.getParentId(), userId, DrivePermissionRoleEnum.EDITOR);
        validParent(reqVO.getSpaceId(), reqVO.getParentId());
        String name = validateName(reqVO.getName());
        if (entryMapper.selectBySpaceAndParentAndName(reqVO.getSpaceId(), reqVO.getParentId(), name)
                != null) {
            throw exception(ENTRY_NAME_DUPLICATE);
        }
        DriveEntryDO entry =
                DriveEntryDO.builder()
                        .spaceId(reqVO.getSpaceId())
                        .parentId(reqVO.getParentId())
                        .name(name)
                        .type(DriveEntryTypeEnum.FOLDER.getCode())
                        .size(0L)
                        .inheritParent(Boolean.TRUE)
                        .trashState(DriveTrashStateEnum.NORMAL.getCode())
                        .lockVersion(0)
                        .build();
        try {
            entryMapper.insert(entry);
        } catch (DuplicateKeyException ex) {
            // 唯一索引兜底两次并发建目录：都通过上面的预检时，后到的按重名提示
            throw exception(ENTRY_NAME_DUPLICATE);
        }
        return entry.getId();
    }

    @Override
    public DriveEntryDO uploadFile(
            Long spaceId,
            Long parentId,
            String fileName,
            String contentType,
            long size,
            InputStream content,
            Long userId) {
        spaceService.getSpace(spaceId);
        permissionService.validatePermission(
                spaceId, parentId, userId, DrivePermissionRoleEnum.EDITOR);
        validParent(spaceId, parentId);
        if (size > MAX_FILE_SIZE) {
            throw exception(ENTRY_FILE_TOO_LARGE);
        }
        String rawName = validateName(fileName);
        // V1 无版本链：同名文件保留两份并自动加序号，不覆盖既有内容
        Long storageConfigId = storageService.getSelectedConfigId();
        FileDO file =
                storageConfigId == null
                        ? fileApi.createProtectedFile(
                                content, size, rawName, buildStorageDirectory(spaceId), contentType)
                        : fileApi.createProtectedFile(
                                storageConfigId,
                                content,
                                size,
                                rawName,
                                buildStorageDirectory(spaceId),
                                contentType);
        DriveEntryDO entry =
                DriveEntryDO.builder()
                        .spaceId(spaceId)
                        .parentId(parentId)
                        .name(rawName)
                        .type(DriveEntryTypeEnum.FILE.getCode())
                        .fileId(file.getId())
                        .size(size)
                        .mimeType(file.getType())
                        .inheritParent(Boolean.TRUE)
                        .trashState(DriveTrashStateEnum.NORMAL.getCode())
                        .lockVersion(0)
                        .build();
        // 同一目录的同名判断先查后插，两次上传同时到达会各写一份；唯一索引兜底，冲突时换名重试
        for (int attempt = 0; ; attempt++) {
            entry.setName(buildUniqueName(spaceId, parentId, rawName, null));
            try {
                entryMapper.insert(entry);
                spaceMapper.updateUsedBytes(spaceId, size);
                return entry;
            } catch (DuplicateKeyException ex) {
                if (attempt >= MAX_RENAME_ATTEMPT) {
                    throw exception(ENTRY_NAME_DUPLICATE);
                }
                log.warn(
                        "网盘同名文件并发写入，换名重试：spaceId={}, parentId={}, name={}",
                        spaceId,
                        parentId,
                        entry.getName());
            }
        }
    }

    @Override
    public void renameEntry(DriveEntryRenameReqVO reqVO, Long userId) {
        DriveEntryDO entry = validEntry(reqVO.getId());
        validateNotManaged(entry);
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.EDITOR);
        if (DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        String name = validateName(reqVO.getName());
        if (ObjUtil.equal(name, entry.getName())) {
            return;
        }
        if (DriveEntryTypeEnum.isFolder(entry.getType())) {
            // 目录名在父目录内唯一，重名直接提示而不是自动加序号
            DriveEntryDO conflict =
                    entryMapper.selectBySpaceAndParentAndName(
                            entry.getSpaceId(), entry.getParentId(), name);
            if (conflict != null && ObjUtil.notEqual(conflict.getId(), entry.getId())) {
                throw exception(ENTRY_NAME_DUPLICATE);
            }
            try {
                updateEntryChecked(
                        entry, DriveEntryDO.builder().id(entry.getId()).name(name).build());
            } catch (DuplicateKeyException ex) {
                throw exception(ENTRY_NAME_DUPLICATE);
            }
            return;
        }
        // 文件重名自动加序号；并发改名由唯一索引拒绝，按最新占用情况重算
        for (int attempt = 0; ; attempt++) {
            String candidate =
                    buildUniqueName(entry.getSpaceId(), entry.getParentId(), name, entry.getId());
            try {
                updateEntryChecked(
                        entry, DriveEntryDO.builder().id(entry.getId()).name(candidate).build());
                return;
            } catch (DuplicateKeyException ex) {
                if (attempt >= MAX_RENAME_ATTEMPT) {
                    throw exception(ENTRY_NAME_DUPLICATE);
                }
                log.warn("网盘同名文件并发改名，换名重试：entryId={}, name={}", entry.getId(), candidate);
            }
        }
    }

    @Override
    public void moveEntry(DriveEntryMoveReqVO reqVO, Long userId) {
        DriveEntryDO entry = validEntry(reqVO.getId());
        validateNotManaged(entry);
        if (DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.EDITOR);
        permissionService.validatePermission(
                entry.getSpaceId(),
                reqVO.getTargetParentId(),
                userId,
                DrivePermissionRoleEnum.EDITOR);
        validParent(entry.getSpaceId(), reqVO.getTargetParentId());
        if (ObjUtil.equal(entry.getParentId(), reqVO.getTargetParentId())) {
            return;
        }
        if (DriveEntryTypeEnum.isFolder(entry.getType())
                && Boolean.TRUE.equals(
                        entryMapper.selectIsInSubtree(reqVO.getTargetParentId(), entry.getId()))) {
            throw exception(ENTRY_MOVE_TO_CHILD);
        }
        updateEntryChecked(
                entry,
                DriveEntryDO.builder()
                        .id(entry.getId())
                        .parentId(reqVO.getTargetParentId())
                        .build());
    }

    @Override
    public void updateInheritParent(DriveEntryInheritReqVO reqVO, Long userId) {
        DriveEntryDO entry = validEntry(reqVO.getId());
        validateNotManaged(entry);
        if (DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        // 继承边界决定谁能读到子树，按可管理权限而非可编辑权限校验
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.MANAGER);
        if (ObjUtil.equal(entry.getInheritParent(), reqVO.getInheritParent())) {
            return;
        }
        updateEntryChecked(
                entry,
                DriveEntryDO.builder()
                        .id(entry.getId())
                        .inheritParent(reqVO.getInheritParent())
                        .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyEntry(DriveEntryCopyReqVO reqVO, Long userId) {
        DriveEntryDO source = validEntry(reqVO.getId());
        // 受管业务文件首期不开放复制到个人/团队空间，避免绕过业务授权导出
        validateNotManaged(source);
        if (DriveTrashStateEnum.isTrashed(source.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        spaceService.getSpace(reqVO.getTargetSpaceId());
        permissionService.validatePermission(
                source.getSpaceId(), source.getId(), userId, DrivePermissionRoleEnum.VIEWER);
        permissionService.validatePermission(
                reqVO.getTargetSpaceId(),
                reqVO.getTargetParentId(),
                userId,
                DrivePermissionRoleEnum.EDITOR);
        validParent(reqVO.getTargetSpaceId(), reqVO.getTargetParentId());

        List<DriveEntryDO> subtree = entryMapper.selectSubtree(source.getSpaceId(), source.getId());
        // Mapper 按真实层级排序。旧文件移入新目录后，编号大小不再代表父子顺序。
        // 先检查全部待复制节点，避免通过复制父目录导出断开继承的内容，也避免拒绝后遗留物理文件。
        for (DriveEntryDO node : subtree) {
            if (!DriveTrashStateEnum.isTrashed(node.getTrashState())) {
                validateNotManaged(node);
                permissionService.validatePermission(
                        node.getSpaceId(), node.getId(), userId, DrivePermissionRoleEnum.VIEWER);
            }
        }
        Map<Long, Long> newIdMap = new LinkedHashMap<>();
        long copiedSize = 0L;
        Long storageConfigId = storageService.getSelectedConfigId();
        Long rootNewId = null;
        for (DriveEntryDO node : subtree) {
            if (DriveTrashStateEnum.isTrashed(node.getTrashState())) {
                continue;
            }
            Long newParentId =
                    ObjUtil.equal(node.getId(), source.getId())
                            ? reqVO.getTargetParentId()
                            : newIdMap.get(node.getParentId());
            if (newParentId == null) {
                // 父节点在回收站中而被跳过，整棵子树不复制
                continue;
            }
            String name =
                    buildUniqueName(reqVO.getTargetSpaceId(), newParentId, node.getName(), null);
            DriveEntryDO target =
                    DriveEntryDO.builder()
                            .spaceId(reqVO.getTargetSpaceId())
                            .parentId(newParentId)
                            .name(name)
                            .type(node.getType())
                            .size(node.getSize())
                            .mimeType(node.getMimeType())
                            .inheritParent(Boolean.TRUE)
                            .trashState(DriveTrashStateEnum.NORMAL.getCode())
                            .lockVersion(0)
                            .build();
            if (DriveEntryTypeEnum.isFile(node.getType())) {
                byte[] copyContent = fileApi.getFileContent(node.getFileId());
                FileDO file =
                        storageConfigId == null
                                ? fileApi.createProtectedFile(
                                        copyContent,
                                        name,
                                        buildStorageDirectory(reqVO.getTargetSpaceId()),
                                        node.getMimeType())
                                : fileApi.createProtectedFile(
                                        storageConfigId,
                                        new ByteArrayInputStream(copyContent),
                                        copyContent.length,
                                        name,
                                        buildStorageDirectory(reqVO.getTargetSpaceId()),
                                        node.getMimeType());
                target.setFileId(file.getId());
                target.setSize(file.getSize());
                copiedSize += file.getSize() != null ? file.getSize() : 0L;
            }
            entryMapper.insert(target);
            newIdMap.put(node.getId(), target.getId());
            if (ObjUtil.equal(node.getId(), source.getId())) {
                rootNewId = target.getId();
            }
        }
        if (copiedSize > 0) {
            spaceMapper.updateUsedBytes(reqVO.getTargetSpaceId(), copiedSize);
        }
        if (rootNewId == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        return rootNewId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void trashEntryList(Collection<Long> ids, Long userId) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        for (Long id : ids) {
            DriveEntryDO entry = validEntry(id);
            validateNotManaged(entry);
            if (DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
                throw exception(ENTRY_ALREADY_TRASHED);
            }
            permissionService.validatePermission(
                    entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.EDITOR);
            List<DriveEntryDO> subtree =
                    entryMapper.selectSubtree(entry.getSpaceId(), entry.getId());
            List<Long> flipIds = new ArrayList<>();
            long delta = 0L;
            for (DriveEntryDO node : subtree) {
                if (DriveTrashStateEnum.isTrashed(node.getTrashState())) {
                    continue;
                }
                flipIds.add(node.getId());
                if (DriveEntryTypeEnum.isFile(node.getType())) {
                    delta -= node.getSize() != null ? node.getSize() : 0L;
                }
            }
            if (flipIds.isEmpty()) {
                continue;
            }
            entryMapper.updateTrashState(
                    flipIds,
                    DriveTrashStateEnum.TRASHED.getCode(),
                    LocalDateTime.now(),
                    String.valueOf(userId));
            // 只有删除入口节点记录原位置，子节点随入口一起恢复
            entryMapper.updateOriginParentId(entry.getId(), entry.getParentId());
            if (delta != 0L) {
                spaceMapper.updateUsedBytes(entry.getSpaceId(), delta);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DriveEntryDO restoreEntry(Long id, Long userId) {
        DriveEntryDO entry = validEntry(id);
        if (!DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            throw exception(ENTRY_NOT_TRASHED);
        }
        validateTrashOperator(entry, userId);
        // 原位置不可用时回到空间根目录，交由调用方按返回结果提示
        Long targetParentId = resolveRestoreParentId(entry);
        String targetName = entry.getName();
        if (DriveEntryTypeEnum.isFolder(entry.getType())) {
            DriveEntryDO conflict =
                    entryMapper.selectBySpaceAndParentAndName(
                            entry.getSpaceId(), targetParentId, targetName);
            if (conflict != null) {
                throw exception(ENTRY_TRASH_CONFLICT);
            }
        } else {
            // 文件同名时自动加序号，保留原位置已有内容
            targetName =
                    buildUniqueName(entry.getSpaceId(), targetParentId, targetName, entry.getId());
        }
        boolean parentChanged = ObjUtil.notEqual(targetParentId, entry.getParentId());
        if (parentChanged || ObjUtil.notEqual(targetName, entry.getName())) {
            if (parentChanged) {
                entryMapper.updateOriginParentId(entry.getId(), targetParentId);
            }
            // 名称与父节点合并为一次更新，避免两次乐观锁更新互相冲突
            updateEntryChecked(
                    entry,
                    DriveEntryDO.builder()
                            .id(entry.getId())
                            .parentId(targetParentId)
                            .name(targetName)
                            .build());
        }
        List<DriveEntryDO> subtree = entryMapper.selectSubtree(entry.getSpaceId(), entry.getId());
        List<Long> flipIds = new ArrayList<>();
        long delta = 0L;
        for (DriveEntryDO node : subtree) {
            if (!DriveTrashStateEnum.isTrashed(node.getTrashState())) {
                continue;
            }
            flipIds.add(node.getId());
            if (DriveEntryTypeEnum.isFile(node.getType())) {
                delta += node.getSize() != null ? node.getSize() : 0L;
            }
        }
        if (CollUtil.isNotEmpty(flipIds)) {
            entryMapper.updateTrashState(flipIds, DriveTrashStateEnum.NORMAL.getCode(), null, null);
        }
        if (delta != 0L) {
            spaceMapper.updateUsedBytes(entry.getSpaceId(), delta);
        }
        return entryMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purgeEntryList(Collection<Long> ids, Long userId) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        for (Long id : ids) {
            DriveEntryDO entry = validEntry(id);
            validateNotManaged(entry);
            if (!DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
                throw exception(ENTRY_NOT_TRASHED);
            }
            validateTrashOperator(entry, userId);
            // 回收站按删除入口节点操作：目录的子节点随入口节点一起进回收站，彻底删除时同样整棵子树处理
            List<DriveEntryDO> subtree =
                    entryMapper.selectSubtree(entry.getSpaceId(), entry.getId());
            for (DriveEntryDO node : subtree) {
                if (DriveEntryTypeEnum.isFile(node.getType()) && node.getFileId() != null) {
                    fileApi.deleteFile(node.getFileId());
                }
            }
            List<Long> subtreeIds = CollectionUtils.convertList(subtree, DriveEntryDO::getId);
            permissionMapper.deleteByEntryIds(subtreeIds);
            // 分享随节点一起清理：节点已彻底删除，分享记录保留会让列表条数与实际行数对不上
            List<DriveShareDO> shares = shareMapper.selectListByEntryIds(subtreeIds);
            if (CollUtil.isNotEmpty(shares)) {
                List<Long> shareIds = CollectionUtils.convertList(shares, DriveShareDO::getId);
                shareSubjectMapper.deleteByShareIds(shareIds);
                shareMapper.deleteBatch(DriveShareDO::getId, shareIds);
            }
            markMapper.deleteByEntryIds(subtreeIds);
            entryMapper.deleteBatch(DriveEntryDO::getId, subtreeIds);
        }
    }

    @Override
    public List<DriveEntryDO> getTrashList(DriveTrashListReqVO reqVO, Long userId) {
        permissionService.validatePermission(
                reqVO.getSpaceId(),
                DriveEntryDO.PARENT_ID_ROOT,
                userId,
                DrivePermissionRoleEnum.VIEWER);
        // 空间管理者查看全部删除记录，其余用户只看自己删除的节点
        DrivePermissionRoleEnum role =
                permissionService.getEffectiveRole(
                        reqVO.getSpaceId(), DriveEntryDO.PARENT_ID_ROOT, userId);
        boolean manager = role != null && role.satisfies(DrivePermissionRoleEnum.MANAGER);
        return entryMapper.selectTrashList(
                reqVO.getSpaceId(), reqVO.getName(), manager ? null : String.valueOf(userId));
    }

    @Override
    public DriveEntryContent getContentInfo(Long id, Long userId) {
        DriveEntryDO entry = validEntry(id);
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.VIEWER);
        if (!DriveEntryTypeEnum.isFile(entry.getType())) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        if (DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        return new DriveEntryContent(entry, fileApi.getFileContentLength(entry.getFileId()));
    }

    @Override
    public InputStream getContentStream(Long id, Long userId, long offset) {
        DriveEntryContent content = getContentInfo(id, userId);
        InputStream stream = fileApi.getFileContentStream(content.getEntry().getFileId(), offset);
        if (stream == null) {
            throw exception(ENTRY_FILE_CONTENT_MISSING);
        }
        return stream;
    }

    @Override
    public Map<Long, DriveEntryDO> getEntryMap(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Map.of();
        }
        return CollectionUtils.convertMap(entryMapper.selectListByIds(ids), DriveEntryDO::getId);
    }

    /** 校验回收站操作权限：空间管理者可管理全部内容，其余用户只能处理自己删除的节点 */
    private void validateTrashOperator(DriveEntryDO entry, Long userId) {
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.VIEWER);
        DrivePermissionRoleEnum spaceRole =
                permissionService.getEffectiveRole(
                        entry.getSpaceId(), DriveEntryDO.PARENT_ID_ROOT, userId);
        if (spaceRole != null && spaceRole.satisfies(DrivePermissionRoleEnum.MANAGER)) {
            return;
        }
        if (!ObjUtil.equal(entry.getTrashedBy(), String.valueOf(userId))) {
            throw exception(PERMISSION_DENIED);
        }
    }

    private DriveEntryDO validEntry(Long id) {
        DriveEntryDO entry = id == null ? null : entryMapper.selectById(id);
        if (entry == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        return entry;
    }

    /** 校验父节点：0 为空间根目录，其余必须是同空间的正常目录；受管目录不接收普通上传/新建 */
    private void validParent(Long spaceId, Long parentId) {
        if (ObjUtil.equal(parentId, DriveEntryDO.PARENT_ID_ROOT)) {
            return;
        }
        DriveEntryDO parent = entryMapper.selectById(parentId);
        if (parent == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        if (ObjUtil.notEqual(parent.getSpaceId(), spaceId)) {
            throw exception(ENTRY_SPACE_NOT_MATCH);
        }
        if (!DriveEntryTypeEnum.isFolder(parent.getType())) {
            throw exception(ENTRY_PARENT_NOT_FOLDER);
        }
        if (DriveTrashStateEnum.isTrashed(parent.getTrashState())) {
            throw exception(ENTRY_ALREADY_TRASHED);
        }
        // 受管目录内的内容只能由业务文件规则建立，普通入口（上传、新建、移动、复制目标）一律拒绝
        if (Boolean.TRUE.equals(parent.getManagedBiz())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
    }

    /** 受管节点只能由业务文件规则调整：普通重命名、移动、回收站、分享与继承设置入口拒绝 */
    private void validateNotManaged(DriveEntryDO entry) {
        if (Boolean.TRUE.equals(entry.getManagedBiz())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
    }

    private Long resolveRestoreParentId(DriveEntryDO entry) {
        Long originParentId = entry.getOriginParentId();
        if (originParentId == null || ObjUtil.equal(originParentId, DriveEntryDO.PARENT_ID_ROOT)) {
            return DriveEntryDO.PARENT_ID_ROOT;
        }
        DriveEntryDO parent = entryMapper.selectById(originParentId);
        if (parent == null
                || !DriveEntryTypeEnum.isFolder(parent.getType())
                || DriveTrashStateEnum.isTrashed(parent.getTrashState())) {
            return DriveEntryDO.PARENT_ID_ROOT;
        }
        return originParentId;
    }

    /** 更新节点并校验乐观锁，返回 0 说明结构已被并发修改 */
    private void updateEntryChecked(DriveEntryDO current, DriveEntryDO updateObj) {
        updateObj.setLockVersion(current.getLockVersion());
        if (entryMapper.updateById(updateObj) == 0) {
            throw exception(ENTRY_UPDATE_CONFLICT);
        }
    }

    private String validateName(String name) {
        String trimmed = StrUtil.trim(name);
        if (StrUtil.isEmpty(trimmed)
                || StrUtil.containsAny(trimmed, "/", "\\")
                || StrUtil.containsAny(trimmed, "\r", "\n", "\t")
                || StrUtil.equals(trimmed, ".")
                || StrUtil.equals(trimmed, "..")) {
            throw exception(ENTRY_NAME_INVALID);
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw exception(ENTRY_NAME_INVALID);
        }
        return trimmed;
    }

    /** 生成同目录内不冲突的名称，冲突时在扩展名前追加序号 */
    private String buildUniqueName(Long spaceId, Long parentId, String name, Long excludeEntryId) {
        DriveEntryDO exists = entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, name);
        if (exists == null || ObjUtil.equal(exists.getId(), excludeEntryId)) {
            return name;
        }
        String mainName = StrUtil.subBefore(name, ".", true);
        String extension = StrUtil.subAfter(name, ".", true);
        boolean hasExtension =
                StrUtil.isNotEmpty(mainName)
                        && StrUtil.isNotEmpty(extension)
                        && !StrUtil.equals(mainName, name);
        for (int index = 2; index < MAX_RENAME_ATTEMPT; index++) {
            String candidate =
                    hasExtension
                            ? StrUtil.format("{} ({})", mainName, index) + "." + extension
                            : StrUtil.format("{} ({})", name, index);
            if (candidate.length() > MAX_NAME_LENGTH) {
                throw exception(ENTRY_NAME_INVALID);
            }
            DriveEntryDO conflict =
                    entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, candidate);
            if (conflict == null || ObjUtil.equal(conflict.getId(), excludeEntryId)) {
                return candidate;
            }
        }
        throw exception(ENTRY_NAME_DUPLICATE);
    }

    private String buildStorageDirectory(Long spaceId) {
        return StrUtil.format("drive/{}", spaceId);
    }

    private Map<Long, String> resolveEntryNames(List<DriveEntryChainRow> rows) {
        if (CollUtil.isEmpty(rows)) {
            return Map.of();
        }
        List<Long> ids = CollectionUtils.convertList(rows, DriveEntryChainRow::getId);
        List<DriveEntryDO> entries = entryMapper.selectListByIds(ids);
        Map<Long, String> names = new LinkedHashMap<>(entries.size());
        for (DriveEntryDO entry : entries) {
            names.put(entry.getId(), entry.getName());
        }
        return names;
    }
}
