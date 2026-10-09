package com.lingan.ucp.module.system.legacy.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.common.tenant.TenantContext;
import com.lingan.ucp.common.util.JwtUtil;
import com.lingan.ucp.module.system.legacy.dto.LoginDTO;
import com.lingan.ucp.module.system.legacy.entity.SysDepartment;
import com.lingan.ucp.module.system.legacy.entity.SysMenu;
import com.lingan.ucp.module.system.legacy.entity.SysTenant;
import com.lingan.ucp.module.system.legacy.entity.SysUser;
import com.lingan.ucp.module.system.legacy.mapper.SysDepartmentMapper;
import com.lingan.ucp.module.system.legacy.mapper.SysMenuMapper;
import com.lingan.ucp.module.system.legacy.mapper.SysTenantMapper;
import com.lingan.ucp.module.system.legacy.mapper.SysUserMapper;
import com.lingan.ucp.module.system.legacy.service.AuthService;
import com.lingan.ucp.module.system.legacy.service.OrganizationService;
import com.lingan.ucp.module.system.legacy.vo.LoginVO;
import com.lingan.ucp.module.system.legacy.vo.MenuVO;
import com.lingan.ucp.module.system.legacy.vo.TenantVO;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_TENANT_ID = "1";
    private static final long TOKEN_EXPIRE_DAYS = 1;
    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysTenantMapper tenantMapper;
    private final SysDepartmentMapper departmentMapper;
    private final OrganizationService organizationService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        // 1. 校验租户并获取租户ID
        String tenantId = validateAndGetTenantId(loginDTO.getTenantCode());

        // 2. 设置租户上下文
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }

        try {
            // 3. 查询并校验用户
            SysUser user = validateUser(loginDTO, tenantId);

            // 4. 确定最终租户ID
            tenantId = resolveFinalTenantId(user, tenantId);
            TenantContext.setTenantId(tenantId);

            // 5. 校验用户所属租户
            if (!StringUtils.hasText(loginDTO.getTenantCode())) {
                validateTenant(tenantMapper.selectById(tenantId));
            }

            // 6. 校验密码
            validatePassword(loginDTO.getPassword(), user.getPassword());

            // 7. 生成Token并缓存
            String token = jwtUtil.generateToken(user.getId(), user.getUsername(), tenantId);
            cacheToken(user.getId(), token);

            // 8. 构建用户信息并缓存
            UserInfoVO userInfoVO = buildUserInfoVO(user, tenantId);
            cacheUserInfo(user.getId(), userInfoVO);

            // 9. 组装返回数据
            return buildLoginVO(user, token, userInfoVO, tenantId);
        } catch (BusinessException e) {
            TenantContext.clear();
            throw e;
        }
    }

    @Override
    public UserInfoVO getUserInfo(String userId) {
        // 优先从 Redis 缓存读取
        String cached = redisTemplate.opsForValue().get("userInfo:" + userId);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, UserInfoVO.class);
            } catch (JsonProcessingException e) {
                log.warn("读取缓存用户信息失败, userId={}", userId, e);
            }
        }

        // 缓存未命中时查库
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return buildUserInfoVO(user, user.getTenantId());
    }

    @Override
    public void logout(String userId) {
        redisTemplate.delete("token:" + userId);
        redisTemplate.delete("userInfo:" + userId);
    }

    // ==================== 私有方法 ====================

    /**
     * 校验租户编码并返回租户ID
     */
    private String validateAndGetTenantId(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            return null;
        }
        SysTenant tenant = tenantMapper.selectByCode(tenantCode);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        validateTenant(tenant);
        return tenant.getId();
    }

    /**
     * 校验租户状态和过期时间
     */
    private void validateTenant(SysTenant tenant) {
        if (tenant == null) return;
        if (tenant.getStatus() != 1) {
            throw new BusinessException("租户已被禁用或过期，请联系管理员");
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("租户已过期，请联系管理员");
        }
    }

    /**
     * 查询并校验用户
     */
    private SysUser validateUser(LoginDTO loginDTO, String tenantId) {
        SysUser user = userMapper.selectByUsername(loginDTO.getUsername());
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }
        if (tenantId != null && !tenantId.equals(user.getTenantId())) {
            throw new BusinessException("用户不属于该租户");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }
        return user;
    }

    /**
     * 确定最终租户ID
     */
    private String resolveFinalTenantId(SysUser user, String tenantId) {
        if (tenantId != null) {
            return tenantId;
        }
        String userTenantId = user.getTenantId();
        return (userTenantId == null || "0".equals(userTenantId)) ? DEFAULT_TENANT_ID : userTenantId;
    }

    /**
     * 校验密码
     */
    private void validatePassword(String rawPassword, String encodedPassword) {
        String encrypted = DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8));
        if (!encrypted.equals(encodedPassword)) {
            throw new BusinessException("用户名或密码错误");
        }
    }

    /**
     * 缓存Token
     */
    private void cacheToken(String userId, String token) {
        redisTemplate.opsForValue().set("token:" + userId, token, TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);
    }

    /**
     * 缓存用户信息
     */
    private void cacheUserInfo(String userId, UserInfoVO userInfoVO) {
        try {
            redisTemplate.opsForValue().set(
                    "userInfo:" + userId,
                    objectMapper.writeValueAsString(userInfoVO),
                    TOKEN_EXPIRE_DAYS, TimeUnit.DAYS
            );
        } catch (JsonProcessingException e) {
            log.warn("缓存用户信息失败, userId={}", userId, e);
        }
    }

    /**
     * 构建用户信息VO
     */
    private UserInfoVO buildUserInfoVO(SysUser user, String tenantId) {
        UserInfoVO vo = new UserInfoVO();
        BeanUtils.copyProperties(user, vo);
        vo.setTenantId(tenantId);
        // 获取部门名称
        if (user.getDeptId() != null) {
            SysDepartment dept = departmentMapper.selectById(user.getDeptId());
            if (dept != null) {
                vo.setDeptName(dept.getDeptName());
            }
        }
        return vo;
    }

    /**
     * 构建登录响应VO
     */
    private LoginVO buildLoginVO(SysUser user, String token, UserInfoVO userInfoVO, String tenantId) {
        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setUserType(user.getUserType());
        loginVO.setDataScope(user.getDataScope());
        loginVO.setUserInfo(userInfoVO);
        loginVO.setMenus(buildFlatMenuList(menuMapper.selectMenusByUserId(user.getId())));

        // 租户信息
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant != null) {
            TenantVO tenantVO = new TenantVO();
            BeanUtils.copyProperties(tenant, tenantVO);
            loginVO.setTenantInfo(tenantVO);
        }

        // 组织树
        loginVO.setOrgTree(organizationService.getTree(tenantId));
        return loginVO;
    }

    /**
     * 构建扁平菜单列表
     */
    private List<MenuVO> buildFlatMenuList(List<SysMenu> menus) {
        return menus.stream()
                .map(menu -> {
                    MenuVO vo = new MenuVO();
                    BeanUtils.copyProperties(menu, vo);
                    return vo;
                })
                .sorted((m1, m2) -> m1.getSort() - m2.getSort())
                .collect(Collectors.toList());
    }
}
