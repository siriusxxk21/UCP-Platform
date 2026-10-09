package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RuleFixtures.formula;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 同一字段只能由一处写入：应用自动更新的目标字段，与数据对象上的数据联动 / 公式默认值互斥，两个方向都要拦住——
 * 应用发布时拒绝写入已配规则的字段；对象发布时拒绝给已被自动更新写入的字段配规则。两套能力都保留。仅清理本测试前缀拥有的夹具。
 */
class RuleWriteExclusionIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private DataObjectApi objects;
    private DataCenter.Definition source, target;
    private String relation;
    private int serial;

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
        objects = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
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
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    private DataCenter.Definition object(
            String name,
            List<FieldDefinition> fields,
            List<DataCenter.Relation> relations,
            Map<String, DataCenter.FieldOptions> options) {
        var request = fixture.createRequest("object" + serial++);
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        name,
                                        null,
                                        request.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                relations,
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "写入互斥集成测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    private FieldDefinition f(String code, String type) {
        return fixture.field(code, code, type, serial++);
    }

    private String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private ApplicationCenter.ObjectReference ref(DataCenter.Definition d) {
        var v = objects.getVersion(d.objectId(), null);
        return new ApplicationCenter.ObjectReference(v.objectId(), v.versionNo(), v.checksum());
    }

    /** 事件赋值：来源新增或修改时，把来源的 name 写到目标的 latest。 */
    private ApplicationCenter.Resource latestRule(String id) {
        var config =
                new ApplicationAutomations.Config(
                        source.objectId(),
                        target.objectId(),
                        true,
                        "EVENT",
                        Set.of("CREATE", "UPDATE"),
                        null,
                        new ApplicationAutomations.Binding(relation, "OUTGOING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        field(target, "latest"),
                                        "FIELD",
                                        field(source, "name"),
                                        null,
                                        null)));
        return new ApplicationCenter.Resource(
                id,
                "AUTOMATION",
                id,
                id,
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private String application(List<ApplicationCenter.Resource> rules) {
        var saved =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "写入互斥测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(ref(source), ref(target)), rules)),
                        10001);
        grantApplicationObjects(saved.application().id());
        apps.publish(
                new ApplicationCenter.Revision(
                        saved.application().id(), saved.application().revision(), "测试"),
                10001);
        return saved.application().id();
    }

    private void objects(Map<String, DataCenter.FieldOptions> targetOptions) {
        target =
                object(
                        "订单",
                        List.of(f("name", "TEXT"), f("latest", "TEXT")),
                        List.of(),
                        targetOptions);
        source =
                object(
                        "收款",
                        List.of(f("name", "TEXT")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "purchase",
                                        "关联订单",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)),
                        Map.of());
        relation = source.relations().getFirst().id();
    }

    @Test
    void applicationPublishRejectsAutomationTargetThatCarriesAnObjectRule() {
        objects(
                Map.of(
                        "latest",
                        DataCenter.FieldOptions.defaults()
                                .withRules(formula("upper(name)", null))));

        assertThatThrownBy(() -> application(List.of(latestRule("event"))))
                .hasMessageContaining("自动更新“event”要写入的字段")
                .hasMessageContaining("配置了公式默认值")
                .hasMessageContaining("同一字段只能由一处写入");
    }

    @Test
    void objectPublishRejectsRuleOnFieldWrittenByPublishedAutomation() {
        objects(Map.of());
        // 没有对象规则时，自动更新照常发布：两套能力都保留。
        application(List.of(latestRule("event")));

        var current = designs.get(target.objectId());
        var draft =
                designs.editPublished(
                        new DataCenter.Revision(
                                current.draft().id(), current.draft().lockVersion(), null),
                        10001);
        String latest = field(target, "latest");
        var ruled =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        draft.draft().id(),
                                        draft.draft().lockVersion(),
                                        draft.draft().objectCode(),
                                        draft.draft().objectName(),
                                        draft.draft().description(),
                                        draft.draft().tableName(),
                                        draft.draft().titleFieldId(),
                                        draft.draft().fields(),
                                        List.of()),
                                null,
                                Map.of(
                                        latest,
                                        draft.fieldOptions()
                                                .get(latest)
                                                .withRules(formula("upper(name)", null))),
                                null,
                                null,
                                null),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                ruled.draft().id(), ruled.draft().lockVersion(), null),
                        10001);

        assertThat(plan.checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.blocking()).isTrue();
                            assertThat(check.message())
                                    .contains("配置了公式默认值")
                                    .contains("自动更新“event”也在写入该字段")
                                    .contains("同一字段只能由一处写入");
                        });
        assertThat(plan.state()).isEqualTo("BLOCKED");
    }
}
