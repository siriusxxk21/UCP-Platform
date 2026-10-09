package com.richuang.os.module.drive.service.space;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.util.collection.CollectionUtils;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveBizSpaceCreateReqVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceCreateReqVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceRespVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceUpdateReqVO;
import com.richuang.os.module.drive.dal.dataobject.permission.DrivePermissionDO;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.enums.space.DriveSpaceTypeEnum;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网盘空间 Service 实现类
 *
 * @author os
 */
@Slf4j
@Service
@Validated
public class DriveSpaceServiceImpl implements DriveSpaceService {

    private static final String PERSONAL_SPACE_SUFFIX = "的个人空间";

    @Resource private DriveSpaceMapper spaceMapper;
    @Resource private DriveEntryMapper entryMapper;
    @Resource private DrivePermissionService permissionService;
    @Resource private AdminUserApi adminUserApi;
    @Resource private DeptApi deptApi;

    @Override
    public DriveSpaceDO getOrCreatePersonalSpace(Long userId) {
        DriveSpaceDO exists =
                spaceMapper.selectByOwnerIdAndType(userId, DriveSpaceTypeEnum.PERSONAL.getCode());
        if (exists != null) {
            return exists;
        }
        AdminUserRespDTO user = adminUserApi.getUser(userId);
        DriveSpaceDO space =
                DriveSpaceDO.builder()
                        .name(
                                StrUtil.format(
                                        "{}{}",
                                        user != null ? user.getNickname() : userId,
                                        PERSONAL_SPACE_SUFFIX))
                        .type(DriveSpaceTypeEnum.PERSONAL.getCode())
                        .ownerId(userId)
                        .ownerDeptId(user != null ? user.getDeptId() : null)
                        .quotaBytes(0L)
                        .usedBytes(0L)
                        .status(CommonStatusEnum.ENABLE.getStatus())
                        .build();
        try {
            spaceMapper.insert(space);
            return space;
        } catch (DuplicateKeyException e) {
            // 并发首次进入时由唯一索引兜底，重复建立直接返回已有空间
            return spaceMapper.selectByOwnerIdAndType(
                    userId, DriveSpaceTypeEnum.PERSONAL.getCode());
        }
    }

    @Override
    public Long createTeamSpace(DriveSpaceCreateReqVO reqVO, Long operatorId) {
        Long ownerId = reqVO.getOwnerId() != null ? reqVO.getOwnerId() : operatorId;
        AdminUserRespDTO owner = adminUserApi.getUser(ownerId);
        if (owner == null || !CommonStatusEnum.isEnable(owner.getStatus())) {
            throw exception(PERMISSION_SUBJECT_INVALID);
        }
        if (reqVO.getOwnerDeptId() != null) {
            DeptRespDTO dept = deptApi.getDept(reqVO.getOwnerDeptId());
            if (dept == null || !CommonStatusEnum.isEnable(dept.getStatus())) {
                throw exception(PERMISSION_SUBJECT_INVALID);
            }
        }
        DriveSpaceDO space =
                DriveSpaceDO.builder()
                        .name(reqVO.getName())
                        .type(DriveSpaceTypeEnum.TEAM.getCode())
                        .ownerId(ownerId)
                        .ownerDeptId(reqVO.getOwnerDeptId())
                        .quotaBytes(reqVO.getQuotaBytes() != null ? reqVO.getQuotaBytes() : 0L)
                        .usedBytes(0L)
                        .status(CommonStatusEnum.ENABLE.getStatus())
                        .build();
        spaceMapper.insert(space);
        return space.getId();
    }

    @Override
    public Long createBizSpace(DriveBizSpaceCreateReqVO reqVO) {
        String name = validateBizName(reqVO.getName());
        DriveSpaceDO space =
                DriveSpaceDO.builder()
                        .name(name)
                        .type(DriveSpaceTypeEnum.BIZ.getCode())
                        .ownerId(null)
                        .ownerDeptId(null)
                        .quotaBytes(reqVO.getQuotaBytes() != null ? reqVO.getQuotaBytes() : 0L)
                        .usedBytes(0L)
                        .status(CommonStatusEnum.ENABLE.getStatus())
                        .build();
        try {
            spaceMapper.insert(space);
        } catch (DuplicateKeyException duplicateException) {
            throw exception(SPACE_NAME_DUPLICATE);
        }
        return space.getId();
    }

    @Override
    public void updateTeamSpace(DriveSpaceUpdateReqVO reqVO, Long operatorId) {
        DriveSpaceDO space = getSpace(reqVO.getId());
        if (ObjUtil.equal(DriveSpaceTypeEnum.PERSONAL.getCode(), space.getType())) {
            throw exception(SPACE_PERSONAL_NOT_CREATE);
        }
        if (DriveSpaceTypeEnum.isBiz(space.getType())) {
            // 业务空间不归属个人，仅允许网盘治理权限调整名称与配额，归属主体保持为空
            String name = validateBizName(reqVO.getName());
            try {
                spaceMapper.updateById(
                        DriveSpaceDO.builder()
                                .id(space.getId())
                                .name(name)
                                .quotaBytes(reqVO.getQuotaBytes())
                                .status(reqVO.getStatus())
                                .build());
            } catch (DuplicateKeyException duplicateException) {
                throw exception(SPACE_NAME_DUPLICATE);
            }
            return;
        }
        DrivePermissionRoleEnum role =
                permissionService.getEffectiveRole(space.getId(), 0L, operatorId);
        if (role == null || !role.satisfies(DrivePermissionRoleEnum.MANAGER)) {
            throw exception(SPACE_NO_PERMISSION);
        }
        DriveSpaceDO updateObj =
                DriveSpaceDO.builder()
                        .id(space.getId())
                        .name(reqVO.getName())
                        .ownerDeptId(reqVO.getOwnerDeptId())
                        .quotaBytes(reqVO.getQuotaBytes())
                        .status(reqVO.getStatus())
                        .ownerId(
                                reqVO.getOwnerId() != null
                                        ? reqVO.getOwnerId()
                                        : space.getOwnerId())
                        .build();
        spaceMapper.updateById(updateObj);
    }

    @Override
    public void deleteTeamSpace(Long id, Long operatorId) {
        DriveSpaceDO space = getSpace(id);
        if (ObjUtil.equal(DriveSpaceTypeEnum.PERSONAL.getCode(), space.getType())) {
            throw exception(SPACE_PERSONAL_NOT_DELETE);
        }
        if (DriveSpaceTypeEnum.isBiz(space.getType())) {
            // 业务空间由业务融合规则建立，删除会导致受管节点失去归属，统一禁止
            throw exception(SPACE_BIZ_FORBIDDEN);
        }
        permissionService.validatePermission(
                space.getId(), 0L, operatorId, DrivePermissionRoleEnum.MANAGER);
        if (entryMapper.selectCountBySpace(id) > 0) {
            throw exception(SPACE_NOT_EMPTY);
        }
        spaceMapper.deleteById(id);
    }

    @Override
    public DriveSpaceDO getSpace(Long id) {
        DriveSpaceDO space = id == null ? null : spaceMapper.selectById(id);
        if (space == null) {
            throw exception(SPACE_NOT_EXISTS);
        }
        return space;
    }

    @Override
    public DriveSpaceRespVO getSpaceDetail(Long id, Long userId) {
        DriveSpaceDO space = getSpace(id);
        DrivePermissionRoleEnum role =
                permissionService.getEffectiveRole(
                        space.getId(), DrivePermissionDO.ENTRY_ID_SPACE, userId);
        if (role == null) {
            throw exception(SPACE_NO_PERMISSION);
        }
        DriveSpaceRespVO vo = BeanUtils.toBean(space, DriveSpaceRespVO.class);
        vo.setRole(role.getCode());
        AdminUserRespDTO owner =
                space.getOwnerId() != null ? adminUserApi.getUser(space.getOwnerId()) : null;
        vo.setOwnerName(owner != null ? owner.getNickname() : StrUtil.EMPTY);
        DeptRespDTO dept =
                space.getOwnerDeptId() != null ? deptApi.getDept(space.getOwnerDeptId()) : null;
        vo.setOwnerDeptName(dept != null ? dept.getName() : StrUtil.EMPTY);
        return vo;
    }

    @Override
    public List<DriveSpaceRespVO> getMySpaceList(Long userId) {
        // 个人空间对用户必然可见，首次进入工作区时顺带建立，避免前端多一次请求
        getOrCreatePersonalSpace(userId);
        Set<Long> spaceIds = permissionService.getAccessibleSpaceIds(userId);
        if (CollUtil.isEmpty(spaceIds)) {
            return new ArrayList<>();
        }
        List<DriveSpaceDO> spaces = new ArrayList<>(spaceMapper.selectListByIds(spaceIds));
        spaces.removeIf(space -> DriveSpaceTypeEnum.isBiz(space.getType()));
        Set<Long> ownerIds =
                new LinkedHashSet<>(CollectionUtils.convertSet(spaces, DriveSpaceDO::getOwnerId));
        Set<Long> deptIds =
                new LinkedHashSet<>(
                        CollectionUtils.convertSet(spaces, DriveSpaceDO::getOwnerDeptId));
        ownerIds.remove(null);
        deptIds.remove(null);
        Map<Long, AdminUserRespDTO> ownerMap = adminUserApi.getUserMap(ownerIds);
        Map<Long, DeptRespDTO> deptMap = deptApi.getDeptMap(deptIds);
        List<DriveSpaceRespVO> result = new ArrayList<>(spaces.size());
        for (DriveSpaceDO space : spaces) {
            DriveSpaceRespVO vo = new DriveSpaceRespVO();
            vo.setId(space.getId());
            vo.setName(space.getName());
            vo.setType(space.getType());
            vo.setOwnerId(space.getOwnerId());
            vo.setOwnerDeptId(space.getOwnerDeptId());
            vo.setQuotaBytes(space.getQuotaBytes());
            vo.setUsedBytes(space.getUsedBytes());
            vo.setStatus(space.getStatus());
            vo.setCreateTime(space.getCreateTime());
            AdminUserRespDTO owner = ownerMap.get(space.getOwnerId());
            vo.setOwnerName(owner != null ? owner.getNickname() : StrUtil.EMPTY);
            DeptRespDTO dept =
                    space.getOwnerDeptId() != null ? deptMap.get(space.getOwnerDeptId()) : null;
            vo.setOwnerDeptName(dept != null ? dept.getName() : StrUtil.EMPTY);
            DrivePermissionRoleEnum role =
                    permissionService.getEffectiveRole(space.getId(), 0L, userId);
            vo.setRole(role != null ? role.getCode() : null);
            result.add(vo);
        }
        // 个人空间在前，其余按建立时间倒序
        result.sort(
                Comparator.comparing(
                                (DriveSpaceRespVO vo) ->
                                        !DriveSpaceTypeEnum.PERSONAL.getCode().equals(vo.getType()))
                        .thenComparing(DriveSpaceRespVO::getId, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public List<DriveSpaceRespVO> getManageSpaceList(Long userId) {
        List<DriveSpaceRespVO> result = new ArrayList<>(getMySpaceList(userId));
        result.removeIf(
                space -> !DrivePermissionRoleEnum.MANAGER.getCode().equals(space.getRole()));
        for (DriveSpaceDO space : spaceMapper.selectBizList()) {
            DriveSpaceRespVO vo = BeanUtils.toBean(space, DriveSpaceRespVO.class);
            vo.setOwnerId(null);
            vo.setOwnerName(null);
            vo.setOwnerDeptId(null);
            vo.setOwnerDeptName(null);
            vo.setRole(null);
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<DriveSpaceRespVO> getBusinessSpaceList(Long userId) {
        List<DriveSpaceRespVO> result = new ArrayList<>();
        for (DriveSpaceDO space : spaceMapper.selectBizList()) {
            if (!CommonStatusEnum.isEnable(space.getStatus())) {
                continue;
            }
            DrivePermissionRoleEnum role =
                    permissionService.getEffectiveRole(
                            space.getId(), DrivePermissionDO.ENTRY_ID_SPACE, userId);
            if (role == null) {
                continue;
            }
            DriveSpaceRespVO vo = BeanUtils.toBean(space, DriveSpaceRespVO.class);
            vo.setOwnerId(null);
            vo.setOwnerName(null);
            vo.setOwnerDeptId(null);
            vo.setOwnerDeptName(null);
            vo.setRole(role.getCode());
            result.add(vo);
        }
        return result;
    }

    @Override
    public Map<Long, DriveSpaceDO> getSpaceMap(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Map.of();
        }
        return CollectionUtils.convertMap(spaceMapper.selectListByIds(ids), DriveSpaceDO::getId);
    }

    private static String validateBizName(String value) {
        String name = StrUtil.trim(value);
        if (StrUtil.isBlank(name) || name.length() > 64) {
            throw exception(SPACE_BIZ_NAME_INVALID);
        }
        return name;
    }
}
