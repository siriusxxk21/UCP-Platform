package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.SaveObjectDraft;
import com.richuang.os.nocode.api.TaskCenter;
import com.richuang.os.nocode.api.TaskWorkTimes;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

/** 对照提取前四个 Controller 的读取算法，覆盖输入形态、嵌套错误及异常边界。 */
class StrictRequestDecoderTest {
    private record Input(String profile, String body) {}

    static Stream<Input> requests() {
        return Stream.of("application", "runtime", "design", "draft")
                .flatMap(
                        profile ->
                                Stream.of(
                                                "<java-null>",
                                                "null",
                                                "[]",
                                                "1",
                                                "\"value\"",
                                                "{}",
                                                "{\"objectName\":\"名称\",\"expectedLockVersion\":2}",
                                                "{\"expectedLockVersion\":null}",
                                                "{\"expectedLockVersion\":2.5}",
                                                "{\"expectedLockVersion\":\"2\"}",
                                                "{\"expectedLockVersion\":\"invalid\"}",
                                                "{\"unexpected\":true}",
                                                "{\"fields\":[{\"unexpected\":true}]}",
                                                "{\"fields\":{},\"removedFieldIds\":[]}",
                                                "{\"fields\":[],\"removedFieldIds\":[\"1\"],\"titleTemplate\":null}")
                                        .map(body -> new Input(profile, body)));
    }

    @ParameterizedTest
    @MethodSource("requests")
    void matchesOriginalController(Input input) throws Exception {
        var shared =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        JsonNode body = input.body().equals("<java-null>") ? null : shared.readTree(input.body());
        try (var context = context(shared)) {
            var decoder = context.getBean(StrictRequestDecoder.class);
            assertThat(outcome(() -> decode(decoder, input.profile(), body), shared))
                    .isEqualTo(outcome(() -> original(shared, input.profile(), body), shared));
            assertThat(shared.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                    .isFalse();
            assertThat(shared.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isTrue();
        }
    }

    @Test
    void retainsDifferentRuntimeExceptionBoundaries() throws Exception {
        var module =
                new SimpleModule()
                        .addDeserializer(
                                SaveObjectDraft.class,
                                new JsonDeserializer<SaveObjectDraft>() {
                                    @Override
                                    public SaveObjectDraft deserialize(
                                            com.fasterxml.jackson.core.JsonParser parser,
                                            DeserializationContext context) {
                                        throw new IllegalStateException("custom reader failure");
                                    }
                                });
        var shared = new ObjectMapper().registerModule(module);
        var body = shared.readTree("{}");
        try (var context = context(shared)) {
            var decoder = context.getBean(StrictRequestDecoder.class);
            for (String profile : List.of("application", "runtime", "design", "draft")) {
                assertThat(outcome(() -> decode(decoder, profile, body), shared))
                        .isEqualTo(outcome(() -> original(shared, profile, body), shared));
            }
            assertThat(outcome(() -> decoder.design(body, SaveObjectDraft.class), shared))
                    .contains("ServiceException", "请求包含未知属性或字段类型不正确");
            assertThat(outcome(() -> decoder.runtime(body, SaveObjectDraft.class), shared))
                    .contains("IllegalStateException", "custom reader failure");
        }
    }

    private static AnnotationConfigApplicationContext context(ObjectMapper shared) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> shared);
        context.register(StrictRequestDecoder.class);
        context.refresh();
        return context;
    }

    @Test
    void taskWorkTimeContractAcceptsOptionalAndDecimalPlanningFields() throws Exception {
        ObjectMapper shared = new ObjectMapper().findAndRegisterModules();
        try (AnnotationConfigApplicationContext context = context(shared)) {
            StrictRequestDecoder decoder = context.getBean(StrictRequestDecoder.class);
            for (String quantity : List.of("null", "0", "2", "2.5")) {
                JsonNode body =
                        shared.readTree(
                                """
                                {"name":"可选预算","task":{"id":"root","title":"任务",
                                 "workTotalMode":"AUTO","effectiveWorkMinutes":null,
                                 "entries":[{"key":"entry","name":"业务",
                                   "workRule":{"mode":"QUANTITY","minutes":10,
                                     "quantityFieldId":"count","plannedQuantity":%s}}]}}
                                """
                                        .formatted(quantity));
                TaskCenter.SaveTemplate input = decoder.design(body, TaskCenter.SaveTemplate.class);
                assertThat(input.task().workTotalMode()).isEqualTo(TaskWorkTimes.TotalMode.AUTO);
                if (quantity.equals("null"))
                    assertThat(input.task().entries().getFirst().workRule().plannedQuantity())
                            .isNull();
                else
                    assertThat(input.task().entries().getFirst().workRule().plannedQuantity())
                            .isEqualByComparingTo(quantity);
            }
            JsonNode empty =
                    shared.readTree(
                            """
                            {"name":"可选预算","task":{"id":"root","title":"任务",
                              "workTotalMode":"AUTO","entries":[{"key":"entry","workRule":null}]}}
                            """);
            assertThat(
                            decoder.design(empty, TaskCenter.SaveTemplate.class)
                                    .task()
                                    .entries()
                                    .getFirst()
                                    .workRule())
                    .isNull();
        }
    }

    private static SaveObjectDraft decode(
            StrictRequestDecoder decoder, String profile, JsonNode body) {
        return switch (profile) {
            case "application" -> decoder.application(body, SaveObjectDraft.class);
            case "runtime" -> decoder.runtime(body, SaveObjectDraft.class);
            case "design" -> decoder.design(body, SaveObjectDraft.class);
            case "draft" -> decoder.draft(body);
            default -> throw new IllegalArgumentException(profile);
        };
    }

    /** 固定为提取前的处理顺序，不能随新组件实现调整。 */
    private static SaveObjectDraft original(ObjectMapper shared, String profile, JsonNode body) {
        var json =
                shared.copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        var reader = json.readerFor(SaveObjectDraft.class);
        if (body == null || !body.isObject()) throw invalid("请求必须是对象");
        if (profile.equals("draft")) {
            try {
                return reader.readValue(body);
            } catch (IOException ex) {
                throw invalid("请求包含未知属性或字段类型不正确");
            }
        }
        if (profile.equals("design")) {
            try {
                return json.treeToValue(body, SaveObjectDraft.class);
            } catch (Exception ex) {
                throw invalid("请求包含未知属性或字段类型不正确");
            }
        }
        try {
            return json.treeToValue(body, SaveObjectDraft.class);
        } catch (JsonProcessingException ex) {
            throw invalid(profile.equals("application") ? "应用请求结构无效" : "运行请求结构无效");
        }
    }

    private interface Read {
        Object get() throws Exception;
    }

    private static String outcome(Read read, ObjectMapper json) {
        try {
            return json.writeValueAsString(read.get());
        } catch (Exception ex) {
            return ex.getClass().getSimpleName()
                    + ":"
                    + (ex instanceof ServiceException service ? service.getCode() + ":" : "")
                    + ex.getMessage();
        }
    }
}
