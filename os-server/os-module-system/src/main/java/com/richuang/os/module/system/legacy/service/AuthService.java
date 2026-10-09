package com.richuang.os.module.system.legacy.service;

import com.richuang.os.module.system.legacy.dto.LoginDTO;
import com.richuang.os.module.system.legacy.vo.LoginVO;
import com.richuang.os.module.system.legacy.vo.UserInfoVO;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO);

    UserInfoVO getUserInfo(String userId);

    void logout(String userId);
}
