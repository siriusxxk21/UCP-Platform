package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.nocode.api.BusinessFiles;
import com.lingan.ucp.nocode.controller.admin.BizFileController;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService;
import com.lingan.ucp.nocode.web.NocodeAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 业务附件上传端点的服务端契约：multipart 参数归一为上传请求，空白应用/记录/明细身份转换为 null； 续期按会话键返回结果；临时内容端点沿用与业务内容相同的 200/206/416
 * 传输语义。 授权与会话归属由服务层集成用例覆盖，这里只验证控制器参数与 HTTP 输出。
 */
class BizFileUploadEndpointTest {
    private static final long ACTOR = 10001L;
    private static final long FILE_ID = 740001L;
    private static final byte[] BODY = "0123456789".getBytes(StandardCharsets.UTF_8);

    private final BizFileUploadService upload = mock(BizFileUploadService.class);
    private final NocodeAccess access = mock(NocodeAccess.class);
    private final BizFileController controller = new BizFileController();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(controller, "upload", upload);
        ReflectionTestUtils.setField(controller, "access", access);
        when(access.actor()).thenReturn(ACTOR);
    }

    @Test
    void uploadEndpointNormalizesBlankIdentityAndForwardsMultipartContent() throws Exception {
        BusinessFiles.Uploaded uploaded =
                new BusinessFiles.Uploaded(FILE_ID, "合同.pdf", 10L, "application/pdf");
        when(upload.upload(any(), anyLong(), any())).thenReturn(uploaded);

        MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf", BODY);
        var result = controller.upload("", "object-1", "  ", null, "field-1", "sess-1", "", file);

        assertThat(result.getData().fileId()).isEqualTo(FILE_ID);
        ArgumentCaptor<BusinessFiles.UploadQuery> query =
                ArgumentCaptor.forClass(BusinessFiles.UploadQuery.class);
        ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
        verify(upload).upload(query.capture(), eq(ACTOR), content.capture());
        // 空白入口与位置身份必须归一为 null，不能把空串带到授权判定
        assertThat(query.getValue().applicationId()).isNull();
        assertThat(query.getValue().recordId()).isNull();
        assertThat(query.getValue().detailId()).isNull();
        assertThat(query.getValue().idempotencyKey()).isNull();
        assertThat(query.getValue().objectId()).isEqualTo("object-1");
        assertThat(query.getValue().fieldId()).isEqualTo("field-1");
        assertThat(query.getValue().sessionKey()).isEqualTo("sess-1");
        assertThat(query.getValue().fileName()).isEqualTo("合同.pdf");
        assertThat(query.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(query.getValue().size()).isEqualTo(10L);
        assertThat(content.getValue().readAllBytes()).isEqualTo(BODY);
    }

    @Test
    void renewEndpointReturnsWhetherTheSessionIsStillValid() {
        when(upload.renew("sess-1", ACTOR)).thenReturn(true);
        when(upload.renew("sess-2", ACTOR)).thenReturn(false);

        assertThat(controller.renew("sess-1").getData()).isTrue();
        assertThat(controller.renew("sess-2").getData()).isFalse();
    }

    @Test
    void temporaryContentEndpointServesWholeAndPartialContent() throws Exception {
        BusinessFiles.TemporaryContent content =
                new BusinessFiles.TemporaryContent(
                        FILE_ID, "draft.pdf", 10L, "application/pdf", 10L);
        when(upload.temporaryContent(any(), anyLong())).thenReturn(content);
        when(upload.temporaryStream(content, 0L)).thenReturn(new ByteArrayInputStream(BODY));

        MockHttpServletResponse response = serveTemp(null, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("10");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment;filename=draft.pdf");
        ArgumentCaptor<BusinessFiles.TemporaryQuery> query =
                ArgumentCaptor.forClass(BusinessFiles.TemporaryQuery.class);
        verify(upload).temporaryContent(query.capture(), eq(ACTOR));
        assertThat(query.getValue().objectId()).isEqualTo("object-1");
        assertThat(query.getValue().fieldId()).isEqualTo("field-1");
        assertThat(query.getValue().sessionKey()).isEqualTo("sess-1");
        assertThat(query.getValue().fileId()).isEqualTo(FILE_ID);

        // 区间读取与越界拒绝与业务内容端点保持一致
        when(upload.temporaryStream(content, 2L))
                .thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse partial = serveTemp("bytes=2-5", false);
        assertThat(partial.getStatus()).isEqualTo(206);
        assertThat(partial.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(partial.getContentAsByteArray())
                .isEqualTo("2345".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse rejected = serveTemp("bytes=20-30", false);
        assertThat(rejected.getStatus()).isEqualTo(416);
        assertThat(rejected.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        assertThat(rejected.getContentAsByteArray()).isEmpty();
        // 越界区间不打开内容流：前面只有两次有效读取（偏移 0 与 2）
        verify(upload, times(2)).temporaryStream(any(), anyLong());
    }

    private MockHttpServletResponse serveTemp(String rangeHeader, boolean inline) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/nocode/biz-file/upload/content");
        if (rangeHeader != null) {
            request.addHeader(HttpHeaders.RANGE, rangeHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.temporaryContent(
                "object-1", "field-1", "sess-1", FILE_ID, inline, request, response);
        return response;
    }
}
