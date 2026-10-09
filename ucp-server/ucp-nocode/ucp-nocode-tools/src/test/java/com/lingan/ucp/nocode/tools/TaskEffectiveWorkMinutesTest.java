package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskGraph;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskTemplateGraph;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

/** 参考工时的分钟精度、总任务边界及旧 JSON 兼容，不将工作量解释为排期。 */
class TaskEffectiveWorkMinutesTest {
    @Test
    void rootMinutesAreNormalizedWithoutChangingSchedule() {
        for (Integer minutes : List.of(1, 90, 599999)) {
            NodeInput input = node("root", null, minutes);
            NodeInput normalized = TaskGraph.normalize(List.of(input), 1L).getFirst();
            assertThat(normalized.effectiveWorkMinutes()).isEqualTo(minutes);
            assertThat(normalized.schedule()).isEqualTo(input.schedule());
        }
        assertThat(
                        TaskGraph.normalize(List.of(node("root", null, 0)), 1L)
                                .getFirst()
                                .effectiveWorkMinutes())
                .isNull();
        assertThat(
                        TaskGraph.normalize(List.of(node("root", null, null)), 1L)
                                .getFirst()
                                .effectiveWorkMinutes())
                .isNull();
    }

    @Test
    void negativeOverflowAndChildMinutesAreRejected() {
        for (Integer minutes : List.of(-1, 600000, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> TaskGraph.normalize(List.of(node("root", null, minutes)), 1L))
                    .hasMessageContaining("有效工作时长");
        }
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        node("root", null, 90),
                                        List.of(node("child", null, 30)),
                                        1L))
                .hasMessageContaining("只能配置在总任务");
        assertThatThrownBy(
                        () ->
                                TaskTemplateGraph.normalize(
                                        null, List.of(node("legacy-child", null, 30)), 1L))
                .hasMessageContaining("独立的模板总任务");
    }

    @Test
    void missingFieldAndOldConstructorRemainEmpty() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        NodeInput legacy = json.readValue("{\"id\":\"root\",\"title\":\"旧任务\"}", NodeInput.class);
        assertThat(legacy.effectiveWorkMinutes()).isNull();
        NodeInput previous =
                new NodeInput(
                        "root",
                        null,
                        "旧任务",
                        null,
                        1L,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        null,
                        null);
        assertThat(previous.effectiveWorkMinutes()).isNull();
        assertThat(json.writeValueAsString(previous)).doesNotContain("effectiveWorkMinutes");
        assertThat(
                        json.readValue(
                                        json.writeValueAsString(node("root", null, 125)),
                                        NodeInput.class)
                                .effectiveWorkMinutes())
                .isEqualTo(125);
    }

    @Test
    void requestDecoderRejectsFractionalAndInvalidMinuteTypes() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> json);
            context.register(StrictRequestDecoder.class);
            context.refresh();
            StrictRequestDecoder decoder = context.getBean(StrictRequestDecoder.class);
            for (String value : List.of("1.5", "true", "{}", "[]", "2147483648")) {
                assertThatThrownBy(
                                () ->
                                        decoder.design(
                                                json.readTree(
                                                        "{\"task\":{\"id\":\"root\",\"title\":\"测试\",\"effectiveWorkMinutes\":"
                                                                + value
                                                                + "}}"),
                                                SaveTemplate.class))
                        .hasMessageContaining("字段类型不正确");
            }
        }
    }

    private NodeInput node(String id, String parent, Integer minutes) {
        return new NodeInput(
                id,
                parent,
                "工时边界测试",
                null,
                1L,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of(),
                null,
                null,
                minutes);
    }
}
