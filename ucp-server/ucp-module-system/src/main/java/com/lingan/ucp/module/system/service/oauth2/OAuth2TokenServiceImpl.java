package com.lingan.ucp.module.system.service.oauth2;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.enums.UserTypeEnum;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.util.date.DateUtils;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.framework.security.core.LoginUser;
import com.lingan.ucp.framework.tenant.core.context.TenantContextHolder;
import com.lingan.ucp.framework.tenant.core.util.TenantUtils;
import com.lingan.ucp.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenPageReqVO;
import com.lingan.ucp.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import com.lingan.ucp.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import com.lingan.ucp.module.system.dal.dataobject.oauth2.OAuth2RefreshTokenDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.RoleDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.UserRoleDO;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import com.lingan.ucp.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import com.lingan.ucp.module.system.dal.mysql.permission.UserRoleMapper;
import com.lingan.ucp.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import com.lingan.ucp.module.system.dal.redis.oauth2.OAuth2SessionRedisDAO;
import com.lingan.ucp.module.system.enums.permission.DataScopeEnum;
import com.lingan.ucp.module.system.enums.oauth2.OAuth2ClientConstants;
import com.lingan.ucp.module.system.service.permission.RoleService;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * OAuth2.0 Token Service 实现类
 *
 * @author os
 */
@Service
@Slf4j
public class OAuth2TokenServiceImpl implements OAuth2TokenService {

    /** 持续登录的存储标记，兼容既有 expires_time 非空约束及过期清理任务。 */
    static final LocalDateTime PERSISTENT_SESSION_EXPIRES_TIME = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    @Resource
    private OAuth2AccessTokenMapper oauth2AccessTokenMapper;
    @Resource
    private OAuth2RefreshTokenMapper oauth2RefreshTokenMapper;

    @Resource
    private OAuth2AccessTokenRedisDAO oauth2AccessTokenRedisDAO;
    @Resource
    private OAuth2SessionRedisDAO oauth2SessionRedisDAO;

    /** 管理后台登录会话最长空闲时间。 */
    @Value("${os.security.session-idle-timeout:30m}")
    private Duration sessionIdleTimeout;

    @Resource
    private OAuth2ClientService oauth2ClientService;
    @Resource
    @Lazy // 懒加载，避免循环依赖
    private AdminUserService adminUserService;
    @Resource
    private UserRoleMapper userRoleMapper;
    @Resource
    @Lazy // 懒加载，避免循环依赖
    private RoleService roleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OAuth2AccessTokenDO createAccessToken(Long userId, Integer userType, String clientId, List<String> scopes) {
        OAuth2ClientDO clientDO = oauth2ClientService.validOAuthClientFromCache(clientId);
        // 创建刷新令牌
        OAuth2RefreshTokenDO refreshTokenDO = createOAuth2RefreshToken(userId, userType, clientDO, scopes);
        // 创建访问令牌
        return createOAuth2AccessToken(refreshTokenDO, clientDO);
    }

    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public OAuth2AccessTokenDO refreshAccessToken(String refreshToken, String clientId) {
        // 查询访问令牌
        OAuth2RefreshTokenDO refreshTokenDO = oauth2RefreshTokenMapper.selectByRefreshToken(refreshToken);
        if (refreshTokenDO == null) {
            throw exception0(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "无效的刷新令牌");
        }

        // 校验 Client 匹配
        OAuth2ClientDO clientDO = oauth2ClientService.validOAuthClientFromCache(clientId);
        if (ObjectUtil.notEqual(clientId, refreshTokenDO.getClientId())) {
            throw exception0(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "刷新令牌的客户端编号不正确");
        }

        // 默认管理端持续登录；其他 OAuth 客户端仍沿用原有效期和空闲策略。
        if (DateUtils.isExpired(refreshTokenDO.getExpiresTime())
                || (!isPersistentSession(refreshTokenDO.getUserType(), clientId)
                    && !oauth2SessionRedisDAO.touch(refreshToken, sessionIdleTimeout))) {
            removeSessionTokens(refreshTokenDO);
            throw exception0(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "登录会话已过期，请重新登录");
        }

        // 当前仍有效的旧会话首次续期时升级；不恢复已过期或已注销的凭证。
        if (isPersistentSession(refreshTokenDO.getUserType(), clientId)
                && !PERSISTENT_SESSION_EXPIRES_TIME.equals(refreshTokenDO.getExpiresTime())) {
            refreshTokenDO.setExpiresTime(PERSISTENT_SESSION_EXPIRES_TIME);
            oauth2RefreshTokenMapper.updateById(refreshTokenDO);
        }

        // 移除相关的访问令牌
        List<OAuth2AccessTokenDO> accessTokenDOs = oauth2AccessTokenMapper.selectListByRefreshToken(refreshToken);
        if (CollUtil.isNotEmpty(accessTokenDOs)) {
            oauth2AccessTokenMapper.deleteByIds(convertSet(accessTokenDOs, OAuth2AccessTokenDO::getId));
            oauth2AccessTokenRedisDAO.deleteList(convertSet(accessTokenDOs, OAuth2AccessTokenDO::getAccessToken));
        }

        // 创建访问令牌
        return createOAuth2AccessToken(refreshTokenDO, clientDO);
    }

    @Override
    public OAuth2AccessTokenDO getAccessToken(String accessToken) {
        // 优先从 Redis 中获取
        OAuth2AccessTokenDO accessTokenDO = oauth2AccessTokenRedisDAO.get(accessToken);
        if (accessTokenDO != null) {
            return accessTokenDO;
        }

        // 获取不到，从 MySQL 中获取访问令牌
        accessTokenDO = oauth2AccessTokenMapper.selectByAccessToken(accessToken);
        if (accessTokenDO == null) {
            // 特殊：从 MySQL 中获取刷新令牌。原因：解决部分场景不方便刷新访问令牌场景
            // 例如说，积木报表只允许传递 token，不允许传递 refresh_token，导致无法刷新访问令牌
            // 再例如说，前端 WebSocket 的 token 直接跟在 url 上，无法传递 refresh_token
            OAuth2RefreshTokenDO refreshTokenDO = oauth2RefreshTokenMapper.selectByRefreshToken(accessToken);
            if (refreshTokenDO != null && !DateUtils.isExpired(refreshTokenDO.getExpiresTime())) {
                accessTokenDO = convertToAccessToken(refreshTokenDO);
            }
        }

        // 如果在 MySQL 存在，则往 Redis 中写入
        if (accessTokenDO != null && !DateUtils.isExpired(accessTokenDO.getExpiresTime())) {
            oauth2AccessTokenRedisDAO.set(accessTokenDO);
        }
        return accessTokenDO;
    }

    @Override
    public OAuth2AccessTokenDO checkAccessToken(String accessToken) {
        OAuth2AccessTokenDO accessTokenDO = getAccessToken(accessToken);
        if (accessTokenDO == null) {
            throw exception0(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "访问令牌不存在");
        }
        if (DateUtils.isExpired(accessTokenDO.getExpiresTime())) {
            throw exception0(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "访问令牌已过期");
        }
        // 持续登录以持久化刷新凭证为准，Redis 空闲键丢失不代表主动退出。
        if (isPersistentSession(accessTokenDO.getUserType(), accessTokenDO.getClientId())) {
            OAuth2RefreshTokenDO session = oauth2RefreshTokenMapper.selectByRefreshToken(accessTokenDO.getRefreshToken());
            if (session == null || DateUtils.isExpired(session.getExpiresTime())) {
                throw exception0(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "登录会话已注销，请重新登录");
            }
        } else if (!oauth2SessionRedisDAO.touch(accessTokenDO.getRefreshToken(), sessionIdleTimeout)) {
            removeSessionTokens(accessTokenDO);
            throw exception0(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "登录会话已过期，请重新登录");
        }
        return accessTokenDO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OAuth2AccessTokenDO removeAccessToken(String accessToken) {
        // 允许用刷新凭证退出，访问凭证过期、轮换或已清理后仍能撤销整个会话。
        OAuth2AccessTokenDO accessTokenDO = oauth2AccessTokenMapper.selectByAccessToken(accessToken);
        if (accessTokenDO == null) {
            OAuth2RefreshTokenDO session = oauth2RefreshTokenMapper.selectByRefreshToken(accessToken);
            if (session == null) {
                return null;
            }
            removeSessionTokens(session);
            return BeanUtils.toBean(session, OAuth2AccessTokenDO.class).setAccessToken(accessToken);
        }
        removeSessionTokens(accessTokenDO);
        return accessTokenDO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAccessToken(Long userId, Integer userType) {
        // 访问凭证可能已被定时清理，改密/停用仍须撤销保留的持续登录凭证。
        List<OAuth2RefreshTokenDO> sessions = oauth2RefreshTokenMapper.selectList(
                OAuth2RefreshTokenDO::getUserId, userId, OAuth2RefreshTokenDO::getUserType, userType);
        sessions.forEach(this::removeSessionTokens);
        List<OAuth2AccessTokenDO> accessTokens = oauth2AccessTokenMapper.selectListByUserIdAndUserType(userId, userType);
        if (CollUtil.isEmpty(accessTokens)) {
            return;
        }
        accessTokens.forEach(accessToken -> {
            // 删除访问令牌
            oauth2AccessTokenMapper.deleteById(accessToken.getId());
            oauth2AccessTokenRedisDAO.delete(accessToken.getAccessToken());
            // 删除刷新令牌
            oauth2RefreshTokenMapper.deleteByRefreshToken(accessToken.getRefreshToken());
            oauth2AccessTokenRedisDAO.delete(accessToken.getRefreshToken());
            oauth2SessionRedisDAO.delete(accessToken.getRefreshToken());
        });
    }

    @Override
    public PageResult<OAuth2AccessTokenDO> getAccessTokenPage(OAuth2AccessTokenPageReqVO reqVO) {
        return oauth2AccessTokenMapper.selectPage(reqVO);
    }

    private OAuth2AccessTokenDO createOAuth2AccessToken(OAuth2RefreshTokenDO refreshTokenDO, OAuth2ClientDO clientDO) {
        OAuth2AccessTokenDO accessTokenDO = new OAuth2AccessTokenDO().setAccessToken(generateAccessToken())
                .setUserId(refreshTokenDO.getUserId()).setUserType(refreshTokenDO.getUserType())
                .setUserInfo(buildUserInfo(refreshTokenDO.getUserId(), refreshTokenDO.getUserType()))
                .setClientId(clientDO.getClientId()).setScopes(refreshTokenDO.getScopes())
                .setRefreshToken(refreshTokenDO.getRefreshToken())
                .setExpiresTime(LocalDateTime.now().plusSeconds(clientDO.getAccessTokenValiditySeconds()));
        // 优先从 refreshToken 获取租户编号，避免 ThreadLocal 被污染时导致 tenantId 为 null
        // 可能关联的 issue：https://t.zsxq.com/JIi5G
        Long tenantId = refreshTokenDO.getTenantId();
        if (tenantId == null) {
            tenantId = TenantContextHolder.getTenantId();
        }
        accessTokenDO.setTenantId(tenantId);
        oauth2AccessTokenMapper.insert(accessTokenDO);
        // 记录到 Redis 中
        oauth2AccessTokenRedisDAO.set(accessTokenDO);
        return accessTokenDO;
    }

    private OAuth2RefreshTokenDO createOAuth2RefreshToken(Long userId, Integer userType, OAuth2ClientDO clientDO, List<String> scopes) {
        OAuth2RefreshTokenDO refreshToken = new OAuth2RefreshTokenDO().setRefreshToken(generateRefreshToken())
                .setUserId(userId).setUserType(userType)
                .setClientId(clientDO.getClientId()).setScopes(scopes)
                .setExpiresTime(isPersistentSession(userType, clientDO.getClientId())
                        ? PERSISTENT_SESSION_EXPIRES_TIME
                        : LocalDateTime.now().plusSeconds(clientDO.getRefreshTokenValiditySeconds()));
        oauth2RefreshTokenMapper.insert(refreshToken);
        if (!isPersistentSession(userType, clientDO.getClientId())) {
            oauth2SessionRedisDAO.create(refreshToken.getRefreshToken(), sessionIdleTimeout);
        }
        return refreshToken;
    }

    private boolean isPersistentSession(Integer userType, String clientId) {
        return UserTypeEnum.ADMIN.getValue().equals(userType)
                && OAuth2ClientConstants.CLIENT_ID_DEFAULT.equals(clientId);
    }

    private void removeSessionTokens(OAuth2AccessTokenDO accessTokenDO) {
        OAuth2RefreshTokenDO refreshTokenDO = oauth2RefreshTokenMapper.selectByRefreshToken(accessTokenDO.getRefreshToken());
        if (refreshTokenDO != null) {
            removeSessionTokens(refreshTokenDO);
        } else {
            oauth2AccessTokenMapper.deleteById(accessTokenDO.getId());
            oauth2AccessTokenRedisDAO.delete(accessTokenDO.getAccessToken());
            oauth2SessionRedisDAO.delete(accessTokenDO.getRefreshToken());
        }
    }

    private void removeSessionTokens(OAuth2RefreshTokenDO refreshTokenDO) {
        List<OAuth2AccessTokenDO> accessTokenDOs = oauth2AccessTokenMapper.selectListByRefreshToken(refreshTokenDO.getRefreshToken());
        if (CollUtil.isNotEmpty(accessTokenDOs)) {
            oauth2AccessTokenMapper.deleteByIds(convertSet(accessTokenDOs, OAuth2AccessTokenDO::getId));
            oauth2AccessTokenRedisDAO.deleteList(convertSet(accessTokenDOs, OAuth2AccessTokenDO::getAccessToken));
        }
        oauth2RefreshTokenMapper.deleteById(refreshTokenDO.getId());
        oauth2AccessTokenRedisDAO.delete(refreshTokenDO.getRefreshToken());
        oauth2SessionRedisDAO.delete(refreshTokenDO.getRefreshToken());
    }

    private OAuth2AccessTokenDO convertToAccessToken(OAuth2RefreshTokenDO refreshTokenDO) {
        OAuth2AccessTokenDO accessTokenDO = BeanUtils.toBean(refreshTokenDO, OAuth2AccessTokenDO.class)
                .setAccessToken(refreshTokenDO.getRefreshToken());
        TenantUtils.execute(refreshTokenDO.getTenantId(),
                        () -> accessTokenDO.setUserInfo(buildUserInfo(refreshTokenDO.getUserId(), refreshTokenDO.getUserType())));
        return accessTokenDO;
    }

    /**
     * 加载用户信息，方便 {@link com.lingan.ucp.framework.security.core.LoginUser} 获取到昵称、部门等信息
     *
     * @param userId 用户编号
     * @param userType 用户类型
     * @return 用户信息
     */
    private Map<String, String> buildUserInfo(Long userId, Integer userType) {
        if (userId == null || userId <= 0) {
            return Collections.emptyMap();
        }
        if (userType.equals(UserTypeEnum.ADMIN.getValue())) {
            AdminUserDO user = adminUserService.getUser(userId);
            Map<String, String> userInfo = MapUtil.builder(LoginUser.INFO_KEY_NICKNAME, user.getNickname())
                    .put(LoginUser.INFO_KEY_DEPT_ID, StrUtil.toStringOrNull(user.getDeptId())).build();
            // 查询用户数据权限范围，用于前端/业务模块的数据权限过滤
            Integer dataScope = getUserDataScope(userId);
            if (dataScope != null) {
                userInfo.put(LoginUser.INFO_KEY_DATA_SCOPE, String.valueOf(dataScope));
            }
            return userInfo;
        } else if (userType.equals(UserTypeEnum.MEMBER.getValue())) {
            // 注意：目前 Member 暂时不读取，可以按需实现
            return Collections.emptyMap();
        }
        throw new IllegalArgumentException("未知用户类型：" + userType);
    }

    /**
     * 获取用户的数据权限范围
     * 合并用户所有角色的数据权限，取最高权限（ALL > DEPT_CUSTOM > DEPT_ONLY > DEPT_AND_CHILD > SELF）
     *
     * @param userId 用户ID
     * @return 数据权限范围，参见 {@link DataScopeEnum}
     */
    private Integer getUserDataScope(Long userId) {
        try {
            List<UserRoleDO> userRoles = userRoleMapper.selectListByUserId(userId);
            if (CollUtil.isEmpty(userRoles)) {
                return null;
            }
            Set<Long> roleIds = convertSet(userRoles, UserRoleDO::getRoleId);
            List<RoleDO> roles = roleService.getRoleListFromCache(roleIds);
            if (CollUtil.isEmpty(roles)) {
                return null;
            }
            Integer bestScope = null;
            for (RoleDO role : roles) {
                if (!CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())) {
                    continue;
                }
                if (role.getDataScope() == null) {
                    continue;
                }
                if (bestScope == null || role.getDataScope() < bestScope) {
                    bestScope = role.getDataScope();
                }
                if (DataScopeEnum.ALL.getScope().equals(bestScope)) {
                    break;
                }
            }
            return bestScope;
        } catch (Exception e) {
            return null;
        }
    }

    private static String generateAccessToken() {
        return IdUtil.fastSimpleUUID();
    }

    private static String generateRefreshToken() {
        return IdUtil.fastSimpleUUID();
    }

    @Override
    public Integer cleanRefreshToken(Integer exceedDay, Integer deleteLimit) {
        int count = 0;
        LocalDateTime expireDate = LocalDateTime.now().minusDays(exceedDay);
        // 循环删除，直到没有满足条件的数据
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            int deleteCount = oauth2RefreshTokenMapper.deleteByExpiresTimeLt(expireDate, deleteLimit);
            count += deleteCount;
            // 达到删除预期条数，说明到底了
            if (deleteCount < deleteLimit) {
                break;
            }
        }
        return count;
    }

    @Override
    public Integer cleanAccessToken(Integer exceedDay, Integer deleteLimit) {
        int count = 0;
        LocalDateTime expireDate = LocalDateTime.now().minusDays(exceedDay);
        // 循环删除，直到没有满足条件的数据
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            int deleteCount = oauth2AccessTokenMapper.deleteByExpiresTimeLt(expireDate, deleteLimit);
            count += deleteCount;
            // 达到删除预期条数，说明到底了
            if (deleteCount < deleteLimit) {
                break;
            }
        }
        return count;
    }
}
