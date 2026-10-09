//package com.richuang.os.module.system.legacy.controller;
//
//import com.richuang.os.framework.common.pojo.Result;
//import com.richuang.os.module.system.legacy.dto.*;
//import com.richuang.os.module.system.legacy.entity.SysUser;
//import com.richuang.os.module.system.legacy.service.UserService;
//import com.richuang.os.module.system.legacy.vo.UserPageVO;
//import com.richuang.os.module.system.legacy.vo.UserVO;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.util.List;
//import java.util.Map;
//
//@RestController
//@RequestMapping("/api/system/user")
//@RequiredArgsConstructor
//public class UserController {
//
//    private final UserService userService;
//
//    /**
//     * 更新当前用户个人信息
//     */
//    @PutMapping("/profile")
//    public Result<Void> updateProfile(@Valid @RequestBody UserProfileDTO profileDTO, HttpServletRequest request) {
//        String userId = (String) request.getAttribute("userId");
//        profileDTO.setId(userId);
//        userService.updateProfile(profileDTO);
//        return Result.success();
//    }
//
//    /**
//     * 获取当前用户详细信息
//     */
//    @GetMapping("/profile")
//    public Result<UserVO> getProfile(HttpServletRequest request) {
//        String userId = (String) request.getAttribute("userId");
//        UserVO userVO = userService.getById(userId);
//        return Result.success(userVO);
//    }
//
//    /**
//     * 修改密码
//     */
//    @PutMapping("/password")
//    public Result<Void> updatePassword(@Valid @RequestBody PasswordDTO passwordDTO, HttpServletRequest request) {
//        String userId = (String) request.getAttribute("userId");
//        userService.updatePassword(userId, passwordDTO);
//        return Result.success();
//    }
//
//    /**
//     * 分页查询用户列表
//     * <p>
//     * 使用 POST + @RequestBody 接收查询参数，支持嵌套的动态检索条件（conditions）
//     * 前端 LaDynamicSearch 输出的 conditions 为递归树型结构，GET 请求无法传递
//     */
//    @PostMapping("/list")
//    public Result<UserPageVO> list(@RequestBody UserQueryDTO queryDTO) {
//        UserPageVO pageVO = userService.list(queryDTO);
//        return Result.success(pageVO);
//    }
//
//    @GetMapping("/{id}")
//    public Result<UserVO> getById(@PathVariable String id) {
//        UserVO userVO = userService.getById(id);
//        return Result.success(userVO);
//    }
//
//    @PostMapping
//    public Result<Void> add(@Valid @RequestBody UserDTO userDTO) {
//        userService.add(userDTO);
//        return Result.success();
//    }
//
//    @PutMapping
//    public Result<Void> update(@Valid @RequestBody UserDTO userDTO) {
//        userService.update(userDTO);
//        return Result.success();
//    }
//
//    @DeleteMapping("/{id}")
//    public Result<Void> delete(@PathVariable String id) {
//        userService.delete(id);
//        return Result.success();
//    }
//
//    @DeleteMapping("/batch")
//    public Result<Void> batchDelete(@RequestBody Map<String, List<String>> body) {
//        List<String> ids = body.get("ids");
//        userService.batchDelete(ids);
//        return Result.success();
//    }
//
//    @PutMapping("/{id}/status")
//    public Result<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, Integer> body) {
//        Integer status = body.get("status");
//        userService.updateStatus(id, status);
//        return Result.success();
//    }
//
//    /**
//     * 查询不在指定角色中的用户
//     */
//    @GetMapping("/not-in-role/{roleId}")
//    public Result<List<SysUser>> listUsersNotInRole(
//            @PathVariable String roleId,
//            @RequestParam(required = false) String username) {
//        List<SysUser> users = userService.listUsersNotInRole(roleId, username);
//        return Result.success(users);
//    }
//
//    /**
//     * 根据ID列表查询用户
//     */
//    @PostMapping("/list-by-ids")
//    public Result<List<UserVO>> listByIds(@RequestBody List<String> ids) {
//        List<UserVO> users = userService.listByIds(ids);
//        return Result.success(users);
//    }
//
//    // ==================== 导入 / 导出 / 重置密码 ====================
//
//    /**
//     * 导出用户列表到 Excel
//     */
//    @GetMapping("/export")
//    public void exportUsers(UserQueryDTO queryDTO, HttpServletResponse response) {
//        userService.exportUsers(queryDTO, response);
//    }
//
//    /**
//     * 下载用户导入模板
//     */
//    @GetMapping("/import-template")
//    public void downloadImportTemplate(HttpServletResponse response) {
//        userService.downloadImportTemplate(response);
//    }
//
//    /**
//     * 从 Excel 导入用户
//     */
//    @PostMapping("/import")
//    public Result<Integer> importUsers(@RequestParam("file") MultipartFile file) {
//        int count = userService.importUsers(file);
//        return Result.success(count);
//    }
//
//    /**
//     * 管理员重置用户密码
//     */
//    @PutMapping("/{id}/reset-password")
//    public Result<Void> resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordDTO dto) {
//        userService.resetPassword(id, dto.getNewPassword());
//        return Result.success();
//    }
//}
