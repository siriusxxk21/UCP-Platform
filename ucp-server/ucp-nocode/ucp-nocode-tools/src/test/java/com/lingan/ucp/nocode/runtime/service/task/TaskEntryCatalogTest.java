package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.TaskEntries;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 复现目录枚举后应用被删除或停用的竞态，保证其他入口继续独立核验权限。 */
class TaskEntryCatalogTest {
    @Test
    void applicationRemovedOrPausedAfterEnumerationDoesNotHideOtherAuthorizedEntries() {
        ApplicationService applications = mock(ApplicationService.class);
        TaskEntryRuntimeInvocation invocations = mock(TaskEntryRuntimeInvocation.class);
        TaskEntryRuntimeQueries queries = queries(applications, invocations);
        when(applications.runnableIds()).thenReturn(List.of("removed", "paused", "active"));
        when(applications.published("removed")).thenThrow(new ServiceException(NOT_FOUND, "应用不存在"));
        when(applications.published("paused")).thenThrow(invalid("应用未发布或已停用"));
        ApplicationCenter.Resource allowed =
                new ApplicationCenter.Resource(
                        "allowed", "TASK_ENTRY", "allowed", "可用入口", Map.of());
        ApplicationCenter.Resource revoked =
                new ApplicationCenter.Resource(
                        "revoked", "TASK_ENTRY", "revoked", "不可见入口", Map.of());
        ApplicationCenter.Published release =
                new ApplicationCenter.Published(
                        new ApplicationCenter.Row(
                                "active", "active", "可用应用", null, null, "ACTIVE", 0, 1, null),
                        1,
                        "checksum",
                        new ApplicationCenter.Definition(List.of(), List.of(allowed, revoked)),
                        List.of());
        when(applications.published("active")).thenReturn(release);
        when(invocations.resolve(new TaskEntries.Locator("active", "allowed", null), 1L, false))
                .thenReturn(
                        new TaskEntryRuntimeInvocation.Resolved(
                                release,
                                allowed,
                                new TaskEntries.Config(
                                        "object", null, "form", "FORM", "日常", null, null, 0,
                                        List.of()),
                                null));
        when(invocations.resolve(new TaskEntries.Locator("active", "revoked", null), 1L, false))
                .thenThrow(invalid("没有此任务入口的办理权限"));

        assertThat(queries.mine(1L))
                .extracting(TaskEntries.Card::entryId)
                .containsExactly("allowed");
        verify(invocations).resolve(new TaskEntries.Locator("active", "revoked", null), 1L, false);
    }

    @Test
    void unexpectedInfrastructureFailureIsNotSilentlyReturnedAsAnEmptyCatalog() {
        ApplicationService applications = mock(ApplicationService.class);
        TaskEntryRuntimeInvocation invocations = mock(TaskEntryRuntimeInvocation.class);
        TaskEntryRuntimeQueries queries = queries(applications, invocations);
        when(applications.runnableIds()).thenReturn(List.of("active"));
        when(applications.published("active")).thenThrow(new IllegalStateException("数据库不可用"));
        assertThatThrownBy(() -> queries.mine(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("数据库不可用");
        verifyNoInteractions(invocations);
    }

    private TaskEntryRuntimeQueries queries(
            ApplicationService applications, TaskEntryRuntimeInvocation invocations) {
        TaskEntryRuntimeQueries queries = new TaskEntryRuntimeQueries();
        ReflectionTestUtils.setField(queries, "applications", applications);
        ReflectionTestUtils.setField(queries, "invocations", invocations);
        return queries;
    }
}
