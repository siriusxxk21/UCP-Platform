package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.TaskCenter.PageQuery;
import com.richuang.os.nocode.api.TaskCenter.RecordRef;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.runtime.dal.query.TaskQueryScope;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskBusiness;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskPageQueries;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 应用任务绑定表单后仍限定当前应用；记录任务只接受发布页面定义的对象身份。 */
class TaskPageApplicationScopeTest {
    private final ApplicationPublishedService published = mock(ApplicationPublishedService.class);
    private final ApplicationRuntimePolicy policy = mock(ApplicationRuntimePolicy.class);
    private final TaskBusiness business = mock(TaskBusiness.class);

    @Test
    void unboundApplicationTasksStayInsideTheirApplication() {
        TaskQueryScope scope = queries(false, false, false).resolve(query(null), 7L, false);
        assertThat(scope.applicationId()).isEqualTo("app");
        assertThat(scope.objectId()).isNull();
        verify(policy).requireEntry("app", 7L);
    }

    @Test
    void bindingAFormCannotExposeOtherApplicationsUsingTheSameObject() {
        TaskQueryScope scope = queries(true, false, false).resolve(query(null), 7L, false);
        assertThat(scope.applicationId()).isEqualTo("app");
        assertThat(scope.objectId()).isEqualTo("project");
        assertThat(scope.context()).isNull();
        verify(policy).requireEntry("app", 7L);
    }

    @Test
    void configuringBusinessColumnsAlsoPreservesApplicationScope() {
        TaskQueryScope scope = queries(false, false, true).resolve(query(null), 7L, false);
        assertThat(scope.applicationId()).isEqualTo("app");
        assertThat(scope.objectId()).isEqualTo("project");
    }

    @Test
    void recordTasksKeepExactPublishedContextAndExplicitLinks() {
        TaskQueryScope scope = queries(true, true, false).resolve(query("record-1"), 7L, false);
        RecordRef expected = new RecordRef("app", "project", "record-1", "记录 record-1");
        assertThat(scope.context()).isEqualTo(expected);
        assertThat(scope.applicationId()).isNull();
        assertThat(scope.objectId()).isNull();
        assertThat(scope.candidates()).isFalse();
        verify(business).project(expected, 7L);
    }

    @Test
    void recordCandidatesKeepTheSameVisibilityCheckAndContext() {
        TaskQueryScope scope = queries(true, true, false).resolve(query("record-2"), 7L, true);
        RecordRef expected = new RecordRef("app", "project", "record-2", "记录 record-2");
        assertThat(scope.context()).isEqualTo(expected);
        assertThat(scope.candidates()).isTrue();
        verify(business).project(expected, 7L);
    }

    @Test
    void missingRecordCannotFallBackToAllApplicationTasks() {
        TaskPageQueries queries = queries(true, true, false);
        assertThatThrownBy(() -> queries.resolve(query(null), 7L, false))
                .hasMessageContaining("需要当前业务记录");
        verify(business, never()).project(any(RecordRef.class), eq(7L));
    }

    private PageQuery query(String record) {
        return new PageQuery("app", "page", "tasks", record, null);
    }

    private TaskPageQueries queries(boolean formBinding, boolean recordPage, boolean businessForm) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "tasks");
        node.put("type", "TASKS");
        if (formBinding) node.put("resourceId", "project-form");
        if (businessForm) node.put("taskView", Map.of("businessFormId", "project-form"));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("nodes", List.of(node));
        if (recordPage) page.put("contextObjectId", "project");
        List<ApplicationCenter.Resource> resources = new ArrayList<>();
        resources.add(new ApplicationCenter.Resource("page", "PAGE", "page", "任务页面", page));
        resources.add(
                new ApplicationCenter.Resource(
                        "project-form",
                        "FORM",
                        "project-form",
                        "项目表单",
                        Map.of("objectId", "project", "nodes", List.of(), "detailIds", List.of())));
        ApplicationCenter.Row app = mock(ApplicationCenter.Row.class);
        when(app.id()).thenReturn("app");
        when(published.getCurrent("app"))
                .thenReturn(
                        new ApplicationCenter.Published(
                                app,
                                1,
                                "checksum",
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        "project", 1, "object-checksum")),
                                        resources),
                                List.of()));
        TaskPageQueries queries = new TaskPageQueries();
        ReflectionTestUtils.setField(queries, "published", published);
        ReflectionTestUtils.setField(queries, "policy", policy);
        ReflectionTestUtils.setField(queries, "business", business);
        ReflectionTestUtils.setField(queries, "records", mock(RecordQueryAccess.class));
        ReflectionTestUtils.setField(queries, "json", new ObjectMapper().findAndRegisterModules());
        return queries;
    }
}
