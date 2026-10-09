package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.api.BusinessFiles;
import com.richuang.os.nocode.controller.admin.BizFileController;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.richuang.os.nocode.web.NocodeAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 业务文件内容端点的传输契约：整文件 200、区间 206、越界 416、附件与内联两种展示方式。
 *
 * <p>授权链由服务层集成用例覆盖，这里只验证控制器把已受权内容按 HTTP 语义正确输出，并确认越界请求不打开内容流。
 */
class BizFileContentEndpointTest {
    private static final long ACTOR = 10001L;
    private static final long ENTRY_ID = 930001L;
    private static final byte[] BODY = "0123456789".getBytes(StandardCharsets.UTF_8);

    private final BizFileBrowseService browse = mock(BizFileBrowseService.class);
    private final NocodeAccess access = mock(NocodeAccess.class);
    private final BizFileController controller = new BizFileController();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(controller, "browse", browse);
        ReflectionTestUtils.setField(controller, "access", access);
        when(access.actor()).thenReturn(ACTOR);
    }

    @Test
    void servesWholeFileAsAttachmentWithContentLength() throws Exception {
        BusinessFiles.Content content = content("application/pdf", 10L);
        when(browse.content(contentQuery(""), ACTOR)).thenReturn(content);
        when(browse.contentStream(content, 0L)).thenReturn(new ByteArrayInputStream(BODY));

        MockHttpServletResponse response = serve(null, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("10");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment;filename=contract.pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isNull();
        // 应用编号与明细位置的空白必须归一为 null，不能把空串带到授权判定
        verify(browse).content(contentQuery(""), ACTOR);
        verify(browse).contentStream(content, 0L);
    }

    @Test
    void servesPartialContentFromTheRequestedOffset() throws Exception {
        BusinessFiles.Content content = content("application/pdf", 10L);
        when(browse.content(any(), anyLong())).thenReturn(content);
        when(browse.contentStream(content, 2L))
                .thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = serve("bytes=2-5", false);

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsByteArray())
                .isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("4");
        verify(browse).contentStream(content, 2L);
    }

    @Test
    void rejectsUnsatisfiableRangeWithoutOpeningContentStream() throws Exception {
        BusinessFiles.Content content = content("application/pdf", 10L);
        when(browse.content(any(), anyLong())).thenReturn(content);

        MockHttpServletResponse response = serve("bytes=20-30", false);

        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getContentAsByteArray()).isEmpty();
        verify(browse, never()).contentStream(any(), anyLong());
    }

    @Test
    void inlineModePreviewsInsteadOfDownloading() throws Exception {
        BusinessFiles.Content content = content("application/pdf", 10L);
        when(browse.content(any(), anyLong())).thenReturn(content);
        when(browse.contentStream(content, 0L)).thenReturn(new ByteArrayInputStream(BODY));

        MockHttpServletResponse response = serve(null, true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline;filename=contract.pdf");
    }

    @Test
    void degradesToSizeAndGenericTypeWhenStorageFactsAreMissing() throws Exception {
        BusinessFiles.Content content =
                new BusinessFiles.Content(ENTRY_ID, 1001L, "contract.pdf", 10L, null, null);
        when(browse.content(any(), anyLong())).thenReturn(content);
        when(browse.contentStream(content, 5L))
                .thenReturn(new ByteArrayInputStream("56789".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = serve("bytes=5-", false);

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-9/10");
        assertThat(response.getContentAsByteArray())
                .isEqualTo("56789".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyFileIgnoresRangeHeaderAndReturnsWholeFile() throws Exception {
        BusinessFiles.Content content =
                new BusinessFiles.Content(
                        ENTRY_ID, 1001L, "contract.pdf", 0L, "application/pdf", 0L);
        when(browse.content(any(), anyLong())).thenReturn(content);
        when(browse.contentStream(content, 0L)).thenReturn(new ByteArrayInputStream(new byte[0]));

        MockHttpServletResponse response = serve("bytes=0-10", false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        verify(browse).contentStream(content, 0L);
    }

    private BusinessFiles.Content content(String mimeType, Long length) {
        return new BusinessFiles.Content(ENTRY_ID, 1001L, "contract.pdf", 10L, mimeType, length);
    }

    private BusinessFiles.ContentQuery contentQuery(String applicationId) {
        return new BusinessFiles.ContentQuery(
                applicationId.isBlank() ? null : applicationId,
                "object-1",
                "record-1",
                null,
                null,
                "field-1",
                ENTRY_ID);
    }

    private MockHttpServletResponse serve(String rangeHeader, boolean inline) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/nocode/biz-file/content");
        if (rangeHeader != null) {
            request.addHeader(HttpHeaders.RANGE, rangeHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.content(
                "",
                "object-1",
                "record-1",
                null,
                null,
                "field-1",
                ENTRY_ID,
                inline,
                request,
                response);
        return response;
    }
}
