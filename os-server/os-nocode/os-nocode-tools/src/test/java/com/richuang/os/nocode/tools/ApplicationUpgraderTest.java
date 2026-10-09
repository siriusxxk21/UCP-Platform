package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.DataCenter.Definition;
import com.richuang.os.nocode.application.service.application.ApplicationUpgrader;
import com.richuang.os.nocode.enums.RelationTypeEnum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用定义的升级函数：纯函数，不连库。只换目标对象的引用、只转任务入口范围里属于该对象的清单，其余逐字节不动。
 *
 * <p>旧版本：字段 1 名称、2 金额、3 公式；明细 d1；多对多关系 m1。新版本多了字段 4 备注与明细 d2。
 */
class ApplicationUpgraderTest {
    private final ObjectMapper json =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static Definition version(boolean upgraded) {
        var fields =
                new ArrayList<>(
                        List.of(
                                field("1", "name", "名称", "TEXT"),
                                field("2", "amount", "金额", "INTEGER"),
                                field("3", "total", "合计", "FORMULA")));
        if (upgraded) fields.add(field("4", "memo", "备注", "TEXT"));
        var details =
                new ArrayList<>(
                        List.of(
                                detail(
                                        "d1",
                                        "明细一",
                                        List.of(field("11", "a", "行", "TEXT")),
                                        Map.of())));
        if (upgraded)
            details.add(detail("d2", "明细二", List.of(field("21", "b", "行", "TEXT")), Map.of()));
        return object(
                "100",
                "甲",
                fields,
                Map.of(),
                List.of(relation("m1", null, "200", RelationTypeEnum.MANY_TO_MANY.getCode())),
                details);
    }

    private static Map<String, Object> limit(
            String objectId,
            List<String> readFields,
            List<String> writeFields,
            List<String> readDetails,
            List<String> writeDetails,
            List<String> readRelations,
            List<String> writeRelations) {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("objectId", objectId);
        grant.put("actions", List.of("READ", "CREATE", "UPDATE"));
        grant.put("scope", "ALL");
        grant.put("readFields", readFields);
        grant.put("writeFields", writeFields);
        grant.put("readDetails", readDetails);
        grant.put("writeDetails", writeDetails);
        grant.put("readRelations", readRelations);
        grant.put("writeRelations", writeRelations);
        grant.put("actionScopes", Map.of());
        grant.put("computeFields", List.of());
        return grant;
    }

    private static ApplicationCenter.Resource entry(String id, List<Map<String, Object>> limits) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objectId", "100");
        config.put("viewId", "view");
        config.put("formId", "form");
        config.put("mode", "LIST");
        config.put("category", "办理");
        config.put("sortOrder", 1);
        config.put("limits", limits);
        return new ApplicationCenter.Resource(id, "TASK_ENTRY", "code_" + id, "入口" + id, config);
    }

    private static ApplicationCenter.Resource view() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objectId", "100");
        config.put("fieldIds", List.of("1", "2"));
        config.put("pageSize", 10);
        return new ApplicationCenter.Resource("view", "VIEW", "code_view", "列表", config);
    }

    private static ApplicationCenter.Definition definition(ApplicationCenter.Resource... entries) {
        List<ApplicationCenter.Resource> resources = new ArrayList<>();
        resources.add(view());
        resources.addAll(List.of(entries));
        return new ApplicationCenter.Definition(
                List.of(
                        new ApplicationCenter.ObjectReference("50", 3, "other-checksum"),
                        new ApplicationCenter.ObjectReference("100", 1, "old-checksum"),
                        new ApplicationCenter.ObjectReference("200", 7, "target-checksum")),
                resources);
    }

    private ApplicationCenter.Definition upgrade(ApplicationCenter.Definition definition) {
        return ApplicationUpgrader.apply(
                definition, "100", 2, "new-checksum", version(false), version(true));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLimit(ApplicationCenter.Resource resource) {
        return ((List<Map<String, Object>>) resource.config().get("limits")).getFirst();
    }

    @Test
    void onlyTheTargetReferenceMovesAndEverythingElseIsByteIdentical() {
        ApplicationCenter.Definition before =
                definition(
                        entry(
                                "keep",
                                List.of(
                                        limit(
                                                "100",
                                                List.of("*"),
                                                List.of("*"),
                                                List.of("*"),
                                                List.of("*"),
                                                List.of("*"),
                                                List.of("*")))));
        String snapshot = write(before);
        ApplicationCenter.Definition after = upgrade(before);
        assertThat(write(before)).as("输入不被修改").isEqualTo(snapshot);
        assertThat(after.objects())
                .containsExactly(
                        new ApplicationCenter.ObjectReference("50", 3, "other-checksum"),
                        new ApplicationCenter.ObjectReference("100", 2, "new-checksum"),
                        new ApplicationCenter.ObjectReference("200", 7, "target-checksum"));
        assertThat(write(after.resources())).as("资源逐字节不变").isEqualTo(write(before.resources()));
        assertThat(after.resources().get(0)).isSameAs(before.resources().get(0));
        assertThat(after.resources().get(1))
                .as("「全部」不动，入口资源原样")
                .isSameAs(before.resources().get(1));
    }

    @Test
    void listsEqualToTheOldUniverseBecomeAll() {
        ApplicationCenter.Definition after =
                upgrade(
                        definition(
                                entry(
                                        "exact",
                                        List.of(
                                                limit(
                                                        "100",
                                                        List.of("1", "2", "3"),
                                                        List.of("1", "2"),
                                                        List.of("d1"),
                                                        List.of("d1"),
                                                        List.of("m1"),
                                                        List.of("m1"))))));
        Map<String, Object> limit = firstLimit(after.resources().get(1));
        assertThat(limit.get("readFields")).isEqualTo(List.of("*"));
        assertThat(limit.get("writeFields"))
                .as("可写清单的全集只算写得了的字段：公式字段不在其中，1、2 就是旧版本的全部")
                .isEqualTo(List.of("*"));
        assertThat(limit.get("readDetails")).isEqualTo(List.of("*"));
        assertThat(limit.get("writeDetails")).isEqualTo(List.of("*"));
        assertThat(limit.get("readRelations")).isEqualTo(List.of("*"));
        assertThat(limit.get("writeRelations")).isEqualTo(List.of("*"));
        assertThat(limit.get("actions")).isEqualTo(List.of("READ", "CREATE", "UPDATE"));
        assertThat(limit.get("scope")).isEqualTo("ALL");
    }

    @Test
    void partialListsAreKeptAndDeadIdsRemoved() {
        ApplicationCenter.Definition after =
                upgrade(
                        definition(
                                entry(
                                        "partial",
                                        List.of(
                                                limit(
                                                        "100",
                                                        List.of("2", "1", "999"),
                                                        List.of("1", "888"),
                                                        List.of("gone"),
                                                        List.of(),
                                                        List.of(),
                                                        List.of())))));
        Map<String, Object> limit = firstLimit(after.resources().get(1));
        assertThat(limit.get("readFields"))
                .as("故意没选公式字段 3：保留清单，新字段 4 不自动加入")
                .isEqualTo(List.of("2", "1"));
        assertThat(limit.get("writeFields")).isEqualTo(List.of("1"));
        assertThat(limit.get("readDetails")).as("只剩死 ID：清成空，空永远不转").isEqualTo(List.of());
        assertThat(limit.get("writeDetails")).isEqualTo(List.of());
        assertThat(limit.get("readRelations")).isEqualTo(List.of());
        assertThat(limit.get("writeRelations")).isEqualTo(List.of());
    }

    /** 引用资料对象：只读，三个写清单必须保持为空（入口规则不允许跨对象写入）。 */
    @Test
    void readOnlyReferenceLimitsKeepEmptyWriteLists() {
        ApplicationCenter.Definition after =
                upgrade(
                        definition(
                                entry(
                                        "readonly",
                                        List.of(
                                                limit(
                                                        "100",
                                                        List.of("1", "2", "3"),
                                                        List.of(),
                                                        List.of("d1"),
                                                        List.of(),
                                                        List.of("m1"),
                                                        List.of())))));
        Map<String, Object> limit = firstLimit(after.resources().get(1));
        assertThat(limit.get("readFields")).isEqualTo(List.of("*"));
        assertThat(limit.get("writeFields")).isEqualTo(List.of());
        assertThat(limit.get("writeDetails")).isEqualTo(List.of());
        assertThat(limit.get("writeRelations")).isEqualTo(List.of());
    }

    /** 可写清单少选了一个写得了的字段：保留清单，不放大写权限。 */
    @Test
    void writeListMissingAWritableFieldStaysAList() {
        ApplicationCenter.Definition after =
                upgrade(
                        definition(
                                entry(
                                        "narrow",
                                        List.of(
                                                limit(
                                                        "100",
                                                        List.of("*"),
                                                        List.of("1"),
                                                        List.of("*"),
                                                        List.of(),
                                                        List.of("*"),
                                                        List.of())))));
        Map<String, Object> limit = firstLimit(after.resources().get(1));
        assertThat(limit.get("readFields")).isEqualTo(List.of("*"));
        assertThat(limit.get("writeFields")).isEqualTo(List.of("1"));
    }

    @Test
    void limitsOfOtherObjectsAndUnknownKeysAreLeftAlone() {
        Map<String, Object> other =
                limit("200", List.of("9"), List.of(), List.of(), List.of(), List.of(), List.of());
        Map<String, Object> mine =
                limit(
                        "100",
                        List.of("1", "2", "3"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        mine.remove("readRelations");
        mine.remove("writeRelations");
        mine.put("futureKey", Map.of("kept", true));
        ApplicationCenter.Definition before = definition(entry("mixed", List.of(other, mine)));
        ApplicationCenter.Definition after = upgrade(before);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> limits =
                (List<Map<String, Object>>) after.resources().get(1).config().get("limits");
        assertThat(limits.get(0)).as("别的对象的范围不动").isSameAs(other);
        assertThat(limits.get(1).get("readFields")).isEqualTo(List.of("*"));
        assertThat(limits.get(1))
                .as("原来没有的键不凭空加上")
                .doesNotContainKeys("readRelations", "writeRelations");
        assertThat(limits.get(1).get("futureKey")).isEqualTo(Map.of("kept", true));
        assertThat(after.resources().get(1).config().get("category")).isEqualTo("办理");
        assertThat(after.resources().get(1).name()).isEqualTo("入口mixed");
    }

    @Test
    void definitionWithoutEntriesOnlyGetsTheNewReference() {
        ApplicationCenter.Definition before = definition();
        ApplicationCenter.Definition after = upgrade(before);
        assertThat(after.resources()).hasSize(1);
        assertThat(after.resources().getFirst()).isSameAs(before.resources().getFirst());
        assertThat(after.objects().get(1).versionNo()).isEqualTo(2);
    }
}
