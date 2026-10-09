package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 业务方 2026-10-04 故障原样复现（当前开发库真实读写）：入住记录.物件名称 的引用筛选「管理状态 等于 固定值 民宿管理」，管理状态是对象引用、存的是记录 ID。 存量文本值 ⇒
 * 候选不再静默为空而是说明配置有误、保存被拒且报能找到改哪里的话、对象设计保存被拒；改成选记录（存记录 ID）⇒ 候选只出民宿管理的物件、保存成功。
 */
class ReferenceConstantIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition status;
    private DataCenter.Definition property;
    private DataCenter.Definition stay;
    private String app;
    private String management;
    private String propertyField;
    private final Map<String, String> ids = new LinkedHashMap<>();

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
        f = new FieldRuleFixture();
        status = f.object("st", List.of(), Map.of(), List.of(), List.of());
        property =
                f.object(
                        "pp",
                        List.of(field("memo", "备注", "TEXT")),
                        Map.of(),
                        List.of(reference("gl", status)),
                        List.of());
        stay =
                f.object(
                        "sy",
                        List.of(field("guest", "入住人", "TEXT"), field("copied", "管理状态名", "TEXT")),
                        Map.of(),
                        List.of(reference("wj", property)),
                        List.of());
        management = relationField(property, "gl");
        propertyField = relationField(stay, "wj");
        app = f.app(status, property, stay);
        ids.put("民宿管理", f.save(app, status, values(id(status, "name"), "民宿管理")).id());
        ids.put("一般管理", f.save(app, status, values(id(status, "name"), "一般管理")).id());
        for (var row :
                List.of(List.of("银座公寓", "民宿管理"), List.of("新宿大楼", "一般管理"), List.of("涩谷小屋", "民宿管理")))
            ids.put(
                    row.get(0),
                    f.save(
                                    app,
                                    property,
                                    values(
                                            id(property, "name"),
                                            row.get(0),
                                            management,
                                            ids.get(row.get(1))))
                            .id());
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private String name(DataCenter.Definition d, String fieldId) {
        return d.fields().stream()
                .filter(x -> x.id().equals(fieldId))
                .findFirst()
                .orElseThrow()
                .name();
    }

    /** 存量：引用筛选里的固定值存了名称（直接写进已发布快照，模拟旧条件行的自由文本框）。 */
    private void storedFilter(Object value) {
        stay =
                FieldRuleFixture.rules(
                        stay, propertyField, filter(null, constant(management, "eq", value)));
    }

    private String configError(Object value) {
        return "配置有误：条件「"
                + name(property, management)
                + "」的固定值「"
                + value
                + "」不是「"
                + status.objectName()
                + "」里的记录，请到对象设计里重新选择";
    }

    private Map<String, Object> stayValues(String property) {
        return values(id(stay, "name"), "张三", propertyField, ids.get(property));
    }

    @Test
    void storedNameExplainsItselfInCandidates() {
        storedFilter("民宿管理");
        var r = f.selection(app, stay, propertyField, Map.of(), List.of());
        assertThat(r.ruleState()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(r.options()).isEmpty();
        assertThat(r.ruleMessage()).isEqualTo(configError("民宿管理"));
    }

    @Test
    void storedNameStillBlocksSavingWithReadableMessage() {
        storedFilter("民宿管理");
        assertThatThrownBy(() -> f.save(app, stay, stayValues("银座公寓")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("「" + name(stay, propertyField) + "」的引用筛选" + configError("民宿管理"));
    }

    @Test
    void recordIdFiltersCandidatesAndSaves() {
        storedFilter(ids.get("民宿管理"));
        var r = f.selection(app, stay, propertyField, Map.of(), List.of());
        assertThat(r.ruleState()).as("筛选生效时候选接口不带状态").isNull();
        assertThat(r.options().stream().map(SelectionFields.Option::label).sorted())
                .containsExactly("涩谷小屋", "银座公寓");
        assertThat(f.save(app, stay, stayValues("银座公寓")).id()).isNotBlank();
        assertThatThrownBy(() -> f.save(app, stay, stayValues("新宿大楼")))
                .hasMessageContaining("所选记录不符合对象引用筛选");
    }

    private void redesign(Object value) {
        var options =
                stay.fieldOptions()
                        .get(propertyField)
                        .withRules(filter(null, constant(management, "eq", value)));
        f.republish(stay, Map.of(propertyField, options));
    }

    private String designError(Object value) {
        return "字段「"
                + name(stay, propertyField)
                + "」的引用筛选：条件「"
                + name(property, management)
                + "」的固定值「"
                + value
                + "」不是「"
                + status.objectName()
                + "」里的记录，请重新选择";
    }

    @Test
    void designSaveRejectsNamesAndMissingRecords() {
        assertThatThrownBy(() -> redesign("民宿管理")).hasMessage(designError("民宿管理"));
        assertThatThrownBy(() -> redesign("987654321")).hasMessage(designError("987654321"));
        redesign(ids.get("民宿管理"));
        assertThat(
                        published(stay.objectId())
                                .fieldOptions()
                                .get(propertyField)
                                .rules()
                                .reference()
                                .filter()
                                .getFirst()
                                .value())
                .isEqualTo(ids.get("民宿管理"));
    }

    @Test
    void linkageWithStoredNameExplainsItself() {
        String copied = id(stay, "copied");
        stay =
                FieldRuleFixture.rules(
                        stay,
                        copied,
                        linkage(
                                property,
                                id(property, "name"),
                                "FIRST",
                                List.of(constant(management, "eq", "民宿管理"))));
        var result =
                result(f.evaluate(app, stay, Map.of(), List.of(), List.of(), null, 10001), copied);
        assertThat(result.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(result.message()).isEqualTo(configError("民宿管理"));
    }
}
