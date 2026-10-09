package com.lingan.ucp.module.system.legacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.module.system.legacy.dto.TenantDTO;
import com.lingan.ucp.module.system.legacy.dto.TenantQueryDTO;
import com.lingan.ucp.module.system.legacy.entity.SysMenu;
import com.lingan.ucp.module.system.legacy.entity.SysRole;
import com.lingan.ucp.module.system.legacy.entity.SysTenant;
import com.lingan.ucp.module.system.legacy.entity.SysUser;
import com.lingan.ucp.module.system.legacy.mapper.*;
import com.lingan.ucp.module.system.legacy.service.TenantService;
import com.lingan.ucp.module.system.legacy.vo.TenantVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 租户服务实现类
 */
@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final SysTenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    @Override
    public Page<TenantVO> list(TenantQueryDTO query) {
        Page<SysTenant> page = new Page<>(query.getPageNum(), query.getPageSize());
        page = tenantMapper.selectTenantPage(page, query);

        List<TenantVO> voList = page.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        Page<TenantVO> voPage = new Page<>();
        BeanUtils.copyProperties(page, voPage);
        voPage.setRecords(voList);

        return voPage;
    }

    @Override
    public List<TenantVO> listAllValid() {
        List<SysTenant> list = tenantMapper.selectAllValid();
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public TenantVO getById(String id) {
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null || tenant.getDeleted() == 1) {
            throw new BusinessException("租户不存在");
        }
        return convertToVO(tenant);
    }

    @Override
    public SysTenant getByCode(String tenantCode) {
        return tenantMapper.selectByCode(tenantCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(TenantDTO dto) {
        // 1. 检查租户编码是否已存在
        if (checkCodeExists(dto.getTenantCode(), null)) {
            throw new BusinessException("租户编码已存在");
        }

        // 2. 创建租户
        SysTenant tenant = new SysTenant();
        BeanUtils.copyProperties(dto, tenant);
        tenant.setId(null);
        tenant.setDeleted(0);
        tenant.setCreateTime(LocalDateTime.now());
        tenant.setUpdateTime(LocalDateTime.now());

        // 设置默认值
        if (tenant.getIsolationStrategy() == null) {
            tenant.setIsolationStrategy("row");
        }
        if (tenant.getStatus() == null) {
            tenant.setStatus(1);
        }
        if (tenant.getMaxUserCount() == null) {
            tenant.setMaxUserCount(100);
        }
        if (tenant.getMaxStorageSize() == null) {
            tenant.setMaxStorageSize(10737418240L); // 10GB
        }
        // 空字符串转null，避免唯一键冲突（MySQL唯一键对NULL不生效）
        if (!StringUtils.hasText(tenant.getDomain())) {
            tenant.setDomain(null);
        }
        if (!StringUtils.hasText(tenant.getLogoUrl())) {
            tenant.setLogoUrl(null);
        }

        tenantMapper.insert(tenant);
        String tenantId = tenant.getId();
        log.info("创建租户成功: {}, tenantId: {}", tenant.getTenantName(), tenantId);

        // 3. 创建租户管理员角色
        SysRole adminRole = new SysRole();
        adminRole.setRoleName("租户管理员");
        adminRole.setRoleCode("TENANT_ADMIN");
        adminRole.setTenantId(tenantId);
        adminRole.setDescription("租户默认管理员角色");
        adminRole.setStatus(1);
        adminRole.setDeleted(0);
        adminRole.setCreateTime(LocalDateTime.now());
        adminRole.setUpdateTime(LocalDateTime.now());
        roleMapper.insert(adminRole);
        log.info("创建租户管理员角色成功: roleId: {}", adminRole.getId());

        // 4. 为管理员角色分配菜单权限（排除租户管理菜单）
        List<SysMenu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>()
                        .eq(SysMenu::getStatus, 1)
                        .eq(SysMenu::getDeleted, 0)
                        .notLike(SysMenu::getPath, "/system/tenant") // 排除租户管理菜单
        );
        for (SysMenu menu : menus) {
            roleMenuMapper.insertRoleMenu(adminRole.getId(), menu.getId());
        }
        log.info("分配菜单权限成功: 共 {} 个菜单", menus.size());

        // 5. 创建管理员用户
        SysUser admin = new SysUser();
        admin.setUsername(dto.getAdminUsername());
        admin.setPassword(encryptPassword(dto.getAdminPassword()));
        admin.setNickname(StringUtils.hasText(dto.getAdminNickname()) ? dto.getAdminNickname() : "管理员");
        admin.setPhone(dto.getAdminPhone());
        admin.setEmail(dto.getAdminEmail());
        admin.setTenantId(tenantId);
        admin.setUserType(2); // 租户管理员
        admin.setDataScope(1); // 全部数据权限
        admin.setStatus(1);
        admin.setDeleted(0);
        admin.setCreateTime(LocalDateTime.now());
        admin.setUpdateTime(LocalDateTime.now());
        userMapper.insert(admin);
        log.info("创建管理员用户成功: {}", admin.getUsername());

        // 6. 关联用户和角色
        userRoleMapper.batchInsert(adminRole.getId(), List.of(admin.getId()));
        log.info("关联用户角色成功");

        return tenantId;
    }

    /**
     * 密码加密（MD5）
     */
    private String encryptPassword(String password) {
        return DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(TenantDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("租户ID不能为空");
        }

        SysTenant existing = tenantMapper.selectById(dto.getId());
        if (existing == null || existing.getDeleted() == 1) {
            throw new BusinessException("租户不存在");
        }

        // 检查编码是否冲突
        if (!existing.getTenantCode().equals(dto.getTenantCode())
                && checkCodeExists(dto.getTenantCode(), dto.getId())) {
            throw new BusinessException("租户编码已存在");
        }

        SysTenant tenant = new SysTenant();
        BeanUtils.copyProperties(dto, tenant);
        tenant.setUpdateTime(LocalDateTime.now());

        // 空字符串转null，避免唯一键冲突
        if (!StringUtils.hasText(tenant.getDomain())) {
            tenant.setDomain(null);
        }
        if (!StringUtils.hasText(tenant.getLogoUrl())) {
            tenant.setLogoUrl(null);
        }

        tenantMapper.updateById(tenant);
        log.info("更新租户成功: {}", tenant.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null || tenant.getDeleted() == 1) {
            throw new BusinessException("租户不存在");
        }

        tenantMapper.deleteById(id);
        log.info("删除租户成功: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, Integer status) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setStatus(status);
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
    }

    @Override
    public boolean checkCodeExists(String tenantCode, String excludeId) {
        if (!StringUtils.hasText(tenantCode)) {
            return false;
        }
        SysTenant tenant = tenantMapper.selectByCode(tenantCode);
        if (tenant == null) {
            return false;
        }
        return excludeId == null || !excludeId.equals(tenant.getId());
    }

    /**
     * 转换为VO
     */
    private TenantVO convertToVO(SysTenant tenant) {
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(tenant, vo);

        // 设置类型名称
        if (tenant.getTenantType() != null) {
            switch (tenant.getTenantType()) {
                case 1:
                    vo.setTenantTypeName("企业");
                    break;
                case 2:
                    vo.setTenantTypeName("个人");
                    break;
                case 3:
                    vo.setTenantTypeName("试用");
                    break;
                default:
                    vo.setTenantTypeName("未知");
            }
        }

        // 设置状态名称
        if (tenant.getStatus() != null) {
            switch (tenant.getStatus()) {
                case 0:
                    vo.setStatusName("禁用");
                    break;
                case 1:
                    vo.setStatusName("启用");
                    break;
                case 2:
                    vo.setStatusName("过期");
                    break;
                default:
                    vo.setStatusName("未知");
            }
        }

        return vo;
    }
}
