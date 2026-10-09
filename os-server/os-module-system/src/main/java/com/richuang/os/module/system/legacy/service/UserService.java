package com.richuang.os.module.system.legacy.service;

import com.richuang.os.module.system.legacy.dto.PasswordDTO;
import com.richuang.os.module.system.legacy.dto.UserDTO;
import com.richuang.os.module.system.legacy.dto.UserProfileDTO;
import com.richuang.os.module.system.legacy.dto.UserQueryDTO;
import com.richuang.os.module.system.legacy.entity.SysUser;
import com.richuang.os.module.system.legacy.vo.UserPageVO;
import com.richuang.os.module.system.legacy.vo.UserVO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UserService {

    UserPageVO list(UserQueryDTO queryDTO);

    UserVO getById(String id);

    void add(UserDTO userDTO);

    void update(UserDTO userDTO);

    void delete(String id);

    void batchDelete(List<String> ids);

    void updateStatus(String id, Integer status);

    /**
     * 查询不在指定角色中的用户列表
     */
    List<SysUser> listUsersNotInRole(String roleId, String username);

    /**
     * 更新用户个人信息（用户自己修改）
     */
    void updateProfile(UserProfileDTO profileDTO);

    /**
     * 修改密码
     */
    void updatePassword(String userId, PasswordDTO passwordDTO);

    /**
     * 根据ID列表查询用户
     */
    List<UserVO> listByIds(List<String> ids);

    /**
     * 根据 ID 查询轻量用户信息，不填充组织/部门等扩展信息。
     */
    SysUser getLightById(String id);

    /**
     * 根据 ID 列表批量查询轻量用户信息，不填充组织/部门等扩展信息。
     */
    List<SysUser> listLightByIds(List<String> ids);

    /**
     * 导出用户列表到 Excel
     */
    void exportUsers(UserQueryDTO queryDTO, HttpServletResponse response);

    /**
     * 下载用户导入模板
     */
    void downloadImportTemplate(HttpServletResponse response);

    /**
     * 从 Excel 导入用户
     *
     * @return 导入成功数量
     */
    int importUsers(MultipartFile file);

    /**
     * 管理员重置用户密码
     */
    void resetPassword(String id, String newPassword);
}
