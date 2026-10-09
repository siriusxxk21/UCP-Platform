package com.richuang.os.framework.apilog.core.filter;

import com.richuang.os.framework.apilog.core.annotation.ApiAccessLog;
import com.richuang.os.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import com.richuang.os.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.web.config.WebProperties;
import com.richuang.os.framework.web.core.util.WebFrameworkUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.richuang.os.framework.apilog.core.interceptor.ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD;
import static com.richuang.os.framework.apilog.core.util.ApiLogSanitizeUtils.MASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 缺陷回归：访问日志落库（infra_api_access_log.request_params / response_body）不得含明文密码、令牌
 */
class ApiAccessLogFilterTest {

    private final ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);

    private final ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "os-test", apiAccessLogApi);

    @Test
    void loginRequest_persistedParamsAreMasked() throws Exception {
        MockHttpServletRequest request = newRequest("/admin-api/system/auth/login");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(("{\"username\":\"admin\",\"password\":\"Login-Plain-Pwd-201\","
                + "\"extra\":{\"newPassword\":\"Pwd-Plain-202\"}}").getBytes(StandardCharsets.UTF_8));
        request.setQueryString("token=Token-Plain-203&tenantId=1");
        request.addParameter("token", "Token-Plain-203");
        request.addParameter("tenantId", "1");

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        String params = capture().getRequestParams();
        assertThat(params).doesNotContain("Login-Plain-Pwd-201", "Pwd-Plain-202", "Token-Plain-203");
        assertThat(params).contains(MASK, "admin", "tenantId");
    }

    @Test
    void annotatedResponse_persistedResponseIsMasked() throws Exception {
        MockHttpServletRequest request = newRequest("/admin-api/demo/secret");
        request.setAttribute(ATTRIBUTE_HANDLER_METHOD,
                new HandlerMethod(new DemoController(), DemoController.class.getMethod("secret")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", "Access-Plain-204");
        data.put("idCard", "IdCard-Plain-205");
        data.put("nickname", "nick");
        WebFrameworkUtils.setCommonResult(request, Result.success(data));

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        String responseBody = capture().getResponseBody();
        assertThat(responseBody).doesNotContain("Access-Plain-204", "IdCard-Plain-205");
        assertThat(responseBody).contains("\"accessToken\":\"" + MASK + "\"", "\"idCard\":\"" + MASK + "\"",
                "\"nickname\":\"nick\"");
    }

    private static MockHttpServletRequest newRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setServletPath(uri);
        WebFrameworkUtils.setLoginUserType(request, 2);
        return request;
    }

    private ApiAccessLogCreateReqDTO capture() {
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        return captor.getValue();
    }

    static class DemoController {

        @ApiAccessLog(responseEnable = true, sanitizeKeys = "idCard")
        public Result<Object> secret() {
            return Result.success(null);
        }

    }

}
