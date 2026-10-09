package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;

/** 对已启动正式服务执行真实登录、底座角色菜单鉴权和对象共享 API 回归。 显式提供 NOCODE_VERIFY_TOKEN 才运行；凭据只在内存，测试按随机前缀清理自身夹具。 */
@EnabledIfEnvironmentVariable(named = "NOCODE_VERIFY_TOKEN", matches = ".+")
class ObjectSharingHttpIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String admin = System.getenv("NOCODE_VERIFY_TOKEN");
    private final String base =
            System.getenv().getOrDefault("NOCODE_VERIFY_URL", "http://127.0.0.1:8080/api");
    private NocodeIntegrationSupport fixture;
    private final List<Long> users = new ArrayList<>(), roles = new ArrayList<>();
    private final List<String> apps = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
    }

    private JsonNode call(String token, String method, String path, Object body) throws Exception {
        // 同步 HTTP 足以验证控制器，避免 Windows 打包环境中 NIO 临时套接字路径限制。
        var connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) {
                    output.write(json.writeValueAsBytes(body));
                }
            }
            try (var input =
                    connection.getResponseCode() >= 400
                            ? connection.getErrorStream()
                            : connection.getInputStream()) {
                return json.readTree(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private JsonNode ok(String token, String method, String path, Object body) throws Exception {
        var result = call(token, method, path, body);
        assertThat(result.path("code").asInt(-1))
                .as(method + " " + path + ": " + result.path("msg").asText())
                .isZero();
        return result.path("data");
    }

    private void denied(String token, String method, String path, Object body) throws Exception {
        assertThat(call(token, method, path, body).path("code").asInt(-1))
                .as("必须拒绝 " + path)
                .isNotZero();
    }

    private String account() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "ncsh" + suffix, password = "Nc1" + suffix;
        long id =
                ok(
                                admin,
                                "POST",
                                "/system/user/create",
                                Map.of(
                                        "username",
                                        username,
                                        "nickname",
                                        "共享边界验收",
                                        "password",
                                        password,
                                        "remark",
                                        fixture.prefix,
                                        "postIds",
                                        List.of(),
                                        "sex",
                                        0))
                        .asLong();
        users.add(id);
        var auth =
                ok(
                        null,
                        "POST",
                        "/system/auth/login",
                        Map.of("username", username, "password", password));
        if (!auth.hasNonNull("accessToken"))
            auth =
                    ok(
                            null,
                            "PUT",
                            "/system/auth/change-required-password",
                            Map.of(
                                    "passwordChangeToken",
                                    auth.path("passwordChangeToken").asText(),
                                    "newPassword",
                                    "Nc2" + suffix));
        assertThat(auth.path("accessToken").asText()).isNotBlank();
        return auth.path("accessToken").asText();
    }

    private void grant(String object, String app, int revision, Object permission)
            throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("objectId", object);
        body.put("applicationId", app);
        body.put("expectedRevision", revision);
        body.put("permission", permission);
        body.put("reason", fixture.prefix + "HTTP 授权验收");
        ok(admin, "POST", "/nocode/object-sharing/save", body);
    }

    private Map<String, Object> permission(String object, String field, List<String> actions) {
        return Map.of(
                "objectId",
                object,
                "actions",
                actions,
                "scope",
                "ALL",
                "readFields",
                List.of(field),
                "writeFields",
                List.of(field),
                "readDetails",
                List.of(),
                "writeDetails",
                List.of(),
                "readRelations",
                List.of(),
                "writeRelations",
                List.of());
    }

    private String app(String token, DataObjectApi.PublishedObject object, String suffix)
            throws Exception {
        var result =
                ok(
                        token,
                        "POST",
                        "/nocode/application/save",
                        Map.of(
                                "code",
                                fixture.prefix + suffix,
                                "name",
                                "共享授权 HTTP " + suffix,
                                "definition",
                                Map.of(
                                        "objects",
                                        List.of(
                                                Map.of(
                                                        "objectId",
                                                        object.objectId(),
                                                        "versionNo",
                                                        object.versionNo(),
                                                        "checksum",
                                                        object.checksum())),
                                        "resources",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "report",
                                                        "kind",
                                                        "REPORT",
                                                        "code",
                                                        "report",
                                                        "name",
                                                        "共享统计",
                                                        "config",
                                                        Map.of(
                                                                "objectId",
                                                                object.objectId(),
                                                                "dimensions",
                                                                List.of(
                                                                        Map.of(
                                                                                "fieldId",
                                                                                object.definition()
                                                                                        .fields()
                                                                                        .getFirst()
                                                                                        .id(),
                                                                                "bucket",
                                                                                "VALUE")),
                                                                "metrics",
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                "count",
                                                                                "name",
                                                                                "记录数",
                                                                                "operation",
                                                                                "COUNT")),
                                                                "equal",
                                                                Map.of(),
                                                                "filterFieldIds",
                                                                List.of(
                                                                        object.definition()
                                                                                .fields()
                                                                                .getFirst()
                                                                                .id()),
                                                                "timeZone",
                                                                "Asia/Shanghai",
                                                                "display",
                                                                "TABLE",
                                                                "descending",
                                                                false,
                                                                "limit",
                                                                20))))));
        String id = result.path("application").path("id").asText();
        apps.add(id);
        return id;
    }

    @Test
    void realFoundationPermissionsAndCreatorBoundaryAcrossApplications() throws Exception {
        String builder = account(), outsider = account();
        assertThat(call(builder, "POST", "/nocode/application/save", Map.of()).path("code").asInt())
                .isEqualTo(403);
        var menuIds = new ArrayList<Long>();
        Set<String> designerPermissions =
                Set.of(
                        "nocode:app:query",
                        "nocode:app:create",
                        "nocode:app:update",
                        "nocode:app:publish",
                        "nocode:app:manage",
                        "nocode:object:query");
        for (var menu : ok(admin, "GET", "/system/menu/list", null))
            if (designerPermissions.contains(menu.path("permission").asText()))
                menuIds.add(menu.path("id").asLong());
        assertThat(menuIds).hasSize(designerPermissions.size());
        long role =
                ok(
                                admin,
                                "POST",
                                "/system/role/create",
                                Map.of(
                                        "name",
                                        "共享边界测试搭建者",
                                        "code",
                                        fixture.prefix,
                                        "sort",
                                        999,
                                        "status",
                                        0,
                                        "remark",
                                        fixture.prefix))
                        .asLong();
        roles.add(role);
        ok(
                admin,
                "POST",
                "/system/permission/assign-role-menu",
                Map.of("roleId", role, "menuIds", menuIds));
        for (long user : users)
            ok(
                    admin,
                    "POST",
                    "/system/permission/assign-user-role",
                    Map.of("userId", user, "roleIds", List.of(role)));
        var identity = ok(builder, "GET", "/system/auth/get-permission-info", null);
        assertThat(identity.path("permissions").toString()).doesNotContain("nocode:object:share");
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("http"),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "HTTP 测试对象"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var object =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        String field = object.definition().fields().getFirst().id(), objectId = object.objectId();
        ok(builder, "GET", "/nocode/application/object-version?id=" + objectId, null);
        String reader = app(builder, object, "reader"), writer = app(outsider, object, "writer");
        // 引用即授权：保存后两个应用都已获得默认上限，发布无需数据管理员单独同意。
        var publish = Map.of("id", reader, "expectedRevision", 0, "reason", "引用即授权后发布");
        denied(
                builder,
                "POST",
                "/nocode/object-sharing/save",
                Map.of(
                        "objectId",
                        objectId,
                        "applicationId",
                        reader,
                        "expectedRevision",
                        1,
                        "permission",
                        permission(objectId, field, List.of("READ", "UPDATE")),
                        "reason",
                        "自行扩权"));
        denied(outsider, "GET", "/nocode/application/get?id=" + reader, null);
        denied(
                outsider,
                "POST",
                "/nocode/application/authorization",
                Map.of("applicationId", reader, "expectedRevision", 0, "members", List.of()));
        // 数据管理员把默认上限收紧为只读/受限写，验证运行能力随上限收敛。
        grant(objectId, reader, 1, permission(objectId, field, List.of("READ")));
        grant(
                objectId,
                writer,
                1,
                permission(objectId, field, List.of("READ", "CREATE", "UPDATE", "DELETE")));
        ok(builder, "POST", "/nocode/application/publish", publish);
        ok(
                outsider,
                "POST",
                "/nocode/application/publish",
                Map.of("id", writer, "expectedRevision", 0, "reason", "显式授权发布"));
        var row =
                ok(
                                outsider,
                                "POST",
                                "/nocode/runtime/save",
                                Map.of(
                                        "applicationId",
                                        writer,
                                        "objectId",
                                        objectId,
                                        "values",
                                        Map.of(field, "共享测试数据")))
                        .path("record");
        String readPath =
                "/nocode/runtime/get?applicationId="
                        + reader
                        + "&objectId="
                        + objectId
                        + "&id="
                        + row.path("id").asText();
        var reportQuery =
                Map.of("applicationId", reader, "reportId", "report", "pageNo", 1, "pageSize", 20);
        assertThat(
                        ok(builder, "POST", "/nocode/runtime/report", reportQuery)
                                .path("recordCount")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        ok(builder, "POST", "/nocode/runtime/report-details", reportQuery)
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        denied(builder, "POST", "/nocode/runtime/report-export", reportQuery);
        denied(null, "POST", "/nocode/runtime/report", reportQuery);
        denied(
                builder,
                "POST",
                "/nocode/runtime/report",
                Map.of("applicationId", writer, "reportId", "report"));
        denied(
                builder,
                "POST",
                "/nocode/runtime/report",
                Map.of(
                        "applicationId",
                        reader,
                        "reportId",
                        "report",
                        "sql",
                        "select * from system_users"));
        assertThat(
                        ok(builder, "GET", readPath, null)
                                .path("record")
                                .path("values")
                                .path(field)
                                .asText())
                .isEqualTo("共享测试数据");
        denied(
                builder,
                "POST",
                "/nocode/runtime/save",
                Map.of(
                        "applicationId",
                        reader,
                        "objectId",
                        objectId,
                        "values",
                        Map.of(field, "越权")));
        denied(
                builder,
                "POST",
                "/nocode/runtime/save",
                Map.of(
                        "applicationId",
                        reader,
                        "objectId",
                        objectId,
                        "id",
                        row.path("id").asText(),
                        "expectedRevision",
                        row.path("revision").asText(),
                        "values",
                        Map.of(field, "越权")));
        denied(
                builder,
                "GET",
                readPath.replace("applicationId=" + reader, "applicationId=" + writer),
                null);
        denied(
                builder,
                "POST",
                "/nocode/application/authorization",
                Map.of(
                        "applicationId",
                        reader,
                        "expectedRevision",
                        0,
                        "members",
                        List.of(
                                Map.of(
                                        "principalKind",
                                        "USER",
                                        "principalId",
                                        users.get(1).toString(),
                                        "objects",
                                        List.of(
                                                permission(
                                                        objectId,
                                                        field,
                                                        List.of("READ", "UPDATE")))))));
        // 超级管理员可以管理别人的应用，但不会仅因 super_admin 获得运行数据权限。
        ok(admin, "GET", "/nocode/application/get?id=" + reader, null);
        denied(admin, "GET", readPath, null);
        // 默认上限(rev1)经收紧后为 rev2，撤权须基于当前修订号。
        grant(objectId, reader, 2, null);
        denied(builder, "POST", "/nocode/runtime/report", reportQuery);
        denied(builder, "POST", "/nocode/runtime/report-details", reportQuery);
        denied(builder, "POST", "/nocode/runtime/report-export", reportQuery);
        denied(builder, "GET", readPath, null);
        denied(
                builder,
                "POST",
                "/nocode/application/restore",
                Map.of("id", reader, "expectedRevision", 1, "sourceVersion", 1, "reason", "恢复旧版本"));
        assertThat(ok(builder, "GET", "/nocode/runtime/mine", null).toString())
                .doesNotContain("\"id\":\"" + reader + "\"");
        // 一个应用撤权不干扰另一应用的显式合法授权。
        ok(
                outsider,
                "GET",
                readPath.replace("applicationId=" + reader, "applicationId=" + writer),
                null);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String app : apps) {
            long id = Long.parseLong(app);
            for (String table :
                    List.of(
                            "nocode_object_application_grant_log",
                            "nocode_object_application_grant",
                            "nocode_application_access",
                            "nocode_application_version"))
                jdbc.update("DELETE FROM public." + table + " WHERE application_id=?", id);
            jdbc.update(
                    "DELETE FROM public.nocode_application WHERE id=? AND app_code LIKE ?",
                    id,
                    fixture.prefix + "%");
        }
        for (long user : users) ok(admin, "DELETE", "/system/user/delete?id=" + user, null);
        for (long role : roles) ok(admin, "DELETE", "/system/role/delete?id=" + role, null);
        fixture.clean();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_application WHERE app_code LIKE"
                                        + " ?",
                                Integer.class,
                                fixture.prefix + "%"))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_object WHERE object_code LIKE"
                                        + " ?",
                                Integer.class,
                                fixture.prefix + "%"))
                .isZero();
        for (long user : users)
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT deleted FROM public.system_users WHERE id=?",
                                    Integer.class,
                                    user))
                    .isEqualTo(1);
        for (long role : roles)
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT deleted FROM public.system_role WHERE id=?",
                                    Integer.class,
                                    role))
                    .isEqualTo(1);
    }
}
