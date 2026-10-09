package com.lingan.ucp.framework.apilog.core.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.lingan.ucp.framework.apilog.core.util.ApiLogSanitizeUtils.MASK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺陷回归：登录接口的明文密码被 preHandle 按 INFO 打进日志（非 prod profile 时打印，线上 profile 为 os）
 */
class ApiAccessLogInterceptorTest {

    private final ApiAccessLogInterceptor interceptor = new ApiAccessLogInterceptor();

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void preHandle_loginJsonBody_logsMaskedPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/system/auth/login");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(("{\"username\":\"admin\",\"password\":\"Login-Plain-Pwd-101\","
                + "\"captchaVerification\":\"CAPTCHA-PLAIN-102\",\"rememberMe\":true}").getBytes(StandardCharsets.UTF_8));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        String output = joinedOutput();
        assertThat(output).contains("/admin-api/system/auth/login");
        assertThat(output).doesNotContain("Login-Plain-Pwd-101", "CAPTCHA-PLAIN-102");
        assertThat(output).contains("\"password\":\"" + MASK + "\"", "\"captchaVerification\":\"" + MASK + "\"");
        assertThat(output).contains("\"username\":\"admin\"", "\"rememberMe\":true");
    }

    @Test
    void preHandle_queryToken_logsMaskedToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/system/auth/refresh-token");
        request.setQueryString("refreshToken=Refresh-Plain-103&token=Token-Plain-104&tenantId=1");
        request.addParameter("refreshToken", "Refresh-Plain-103");
        request.addParameter("token", "Token-Plain-104");
        request.addParameter("tenantId", "1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        String output = joinedOutput();
        assertThat(output).doesNotContain("Refresh-Plain-103", "Token-Plain-104");
        assertThat(output).contains("refreshToken=" + MASK, "token=" + MASK, "tenantId=1");
    }

    private String joinedOutput() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).as("preHandle 应打印请求日志").isNotEmpty();
        StringBuilder sb = new StringBuilder();
        events.forEach(event -> sb.append(event.getFormattedMessage()).append('\n'));
        return sb.toString();
    }

}
