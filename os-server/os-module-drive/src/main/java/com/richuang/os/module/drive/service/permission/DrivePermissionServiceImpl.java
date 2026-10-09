package com.richuang.os.module.drive.service.permission;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionRespVO;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionSaveReqVO;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionSourceRespVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryChainRow;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.dataobject.permission.DrivePermissionDO;
import com.richuang.os.module.drive.dal.dataobject.share.DriveShareDO;
import com.richuang.os.module.drive.dal.dataobject.share.DriveShareSubjectDO;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.permission.DrivePermissionMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.enums.permission.DriveSubjectTypeEnum;
import com.richuang.os.module.drive.enums.space.DriveSpaceTypeEnum;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网盘授权 Service 实现类
 *
 * @author os
 */
@Slf4j
@Service
@Validated
public class DrivePermissionServiceImpl implements DrivePermissionService {

    @Resource private DriveSpaceMapper spaceMapper;
    @Resource private DriveEntryMapper entryMapper;
    @Resource private DrivePermissionMapper permissionMapper;
    @Resource private DriveShareMapper shareMapper;
    @Resource private DriveShareSubjectMapper shareSubjectMapper;
    @Resource private DriveSubjectResolver subjectResolver;
    @Resource private AdminUserApi adminUserApi;
    @Resource private DeptApi deptApi;
    @Resource private PermissionCommonApi permissionApi;

    @Override
    public DrivePermissionRoleEnum getEffectiveRole(Long spaceId, Long entryId, Long userId) {
        DriveScopes.Scope scope = DriveScopes.current();
        if (scope != null) {
            return scopedRole(scope, spaceId, entryId, userId);
        }
        return plainRole(spaceId, entryId, userId);
    }

    /**
     * 限定子树内的角色：节点必须是子树的根自身或其子孙，角色取「限定子树的临时角色」与「本人网盘角色」中能力更强的一个。
     *
     * <p>限定子树的判断先于空间类型判断，业务空间、团队空间里都一样生效；空间根、别的空间、子树之外一律无权限。
     */
    private DrivePermissionRoleEnum scopedRole(
            DriveScopes.Scope scope, Long spaceId, Long entryId, Long userId) {
        if (spaceId == null
                || entryId == null
                || userId == null
                || ObjUtil.notEqual(scope.spaceId(), spaceId)
                || ObjUtil.equal(entryId, DrivePermissionDO.ENTRY_ID_SPACE)) {
            return null;
        }
        DriveSpaceDO space = spaceMapper.selectById(spaceId);
        if (space == null || !CommonStatusEnum.isEnable(space.getStatus())) {
            return null;
        }
        if (!Boolean.TRUE.equals(entryMapper.selectIsInSubtree(entryId, scope.rootEntryId()))) {
            return null;
        }
        return DrivePermissionRoleEnum.max(scope.role(), plainRole(spaceId, entryId, userId));
    }

    /** 不带限定子树的有效角色：空间归属、节点授权、组织内分享三者合并 */
    private DrivePermissionRoleEnum plainRole(Long spaceId, Long entryId, Long userId) {
        if (spaceId == null || entryId == null || userId == null) {
            return null;
        }
        DriveSpaceDO space = spaceMapper.selectById(spaceId);
        if (space == null || !CommonStatusEnum.isEnable(space.getStatus())) {
            return null;
        }
        // 业务空间：附件归档区（受管节点）只走业务授权链；文件夹区（普通节点）只对网盘管理员开放。
        // 两者都不读网盘授权表与分享，历史残留授权不能扩大可见范围。
        if (DriveSpaceTypeEnum.isBiz(space.getType())) {
            return bizFolderZoneRole(spaceId, entryId, userId);
        }
        // 空间归属主体始终按可管理对待，不依赖授权表
        if (ObjUtil.equal(space.getOwnerId(), userId)) {
            return DrivePermissionRoleEnum.MANAGER;
        }
        // 个人空间只对归属人开放
        if (ObjUtil.equal(DriveSpaceTypeEnum.PERSONAL.getCode(), space.getType())) {
            return null;
        }
        Set<Long> chainEntryIds = resolveChainEntryIds(spaceId, entryId);
        if (CollUtil.isEmpty(chainEntryIds)) {
            return null;
        }
        DrivePermissionRoleEnum role = resolveGrantRole(spaceId, chainEntryIds, userId);
        return DrivePermissionRoleEnum.max(role, resolveShareRole(spaceId, chainEntryIds, userId));
    }

    /** 业务空间文件夹区：受管节点（附件归档区）对任何人都无网盘角色；普通节点与空间根只对持有空间治理权限的人开放 */
    private DrivePermissionRoleEnum bizFolderZoneRole(Long spaceId, Long entryId, Long userId) {
        if (ObjUtil.notEqual(entryId, DrivePermissionDO.ENTRY_ID_SPACE)) {
            DriveEntryDO entry = entryMapper.selectById(entryId);
            if (entry == null
                    || ObjUtil.notEqual(entry.getSpaceId(), spaceId)
                    || Boolean.TRUE.equals(entry.getManagedBiz())) {
                return null;
            }
        }
        return permissionApi.hasAnyPermissions(userId, "drive:space:update")
                ? DrivePermissionRoleEnum.MANAGER
                : null;
    }

    @Override
    public void validatePermission(
            Long spaceId, Long entryId, Long userId, DrivePermissionRoleEnum required) {
        DrivePermissionRoleEnum role = getEffectiveRole(spaceId, entryId, userId);
        if (role == null || !role.satisfies(required)) {
            throw exception(PERMISSION_DENIED);
        }
    }

    @Override
    public Set<Long> getAccessibleSpaceIds(Long userId) {
        Set<Long> spaceIds = new LinkedHashSet<>();
        if (userId == null) {
            return spaceIds;
        }
        for (DriveSpaceDO space : spaceMapper.selectListByOwnerId(userId)) {
            spaceIds.add(space.getId());
        }
        DriveSpaceDO personal =
                spaceMapper.selectByOwnerIdAndType(userId, DriveSpaceTypeEnum.PERSONAL.getCode());
        if (personal != null) {
            spaceIds.add(personal.getId());
        }
        // 授权命中的空间：先取与本人及本人部门链相关的授权，再做主体匹配
        Set<Long> deptChain = subjectResolver.getUserDeptChain(userId);
        Long primaryDeptId = subjectResolver.getPrimaryDeptId(userId);
        List<DrivePermissionDO> grants =
                new ArrayList<>(
                        permissionMapper.selectListBySubjects(
                                DriveSubjectTypeEnum.USER.getCode(),
                                CollUtil.newArrayList(userId)));
        if (CollUtil.isNotEmpty(deptChain)) {
            grants.addAll(
                    permissionMapper.selectListBySubjects(
                            DriveSubjectTypeEnum.DEPT.getCode(), deptChain));
        }
        for (DrivePermissionDO grant : grants) {
            if (matchesSubject(
                    grant.getSubjectType(),
                    grant.getSubjectId(),
                    userId,
                    deptChain,
                    primaryDeptId,
                    grant.getIncludeChildren())) {
                spaceIds.add(grant.getSpaceId());
            }
        }
        spaceIds.remove(null);
        return spaceIds;
    }

    @Override
    public List<DrivePermissionRespVO> getPermissionList(Long spaceId, Long entryId, Long userId) {
        validatePermission(spaceId, entryId, userId, DrivePermissionRoleEnum.VIEWER);
        List<DrivePermissionDO> list =
                permissionMapper.selectListBySpaceAndEntryIds(
                        spaceId, CollUtil.newArrayList(entryId));
        if (CollUtil.isEmpty(list)) {
            return List.of();
        }
        boolean spaceRoot = ObjUtil.equal(entryId, DrivePermissionDO.ENTRY_ID_SPACE);
        Map<Long, String> entryNames = resolveEntryNames(spaceId, CollUtil.newArrayList(entryId));
        List<DrivePermissionRespVO> result = new ArrayList<>(list.size());
        for (DrivePermissionDO permission : list) {
            DrivePermissionRespVO vo = BeanUtils.toBean(permission, DrivePermissionRespVO.class);
            vo.setEntryName(spaceRoot ? "空间根目录" : entryNames.get(permission.getEntryId()));
            vo.setSubjectName(
                    resolveSubjectName(permission.getSubjectType(), permission.getSubjectId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<DrivePermissionSourceRespVO> getPermissionSourceList(
            Long spaceId, Long entryId, Long userId) {
        DriveSpaceDO space = validSpace(spaceId);
        validatePermission(spaceId, entryId, userId, DrivePermissionRoleEnum.VIEWER);
        Set<Long> chainEntryIds = resolveChainEntryIds(spaceId, entryId);
        Map<Long, String> entryNames = resolveEntryNames(spaceId, chainEntryIds);
        List<DrivePermissionSourceRespVO> result = new ArrayList<>();
        // 空间归属主体是所有节点的隐含来源
        DrivePermissionSourceRespVO ownerSource = new DrivePermissionSourceRespVO();
        ownerSource.setSubjectType(DriveSubjectTypeEnum.USER.getCode());
        ownerSource.setSubjectId(space.getOwnerId());
        ownerSource.setSubjectName(resolveUserName(space.getOwnerId()));
        ownerSource.setRole(DrivePermissionRoleEnum.MANAGER.getCode());
        ownerSource.setSourceEntryId(DrivePermissionDO.ENTRY_ID_SPACE);
        ownerSource.setSourceEntryName(
                entryNames.getOrDefault(DrivePermissionDO.ENTRY_ID_SPACE, "空间根目录"));
        ownerSource.setOwnerGrant(Boolean.TRUE);
        result.add(ownerSource);
        if (CollUtil.isEmpty(chainEntryIds)) {
            return result;
        }
        List<DrivePermissionDO> grants =
                permissionMapper.selectListBySpaceAndEntryIds(spaceId, chainEntryIds);
        for (DrivePermissionDO grant : grants) {
            DrivePermissionSourceRespVO vo = new DrivePermissionSourceRespVO();
            vo.setId(grant.getId());
            vo.setSubjectType(grant.getSubjectType());
            vo.setSubjectId(grant.getSubjectId());
            vo.setSubjectName(resolveSubjectName(grant.getSubjectType(), grant.getSubjectId()));
            vo.setRole(grant.getRole());
            vo.setIncludeChildren(grant.getIncludeChildren());
            vo.setSourceEntryId(grant.getEntryId());
            vo.setSourceEntryName(entryNames.getOrDefault(grant.getEntryId(), "空间根目录"));
            vo.setOwnerGrant(Boolean.FALSE);
            result.add(vo);
        }
        return result;
    }

    @Override
    public Long savePermission(DrivePermissionSaveReqVO reqVO, Long operatorId) {
        DriveSpaceDO space = validSpace(reqVO.getSpaceId());
        // 业务空间内容访问由业务授权链决定，网盘 ACL 与分享不能扩展业务文件权限
        if (DriveSpaceTypeEnum.isBiz(space.getType())) {
            throw exception(SPACE_BIZ_FORBIDDEN);
        }
        validEntry(reqVO.getSpaceId(), reqVO.getEntryId());
        // 只有可管理角色才能继续授权他人
        validatePermission(
                reqVO.getSpaceId(),
                reqVO.getEntryId(),
                operatorId,
                DrivePermissionRoleEnum.MANAGER);
        DrivePermissionRoleEnum role = DrivePermissionRoleEnum.valueOfCode(reqVO.getRole());
        if (role == null) {
            throw exception(PERMISSION_ROLE_INVALID);
        }
        DriveSubjectTypeEnum subjectType = DriveSubjectTypeEnum.valueOfCode(reqVO.getSubjectType());
        if (subjectType == null) {
            throw exception(PERMISSION_SUBJECT_INVALID);
        }
        validateSubject(subjectType, reqVO.getSubjectId());
        // 归属主体已隐含最高权限，不再单独配置
        if (subjectType == DriveSubjectTypeEnum.USER
                && ObjUtil.equal(space.getOwnerId(), reqVO.getSubjectId())) {
            throw exception(PERMISSION_OWNER_NOT_EDIT);
        }
        Boolean includeChildren = !Boolean.FALSE.equals(reqVO.getIncludeChildren());

        DrivePermissionDO exists =
                permissionMapper.selectByEntryAndSubject(
                        reqVO.getSpaceId(),
                        reqVO.getEntryId(),
                        reqVO.getSubjectType(),
                        reqVO.getSubjectId());
        if (exists == null) {
            DrivePermissionDO permission =
                    DrivePermissionDO.builder()
                            .spaceId(reqVO.getSpaceId())
                            .entryId(reqVO.getEntryId())
                            .subjectType(reqVO.getSubjectType())
                            .subjectId(reqVO.getSubjectId())
                            .role(role.getCode())
                            .includeChildren(includeChildren)
                            .build();
            permissionMapper.insert(permission);
            return permission.getId();
        }
        exists.setRole(role.getCode());
        exists.setIncludeChildren(includeChildren);
        permissionMapper.updateById(exists);
        return exists.getId();
    }

    @Override
    public void deletePermission(Long id, Long operatorId) {
        DrivePermissionDO permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw exception(PERMISSION_NOT_EXISTS);
        }
        validatePermission(
                permission.getSpaceId(),
                permission.getEntryId(),
                operatorId,
                DrivePermissionRoleEnum.MANAGER);
        DriveSpaceDO space = spaceMapper.selectById(permission.getSpaceId());
        if (space != null
                && DriveSubjectTypeEnum.USER.getCode().equals(permission.getSubjectType())
                && ObjUtil.equal(space.getOwnerId(), permission.getSubjectId())) {
            throw exception(PERMISSION_OWNER_NOT_EDIT);
        }
        permissionMapper.deleteById(id);
    }

    /**
     * 解析参与授权判定的节点链：自节点向上，遇到声明不继承的节点即停止
     *
     * <p>继承链顶端仍声明继承时，空间级授权（节点编号 0）同样生效。
     */
    private Set<Long> resolveChainEntryIds(Long spaceId, Long entryId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (ObjUtil.equal(entryId, DrivePermissionDO.ENTRY_ID_SPACE)) {
            ids.add(DrivePermissionDO.ENTRY_ID_SPACE);
            return ids;
        }
        List<DriveEntryChainRow> chain = entryMapper.selectPathChain(spaceId, entryId);
        if (CollUtil.isEmpty(chain)) {
            return Collections.emptySet();
        }
        for (DriveEntryChainRow row : chain) {
            ids.add(row.getId());
            // 该节点声明不继承上级授权时，其父级及更上层授权均不再对其生效
            if (!BooleanUtil.isTrue(row.getInheritParent())) {
                return ids;
            }
        }
        ids.add(DrivePermissionDO.ENTRY_ID_SPACE);
        return ids;
    }

    /** 按节点链上的授权计算角色，取能力最强的一条 */
    private DrivePermissionRoleEnum resolveGrantRole(
            Long spaceId, Set<Long> chainEntryIds, Long userId) {
        List<DrivePermissionDO> grants =
                permissionMapper.selectListBySpaceAndEntryIds(spaceId, chainEntryIds);
        if (CollUtil.isEmpty(grants)) {
            return null;
        }
        Set<Long> deptChain = subjectResolver.getUserDeptChain(userId);
        Long primaryDeptId = subjectResolver.getPrimaryDeptId(userId);
        DrivePermissionRoleEnum role = null;
        for (DrivePermissionDO grant : grants) {
            if (!matchesSubject(
                    grant.getSubjectType(),
                    grant.getSubjectId(),
                    userId,
                    deptChain,
                    primaryDeptId,
                    grant.getIncludeChildren())) {
                continue;
            }
            role =
                    DrivePermissionRoleEnum.max(
                            role, DrivePermissionRoleEnum.valueOfCode(grant.getRole()));
        }
        return role;
    }

    /** 按节点链上仍生效的分享计算角色，取能力最强的一条 */
    private DrivePermissionRoleEnum resolveShareRole(
            Long spaceId, Set<Long> chainEntryIds, Long userId) {
        List<DriveShareDO> shares = shareMapper.selectActiveListByEntryIds(chainEntryIds);
        if (CollUtil.isEmpty(shares)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, DriveShareDO> shareMap = new HashMap<>();
        for (DriveShareDO share : shares) {
            if (ObjUtil.notEqual(share.getSpaceId(), spaceId)) {
                continue;
            }
            if (share.getExpireTime() != null && !share.getExpireTime().isAfter(now)) {
                continue;
            }
            shareMap.put(share.getId(), share);
        }
        if (shareMap.isEmpty()) {
            return null;
        }
        List<DriveShareSubjectDO> subjects =
                shareSubjectMapper.selectListByShareIds(shareMap.keySet());
        if (CollUtil.isEmpty(subjects)) {
            return null;
        }
        Set<Long> deptChain = subjectResolver.getUserDeptChain(userId);
        Long primaryDeptId = subjectResolver.getPrimaryDeptId(userId);
        DrivePermissionRoleEnum role = null;
        for (DriveShareSubjectDO subject : subjects) {
            DriveShareDO share = shareMap.get(subject.getShareId());
            if (share == null) {
                continue;
            }
            // 分享按部门下发时始终覆盖下级部门，避免分享范围随部门调整失效
            if (!matchesSubject(
                    subject.getSubjectType(),
                    subject.getSubjectId(),
                    userId,
                    deptChain,
                    primaryDeptId,
                    Boolean.TRUE)) {
                continue;
            }
            role =
                    DrivePermissionRoleEnum.max(
                            role, DrivePermissionRoleEnum.valueOfCode(share.getRole()));
        }
        return role;
    }

    private boolean matchesSubject(
            String subjectType,
            Long subjectId,
            Long userId,
            Set<Long> deptChain,
            Long primaryDeptId,
            Boolean includeChildren) {
        if (DriveSubjectTypeEnum.USER.getCode().equals(subjectType)) {
            return ObjUtil.equal(subjectId, userId);
        }
        if (!DriveSubjectTypeEnum.DEPT.getCode().equals(subjectType)) {
            return false;
        }
        if (BooleanUtil.isTrue(includeChildren)) {
            return deptChain.contains(subjectId);
        }
        return ObjUtil.equal(subjectId, primaryDeptId);
    }

    private DriveSpaceDO validSpace(Long spaceId) {
        DriveSpaceDO space = spaceId == null ? null : spaceMapper.selectById(spaceId);
        if (space == null) {
            throw exception(SPACE_NOT_EXISTS);
        }
        return space;
    }

    private void validEntry(Long spaceId, Long entryId) {
        if (ObjUtil.equal(entryId, DrivePermissionDO.ENTRY_ID_SPACE)) {
            return;
        }
        DriveEntryDO entry = entryMapper.selectById(entryId);
        if (entry == null) {
            throw exception(ENTRY_NOT_EXISTS);
        }
        if (ObjUtil.notEqual(entry.getSpaceId(), spaceId)) {
            throw exception(ENTRY_SPACE_NOT_MATCH);
        }
    }

    private void validateSubject(DriveSubjectTypeEnum subjectType, Long subjectId) {
        if (subjectType == DriveSubjectTypeEnum.USER) {
            AdminUserRespDTO user = adminUserApi.getUser(subjectId);
            if (user == null || !CommonStatusEnum.isEnable(user.getStatus())) {
                throw exception(PERMISSION_SUBJECT_INVALID);
            }
            return;
        }
        DeptRespDTO dept = deptApi.getDept(subjectId);
        if (dept == null || !CommonStatusEnum.isEnable(dept.getStatus())) {
            throw exception(PERMISSION_SUBJECT_INVALID);
        }
    }

    private Map<Long, String> resolveEntryNames(Long spaceId, Collection<Long> entryIds) {
        Map<Long, String> names = new HashMap<>();
        List<Long> realIds =
                entryIds == null
                        ? new ArrayList<>()
                        : entryIds.stream()
                                .filter(id -> !ObjUtil.equal(id, DrivePermissionDO.ENTRY_ID_SPACE))
                                .toList();
        if (CollUtil.isEmpty(realIds)) {
            return names;
        }
        for (DriveEntryDO entry : entryMapper.selectListByIds(realIds)) {
            names.put(entry.getId(), entry.getName());
        }
        return names;
    }

    private String resolveUserName(Long userId) {
        if (userId == null) {
            return StrUtil.EMPTY;
        }
        AdminUserRespDTO user = adminUserApi.getUser(userId);
        return user != null ? user.getNickname() : StrUtil.EMPTY;
    }

    private String resolveSubjectName(String subjectType, Long subjectId) {
        if (DriveSubjectTypeEnum.USER.getCode().equals(subjectType)) {
            return resolveUserName(subjectId);
        }
        DeptRespDTO dept = deptApi.getDept(subjectId);
        return dept != null ? dept.getName() : StrUtil.EMPTY;
    }
}
