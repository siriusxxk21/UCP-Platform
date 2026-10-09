package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 数据联动自动更新的总览、预告与回填（测试库真实读写）。回填没有服务端状态：是否需要回填只看预告的结果； 预告数 = 回填实际改动数，回填后再预告为 0。
 *
 * <p>本类同时把三个接口的真实请求 / 响应 JSON 打到标准输出（行首 API-SAMPLE），供前端一路对照。
 */
class LinkageBackfillIntegrationTest {
    private LinkageSyncFixture x;

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
        x = new LinkageSyncFixture();
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private String status() {
        return id(x.flow, "status");
    }

    private static void sample(String title, Object request, Object response) {
        try {
            System.out.println(
                    "API-SAMPLE "
                            + title
                            + " REQUEST "
                            + (request == null ? "-" : mapper.writeValueAsString(request)));
            System.out.println(
                    "API-SAMPLE " + title + " RESPONSE " + mapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 把所有流水的状态改成过期值（绕过系统直接改库），造出「需要回填」的存量。 */
    private void stale(String value) {
        jdbc.update(
                "UPDATE public.\"" + x.flow.tableName() + "\" SET status = ? WHERE deleted = 0",
                value);
    }

    private LinkageSync.Preview preview(String basis, String cursor, int limit) {
        return x.sync.preview(
                new LinkageSync.PreviewRequest(
                        x.app, basis, x.flow.objectId(), status(), cursor, limit),
                10001);
    }

    private LinkageSync.Backfill backfill(String signature, String cursor, int limit) {
        return x.sync.backfill(
                new LinkageSync.BackfillRequest(
                        x.app, x.flow.objectId(), status(), signature, cursor, limit),
                10001);
    }

    private String signature() {
        return x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001)
                .fields()
                .getFirst()
                .signature();
    }

    /** 七条流水：三条有凭证、四条没有。 */
    private List<Row> seven() {
        List<Row> flows = new ArrayList<>();
        for (int i = 0; i < 7; i++) flows.add(x.createFlow("流水" + i));
        x.createVoucher("凭证0", flows.get(0).id(), "ylr");
        x.createVoucher("凭证2", flows.get(2).id(), "ysh");
        x.createVoucher("凭证5", flows.get(5).id(), "ylr");
        return flows;
    }

    /** 预告分页走到底；预告的「将更新」= 回填实际改动；回填后再预告为 0；重复回填不再改任何东西。 */
    @Test
    void previewPagesThroughAndMatchesBackfill() {
        x.benchmark();
        var flows = seven();
        assertThat(x.pending()).isZero();
        stale(null);
        var overview = x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001);
        sample(
                "overview PUBLISHED",
                Map.of("applicationId", x.app, "basis", "PUBLISHED"),
                overview);
        int fill = 0, scanned = 0, pages = 0;
        String cursor = null;
        LinkageSync.Preview page;
        do {
            var request =
                    new LinkageSync.PreviewRequest(
                            x.app, "PUBLISHED", x.flow.objectId(), status(), cursor, 3);
            page = x.sync.preview(request, 10001);
            if (pages < 2) sample("preview page " + (pages + 1), request, page);
            assertThat(page.signature()).isEqualTo(overview.fields().getFirst().signature());
            assertThat(page.total()).isEqualTo(pages == 0 ? Long.valueOf(7) : null);
            assertThat(page.willClear() + page.willChange() + page.failedCount()).isZero();
            fill += page.willFill();
            scanned += page.scanned();
            cursor = page.nextCursor();
            pages++;
        } while (!page.done());
        assertThat(pages).isEqualTo(3);
        assertThat(scanned).isEqualTo(7);
        assertThat(fill).as("全部由空变成值").isEqualTo(7);

        int updated = 0;
        cursor = null;
        LinkageSync.Backfill batch;
        int batches = 0;
        do {
            var request =
                    new LinkageSync.BackfillRequest(
                            x.app, x.flow.objectId(), status(), page.signature(), cursor, 3);
            batch = x.sync.backfill(request, 10001);
            if (batches == 0) sample("backfill page 1", request, batch);
            assertThat(batch.failedCount()).isZero();
            updated += batch.updated();
            cursor = batch.nextCursor();
            batches++;
        } while (!batch.done());
        assertThat(updated).as("预告数 = 回填实际改动数").isEqualTo(fill);
        assertThat(x.flowStatus(flows.get(0).id())).isEqualTo("ylr");
        assertThat(x.flowStatus(flows.get(1).id())).isEqualTo("wdj");
        assertThat(x.flowStatus(flows.get(2).id())).isEqualTo("ysh");
        assertThat(x.pending()).as("回填后再预告").isZero();
        var again = backfill(page.signature(), null, 200);
        assertThat(again.updated()).as("幂等：重复回填不再改任何东西").isZero();
        assertThat(again.unchanged()).isEqualTo(7);
        assertThat(again.done()).isTrue();
    }

    /** 中断后带游标续跑，与从头重跑的结果相同。 */
    @Test
    void resumingWithCursorEqualsRestarting() {
        x.benchmark();
        var flows = seven();
        stale("ysh");
        String signature = signature();
        var first = backfill(signature, null, 3);
        assertThat(first.scanned()).isEqualTo(3);
        assertThat(first.done()).isFalse();
        assertThat(x.pending()).as("中断时还剩一部分没回填").isPositive();
        // 续跑。
        var rest = backfill(signature, first.nextCursor(), 200);
        assertThat(rest.done()).isTrue();
        assertThat(first.scanned() + rest.scanned()).isEqualTo(7);
        assertThat(x.pending()).isZero();
        List<String> resumed = flows.stream().map(row -> x.flowStatus(row.id())).toList();
        // 再弄脏，从头重跑。
        stale("ysh");
        var whole = backfill(signature, null, 200);
        assertThat(whole.done()).isTrue();
        assertThat(flows.stream().map(row -> x.flowStatus(row.id())).toList()).isEqualTo(resumed);
        assertThat(whole.updated()).isEqualTo(first.updated() + rest.updated());
    }

    /** 预告三种变化都认：由空变有值、有值变空、值变化；回填历史带 backfill 标记、不带来源记录，操作人是发起回填的人。 */
    @Test
    void previewClassifiesChangesAndBackfillHistoryIsMarked() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        // 不配「没有匹配记录时填入」：没有凭证的流水应有值是空。
        x.flow =
                rules(
                        x.flow,
                        status(),
                        LinkageSyncFixture.auto(
                                x.voucher,
                                id(x.voucher, "status"),
                                "FIRST",
                                null,
                                LinkageSyncFixture.currentRecord(
                                        relationField(x.voucher, "flow"))));
        x.app = x.f.app(x.flow, x.voucher);
        var fill = x.createFlow("有凭证但值被清掉");
        var clear = x.createFlow("没有凭证却有值");
        var change = x.createFlow("有凭证但值不对");
        var same = x.createFlow("已是最新");
        x.createVoucher("凭证一", fill.id(), "ylr");
        x.createVoucher("凭证二", change.id(), "ylr");
        x.createVoucher("凭证三", same.id(), "ysh");
        jdbc.update(
                "UPDATE public.\"" + x.flow.tableName() + "\" SET status = NULL WHERE id::text = ?",
                fill.id());
        jdbc.update(
                "UPDATE public.\""
                        + x.flow.tableName()
                        + "\" SET status = 'ysh' WHERE id::text IN (?, ?)",
                clear.id(),
                change.id());
        var page = preview("PUBLISHED", null, 200);
        assertThat(page.scanned()).isEqualTo(4);
        assertThat(page.willFill()).isEqualTo(1);
        assertThat(page.willClear()).isEqualTo(1);
        assertThat(page.willChange()).isEqualTo(1);
        assertThat(page.unchanged()).isEqualTo(1);
        assertThat(page.done()).isTrue();
        var result = backfill(page.signature(), null, 200);
        assertThat(result.updated()).isEqualTo(3);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(x.flowStatus(fill.id())).isEqualTo("ylr");
        assertThat(x.flowStatus(clear.id())).isNull();
        assertThat(x.flowStatus(change.id())).isEqualTo("ylr");
        var history = x.linkageHistory(x.flow, clear.id()).getLast();
        assertThat(history.path("backfill").asBoolean()).isTrue();
        assertThat(history.has("sourceObjectId")).isFalse();
        assertThat(history.has("sourceRecordId")).isFalse();
        assertThat(history.path("name").asText()).isEqualTo("状态");
        assertThat(x.linkageHistory(x.flow, same.id()))
                .as("没变的记录不留回填历史")
                .noneMatch(node -> node.path("backfill").asBoolean());
    }

    /** 单条失败不影响其它：失败的那条保持旧值、记入失败清单；预告同样点名它。 */
    @Test
    void singleFailureDoesNotStopTheRest() {
        x.benchmark("ERROR", "RESTRICT");
        var ok = x.createFlow("正常");
        var broken = x.createFlow("两张凭证");
        var other = x.createFlow("也正常");
        x.createVoucher("凭证一", ok.id(), "ylr");
        x.createVoucher("凭证二", broken.id(), "ylr");
        x.createVoucher("凭证四", other.id(), "ysh");
        // 绕过系统给同一条流水插入第二张凭证：「报错」档下这条流水无法求值。
        jdbc.update(
                "INSERT INTO public.\""
                        + x.voucher.tableName()
                        + "\"(name, status, \""
                        + LinkageSyncFixture.refColumn(x.voucher, "flow")
                        + "\", creator, updater, create_time, update_time,"
                        + " deleted) VALUES ('凭证三', 'ysh', CAST(? AS bigint), '10001', '10001',"
                        + " clock_timestamp(), clock_timestamp(), 0)",
                broken.id());
        stale("wdj");
        var page = preview("PUBLISHED", null, 200);
        assertThat(page.failedCount()).isEqualTo(1);
        assertThat(page.failed())
                .extracting(LinkageSync.Failure::recordId)
                .containsExactly(broken.id());
        assertThat(page.failed().getFirst().reason()).contains("命中 2 行");
        assertThat(page.willChange()).isEqualTo(2);
        var result = backfill(page.signature(), null, 200);
        sample(
                "backfill with failure",
                new LinkageSync.BackfillRequest(
                        x.app, x.flow.objectId(), status(), page.signature(), null, 200),
                result);
        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failed().getFirst().recordId()).isEqualTo(broken.id());
        assertThat(result.failed().getFirst().reason())
                .startsWith("数据联动自动更新「" + x.flow.objectName() + " · 状态」失败：")
                .doesNotContain("本次数据变更未保存");
        assertThat(x.flowStatus(ok.id())).isEqualTo("ylr");
        assertThat(x.flowStatus(other.id())).isEqualTo("ysh");
        assertThat(x.flowStatus(broken.id())).as("失败的那条保持旧值").isEqualTo("wdj");
    }

    /** 签名与当前发布版登记的不一致：冲突，提示重新预告；一条都不写。 */
    @Test
    void staleSignatureIsRejectedAsConflict() {
        x.benchmark();
        var a = x.createFlow("流水");
        stale("ysh");
        assertThatThrownBy(() -> backfill("0".repeat(64), null, 200))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(NocodeErrorCodes.CONFLICT))
                .hasMessage("规则已变化，请重新预告");
        assertThatThrownBy(() -> backfill(null, null, 200)).hasMessage("规则已变化，请重新预告");
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
        // 没开自动更新的字段、越界的每页条数、不认识的基准都明确拒绝。
        assertThatThrownBy(
                        () ->
                                x.sync.preview(
                                        new LinkageSync.PreviewRequest(
                                                x.app,
                                                "PUBLISHED",
                                                x.flow.objectId(),
                                                id(x.flow, "memo"),
                                                null,
                                                null),
                                        10001))
                .hasMessageContaining("没有开启「来源变化时自动更新」");
        assertThatThrownBy(() -> preview("PUBLISHED", null, 501)).hasMessageContaining("1 到 500");
        assertThatThrownBy(() -> backfill(signature(), null, 201)).hasMessageContaining("1 到 200");
        assertThatThrownBy(() -> preview("LATEST", null, 10))
                .hasMessageContaining("PUBLISHED 或 DRAFT");
    }

    /** 停用的应用没有「发布版」这个基准：按发布版的总览、预告、回填都明确拒绝；按草稿的总览照常可看，全部算新开启。 */
    @Test
    void disabledApplicationHasNoPublishedBasis() {
        x.benchmark();
        var a = x.createFlow("流水");
        String signature = signature();
        stale("ysh");
        var head = x.f.applications.get(x.app).application();
        x.f.applications.status(
                new ApplicationCenter.Revision(x.app, head.revision(), "停用"), "DISABLED", 10001);
        assertThatThrownBy(() -> x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001))
                .hasMessageContaining("应用未发布或已停用");
        assertThatThrownBy(() -> preview(LinkageSync.BASIS_PUBLISHED, null, 10))
                .hasMessageContaining("应用未发布或已停用");
        assertThatThrownBy(() -> backfill(signature, null, 10)).hasMessageContaining("应用未发布或已停用");
        assertThat(x.flowStatus(a.id())).as("停用期间回填一条都不写").isEqualTo("ysh");
        var draft = x.sync.overview(x.app, LinkageSync.BASIS_DRAFT, 10001);
        assertThat(draft.applicationVersion()).isNull();
        assertThat(draft.fields()).hasSize(1);
        assertThat(draft.fields().getFirst().change()).isEqualTo("NEW");
    }

    /** 还没发布过的应用（首次发布前在发布对话框里看预告）：按草稿的总览与预告可用，全部算新开启；按发布版的明确拒绝。 */
    @Test
    void neverPublishedApplicationCanPreviewItsDraft() {
        x.benchmark();
        x.createFlow("流水一");
        x.createFlow("流水二");
        jdbc.update("UPDATE public.\"" + x.flow.tableName() + "\" SET status = NULL");
        var api = servicesContext.getBean(DataObjectApi.class);
        List<ApplicationCenter.ObjectReference> refs = new ArrayList<>();
        for (var d : List.of(x.flow, x.voucher)) {
            var version = api.getVersion(d.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            version.objectId(), version.versionNo(), version.checksum()));
        }
        String draftOnly =
                x.f.applications
                        .save(
                                new ApplicationCenter.Save(
                                        null,
                                        null,
                                        x.f.prefix() + "draftonly",
                                        "只有草稿",
                                        null,
                                        null,
                                        new ApplicationCenter.Definition(refs, List.of())),
                                10001)
                        .application()
                        .id();
        grantApplicationObjects(draftOnly);
        var overview = x.sync.overview(draftOnly, LinkageSync.BASIS_DRAFT, 10001);
        assertThat(overview.applicationVersion()).isNull();
        assertThat(overview.fields()).hasSize(1);
        assertThat(overview.fields().getFirst().change()).isEqualTo("NEW");
        var forecast =
                x.sync.preview(
                        new LinkageSync.PreviewRequest(
                                draftOnly,
                                LinkageSync.BASIS_DRAFT,
                                x.flow.objectId(),
                                status(),
                                null,
                                200),
                        10001);
        assertThat(forecast.total()).isEqualTo(2);
        assertThat(forecast.willFill()).as("两条流水都没有凭证：由空变成「未登记」").isEqualTo(2);
        assertThat(forecast.failedCount()).isZero();
        assertThatThrownBy(() -> x.sync.overview(draftOnly, LinkageSync.BASIS_PUBLISHED, 10001))
                .hasMessageContaining("应用未发布或已停用");
    }

    /** 只有应用设计者能调用。 */
    @Test
    void onlyDesignersMayCall() {
        x.benchmark();
        assertThatThrownBy(() -> x.sync.overview(x.app, "PUBLISHED", 20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                x.sync.preview(
                                        new LinkageSync.PreviewRequest(
                                                x.app,
                                                "PUBLISHED",
                                                x.flow.objectId(),
                                                status(),
                                                null,
                                                10),
                                        20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                x.sync.backfill(
                                        new LinkageSync.BackfillRequest(
                                                x.app,
                                                x.flow.objectId(),
                                                status(),
                                                signature(),
                                                null,
                                                10),
                                        20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /** 目标在流程中照常回填。 */
    @Test
    void targetInRunningProcessIsStillBackfilled() {
        x.benchmark();
        var a = x.createFlow("流水");
        x.createVoucher("凭证", a.id(), "ylr");
        stale("wdj");
        jdbc.update(
                "INSERT INTO public.nocode_record_process(application_id, application_version,"
                    + " object_id, object_version, record_id, action_id, name, business_key,"
                    + " process_definition_id, process_definition_key, status, creator, updater)"
                    + " VALUES (?, 1, ?, 1, ?, 'act', '审批', ?, 'approval:1', 'approval', 'RUNNING',"
                    + " '10001', '10001')",
                Long.valueOf(x.app),
                Long.valueOf(x.flow.objectId()),
                a.id(),
                "nocode:" + UUID.randomUUID());
        var result = backfill(signature(), null, 200);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
    }

    /** 没有应用上下文的入口（对象数据维护）改来源：不触发；预告显示 1；回填后为 0。 */
    @Test
    void objectDataMaintenanceDoesNotTriggerAndPreviewCatchesIt() {
        var permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
        x.benchmark();
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        var maintenance = servicesContext.getBean(ObjectDataMaintenanceService.class);
        var model = maintenance.model(x.voucher.objectId(), 10001L);
        var current = x.runtime.get(x.app, x.voucher.objectId(), v.id(), 10001).record();
        maintenance.save(
                new ObjectDataMaintenance.Save(
                        x.voucher.objectId(),
                        model.versionNo(),
                        model.checksum(),
                        v.id(),
                        current.revision(),
                        Map.of(id(x.voucher, "status"), "ysh"),
                        UUID.randomUUID().toString()),
                10001L);
        assertThat(x.column(x.voucher, v.id(), "status")).isEqualTo("ysh");
        assertThat(x.flowStatus(a.id())).as("没有应用上下文的写入不触发").isEqualTo("ylr");
        assertThat(x.pending()).as("预告对账发现 1 条").isEqualTo(1);
        var result = backfill(signature(), null, 200);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
        assertThat(x.pending()).isZero();
    }

    /**
     * 标杆场景端到端（契约第 11 章，界面步骤用接口等价验证）：存量数据 → 对象发布规则 → 应用草稿同步 → 发布前预告 → 发布 → 回填 → 之后来源的新增 / 改状态 / 改挂 /
     * 删除都在同一次保存里更新流水；凭证的流水引用可按「状态 等于 未登记」筛选。
     */
    @Test
    void benchmarkScenarioEndToEnd() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.app = x.f.app(x.flow, x.voucher);
        // 标杆场景走的是「对象发布 → 人工同步草稿 → 预告 → 发布 → 回填」：把这个应用的自动跟随单独关掉，保留这条路径的覆盖。
        stopFollowing(x.app);
        // 存量：5 条流水，其中 2 条已有凭证；此时没有规则，流水状态都是空。
        List<Row> flows = new ArrayList<>();
        for (int i = 0; i < 5; i++) flows.add(x.createFlow("存量流水" + i));
        x.createVoucher("存量凭证0", flows.get(0).id(), "ylr");
        x.createVoucher("存量凭证3", flows.get(3).id(), "ysh");
        assertThat(x.flowStatus(flows.get(0).id())).isNull();

        // 对象发布规则；应用草稿同步到新版本但还没发布。
        String statusId = status();
        x.flow =
                x.f.republish(
                        x.flow,
                        Map.of(
                                statusId,
                                x.flow.fieldOptions()
                                        .get(statusId)
                                        .withRules(x.benchmarkRule("FIRST"))));
        var api = servicesContext.getBean(DataObjectApi.class);
        var before = x.f.applications.get(x.app);
        var refs = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var r : before.draft().objects()) {
            var version = api.getVersion(r.objectId(), null);
            refs.add(
                    new ApplicationCenter.ObjectReference(
                            version.objectId(), version.versionNo(), version.checksum()));
        }
        var saved =
                x.f.applications.save(
                        new ApplicationCenter.Save(
                                x.app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, before.draft().resources())),
                        10001);
        grantApplicationObjects(x.app);

        // 步骤 1：发布前预告（DRAFT 基准）。
        var draft = x.sync.overview(x.app, LinkageSync.BASIS_DRAFT, 10001);
        sample("overview DRAFT", Map.of("applicationId", x.app, "basis", "DRAFT"), draft);
        assertThat(draft.applicationVersion()).isNull();
        assertThat(draft.fields()).hasSize(1);
        assertThat(draft.fields().getFirst().change()).isEqualTo("NEW");
        assertThat(draft.fields().getFirst().targetFieldName()).isEqualTo("状态");
        assertThat(draft.fields().getFirst().anchorFieldName()).isEqualTo("流水");
        assertThat(x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001).fields()).isEmpty();
        var forecast = preview("DRAFT", null, 200);
        sample(
                "preview DRAFT",
                new LinkageSync.PreviewRequest(
                        x.app, "DRAFT", x.flow.objectId(), statusId, null, 200),
                forecast);
        assertThat(forecast.total()).isEqualTo(5);
        assertThat(forecast.willFill()).as("将更新 N 条 = 存量流水条数（全部由空变成值）").isEqualTo(5);
        assertThat(x.flowStatus(flows.get(0).id())).as("预告不写数据").isNull();

        // 步骤 2：发布后回填。
        x.f.publishSynced(saved.application());
        var published = x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001);
        assertThat(published.applicationVersion()).isEqualTo(2);
        assertThat(published.fields().getFirst().change()).isEqualTo("NEW");
        assertThat(published.fields().getFirst().signature()).isEqualTo(forecast.signature());
        assertThat(
                        x.sync.overview(x.app, LinkageSync.BASIS_DRAFT, 10001)
                                .fields()
                                .getFirst()
                                .change())
                .isEqualTo("UNCHANGED");
        var filled = backfill(forecast.signature(), null, 200);
        assertThat(filled.updated()).isEqualTo(5);
        assertThat(x.flowStatus(flows.get(0).id())).isEqualTo("ylr");
        assertThat(x.flowStatus(flows.get(3).id())).isEqualTo("ysh");
        assertThat(x.flowStatus(flows.get(1).id())).isEqualTo("wdj");
        assertThat(x.pending()).as("再检查一次").isZero();

        // 步骤 3–7：新建流水、凭证新建 / 改状态 / 改挂 / 删除。
        var xFlow = x.createFlow("新流水甲");
        var yFlow = x.createFlow("新流水乙");
        assertThat(x.flowStatus(xFlow.id())).isEqualTo("wdj");
        var v = x.createVoucher("新凭证", xFlow.id(), "ylr");
        assertThat(x.flowStatus(xFlow.id())).isEqualTo("ylr");
        assertThat(x.linkageHistory(x.flow, xFlow.id())).hasSize(1);
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "status"), "ysh"));
        assertThat(x.flowStatus(xFlow.id())).isEqualTo("ysh");
        x.update(x.voucher, v.id(), Map.of(relationField(x.voucher, "flow"), yFlow.id()));
        assertThat(x.flowStatus(xFlow.id())).isEqualTo("wdj");
        assertThat(x.flowStatus(yFlow.id())).isEqualTo("ysh");

        // 步骤 10：凭证的流水引用筛选「状态 等于 未登记」——候选里只有没做凭证的流水；编辑已有凭证不被拦。
        x.voucher =
                rules(
                        x.voucher,
                        relationField(x.voucher, "flow"),
                        filter(null, constant(statusId, "eq", "wdj")));
        var candidates =
                x.f.selection(x.app, x.voucher, relationField(x.voucher, "flow"), Map.of(), null);
        assertThat(candidates.options())
                .extracting(SelectionFields.Option::value)
                .containsExactlyInAnyOrder(
                        flows.get(1).id(), flows.get(2).id(), flows.get(4).id(), xFlow.id());
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "kind"), "编辑已有凭证"));
        assertThat(x.flowStatus(yFlow.id())).isEqualTo("ysh");

        x.delete(x.voucher, v.id());
        assertThat(x.flowStatus(yFlow.id())).isEqualTo("wdj");
        assertThat(x.pending()).isZero();
    }
}
