package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.work.PublishedResourceRef;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 视图授权先收窄再查询，避免隐藏字段搜索推断和已撤销表单明细回流。 */
class TaskBoundViewsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ApplicationPublishedService published = mock(ApplicationPublishedService.class);
    private final TaskBoundViews views = new TaskBoundViews();
    private final Binding binding = new Binding("app", "form", null, "view");
    private final BusinessRef ref =
            new BusinessRef(
                    new PublishedResourceRef("app", 1, "sum", "form", "FORM"),
                    new ApplicationCenter.ObjectReference("object", 1, "sum"),
                    null,
                    null);

    TaskBoundViewsTest() {
        ReflectionTestUtils.setField(views, "json", json);
        ReflectionTestUtils.setField(views, "published", published);
    }

    private ApplicationCenter.Published release(int version, boolean detail) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(Map.of("id", "name", "type", "FIELD", "fieldId", "name"));
        nodes.add(
                Map.of(
                        "id",
                        "readonly",
                        "type",
                        "FIELD",
                        "fieldId",
                        "readonly",
                        "presentation",
                        Map.of("readOnly", true)));
        if (detail) {
            nodes.add(
                    Map.of(
                            "id",
                            "detail",
                            "type",
                            "DETAIL",
                            "detail",
                            Map.of("detailId", "detail", "mode", "TABLE")));
            nodes.add(
                    Map.of(
                            "id",
                            "relation",
                            "type",
                            "RELATION",
                            "binding",
                            Map.of("relationId", "relation", "direction", "FORWARD")));
        }
        var form =
                new ApplicationCenter.Resource(
                        "form",
                        "FORM",
                        "form",
                        "办理表单",
                        Map.of("objectId", "object", "nodes", nodes));
        var view =
                new ApplicationCenter.Resource(
                        "view",
                        "VIEW",
                        "view",
                        "办理视图",
                        Map.of(
                                "objectId",
                                "object",
                                "formId",
                                "form",
                                "fieldIds",
                                List.of("name")));
        return new ApplicationCenter.Published(
                null,
                version,
                "sum",
                new ApplicationCenter.Definition(List.of(ref.object()), List.of(form, view)),
                List.of());
    }

    private Capabilities all() {
        return new Capabilities(
                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                Set.of("name", "readonly", "secret"),
                Set.of("name", "readonly", "secret"),
                Set.of("detail", "secret-detail"),
                Set.of("detail", "secret-detail"),
                Set.of("relation", "secret-relation"),
                Set.of("relation", "secret-relation"));
    }

    @Test
    void frozenAndCurrentFormIntersectFieldsDetailsAndRelations() {
        when(published.getVersion("app", 1)).thenReturn(release(1, true));
        when(published.getCurrent("app")).thenReturn(release(2, false));
        Capabilities result = views.capabilities(binding, ref, all());
        assertThat(result.readFields()).containsExactlyInAnyOrder("name", "readonly");
        assertThat(result.writeFields()).containsExactly("name");
        assertThat(result.readDetails()).isEmpty();
        assertThat(result.writeDetails()).isEmpty();
        assertThat(result.readRelations()).isEmpty();
        assertThat(result.writeRelations()).isEmpty();
        var row =
                new ApplicationRecords.Row(
                        "record", "1", Map.of("name", "room", "secret", "private"), all());
        var aggregate =
                new ApplicationRecords.Aggregate(
                        row,
                        Map.of("secret-detail", List.of(row)),
                        List.of(),
                        Map.of("secret-relation", List.of("other")));
        var projected = views.project(binding, ref, aggregate);
        assertThat(projected.record().values()).containsOnlyKeys("name");
        assertThat(projected.details()).isEmpty();
        assertThat(projected.relations()).isEmpty();
    }

    @Test
    void compilerNarrowsQueryGrantBeforeSearchOrFieldHelpers() {
        TaskDataPolicyCompiler compiler = new TaskDataPolicyCompiler();
        ReflectionTestUtils.setField(compiler, "boundViews", views);
        Capabilities caps = all();
        ObjectGrant source =
                new ObjectGrant(
                        "object",
                        caps.actions(),
                        "ALL",
                        caps.readFields(),
                        caps.writeFields(),
                        caps.readDetails(),
                        caps.writeDetails(),
                        caps.readRelations(),
                        caps.writeRelations());
        ObjectGrant result =
                ReflectionTestUtils.invokeMethod(
                        compiler, "withViewFields", source, binding, release(1, false));
        assertThat(result.readFields()).containsExactlyInAnyOrder("name", "readonly");
        assertThat(result.writeFields()).containsExactly("name");
        assertThat(result.readDetails()).isEmpty();
        assertThat(result.readRelations()).isEmpty();
    }

    @Test
    void dynamicIdentityViewsFailAtConfigurationInsteadOfDuringSave() {
        ApplicationUi.View view =
                json.convertValue(
                        Map.of(
                                "objectId",
                                "object",
                                "formId",
                                "form",
                                "fieldIds",
                                List.of("name"),
                                "query",
                                Map.of(
                                        "fixed",
                                        List.of(
                                                Map.of(
                                                        "fieldId",
                                                        "name",
                                                        "operator",
                                                        "eq",
                                                        "valueSource",
                                                        "CURRENT_USER")))),
                        ApplicationUi.View.class);
        assertThatThrownBy(() -> views.scope(view, null)).hasMessageContaining("动态视图范围");
    }
}
