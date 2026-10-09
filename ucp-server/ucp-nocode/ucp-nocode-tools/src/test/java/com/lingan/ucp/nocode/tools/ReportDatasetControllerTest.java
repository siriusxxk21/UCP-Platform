package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.controller.admin.report.ReportDatasetController;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetCatalogService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService;
import com.lingan.ucp.nocode.web.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

/** 使用真实 MVC、严格 JSON 解码及方法安全代理验证入口；业务授权与真库查询由独立集成测试覆盖。 */
class ReportDatasetControllerTest {
    @Configuration
    @EnableMethodSecurity
    static class SecurityConfig {}

    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;
    private ReportDatasetService datasets;
    private ReportDatasetQueryService queries;
    private ReportDatasetAuthorizationService authorization;
    private final Set<String> allowed = new HashSet<>();

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext();
        NocodeAccess access = mock(NocodeAccess.class);
        when(access.actor()).thenReturn(10001L);
        when(access.has(anyString()))
                .thenAnswer(
                        call ->
                                SecurityContextHolder.getContext().getAuthentication() != null
                                        && allowed.contains(call.getArgument(0)));
        datasets = mock(ReportDatasetService.class);
        queries = mock(ReportDatasetQueryService.class);
        authorization = mock(ReportDatasetAuthorizationService.class);
        context.getBeanFactory().registerSingleton("nocodeAccess", access);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ReportDatasetService.class, () -> datasets);
        context.registerBean(
                ReportDatasetCatalogService.class, () -> mock(ReportDatasetCatalogService.class));
        context.registerBean(ReportDatasetQueryService.class, () -> queries);
        context.registerBean(ReportDatasetAuthorizationService.class, () -> authorization);
        context.register(
                SecurityConfig.class, StrictRequestDecoder.class, ReportDatasetController.class);
        context.refresh();
        mvc =
                MockMvcBuilders.standaloneSetup(context.getBean(ReportDatasetController.class))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("fixture", "", List.of()));
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
        allowed.clear();
    }

    @Test
    void authorizationObjectCatalogRequiresOnlyObjectSharePermission() throws Exception {
        allowed.add("nocode:report:query");
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        get("/nocode/report/dataset/authorization-objects")
                                                .param("id", "12")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        allowed.clear();
        allowed.add("nocode:object:share");
        mvc.perform(get("/nocode/report/dataset/authorization-objects").param("id", "12"))
                .andExpect(status().isOk());
        verify(context.getBean(ReportDatasetCatalogService.class))
                .authorizationObjects("12", 10001L);
    }

    @Test
    void optionsUseAuthenticatedActorAndStrictDecode() throws Exception {
        allowed.add("nocode:report:query");
        mvc.perform(
                        post("/nocode/report/dataset/options")
                                .contentType("application/json")
                                .content(
                                        "{\"datasetId\":\"42\",\"versionNo\":3,\"checksum\":\"fixed-sum\",\"preview\":false,\"fieldId\":\"name\",\"pageNo\":1,\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<ReportDatasetQueries.Options> command =
                ArgumentCaptor.forClass(ReportDatasetQueries.Options.class);
        verify(queries).options(command.capture(), eq(10001L));
        assertThat(command.getValue().versionNo()).isEqualTo(3);
        assertThat(command.getValue().fieldId()).isEqualTo("name");
    }

    @Test
    void usesAuthenticatedActorAndPreservesFixedDatasetReference() throws Exception {
        allowed.add("nocode:report:query");
        mvc.perform(
                        post("/nocode/report/dataset/query")
                                .contentType("application/json")
                                .content(query()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<ReportDatasetQueries.Query> command =
                ArgumentCaptor.forClass(ReportDatasetQueries.Query.class);
        verify(queries).query(command.capture(), eq(10001L));
        assertThat(command.getValue().datasetId()).isEqualTo("42");
        assertThat(command.getValue().versionNo()).isEqualTo(3);
        assertThat(command.getValue().checksum()).isEqualTo("fixed-sum");
        assertThat(command.getValue().preview()).isFalse();
    }

    @Test
    void rejectsUnknownActorAndFractionalRevisionBeforeCallingServices() {
        allowed.add("nocode:report:query");
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/query")
                                                .contentType("application/json")
                                                .content(
                                                        query().replace(
                                                                        "\"limit\":20",
                                                                        "\"limit\":20,\"actor\":1"))))
                .hasRootCauseInstanceOf(ServiceException.class);
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/save")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"id\":\"42\",\"expectedRevision\":1.5,\"name\":\"数据集\"}")))
                .hasRootCauseInstanceOf(ServiceException.class);
        verifyNoInteractions(datasets, queries, authorization);
    }

    @Test
    void refusesAnonymousAndSeparatesObjectSharingFromReportManagement() {
        allowed.add("nocode:report:query");
        SecurityContextHolder.clearContext();
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/query")
                                                .contentType("application/json")
                                                .content(query())))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("fixture", "", List.of()));
        allowed.add("nocode:report:manage");
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/ceiling")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"datasetId\":\"42\",\"objectId\":\"7\",\"expectedRevision\":1,\"permission\":null,\"reason\":\"撤权\"}")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(datasets, queries, authorization);
    }

    @Test
    void copyAndDeleteRequireTheirOwnPermissionsAndRejectActorInjection() throws Exception {
        allowed.add("nocode:report:query");
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/copy")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"id\":\"42\",\"expectedRevision\":1,\"name\":\"副本\",\"reason\":\"测试\"}")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        get("/nocode/report/dataset/delete-preview")
                                                .param("id", "42")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(datasets);
        allowed.add("nocode:report:manage");
        assertThatThrownBy(
                        () ->
                                mvc.perform(
                                        post("/nocode/report/dataset/delete")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"id\":\"42\",\"expectedRevision\":1,\"reason\":\"测试\",\"actor\":\"10002\"}")))
                .hasRootCauseInstanceOf(ServiceException.class);
        verifyNoInteractions(datasets);
        mvc.perform(get("/nocode/report/dataset/delete-preview").param("id", "42"))
                .andExpect(status().isOk());
        verify(datasets).deletePreview("42", 10001L);
    }

    private String query() {
        return "{\"datasetId\":\"42\",\"versionNo\":3,\"checksum\":\"fixed-sum\",\"preview\":false,\"dimensions\":[],\"metrics\":[{\"id\":\"count\",\"name\":\"记录数\",\"operation\":\"COUNT\"}],\"equal\":{},\"limit\":20,\"timeZone\":\"Asia/Shanghai\"}";
    }
}
