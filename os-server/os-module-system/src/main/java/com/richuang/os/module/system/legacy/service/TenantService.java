package com.richuang.os.module.system.legacy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.module.system.legacy.dto.TenantDTO;
import com.richuang.os.module.system.legacy.dto.TenantQueryDTO;
import com.richuang.os.module.system.legacy.entity.SysTenant;
import com.richuang.os.module.system.legacy.vo.TenantVO;

import java.util.List;

/**
 * 租户服务接口
 */
public interface TenantService {

    /**
     * 分页查询租户列表
     */
    Page<TenantVO> list(TenantQueryDTO query);

    /**
     * 查询所有有效租户
     */
    List<TenantVO> listAllValid();

    /**
     * 根据ID查询租户
     */
    TenantVO getById(String id);

    /**
     * 根据编码查询租户
     */
    SysTenant getByCode(String tenantCode);

    /**
     * 创建租户
     */
    String create(TenantDTO dto);

    /**
     * 更新租户
     */
    void update(TenantDTO dto);

    /**
     * 删除租户
     */
    void delete(String id);

    /**
     * 修改租户状态
     */
    void updateStatus(String id, Integer status);

    /**
     * 检查租户编码是否存在
     */
    boolean checkCodeExists(String tenantCode, String excludeId);
}
