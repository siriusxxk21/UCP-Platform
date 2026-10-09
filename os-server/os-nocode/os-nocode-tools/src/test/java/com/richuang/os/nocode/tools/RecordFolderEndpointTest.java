package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.RecordFolders;
import com.richuang.os.nocode.controller.admin.RecordFolderController;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderConfigService;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 记录文件夹端点的传输契约：地址与方法、请求体的严格解码、上传的表单字段、内容响应的 200 / 206 / 416 与响应头。
 *
 * <p>授权链由服务层集成用例覆盖；这里用替身服务，只验证控制器把请求原样交给服务、把结果按 HTTP 语义输出。
 */
class RecordFolderEndpointTest {
    private static final long ACTOR = 10001L;
    private static final byte[] BODY = "0123456789".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RecordFolderConfigService configs = mock(RecordFolderConfigService.class);
    private final RecordFolderService folders = mock(RecordFolderService.class);
    private final NocodeAccess access = mock(NocodeAccess.class);
    private final RecordFolderController controller = new RecordFolderController();
    private MockMvc mvc;

    @BeforeEach
    void inject() {
        StrictRequestDecoder decoder = new StrictRequestDecoder();
        ReflectionTestUtils.setField(decoder, "json", json);
        ReflectionTestUtils.invokeMethod(decoder, "initialize");
        ReflectionTestUtils.setField(controller, "configs", configs);
        ReflectionTestUtils.setField(controller, "folders", folders);
        ReflectionTestUtils.setField(controller, "access", access);
        ReflectionTestUtils.setField(controller, "requests", decoder);
        when(access.actor()).thenReturn(ACTOR);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static RecordFolders.Entry entry(long id, String name) {
        return new RecordFolders.Entry(
                id,
                7L,
                0L,
                name,
                "FILE",
                10L,
                "text/plain",
                null,
                "10001",
                null,
                null,
                null,
                null,
                true);
    }

    private String credential(String extra) {
        return "{\"applicationId\":\"1\",\"objectId\":\"2\",\"recordId\":\"3\",\"sourceId\":\"4\""
                + (extra.isEmpty() ? "" : "," + extra)
                + "}";
    }

    private JsonNode data(String path, String body) throws Exception {
        MockHttpServletResponse response =
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        JsonNode tree = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(tree.get("code").asInt()).as(path).isZero();
        return tree.get("data");
    }

    @Test
    void addressesMethodsAndGuardsMatchTheContract() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GET /config", "@nocodeAccess.query()");
        expected.put("POST /config/save", "@nocodeAccess.manage()");
        expected.put("GET /config/candidates", "@nocodeAccess.query()");
        expected.put("GET /config/name-fields", "@nocodeAccess.query()");
        expected.put("POST /config/backfill", "@nocodeAccess.manage()");
        for (String path :
                List.of(
                        "/open",
                        "/entry/list",
                        "/entry/get",
                        "/entry/path",
                        "/entry/search",
                        "/entry/create-folder",
                        "/entry/upload",
                        "/entry/rename",
                        "/entry/move",
                        "/entry/copy",
                        "/entry/trash",
                        "/entry/trash-list",
                        "/entry/restore")) expected.put("POST " + path, null);
        expected.put("GET /entry/content", null);

        Map<String, String> actual = new LinkedHashMap<>();
        for (Method method : RecordFolderController.class.getDeclaredMethods()) {
            GetMapping read = method.getAnnotation(GetMapping.class);
            PostMapping write = method.getAnnotation(PostMapping.class);
            if (read == null && write == null) continue;
            PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
            actual.put(
                    read != null ? "GET " + read.value()[0] : "POST " + write.value()[0],
                    guard == null ? null : guard.value());
        }

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(RecordFolderController.class.getAnnotation(PreAuthorize.class).value())
                .as("类级只要求登录，浏览接口的鉴权在服务里")
                .isEqualTo("isAuthenticated()");
    }

    @Test
    void configEndpointsPassThrough() throws Exception {
        RecordFolders.Source source =
                new RecordFolders.Source(
                        "4",
                        "2",
                        "FOLDER",
                        "DIRECT",
                        "",
                        7L,
                        8L,
                        null,
                        null,
                        "ON_FIRST_WRITE",
                        null,
                        "合同资料",
                        "业务档案 / 合同资料",
                        null,
                        null,
                        null,
                        null,
                        null);
        when(configs.list("2", ACTOR)).thenReturn(List.of(source));
        when(configs.save(any(), eq(ACTOR))).thenReturn(List.of(source));
        when(configs.candidates("2", ACTOR)).thenReturn(List.of());
        when(configs.nameFields("2", ACTOR))
                .thenReturn(List.of(new RecordFolders.NameField("9", "编号", "TEXT")));
        when(configs.backfill(any(), eq(ACTOR)))
                .thenReturn(new RecordFolders.BackfillResult("30", true, 3, 2, 1, 0, 0, List.of()));

        JsonNode listed =
                json.readTree(
                        mvc.perform(get("/nocode/record-folder/config").param("objectId", "2"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(listed.get("data").get(0).get("displayLabel").asText()).isEqualTo("合同资料");
        assertThat(listed.get("data").get(0).get("entryId").asLong()).isEqualTo(8L);

        String save =
                "{\"objectId\":\"2\",\"sources\":[{\"id\":null,\"kind\":\"FOLDER\",\"placement\":"
                    + "\"RECORD_SUBFOLDER\",\"label\":\"合同文件\",\"spaceId\":7,\"entryId\":\"8\","
                    + "\"relationFieldId\":null,\"targetSourceId\":null,\"createMode\":\"ON_SAVE\","
                    + "\"nameTemplate\":{\"separator\":\"-\",\"parts\":[{\"kind\":\"FIELD\","
                    + "\"fieldId\":\"9\",\"text\":null},{\"kind\":\"TEXT\",\"fieldId\":null,"
                    + "\"text\":\"资料\"}]}}]}";
        assertThat(data("/nocode/record-folder/config/save", save).get(0).get("id").asText())
                .isEqualTo("4");
        ArgumentCaptor<RecordFolders.SaveConfig> saved =
                ArgumentCaptor.forClass(RecordFolders.SaveConfig.class);
        verify(configs).save(saved.capture(), eq(ACTOR));
        RecordFolders.SourceInput input = saved.getValue().sources().getFirst();
        assertThat(input.spaceId()).isEqualTo(7L);
        assertThat(input.entryId()).as("网盘编号以字符串提交也收").isEqualTo(8L);
        assertThat(input.nameTemplate().parts())
                .containsExactly(
                        new RecordFolders.NamePart("FIELD", "9", null),
                        new RecordFolders.NamePart("TEXT", null, "资料"));

        assertThat(
                        json.readTree(
                                        mvc.perform(
                                                        get("/nocode/record-folder/config/candidates")
                                                                .param("objectId", "2"))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString(StandardCharsets.UTF_8))
                                .get("data"))
                .isEmpty();
        assertThat(
                        json.readTree(
                                        mvc.perform(
                                                        get("/nocode/record-folder/config/name-fields")
                                                                .param("objectId", "2"))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString(StandardCharsets.UTF_8))
                                .get("data")
                                .get(0)
                                .get("fieldId")
                                .asText())
                .isEqualTo("9");
        JsonNode backfill =
                data(
                        "/nocode/record-folder/config/backfill",
                        "{\"objectId\":\"2\",\"sourceId\":\"4\",\"cursor\":null,\"limit\":100}");
        assertThat(backfill.get("cursor").asText()).isEqualTo("30");
        assertThat(backfill.get("done").asBoolean()).isTrue();
        verify(configs).backfill(new RecordFolders.Backfill("2", "4", null, 100), ACTOR);
    }

    @Test
    void browseEndpointsDecodeTheCredentialAndPassThrough() throws Exception {
        RecordFolders.EntryQuery base =
                new RecordFolders.EntryQuery(
                        "1", "2", "3", "4", null, null, null, null, null, null);
        when(folders.open(new RecordFolders.OpenQuery("1", "2", "3"), ACTOR))
                .thenReturn(
                        new RecordFolders.Opened(
                                List.of(
                                        new RecordFolders.Tab(
                                                "4", "合同文件", "READY", null, false, true))));
        when(folders.list(any(), eq(ACTOR))).thenReturn(List.of(entry(11, "a.txt")));
        when(folders.get(any(), eq(ACTOR))).thenReturn(entry(11, "a.txt"));
        when(folders.path(any(), eq(ACTOR))).thenReturn(List.of("子", "孙"));
        when(folders.search(any(), eq(ACTOR))).thenReturn(List.of(entry(11, "a.txt")));
        when(folders.createFolder(any(), eq(ACTOR))).thenReturn(12L);
        when(folders.copy(any(), eq(ACTOR))).thenReturn(13L);
        when(folders.trashList(any(), eq(ACTOR))).thenReturn(List.of());
        when(folders.restore(any(), eq(ACTOR))).thenReturn(entry(11, "a.txt"));

        JsonNode opened =
                data(
                        "/nocode/record-folder/open",
                        "{\"applicationId\":\"1\",\"objectId\":\"2\",\"recordId\":\"3\"}");
        assertThat(opened.get("tabs").get(0).get("writable").asBoolean()).isFalse();
        assertThat(opened.get("tabs").get(0).get("canWrite").asBoolean()).isTrue();
        assertThat(opened.get("tabs").get(0).get("state").asText()).isEqualTo("READY");

        JsonNode listed = data("/nocode/record-folder/entry/list", credential("\"parentId\":0"));
        assertThat(listed.get(0).get("name").asText()).isEqualTo("a.txt");
        assertThat(listed.get(0).get("modifiable").asBoolean()).isTrue();
        assertThat(listed.get(0).get("parentId").asLong()).isZero();
        verify(folders)
                .list(
                        new RecordFolders.EntryQuery(
                                "1", "2", "3", "4", null, 0L, null, null, null, null),
                        ACTOR);

        assertThat(
                        data("/nocode/record-folder/entry/get", credential("\"id\":11"))
                                .get("id")
                                .asLong())
                .isEqualTo(11L);
        assertThat(data("/nocode/record-folder/entry/path", credential("\"id\":\"11\"")))
                .extracting(JsonNode::asText)
                .containsExactly("子", "孙");
        data("/nocode/record-folder/entry/search", credential("\"name\":\"txt\",\"limit\":20"));
        verify(folders)
                .search(
                        new RecordFolders.EntryQuery(
                                "1", "2", "3", "4", null, null, null, null, "txt", 20),
                        ACTOR);
        assertThat(
                        data(
                                        "/nocode/record-folder/entry/create-folder",
                                        credential("\"parentId\":0,\"name\":\"资料\""))
                                .asLong())
                .isEqualTo(12L);
        assertThat(
                        data(
                                        "/nocode/record-folder/entry/rename",
                                        credential("\"id\":11,\"name\":\"b.txt\""))
                                .asBoolean())
                .isTrue();
        verify(folders)
                .rename(
                        new RecordFolders.EntryQuery(
                                "1", "2", "3", "4", 11L, null, null, null, "b.txt", null),
                        ACTOR);
        assertThat(
                        data(
                                        "/nocode/record-folder/entry/move",
                                        credential("\"id\":11,\"targetParentId\":12"))
                                .asBoolean())
                .isTrue();
        verify(folders)
                .move(
                        new RecordFolders.EntryQuery(
                                "1", "2", "3", "4", 11L, null, 12L, null, null, null),
                        ACTOR);
        assertThat(
                        data(
                                        "/nocode/record-folder/entry/copy",
                                        credential("\"id\":11,\"targetParentId\":0"))
                                .asLong())
                .isEqualTo(13L);
        assertThat(
                        data("/nocode/record-folder/entry/trash", credential("\"ids\":[11,\"12\"]"))
                                .asBoolean())
                .isTrue();
        verify(folders)
                .trash(
                        new RecordFolders.EntryQuery(
                                "1",
                                "2",
                                "3",
                                "4",
                                null,
                                null,
                                null,
                                List.of(11L, 12L),
                                null,
                                null),
                        ACTOR);
        assertThat(data("/nocode/record-folder/entry/trash-list", credential(""))).isEmpty();
        verify(folders).trashList(base, ACTOR);
        assertThat(
                        data("/nocode/record-folder/entry/restore", credential("\"id\":11"))
                                .get("name")
                                .asText())
                .isEqualTo("a.txt");
    }

    @Test
    void requestBodiesAreDecodedStrictly() {
        // 清点项：未知字段拒绝；null 与缺省字段接受；数据维护入口的应用编号空白归一为 null
        assertThatThrownBy(() -> controller.list(json.readTree(credential("\"rootEntryId\":99"))))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("运行请求结构无效"));
        assertThatThrownBy(() -> controller.list(json.readTree(credential("\"parentId\":1.5"))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> controller.list(json.readTree("[]")))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("请求必须是对象"));
        assertThatCode(
                        () ->
                                controller.list(
                                        json.readTree(
                                                "{\"applicationId\":\"  \",\"objectId\":\"2\","
                                                        + "\"recordId\":\"3\",\"sourceId\":\"4\","
                                                        + "\"ids\":null}")))
                .doesNotThrowAnyException();
        verify(folders)
                .list(
                        new RecordFolders.EntryQuery(
                                null, "2", "3", "4", null, null, null, null, null, null),
                        ACTOR);
        verifyNoMoreInteractions(folders);
    }

    @Test
    void uploadTakesMultipartFieldsAndDefaultsToTheRoot() throws Exception {
        when(folders.upload(any(), eq(ACTOR), any())).thenReturn(entry(21, "合同.pdf"));
        MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf", BODY);

        MockHttpServletResponse response =
                mvc.perform(
                                multipart("/nocode/record-folder/entry/upload")
                                        .file(file)
                                        .param("applicationId", "")
                                        .param("objectId", "2")
                                        .param("recordId", "3")
                                        .param("sourceId", "4"))
                        .andReturn()
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(
                        json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                                .get("data")
                                .get("id")
                                .asLong())
                .isEqualTo(21L);
        ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
        verify(folders)
                .upload(
                        eq(
                                new RecordFolders.UploadQuery(
                                        null, "2", "3", "4", 0L, "合同.pdf", "application/pdf", 10L)),
                        eq(ACTOR),
                        content.capture());
        assertThat(content.getValue().readAllBytes()).isEqualTo(BODY);

        mvc.perform(
                multipart("/nocode/record-folder/entry/upload")
                        .file(file)
                        .param("applicationId", "1")
                        .param("objectId", "2")
                        .param("recordId", "3")
                        .param("sourceId", "4")
                        .param("parentId", "12"));
        verify(folders)
                .upload(
                        eq(
                                new RecordFolders.UploadQuery(
                                        "1", "2", "3", "4", 12L, "合同.pdf", "application/pdf", 10L)),
                        eq(ACTOR),
                        any());
    }

    private RecordFolders.Content content(String mimeType, Long length) {
        return new RecordFolders.Content(
                31L, "contract.pdf", mimeType, 10L, length, 7L, 8L, "VIEWER", "2:3");
    }

    private MockHttpServletResponse serve(String range, String inline) throws Exception {
        var request =
                get("/nocode/record-folder/entry/content")
                        .param("applicationId", "")
                        .param("objectId", "2")
                        .param("recordId", "3")
                        .param("sourceId", "4")
                        .param("id", "31");
        if (inline != null) request.param("inline", inline);
        if (range != null) request.header(HttpHeaders.RANGE, range);
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void contentServesWholeFileAsAttachment() throws Exception {
        RecordFolders.Content content = content("application/pdf", 10L);
        when(folders.content(new RecordFolders.ContentQuery(null, "2", "3", "4", 31L), ACTOR))
                .thenReturn(content);
        when(folders.contentStream(content, ACTOR, 0L)).thenReturn(new ByteArrayInputStream(BODY));

        MockHttpServletResponse response = serve(null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("10");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment;filename=contract.pdf");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(response.getHeader("Content-Security-Policy"))
                .as("与网盘自己的内容接口一致：不加内容安全策略头")
                .isNull();
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isNull();
    }

    @Test
    void contentServesRangesAndRejectsUnsatisfiableOnes() throws Exception {
        RecordFolders.Content content = content("application/pdf", 10L);
        when(folders.content(any(), eq(ACTOR))).thenReturn(content);
        when(folders.contentStream(content, ACTOR, 2L))
                .thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse partial = serve("bytes=2-5", "true");
        assertThat(partial.getStatus()).isEqualTo(206);
        assertThat(partial.getContentAsByteArray())
                .isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
        assertThat(partial.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(partial.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("4");
        assertThat(partial.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline;filename=contract.pdf");

        MockHttpServletResponse beyond = serve("bytes=20-30", null);
        assertThat(beyond.getStatus()).isEqualTo(416);
        assertThat(beyond.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        assertThat(beyond.getContentAsByteArray()).isEmpty();
        verify(folders, never()).contentStream(any(), anyLong(), eq(20L));
        verify(folders, times(1)).contentStream(any(), anyLong(), anyLong());
    }

    @Test
    void contentFallsBackWhenStorageFactsAreMissing() throws Exception {
        // 存储侧给不出长度时按节点记录的大小；节点上没有类型时按二进制流
        RecordFolders.Content content = content(null, null);
        when(folders.content(any(), eq(ACTOR))).thenReturn(content);
        when(folders.contentStream(content, ACTOR, 5L))
                .thenReturn(new ByteArrayInputStream("56789".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = serve("bytes=5-", null);

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentType()).isEqualTo("application/octet-stream");
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-9/10");
        assertThat(response.getContentAsByteArray())
                .isEqualTo("56789".getBytes(StandardCharsets.UTF_8));
    }

    /** 清点项：哪些类型会被内联取决于现有的响应头工具——与网盘自己的内容接口同一个风险面，这里只记录现象。 */
    @Test
    void activeContentIsNeverServedInline() throws Exception {
        for (String type :
                List.of(
                        "text/html",
                        "TEXT/HTML; charset=UTF-8",
                        "application/xhtml+xml",
                        "image/svg+xml",
                        "text/xml",
                        "application/xml",
                        "text/xsl",
                        "text/javascript",
                        "application/ecmascript")) {
            for (String inline : Arrays.asList("true", "false", null)) {
                RecordFolders.Content content = content(type, 10L);
                when(folders.content(any(), eq(ACTOR))).thenReturn(content);
                when(folders.contentStream(content, ACTOR, 0L))
                        .thenReturn(new ByteArrayInputStream(BODY));

                MockHttpServletResponse response = serve(null, inline);

                assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                        .as("%s inline=%s 只能下载", type, inline)
                        .startsWith("attachment;");
                assertThat(response.getHeader("Content-Security-Policy"))
                        .as("%s inline=%s 带沙箱策略", type, inline)
                        .isEqualTo("default-src 'none'; sandbox");
                assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
            }
        }
    }

    @Test
    void passiveContentStillOpensInline() throws Exception {
        for (String type :
                List.of("application/pdf", "image/png", "text/plain", "video/mp4", "audio/mpeg")) {
            RecordFolders.Content content = content(type, 10L);
            when(folders.content(any(), eq(ACTOR))).thenReturn(content);
            when(folders.contentStream(content, ACTOR, 0L))
                    .thenReturn(new ByteArrayInputStream(BODY));

            MockHttpServletResponse inline = serve(null, "true");

            assertThat(inline.getContentType()).isEqualTo(type);
            assertThat(inline.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .as("%s 可在页面内打开", type)
                    .startsWith("inline;");
            assertThat(inline.getHeader("Content-Security-Policy")).isNull();
            assertThat(inline.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }
}
