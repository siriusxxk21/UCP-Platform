package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.controller.admin.task.TaskBusinessFileController;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskBusinessFileService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 任务内容端点复用业务文件流输出，授权失败不打开存储，严格接参与 Range 契约不降级。 */
class TaskBusinessFileEndpointTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TaskBusinessFileService service = mock(TaskBusinessFileService.class);
    private final NocodeAccess access = mock(NocodeAccess.class);
    private final TaskBusinessFileController controller = new TaskBusinessFileController();
    private final TaskWorkEntries.Form target =
            new TaskWorkEntries.Form("task", "entry", "record", null);
    private final TaskBusinessFiles.Content query =
            new TaskBusinessFiles.Content(
                    target,
                    new BusinessFiles.ContentQuery(
                            "app", "object", "record", null, null, "file", 99L),
                    true);
    private final BusinessFiles.Content content =
            new BusinessFiles.Content(99L, 88L, "proof.pdf", 10L, "application/pdf", 10L);

    @BeforeEach
    void setup() {
        StrictRequestDecoder decoder = new StrictRequestDecoder();
        ReflectionTestUtils.setField(decoder, "json", json);
        ReflectionTestUtils.invokeMethod(decoder, "initialize");
        ReflectionTestUtils.setField(controller, "requests", decoder);
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "access", access);
        when(access.actor()).thenReturn(20002L);
    }

    @Test
    void authorizedContentPreservesRangeAndPrivateHeaders() throws Exception {
        when(service.content(query, 20002L)).thenReturn(content);
        when(service.contentStream(content, 2L))
                .thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.RANGE, "bytes=2-5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.content(json.valueToTree(query), request, response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsString()).isEqualTo("2345");
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
    }

    @Test
    void invalidRangeNeverOpensStorage() throws Exception {
        when(service.content(query, 20002L)).thenReturn(content);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.RANGE, "bytes=20-30");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.content(json.valueToTree(query), request, response);
        assertThat(response.getStatus()).isEqualTo(416);
        verify(service, never()).contentStream(any(), anyLong());
    }

    @Test
    void rejectedTaskDoesNotWriteHeadersOrReadStorage() {
        when(service.content(query, 20002L)).thenThrow(NocodeErrorCodes.invalid("任务已转交"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(
                        () ->
                                controller.content(
                                        json.valueToTree(query),
                                        new MockHttpServletRequest(),
                                        response))
                .hasMessageContaining("任务已转交");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        verify(service, never()).contentStream(any(), anyLong());
    }

    @Test
    void unknownPermissionHintsCannotEnterService() throws Exception {
        assertThatThrownBy(
                        () ->
                                controller.files(
                                        json.readTree(
                                                "{\"target\":{\"taskId\":\"task\",\"entryKey\":\"entry\",\"manager\":true},\"query\":{}}")))
                .hasMessageContaining("运行请求结构无效");
        verifyNoInteractions(service);
    }
}
