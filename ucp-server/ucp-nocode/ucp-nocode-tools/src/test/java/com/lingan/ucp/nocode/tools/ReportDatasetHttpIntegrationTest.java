package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 显式启用的当前开发服务 HTTP 验收；数据库复用标准夹具装配，登录凭据只在内存。 */
@EnabledIfSystemProperty(named = "report.http", matches = "true")
class ReportDatasetHttpIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base = System.getProperty("report.http.base", "http://127.0.0.1:8080/api");
    private NocodeIntegrationSupport fixture;
    private String token;
    private String datasetId;
    private String sourceDatasetId;
    private boolean retainedDemo;
    private Long dataAdminUser, dataAdminRole;
    private String dataAdminToken;
    private Long designerUser, designerRole;
    private String designerToken;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() throws Exception {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        Map<String, String> credentials = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("../../../ucp-front/.env.test"))) {
            int split = line.indexOf('=');
            if (split < 0
                    || !Set.of("E2E_USERNAME", "E2E_PASSWORD").contains(line.substring(0, split)))
                continue;
            String value = line.substring(split + 1).trim();
            if (value.length() > 1
                    && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            credentials.put(line.substring(0, split), value);
        }
        token =
                ok(
                                "/system/auth/login",
                                Map.of(
                                        "username",
                                        credentials.get("E2E_USERNAME"),
                                        "password",
                                        credentials.get("E2E_PASSWORD")))
                        .path("accessToken")
                        .asText();
        assertThat(token).isNotBlank();
        credentials.clear();
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (retainedDemo) return;
            List<String> cleanupIds =
                    datasetId == null
                            ? new ArrayList<>()
                            : new ArrayList<>(
                                    jdbc.queryForList(
                                            "SELECT resource_id::text FROM"
                                                + " public.nocode_report_operation_log WHERE"
                                                + " resource_kind='DATASET' AND action='COPY' AND"
                                                + " before_json->>'id'=?",
                                            String.class,
                                            datasetId));
            if (datasetId != null) cleanupIds.add(datasetId);
            if (sourceDatasetId != null) cleanupIds.add(sourceDatasetId);
            if (designerUser != null)
                cleanupIds.addAll(
                        jdbc.queryForList(
                                "SELECT id::text FROM public.nocode_report_dataset WHERE owner_id=?"
                                        + " AND name IN (?,?)",
                                String.class,
                                designerUser,
                                fixture.prefix + "HTTP 数据集 普通制作",
                                fixture.prefix + "HTTP 数据集 拥有者闭环"));
            for (String cleanupId : cleanupIds) {
                long id = Long.parseLong(cleanupId);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT name FROM public.nocode_report_dataset WHERE id=?",
                                        String.class,
                                        id))
                        .startsWith(fixture.prefix);
                // 名称绑定本次随机前缀，也清理尚未保存组件、没有依赖登记的空看板。
                List<Long> dashboards =
                        jdbc.queryForList(
                                "SELECT b.id FROM public.nocode_report_dashboard b WHERE b.name"
                                        + " LIKE ? OR EXISTS (SELECT 1 FROM"
                                        + " public.nocode_report_operation_log l WHERE"
                                        + " l.resource_kind='DASHBOARD' AND l.resource_id=b.id AND"
                                        + " l.action='SAVE' AND l.after_json->>'name' LIKE ?)",
                                Long.class,
                                fixture.prefix + "%",
                                fixture.prefix + "%");
                for (Long dashboard : dashboards) {
                    jdbc.update(
                            "DELETE FROM public.nocode_report_operation_log WHERE"
                                    + " resource_kind='DASHBOARD' AND resource_id=?",
                            dashboard);
                    jdbc.update(
                            "DELETE FROM public.nocode_report_dependency WHERE"
                                    + " source_kind='DASHBOARD' AND source_id=?",
                            dashboard);
                    jdbc.update(
                            "DELETE FROM public.nocode_report_dashboard_version WHERE"
                                    + " dashboard_id=?",
                            dashboard);
                    jdbc.update("DELETE FROM public.nocode_report_dashboard WHERE id=?", dashboard);
                }
                ReportIntegrationSupport.clearAuthorization(cleanupId);
                jdbc.update(
                        "DELETE FROM public.nocode_resource_dependency WHERE source_kind='DATASET'"
                                + " AND source_key LIKE ?",
                        cleanupId + ":%");
                jdbc.update(
                        "DELETE FROM public.nocode_report_operation_log WHERE"
                                + " resource_kind='DATASET' AND resource_id=?",
                        id);
                jdbc.update(
                        "DELETE FROM public.nocode_report_dataset_version WHERE dataset_id=?", id);
                jdbc.update(
                        "DELETE FROM public.nocode_report_dependency WHERE target_kind='DATASET'"
                                + " AND target_id=?",
                        id);
                jdbc.update("DELETE FROM public.nocode_report_dataset WHERE id=?", id);
            }
            // 目录夹具由页面创建，CREATE 审计精确绑定本次随机数据集名称；子目录后创建，按 ID 逆序清理。
            if (datasetId != null) {
                List<Long> directoryIds =
                        jdbc.queryForList(
                                "SELECT resource_id FROM public.nocode_report_operation_log WHERE"
                                        + " resource_kind='FOLDER' AND action='CREATE' AND reason=?"
                                        + " ORDER BY resource_id DESC",
                                Long.class,
                                fixture.prefix + "HTTP 数据集 目录验收");
                for (Long directoryId : directoryIds) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT name FROM public.nocode_report_folder WHERE"
                                                    + " id=?",
                                            String.class,
                                            directoryId))
                            .startsWith(fixture.prefix);
                    jdbc.update(
                            "DELETE FROM public.nocode_report_operation_log WHERE"
                                    + " resource_kind='FOLDER' AND resource_id=?",
                            directoryId);
                    jdbc.update("DELETE FROM public.nocode_report_folder WHERE id=?", directoryId);
                }
            }
            fixture.clean();
        } finally {
            try {
                if (designerToken != null)
                    call(designerToken, "POST", "/system/auth/logout", Map.of());
                if (designerUser != null) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT remark FROM public.system_users WHERE id=?",
                                            String.class,
                                            designerUser))
                            .isEqualTo(fixture.prefix);
                    assertThat(
                                    call(
                                                    token,
                                                    "DELETE",
                                                    "/system/user/delete?id=" + designerUser,
                                                    null)
                                            .path("code")
                                            .asInt())
                            .isZero();
                }
                if (designerRole != null) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT code FROM public.system_role WHERE id=?",
                                            String.class,
                                            designerRole))
                            .isEqualTo(fixture.prefix + "_designer");
                    assertThat(
                                    call(
                                                    token,
                                                    "DELETE",
                                                    "/system/role/delete?id=" + designerRole,
                                                    null)
                                            .path("code")
                                            .asInt())
                            .isZero();
                }
                if (dataAdminToken != null)
                    call(dataAdminToken, "POST", "/system/auth/logout", Map.of());
                if (dataAdminUser != null) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT remark FROM public.system_users WHERE id=?",
                                            String.class,
                                            dataAdminUser))
                            .isEqualTo(fixture.prefix);
                    assertThat(
                                    call(
                                                    token,
                                                    "DELETE",
                                                    "/system/user/delete?id=" + dataAdminUser,
                                                    null)
                                            .path("code")
                                            .asInt())
                            .isZero();
                }
                if (dataAdminRole != null) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT code FROM public.system_role WHERE id=?",
                                            String.class,
                                            dataAdminRole))
                            .isEqualTo(fixture.prefix);
                    assertThat(
                                    call(
                                                    token,
                                                    "DELETE",
                                                    "/system/role/delete?id=" + dataAdminRole,
                                                    null)
                                            .path("code")
                                            .asInt())
                            .isZero();
                }
            } finally {
                if (token != null) call("/system/auth/logout", Map.of());
            }
            token = null;
        }
    }

    @Test
    void defaultObjectAccessSupportsPreviewAndBrowserPublication() throws Exception {
        ObjectDraft draft = fixture.create("http");
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "HTTP 来源夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        String field = draft.fields().getFirst().id();
        jdbc.update(
                "INSERT INTO public.\"" + draft.tableName() + "\"(name) VALUES ('A'),('B'),('B')");
        String actor =
                ok("/system/auth/get-permission-info", null).path("user").path("id").asText();
        assertThat(actor).isNotBlank();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), published.versionNo(), published.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), field, "名称", "DIMENSION")));
        ReportDatasets.Analysis analysis =
                new ReportDatasets.Analysis(
                        1,
                        List.of(new ReportDatasetQueries.Metric("rows", "记录数", "COUNT", null)),
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition("name", "eq", "B")),
                                List.of()),
                        Map.of(),
                        "Asia/Shanghai");
        JsonNode saved =
                ok(
                        "/nocode/report/dataset/save",
                        new ReportDatasets.Save(
                                null,
                                0,
                                fixture.prefix + "HTTP 数据集",
                                "真实 HTTP 验证",
                                source,
                                analysis));
        datasetId = saved.path("id").asText();
        assertThat(datasetId).isNotBlank();
        assertThat(ok("/nocode/report/dataset/ceilings?id=" + datasetId, null)).isEmpty();
        assertThat(ok("/nocode/report/dataset/data-policy?id=" + datasetId, null).path("members"))
                .isEmpty();
        ReportDatasetQueries.Query preview =
                new ReportDatasetQueries.Query(
                        datasetId,
                        null,
                        null,
                        true,
                        List.of(),
                        null,
                        Map.of(),
                        20,
                        null,
                        List.of("rows"));
        assertThat(ok("/nocode/report/dataset/query", preview).path("recordCount").asLong())
                .isEqualTo(2);
        if (Boolean.getBoolean("report.browser")) {
            Path log =
                    Path.of("target/report-default-browser-" + datasetId + ".log").toAbsolutePath();
            ProcessBuilder builder =
                    new ProcessBuilder("node", "tools/nocode-e2e/verify-report-default-access.mjs")
                            .directory(Path.of("../../../ucp-front").toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            builder.environment().put("REPORT_TEST_TOKEN", token);
            builder.environment().put("REPORT_TEST_DATASET", datasetId);
            Process process = builder.start();
            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            assertThat(finished).isTrue();
            assertThat(process.exitValue()).as("默认权限浏览器验收：%s", log).isZero();
        } else {
            ok(
                    "/nocode/report/dataset/publish",
                    new ReportDatasets.Publish(
                            datasetId,
                            saved.path("revision").asInt(),
                            "default_publish",
                            "默认权限发布"));
        }
        JsonNode current = ok("/nocode/report/dataset/get?id=" + datasetId, null);
        ReportDatasetQueries.Query query =
                new ReportDatasetQueries.Query(
                        datasetId,
                        current.path("publishedVersion").asInt(),
                        current.path("checksum").asText(),
                        false,
                        List.of(),
                        null,
                        Map.of(),
                        20,
                        null,
                        List.of("rows"));
        assertThat(ok("/nocode/report/dataset/query", query).path("recordCount").asLong())
                .isEqualTo(2);
        assertThat(ok("/nocode/report/dataset/ceilings?id=" + datasetId, null)).isEmpty();
    }

    @Test
    @EnabledIfSystemProperty(named = "report.legacy.authorization", matches = "true")
    void savesPublishesQueriesAndRevokesThroughTheRunningServer() throws Exception {
        if ("sources".equals(System.getProperty("report.browser.scope"))) {
            verifySourceBrowser();
            return;
        }
        ObjectDraft draft = fixture.create("http");
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "HTTP 来源夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        String field = draft.fields().getFirst().id();
        jdbc.update(
                "INSERT INTO public.\"" + draft.tableName() + "\"(name) VALUES ('A'),('B'),('B')");
        String actor =
                ok("/system/auth/get-permission-info", null).path("user").path("id").asText();
        assertThat(actor).isNotBlank();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), published.versionNo(), published.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), field, "名称", "DIMENSION")));
        ReportDatasets.Analysis analysis =
                new ReportDatasets.Analysis(
                        1,
                        List.of(new ReportDatasetQueries.Metric("rows", "记录数", "COUNT", null)),
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition("name", "eq", "B")),
                                List.of()),
                        Map.of(),
                        "Asia/Shanghai");
        JsonNode saved =
                ok(
                        "/nocode/report/dataset/save",
                        new ReportDatasets.Save(
                                null,
                                0,
                                fixture.prefix + "HTTP 数据集",
                                "真实 HTTP 验证",
                                source,
                                analysis));
        datasetId = saved.path("id").asText();
        assertThat(datasetId).isNotBlank();
        assertThat(
                        ok("/nocode/report/dataset/get?id=" + datasetId, null)
                                .path("draft")
                                .path("analysis")
                                .path("metrics")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo("rows");
        JsonNode candidate = ok("/nocode/report/dataset/source-object?id=" + draft.id(), null);
        assertThat(candidate.path("reference").path("checksum").asText())
                .isEqualTo(published.checksum());
        assertThat(candidate.has("definition")).isFalse();
        assertThat(candidate.toString()).doesNotContain("schemaName", "tableName", "columnName");
        assertThat(
                        ok(
                                        "/nocode/report/dataset/authorization-targets?search="
                                                + fixture.prefix,
                                        null)
                                .path("list")
                                .get(0)
                                .path("sources")
                                .get(0)
                                .path("objectId")
                                .asText())
                .isEqualTo(draft.id());
        ReportDatasets.Publish publication =
                new ReportDatasets.Publish(
                        datasetId, saved.path("revision").asInt(), "http_publish", "HTTP 验证发布");
        assertThat(call("/nocode/report/dataset/publish", publication).path("code").asInt())
                .isNotZero();
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        draft.id(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(field),
                        Set.of(),
                        Set.of(),
                        Set.of());
        ok(
                "/nocode/report/dataset/ceiling",
                new ReportAuthorization.SaveCeiling(datasetId, draft.id(), 0, grant, "HTTP 显式上限"));
        JsonNode release = ok("/nocode/report/dataset/publish", publication);
        ReportDatasetQueries.Query query =
                new ReportDatasetQueries.Query(
                        datasetId,
                        release.path("versionNo").asInt(),
                        release.path("checksum").asText(),
                        false,
                        List.of(),
                        null,
                        Map.of(),
                        20,
                        null,
                        List.of("rows"));
        assertThat(call("/nocode/report/dataset/query", query).path("code").asInt()).isNotZero();
        ok(
                "/nocode/report/dataset/data-policy",
                new ReportAuthorization.SaveDataPolicy(
                        datasetId,
                        0,
                        List.of(new ApplicationAuthorization.Member("USER", actor, List.of(grant))),
                        "HTTP 显式成员"));
        JsonNode result = ok("/nocode/report/dataset/query", query);
        assertThat(result.path("totals").path("rows").asText()).isEqualTo("2");
        assertThat(result.path("recordCount").asLong()).isEqualTo(2);
        assertThat(result.path("canExport").asBoolean()).isFalse();
        if (Boolean.getBoolean("report.browser")) {
            if (!"identities".equals(System.getProperty("report.browser.scope"))) {
                verifyBrowser();
                // 原制作验收有意重配草稿；身份矩阵恢复自己的固定条件基线，不改已有发布版本。
                ok(
                        "/nocode/report/dataset/save",
                        new ReportDatasets.Save(
                                datasetId,
                                ok("/nocode/report/dataset/get?id=" + datasetId, null)
                                        .path("revision")
                                        .asInt(),
                                fixture.prefix + "HTTP 数据集",
                                "身份矩阵来源",
                                source,
                                analysis));
            }
            verifyDesignerBrowser();
        }
        verifyDataAdministrator();
        if (Boolean.getBoolean("report.browser")) {
            verifyOwnerBrowser();
            verifySourceBrowser();
        }
        ReportDatasetQueries.Query narrowed =
                new ReportDatasetQueries.Query(
                        datasetId,
                        query.versionNo(),
                        query.checksum(),
                        false,
                        List.of(),
                        null,
                        Map.of("name", "A"),
                        20,
                        null,
                        List.of("rows"));
        assertThat(ok("/nocode/report/dataset/query", narrowed).path("recordCount").asLong())
                .isZero();
        JsonNode tampered = json.valueToTree(query);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("actor", "1");
        assertThat(call("/nocode/report/dataset/query", tampered).path("code").asInt()).isNotZero();
        ok(
                "/nocode/report/dataset/ceiling",
                new ReportAuthorization.SaveCeiling(
                        datasetId,
                        draft.id(),
                        ok("/nocode/report/dataset/ceilings?id=" + datasetId, null)
                                .get(0)
                                .path("revision")
                                .asInt(),
                        null,
                        "HTTP 撤销上限"));
        assertThat(call("/nocode/report/dataset/query", query).path("code").asInt()).isNotZero();
    }

    /** 浏览器复用本测试的显式授权夹具，结束后仍由 finally 清理，不遗留体验数据。 */
    private void verifyDataAdministrator() throws Exception {
        List<Long> menus = new ArrayList<>();
        for (JsonNode menu : ok("/system/menu/list", null)) {
            if (Set.of("/nocode/report-center", "/nocode/report-center/data-authorization")
                    .contains(menu.path("path").asText())) menus.add(menu.path("id").asLong());
        }
        assertThat(menus).hasSize(2);
        dataAdminRole =
                ok(
                                "/system/role/create",
                                Map.of(
                                        "name",
                                        "报表上限验收",
                                        "code",
                                        fixture.prefix,
                                        "sort",
                                        999,
                                        "status",
                                        0,
                                        "remark",
                                        fixture.prefix))
                        .asLong();
        ok(
                "/system/permission/assign-role-menu",
                Map.of("roleId", dataAdminRole, "menuIds", menus));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "ncrp" + suffix, password = "Nc1" + suffix;
        dataAdminUser =
                ok(
                                "/system/user/create",
                                Map.of(
                                        "username",
                                        username,
                                        "nickname",
                                        "报表数据管理员验收",
                                        "password",
                                        password,
                                        "remark",
                                        fixture.prefix,
                                        "postIds",
                                        List.of(),
                                        "sex",
                                        0))
                        .asLong();
        ok(
                "/system/permission/assign-user-role",
                Map.of("userId", dataAdminUser, "roleIds", List.of(dataAdminRole)));
        JsonNode auth =
                call(
                                null,
                                "POST",
                                "/system/auth/login",
                                Map.of("username", username, "password", password))
                        .path("data");
        if (!auth.hasNonNull("accessToken"))
            auth =
                    call(
                                    null,
                                    "PUT",
                                    "/system/auth/change-required-password",
                                    Map.of(
                                            "passwordChangeToken",
                                            auth.path("passwordChangeToken").asText(),
                                            "newPassword",
                                            "Nc2" + suffix))
                            .path("data");
        dataAdminToken = auth.path("accessToken").asText();
        assertThat(dataAdminToken).isNotBlank();
        JsonNode identity =
                call(dataAdminToken, "GET", "/system/auth/get-permission-info", null).path("data");
        assertThat(identity.path("permissions").toString())
                .contains("nocode:object:share")
                .doesNotContain("nocode:report:", "nocode:object:query");
        assertThat(
                        call(
                                        dataAdminToken,
                                        "GET",
                                        "/nocode/report/dataset/authorization-targets?search="
                                                + fixture.prefix,
                                        null)
                                .path("code")
                                .asInt())
                .isZero();
        JsonNode objects =
                call(
                        dataAdminToken,
                        "GET",
                        "/nocode/report/dataset/authorization-objects?id=" + datasetId,
                        null);
        assertThat(objects.path("code").asInt()).isZero();
        assertThat(objects.path("data").get(0).path("definition").path("fields").size())
                .isPositive();
        for (String path :
                List.of(
                        "/get?id=" + datasetId,
                        "/page",
                        "/source-objects",
                        "/resource-policy?id=" + datasetId,
                        "/data-policy?id=" + datasetId))
            assertThat(
                            call(dataAdminToken, "GET", "/nocode/report/dataset" + path, null)
                                    .path("code")
                                    .asInt())
                    .as(path)
                    .isNotZero();
        assertThat(
                        call(
                                        dataAdminToken,
                                        "POST",
                                        "/nocode/report/dataset/query",
                                        Map.of("datasetId", datasetId, "preview", true))
                                .path("code")
                                .asInt())
                .isNotZero();
        if (Boolean.getBoolean("report.browser")) {
            Path log =
                    Path.of("target/report-data-admin-browser-" + datasetId + ".log")
                            .toAbsolutePath();
            ProcessBuilder builder =
                    new ProcessBuilder("node", "tools/nocode-e2e/verify-report-data-admin.mjs")
                            .directory(Path.of("../../../ucp-front").toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            builder.environment().put("REPORT_TEST_TOKEN", dataAdminToken);
            builder.environment().put("REPORT_TEST_OWNER_TOKEN", token);
            builder.environment().put("REPORT_TEST_DATASET", datasetId);
            builder.environment()
                    .put("REPORT_TEST_SCOPE", System.getProperty("report.browser.scope", "all"));
            Process process = builder.start();
            boolean finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            assertThat(finished).as("独立授权浏览器验收应在三分钟内结束").isTrue();
            assertThat(process.exitValue()).as("独立授权浏览器日志：%s", log).isZero();
        }
    }

    /** 最小制作角色不持数据授权或对象共享权；权限来自真实底座角色，数据范围由页面流程验证。 */
    private void verifyDesignerBrowser() throws Exception {
        Set<String> codes =
                Set.of(
                        "nocode:report:query",
                        "nocode:report:create",
                        "nocode:report:update",
                        "nocode:report:publish",
                        "nocode:object:query");
        List<Long> menus = new ArrayList<>();
        Map<Long, JsonNode> menuIndex = new HashMap<>();
        for (JsonNode menu : ok("/system/menu/list", null)) {
            menuIndex.put(menu.path("id").asLong(), menu);
            if (codes.contains(menu.path("permission").asText()))
                menus.add(menu.path("id").asLong());
        }
        // 底座会过滤缺少父菜单的授权，按实际树补齐祖先，不硬编码历史路由。
        for (int i = 0; i < menus.size(); i++) {
            long parent = menuIndex.get(menus.get(i)).path("parentId").asLong();
            if (parent > 0 && !menus.contains(parent)) menus.add(parent);
        }
        designerRole =
                ok(
                                "/system/role/create",
                                Map.of(
                                        "name",
                                        "报表普通制作验收",
                                        "code",
                                        fixture.prefix + "_designer",
                                        "sort",
                                        999,
                                        "status",
                                        0,
                                        "remark",
                                        fixture.prefix))
                        .asLong();
        ok("/system/permission/assign-role-menu", Map.of("roleId", designerRole, "menuIds", menus));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "ncrd" + suffix, password = "Nc1" + suffix;
        designerUser =
                ok(
                                "/system/user/create",
                                Map.of(
                                        "username",
                                        username,
                                        "nickname",
                                        "报表普通制作验收",
                                        "password",
                                        password,
                                        "remark",
                                        fixture.prefix,
                                        "postIds",
                                        List.of(),
                                        "sex",
                                        0))
                        .asLong();
        ok(
                "/system/permission/assign-user-role",
                Map.of("userId", designerUser, "roleIds", List.of(designerRole)));
        JsonNode auth =
                call(
                                null,
                                "POST",
                                "/system/auth/login",
                                Map.of("username", username, "password", password))
                        .path("data");
        if (!auth.hasNonNull("accessToken"))
            auth =
                    call(
                                    null,
                                    "PUT",
                                    "/system/auth/change-required-password",
                                    Map.of(
                                            "passwordChangeToken",
                                            auth.path("passwordChangeToken").asText(),
                                            "newPassword",
                                            "Nc2" + suffix))
                            .path("data");
        designerToken = auth.path("accessToken").asText();
        assertThat(designerToken).isNotBlank();
        JsonNode identity =
                call(designerToken, "GET", "/system/auth/get-permission-info", null).path("data");
        Set<String> actual = new HashSet<>();
        identity.path("permissions")
                .forEach(
                        value -> {
                            if (!value.asText().isBlank()) actual.add(value.asText());
                        });
        assertThat(actual).containsExactlyInAnyOrderElementsOf(codes);
        Path log =
                Path.of("target/report-identities-browser-" + datasetId + ".log").toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder("node", "tools/nocode-e2e/verify-report-identities.mjs")
                        .directory(Path.of("../../../ucp-front").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().put("REPORT_TEST_TOKEN", designerToken);
        builder.environment().put("REPORT_TEST_OWNER_TOKEN", token);
        builder.environment().put("REPORT_TEST_DATASET", datasetId);
        Process process = builder.start();
        boolean finished = process.waitFor(240, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor();
        assertThat(finished).as("普通制作身份浏览器验收应在四分钟内结束").isTrue();
        assertThat(process.exitValue()).as("身份矩阵浏览器日志：%s", log).isZero();
    }

    /** 完整拥有者流程复用普通账号，仅追加管理本资源协作/成员的系统能力，不授予对象共享权。 */
    private void verifyOwnerBrowser() throws Exception {
        Set<String> codes =
                Set.of(
                        "nocode:report:query",
                        "nocode:report:create",
                        "nocode:report:update",
                        "nocode:report:publish",
                        "nocode:report:manage",
                        "nocode:report:authorize",
                        "nocode:object:query");
        List<Long> menus = new ArrayList<>();
        Map<Long, JsonNode> index = new HashMap<>();
        for (JsonNode menu : ok("/system/menu/list", null)) {
            index.put(menu.path("id").asLong(), menu);
            if (codes.contains(menu.path("permission").asText()))
                menus.add(menu.path("id").asLong());
        }
        for (int i = 0; i < menus.size(); i++) {
            long parent = index.get(menus.get(i)).path("parentId").asLong();
            if (parent > 0 && !menus.contains(parent)) menus.add(parent);
        }
        ok("/system/permission/assign-role-menu", Map.of("roleId", designerRole, "menuIds", menus));
        Path log = Path.of("target/report-owner-browser-" + datasetId + ".log").toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder("node", "tools/nocode-e2e/verify-report-owner.mjs")
                        .directory(Path.of("../../../ucp-front").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().put("REPORT_TEST_TOKEN", designerToken);
        builder.environment().put("REPORT_TEST_DATA_ADMIN_TOKEN", dataAdminToken);
        builder.environment().put("REPORT_TEST_OWNER_TOKEN", token);
        builder.environment().put("REPORT_TEST_DATASET", datasetId);
        builder.environment().put("REPORT_TEST_ROLE", designerRole.toString());
        Process process = builder.start();
        boolean finished = process.waitFor(240, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor();
        assertThat(finished).as("拥有者制作闭环应在四分钟内结束").isTrue();
        assertThat(process.exitValue()).as("拥有者制作闭环日志：%s", log).isZero();
    }

    /** 公司→账户→流水的真实两层单值关系，业务行仅属于本批随机前缀对象。 */
    private void verifySourceBrowser() throws Exception {
        DataObjectApi.PublishedObject company = sourceObject("companies", "来源公司", null, false);
        DataObjectApi.PublishedObject account = sourceObject("accounts", "来源账户", company, false);
        DataObjectApi.PublishedObject entry = sourceObject("entries", "来源流水", account, true);
        long visible =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + company.definition().tableName()
                                + "\"(name) VALUES ('可见公司') RETURNING id",
                        Long.class);
        long hidden =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + company.definition().tableName()
                                + "\"(name) VALUES ('隐藏公司') RETURNING id",
                        Long.class);
        String accountColumn = relationColumn(account), entryColumn = relationColumn(entry);
        long a =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + account.definition().tableName()
                                + "\"(name,\""
                                + accountColumn
                                + "\") VALUES ('可见账户',?) RETURNING id",
                        Long.class,
                        visible);
        long b =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + account.definition().tableName()
                                + "\"(name,\""
                                + accountColumn
                                + "\") VALUES ('隐藏账户',?) RETURNING id",
                        Long.class,
                        hidden);
        long c =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + account.definition().tableName()
                                + "\"(name) VALUES ('无公司账户') RETURNING id",
                        Long.class);
        jdbc.update(
                "INSERT INTO public.\""
                        + entry.definition().tableName()
                        + "\"(name,amount,occurred,business_day,state,checked,\""
                        + entryColumn
                        + "\") VALUES ('一月',10.25,'2026-01-31"
                        + " 23:59:59','2026-01-31','OPEN',true,?),('二月隐藏',20.75,'2026-02-01"
                        + " 00:00:00','2026-02-01','HIDDEN',true,?),('二月空公司',5,'2026-02-01"
                        + " 12:00:00','2026-02-01','CLOSED',false,?),('二月空账户',4,'2026-02-02"
                        + " 00:00:00','2026-02-02','CLOSED',false,null)",
                a,
                b,
                c);
        sourceDatasetId =
                ok(
                                "/nocode/report/dataset/save",
                                new ReportDatasets.Save(
                                        null, 0, fixture.prefix + "两层日期验收", "真实来源专项", null))
                        .path("id")
                        .asText();
        Path log =
                Path.of("target/report-sources-browser-" + sourceDatasetId + ".log")
                        .toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder("node", "tools/nocode-e2e/verify-report-sources.mjs")
                        .directory(Path.of("../../../ucp-front").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().put("REPORT_TEST_TOKEN", token);
        builder.environment().put("REPORT_TEST_DATASET", sourceDatasetId);
        builder.environment()
                .put(
                        "REPORT_KEEP_DEMO",
                        Boolean.toString(Boolean.getBoolean("report.dashboard.demo")));
        builder.environment()
                .put(
                        "REPORT_TEST_SOURCES",
                        json.writeValueAsString(List.of(entry, account, company)));
        Process process = builder.start();
        boolean finished = process.waitFor(240, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor();
        assertThat(finished).as("两层关联与日期页面验收应在四分钟内结束").isTrue();
        assertThat(process.exitValue()).as("来源专项日志：%s", log).isZero();
        retainedDemo = Boolean.getBoolean("report.dashboard.demo");
    }

    private DataObjectApi.PublishedObject sourceObject(
            String suffix, String name, DataObjectApi.PublishedObject target, boolean facts) {
        List<FieldDefinition> fields = new ArrayList<>();
        fields.add(
                new FieldDefinition(
                        "name", null, "name", "名称", "TEXT", 100, null, null, false, false, 0));
        if (facts) {
            fields.add(
                    new FieldDefinition(
                            "amount", null, "amount", "金额", "DECIMAL", null, 20, 2, false, false,
                            1));
            fields.add(
                    new FieldDefinition(
                            "occurred",
                            null,
                            "occurred",
                            "发生时间",
                            "DATETIME",
                            null,
                            null,
                            null,
                            false,
                            false,
                            2));
            fields.add(
                    new FieldDefinition(
                            "business_day",
                            null,
                            "business_day",
                            "业务日期",
                            "DATE",
                            null,
                            null,
                            null,
                            false,
                            false,
                            3));
        }
        if (facts) {
            fields.add(
                    new FieldDefinition(
                            "state", null, "state", "状态", "SELECT", null, null, null, false, false,
                            4));
            fields.add(
                    new FieldDefinition(
                            "checked", null, "checked", "已核销", "BOOLEAN", null, null, null, false,
                            false, 5));
        }
        String code = fixture.prefix + suffix;
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        code,
                                        fixture.prefix + name,
                                        null,
                                        "biz_" + code,
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                facts
                                        ? Map.of(
                                                "state",
                                                DataCenter.FieldOptions.copyOf(
                                                                DataCenter.FieldOptions.defaults())
                                                        .options(
                                                                List.of(
                                                                        new DataCenter.Option(
                                                                                        "OPEN",
                                                                                        "正常",
                                                                                        false),
                                                                                new DataCenter
                                                                                        .Option(
                                                                                        "CLOSED",
                                                                                        "正常",
                                                                                        false),
                                                                        new DataCenter.Option(
                                                                                        "HIDDEN",
                                                                                        "隐藏类型",
                                                                                        false),
                                                                                new DataCenter
                                                                                        .Option(
                                                                                        "UNUSED",
                                                                                        "未使用类型",
                                                                                        false)))
                                                        .build())
                                        : Map.of(),
                                target == null
                                        ? List.of()
                                        : List.of(
                                                new DataCenter.Relation(
                                                        null,
                                                        "parent",
                                                        target.definition().objectName(),
                                                        "REFERENCE",
                                                        target.objectId(),
                                                        null,
                                                        null,
                                                        false,
                                                        "RESTRICT")),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "两层日期夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
    }

    private String relationColumn(DataObjectApi.PublishedObject object) {
        String id = object.definition().relations().getFirst().fieldId();
        return object.definition().fields().stream()
                .filter(field -> field.id().equals(id))
                .findFirst()
                .orElseThrow()
                .code();
    }

    private void verifyBrowser() throws Exception {
        // 用可识别索引夹具验证删除阻断，不将其当作尚未实现的仪表板端到端证据。
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_report_dependency(source_kind,source_id,source_stage,source_version,target_kind,target_id,target_version)"
                    + " VALUES('DASHBOARD',?,'VERSION',9,'DATASET',?,1)",
                Long.parseLong(datasetId),
                Long.parseLong(datasetId));
        Path browserLog = Path.of("target/report-browser-" + datasetId + ".log").toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder("node", "tools/nocode-e2e/verify-report-center.mjs")
                        .directory(Path.of("../../../ucp-front").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(browserLog.toFile());
        builder.environment().put("REPORT_TEST_TOKEN", token);
        builder.environment().put("REPORT_TEST_DATASET", datasetId);
        builder.environment()
                .put("REPORT_TEST_SCOPE", System.getProperty("report.browser.scope", "all"));
        Process process = builder.start();
        boolean finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor();
        assertThat(finished).as("数据集浏览器验收应在三分钟内结束").isTrue();
        assertThat(process.exitValue()).as("数据集浏览器验收结果；日志：%s", browserLog).isZero();
    }

    private JsonNode ok(String path, Object body) throws Exception {
        JsonNode result = call(path, body);
        assertThat(result.path("code").asInt())
                .as(path + ": " + result.path("msg").asText())
                .isZero();
        return result.path("data");
    }

    private JsonNode call(String path, Object body) throws Exception {
        return call(token, body == null ? "GET" : "POST", path, body);
    }

    private JsonNode call(String sessionToken, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (sessionToken != null) request.header("Authorization", "Bearer " + sessionToken);
        request.header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(body)));
        return json.readTree(
                http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body());
    }
}
