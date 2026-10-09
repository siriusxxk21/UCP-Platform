package com.richuang.os.module.drive.service.permission;

import cn.hutool.core.collection.CollUtil;

import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 网盘授权主体解析器
 *
 * <p>把用户换算成参与授权判定的部门链。刻意不使用角色的数据范围（data_scope）： 数据范围是角色级配置，若直接采用会让拥有全部数据权限的账号看到所有部门授权，
 * 等于把授权判定耦合到角色配置上。这里只认用户主部门及其祖先部门。
 *
 * @author os
 */
@Component
public class DriveSubjectResolver {

    /** 部门链最大层级，避免历史脏数据形成环时死循环 */
    private static final int MAX_DEPT_DEPTH = 50;

    @Resource private AdminUserApi adminUserApi;
    @Resource private DeptApi deptApi;

    /** 获得用户主部门编号，用户不存在或未设置部门时返回 null */
    public Long getPrimaryDeptId(Long userId) {
        if (userId == null) {
            return null;
        }
        AdminUserRespDTO user = adminUserApi.getUser(userId);
        return user != null ? user.getDeptId() : null;
    }

    /** 获得用户主部门及其全部祖先部门，顺序为自身部门在前 */
    public Set<Long> getUserDeptChain(Long userId) {
        Set<Long> chain = new LinkedHashSet<>();
        Long deptId = getPrimaryDeptId(userId);
        for (int i = 0; i < MAX_DEPT_DEPTH && deptId != null && deptId > 0; i++) {
            if (!chain.add(deptId)) {
                break;
            }
            DeptRespDTO dept = deptApi.getDept(deptId);
            deptId = dept != null ? dept.getParentId() : null;
        }
        return chain;
    }

    /** 批量获得用户主部门编号 */
    public Set<Long> getUserIdsOfDept(Long deptId) {
        if (deptId == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(
                CollUtil.emptyIfNull(
                                adminUserApi.getUserListByDeptIds(CollUtil.newArrayList(deptId)))
                        .stream()
                        .map(AdminUserRespDTO::getId)
                        .toList());
    }
}
