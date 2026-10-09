package com.richuang.os.module.drive.service.folder;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.module.drive.api.folder.DriveFolderApi;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderContent;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderInfo;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderNode;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderScope;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryFolderCreateReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRenameReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRespVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryChainRow;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryOriginRow;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.enums.entry.DriveEntryTypeEnum;
import com.richuang.os.module.drive.enums.entry.DriveTrashStateEnum;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.service.entry.DriveEntryService;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.drive.service.permission.DriveScopes;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记录文件夹领域接口实现
 *
 * <p>本类不自己实现上传、改名、移动、复制、删除与同名处理：限定子树的每个操作都是「挂上限定子树 → 转调现有的 {@link DriveEntryService}」，
 * 权限判定仍只发生在 {@link DrivePermissionService#getEffectiveRole}。它自己只做权限层管不到的几件事：确认根仍可用、根自身不可操作、 子树内搜索与最近删除、恢复不落到子树之外、
 * 给新节点打来源标记、改删之前查来源。除 {@link #ensureChild} 外，本类直接读写的表只有 drive_entry_origin。
 *
 * <p>来源检查的顺序不能反：先在限定子树<b>之外</b>算本人网盘角色，再决定查不查来源，最后才挂上限定子树转调。在限定子树之内算「本人角色」
 * 会把凭记录换来的临时角色当成本人角色，来源规则整个失效。
 */
@Slf4j
@Service
public class DriveFolderApiImpl implements DriveFolderApi {

    /** 名称长度上限，与 drive_entry.name 字段一致 */
    private static final int MAX_NAME_LENGTH = 255;

    /** 搜索返回条数上限，与 DriveEntryServiceImpl 同值 */
    private static final int MAX_SEARCH_LIMIT = 200;

    /** 搜索默认返回条数，与 DriveEntryServiceImpl 同值 */
    private static final int DEFAULT_SEARCH_LIMIT = 50;

    /** 自动加序号时尝试的最大次数 */
    private static final int MAX_RENAME_ATTEMPT = 1000;

    /** 来源键长度上限，与 drive_entry_origin.origin_key 字段一致 */
    private static final int MAX_ORIGIN_KEY_LENGTH = 700;

    @Resource private DriveEntryMapper entryMapper;
    @Resource private DriveSpaceMapper spaceMapper;
    @Resource private DriveEntryService entryService;
    @Resource private DrivePermissionService permissionService;

    /** 一次限定子树调用的已校验上下文 */
    private record Root(DriveScopes.Scope scope, String originKey) {
        Long spaceId() {
            return scope.spaceId();
        }

        Long id() {
            return scope.rootEntryId();
        }

        boolean editor() {
            return scope.role() == DrivePermissionRoleEnum.EDITOR;
        }
    }

    // ========== 查询：不鉴权 ==========

    @Override
    public DriveFolderInfo describe(Long entryId) {
        DriveEntryDO entry = entryId == null ? null : entryMapper.selectById(entryId);
        if (entry == null) {
            return null;
        }
        DriveSpaceDO space = spaceMapper.selectById(entry.getSpaceId());
        return new DriveFolderInfo(
                entry.getId(),
                entry.getSpaceId(),
                space == null ? null : space.getType(),
                space == null ? null : space.getName(),
                space != null && CommonStatusEnum.isEnable(space.getStatus()),
                entry.getParentId(),
                entry.getName(),
                DriveEntryTypeEnum.isFolder(entry.getType()),
                Boolean.TRUE.equals(entry.getManagedBiz()),
                DriveTrashStateEnum.isTrashed(entry.getTrashState()));
    }

    @Override
    public String roleOf(Long spaceId, Long entryId, Long userId) {
        DrivePermissionRoleEnum role = permissionService.getEffectiveRole(spaceId, entryId, userId);
        return role == null ? null : role.getCode();
    }

    @Override
    public String displayPath(Long entryId) {
        DriveEntryDO entry = entryId == null ? null : entryMapper.selectById(entryId);
        if (entry == null) {
            return null;
        }
        DriveSpaceDO space = spaceMapper.selectById(entry.getSpaceId());
        List<String> names = new ArrayList<>();
        names.add(space == null ? StrUtil.EMPTY : space.getName());
        names.addAll(namesTopDown(entryMapper.selectPathChain(entry.getSpaceId(), entryId)));
        return String.join(" / ", names);
    }

    // ========== 系统建目录：不鉴权 ==========

    @Override
    public Long ensureChild(Long parentEntryId, String name, Long operatorId, String originKey) {
        requireOriginKey(originKey);
        DriveEntryDO parent = parentEntryId == null ? null : entryMapper.selectById(parentEntryId);
        if (parent == null
                || !DriveEntryTypeEnum.isFolder(parent.getType())
                || Boolean.TRUE.equals(parent.getManagedBiz())
                || DriveTrashStateEnum.isTrashed(parent.getTrashState())) {
            throw exception(ENTRY_SCOPE_UNAVAILABLE);
        }
        DriveSpaceDO space = spaceMapper.selectById(parent.getSpaceId());
        if (space == null || !CommonStatusEnum.isEnable(space.getStatus())) {
            throw exception(ENTRY_SCOPE_UNAVAILABLE);
        }
        String base = validateName(name);
        for (int attempt = 0; attempt < MAX_RENAME_ATTEMPT; attempt++) {
            // 同名时加「 (2)」起的序号；候选名超长时不再继续
            String candidate = attempt == 0 ? base : StrUtil.format("{} ({})", base, attempt + 1);
            if (candidate.length() > MAX_NAME_LENGTH) {
                throw exception(ENTRY_NAME_INVALID);
            }
            DriveEntryDO folder =
                    DriveEntryDO.builder()
                            .spaceId(parent.getSpaceId())
                            .parentId(parent.getId())
                            .name(candidate)
                            .type(DriveEntryTypeEnum.FOLDER.getCode())
                            .size(0L)
                            .inheritParent(Boolean.TRUE)
                            .trashState(DriveTrashStateEnum.NORMAL.getCode())
                            .managedBiz(Boolean.FALSE)
                            .lockVersion(0)
                            .build();
            // 是否建成按受影响行数判定：同名冲突时返回 0，不抛异常、不污染调用方的事务
            if (entryMapper.insertFolderIfAbsent(folder, operatorId) == 1) {
                entryMapper.insertOrigins(List.of(folder.getId()), originKey, operatorId);
                return folder.getId();
            }
        }
        throw exception(ENTRY_NAME_DUPLICATE);
    }

    // ========== 限定子树的操作 ==========

    @Override
    public List<DriveFolderNode> list(DriveFolderScope scope, Long userId, Long parentId) {
        Root root = requireRoot(scope);
        Long parent = real(root, parentId);
        DriveEntryListReqVO reqVO = new DriveEntryListReqVO();
        reqVO.setSpaceId(root.spaceId());
        reqVO.setParentId(parent);
        List<DriveEntryDO> entries =
                DriveScopes.call(root.scope(), () -> entryService.getEntryList(reqVO, userId));
        return nodes(root, entries, editorByOwnRole(root, parent, userId));
    }

    @Override
    public DriveFolderNode get(DriveFolderScope scope, Long userId, Long id) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        DriveEntryRespVO detail =
                DriveScopes.call(root.scope(), () -> entryService.getEntryDetail(id, userId));
        boolean modifiable =
                modifiable(
                        root,
                        editorByOwnRole(root, id, userId),
                        originKeys(List.of(id)).get(id));
        return new DriveFolderNode(
                detail.getId(),
                detail.getSpaceId(),
                outward(root, detail.getParentId()),
                detail.getName(),
                detail.getType(),
                detail.getSize(),
                detail.getMimeType(),
                detail.getRole(),
                detail.getCreator(),
                detail.getCreateTime(),
                detail.getUpdateTime(),
                detail.getTrashedAt(),
                detail.getTrashedBy(),
                modifiable);
    }

    @Override
    public List<String> path(DriveFolderScope scope, Long userId, Long id) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        return DriveScopes.call(
                root.scope(),
                () -> {
                    DriveEntryDO entry = entryService.getEntry(id, userId);
                    // 链自节点向上返回；截到根为止（不含根），再去掉节点自身，剩下的就是根以下的各级目录
                    List<DriveEntryChainRow> between = new ArrayList<>();
                    for (DriveEntryChainRow row :
                            entryMapper.selectPathChain(entry.getSpaceId(), id)) {
                        if (ObjUtil.equal(row.getId(), root.id())) {
                            break;
                        }
                        if (ObjUtil.notEqual(row.getId(), id)) {
                            between.add(row);
                        }
                    }
                    return namesTopDown(between);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFolder(DriveFolderScope scope, Long userId, Long parentId, String name) {
        Root root = requireRoot(scope);
        DriveEntryFolderCreateReqVO reqVO = new DriveEntryFolderCreateReqVO();
        reqVO.setSpaceId(root.spaceId());
        reqVO.setParentId(real(root, parentId));
        reqVO.setName(name);
        Long created =
                DriveScopes.call(root.scope(), () -> entryService.createFolder(reqVO, userId));
        // 标记与节点同成同败：打标记失败时事务回滚，目录不留下
        entryMapper.insertOrigins(List.of(created), root.originKey(), userId);
        return created;
    }

    @Override
    public DriveFolderNode upload(
            DriveFolderScope scope,
            Long userId,
            Long parentId,
            String fileName,
            String contentType,
            long size,
            InputStream content) {
        Root root = requireRoot(scope);
        Long parent = real(root, parentId);
        // 不包事务：传内容期间不能占着数据库事务
        DriveEntryDO entry =
                DriveScopes.call(
                        root.scope(),
                        () ->
                                entryService.uploadFile(
                                        root.spaceId(),
                                        parent,
                                        fileName,
                                        contentType,
                                        size,
                                        content,
                                        userId));
        // 打标记抛异常时原样抛出：文件已在、没有标记，对只凭记录的人只读；调用方看到的是上传失败
        entryMapper.insertOrigins(List.of(entry.getId()), root.originKey(), userId);
        return node(
                root,
                entry,
                modifiable(root, editorByOwnRole(root, entry.getId(), userId), root.originKey()));
    }

    @Override
    public void rename(DriveFolderScope scope, Long userId, Long id, String name) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        if (restricted(root, id, userId)) {
            requireOwn(root, id);
        }
        DriveEntryRenameReqVO reqVO = new DriveEntryRenameReqVO();
        reqVO.setId(id);
        reqVO.setName(name);
        DriveScopes.run(root.scope(), () -> entryService.renameEntry(reqVO, userId));
    }

    @Override
    public void move(DriveFolderScope scope, Long userId, Long id, Long targetParentId) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        if (restricted(root, id, userId)) {
            requireOwnSubtree(root, id);
        }
        // 移动的目标文件夹不做来源检查：子树内任何目录都可以放
        DriveEntryMoveReqVO reqVO = new DriveEntryMoveReqVO();
        reqVO.setId(id);
        reqVO.setTargetParentId(real(root, targetParentId));
        DriveScopes.run(root.scope(), () -> entryService.moveEntry(reqVO, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copy(DriveFolderScope scope, Long userId, Long id, Long targetParentId) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        DriveEntryCopyReqVO reqVO = new DriveEntryCopyReqVO();
        reqVO.setId(id);
        reqVO.setTargetSpaceId(root.spaceId());
        reqVO.setTargetParentId(real(root, targetParentId));
        Long created = DriveScopes.call(root.scope(), () -> entryService.copyEntry(reqVO, userId));
        // 复制出来的整棵新子树都算本次来源放进去的，不管被复制的是谁的
        List<Long> ids =
                entryMapper.selectSubtree(root.spaceId(), created).stream()
                        .map(DriveEntryDO::getId)
                        .toList();
        entryMapper.insertOrigins(ids, root.originKey(), userId);
        return created;
    }

    @Override
    public void trash(DriveFolderScope scope, Long userId, Collection<Long> ids) {
        Root root = requireRoot(scope);
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        Set<Long> targets = new LinkedHashSet<>(ids);
        for (Long id : targets) {
            requireNotRoot(root, id);
        }
        // 任何一个不过整批不做：先逐个查完，再一次转调
        for (Long id : targets) {
            if (restricted(root, id, userId)) {
                requireOwnSubtree(root, id);
            }
        }
        DriveScopes.run(root.scope(), () -> entryService.trashEntryList(targets, userId));
    }

    @Override
    public List<DriveFolderNode> search(
            DriveFolderScope scope, Long userId, String name, Integer limit) {
        Root root = requireRoot(scope);
        if (StrUtil.isBlank(name)) {
            return List.of();
        }
        int size =
                limit != null && limit > 0
                        ? Math.min(limit, MAX_SEARCH_LIMIT)
                        : DEFAULT_SEARCH_LIMIT;
        List<DriveEntryDO> entries =
                DriveScopes.call(
                        root.scope(),
                        () -> {
                            permissionService.validatePermission(
                                    root.spaceId(),
                                    root.id(),
                                    userId,
                                    DrivePermissionRoleEnum.VIEWER);
                            return entryMapper.selectSubtreeByNameLike(
                                    root.spaceId(), root.id(), name, size);
                        });
        return nodes(root, entries, editorByOwnRole(root, root.id(), userId));
    }

    @Override
    public DriveFolderContent contentInfo(DriveFolderScope scope, Long userId, Long id) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        DriveEntryService.DriveEntryContent content =
                DriveScopes.call(root.scope(), () -> entryService.getContentInfo(id, userId));
        DriveEntryDO entry = content.getEntry();
        return new DriveFolderContent(
                entry.getId(),
                entry.getName(),
                entry.getMimeType(),
                entry.getSize() == null ? 0L : entry.getSize(),
                content.getLength());
    }

    @Override
    public InputStream openContent(DriveFolderScope scope, Long userId, Long id, long offset) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        return DriveScopes.call(
                root.scope(), () -> entryService.getContentStream(id, userId, offset));
    }

    @Override
    public List<DriveFolderNode> trashList(DriveFolderScope scope, Long userId) {
        Root root = requireRoot(scope);
        List<DriveEntryDO> entries =
                DriveScopes.call(
                        root.scope(),
                        () -> {
                            permissionService.validatePermission(
                                    root.spaceId(),
                                    root.id(),
                                    userId,
                                    DrivePermissionRoleEnum.VIEWER);
                            return entryMapper.selectTrashedInSubtree(
                                    root.spaceId(),
                                    root.id(),
                                    String.valueOf(userId),
                                    root.originKey());
                        });
        return nodes(root, entries, editorByOwnRole(root, root.id(), userId));
    }

    @Override
    public DriveFolderNode restore(DriveFolderScope scope, Long userId, Long id) {
        Root root = requireRoot(scope);
        requireNotRoot(root, id);
        requireInside(root, id, userId);
        DriveEntryDO entry = entryMapper.selectById(id);
        // 不在回收站的节点交给网盘按原规则拒绝；在回收站的才看原位置与来源
        if (entry != null && DriveTrashStateEnum.isTrashed(entry.getTrashState())) {
            // 原位置必须还在子树里：网盘自己的恢复在原位置不可用时会把节点放回空间根，那就出了这个文件夹
            Long originParentId = entry.getOriginParentId();
            DriveEntryDO originParent =
                    originParentId == null
                                    || ObjUtil.equal(originParentId, DriveEntryDO.PARENT_ID_ROOT)
                            ? null
                            : entryMapper.selectById(originParentId);
            if (originParent == null
                    || !DriveEntryTypeEnum.isFolder(originParent.getType())
                    || DriveTrashStateEnum.isTrashed(originParent.getTrashState())
                    || !Boolean.TRUE.equals(
                            entryMapper.selectIsInSubtree(originParentId, root.id()))) {
                throw exception(ENTRY_RESTORE_PARENT_GONE);
            }
            if (restricted(root, id, userId)) {
                requireOwn(root, id);
            }
        }
        DriveEntryDO restored =
                DriveScopes.call(root.scope(), () -> entryService.restoreEntry(id, userId));
        return node(
                root,
                restored,
                modifiable(
                        root,
                        editorByOwnRole(root, id, userId),
                        originKeys(List.of(id)).get(id)));
    }

    // ========== 根、编号换算 ==========

    /** 每个限定子树方法开头：凭据合法，根仍是「普通、未进回收站的目录，属于该空间，空间存在且开启」 */
    private Root requireRoot(DriveFolderScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("限定子树不能为空");
        }
        DrivePermissionRoleEnum role = DrivePermissionRoleEnum.valueOfCode(scope.role());
        if (role != DrivePermissionRoleEnum.VIEWER && role != DrivePermissionRoleEnum.EDITOR) {
            throw new IllegalArgumentException("限定子树的角色只能是 VIEWER 或 EDITOR");
        }
        requireOriginKey(scope.originKey());
        DriveEntryDO rootEntry =
                scope.rootEntryId() == null ? null : entryMapper.selectById(scope.rootEntryId());
        if (rootEntry == null
                || !DriveEntryTypeEnum.isFolder(rootEntry.getType())
                || Boolean.TRUE.equals(rootEntry.getManagedBiz())
                || DriveTrashStateEnum.isTrashed(rootEntry.getTrashState())
                || ObjUtil.notEqual(rootEntry.getSpaceId(), scope.spaceId())) {
            throw exception(ENTRY_SCOPE_UNAVAILABLE);
        }
        DriveSpaceDO space = spaceMapper.selectById(scope.spaceId());
        if (space == null || !CommonStatusEnum.isEnable(space.getStatus())) {
            throw exception(ENTRY_SCOPE_UNAVAILABLE);
        }
        return new Root(
                new DriveScopes.Scope(scope.spaceId(), scope.rootEntryId(), role),
                scope.originKey());
    }

    private static void requireOriginKey(String originKey) {
        if (StrUtil.isBlank(originKey) || originKey.length() > MAX_ORIGIN_KEY_LENGTH) {
            throw new IllegalArgumentException("来源键不能为空白，且不能超过 700 个字");
        }
    }

    /** 根自身不可被改名、移动、复制、删除、恢复、读取：0 与根的真实编号都算根 */
    private static void requireNotRoot(Root root, Long id) {
        if (id == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        if (ObjUtil.equal(id, DriveEntryDO.PARENT_ID_ROOT) || ObjUtil.equal(id, root.id())) {
            throw exception(ENTRY_SCOPE_ROOT_FORBIDDEN);
        }
    }

    /** 入参里的 0（或缺省）换成根的真实编号 */
    private static Long real(Root root, Long parentId) {
        return parentId == null || ObjUtil.equal(parentId, DriveEntryDO.PARENT_ID_ROOT)
                ? root.id()
                : parentId;
    }

    /** 出参里等于根编号的父节点换成 0：根自身的编号不出现在任何返回值里 */
    private static Long outward(Root root, Long parentId) {
        return ObjUtil.equal(parentId, root.id()) ? DriveEntryDO.PARENT_ID_ROOT : parentId;
    }

    // ========== 来源规则 ==========

    /** 本人网盘角色是否达到可编辑。⛔ 必须在限定子树之外调用：此时线程上没有限定子树，得到的才是本人的角色。 */
    private boolean editorByOwnRole(Root root, Long entryId, Long userId) {
        DrivePermissionRoleEnum own =
                permissionService.getEffectiveRole(root.spaceId(), entryId, userId);
        return own != null && own.satisfies(DrivePermissionRoleEnum.EDITOR);
    }

    /**
     * 这个节点这次要不要受来源限制：只有「凭限定子树的可编辑角色、且本人网盘角色不到可编辑」时才受限。
     *
     * <p>限定子树是可查看时不查来源，直接转调，由网盘按本人角色放行或拒绝。节点不在子树内时先按无权限拒绝，不透露它的来源。
     */
    private boolean restricted(Root root, Long id, Long userId) {
        if (!root.editor() || editorByOwnRole(root, id, userId)) {
            return false;
        }
        requireInside(root, id, userId);
        return true;
    }

    /** 节点必须在这棵子树里（挂上限定子树后至少可查看），否则按无权限拒绝 */
    private void requireInside(Root root, Long id, Long userId) {
        DriveScopes.run(
                root.scope(),
                () ->
                        permissionService.validatePermission(
                                root.spaceId(), id, userId, DrivePermissionRoleEnum.VIEWER));
    }

    /** 节点自己的来源键必须是本次的来源键（没有标记的不算） */
    private void requireOwn(Root root, Long id) {
        if (ObjUtil.notEqual(originKeys(List.of(id)).get(id), root.originKey())) {
            throw exception(ENTRY_SCOPE_NOT_OWN);
        }
    }

    /** 节点自己及其下所有未进回收站的节点，来源键都必须是本次的来源键 */
    private void requireOwnSubtree(Root root, Long id) {
        requireOwn(root, id);
        if (entryMapper.countForeignInSubtree(root.spaceId(), id, root.originKey()) > 0) {
            throw exception(ENTRY_SCOPE_FOLDER_MIXED);
        }
    }

    private Map<Long, String> originKeys(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (DriveEntryOriginRow row : entryMapper.selectOrigins(ids)) {
            result.put(row.getEntryId(), row.getOriginKey());
        }
        return result;
    }

    /** 这次调用能不能改名/移动/删除这个节点：本人网盘角色可编辑，或凭限定子树可编辑且来源键相符 */
    private static boolean modifiable(Root root, boolean editorByOwnRole, String originKey) {
        return editorByOwnRole || root.editor() && ObjUtil.equal(originKey, root.originKey());
    }

    // ========== 转换 ==========

    private List<DriveFolderNode> nodes(
            Root root, List<DriveEntryDO> entries, boolean editorByOwnRole) {
        if (CollUtil.isEmpty(entries)) {
            return List.of();
        }
        Map<Long, String> keys =
                editorByOwnRole
                        ? Map.of()
                        : originKeys(entries.stream().map(DriveEntryDO::getId).toList());
        List<DriveFolderNode> result = new ArrayList<>(entries.size());
        for (DriveEntryDO entry : entries) {
            result.add(
                    node(root, entry, modifiable(root, editorByOwnRole, keys.get(entry.getId()))));
        }
        return result;
    }

    private static DriveFolderNode node(Root root, DriveEntryDO entry, boolean modifiable) {
        return new DriveFolderNode(
                entry.getId(),
                entry.getSpaceId(),
                outward(root, entry.getParentId()),
                entry.getName(),
                entry.getType(),
                entry.getSize(),
                entry.getMimeType(),
                null,
                entry.getCreator(),
                entry.getCreateTime(),
                entry.getUpdateTime(),
                entry.getTrashedAt(),
                entry.getTrashedBy(),
                modifiable);
    }

    /** 把自节点向上的链换成自上而下的名称 */
    private List<String> namesTopDown(List<DriveEntryChainRow> chain) {
        if (CollUtil.isEmpty(chain)) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (DriveEntryDO entry :
                entryMapper.selectListByIds(
                        chain.stream().map(DriveEntryChainRow::getId).toList())) {
            names.put(entry.getId(), entry.getName());
        }
        List<String> result = new ArrayList<>(chain.size());
        for (DriveEntryChainRow row : chain) {
            result.add(names.getOrDefault(row.getId(), StrUtil.EMPTY));
        }
        Collections.reverse(result);
        return result;
    }

    /** 名称校验，规则同 DriveEntryServiceImpl.validateName */
    private static String validateName(String name) {
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
