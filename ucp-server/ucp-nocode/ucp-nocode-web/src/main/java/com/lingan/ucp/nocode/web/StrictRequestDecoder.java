package com.lingan.ucp.nocode.web;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.lingan.ucp.nocode.api.SaveObjectDraft;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

/** 无代码手工 JSON 接口的严格读取边界。仅复制底座配置，不更改共享 ObjectMapper。 各入口保留原有异常捕获范围和错误提示；不能在此统一收窄或扩大请求接受范围。 */
@Component
public class StrictRequestDecoder {
    @Resource private ObjectMapper json;
    private ObjectMapper strict;
    private ObjectReader draftReader;

    @PostConstruct
    void initialize() {
        strict =
                json.copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        draftReader = strict.readerFor(SaveObjectDraft.class);
    }

    /** 应用设计入口只转换 Jackson 解析异常，其他运行异常沿用底座处理。 */
    public <T> T application(JsonNode body, Class<T> type) {
        return read(body, type, "应用请求结构无效");
    }

    /** 运行入口保留独立的错误文案。 */
    public <T> T runtime(JsonNode body, Class<T> type) {
        return read(body, type, "运行请求结构无效");
    }

    private <T> T read(JsonNode body, Class<T> type, String message) {
        requireObject(body);
        try {
            return strict.treeToValue(body, type);
        } catch (JsonProcessingException ex) {
            throw invalid(message);
        }
    }

    /** 数据中心设计入口历史上转换所有 Exception，保功能提取时保持这一边界。 */
    public <T> T design(JsonNode body, Class<T> type) {
        requireObject(body);
        try {
            return strict.treeToValue(body, type);
        } catch (Exception ex) {
            throw invalid("请求包含未知属性或字段类型不正确");
        }
    }

    /** 草稿使用固定类型 ObjectReader，保留原 IOException 捕获语义。 */
    public SaveObjectDraft draft(JsonNode body) {
        requireObject(body);
        try {
            return draftReader.readValue(body);
        } catch (java.io.IOException ex) {
            throw invalid("请求包含未知属性或字段类型不正确");
        }
    }

    private void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid("请求必须是对象");
    }
}
