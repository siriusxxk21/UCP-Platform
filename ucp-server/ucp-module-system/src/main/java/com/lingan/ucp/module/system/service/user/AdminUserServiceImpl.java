package com.lingan.ucp.module.system.service.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.enums.UserTypeEnum;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.util.collection.CollectionUtils;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.framework.common.util.validation.ValidationUtils;
import com.lingan.ucp.framework.datapermission.core.util.DataPermissionUtils;
import com.lingan.ucp.module.infra.api.config.ConfigApi;
import com.lingan.ucp.module.system.controller.admin.auth.vo.AuthRegisterReqVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.PasswordStatusVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserImportExcelVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserImportRespVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO;
import com.lingan.ucp.module.system.dal.dataobject.dept.UserPostDO;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.dal.mysql.dept.UserPostMapper;
import com.lingan.ucp.module.system.dal.mysql.user.AdminUserMapper;
import com.lingan.ucp.module.system.service.dept.DeptService;
import com.lingan.ucp.module.system.service.dept.PostService;
import com.lingan.ucp.module.system.service.dept.UserDeptService;
import com.lingan.ucp.module.system.service.organization.OrganizationService;
import com.lingan.ucp.module.system.service.oauth2.OAuth2TokenService;
import com.lingan.ucp.module.system.service.permission.PermissionRelationService;
import com.lingan.ucp.module.system.service.permission.UserPermissionQueryService;
import com.lingan.ucp.module.system.service.tenant.TenantService;
import com.lingan.ucp.module.system.util.UserPinyinConverter;
import com.google.common.annotations.VisibleForTesting;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.*;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.*;
import static com.lingan.ucp.module.system.enums.LogRecordConstants.*;

/**
 * 后台用户 Service 实现类
 *
 * @author os
 */
@Service("adminUserService")
@Slf4j
public class AdminUserServiceImpl implements AdminUserService {

    static final String USER_INIT_PASSWORD_KEY = "system.user.init-password";

    static final String USER_REGISTER_ENABLED_KEY = "system.user.register-enabled";

    static final String USER_PASSWORD_EXPIRE_DAYS_KEY = "system.user.password-expire-days";

    static final String USER_PASSWORD_REMIND_DAYS_KEY = "system.user.password-remind-days";

    /** 默认密码过期天数（配置缺失时兜底，0 表示永不过期） */
    private static final int DEFAULT_PASSWORD_EXPIRE_DAYS = 30;

    /** 默认密码临期提醒天数（配置缺失时兜底） */
    private static final int DEFAULT_PASSWORD_REMIND_DAYS = 7;

    /** 密码状态本地缓存过期时间（毫秒），避免每次请求都查询用户表与配置 */
    private static final long PASSWORD_STATUS_CACHE_TTL_MS = 30_000L;

    /** 密码状态本地缓存 */
    private final Map<Long, PasswordStatusCacheEntry> passwordStatusCache = new ConcurrentHashMap<>();

    private record PasswordStatusCacheEntry(PasswordStatusVO status, long expireAt) {
    }

    @Resource
    private AdminUserMapper userMapper;

    @Resource
    private DeptService deptService;
    @Resource
    private OrganizationService organizationService;
    @Resource
    private UserDeptService userDeptService;
    @Resource
    private PostService postService;
    @Resource
    private PermissionRelationService permissionRelationService;
    @Resource
    private UserPermissionQueryService userPermissionQueryService;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    @Lazy // 延迟，避免循环依赖报错
    private TenantService tenantService;
    @Resource
    @Lazy // 懒加载，避免循环依赖
    private OAuth2TokenService oauth2TokenService;

    @Resource
    private UserPostMapper userPostMapper;

    @Resource
    private ConfigApi configApi;

    @Resource
    private UserPinyinConverter userPinyinConverter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = SYSTEM_USER_TYPE, subType = SYSTEM_USER_CREATE_SUB_TYPE, bizNo = "{{#user.id}}",
            success = SYSTEM_USER_CREATE_SUCCESS)
    public Long createUser(UserSaveReqVO createReqVO) {
        // 1.1 校验账户配合
        tenantService.handleTenantInfo(tenant -> {
            long count = userMapper.selectCount();
            if (count >= tenant.getAccountCount()) {
                throw exception(USER_COUNT_MAX, tenant.getAccountCount());
            }
        });
        // 1.2 校验正确性
        validateUserForCreateOrUpdate(null, createReqVO.getUsername(),
                createReqVO.getMobile(), createReqVO.getEmail(), createReqVO.getDeptId(), createReqVO.getPostIds());
        // 2.1 插入用户
        AdminUserDO user = BeanUtils.toBean(createReqVO, AdminUserDO.class);
        fillNicknamePinyin(user);
        user.setStatus(CommonStatusEnum.ENABLE.getStatus()); // 默认开启
        user.setPassword(encodePassword(createReqVO.getPassword())); // 加密密码
        userMapper.insert(user);
        userDeptService.syncMainDept(user.getId(), user.getDeptId());
        // 2.2 插入关联岗位
        if (CollectionUtil.isNotEmpty(user.getPostIds())) {
            userPostMapper.insertBatch(convertList(user.getPostIds(),
                    postId -> new UserPostDO().setUserId(user.getId()).setPostId(postId)));
        }

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("user", user);
        return user.getId();
    }

    @Override
    public Long registerUser(AuthRegisterReqVO registerReqVO) {
        // 1.1 校验是否开启注册
        if (ObjUtil.notEqual(configApi.getConfigValueByKey(USER_REGISTER_ENABLED_KEY), "true")) {
            throw exception(USER_REGISTER_DISABLED);
        }
        // 1.2 校验账户配合
        tenantService.handleTenantInfo(tenant -> {
            long count = userMapper.selectCount();
            if (count >= tenant.getAccountCount()) {
                throw exception(USER_COUNT_MAX, tenant.getAccountCount());
            }
        });
        // 1.3 校验正确性
        validateUserForCreateOrUpdate(null, registerReqVO.getUsername(), null, null, null, null);

        // 2. 插入用户
        AdminUserDO user = BeanUtils.toBean(registerReqVO, AdminUserDO.class);
        fillNicknamePinyin(user);
        user.setStatus(CommonStatusEnum.ENABLE.getStatus()); // 默认开启
        user.setPassword(encodePassword(registerReqVO.getPassword())); // 加密密码
        user.setPasswordUpdateTime(LocalDateTime.now()); // 自助注册，密码本人设置，记录改密时间
        userMapper.insert(user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = SYSTEM_USER_TYPE, subType = SYSTEM_USER_UPDATE_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = SYSTEM_USER_UPDATE_SUCCESS)
    public void updateUser(UserSaveReqVO updateReqVO) {
        updateReqVO.setPassword(null); // 特殊：此处不更新密码
        // 1. 校验正确性
        AdminUserDO oldUser = validateUserForCreateOrUpdate(updateReqVO.getId(), updateReqVO.getUsername(),
                updateReqVO.getMobile(), updateReqVO.getEmail(), updateReqVO.getDeptId(), updateReqVO.getPostIds());

        // 2.1 更新用户
        AdminUserDO updateObj = BeanUtils.toBean(updateReqVO, AdminUserDO.class);
        if (updateReqVO.getNickname() != null) {
            fillNicknamePinyin(updateObj);
        }
        userMapper.updateById(updateObj);
        userDeptService.syncMainDept(updateReqVO.getId(), updateReqVO.getDeptId());
        // 2.2 更新岗位
        updateUserPost(updateReqVO, updateObj);

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, BeanUtils.toBean(oldUser, UserSaveReqVO.class));
        LogRecordContext.putVariable("user", oldUser);
    }

    private void updateUserPost(UserSaveReqVO reqVO, AdminUserDO updateObj) {
        Long userId = reqVO.getId();
        Set<Long> dbPostIds = convertSet(userPostMapper.selectListByUserId(userId), UserPostDO::getPostId);
        // 计算新增和删除的岗位编号
        Set<Long> postIds = CollUtil.emptyIfNull(updateObj.getPostIds());
        Collection<Long> createPostIds = CollUtil.subtract(postIds, dbPostIds);
        Collection<Long> deletePostIds = CollUtil.subtract(dbPostIds, postIds);
        // 执行新增和删除。对于已经授权的岗位，不用做任何处理
        if (!CollectionUtil.isEmpty(createPostIds)) {
            userPostMapper.insertBatch(convertList(createPostIds,
                    postId -> new UserPostDO().setUserId(userId).setPostId(postId)));
        }
        if (!CollectionUtil.isEmpty(deletePostIds)) {
            userPostMapper.deleteByUserIdAndPostId(userId, deletePostIds);
        }
    }

    @Override
    public void updateUserLogin(Long id, String loginIp) {
        userMapper.updateById(new AdminUserDO().setId(id).setLoginIp(loginIp).setLoginDate(LocalDateTime.now()));
    }

    @Override
    public void updateUserProfile(Long id, UserProfileUpdateReqVO reqVO) {
        // 校验正确性
        validateUserExists(id);
        validateEmailUnique(id, reqVO.getEmail());
        validateMobileUnique(id, reqVO.getMobile());
        // 执行更新
        AdminUserDO updateObj = BeanUtils.toBean(reqVO, AdminUserDO.class).setId(id);
        if (reqVO.getNickname() != null) {
            fillNicknamePinyin(updateObj);
        }
        userMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserPassword(Long id, UserProfileUpdatePasswordReqVO reqVO) {
        // 校验旧密码密码
        validateOldPassword(id, reqVO.getOldPassword());
        // 执行更新
        AdminUserDO updateObj = new AdminUserDO().setId(id);
        updateObj.setPassword(encodePassword(reqVO.getNewPassword())); // 加密密码
        updateObj.setPasswordUpdateTime(LocalDateTime.now()); // 记录改密时间
        userMapper.updateById(updateObj);
        // 清理密码状态缓存，并删除该用户的访问令牌，强制重新登录，避免旧 Token 继续可用
        passwordStatusCache.remove(id);
        oauth2TokenService.removeAccessToken(id, UserTypeEnum.ADMIN.getValue());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRequiredPassword(Long id, String newPassword) {
        validateUserExists(id);
        AdminUserDO updateObj = new AdminUserDO().setId(id)
                .setPassword(encodePassword(newPassword))
                .setPasswordUpdateTime(LocalDateTime.now());
        userMapper.updateById(updateObj);
        passwordStatusCache.remove(id);
        // 防止其他浏览器中遗留的正式会话在改密后继续使用。
        oauth2TokenService.removeAccessToken(id, UserTypeEnum.ADMIN.getValue());
    }

    @Override
    @LogRecord(type = SYSTEM_USER_TYPE, subType = SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE, bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_PASSWORD_SUCCESS)
    @Transactional(rollbackFor = Exception.class)
    public void updateUserPassword(Long id, String password) {
        // 1. 校验用户存在
        AdminUserDO user = validateUserExists(id);

        // 2. 更新密码，并将密码修改时间置空，强制用户下次登录修改密码
        String encodedPassword = encodePassword(password);
        userMapper.update(null, new LambdaUpdateWrapper<AdminUserDO>()
                .set(AdminUserDO::getPassword, encodedPassword)
                .set(AdminUserDO::getPasswordUpdateTime, null)
                .eq(AdminUserDO::getId, id));
        passwordStatusCache.remove(id);
        oauth2TokenService.removeAccessToken(id, UserTypeEnum.ADMIN.getValue());

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetUserPassword(Long id, String password) {
        // 1. 校验用户存在
        validateUserExists(id);

        // 2. 更新密码，密码由用户本人设置，记录改密时间，不强制再次改密
        AdminUserDO updateObj = new AdminUserDO().setId(id);
        updateObj.setPassword(encodePassword(password));
        updateObj.setPasswordUpdateTime(LocalDateTime.now());
        userMapper.updateById(updateObj);
        passwordStatusCache.remove(id);
        oauth2TokenService.removeAccessToken(id, UserTypeEnum.ADMIN.getValue());
    }

    @Override
    public PasswordStatusVO getPasswordStatus(Long userId) {
        long now = System.currentTimeMillis();
        PasswordStatusCacheEntry cached = passwordStatusCache.get(userId);
        if (cached != null && cached.expireAt() > now) {
            return cached.status();
        }
        PasswordStatusVO status = doGetPasswordStatus(userId);
        passwordStatusCache.put(userId, new PasswordStatusCacheEntry(status, now + PASSWORD_STATUS_CACHE_TTL_MS));
        return status;
    }

    private PasswordStatusVO doGetPasswordStatus(Long userId) {
        AdminUserDO user = userMapper.selectById(userId);
        if (user == null) {
            return PasswordStatusVO.builder().mustChange(false).build();
        }
        int remindDays = parseIntConfig(USER_PASSWORD_REMIND_DAYS_KEY, DEFAULT_PASSWORD_REMIND_DAYS);
        // 从未修改过密码（使用初始密码），强制改密
        if (user.getPasswordUpdateTime() == null) {
            return PasswordStatusVO.builder().mustChange(true).remainDays(0).remindDays(remindDays).build();
        }
        int expireDays = parseIntConfig(USER_PASSWORD_EXPIRE_DAYS_KEY, DEFAULT_PASSWORD_EXPIRE_DAYS);
        // 0 表示永不过期
        if (expireDays <= 0) {
            return PasswordStatusVO.builder().mustChange(false).remainDays(null).remindDays(remindDays).build();
        }
        long usedDays = ChronoUnit.DAYS.between(user.getPasswordUpdateTime(), LocalDateTime.now());
        int remainDays = Math.max((int) (expireDays - usedDays), 0);
        return PasswordStatusVO.builder()
                .mustChange(remainDays <= 0)
                .remainDays(remainDays)
                .remindDays(remindDays)
                .build();
    }

    private int parseIntConfig(String key, int defaultValue) {
        String value = configApi.getConfigValueByKey(key);
        if (StrUtil.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            log.warn("[parseIntConfig][配置({})的值({})不是合法数字，使用默认值({})]", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public void updateUserStatus(Long id, Integer status) {
        // 校验用户存在
        validateUserExists(id);
        // 更新状态
        AdminUserDO updateObj = new AdminUserDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        userMapper.updateById(updateObj);

        // 如果是禁用用户，则删除其 Token 信息
        if (CommonStatusEnum.isDisable(status)) {
            oauth2TokenService.removeAccessToken(id, UserTypeEnum.ADMIN.getValue());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = SYSTEM_USER_TYPE, subType = SYSTEM_USER_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = SYSTEM_USER_DELETE_SUCCESS)
    public void deleteUser(Long id) {
        // 1. 校验用户存在
        AdminUserDO user = validateUserExists(id);

        // 2.1 删除用户
        userMapper.deleteById(id);
        // 2.2 删除用户关联数据
        permissionRelationService.processUserDeleted(id);
        // 2.2 删除用户岗位
        userPostMapper.deleteByUserId(id);

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserList(List<Long> ids) {
        // 1. 批量删除用户
        userMapper.deleteByIds(ids);

        // 2. 批量删除用户关联数据
        ids.forEach(id -> {
            permissionRelationService.processUserDeleted(id);
            userPostMapper.deleteByUserId(id);
        });
    }

    @Override
    public AdminUserDO getUserByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    @Override
    public AdminUserDO getUserByMobile(String mobile) {
        return userMapper.selectByMobile(mobile);
    }

    @Override
    public PageResult<AdminUserDO> getUserPage(UserPageReqVO reqVO) {
        // 如果有角色编号，查询角色对应的用户编号
        Set<Long> userIds = null;
        if (reqVO.getRoleId() != null) {
            userIds = userPermissionQueryService.getUserRoleIdListByRoleId(singleton(reqVO.getRoleId()));
            if (CollUtil.isEmpty(userIds)) {
                return PageResult.empty();
            }
        }

        // 分页查询
        return userMapper.selectPage(reqVO, getDeptCondition(reqVO.getDeptId()),
                getOrganizationCondition(reqVO.getOrgId()), userIds);
    }

    @Override
    public AdminUserDO getUser(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public List<AdminUserDO> getUserListByDeptIds(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectListByDeptIds(deptIds);
    }

    @Override
    public List<AdminUserDO> getUserListByPostIds(Collection<Long> postIds) {
        if (CollUtil.isEmpty(postIds)) {
            return Collections.emptyList();
        }
        Set<Long> userIds = convertSet(userPostMapper.selectListByPostIds(postIds), UserPostDO::getUserId);
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(userIds);
    }

    @Override
    public List<AdminUserDO> getUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(ids);
    }

    @Override
    public void validateUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 获得岗位信息
        List<AdminUserDO> users = userMapper.selectByIds(ids);
        Map<Long, AdminUserDO> userMap = CollectionUtils.convertMap(users, AdminUserDO::getId);
        // 校验
        ids.forEach(id -> {
            AdminUserDO user = userMap.get(id);
            if (user == null) {
                throw exception(USER_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
                throw exception(USER_IS_DISABLE, user.getNickname());
            }
        });
    }

    @Override
    public List<AdminUserDO> getUserListByNickname(String nickname) {
        return userMapper.selectListByNickname(nickname);
    }

    /**
     * 获得部门条件：查询指定部门的子部门编号们，包括自身
     *
     * @param deptId 部门编号
     * @return 部门编号集合
     */
    private Set<Long> getDeptCondition(Long deptId) {
        if (deptId == null) {
            return Collections.emptySet();
        }
        Set<Long> deptIds = convertSet(deptService.getChildDeptList(deptId), DeptDO::getId);
        deptIds.add(deptId); // 包括自身
        return deptIds;
    }

    /**
     * 获取组织筛选条件，组织节点需要包含当前组织及其全部下属组织。
     */
    private Set<Long> getOrganizationCondition(Long orgId) {
        if (orgId == null) {
            return Collections.emptySet();
        }
        Set<Long> orgIds = convertSet(organizationService.getChildOrganizationList(orgId),
                organization -> organization.getId());
        orgIds.add(orgId);
        return orgIds;
    }

    private AdminUserDO validateUserForCreateOrUpdate(Long id, String username, String mobile, String email,
                                               Long deptId, Set<Long> postIds) {
        // 关闭数据权限，避免因为没有数据权限，查询不到数据，进而导致唯一校验不正确
        return DataPermissionUtils.executeIgnore(() -> {
            // 校验用户存在
            AdminUserDO user = validateUserExists(id);
            // 校验用户名唯一
            validateUsernameUnique(id, username);
            // 校验手机号唯一
            validateMobileUnique(id, mobile);
            // 校验邮箱唯一
            validateEmailUnique(id, email);
            // 校验部门处于开启状态
            deptService.validateDeptList(CollectionUtils.singleton(deptId));
            // 校验岗位处于开启状态
            postService.validatePostList(postIds);
            return user;
        });
    }

    @VisibleForTesting
    AdminUserDO validateUserExists(Long id) {
        if (id == null) {
            return null;
        }
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    @VisibleForTesting
    void validateUsernameUnique(Long id, String username) {
        if (StrUtil.isBlank(username)) {
            return;
        }
        AdminUserDO user = userMapper.selectByUsername(username);
        if (user == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的用户
        if (id == null) {
            throw exception(USER_USERNAME_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_USERNAME_EXISTS);
        }
    }

    @VisibleForTesting
    void validateEmailUnique(Long id, String email) {
        if (StrUtil.isBlank(email)) {
            return;
        }
        AdminUserDO user = userMapper.selectByEmail(email);
        if (user == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的用户
        if (id == null) {
            throw exception(USER_EMAIL_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_EMAIL_EXISTS);
        }
    }

    @VisibleForTesting
    void validateMobileUnique(Long id, String mobile) {
        if (StrUtil.isBlank(mobile)) {
            return;
        }
        AdminUserDO user = userMapper.selectByMobile(mobile);
        if (user == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的用户
        if (id == null) {
            throw exception(USER_MOBILE_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_MOBILE_EXISTS);
        }
    }

    /**
     * 校验旧密码
     * @param id          用户 id
     * @param oldPassword 旧密码
     */
    @VisibleForTesting
    void validateOldPassword(Long id, String oldPassword) {
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        if (!isPasswordMatch(oldPassword, user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 添加事务，异常则回滚所有导入
    public UserImportRespVO importUserList(List<UserImportExcelVO> importUsers, boolean isUpdateSupport) {
        // 1.1 参数校验
        if (CollUtil.isEmpty(importUsers)) {
            throw exception(USER_IMPORT_LIST_IS_EMPTY);
        }
        // 1.2 初始化密码不能为空
        String initPassword = configApi.getConfigValueByKey(USER_INIT_PASSWORD_KEY);
        if (StrUtil.isEmpty(initPassword)) {
            throw exception(USER_IMPORT_INIT_PASSWORD);
        }

        // 2. 遍历，逐个创建 or 更新
        UserImportRespVO respVO = UserImportRespVO.builder().createUsernames(new ArrayList<>())
                .updateUsernames(new ArrayList<>()).failureUsernames(new LinkedHashMap<>()).build();
        Set<String> deptCodes = importUsers.stream().map(UserImportExcelVO::getDeptCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        Map<String, DeptDO> deptMap = convertMap(deptService.getDeptListByCodes(deptCodes), DeptDO::getDeptCode);
        AtomicInteger index = new AtomicInteger(1);
        importUsers.forEach(importUser -> {
            int currentIndex = index.getAndIncrement();
            DeptDO dept = StrUtil.isBlank(importUser.getDeptCode()) ? null : deptMap.get(importUser.getDeptCode());
            if (StrUtil.isNotBlank(importUser.getDeptCode()) && dept == null) {
                respVO.getFailureUsernames().put(importUser.getUsername(),
                        exception(DEPT_CODE_NOT_FOUND, importUser.getDeptCode()).getMessage());
                return;
            }
            Long deptId = dept == null ? null : dept.getId();
            // 2.1.1 校验字段是否符合要求
            try {
                ValidationUtils.validate(BeanUtils.toBean(importUser, UserSaveReqVO.class).setPassword(initPassword));
            } catch (ConstraintViolationException ex) {
                String key = StrUtil.blankToDefault(importUser.getUsername(), "第 " + currentIndex + " 行");
                respVO.getFailureUsernames().put(key, ex.getMessage());
                return;
            }
            // 2.1.2 校验，判断是否有不符合的原因
            try {
                validateUserForCreateOrUpdate(null, null, importUser.getMobile(), importUser.getEmail(),
                        deptId, null);
            } catch (ServiceException ex) {
                respVO.getFailureUsernames().put(importUser.getUsername(), ex.getMessage());
                return;
            }

            // 2.2.1 判断如果不存在，在进行插入
            AdminUserDO existUser = userMapper.selectByUsername(importUser.getUsername());
            if (existUser == null) {
                AdminUserDO newUser = BeanUtils.toBean(importUser, AdminUserDO.class)
                        .setDeptId(deptId).setPassword(encodePassword(initPassword)).setPostIds(new HashSet<>());
                fillNicknamePinyin(newUser);
                userMapper.insert(newUser); // 设置默认密码及空岗位编号数组
                userDeptService.syncMainDept(newUser.getId(), newUser.getDeptId());
                respVO.getCreateUsernames().add(importUser.getUsername());
                return;
            }
            // 2.2.2 如果存在，判断是否允许更新
            if (!isUpdateSupport) {
                respVO.getFailureUsernames().put(importUser.getUsername(), USER_USERNAME_EXISTS.getMsg());
                return;
            }
            AdminUserDO updateUser = BeanUtils.toBean(importUser, AdminUserDO.class).setDeptId(deptId);
            updateUser.setId(existUser.getId());
            if (importUser.getNickname() != null) {
                fillNicknamePinyin(updateUser);
            }
            userMapper.updateById(updateUser);
            userDeptService.syncMainDept(updateUser.getId(), updateUser.getDeptId());
            respVO.getUpdateUsernames().add(importUser.getUsername());
        });
        return respVO;
    }

    @Override
    public List<AdminUserDO> getUserListByStatus(Integer status) {
        return userMapper.selectListByStatus(status);
    }

    @Override
    public List<AdminUserDO> getUserListByStatus(Integer status, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return getUserListByStatus(status);
        }
        return userMapper.selectListByStatusAndKeyword(status, keyword.trim());
    }

    @Override
    public boolean isPasswordMatch(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 对密码进行加密
     *
     * @param password 密码
     * @return 加密后的密码
     */
    private String encodePassword(String password) {
        return passwordEncoder.encode(password);
    }

    /**
     * 根据昵称生成派生拼音字段，避免客户端直接写入或伪造拼音值。
     */
    private void fillNicknamePinyin(AdminUserDO user) {
        UserPinyinConverter.UserPinyin pinyin = userPinyinConverter.convert(user.getNickname());
        user.setNicknamePinyin(pinyin.fullPinyin());
        user.setNicknamePinyinInitial(pinyin.initials());
    }

}
