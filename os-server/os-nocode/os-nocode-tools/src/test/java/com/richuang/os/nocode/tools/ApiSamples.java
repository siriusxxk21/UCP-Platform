package com.richuang.os.nocode.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.richuang.os.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;

import java.time.LocalDateTime;

/**
 * 把集成用例里真实服务的入参与返回值按接口的序列化口径打到标准输出，供接口样例文档取用。只在环境变量 API_SAMPLES=1 时输出，平时什么也不做。
 *
 * <p>每个样例一行：{@code API_SAMPLE|名称|请求 JSON|响应 JSON}。时间按底座的口径输出为毫秒时间戳；接口外层的 {code, data, msg} 包装不在这里。
 */
final class ApiSamples {
    private static final boolean ON = "1".equals(System.getenv("API_SAMPLES"));
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(
                            new JavaTimeModule()
                                    .addSerializer(
                                            LocalDateTime.class,
                                            TimestampLocalDateTimeSerializer.INSTANCE))
                    .registerModule(new SimpleModule());

    private ApiSamples() {}

    static boolean on() {
        return ON;
    }

    static void print(String name, Object request, Object response) {
        if (!ON) return;
        try {
            System.out.println(
                    "API_SAMPLE|"
                            + name
                            + "|"
                            + JSON.writeValueAsString(request)
                            + "|"
                            + JSON.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
