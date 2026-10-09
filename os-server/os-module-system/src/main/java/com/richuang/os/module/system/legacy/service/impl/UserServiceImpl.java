package com.richuang.os.module.system.legacy.service.impl;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.read.listener.PageReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.common.exception.BusinessException;
import com.richuang.os.common.util.DynamicQueryProcessor;
import com.richuang.os.module.system.legacy.dto.*;
import com.richuang.os.module.system.legacy.entity.SysDepartment;
import com.richuang.os.module.system.legacy.entity.SysOrganization;
import com.richuang.os.module.system.legacy.entity.SysUser;
import com.richuang.os.module.system.legacy.mapper.SysDepartmentMapper;
import com.richuang.os.module.system.legacy.mapper.SysOrganizationMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserRoleMapper;
import com.richuang.os.module.system.legacy.service.UserService;
import com.richuang.os.module.system.legacy.vo.UserPageVO;
import com.richuang.os.module.system.legacy.vo.UserVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    /**
     * 用户表字段映射：前端 field → 数据库列名
     */
    private static final Map<String, String> USER_FIELD_MAPPING = Map.ofEntries(
            Map.entry("username", "username"),
            Map.entry("nickname", "nickname"),
            Map.entry("phone", "phone"),
            Map.entry("email", "email"),
            Map.entry("status", "status"),
            Map.entry("userType", "user_type"),
            Map.entry("dataScope", "data_scope"),
            Map.entry("orgId", "org_id"),
            Map.entry("deptId", "dept_id"),
            Map.entry("post", "post"),
            Map.entry("createTime", "create_time"),
            Map.entry("updateTime", "update_time")
    );
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysOrganizationMapper organizationMapper;
    private final SysDepartmentMapper departmentMapper;

    @Override
    public UserPageVO list(UserQueryDTO queryDTO) {
        Page<SysUser> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        QueryWrapper<SysUser> wrapper = buildQueryWrapper(queryDTO);
        Page<SysUser> userPage = userMapper.selectPage(page, wrapper);
        return buildPageVO(userPage);
    }

    /**
     * 将简单查询条件追加到 QueryWrapper（动态条件模式下作为补充）
     */
    private void appendSimpleConditions(QueryWrapper<SysUser> wrapper, UserQueryDTO queryDTO) {
        if (StringUtils.hasText(queryDTO.getUsername())) {
            wrapper.like("username", queryDTO.getUsername());
        }
        if (StringUtils.hasText(queryDTO.getNickname())) {
            wrapper.like("nickname", queryDTO.getNickname());
        }
        if (StringUtils.hasText(queryDTO.getPhone())) {
            wrapper.like("phone", queryDTO.getPhone());
        }
        if (queryDTO.getStatus() != null) {
            wrapper.eq("status", queryDTO.getStatus());
        }
        if (queryDTO.getOrgId() != null) {
            wrapper.eq("org_id", queryDTO.getOrgId());
        }
        if (queryDTO.getDeptId() != null) {
            wrapper.eq("dept_id", queryDTO.getDeptId());
        }
    }

    /**
     * 构建查询条件（统一 list / export 使用）
     */
    private QueryWrapper<SysUser> buildQueryWrapper(UserQueryDTO queryDTO) {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();

        if (queryDTO.getConditions() != null && queryDTO.getConditions().getItems() != null
                && !queryDTO.getConditions().getItems().isEmpty()) {
            DynamicQueryProcessor.applyConditions(wrapper, queryDTO.getConditions(), USER_FIELD_MAPPING);
            appendSimpleConditions(wrapper, queryDTO);
        } else {
            if (StringUtils.hasText(queryDTO.getUsername())) {
                wrapper.like("username", queryDTO.getUsername());
            }
            if (StringUtils.hasText(queryDTO.getNickname())) {
                wrapper.like("nickname", queryDTO.getNickname());
            }
            if (StringUtils.hasText(queryDTO.getPhone())) {
                wrapper.like("phone", queryDTO.getPhone());
            }
            if (queryDTO.getStatus() != null) {
                wrapper.eq("status", queryDTO.getStatus());
            }
            if (queryDTO.getOrgId() != null) {
                wrapper.eq("org_id", queryDTO.getOrgId());
            }
            if (queryDTO.getDeptId() != null) {
                wrapper.eq("dept_id", queryDTO.getDeptId());
            }
        }
        wrapper.orderByDesc("create_time");
        return wrapper;
    }

    /**
     * 构建 UserPageVO
     */
    private UserPageVO buildPageVO(Page<SysUser> userPage) {
        List<UserVO> userVOList = userPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        fillOrgAndDeptNames(userVOList);
        UserPageVO result = new UserPageVO();
        result.setTotal(userPage.getTotal());
        result.setList(userVOList);
        return result;
    }

    @Override
    public UserVO getById(String id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        UserVO vo = convertToVO(user);
        fillOrgAndDeptNames(List.of(vo));
        return vo;
    }

    @Override
    public void add(UserDTO userDTO) {
        // 检查用户名是否已存在
        SysUser existUser = userMapper.selectByUsername(userDTO.getUsername());
        if (existUser != null) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        BeanUtils.copyProperties(userDTO, user);

        // 设置默认密码
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword(DigestUtils.md5DigestAsHex("123456".getBytes(StandardCharsets.UTF_8)));
        } else {
            user.setPassword(DigestUtils.md5DigestAsHex(user.getPassword().getBytes(StandardCharsets.UTF_8)));
        }

        userMapper.insert(user);
    }

    @Override
    public void update(UserDTO userDTO) {
        if (userDTO.getId() == null) {
            throw new BusinessException("用户ID不能为空");
        }

        SysUser existUser = userMapper.selectById(userDTO.getId());
        if (existUser == null) {
            throw new BusinessException("用户不存在");
        }

        // 如果修改了用户名，检查是否与其他用户冲突
        if (!existUser.getUsername().equals(userDTO.getUsername())) {
            SysUser userWithSameName = userMapper.selectByUsername(userDTO.getUsername());
            if (userWithSameName != null && !userWithSameName.getId().equals(userDTO.getId())) {
                throw new BusinessException("用户名已存在");
            }
        }

        SysUser user = new SysUser();
        BeanUtils.copyProperties(userDTO, user);

        // 密码处理：传了明文则加密更新；未传则保持原密码（MyBatis-Plus updateById 自动跳过 null 字段）
        if (StringUtils.hasText(userDTO.getPassword())) {
            user.setPassword(DigestUtils.md5DigestAsHex(userDTO.getPassword().getBytes(StandardCharsets.UTF_8)));
        }

        userMapper.updateById(user);
    }

    @Override
    public void delete(String id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        userMapper.deleteById(id);
    }

    @Override
    public void batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        userMapper.deleteBatchIds(ids);
    }

    @Override
    public void updateStatus(String id, Integer status) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser updateUser = new SysUser();
        updateUser.setId(id);
        updateUser.setStatus(status);
        userMapper.updateById(updateUser);
    }

    private UserVO convertToVO(SysUser user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }

    /**
     * 批量填充组织名称和部门名称
     */
    private void fillOrgAndDeptNames(List<UserVO> voList) {
        // 收集所有的 orgId
        List<String> orgIds = voList.stream()
                .map(UserVO::getOrgId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        // 收集所有的 deptId
        List<String> deptIds = voList.stream()
                .map(UserVO::getDeptId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        // 批量查询组织
        Map<String, String> orgNameMap = Map.of();
        if (!orgIds.isEmpty()) {
            List<SysOrganization> orgs = organizationMapper.selectBatchIds(orgIds);
            orgNameMap = orgs.stream()
                    .collect(Collectors.toMap(SysOrganization::getId, SysOrganization::getOrgName, (a, b) -> a));
        }

        // 批量查询部门
        Map<String, String> deptNameMap = Map.of();
        if (!deptIds.isEmpty()) {
            List<SysDepartment> depts = departmentMapper.selectBatchIds(deptIds);
            deptNameMap = depts.stream()
                    .collect(Collectors.toMap(SysDepartment::getId, SysDepartment::getDeptName, (a, b) -> a));
        }

        // 填充名称
        Map<String, String> finalOrgNameMap = orgNameMap;
        Map<String, String> finalDeptNameMap = deptNameMap;
        for (UserVO vo : voList) {
            if (vo.getOrgId() != null) {
                vo.setOrgName(finalOrgNameMap.get(vo.getOrgId()));
            }
            if (vo.getDeptId() != null) {
                vo.setDeptName(finalDeptNameMap.get(vo.getDeptId()));
            }
        }
    }

    @Override
    public List<SysUser> listUsersNotInRole(String roleId, String username) {
        // 获取已在角色中的用户ID
        List<String> existingUserIds = userRoleMapper.selectUserIdsByRoleId(roleId);

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        // 排除已在角色中的用户
        if (existingUserIds != null && !existingUserIds.isEmpty()) {
            wrapper.notIn(SysUser::getId, existingUserIds);
        }
        // 用户名模糊查询
        if (StringUtils.hasText(username)) {
            wrapper.like(SysUser::getUsername, username);
        }
        // 只查询未删除的用户
        wrapper.eq(SysUser::getDeleted, 0);
        wrapper.eq(SysUser::getStatus, 1);
        wrapper.orderByDesc(SysUser::getCreateTime);

        return userMapper.selectList(wrapper);
    }

    @Override
    public void updateProfile(UserProfileDTO profileDTO) {
        if (profileDTO.getId() == null) {
            throw new BusinessException("用户ID不能为空");
        }

        SysUser existUser = userMapper.selectById(profileDTO.getId());
        if (existUser == null) {
            throw new BusinessException("用户不存在");
        }

        // 只更新个人信息字段
        SysUser user = new SysUser();
        user.setId(profileDTO.getId());
        user.setNickname(profileDTO.getNickname());
        user.setAvatar(profileDTO.getAvatar());
        user.setEmail(profileDTO.getEmail());
        user.setPhone(profileDTO.getPhone());

        userMapper.updateById(user);
    }

    @Override
    public void updatePassword(String userId, PasswordDTO passwordDTO) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 验证旧密码
        String oldPasswordMd5 = DigestUtils.md5DigestAsHex(passwordDTO.getOldPassword().getBytes(StandardCharsets.UTF_8));
        if (!user.getPassword().equals(oldPasswordMd5)) {
            throw new BusinessException("旧密码错误");
        }

        // 更新新密码
        String newPasswordMd5 = DigestUtils.md5DigestAsHex(passwordDTO.getNewPassword().getBytes(StandardCharsets.UTF_8));
        SysUser updateUser = new SysUser();
        updateUser.setId(userId);
        updateUser.setPassword(newPasswordMd5);
        userMapper.updateById(updateUser);
    }

    @Override
    public List<UserVO> listByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SysUser> users = userMapper.selectBatchIds(ids);
        List<UserVO> voList = users.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        fillOrgAndDeptNames(voList);
        return voList;
    }

    @Override
    public SysUser getLightById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname, SysUser::getAvatar)
                .eq(SysUser::getId, id)
                .last("LIMIT 1"));
    }

    @Override
    public List<SysUser> listLightByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> distinctIds = ids.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname, SysUser::getAvatar)
                .in(SysUser::getId, distinctIds));
    }

    // ==================== 导入 / 导出 / 重置密码 ====================

    @Override
    public void exportUsers(UserQueryDTO queryDTO, HttpServletResponse response) {
        try {
            // 1. 查询全部数据（不分页）
            QueryWrapper<SysUser> wrapper = buildQueryWrapper(queryDTO);
            List<SysUser> users = userMapper.selectList(wrapper);

            // 2. 转为 Excel VO
            List<UserExcelVO> excelData = users.stream().map(user -> {
                UserVO vo = convertToVO(user);
                UserExcelVO excelVO = new UserExcelVO();
                BeanUtils.copyProperties(vo, excelVO);
                excelVO.setUserTypeLabel(userTypeLabel(user.getUserType()));
                excelVO.setDataScopeLabel(dataScopeLabel(user.getDataScope()));
                excelVO.setStatusLabel(user.getStatus() != null && user.getStatus() == 1 ? "启用" : "禁用");
                return excelVO;
            }).collect(Collectors.toList());
            fillOrgAndDeptNamesForExcel(excelData, users);

            // 3. 写入响应流
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("用户列表", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");
            FastExcelFactory.write(response.getOutputStream(), UserExcelVO.class)
                    .sheet("用户列表")
                    .doWrite(excelData);
        } catch (IOException e) {
            log.error("导出用户列表失败", e);
            throw new BusinessException("导出失败: " + e.getMessage());
        }
    }

    @Override
    public void downloadImportTemplate(HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("用户导入模板", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");
            FastExcelFactory.write(response.getOutputStream(), UserImportDTO.class)
                    .sheet("导入模板")
                    .doWrite(Collections.emptyList());
        } catch (IOException e) {
            log.error("下载导入模板失败", e);
            throw new BusinessException("下载模板失败: " + e.getMessage());
        }
    }

    @Override
    public int importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("导入文件不能为空");
        }
        List<String> errorList = new ArrayList<>();
        List<UserDTO> successList = new ArrayList<>();

        try {
            FastExcelFactory.read(file.getInputStream(), UserImportDTO.class,
                    new PageReadListener<UserImportDTO>(dataList -> {
                        for (int i = 0; i < dataList.size(); i++) {
                            UserImportDTO dto = dataList.get(i);
                            int rowNum = successList.size() + errorList.size() + i + 1;
                            try {
                                if (!StringUtils.hasText(dto.getUsername())) {
                                    errorList.add("第" + rowNum + "行：用户名不能为空");
                                    continue;
                                }
                                UserDTO userDTO = new UserDTO();
                                userDTO.setUsername(dto.getUsername());
                                userDTO.setNickname(dto.getNickname());
                                userDTO.setPhone(dto.getPhone());
                                userDTO.setEmail(dto.getEmail());
                                userDTO.setPost(dto.getPost());
                                userDTO.setUserType(parseUserType(dto.getUserTypeLabel()));
                                userDTO.setStatus(parseStatus(dto.getStatusLabel()));
                                // 默认密码
                                userDTO.setPassword("123456");
                                userDTO.setStatus(userDTO.getStatus() != null ? userDTO.getStatus() : 1);
                                userDTO.setUserType(userDTO.getUserType() != null ? userDTO.getUserType() : 1);
                                successList.add(userDTO);
                            } catch (Exception e) {
                                errorList.add("第" + rowNum + "行：" + e.getMessage());
                            }
                        }
                    })).sheet().doRead();
        } catch (IOException e) {
            log.error("读取导入文件失败", e);
            throw new BusinessException("读取文件失败: " + e.getMessage());
        }

        // 批量校验用户名唯一性（一次查询替代 N 次 add 内部校验）
        List<String> usernames = successList.stream().map(UserDTO::getUsername).collect(Collectors.toList());
        Set<String> existUsernames = Set.of();
        if (!usernames.isEmpty()) {
            existUsernames = userMapper.selectList(
                            new LambdaQueryWrapper<SysUser>().in(SysUser::getUsername, usernames))
                    .stream().map(SysUser::getUsername).collect(Collectors.toSet());
        }
        Set<String> finalExistUsernames = existUsernames;

        // 批量插入
        int successCount = 0;
        for (UserDTO userDTO : successList) {
            try {
                if (finalExistUsernames.contains(userDTO.getUsername())) {
                    errorList.add("用户[" + userDTO.getUsername() + "]已存在");
                    continue;
                }
                SysUser user = new SysUser();
                BeanUtils.copyProperties(userDTO, user);
                String rawPassword = StringUtils.hasText(userDTO.getPassword()) ? userDTO.getPassword() : "123456";
                user.setPassword(DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8)));
                if (user.getStatus() == null) user.setStatus(1);
                if (user.getUserType() == null) user.setUserType(1);
                userMapper.insert(user);
                successCount++;
            } catch (Exception e) {
                errorList.add("用户[" + userDTO.getUsername() + "]导入失败：" + e.getMessage());
            }
        }

        if (!errorList.isEmpty()) {
            log.warn("用户导入部分失败：{}", errorList);
            throw new BusinessException("导入完成，成功" + successCount + "条，失败" + errorList.size() + "条。首条错误：" + errorList.get(0));
        }
        return successCount;
    }

    @Override
    public void resetPassword(String id, String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!StringUtils.hasText(newPassword)) {
            throw new BusinessException("新密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException("密码长度不能少于6位");
        }
        SysUser updateUser = new SysUser();
        updateUser.setId(id);
        updateUser.setPassword(DigestUtils.md5DigestAsHex(newPassword.getBytes(StandardCharsets.UTF_8)));
        userMapper.updateById(updateUser);
    }

    // ==================== 导入导出工具方法 ====================

    private String userTypeLabel(Integer userType) {
        if (userType == null) return "普通用户";
        return switch (userType) {
            case 2 -> "租户管理员";
            case 3 -> "系统管理员";
            default -> "普通用户";
        };
    }

    private String dataScopeLabel(Integer dataScope) {
        if (dataScope == null) return "全部数据";
        return switch (dataScope) {
            case 2 -> "本部门数据";
            case 3 -> "本部门及以下";
            case 4 -> "仅本人数据";
            default -> "全部数据";
        };
    }

    private Integer parseUserType(String label) {
        if (!StringUtils.hasText(label)) return 1;
        return switch (label.trim()) {
            case "租户管理员" -> 2;
            case "系统管理员" -> 3;
            default -> 1;
        };
    }

    private Integer parseStatus(String label) {
        if (!StringUtils.hasText(label)) return 1;
        return "禁用".equals(label.trim()) ? 0 : 1;
    }

    /**
     * 为 Excel 数据填充组织和部门名称
     */
    private void fillOrgAndDeptNamesForExcel(List<UserExcelVO> excelData, List<SysUser> users) {
        // 复用已有的 VO 填充逻辑
        List<UserVO> voList = users.stream().map(this::convertToVO).collect(Collectors.toList());
        fillOrgAndDeptNames(voList);
        for (int i = 0; i < excelData.size() && i < voList.size(); i++) {
            excelData.get(i).setOrgName(voList.get(i).getOrgName());
            excelData.get(i).setDeptName(voList.get(i).getDeptName());
        }
    }
}
