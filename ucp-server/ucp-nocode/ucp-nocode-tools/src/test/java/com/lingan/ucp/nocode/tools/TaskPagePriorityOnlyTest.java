package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.TaskCenter.PageQuery;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.runtime.dal.query.TaskQueryScope;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBusiness;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskPageQueries;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 任务视图去掉紧急程度后，旧发布配置不能继续以隐藏条件遗漏任务。 */
class TaskPagePriorityOnlyTest {
    @Test
    void publishedUrgencyFilterAndSortAreIgnoredWithoutDroppingOtherFixedScopes() {
        TaskQueryScope scope = resolve("urgency", true);
        assertThat(scope.urgencies()).isEmpty();
        assertThat(scope.sort()).isEqualTo("expectedEnd");
        assertThat(scope.descending()).isFalse();
        assertThat(scope.statuses()).containsExactly("RUNNING");
        assertThat(scope.priorities()).containsExactly("HIGH");
        assertThat(scope.templateIds()).containsExactly("template-fixed");
        assertThat(scope.applicationId()).isEqualTo("app");
    }

    @Test
    void prioritySortAndDirectionRemainEffective() {
        TaskQueryScope scope = resolve("priority", true);
        assertThat(scope.urgencies()).isEmpty();
        assertThat(scope.sort()).isEqualTo("priority");
        assertThat(scope.descending()).isTrue();
    }

    private TaskQueryScope resolve(String sort, boolean descending) {
        ApplicationPublishedService published = mock(ApplicationPublishedService.class);
        ApplicationRuntimePolicy policy = mock(ApplicationRuntimePolicy.class);
        Map<String, Object> view =
                Map.of(
                        "taskFilter",
                                Map.of(
                                        "statuses",
                                        List.of("RUNNING"),
                                        "urgencies",
                                        List.of("URGENT"),
                                        "priorities",
                                        List.of("HIGH")),
                        "templateIds", List.of("template-fixed"),
                        "sort", Map.of("field", sort, "descending", descending));
        Map<String, Object> page =
                Map.of("nodes", List.of(Map.of("id", "tasks", "type", "TASKS", "taskView", view)));
        ApplicationCenter.Row app = mock(ApplicationCenter.Row.class);
        when(app.id()).thenReturn("app");
        when(published.getCurrent("app"))
                .thenReturn(
                        new ApplicationCenter.Published(
                                app,
                                1,
                                "checksum",
                                new ApplicationCenter.Definition(
                                        List.of(),
                                        List.of(
                                                new ApplicationCenter.Resource(
                                                        "page", "PAGE", "page", "任务页面", page))),
                                List.of()));
        TaskPageQueries queries = new TaskPageQueries();
        ReflectionTestUtils.setField(queries, "published", published);
        ReflectionTestUtils.setField(queries, "policy", policy);
        ReflectionTestUtils.setField(queries, "business", mock(TaskBusiness.class));
        ReflectionTestUtils.setField(queries, "records", mock(RecordQueryAccess.class));
        ReflectionTestUtils.setField(queries, "json", new ObjectMapper().findAndRegisterModules());
        TaskQueryScope result =
                queries.resolve(new PageQuery("app", "page", "tasks", null, null), 1L, false);
        verify(policy).requireEntry("app", 1L);
        return result;
    }
}
