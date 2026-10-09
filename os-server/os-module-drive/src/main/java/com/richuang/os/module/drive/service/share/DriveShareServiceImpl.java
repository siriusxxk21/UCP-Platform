package com.richuang.os.module.drive.service.share;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.util.collection.CollectionUtils;
import com.richuang.os.module.drive.controller.admin.share.vo.DriveShareCreateReqVO;
import com.richuang.os.module.drive.controller.admin.share.vo.DriveSharePageReqVO;
import com.richuang.os.module.drive.controller.admin.share.vo.DriveShareRespVO;
import com.richuang.os.module.drive.controller.admin.share.vo.DriveShareSubjectVO;
import com.richuang.os.module.drive.controller.admin.share.vo.DriveShareUpdateReqVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.dataobject.share.DriveShareDO;
import com.richuang.os.module.drive.dal.dataobject.share.DriveShareSubjectDO;
import com.richuang.os.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.richuang.os.module.drive.enums.entry.DriveEntryTypeEnum;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.enums.permission.DriveSubjectTypeEnum;
import com.richuang.os.module.drive.enums.share.DriveShareStatusEnum;
import com.richuang.os.module.drive.service.entry.DriveEntryService;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.drive.service.permission.DriveSubjectResolver;
import com.richuang.os.module.drive.service.space.DriveSpaceService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网盘分享 Service 实现类
 *
 * @author os
 */
@Slf4j
@Service
@Validated
public class DriveShareServiceImpl implements DriveShareService {

    @Resource private DriveShareMapper shareMapper;
    @Resource private DriveShareSubjectMapper shareSubjectMapper;
    @Resource private DriveSpaceService spaceService;
    @Resource private DriveEntryService entryService;
    @Resource private DrivePermissionService permissionService;
    @Resource private DriveSubjectResolver subjectResolver;
    @Resource private AdminUserApi adminUserApi;
    @Resource private DeptApi deptApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createShare(DriveShareCreateReqVO reqVO, Long userId) {
        DriveEntryDO entry = entryService.getEntry(reqVO.getEntryId(), userId);
        // 受管业务文件首期不开放分享；后续导出与再授权规则另行定义
        if (Boolean.TRUE.equals(entry.getManagedBiz())) {
            throw exception(ENTRY_MANAGED_FORBIDDEN);
        }
        if (ObjUtil.notEqual(entry.getSpaceId(), reqVO.getSpaceId())) {
            throw exception(ENTRY_SPACE_NOT_MATCH);
        }
        // 分享等同于授权他人访问，与直接授权保持一致要求可管理角色
        permissionService.validatePermission(
                entry.getSpaceId(), entry.getId(), userId, DrivePermissionRoleEnum.MANAGER);
        DrivePermissionRoleEnum role = validateShareRole(reqVO.getRole());
        validateExpireTime(reqVO.getExpireTime());
        List<DriveShareSubjectDO> subjects = validateSubjects(reqVO.getSubjects());

        DriveShareDO share =
                DriveShareDO.builder()
                        .spaceId(entry.getSpaceId())
                        .entryId(entry.getId())
                        .role(role.getCode())
                        .expireTime(reqVO.getExpireTime())
                        .status(DriveShareStatusEnum.ACTIVE.getCode())
                        .build();
        shareMapper.insert(share);
        for (DriveShareSubjectDO subject : subjects) {
            subject.setShareId(share.getId());
            shareSubjectMapper.insert(subject);
        }
        return share.getId();
    }

    @Override
    public void updateShare(DriveShareUpdateReqVO reqVO, Long userId) {
        DriveShareDO share = validShare(reqVO.getId());
        // 修改角色或有效期属于再次授权，创建者失去管理权后不能继续扩权；仍允许撤回本人分享。
        permissionService.validatePermission(
                share.getSpaceId(), share.getEntryId(), userId, DrivePermissionRoleEnum.MANAGER);
        validShareActive(share);
        DrivePermissionRoleEnum role = validateShareRole(reqVO.getRole());
        validateExpireTime(reqVO.getExpireTime());
        // 失效时间可能被清空，交由 SQL 显式赋值
        shareMapper.updateRoleAndExpireTime(share.getId(), role.getCode(), reqVO.getExpireTime());
    }

    @Override
    public void revokeShare(Long id, Long userId) {
        DriveShareDO share = validShare(id);
        validateShareOperator(share, userId);
        if (!DriveShareStatusEnum.isActive(share.getStatus())) {
            return;
        }
        shareMapper.updateById(
                DriveShareDO.builder()
                        .id(share.getId())
                        .status(DriveShareStatusEnum.REVOKED.getCode())
                        .build());
    }

    @Override
    public PageResult<DriveShareRespVO> getSharePage(DriveSharePageReqVO reqVO, Long userId) {
        // V1 只展示当前用户发起的分享，空间管理员的全局分享视图留待后续
        PageResult<DriveShareDO> page =
                shareMapper.selectPage(reqVO, List.of(String.valueOf(userId)));
        // "我分享的"要回查历史，已撤销的分享由查询条件决定是否出现，此处不再二次过滤，否则行数与总数对不上
        return new PageResult<>(convertShareRespList(page.getList(), false), page.getTotal());
    }

    @Override
    public List<DriveShareRespVO> getShareListByEntry(Long entryId, Long userId) {
        DriveEntryDO entry = entryService.getEntry(entryId, userId);
        List<DriveShareDO> shares = shareMapper.selectActiveListByEntryIds(List.of(entry.getId()));
        return convertShareRespList(shares, true);
    }

    @Override
    public List<DriveShareRespVO> getSharedToMeList(Long userId) {
        Set<Long> shareIds = new LinkedHashSet<>();
        List<DriveShareSubjectDO> directList =
                shareSubjectMapper.selectListBySubjects(
                        DriveSubjectTypeEnum.USER.getCode(), List.of(userId));
        Set<Long> deptChain = subjectResolver.getUserDeptChain(userId);
        List<DriveShareSubjectDO> deptList =
                CollUtil.isEmpty(deptChain)
                        ? List.of()
                        : shareSubjectMapper.selectListBySubjects(
                                DriveSubjectTypeEnum.DEPT.getCode(), deptChain);
        for (DriveShareSubjectDO subject : CollUtil.union(directList, deptList)) {
            shareIds.add(subject.getShareId());
        }
        return buildShareRespList(new ArrayList<>(shareIds), String.valueOf(userId));
    }

    @Override
    public List<DriveShareRespVO> buildShareRespList(List<Long> shareIds, String viewerId) {
        if (CollUtil.isEmpty(shareIds)) {
            return List.of();
        }
        return convertShareRespList(shareMapper.selectListByIds(shareIds), true);
    }

    /**
     * 转换分享列表并补齐节点、空间与接收主体信息
     *
     * @param activeOnly 只保留生效中且未到期的分享；"我分享的"按状态回查历史时传 false，由查询条件过滤
     */
    private List<DriveShareRespVO> convertShareRespList(
            List<DriveShareDO> shares, boolean activeOnly) {
        if (CollUtil.isEmpty(shares)) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        // 已撤销与已到期的分享不再出现在"收到的共享"中
        List<DriveShareDO> visibleList =
                activeOnly
                        ? shares.stream()
                                .filter(share -> DriveShareStatusEnum.isActive(share.getStatus()))
                                .filter(
                                        share ->
                                                share.getExpireTime() == null
                                                        || share.getExpireTime().isAfter(now))
                                .toList()
                        : shares;
        if (CollUtil.isEmpty(visibleList)) {
            return List.of();
        }

        Map<Long, DriveEntryDO> entryMap =
                entryService.getEntryMap(
                        CollectionUtils.convertSet(visibleList, DriveShareDO::getEntryId));
        Map<Long, DriveSpaceDO> spaceMap =
                spaceService.getSpaceMap(
                        CollectionUtils.convertSet(visibleList, DriveShareDO::getSpaceId));
        List<DriveShareSubjectDO> allSubjects =
                shareSubjectMapper.selectListByShareIds(
                        CollectionUtils.convertList(visibleList, DriveShareDO::getId));
        Map<Long, List<DriveShareSubjectDO>> subjectMap =
                CollectionUtils.convertMultiMap(allSubjects, DriveShareSubjectDO::getShareId);

        List<DriveShareRespVO> result = new ArrayList<>(visibleList.size());
        for (DriveShareDO share : visibleList) {
            DriveEntryDO entry = entryMap.get(share.getEntryId());
            // 节点已被彻底删除时不再展示
            if (entry == null) {
                continue;
            }
            DriveShareRespVO vo = new DriveShareRespVO();
            vo.setId(share.getId());
            vo.setSpaceId(share.getSpaceId());
            DriveSpaceDO space = spaceMap.get(share.getSpaceId());
            vo.setSpaceName(space != null ? space.getName() : null);
            vo.setEntryId(entry.getId());
            vo.setEntryName(entry.getName());
            vo.setEntryType(
                    DriveEntryTypeEnum.isFolder(entry.getType())
                            ? DriveEntryTypeEnum.FOLDER.getCode()
                            : DriveEntryTypeEnum.FILE.getCode());
            vo.setRole(share.getRole());
            vo.setExpireTime(share.getExpireTime());
            vo.setStatus(share.getStatus());
            vo.setCreator(share.getCreator());
            vo.setCreateTime(share.getCreateTime());
            vo.setSubjects(convertSubjectList(subjectMap.get(share.getId())));
            result.add(vo);
        }
        fillUserNames(result);
        return result;
    }

    private List<DriveShareSubjectVO> convertSubjectList(List<DriveShareSubjectDO> subjects) {
        if (CollUtil.isEmpty(subjects)) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> deptIds = new LinkedHashSet<>();
        for (DriveShareSubjectDO subject : subjects) {
            if (DriveSubjectTypeEnum.DEPT.getCode().equals(subject.getSubjectType())) {
                deptIds.add(subject.getSubjectId());
            } else {
                userIds.add(subject.getSubjectId());
            }
        }
        Map<Long, String> userNameMap =
                CollUtil.isEmpty(userIds)
                        ? Map.of()
                        : CollectionUtils.convertMap(
                                adminUserApi.getUserList(userIds),
                                AdminUserRespDTO::getId,
                                AdminUserRespDTO::getNickname);
        Map<Long, String> deptNameMap =
                CollUtil.isEmpty(deptIds)
                        ? Map.of()
                        : CollectionUtils.convertMap(
                                deptApi.getDeptList(deptIds),
                                DeptRespDTO::getId,
                                DeptRespDTO::getName);

        List<DriveShareSubjectVO> list = new ArrayList<>(subjects.size());
        for (DriveShareSubjectDO subject : subjects) {
            DriveShareSubjectVO vo = new DriveShareSubjectVO();
            vo.setSubjectType(subject.getSubjectType());
            vo.setSubjectId(subject.getSubjectId());
            Map<Long, String> nameMap =
                    DriveSubjectTypeEnum.DEPT.getCode().equals(subject.getSubjectType())
                            ? deptNameMap
                            : userNameMap;
            vo.setSubjectName(nameMap.get(subject.getSubjectId()));
            list.add(vo);
        }
        return list;
    }

    private void fillUserNames(List<DriveShareRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> creatorIds = new LinkedHashSet<>();
        for (DriveShareRespVO vo : list) {
            if (StrUtil.isNotBlank(vo.getCreator()) && StrUtil.isNumeric(vo.getCreator())) {
                creatorIds.add(Long.valueOf(vo.getCreator()));
            }
        }
        if (creatorIds.isEmpty()) {
            return;
        }
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(creatorIds);
        for (DriveShareRespVO vo : list) {
            if (StrUtil.isBlank(vo.getCreator()) || !StrUtil.isNumeric(vo.getCreator())) {
                continue;
            }
            AdminUserRespDTO user = userMap.get(Long.valueOf(vo.getCreator()));
            if (user != null) {
                vo.setCreatorName(user.getNickname());
            }
        }
    }

    private DriveShareDO validShare(Long id) {
        DriveShareDO share = id == null ? null : shareMapper.selectById(id);
        if (share == null) {
            throw exception(SHARE_NOT_EXISTS);
        }
        return share;
    }

    /** 分享的变更由发起人本人或节点可管理者执行 */
    private void validateShareOperator(DriveShareDO share, Long userId) {
        if (StrUtil.equals(String.valueOf(userId), share.getCreator())) {
            return;
        }
        permissionService.validatePermission(
                share.getSpaceId(), share.getEntryId(), userId, DrivePermissionRoleEnum.MANAGER);
    }

    private void validShareActive(DriveShareDO share) {
        if (!DriveShareStatusEnum.isActive(share.getStatus())) {
            throw exception(SHARE_INACTIVE);
        }
        if (share.getExpireTime() != null && share.getExpireTime().isBefore(LocalDateTime.now())) {
            throw exception(SHARE_INACTIVE);
        }
    }

    private DrivePermissionRoleEnum validateShareRole(String role) {
        DrivePermissionRoleEnum target = DrivePermissionRoleEnum.valueOfCode(role);
        // 分享只下发查看与编辑，管理权限仅通过空间或目录授权配置
        if (target == null || target == DrivePermissionRoleEnum.MANAGER) {
            throw exception(PERMISSION_ROLE_INVALID);
        }
        return target;
    }

    private void validateExpireTime(LocalDateTime expireTime) {
        if (expireTime != null && !expireTime.isAfter(LocalDateTime.now())) {
            throw exception(SHARE_EXPIRE_INVALID);
        }
    }

    /** 校验接收主体并去重，用户与部门都必须存在且启用 */
    private List<DriveShareSubjectDO> validateSubjects(List<DriveShareSubjectVO> subjects) {
        if (CollUtil.isEmpty(subjects)) {
            throw exception(SHARE_SUBJECT_EMPTY);
        }
        List<Long> userIds = new ArrayList<>();
        List<Long> deptIds = new ArrayList<>();
        List<DriveShareSubjectDO> result = new ArrayList<>(subjects.size());
        Set<String> uniqueKeys = new LinkedHashSet<>();
        for (DriveShareSubjectVO subject : subjects) {
            DriveSubjectTypeEnum subjectType =
                    DriveSubjectTypeEnum.valueOfCode(subject.getSubjectType());
            if (subjectType == null || subject.getSubjectId() == null) {
                throw exception(PERMISSION_SUBJECT_INVALID);
            }
            if (!uniqueKeys.add(
                    StrUtil.format("{}:{}", subjectType.getCode(), subject.getSubjectId()))) {
                continue;
            }
            if (subjectType == DriveSubjectTypeEnum.USER) {
                userIds.add(subject.getSubjectId());
            } else {
                deptIds.add(subject.getSubjectId());
            }
            result.add(
                    DriveShareSubjectDO.builder()
                            .subjectType(subjectType.getCode())
                            .subjectId(subject.getSubjectId())
                            .build());
        }
        if (CollUtil.isEmpty(result)) {
            throw exception(SHARE_SUBJECT_EMPTY);
        }
        try {
            if (CollUtil.isNotEmpty(userIds)) {
                adminUserApi.validateUserList(userIds);
            }
            if (CollUtil.isNotEmpty(deptIds)) {
                deptApi.validateDeptList(deptIds);
            }
        } catch (Exception exception) {
            log.warn(
                    "[validateSubjects][主体校验失败, userIds({}) deptIds({})]",
                    userIds,
                    deptIds,
                    exception);
            throw exception(PERMISSION_SUBJECT_INVALID);
        }
        return result;
    }
}
