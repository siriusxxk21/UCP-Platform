package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.api.ObjectSharing;
import com.richuang.os.nocode.application.service.resource.ApplicationLinkageTriggers;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.metadata.service.object.LinkageTriggerPlan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应用自动跟随、系统取数与数据联动「来源变化时自动更新」的衔接（契约第 11 章）。夹具用联动一期的标杆配置： 流水.状态 ← 凭证.凭证状态，条件「凭证的流水 等于
 * 当前记录」，没有凭证时填「未登记」。
 *
 * <ul>
 *   <li>L0：经自动跟随产生的应用版本，联动的反向索引照常登记，来源保存照常触发回写；
 *   <li>补丁 A：联动读来源以「应用对来源对象的授权」为前提——授权收窄成「本人创建」时返回说明而不是悄悄按全部数据算；
 *   <li>补丁 B：没开自动更新的联动同样按全部数据算；
 *   <li>自动更新写目标记录不看操作者在目标对象上的授权——这一条是联动一期的行为，这里钉住它没有被本期的授权展开弄回去。
 * </ul>
 */
class ApplicationFollowLinkageIntegrationTest {
    private LinkageSyncFixture x;
    private FollowFixture follow;
    private ApplicationLinkageTriggers triggers;

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
        follow = new FollowFixture();
        triggers = servicesContext.getBean(ApplicationLinkageTriggers.class);
    }

    @AfterEach
    void cleanup() {
        follow.cleanup();
        x.cleanup();
    }

    private List<String> stored(int version) {
        return jdbc.queryForList(
                "SELECT source_object_id || '|' || target_object_id || '|' || target_object_version"
                        + " || '|' || target_field_id || '|' || anchor FROM"
                        + " public.nocode_linkage_trigger WHERE application_id = ? AND"
                        + " application_version = ? AND deleted = 0 ORDER BY target_object_id,"
                        + " target_field_id",
                String.class,
                Long.valueOf(x.app),
                version);
    }

    private List<String> derived(int version) {
        List<String> rows = new ArrayList<>();
        for (LinkageTriggerPlan.Row row :
                triggers.derive(x.f.applications.published(x.app, version).definition()))
            rows.add(
                    String.join(
                            "|",
                            row.sourceObjectId(),
                            row.targetObjectId(),
                            Integer.toString(row.targetObjectVersion()),
                            row.targetFieldId(),
                            row.anchor()));
        return rows;
    }

    private void share(DataCenter.Definition d, ApplicationAuthorization.ObjectGrant grant) {
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        int revision =
                sharing.forApplication(x.app).stream()
                        .filter(item -> item.objectId().equals(d.objectId()))
                        .mapToInt(ObjectSharing.Grant::revision)
                        .findFirst()
                        .orElse(0);
        sharing.save(new ObjectSharing.Save(d.objectId(), x.app, revision, grant, "衔接用例"), 10001);
    }

    /** 在流水的表单里对已有记录求值（当前记录锚点需要记录 ID），取「状态」那一格的结果。 */
    private FieldRules.Result status(Row flowRow, long actor) {
        return result(
                x.runtime.evaluateRules(
                        new FieldRules.EvaluateQuery(
                                x.app,
                                x.flow.objectId(),
                                null,
                                flowRow.id(),
                                false,
                                Map.of(id(x.flow, "name"), "流水"),
                                List.of(),
                                List.of(),
                                List.of()),
                        actor),
                id(x.flow, "status"));
    }

    /** L0：对象发布后应用自动跟上，新应用版本登记了自己的索引行（目标对象版本是新的），来源保存照常回写。 */
    @Test
    void followedVersionRegistersTheReverseIndexAndStillTriggers() {
        x.benchmark();
        assertThat(stored(1)).hasSize(1).isEqualTo(derived(1));
        // 流水发布新版本（走真实的设计保存与对象发布；规则原样带上，否则夹具直接写进快照的规则不会进新版本）。
        String statusId = id(x.flow, "status");
        x.flow =
                x.f.republish(
                        x.flow,
                        Map.of(
                                statusId,
                                x.flow.fieldOptions()
                                        .get(statusId)
                                        .withRules(x.benchmarkRule("FIRST"))));
        assertThat(follow.publishedVersion(x.app)).as("没有人去应用里点任何东西").isEqualTo(2);
        assertThat(follow.publishReason(x.app)).startsWith("系统跟随：");
        assertThat(stored(2)).hasSize(1).isEqualTo(derived(2));
        assertThat(stored(2).getFirst())
                .startsWith(x.voucher.objectId() + "|" + x.flow.objectId() + "|2|");
        assertThat(stored(1)).as("旧版本的行不动").hasSize(1);
        Row a = x.createFlow("流水一");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        x.createVoucher("凭证一", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).as("经跟随后的应用版本保存来源：照常回写").isEqualTo("ylr");
    }

    /** 补丁 A：联动读来源以应用对来源对象的授权为前提。收窄成「本人创建」⇒ 说明原因、不填值；恢复后照常。 */
    @Test
    void linkageSourceReadDependsOnTheApplicationCeiling() {
        x.benchmark();
        Row a = x.createFlow("流水一");
        x.createVoucher("凭证一", a.id(), "ylr");
        assertThat(status(a, 10001).value()).isEqualTo("ylr");
        ApplicationAuthorization.ObjectGrant full = resolvedPermission(x.voucher.objectId(), x.app);
        share(
                x.voucher,
                new ApplicationAuthorization.ObjectGrant(
                        full.objectId(),
                        full.actions(),
                        "OWN",
                        full.readFields(),
                        full.writeFields(),
                        full.readDetails(),
                        full.writeDetails(),
                        full.readRelations(),
                        full.writeRelations()));
        FieldRules.Result denied = status(a, 10001);
        assertThat(denied.state()).isEqualTo("SOURCE_NOT_READABLE");
        assertThat(denied.value()).isNull();
        assertThat(denied.message())
                .isEqualTo(
                        "「"
                                + x.voucher.objectName()
                                + "」授给应用「"
                                + x.f.applications.get(x.app).application().name()
                                + "」的记录范围是“当前操作者创建的记录”，系统计算必须能读全部记录，不填值");
        share(x.voucher, full);
        assertThat(status(a, 10001).value()).isEqualTo("ylr");
    }

    /** 补丁 B + 联动一期的写入口径：成员在来源对象上只看得到自己建的、在目标对象上没有修改权，联动照样按全部数据算、照样回写。 */
    @Test
    void memberScopeNeitherNarrowsTheSourceReadNorBlocksTheSystemWrite() {
        x.benchmark();
        x.authorize(
                LinkageSyncFixture.grant(
                        x.flow,
                        "ALL",
                        Set.of("READ"),
                        LinkageSyncFixture.allFields(x.flow),
                        Set.of()),
                LinkageSyncFixture.grant(
                        x.voucher,
                        "OWN",
                        Set.of("READ", "CREATE"),
                        LinkageSyncFixture.allFields(x.voucher),
                        LinkageSyncFixture.allFields(x.voucher)));
        Row a = x.createFlow("流水一");
        x.createVoucher("创建人的凭证", a.id(), "ylr");
        assertThat(status(a, 20002).value()).as("成员看不到创建人的凭证，联动仍按全部数据算").isEqualTo("ylr");
        Row b = x.createFlow("流水二");
        x.createVoucher(x.app, "成员的凭证", b.id(), "ysh", 20002);
        assertThat(x.flowStatus(b.id())).as("成员在流水上没有修改权：自动更新是系统写入，照样回写").isEqualTo("ysh");
    }
}
