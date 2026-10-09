package com.lingan.ucp.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationUi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 资源图解析与旧 JSON 兼容性，不依赖名称、数组顺序或其他应用资源。 */
class ApplicationDefaultFormTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApplicationResourceContext context = new ApplicationResourceContext();

    @BeforeEach
    void initialize() {
        ReflectionTestUtils.setField(context, "json", json);
        context.initialize();
    }

    @Test
    void absentFlagAndLegacyConstructorsNeverBecomeDefault() {
        ApplicationUi.FormOptions old =
                context.decode(
                        Map.of("layout", "vertical", "submitText", "保存"),
                        ApplicationUi.FormOptions.class);
        assertThat(old.defaultForObject()).isFalse();
        assertThat(new ApplicationUi.FormOptions("vertical", "保存").defaultForObject()).isFalse();
        assertThat(new ApplicationUi.FormOptions("vertical", "保存", false, true).defaultForObject())
                .isFalse();
        assertThat(context.viewForm(List.of(form("first", "object", false)), view("object", null)))
                .isNull();
    }

    @Test
    void explicitSelectionWinsAndInvalidSelectionNeverFallsBack() {
        ApplicationCenter.Resource ordinary = form("ordinary", "object", false);
        ApplicationCenter.Resource primary = form("default", "object", true);
        List<ApplicationCenter.Resource> resources =
                List.of(ordinary, primary, form("foreign", "other", true));
        assertThat(context.viewForm(resources, view("object", null))).isEqualTo(primary);
        assertThat(context.viewForm(resources, view("object", "ordinary"))).isEqualTo(ordinary);
        assertThatThrownBy(() -> context.viewForm(resources, view("object", "missing")))
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> context.viewForm(resources, view("object", "foreign")))
                .hasMessageContaining("同一对象");
        assertThatThrownBy(() -> context.viewForm(resources, view("object", "")))
                .hasMessageContaining("不存在");
        assertThat(context.viewForm(List.of(), view("object", null))).isNull();
    }

    @Test
    void defaultsAreUniquePerObjectAndDeletionRequiresExplicitTransition() {
        ApplicationCenter.Resource first = form("first", "object", true);
        ApplicationCenter.Resource second = form("second", "object", true);
        assertThatThrownBy(() -> context.defaultForms(List.of(first, second)))
                .hasMessageContaining("只能设置一个默认表单");
        assertThat(context.defaultForms(List.of(first, form("other", "other", true)))).hasSize(2);
        ApplicationCenter.Definition before = definition(first);
        assertThatThrownBy(() -> context.validateDefaultFormRemoval(before, definition()))
                .hasMessageContaining("请先更换或取消默认表单");
        assertThatThrownBy(
                        () ->
                                context.validateDefaultFormRemoval(
                                        before, definition(form("first", "other", true))))
                .hasMessageContaining("请先更换或取消默认表单");
        assertThatCode(() -> context.validateDefaultFormRemoval(before, definition(second)))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                context.validateDefaultFormRemoval(
                                        before, definition(form("first", "object", false))))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                context.validateDefaultFormRemoval(
                                        before, ApplicationCenter.Definition.empty()))
                .doesNotThrowAnyException();
    }

    private ApplicationCenter.Definition definition(ApplicationCenter.Resource... resources) {
        return new ApplicationCenter.Definition(
                List.of(new ApplicationCenter.ObjectReference("object", 1, "checksum")),
                List.of(resources));
    }

    private ApplicationUi.View view(String objectId, String formId) {
        return new ApplicationUi.View(
                objectId, List.of("field"), Map.of(), null, false, 20, formId);
    }

    private ApplicationCenter.Resource form(String id, String objectId, boolean defaultForObject) {
        ApplicationUi.Form config =
                new ApplicationUi.Form(
                        objectId,
                        List.of(),
                        List.of(),
                        new ApplicationUi.FormOptions(
                                "vertical", "保存", false, false, defaultForObject));
        return new ApplicationCenter.Resource(
                id,
                "FORM",
                id,
                id,
                json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }
}
