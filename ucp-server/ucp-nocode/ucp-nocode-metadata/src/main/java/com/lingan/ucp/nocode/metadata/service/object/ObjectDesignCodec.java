package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

/** 对象设计及审计载荷的 JSON 编解码，保留原异常类型及底座注册模块。 */
@Component
public class ObjectDesignCodec {
    @Resource private ObjectMapper json;

    @PostConstruct
    void initialize() {
        this.json = json.copy().findAndRegisterModules();
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("对象结构序列化失败", ex);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("对象结构读取失败", ex);
        }
    }

    <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("对象配置读取失败", ex);
        }
    }
}
