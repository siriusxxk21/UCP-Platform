package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.nocode.api.CalculationOptions;
import com.richuang.os.nocode.api.DataCenter.Definition;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.application.service.sharing.ImpliedObjects;
import com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Implied;
import com.richuang.os.nocode.application.service.sharing.ImpliedObjects.Via;
import com.richuang.os.nocode.enums.RelationTypeEnum;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隐式可读集合的纯计算；不连库。
 *
 * <p>第一跳认关系目标（含明细上的关系与多对多）、数据联动来源、引用筛选目标、挑取值来源、计算来源；后续跳只沿取数链（计算、联动、挑取值）传，
 * 不沿关系传——否则引用一个对象就会把整张关系网都放行。
 */
class ImpliedObjectsTest {
    private final Map<String, Definition> published = new HashMap<>();

    private Definition latest(String id) {
        return published.get(id);
    }

    private Definition publish(Definition d) {
        published.put(d.objectId(), d);
        return d;
    }

    private static Definition plain(String id, String name) {
        return object(
                id,
                name,
                List.of(field(id + "1", "name", "名称", "TEXT")),
                Map.of(),
                List.of(),
                List.of());
    }

    private static FieldOptions lookup(String targetObjectId) {
        return FieldOptions.defaults()
                .withCalculation(
                        new CalculationOptions(
                                "LOOKUP",
                                "LIVE",
                                targetObjectId,
                                null,
                                "name",
                                "SINGLE",
                                "AND",
                                List.of(),
                                false,
                                List.of(),
                                null));
    }

    private static Map<String, Definition> explicit(Definition... definitions) {
        Map<String, Definition> result = new LinkedHashMap<>();
        for (Definition d : definitions) result.put(d.objectId(), d);
        return result;
    }

    private Map<String, Implied> compute(Definition... definitions) {
        return ImpliedObjects.compute(explicit(definitions), this::latest);
    }

    private static List<String> kinds(Implied implied) {
        return implied.via().stream().map(Via::kind).toList();
    }

    @Test
    void relationTargetsIncludingDetailAndManyToManyAreImplied() {
        publish(plain("200", "乙"));
        publish(plain("300", "丙"));
        publish(plain("400", "丁"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "customer", "客户", "INTEGER")),
                        Map.of(),
                        List.of(
                                ref("r1", "2", "200"),
                                relation(
                                        "m1", null, "300", RelationTypeEnum.MANY_TO_MANY.getCode()),
                                new com.richuang.os.nocode.api.DataCenter.Relation(
                                        "r2",
                                        "r2",
                                        "明细上的关系",
                                        RelationTypeEnum.REFERENCE.getCode(),
                                        "400",
                                        "11",
                                        null,
                                        false,
                                        "RESTRICT",
                                        "d1")),
                        List.of(
                                detail(
                                        "d1",
                                        "明细",
                                        List.of(field("11", "item", "物料", "INTEGER")),
                                        Map.of())));
        Map<String, Implied> result = compute(a);
        assertThat(result.keySet()).containsExactly("200", "300", "400");
        assertThat(result.get("200").via()).containsExactly(new Via("100", "RELATION", "关系r1"));
        assertThat(result.get("300").via()).containsExactly(new Via("100", "RELATION", "关系m1"));
        assertThat(result.get("400").via()).containsExactly(new Via("100", "RELATION", "明细上的关系"));
        assertThat(result.get("200").latest()).isSameAs(published.get("200"));
        assertThat(result.get("200").closed()).isFalse();
    }

    @Test
    void linkageSourceReferenceFilterPickSourceAndCalculationSourceAreImplied() {
        publish(plain("200", "乙"));
        publish(plain("300", "丙"));
        publish(plain("400", "丁"));
        publish(plain("500", "戊"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "customer", "客户", "INTEGER"),
                                field("3", "linked", "联动值", "TEXT"),
                                field("4", "picked", "挑取", "SELECT"),
                                field("5", "looked", "查找", "FORMULA")),
                        options(
                                Map.entry("2", ruled(reference(null, constant("2001", "EQ", "x")))),
                                Map.entry("3", ruled(linkage("300", "3001", "FIRST"))),
                                Map.entry("4", pick("400", "4001")),
                                Map.entry("5", lookup("500"))),
                        List.of(ref("r1", "2", "200")),
                        List.of());
        Map<String, Implied> result = compute(a);
        assertThat(result.keySet()).containsExactlyInAnyOrder("200", "300", "400", "500");
        assertThat(kinds(result.get("200"))).containsExactly("RELATION", "REFERENCE_FILTER");
        assertThat(result.get("200").via().get(1).name()).isEqualTo("客户");
        assertThat(result.get("300").via()).containsExactly(new Via("100", "LINKAGE", "联动值"));
        assertThat(result.get("400").via()).containsExactly(new Via("100", "PICK", "挑取"));
        assertThat(result.get("500").via()).containsExactly(new Via("100", "CALCULATION", "查找"));
    }

    @Test
    void detailFieldRulesCountToo() {
        publish(plain("300", "丙"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(field("1", "name", "名称", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of(
                                detail(
                                        "d1",
                                        "明细",
                                        List.of(field("11", "price", "单价", "TEXT")),
                                        options(
                                                Map.entry(
                                                        "11",
                                                        ruled(linkage("300", "3001", "FIRST")))))));
        assertThat(compute(a).keySet()).containsExactly("300");
    }

    @Test
    void explicitObjectsAndTheObjectItselfAreNotImplied() {
        Definition b = publish(plain("200", "乙"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "customer", "客户", "INTEGER"),
                                field("3", "parent", "上级", "INTEGER"),
                                field("4", "self", "本表统计", "FORMULA")),
                        options(Map.entry("4", lookup(null))),
                        List.of(ref("r1", "2", "200"), ref("r2", "3", "100")),
                        List.of());
        assertThat(compute(a, b)).isEmpty();
        assertThat(compute(a).keySet()).containsExactly("200");
    }

    /** 后续跳只沿取数链：隐式对象的计算来源、联动来源、挑取值来源算；它的关系目标不算。 */
    @Test
    void laterHopsFollowDataSourcesButNeverRelations() {
        // 乙（隐式）：计算从 丁丁 取数、联动从 庚 取数、挑取值来自 辛，另有一条关系指向 戊戊。
        publish(
                object(
                        "200",
                        "乙",
                        List.of(
                                field("21", "name", "名称", "TEXT"),
                                field("22", "calc", "计算", "FORMULA"),
                                field("23", "owner", "负责人", "INTEGER"),
                                field("24", "linked", "联动", "TEXT"),
                                field("25", "picked", "挑取", "SELECT")),
                        options(
                                Map.entry("22", lookup("400")),
                                Map.entry("24", ruled(linkage("600", "6001", "FIRST"))),
                                Map.entry("25", pick("700", "7001"))),
                        List.of(ref("r9", "23", "500")),
                        List.of()));
        publish(plain("400", "丁丁"));
        publish(plain("500", "戊戊"));
        publish(plain("600", "庚"));
        publish(plain("700", "辛"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "ref", "引用乙", "INTEGER")),
                        Map.of(),
                        List.of(ref("r1", "2", "200")),
                        List.of());
        Map<String, Implied> result = compute(a);
        assertThat(result.keySet()).containsExactlyInAnyOrder("200", "400", "600", "700");
        assertThat(result).as("隐式对象的关系目标不传递").doesNotContainKey("500");
        assertThat(result.get("400").via()).containsExactly(new Via("200", "CALCULATION", "计算"));
        assertThat(result.get("600").via()).containsExactly(new Via("200", "LINKAGE", "联动"));
        assertThat(result.get("700").via()).containsExactly(new Via("200", "PICK", "挑取"));
    }

    /** 隐式对象上配了引用筛选的关系字段：筛选的目标对象就是它的关系目标，同样不传递。 */
    @Test
    void referenceFilterOnAnImpliedObjectDoesNotPassItsRelationTarget() {
        publish(
                object(
                        "200",
                        "乙",
                        List.of(
                                field("21", "name", "名称", "TEXT"),
                                field("23", "owner", "负责人", "INTEGER")),
                        options(
                                Map.entry(
                                        "23",
                                        ruled(reference("5001", constant("5002", "EQ", "x"))))),
                        List.of(ref("r9", "23", "500")),
                        List.of()));
        publish(plain("500", "戊戊"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "ref", "引用乙", "INTEGER")),
                        Map.of(),
                        List.of(ref("r1", "2", "200")),
                        List.of());
        assertThat(compute(a).keySet()).containsExactly("200");
    }

    @Test
    void cyclesTerminate() {
        // 甲 → 乙（关系）；乙 的计算来自 丙；丙 的计算又来自 乙 和 甲。
        publish(
                object(
                        "200",
                        "乙",
                        List.of(
                                field("21", "name", "名称", "TEXT"),
                                field("22", "calc", "计算", "FORMULA")),
                        options(Map.entry("22", lookup("300"))),
                        List.of(),
                        List.of()));
        publish(
                object(
                        "300",
                        "丙",
                        List.of(
                                field("31", "name", "名称", "TEXT"),
                                field("32", "back", "回指乙", "FORMULA"),
                                field("33", "root", "回指甲", "FORMULA")),
                        options(Map.entry("32", lookup("200")), Map.entry("33", lookup("100"))),
                        List.of(),
                        List.of()));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "ref", "引用乙", "INTEGER")),
                        Map.of(),
                        List.of(ref("r1", "2", "200")),
                        List.of());
        assertThat(compute(a).keySet()).containsExactly("200", "300");
    }

    @Test
    void chainIsCutAtTheDepthLimit() {
        // 甲 → 链1 → 链2 → … → 链20（每一环的计算来源是下一环）。
        for (int i = 1; i <= 20; i++) {
            String id = Integer.toString(1000 + i);
            String next = Integer.toString(1000 + i + 1);
            publish(
                    object(
                            id,
                            "链" + i,
                            List.of(
                                    field(id + "1", "name", "名称", "TEXT"),
                                    field(id + "2", "calc", "计算", "FORMULA")),
                            i == 20 ? Map.of() : options(Map.entry(id + "2", lookup(next))),
                            List.of(),
                            List.of()));
        }
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "calc", "计算", "FORMULA")),
                        options(Map.entry("2", lookup("1001"))),
                        List.of(),
                        List.of());
        Map<String, Implied> result = compute(a);
        assertThat(result).hasSize(16);
        assertThat(result).containsKey("1016").doesNotContainKey("1017");
    }

    @Test
    void unpublishedOrDisabledTargetsStayOut() {
        publish(plain("300", "丙"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "gone", "指向未发布对象", "INTEGER"),
                                field("3", "kept", "指向丙", "INTEGER")),
                        Map.of(),
                        List.of(ref("r1", "2", "999"), ref("r2", "3", "300")),
                        List.of());
        assertThat(compute(a).keySet()).containsExactly("300");
    }

    @Test
    void inactiveFieldsDoNotContribute() {
        publish(plain("300", "丙"));
        Definition a =
                object(
                        "100",
                        "甲",
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("3", "linked", "已停用的联动", "TEXT")),
                        options(
                                Map.entry(
                                        "3",
                                        FieldOptions.copyOf(ruled(linkage("300", "3001", "FIRST")))
                                                .state("INACTIVE")
                                                .build())),
                        List.of(),
                        List.of());
        assertThat(compute(a)).isEmpty();
    }
}
