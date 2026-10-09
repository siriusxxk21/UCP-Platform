package com.richuang.os.nocode.report.service.authorization;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 报表授权序列化复用底座 Mapper；拒绝损坏配置和过大写入，不改变全局 JSON 设置。 */
@Component
public class ReportJson {
    @Resource private ObjectMapper json;

    public String write(Object value) {
        try {
            String text = json.writeValueAsString(value);
            if (text.getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw invalid("报表授权配置过大");
            return text;
        } catch (JsonProcessingException error) {
            throw invalid("报表授权配置无法保存");
        }
    }

    public <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw invalid("报表授权配置损坏，已拒绝访问");
        }
    }
}
