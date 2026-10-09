package com.richuang.os.framework.web.core.handler;

import com.richuang.os.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.richuang.os.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.richuang.os.framework.web.core.util.WebFrameworkUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static com.richuang.os.framework.apilog.core.util.ApiLogSanitizeUtils.MASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 缺陷回归：未知异常时错误日志落库（infra_api_error_log.request_params）不得含明文密码、令牌
 */
class GlobalExceptionHandlerErrorLogSanitizeTest {

    private final ApiErrorLogCommonApi apiErrorLogApi = mock(ApiErrorLogCommonApi.class);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler("os-test", apiErrorLogApi);

    @Test
    void unknownExceptionDuringLogin_persistedParamsAreMasked() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/system/auth/login");
        WebFrameworkUtils.setLoginUserType(request, 2);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"username\":\"admin\",\"password\":\"Login-Plain-Pwd-301\"}".getBytes(StandardCharsets.UTF_8));
        request.setQueryString("accessToken=Access-Plain-302&tenantId=1");
        request.addParameter("accessToken", "Access-Plain-302");
        request.addParameter("tenantId", "1");

        handler.defaultExceptionHandler(request, new IllegalStateException("boom"));

        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(apiErrorLogApi).createApiErrorLogAsync(captor.capture());
        String params = captor.getValue().getRequestParams();
        assertThat(params).doesNotContain("Login-Plain-Pwd-301", "Access-Plain-302");
        assertThat(params).contains(MASK, "admin", "\"tenantId\":\"1\"");
    }

}
