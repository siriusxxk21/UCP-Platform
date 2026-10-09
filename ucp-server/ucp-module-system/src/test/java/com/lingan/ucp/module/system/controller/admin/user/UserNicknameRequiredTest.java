package com.lingan.ucp.module.system.controller.admin.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lingan.ucp.framework.web.core.handler.GlobalExceptionHandler;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import com.lingan.ucp.module.system.service.user.AdminUserService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

/**
 * 新建 / 编辑用户时昵称为空：以前不校验，空昵称落到数据库（system_users.nickname 非空列）报错，页面只显示「系统异常」。
 * 现在请求在进服务层之前就被拒，返回可读的业务错误（code 400「请求参数不正确:请填写用户昵称」），不是 500。
 */
class UserNicknameRequiredTest {
    private AdminUserService users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(AdminUserService.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler("os-test", null))
                .build();
    }

    @Test
    void createWithBlankNicknameIsRejectedReadablyBeforeService() throws Exception {
        for (String nickname : new String[] {"\"\"", "\"   \"", "null"}) {
            mvc.perform(post("/system/user/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"yamada01\",\"password\":\"Init12345\",\"nickname\":" + nickname + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.msg").value("请求参数不正确:请填写用户昵称"));
        }
        // 前端把空串清成 undefined 后提交：字段整个缺失也要拦
        mvc.perform(post("/system/user/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"yamada01\",\"password\":\"Init12345\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求参数不正确:请填写用户昵称"));
        verify(users, never()).createUser(any());
    }

    @Test
    void updateWithBlankNicknameIsRejectedReadablyBeforeService() throws Exception {
        mvc.perform(put("/system/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1024,\"username\":\"yamada01\",\"nickname\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求参数不正确:请填写用户昵称"));
        verify(users, never()).updateUser(any());
    }

    @Test
    void createWithNicknameStillReachesService() throws Exception {
        when(users.createUser(any())).thenReturn(7L);
        mvc.perform(post("/system/user/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"yamada01\",\"password\":\"Init12345\",\"nickname\":\"山田\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(7));
        verify(users).createUser(any());
    }

    /** 服务层（导入用户时也走同一套 Bean 校验）看到的是同一条约束。 */
    @Test
    void beanValidationReportsTheNicknameConstraint() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        UserSaveReqVO request = new UserSaveReqVO();
        request.setUsername("yamada01");
        request.setPassword("Init12345");
        request.setNickname(" ");
        Set<ConstraintViolation<UserSaveReqVO>> violations = validator.validate(request);
        assertEquals(1, violations.size(), violations::toString);
        ConstraintViolation<UserSaveReqVO> only = violations.iterator().next();
        assertEquals("nickname", only.getPropertyPath().toString());
        assertEquals("请填写用户昵称", only.getMessage());
        request.setNickname("山田");
        assertTrue(validator.validate(request).isEmpty());
    }
}
