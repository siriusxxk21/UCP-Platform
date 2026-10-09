package com.lingan.ucp.module.drive.service.bizfile;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.lingan.ucp.module.infra.service.file.FileService;
import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizEntryDTO;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizFileContent;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizSpaceDTO;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizTemporaryContent;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.entry.DriveEntryTypeEnum;
import com.lingan.ucp.module.drive.enums.entry.DriveTrashStateEnum;
import com.lingan.ucp.module.drive.enums.space.DriveSpaceTypeEnum;
import com.lingan.ucp.module.drive.service.storage.DriveStorageService;
import com.lingan.ucp.module.infra.api.file.FileApi;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.framework.file.core.utils.FileTypeUtils;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 业务文件融合领域接口实现
 *
 * <p>供无代码运行时在业务保存事务内调用：不校验普通网盘空间 ACL，业务授权链由调用方完成； 受管节点只允许经本接口建立与调整，普通网盘操作入口在各自服务中拒绝受管节点。
 *
 * <p>本类所有写方法默认运行在调用方的业务事务内（除 ensureBusinessSpace 自带事务外），
 * 任何失败抛出异常使业务保存整体回滚；对象存储内容写入发生在上传会话阶段，这里只处理逻辑节点。
 */
@Slf4j
@Service
public class DriveBizFileApiImpl implements DriveBizFileApi {

    /** 单文件上限与普通网盘一致，业务附件在此基础上由无代码侧按对象约束进一步收紧 */
    private static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;

    /** 名称长度上限，与 drive_entry.name 字段一致 */
    private static final int MAX_NAME_LENGTH = 255;

    /** 搜索返回条数上限 */
    private static final int MAX_SEARCH_LIMIT = 200;

    @Resource private DriveEntryMapper entryMapper;
    @Resource private DriveSpaceMapper spaceMapper;
    @Resource private FileApi fileApi;
    @Resource private FileService fileService;
    @Resource private DriveStorageService storageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long ensureBusinessSpace(String name, Long operatorId) {
        return ensureBusinessSpace(null, name, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long ensureBusinessSpace(Long spaceId, String legacyName, Long operatorId) {
        if (spaceId != null) {
            return requireWritableBizSpace(spaceId).getId();
        }
        String trimmed = StrUtil.trim(legacyName);
        if (StrUtil.isEmpty(trimmed)) {
            throw exception(ENTRY_NAME_INVALID);
        }
        DriveSpaceDO exists = spaceMapper.selectBizByName(trimmed);
        if (exists != null) {
            return requireWritableBizSpace(exists.getId()).getId();
        }
        // 旧配置只保存了名称时允许解析既有空间，但不再静默创建；治理端须先建立空间并回填稳定编号。
        throw exception(SPACE_NOT_EXISTS);
    }

    @Override
    public List<DriveBizSpaceDTO> listBusinessSpaces() {
        return spaceMapper.selectBizList().stream()
                .map(
                        space ->
                                new DriveBizSpaceDTO(
                                        space.getId(), space.getName(), space.getStatus()))
                .toList();
    }

    @Override
    public Long uploadProtectedContent(
            String fileName, String contentType, long size, InputStream content) {
        if (size <= 0 || size > MAX_FILE_SIZE) {
            throw exception(ENTRY_FILE_TOO_LARGE);
        }
        String rawName = validateName(fileName);
        // Multipart Content-Type 来自客户端，不作为后续内联预览与下载响应的信任依据。
        String detectedType = FileTypeUtils.getMineType(rawName);
        // 与网盘上传一致：内容写入网盘当前选定的存储源，登记受保护标记，通用文件接口拒绝读取
        Long storageConfigId = storageService.getSelectedConfigId();
        FileDO file;
        if (storageConfigId == null) {
            file = fileApi.createProtectedFile(content, size, rawName, null, detectedType);
        } else {
            file =
                    fileApi.createProtectedFile(
                            storageConfigId, content, size, rawName, null, detectedType);
        }
        return file.getId();
    }

    @Override
    public Long ensureDirectory(Long spaceId, List<String> path, Long operatorId) {
        DriveSpaceDO space = requireWritableBizSpace(spaceId);
        if (CollUtil.isEmpty(path)) {
            throw exception(ENTRY_NAME_INVALID);
        }
        Long parentId = DriveEntryDO.PARENT_ID_ROOT;
        DriveEntryDO current = null;
        for (String rawName : path) {
            String name = validateName(rawName);
            current = entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, name);
            if (current == null) {
                current = insertManagedFolder(spaceId, parentId, name);
            } else if (!Boolean.TRUE.equals(current.getManagedBiz())) {
                // 同名位置已被文件夹区的普通目录占用：附件归档不能落进普通目录，提示改名
                throw exception(ENTRY_ORDINARY_NAME_TAKEN, name);
            }
            parentId = current.getId();
        }
        return current.getId();
    }

    @Override
    public Long createManagedDirectory(
            Long spaceId, Long parentEntryId, String rawName, Long operatorId) {
        requireWritableBizSpace(spaceId);
        if (ObjUtil.notEqual(parentEntryId, DriveEntryDO.PARENT_ID_ROOT)) {
            validManagedFolder(spaceId, parentEntryId);
        }
        String name = validateName(rawName);
        for (int attempt = 0; attempt < 1000; attempt++) {
            // 同一父目录下其他身份可能连续抢占候选名；每次均从当前快照重算，不复用冲突节点。
            String candidate = uniqueName(spaceId, parentEntryId, name);
            DriveEntryDO folder = managedFolder(spaceId, parentEntryId, candidate);
            if (entryMapper.insertManagedFolderIfAbsent(folder, operatorId) == 1) {
                return folder.getId();
            }
        }
        throw exception(ENTRY_NAME_DUPLICATE);
    }

    @Override
    public DriveBizEntryDTO bindFile(
            Long spaceId, Long parentEntryId, Long fileId, String sourceEntry, Long operatorId) {
        requireWritableBizSpace(spaceId);
        DriveEntryDO parent = validManagedFolder(spaceId, parentEntryId);
        if (fileId == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        FileDO file = validProtectedFile(fileId);
        // 同一目录下相同内容的绑定幂等：业务保存重试或重复提交直接复用既有节点
        List<DriveEntryDO> managed = entryMapper.selectManagedListByFileId(fileId);
        for (DriveEntryDO entry : managed) {
            if (ObjUtil.equal(entry.getParentId(), parentEntryId)
                    && ObjUtil.equal(entry.getSpaceId(), spaceId)) {
                return toDTO(entry);
            }
        }
        // 展示名、大小、类型以文件底座记录为准，调用方不另行提交，避免与实际内容不一致
        String name = validateName(StrUtil.blankToDefault(file.getName(), "未命名文件"));
        DriveEntryDO entry =
                DriveEntryDO.builder()
                        .spaceId(spaceId)
                        .parentId(parentEntryId)
                        .name(uniqueName(spaceId, parentEntryId, name))
                        .type(DriveEntryTypeEnum.FILE.getCode())
                        .fileId(fileId)
                        .size(file.getSize() != null ? file.getSize() : 0L)
                        .mimeType(file.getType())
                        .managedBiz(Boolean.TRUE)
                        .inheritParent(Boolean.TRUE)
                        .trashState(DriveTrashStateEnum.NORMAL.getCode())
                        .lockVersion(0)
                        .build();
        entryMapper.insert(entry);
        spaceMapper.updateUsedBytes(spaceId, file.getSize() != null ? file.getSize() : 0L);
        return toDTO(entry);
    }

    @Override
    public void unbindFile(Long entryId, Long operatorId) {
        DriveEntryDO entry = validEntry(entryId);
        if (!Boolean.TRUE.equals(entry.getManagedBiz())
                || !DriveEntryTypeEnum.isFile(entry.getType())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        // 业务移除直接删除逻辑节点并回收容量，不进入普通回收站；内容是否物理删除由无代码侧按保留引用判断
        entryMapper.deleteById(entryId);
        long size = entry.getSize() != null ? entry.getSize() : 0L;
        if (size > 0) {
            spaceMapper.updateUsedBytes(entry.getSpaceId(), -size);
        }
    }

    @Override
    public void removeDirectory(Long entryId, Long operatorId) {
        DriveEntryDO entry = validEntry(entryId);
        if (!Boolean.TRUE.equals(entry.getManagedBiz())
                || !DriveEntryTypeEnum.isFolder(entry.getType())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        requireBizSpace(entry.getSpaceId());
        removeDirectoryTree(entry);
    }

    /** 自底向上删除只含目录的受管子树；发现文件节点立即拒绝，由调用方先解绑文件 */
    private void removeDirectoryTree(DriveEntryDO folder) {
        List<DriveEntryDO> children =
                entryMapper.selectListByParent(folder.getSpaceId(), folder.getId());
        for (DriveEntryDO child : children) {
            if (DriveEntryTypeEnum.isFile(child.getType())) {
                throw exception(ENTRY_MANAGED_FORBIDDEN);
            }
            removeDirectoryTree(child);
        }
        entryMapper.deleteById(folder.getId());
    }

    @Override
    public void moveDirectory(Long entryId, List<String> newPath, Long operatorId) {
        DriveEntryDO entry = validEntry(entryId);
        if (!Boolean.TRUE.equals(entry.getManagedBiz())
                || !DriveEntryTypeEnum.isFolder(entry.getType())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        if (CollUtil.isEmpty(newPath)) {
            throw exception(ENTRY_NAME_INVALID);
        }
        Long spaceId = entry.getSpaceId();
        requireWritableBizSpace(spaceId);
        // 新层级中除末层目录名外逐层确保存在（幂等），末层作为该记录目录的新显示名
        List<String> parentPath = newPath.subList(0, newPath.size() - 1);
        Long newParentId = DriveEntryDO.PARENT_ID_ROOT;
        for (String rawName : parentPath) {
            String name = validateName(rawName);
            DriveEntryDO dir =
                    entryMapper.selectBySpaceAndParentAndName(spaceId, newParentId, name);
            if (dir == null) {
                dir = insertManagedFolder(spaceId, newParentId, name);
            }
            newParentId = dir.getId();
        }
        String newName =
                uniqueName(
                        spaceId,
                        newParentId,
                        validateName(newPath.get(newPath.size() - 1)),
                        entryId);
        boolean parentChanged = ObjUtil.notEqual(newParentId, entry.getParentId());
        boolean nameChanged = ObjUtil.notEqual(newName, entry.getName());
        if (!parentChanged && !nameChanged) {
            return;
        }
        DriveEntryDO updateObj =
                DriveEntryDO.builder()
                        .id(entryId)
                        .parentId(newParentId)
                        .name(newName)
                        .lockVersion(entry.getLockVersion())
                        .build();
        if (entryMapper.updateById(updateObj) == 0) {
            throw exception(ENTRY_UPDATE_CONFLICT);
        }
    }

    @Override
    public boolean hasManagedEntry(Long fileId) {
        return !entryMapper.selectManagedListByFileId(fileId).isEmpty();
    }

    @Override
    public DriveBizFileContent contentInfo(Long entryId) {
        DriveEntryDO entry = validManagedFile(entryId);
        return new DriveBizFileContent(
                entry.getId(),
                entry.getSpaceId(),
                entry.getFileId(),
                entry.getName(),
                entry.getSize() != null ? entry.getSize() : 0L,
                entry.getMimeType(),
                fileApi.getFileContentLength(entry.getFileId()));
    }

    @Override
    public InputStream openContent(Long entryId, long offset) {
        DriveEntryDO entry = validManagedFile(entryId);
        InputStream stream = fileApi.getFileContentStream(entry.getFileId(), offset);
        if (stream == null) {
            throw exception(ENTRY_FILE_CONTENT_MISSING);
        }
        return stream;
    }

    @Override
    public DriveBizTemporaryContent temporaryContentInfo(Long fileId) {
        FileDO file = validProtectedFile(fileId);
        return new DriveBizTemporaryContent(
                file.getId(),
                file.getName(),
                file.getSize() != null ? file.getSize() : 0L,
                file.getType(),
                fileApi.getFileContentLength(file.getId()));
    }

    @Override
    public InputStream openTemporaryContent(Long fileId, long offset) {
        FileDO file = validProtectedFile(fileId);
        InputStream stream = fileApi.getFileContentStream(file.getId(), offset);
        if (stream == null) {
            throw exception(ENTRY_FILE_CONTENT_MISSING);
        }
        return stream;
    }

    @Override
    public void deleteTemporaryContent(Long fileId) {
        if (fileId == null) {
            return;
        }
        // 用列表查询容忍内容行已不存在：清理重试按删除完成处理，保持幂等
        List<FileDO> files = fileService.getFiles(List.of(fileId));
        if (files.isEmpty()) {
            return;
        }
        FileDO file = files.get(0);
        if (!Boolean.TRUE.equals(file.getProtectedFlag())) {
            throw exception(BIZ_FILE_NOT_EXISTS);
        }
        if (hasManagedEntry(fileId)) {
            // 仍有受管文件节点引用：调用方必须先解绑，避免删除有效业务内容
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        fileApi.deleteFile(fileId);
    }

    @Override
    public List<DriveBizEntryDTO> listManagedChildren(Long spaceId, Long parentEntryId) {
        requireBizSpace(spaceId);
        if (ObjUtil.notEqual(parentEntryId, DriveEntryDO.PARENT_ID_ROOT)) {
            // 子目录必须是同空间的受管目录，防止借业务浏览原语读取普通空间内容
            DriveEntryDO parent = validEntry(parentEntryId);
            if (ObjUtil.notEqual(parent.getSpaceId(), spaceId)
                    || !Boolean.TRUE.equals(parent.getManagedBiz())) {
                throw exception(ENTRY_MANAGED_FORBIDDEN);
            }
        }
        List<DriveEntryDO> children = entryMapper.selectListByParent(spaceId, parentEntryId);
        children.sort(
                Comparator.comparing(
                                (DriveEntryDO node) -> !DriveEntryTypeEnum.isFolder(node.getType()))
                        .thenComparing(DriveEntryDO::getName));
        List<DriveBizEntryDTO> result = new ArrayList<>(children.size());
        for (DriveEntryDO node : children) {
            result.add(toDTO(node));
        }
        return result;
    }

    @Override
    public List<DriveBizEntryDTO> searchManagedEntries(Long spaceId, String nameLike, int limit) {
        requireBizSpace(spaceId);
        String keyword = StrUtil.trim(nameLike);
        if (StrUtil.isEmpty(keyword)) {
            return List.of();
        }
        int actual = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        List<DriveEntryDO> entries =
                entryMapper.selectManagedListByNameLike(spaceId, keyword, actual);
        List<DriveBizEntryDTO> result = new ArrayList<>(entries.size());
        for (DriveEntryDO node : entries) {
            result.add(toDTO(node));
        }
        return result;
    }

    private DriveBizEntryDTO toDTO(DriveEntryDO node) {
        return DriveBizEntryDTO.builder()
                .id(node.getId())
                .parentId(node.getParentId())
                .spaceId(node.getSpaceId())
                .name(node.getName())
                .folder(DriveEntryTypeEnum.isFolder(node.getType()))
                .fileId(node.getFileId())
                .size(node.getSize() != null ? node.getSize() : 0L)
                .mimeType(node.getMimeType())
                .creator(node.getCreator())
                .createTime(node.getCreateTime())
                .build();
    }

    /** 校验空间存在且为业务空间；业务文件的目录与节点只能落在业务空间 */
    private DriveSpaceDO requireBizSpace(Long spaceId) {
        DriveSpaceDO space = spaceMapper.selectById(spaceId);
        if (space == null) {
            throw exception(SPACE_NOT_EXISTS);
        }
        if (!DriveSpaceTypeEnum.isBiz(space.getType())) {
            throw exception(SPACE_BIZ_FORBIDDEN);
        }
        return space;
    }

    /** 只有新建目录和绑定使用写校验；已停用空间的既有内容仍可按业务授权读取。 */
    private DriveSpaceDO requireWritableBizSpace(Long spaceId) {
        DriveSpaceDO space = requireBizSpace(spaceId);
        if (!com.lingan.ucp.framework.common.enums.CommonStatusEnum.ENABLE
                .getStatus()
                .equals(space.getStatus())) {
            throw exception(SPACE_DISABLED);
        }
        return space;
    }

    /** 建立受管目录：并发重名由唯一索引兜底后重查复用 */
    private DriveEntryDO insertManagedFolder(Long spaceId, Long parentId, String name) {
        DriveEntryDO entry = managedFolder(spaceId, parentId, name);
        try {
            entryMapper.insert(entry);
            return entry;
        } catch (DuplicateKeyException ex) {
            DriveEntryDO exists =
                    entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, name);
            if (exists != null) {
                return exists;
            }
            throw ex;
        }
    }

    private DriveEntryDO managedFolder(Long spaceId, Long parentId, String name) {
        return DriveEntryDO.builder()
                .spaceId(spaceId)
                .parentId(parentId)
                .name(name)
                .type(DriveEntryTypeEnum.FOLDER.getCode())
                .size(0L)
                .managedBiz(Boolean.TRUE)
                .inheritParent(Boolean.TRUE)
                .trashState(DriveTrashStateEnum.NORMAL.getCode())
                .lockVersion(0)
                .build();
    }

    private DriveEntryDO validManagedFolder(Long spaceId, Long entryId) {
        DriveEntryDO entry = validEntry(entryId);
        if (ObjUtil.notEqual(entry.getSpaceId(), spaceId)
                || !DriveEntryTypeEnum.isFolder(entry.getType())
                || !Boolean.TRUE.equals(entry.getManagedBiz())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        return entry;
    }

    /** 受管文件节点：业务空间内的受管文件；内容信息与内容流读取共用同一身份校验 */
    private DriveEntryDO validManagedFile(Long entryId) {
        DriveEntryDO entry = validEntry(entryId);
        if (!Boolean.TRUE.equals(entry.getManagedBiz())
                || !DriveEntryTypeEnum.isFile(entry.getType())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        requireBizSpace(entry.getSpaceId());
        return entry;
    }

    private DriveEntryDO validEntry(Long id) {
        DriveEntryDO entry = id == null ? null : entryMapper.selectById(id);
        if (entry == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        return entry;
    }

    /** 临时上传内容：只接受带受保护标记的文件；普通附件不构成业务上传内容，按不存在处理 */
    private FileDO validProtectedFile(Long fileId) {
        FileDO file = fileId == null ? null : fileService.getFile(fileId);
        if (file == null || !Boolean.TRUE.equals(file.getProtectedFlag())) {
            throw exception(BIZ_FILE_NOT_EXISTS);
        }
        return file;
    }

    /** 生成同目录内不冲突的名称，冲突时在扩展名前追加序号；目录与文件共用 */
    private String uniqueName(Long spaceId, Long parentId, String name) {
        return uniqueName(spaceId, parentId, name, null);
    }

    /** 移动/重命名时排除当前节点，避免将自身误判为重名并在每次保存间反复加序号。 */
    private String uniqueName(Long spaceId, Long parentId, String name, Long currentEntryId) {
        DriveEntryDO exists = entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, name);
        if (exists == null || ObjUtil.equal(exists.getId(), currentEntryId)) {
            return name;
        }
        String mainName = StrUtil.subBefore(name, ".", true);
        String extension = StrUtil.subAfter(name, ".", true);
        boolean hasExtension =
                StrUtil.isNotEmpty(mainName)
                        && StrUtil.isNotEmpty(extension)
                        && !StrUtil.equals(mainName, name);
        for (int index = 2; index < 1000; index++) {
            String candidate =
                    hasExtension
                            ? StrUtil.format("{} ({})", mainName, index) + "." + extension
                            : StrUtil.format("{} ({})", name, index);
            if (candidate.length() > MAX_NAME_LENGTH) {
                throw exception(ENTRY_NAME_INVALID);
            }
            DriveEntryDO conflict =
                    entryMapper.selectBySpaceAndParentAndName(spaceId, parentId, candidate);
            if (conflict == null || ObjUtil.equal(conflict.getId(), currentEntryId)) {
                return candidate;
            }
        }
        throw exception(ENTRY_NAME_DUPLICATE);
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
}
