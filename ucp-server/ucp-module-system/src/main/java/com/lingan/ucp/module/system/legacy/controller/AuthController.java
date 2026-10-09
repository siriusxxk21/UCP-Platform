//package com.lingan.ucp.module.system.legacy.controller;
//
//import com.lingan.ucp.framework.common.pojo.Result;
//import com.lingan.ucp.common.util.JwtUtil;
//import com.lingan.ucp.module.system.legacy.dto.LoginDTO;
//import com.lingan.ucp.module.system.legacy.service.AuthService;
//import com.lingan.ucp.module.system.legacy.vo.LoginVO;
//import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
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
