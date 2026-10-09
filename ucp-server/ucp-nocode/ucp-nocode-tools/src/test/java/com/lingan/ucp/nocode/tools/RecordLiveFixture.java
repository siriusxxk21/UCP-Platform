package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 实时推送集成用例共用的虚构夹具：一个与「资金流水」同形状的对象（账户、记账日序、金额、按日序累计的「余额」）、带内部明细的单据、
 * 关联对象与应用，以及对登记簿交付的捕获。只创建并清理本次随机前缀的对象与应用，数据全部虚构。
 */
final class RecordLiveFixture {
    static final long OWNER = 10001L;
    static final long MEMBER = 20002L;

    final NocodeIntegrationSupport fixture = new NocodeIntegrationSupport();
    final RecordService runtime = servicesContext.getBean(RecordService.class);
    final ApplicationService applications = servicesContext.getBean(ApplicationService.class);
    final DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
    final RecordChangeCollector collector = servicesContext.getBean(RecordChangeCollector.class);

    /** 登记簿交付的每一份批，按交付顺序。 */
    final List<RecordChangeBatch> batches = new CopyOnWriteArrayList<>();

    private final AutoCloseable capture;
    private int serial;

    RecordLiveFixture() {
        fixture.name();
        var permissions =
                servicesContext.getBean(
                        com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi
                                .class);
        for (String permission :
                List.of(
                        ObjectSharingService.MANAGE_PERMISSION,
                        "nocode:app:manage",
                        "nocode:object:query",
                        "nocode:object:manage"))
            org.mockito.Mockito.when(permissions.hasAnyPermissions(OWNER, permission))
                    .thenReturn(true);
        capture = collector.listen(batches::add);
    }

    void cleanup() {
        try {
            capture.close();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%")) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_record_process WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        jdbc.update(
                "DELETE FROM public.nocode_ordered_calculation_state WHERE object_id IN"
                        + " (SELECT id FROM public.nocode_object WHERE object_code LIKE ?)",
                fixture.prefix + "%");
        jdbc.update(
                "DELETE FROM public.nocode_business_counter WHERE object_id IN (SELECT id FROM"
                        + " public.nocode_object WHERE object_code LIKE ?)",
                fixture.prefix + "%");
        fixture.clean();
    }

    /** 流水：按账户分组、按记账日序累计金额得到余额。mode 为 ON_SAVE（落库）或 LIVE（读取时计算）。 */
    DataCenter.Definition ledger(String mode) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (String code : List.of("name", "account")) fields.add(field(code, "TEXT", fields));
        for (String code : List.of("sequence", "tie")) fields.add(field(code, "INTEGER", fields));
        for (String code : List.of("incoming", "outgoing", "opening"))
            fields.add(field(code, "DECIMAL", fields));
        fields.add(field("included", "BOOLEAN", fields));
        fields.add(field("balance", "FORMULA", fields));
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>();
        options.put(
                "balance",
                new DataCenter.FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        "DECIMAL",
                        "NONE",
                        null,
                        false,
                        false,
                        null,
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                mode,
                                null,
                                null,
                                "incoming",
                                "SUM",
                                "AND",
                                List.of(new CalculationOptions.Match("included", "eq", null, true)),
                                false,
                                List.of("account"),
                                new CalculationOptions.RunningTotal(
                                        "sequence", "tie", "outgoing", null, "opening"))));
        SaveObjectDraft base = fixture.createRequest("ledger" + serial++);
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        "虚构流水",
                                        null,
                                        base.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                List.of(),
                                List.of(),
                                List.of()),
                        OWNER));
    }

    /** 把流水的余额在 LIVE 与 ON_SAVE 之间切换并重新发布。 */
    DataCenter.Definition switchLedgerMode(DataCenter.Definition ledger, String mode) {
        DataCenter.Design edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                ledger.objectId(),
                                designs.get(ledger.objectId()).draft().lockVersion(),
                                "切换余额计算时机"),
                        OWNER);
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>(edited.fieldOptions());
        for (Map.Entry<String, DataCenter.FieldOptions> entry :
                new ArrayList<>(options.entrySet())) {
            CalculationOptions old = entry.getValue().calculation();
            if (old == null || !"RUNNING_TOTAL".equals(old.mode())) continue;
            options.put(
                    entry.getKey(),
                    entry.getValue()
                            .withCalculation(
                                    new CalculationOptions(
                                            old.mode(),
                                            mode,
                                            old.targetObjectId(),
                                            old.relationId(),
                                            old.targetField(),
                                            old.aggregate(),
                                            old.logic(),
                                            old.conditions(),
                                            old.excludeCurrent(),
                                            old.groupFields(),
                                            old.runningTotal(),
                                            old.sequence())));
        }
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        edited.draft(),
                                        edited.draft().fields(),
                                        List.of(),
                                        edited.draft().titleFieldId()),
                                edited.settings(),
                                options,
                                edited.relations(),
                                edited.indexes(),
                                edited.details(),
                                edited.mainBinding()),
                        OWNER));
    }

    /** 只有标题字段的对象；detail 为真时带一个内部明细「分录」（数量必填）。 */
    DataCenter.Definition plain(boolean detail) {
        List<DataCenter.Detail> details =
                detail
                        ? List.of(
                                new DataCenter.Detail(
                                        null,
                                        "items",
                                        "分录",
                                        "biz_" + fixture.prefix + "items" + serial,
                                        "ACTIVE",
                                        List.of(
                                                new FieldDefinition(
                                                        "qty",
                                                        null,
                                                        "quantity",
                                                        "数量",
                                                        "INTEGER",
                                                        null,
                                                        null,
                                                        null,
                                                        true,
                                                        false,
                                                        0)),
                                        Map.of(),
                                        List.of()))
                        : List.of();
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("object" + serial++),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                details),
                        OWNER));
    }

    /** 指向 target 的关联对象；kind 为 REFERENCE / MASTER_DETAIL / MANY_TO_MANY，deletion 为删除策略。 */
    DataCenter.Definition related(DataCenter.Definition target, String kind, String deletion) {
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("related" + serial++),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "related",
                                                "业务关联",
                                                kind,
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                deletion)),
                                List.of(),
                                List.of()),
                        OWNER));
    }

    DataCenter.Definition publish(DataCenter.Design design) {
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        OWNER);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(
                                                plan.id(),
                                                "实时推送夹具",
                                                List.of(),
                                                plan.applicationUpgrades().stream()
                                                        .map(
                                                                ObjectApplicationUpgrade.Impact
                                                                        ::applicationId)
                                                        .toList()),
                                        OWNER)
                                .state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    /** 建一个引用这些对象的应用并发布；应用对每个对象取得全部操作与全部字段（含计算取数）。 */
    String app(List<ApplicationCenter.Resource> resources, DataCenter.Definition... definitions) {
        List<ApplicationCenter.ObjectReference> references =
                Arrays.stream(definitions)
                        .map(
                                d -> {
                                    var version = objects.getVersion(d.objectId(), null);
                                    return new ApplicationCenter.ObjectReference(
                                            version.objectId(),
                                            version.versionNo(),
                                            version.checksum());
                                })
                        .toList();
        var saved =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "实时推送夹具",
                                null,
                                null,
                                new ApplicationCenter.Definition(references, resources)),
                        OWNER);
        String app = saved.application().id();
        grantApplicationObjects(app);
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        for (DataCenter.Definition d : definitions) {
            ApplicationAuthorization.ObjectGrant grant =
                    sharing.storedPermission(d.objectId(), app);
            ObjectSharing.Grant existing =
                    sharing.forApplication(app).stream()
                            .filter(item -> item.objectId().equals(d.objectId()))
                            .findFirst()
                            .orElseThrow();
            sharing.save(
                    new ObjectSharing.Save(
                            d.objectId(),
                            app,
                            existing.revision(),
                            new ApplicationAuthorization.ObjectGrant(
                                    grant.objectId(),
                                    grant.actions(),
                                    grant.scope(),
                                    grant.readFields(),
                                    grant.writeFields(),
                                    grant.readDetails(),
                                    grant.writeDetails(),
                                    grant.readRelations(),
                                    grant.writeRelations(),
                                    Map.of(),
                                    d.fields().stream()
                                            .map(FieldDefinition::id)
                                            .collect(Collectors.toSet())),
                            "实时推送夹具显式授权"),
                    OWNER);
        }
        applications.publish(new ApplicationCenter.Revision(app, 0, "实时推送夹具"), OWNER);
        return app;
    }

    /** 把成员 20002 登记为应用成员并给定对象授权。 */
    void authorize(String app, ApplicationAuthorization.ObjectGrant... grants) {
        var authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        authorization.save(
                new ApplicationAuthorization.Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", Long.toString(MEMBER), List.of(grants)))),
                OWNER);
    }

    String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    Map<String, Object> values(DataCenter.Definition d, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((code, value) -> result.put(field(d, code), value));
        return result;
    }

    /** 一条流水的输入：账户、记账日序、金额。 */
    Map<String, Object> entry(
            DataCenter.Definition ledger, String account, int day, String amount) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", account + "-" + day);
        input.put("account", account);
        input.put("sequence", day);
        input.put("tie", day);
        input.put("incoming", amount);
        input.put("outgoing", "0");
        input.put("opening", "0");
        input.put("included", true);
        return values(ledger, input);
    }

    Row create(String app, DataCenter.Definition d, Map<String, Object> values) {
        return runtime.save(new Save(app, d.objectId(), null, null, values, null), OWNER).record();
    }

    Row named(String app, DataCenter.Definition d, String name) {
        return create(app, d, Map.of(field(d, "name"), name));
    }

    Row get(String app, DataCenter.Definition d, String id) {
        return runtime.get(app, d.objectId(), id, OWNER).record();
    }

    /** 批里某个对象的那一项；没有则为 null。 */
    static RecordChangeBatch.ObjectChange of(RecordChangeBatch batch, DataCenter.Definition d) {
        return batch.objects().stream()
                .filter(change -> change.objectId().equals(d.objectId()))
                .findFirst()
                .orElse(null);
    }

    private static FieldDefinition field(String code, String type, List<FieldDefinition> fields) {
        return new FieldDefinition(
                code,
                null,
                code,
                code,
                type,
                "TEXT".equals(type) ? 100 : null,
                "DECIMAL".equals(type) ? 38 : null,
                "DECIMAL".equals(type) ? 10 : null,
                false,
                false,
                fields.size());
    }
}
