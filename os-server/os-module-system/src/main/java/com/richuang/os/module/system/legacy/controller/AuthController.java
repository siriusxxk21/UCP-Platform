//package com.richuang.os.module.system.legacy.controller;
//
//import com.richuang.os.framework.common.pojo.Result;
//import com.richuang.os.common.util.JwtUtil;
//import com.richuang.os.module.system.legacy.dto.LoginDTO;
//import com.richuang.os.module.system.legacy.service.AuthService;
//import com.richuang.os.module.system.legacy.vo.LoginVO;
//import com.richuang.os.module.system.legacy.vo.UserInfoVO;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import org.springframework.web.bind.annotation.*;
//
//@RestController
//@RequestMapping("/api/auth")
//@RequiredArgsConstructor
//public class AuthController {
//
//    private final AuthService authService;
//    private final JwtUtil jwtUtil;
//
//    @PostMapping("/login")
//    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
//        LoginVO loginVO = authService.login(loginDTO);
//        return Result.success(loginVO);
//    }
//
//    @GetMapping("/info")
//    public Result<UserInfoVO> getUserInfo(HttpServletRequest request) {
//        String userId = (String) request.getAttribute("userId");
//        UserInfoVO userInfo = authService.getUserInfo(userId);
//        return Result.success(userInfo);
//    }
//
//    @PostMapping("/logout")
//    public Result<Void> logout(HttpServletRequest request) {
//        String userId = (String) request.getAttribute("userId");
//        authService.logout(userId);
//        return Result.success();
//    }
//}
