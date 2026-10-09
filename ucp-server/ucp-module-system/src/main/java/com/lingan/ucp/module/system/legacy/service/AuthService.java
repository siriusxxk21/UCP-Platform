package com.lingan.ucp.module.system.legacy.service;

import com.lingan.ucp.module.system.legacy.dto.LoginDTO;
import com.lingan.ucp.module.system.legacy.vo.LoginVO;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO);

    UserInfoVO getUserInfo(String userId);

    void logout(String userId);
}
