package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 授权清单「全部」在四个存放处（对象→应用授权、应用成员授权、任务入口允许范围、任务入口成员授权）的接线。
 *
 * <p>主防线是「同一份夹具存两种形态、结果逐项相同」：应用 S 的四处都存 {@code ["*"]}，应用 E 的四处都存显式全量清单，
 * 应用创建人、应用成员、入口成员在两个应用里拿到的权限必须逐项相同，且都是展开后的普通 ID。任何一处忘了展开都会在这里变红。 只操作随机前缀夹具。
 */
class SelectionAllIntegrationTest {
    private static final Set<String> ALL = Set.of("*");
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private ApplicationAuthorizationService authorization;
    private ObjectSharingService sharing;
    private RecordService records;
    private DataObjectApi objects;
    private final List<String> appIds = new ArrayList<>();
    private String objectId;
    private final long owner = 10001L;
    private final long member = 23001L;

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
        apps = servicesContext.getBean(ApplicationService.class);
        authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        records = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        var request = fixture.createRequest("all");
        var fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("amount", "amount", "INTEGER", 1));
        fields.add(fixture.field("memo", "memo", "TEXT", 2));
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        "甲",
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of(
                                        new DataCenter.Detail(
                                                null,
                                                "items",
                                                "内部明细",
                                                "biz_" + fixture.prefix + "items",
                                                "ACTIVE",
                                                List.of(
                                                        new FieldDefinition(
                                                                "quantity",
                                                                null,
                                                                "quantity",
                                                                "数量",
                                                                "INTEGER",
                                                                null,
                                                                null,
                                                                null,
                                                                false,
                                                                false,
                                                                0)),
                                                Map.of(),
                                                List.of()))),
                        owner);
        publish(design, "全部授权夹具初始发布");
        objectId = design.draft().id();
    }

    @AfterEach
    void cleanup() {
        for (String app : appIds) {
            long id = Long.parseLong(app);
            jdbc.update(
                    "DELETE FROM public.nocode_work_draft WHERE resource_json->>'applicationId'=?",
                    app);
            for (String table :
                    List.of(
                            "nocode_object_application_grant_log",
                            "nocode_object_application_grant",
                            "nocode_task_entry_access",
                            "nocode_application_access",
                            "nocode_application_version"))
                jdbc.update("DELETE FROM public." + table + " WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        appIds.clear();
        if (fixture != null) fixture.clean();
    }

    // ── 夹具 ──

    private void publish(DataCenter.Design design, String reason) {
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        owner);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), reason), owner).state())
                .isEqualTo("SUCCEEDED");
    }

    private DataCenter.Definition latest() {
        return objects.getVersion(objectId, null).definition();
    }

    private ApplicationCenter.ObjectReference latestReference() {
        var version = objects.getVersion(objectId, null);
        return new ApplicationCenter.ObjectReference(
                objectId, version.versionNo(), version.checksum());
    }

    private String field(String code) {
        return latest().fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Set<String> fieldIds(DataCenter.Definition d) {
        Set<String> ids = new LinkedHashSet<>();
        d.fields().forEach(f -> ids.add(f.id()));
        return ids;
    }

    private Set<String> detailIds(DataCenter.Definition d) {
        Set<String> ids = new LinkedHashSet<>();
        d.details().forEach(t -> ids.add(t.id()));
        return ids;
    }

    /** star=true 存「全部」，false 存对着 d 的显式全量清单；两者展开后应当是同一组 ID。 */
    private ObjectGrant grant(
            boolean star,
            DataCenter.Definition d,
            Set<String> actions,
            String scope,
            boolean writable) {
        Set<String> readFields = star ? ALL : fieldIds(d);
        Set<String> readDetails = star ? ALL : detailIds(d);
        return new ObjectGrant(
                d.objectId(),
                actions,
                scope,
                readFields,
                writable ? readFields : Set.of(),
                readDetails,
                writable ? readDetails : Set.of(),
                star ? ALL : Set.of(),
                writable && star ? ALL : Set.of());
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "all_test_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private List<ApplicationCenter.Resource> resources(boolean star, DataCenter.Definition d) {
        String name =
                d.fields().stream()
                        .filter(f -> f.code().equals("name"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        var form =
                resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                d.objectId(),
                                List.of(
                                        new ApplicationUi.Node(
                                                "name_node",
                                                "FIELD",
                                                name,
                                                null,
                                                null,
                                                null,
                                                List.of())),
                                List.of(d.details().getFirst().id())));
        var view =
                resource(
                        "view",
                        "VIEW",
                        new ApplicationUi.View(
                                d.objectId(), List.of(name), Map.of(), null, false, 10, "form"));
        // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增 / 修改 / 删除），入口成员与入口允许范围随之去掉；其余三处（上限、创建人、应用成员）照旧逐项核对。
        return List.of(form, view);
    }

    /** 建应用并发布；四个存放处按 star 决定存「全部」还是显式全量。 */
    private String application(boolean star, String suffix) {
        DataCenter.Definition d = latest();
        var created =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + suffix,
                                "全部授权验证" + suffix,
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(latestReference()), resources(star, d))),
                        owner);
        String app = created.application().id();
        appIds.add(app);
        if (!star)
            share(
                    app,
                    grant(
                            false,
                            d,
                            Set.of("READ", "CREATE", "UPDATE", "DELETE", "IMPORT", "EXPORT"),
                            "ALL",
                            true));
        apps.publish(
                new ApplicationCenter.Revision(app, created.application().revision(), "全部授权验证"),
                owner);
        authorize(app, grant(star, d, Set.of("READ", "UPDATE"), "ALL", true));
        return app;
    }

    private void share(String app, ObjectGrant permission) {
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(objectId))
                        .findFirst()
                        .map(ObjectSharing.Grant::revision)
                        .orElse(0);
        sharing.save(new ObjectSharing.Save(objectId, app, revision, permission, "全部授权验证"), owner);
    }

    private void authorize(String app, ObjectGrant permission) {
        authorization.save(
                new Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(new Member("USER", Long.toString(member), List.of(permission)))),
                owner);
    }

    private JsonNode sharedJson(String app) {
        return json(
                jdbc.queryForObject(
                        "SELECT grant_json::text FROM public.nocode_object_application_grant WHERE"
                                + " application_id=? AND object_id=?",
                        String.class,
                        Long.valueOf(app),
                        Long.valueOf(objectId)));
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText()));
        return result;
    }

    private static void assertSame(Capabilities star, Capabilities explicit, String who) {
        assertThat(star.actions()).as(who + " 操作").isEqualTo(explicit.actions());
        assertThat(star.readFields()).as(who + " 可查看字段").isEqualTo(explicit.readFields());
        assertThat(star.writeFields()).as(who + " 可修改字段").isEqualTo(explicit.writeFields());
        assertThat(star.readDetails()).as(who + " 可查看明细").isEqualTo(explicit.readDetails());
        assertThat(star.writeDetails()).as(who + " 可修改明细").isEqualTo(explicit.writeDetails());
        assertThat(star.readRelations()).as(who + " 可查看关系").isEqualTo(explicit.readRelations());
        assertThat(star.writeRelations()).as(who + " 可修改关系").isEqualTo(explicit.writeRelations());
    }

    private void assertExpanded(Capabilities caps, DataCenter.Definition d, String who) {
        assertThat(caps.readFields()).as(who + " 拿到的是展开后的普通 ID").doesNotContain("*");
        assertThat(caps.writeFields()).doesNotContain("*");
        assertThat(caps.readDetails()).doesNotContain("*");
        assertThat(caps.writeDetails()).doesNotContain("*");
        assertThat(caps.readFields()).as(who + " 读得到全部字段").isEqualTo(fieldIds(d));
        assertThat(caps.readDetails()).as(who + " 读得到全部明细").isEqualTo(detailIds(d));
    }

    // ── 用例 ──

    /** 首次引用对象时系统写入的默认上限：六个清单都是「全部」，没有计算取数的内容。 */
    @Test
    void firstReferenceWritesAllSixListsAsStar() {
        String app = application(true, "s");
        JsonNode stored = sharedJson(app);
        for (String key :
                List.of(
                        "readFields",
                        "writeFields",
                        "readDetails",
                        "writeDetails",
                        "readRelations",
                        "writeRelations"))
            assertThat(texts(stored.path(key))).as(key).containsExactly("*");
        assertThat(texts(stored.path("computeFields"))).isEmpty();
        assertThat(texts(stored.path("actions")))
                .containsExactlyInAnyOrder(
                        "READ", "CREATE", "UPDATE", "DELETE", "IMPORT", "EXPORT");
        assertThat(stored.path("scope").asText()).isEqualTo("ALL");
    }

    /** 主防线：各处都存「全部」与都存显式全量，创建人、成员拿到的权限逐项相同（入口成员已随 dev 退役）。 */
    @Test
    void starAndExplicitFullListsResolveToTheSameCapabilities() {
        String star = application(true, "s");
        String explicit = application(false, "e");
        DataCenter.Definition d = latest();
        // ②④ 落库的形态确实是两种（否则这条用例什么都没比）。
        assertThat(texts(sharedJson(star).path("readFields"))).containsExactly("*");
        assertThat(texts(sharedJson(explicit).path("readFields")))
                .containsExactlyInAnyOrderElementsOf(fieldIds(d));
        assertThat(authorization.get(star).members().getFirst().objects().getFirst().readFields())
                .containsExactly("*");
        assertThat(
                        authorization
                                .get(explicit)
                                .members()
                                .getFirst()
                                .objects()
                                .getFirst()
                                .readFields())
                .isEqualTo(fieldIds(d));

        // 应用创建人：直接拿对象授予应用的上限。
        Capabilities ownerStar = records.model(star, objectId, owner).permissions();
        Capabilities ownerExplicit = records.model(explicit, objectId, owner).permissions();
        assertExpanded(ownerStar, d, "应用创建人");
        assertThat(ownerStar.writeFields()).isEqualTo(fieldIds(d));
        assertSame(ownerStar, ownerExplicit, "应用创建人");

        // 应用成员：成员授权 ∩ 上限。
        Capabilities memberStar = records.model(star, objectId, member).permissions();
        Capabilities memberExplicit = records.model(explicit, objectId, member).permissions();
        assertExpanded(memberStar, d, "应用成员");
        assertSame(memberStar, memberExplicit, "应用成员");

        // 记录上的权限（逐行计算）同样是展开后的。
        String name = field("name");
        var saved =
                records.save(
                        new ApplicationRecords.Save(
                                star,
                                objectId,
                                null,
                                null,
                                Map.of(name, "全部授权"),
                                Map.of(),
                                Map.of(),
                                null,
                                "form",
                                UUID.randomUUID().toString(),
                                null),
                        owner);
        var row = records.get(star, objectId, saved.record().id(), member).record();
        assertThat(row.permissions().readFields()).isEqualTo(fieldIds(d));
        assertThat(row.values()).containsEntry(name, "全部授权");
    }

    /** 成员是「全部」、上限是清单：成员只读得到上限里的（放大方向）。 */
    @Test
    void starMemberUnderListCeilingOnlyReadsTheCeiling() {
        String app = application(true, "s");
        DataCenter.Definition d = latest();
        String name = field("name"), amount = field("amount");
        share(
                app,
                new ObjectGrant(
                        objectId,
                        Set.of("READ", "CREATE", "UPDATE"),
                        "ALL",
                        Set.of(name, amount),
                        Set.of(name),
                        Set.of(),
                        Set.of()));
        Capabilities memberCaps = records.model(app, objectId, member).permissions();
        assertThat(memberCaps.readFields()).containsExactlyInAnyOrder(name, amount);
        assertThat(memberCaps.writeFields()).containsExactly(name);
        assertThat(memberCaps.readDetails()).isEmpty();
        Capabilities ownerCaps = records.model(app, objectId, owner).permissions();
        assertThat(ownerCaps.readFields()).containsExactlyInAnyOrder(name, amount);
        assertThat(fieldIds(d)).contains(field("memo"));
    }

    private DataCenter.Design addField(String code) {
        DataCenter.Design current = designs.get(objectId);
        DataCenter.Design editable =
                designs.editPublished(
                        new DataCenter.Revision(objectId, current.draft().lockVersion(), null),
                        owner);
        ObjectDraft draft = editable.draft();
        List<FieldDefinition> fields = new ArrayList<>(draft.fields());
        fields.add(fixture.field(code, code, "TEXT", fields.size()));
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                fields,
                                List.of()),
                        editable.settings(),
                        editable.fieldOptions(),
                        editable.relations(),
                        editable.indexes(),
                        editable.details(),
                        editable.mainBinding()),
                owner);
    }

    /** 让应用固定到对象最新版本：没跟上就手工「同步对象版本 + 保存草稿 + 发布」。入口范围沿用草稿里已有的配置。 */
    private void syncToLatest(String app) {
        ApplicationCenter.ObjectReference latest = latestReference();
        if (apps.published(app).definition().objects().getFirst().versionNo() == latest.versionNo())
            return;
        ApplicationCenter.Detail head = apps.get(app);
        ApplicationCenter.Detail saved =
                apps.save(
                        new ApplicationCenter.Save(
                                app,
                                head.application().revision(),
                                head.application().code(),
                                head.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(latest), head.draft().resources())),
                        owner);
        apps.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "同步对象版本"),
                owner);
    }

    /** 对象加了字段、应用固定版本里有它之后：存「全部」的自动读得到；存显式清单的读不到（对照组）。 */
    @Test
    void newFieldReachesStarGrantsButNotExplicitLists() {
        String star = application(true, "s");
        String explicit = application(false, "e");
        Set<String> before = fieldIds(latest());
        publish(addField("note"), "新增字段");
        String note = field("note");
        assertThat(before).doesNotContain(note);
        syncToLatest(star);
        syncToLatest(explicit);
        assertThat(records.model(star, objectId, owner).permissions().readFields()).contains(note);
        assertThat(records.model(star, objectId, member).permissions().readFields())
                .as("没有人回授权里重勾")
                .contains(note);
        assertThat(records.model(star, objectId, member).permissions().writeFields())
                .contains(note);
        assertThat(records.model(explicit, objectId, owner).permissions().readFields())
                .as("显式清单不会自动包含新字段")
                .isEqualTo(before);
        assertThat(records.model(explicit, objectId, member).permissions().readFields())
                .isEqualTo(before);
    }

    /** 库里落的是规范化结果：带死 ID 的请求保存后，读库看不到死 ID；计算取数被置空。 */
    @Test
    void savedGrantsAreNormalizedInTheDatabase() {
        String app = application(true, "s");
        String name = field("name"), amount = field("amount");
        String dead = "999999999";
        // ① 对象→应用授权
        share(
                app,
                new ObjectGrant(
                        objectId,
                        Set.of("READ", "CREATE", "UPDATE"),
                        "ALL",
                        Set.of(name, amount, dead),
                        Set.of(name, dead),
                        Set.of(dead),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Set.of(name)));
        JsonNode shared = sharedJson(app);
        assertThat(texts(shared.path("readFields"))).containsExactlyInAnyOrder(name, amount);
        assertThat(texts(shared.path("writeFields"))).containsExactly(name);
        assertThat(texts(shared.path("readDetails"))).isEmpty();
        assertThat(texts(shared.path("computeFields"))).as("① 带了计算取数不报错，落库为空").isEmpty();
        // ② 应用成员授权
        authorize(
                app,
                new ObjectGrant(
                        objectId,
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of(name, dead),
                        Set.of(name, dead),
                        Set.of(),
                        Set.of()));
        JsonNode policy =
                json(
                        jdbc.queryForObject(
                                "SELECT policy_json::text FROM public.nocode_application_access"
                                        + " WHERE application_id=?",
                                String.class,
                                Long.valueOf(app)));
        assertThat(texts(policy.get(0).path("objects").get(0).path("readFields")))
                .containsExactly(name);
        assertThat(texts(policy.get(0).path("objects").get(0).path("writeFields")))
                .containsExactly(name);
        // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增 / 修改 / 删除），入口成员与入口允许范围随之去掉；其余三处（上限、创建人、应用成员）照旧逐项核对。
    }

    /** ② 应用成员带计算取数仍按原文案拒绝（计算取数只由数据中心授予应用）；③④ 入口两处已随 dev 退役。 */
    @Test
    void membersAndEntriesStillCannotCarryComputeFields() {
        String app = application(true, "s");
        String name = field("name");
        ObjectGrant withCompute =
                new ObjectGrant(
                        objectId,
                        Set.of("READ"),
                        "ALL",
                        ALL,
                        Set.of(),
                        ALL,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Set.of(name));
        assertThatThrownBy(() -> authorize(app, withCompute)).hasMessage("计算取数授权由数据中心授予应用，不能授予成员");
        // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增 / 修改 / 删除），入口成员与入口允许范围随之去掉；其余三处（上限、创建人、应用成员）照旧逐项核对。
    }

    /** 成员清单超出上限：六个清单被收紧并返回结果；操作超出仍按原文案拒绝（对照组）。 */
    @Test
    void memberListsBeyondTheCeilingAreTightenedButActionsStillReject() {
        String app = application(true, "s");
        String name = field("name"), amount = field("amount");
        share(
                app,
                new ObjectGrant(
                        objectId,
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of(name),
                        Set.of(name),
                        Set.of(),
                        Set.of()));
        Policy saved =
                authorization.save(
                        new Save(
                                app,
                                authorization.get(app).revision(),
                                List.of(
                                        new Member(
                                                "USER",
                                                Long.toString(member),
                                                List.of(
                                                        new ObjectGrant(
                                                                objectId,
                                                                Set.of("READ", "UPDATE"),
                                                                "ALL",
                                                                Set.of(name, amount),
                                                                Set.of(name, amount),
                                                                Set.of(),
                                                                Set.of()))))),
                        owner);
        ObjectGrant tightened = saved.members().getFirst().objects().getFirst();
        assertThat(tightened.readFields()).containsExactly(name);
        assertThat(tightened.writeFields()).containsExactly(name);
        assertThatThrownBy(
                        () ->
                                authorize(
                                        app,
                                        new ObjectGrant(
                                                objectId,
                                                Set.of("READ", "DELETE"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of())))
                .hasMessage("成员授权超出对象授予应用的范围");
    }

    /** 引用即授权只写一次：管理员收窄后的上限在应用「同步对象版本 + 保存草稿」后不变；已撤销的不被重新写上。 */
    @Test
    void referenceGrantIsWrittenOnceAndNeverOverwritten() {
        String app = application(true, "s");
        String name = field("name");
        ObjectGrant narrowed =
                new ObjectGrant(
                        objectId,
                        Set.of("READ"),
                        "ALL",
                        Set.of(name),
                        Set.of(),
                        Set.of(),
                        Set.of());
        share(app, narrowed);
        String narrowedJson = sharedJson(app).toString();
        publish(addField("note"), "新增字段");
        // 同步对象版本并保存草稿：引用的版本号与校验和都变了。
        ApplicationCenter.Detail head = apps.get(app);
        ApplicationCenter.ObjectReference latest = latestReference();
        assertThat(latest.versionNo()).isEqualTo(2);
        apps.save(
                new ApplicationCenter.Save(
                        app,
                        head.application().revision(),
                        head.application().code(),
                        head.application().name(),
                        null,
                        null,
                        new ApplicationCenter.Definition(
                                List.of(latest), head.draft().resources())),
                owner);
        assertThat(apps.get(app).draft().objects().getFirst().versionNo()).isEqualTo(2);
        assertThat(sharedJson(app).toString()).as("管理员收窄过的上限保持原样").isEqualTo(narrowedJson);
        assertThat(texts(sharedJson(app).path("actions"))).containsExactly("READ");
        // 撤销后再同步保存：仍是撤销。
        share(app, null);
        assertThat(sharedJson(app).isNull()).isTrue();
        publish(addField("more"), "再新增字段");
        ApplicationCenter.Detail again = apps.get(app);
        apps.save(
                new ApplicationCenter.Save(
                        app,
                        again.application().revision(),
                        again.application().code(),
                        again.application().name(),
                        null,
                        null,
                        new ApplicationCenter.Definition(
                                List.of(latestReference()), again.draft().resources())),
                owner);
        assertThat(apps.get(app).draft().objects().getFirst().versionNo()).isEqualTo(3);
        assertThat(sharedJson(app).isNull()).as("已撤销的授权不被重新写上").isTrue();
        assertThat(sharing.storedPermission(objectId, app)).isNull();
    }

    /** 业务编号的发布校验：上限是「全部」时通过；上限是不含该字段的清单时仍按原文案拒绝（对照组）。 */
    @Test
    void numberRulePublishCheckExpandsTheStarCeiling() {
        var request = fixture.createRequest("num");
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        "编号对象",
                                        null,
                                        request.tableName(),
                                        "number",
                                        List.of(
                                                new FieldDefinition(
                                                        "number",
                                                        null,
                                                        "asset_number",
                                                        "分类编号",
                                                        "TEXT",
                                                        80,
                                                        null,
                                                        null,
                                                        true,
                                                        true,
                                                        0)),
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        owner);
        publish(design, "编号对象初始发布");
        String numbered = design.draft().id();
        var version = objects.getVersion(numbered, null);
        String number = version.definition().fields().getFirst().id();
        var rule =
                new ApplicationCenter.Resource(
                        "number",
                        "NUMBER_RULE",
                        "number",
                        "分类自动编号",
                        Map.of(
                                "objectId",
                                numbered,
                                "fieldId",
                                number,
                                "prefix",
                                "QB-",
                                "period",
                                "NONE",
                                "width",
                                4));
        var created =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "n",
                                "编号校验",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        numbered,
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of(rule))),
                        owner);
        String app = created.application().id();
        appIds.add(app);
        JsonNode stored =
                json(
                        jdbc.queryForObject(
                                "SELECT grant_json::text FROM"
                                        + " public.nocode_object_application_grant WHERE"
                                        + " application_id=? AND object_id=?",
                                String.class,
                                Long.valueOf(app),
                                Long.valueOf(numbered)));
        assertThat(texts(stored.path("writeFields"))).containsExactly("*");
        var revision =
                new ApplicationCenter.Revision(app, created.application().revision(), "编号校验");
        sharing.save(
                new ObjectSharing.Save(
                        numbered,
                        app,
                        1,
                        new ObjectGrant(
                                numbered,
                                Set.of("READ", "CREATE"),
                                "ALL",
                                Set.of(number),
                                Set.of(),
                                Set.of(),
                                Set.of()),
                        "清单里没有可写字段"),
                owner);
        assertThatThrownBy(() -> apps.publish(revision, owner))
                .hasMessageContaining("自动编号“分类自动编号”无法使用：数据对象“编号对象”缺少“分类编号”字段的填写和修改权限。");
        sharing.save(
                new ObjectSharing.Save(
                        numbered,
                        app,
                        2,
                        new ObjectGrant(
                                numbered,
                                Set.of("READ", "CREATE"),
                                "ALL",
                                ALL,
                                ALL,
                                Set.of(),
                                Set.of()),
                        "可写改回全部"),
                owner);
        assertThat(apps.publish(revision, owner).application().publishedVersion()).isEqualTo(1);
    }
}
