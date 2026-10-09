package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.dict.dto.DictDataRespDTO;
import com.lingan.ucp.module.system.api.dict.DictDataApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryService;
import com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport;

import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * 挑取值（selection.kind=OBJECT_FIELD_OPTIONS）：候选来自来源字段实际生效的选项集，一行业务记录都不读。用例改写自老
 * data-linkage.service.spec.ts:523-580，覆盖 B25–B27、B59 与实施裁定「来源字段可用公共字典」。
 */
class ObjectFieldOptionsTest {
    private FieldRuleFixture f;
    private DataCenter.Definition source;
    private DataCenter.Definition target;
    private String app;

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
        source =
                f.object(
                        "source",
                        List.of(
                                field("status", "状态", "SELECT"),
                                field("empty", "空选项", "SELECT"),
                                field("dict", "币种", "SELECT")),
                        Map.of(
                                "status",
                                options(
                                        List.of(
                                                new DataCenter.Option("A", "甲", false),
                                                new DataCenter.Option("B", "乙", false))),
                                "empty",
                                placeholder(),
                                "dict",
                                placeholder()),
                        List.of(),
                        List.of());
        // 选项表库层没有唯一约束：重复编码与停用项直接写进快照，验证收敛与停用标记。
        source =
                patch(
                        source,
                        id(source, "status"),
                        o ->
                                withOptions(
                                        o,
                                        List.of(
                                                new DataCenter.Option("A", "甲", false),
                                                new DataCenter.Option("B", "乙", false),
                                                new DataCenter.Option("A", "重复甲", false),
                                                new DataCenter.Option("C", "丙", true))));
        source =
                patch(
                        source,
                        id(source, "dict"),
                        o ->
                                withOptions(o, List.of())
                                        .withSelection(
                                                new SelectionFields.Source(
                                                        "SYSTEM_DICTIONARY",
                                                        null,
                                                        "rule_currency",
                                                        List.of(),
                                                        false,
                                                        List.of(),
                                                        "NONE")));
        // B27：来源字段用本夹具自建的公共字典，发布前有字典项，应用发布后字典项被全部删除。
        source =
                patch(
                        source,
                        id(source, "empty"),
                        o ->
                                withOptions(o, List.of())
                                        .withSelection(
                                                new SelectionFields.Source(
                                                        "SYSTEM_DICTIONARY",
                                                        null,
                                                        "rule_emptied",
                                                        List.of(),
                                                        false,
                                                        List.of(),
                                                        "NONE")));
        target =
                f.object(
                        "target",
                        List.of(
                                field("pick", "挑取值", "SELECT"),
                                field("pick_empty", "挑空", "SELECT"),
                                field("pick_dict", "挑字典", "SELECT")),
                        Map.of(
                                "pick",
                                placeholder(),
                                "pick_empty",
                                placeholder(),
                                "pick_dict",
                                placeholder()),
                        List.of(),
                        List.of(
                                f.detail(
                                        "lines",
                                        List.of(field("line_pick", "明细挑取值", "SELECT")),
                                        Map.of("line_pick", placeholder()))));
        target = pick(target, id(target, "pick"), "status");
        target = pick(target, id(target, "pick_empty"), "empty");
        target = pick(target, id(target, "pick_dict"), "dict");
        target = pick(target, detailField(target, "lines", "line_pick"), "status");
        var dictionary = servicesContext.getBean(DictDataApi.class);
        var usd = new DictDataRespDTO();
        usd.setDictType("rule_currency");
        usd.setValue("USD");
        usd.setLabel("美元");
        usd.setStatus(0);
        var jpy = new DictDataRespDTO();
        jpy.setDictType("rule_currency");
        jpy.setValue("JPY");
        jpy.setLabel("日元");
        jpy.setStatus(1);
        Mockito.when(dictionary.getDictDataList("rule_currency")).thenReturn(List.of(usd, jpy));
        var only = new DictDataRespDTO();
        only.setDictType("rule_emptied");
        only.setValue("ONLY");
        only.setLabel("唯一项");
        only.setStatus(0);
        Mockito.when(dictionary.getDictDataList("rule_emptied")).thenReturn(List.of(only));
        // 应用发布按固定版本校验挑取值来源必须有生效选项（路 A）；之后字典项被全部删除。
        app = f.app(source, target);
        Mockito.when(dictionary.getDictDataList("rule_emptied")).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private DataCenter.Definition pick(DataCenter.Definition d, String fieldId, String sourceCode) {
        return patch(
                d,
                fieldId,
                o ->
                        withOptions(o, List.of())
                                .withSelection(
                                        new SelectionFields.Source(
                                                "OBJECT_FIELD_OPTIONS",
                                                null,
                                                null,
                                                List.of(),
                                                false,
                                                List.of(),
                                                "NONE",
                                                null,
                                                source.objectId(),
                                                id(source, sourceCode))));
    }

    /** 把候选链路上所有持有 RecordMapper 的组件换成计数替身，调用结束后还原。 */
    private <T> T countingRows(java.util.function.Function<RecordMapper, T> action) {
        var holders =
                List.<Object>of(
                        servicesContext.getBean(RecordQueryService.class),
                        servicesContext.getBean(RecordSelectionSupport.class),
                        servicesContext.getBean(
                                com.lingan.ucp.nocode.runtime.service.record.RecordPersistence
                                        .class));
        var original = (RecordMapper) ReflectionTestUtils.getField(holders.getFirst(), "records");
        var spy = Mockito.mock(RecordMapper.class, AdditionalAnswers.delegatesTo(original));
        holders.forEach(h -> ReflectionTestUtils.setField(h, "records", spy));
        try {
            return action.apply(spy);
        } finally {
            holders.forEach(h -> ReflectionTestUtils.setField(h, "records", original));
        }
    }

    /** B25：挑取值一行记录都不读（RecordMapper.rows 调用次数为 0）。 */
    @Test
    void readsNoRows() {
        f.save(app, target, values(id(target, "name"), "已有记录", id(target, "pick"), "A"));
        var result =
                countingRows(
                        spy -> {
                            var r =
                                    f.selection(
                                            app,
                                            target,
                                            id(target, "pick"),
                                            Map.of(),
                                            List.of("A"));
                            Mockito.verify(spy, Mockito.never()).rows(Mockito.any());
                            Mockito.verify(spy, Mockito.never()).count(Mockito.any());
                            return r;
                        });
        assertThat(result.options())
                .extracting(SelectionFields.Option::value)
                .containsExactly("A", "B");
        assertThat(result.selected())
                .singleElement()
                .satisfies(o -> assertThat(o.label()).isEqualTo("甲"));
        assertThat(result.ruleState()).isNull();
    }

    /** B26：label 取定义里的 label；重复编码收敛成一项、label 取第一条；停用项标 disabled 且不可新选。 */
    @Test
    void dedupeFirstLabel() {
        var catalog =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog.class);
        var picked =
                catalog.inApplication(
                        app,
                        null,
                        () ->
                                catalog.objectFieldOptions(
                                        target.fieldOptions().get(id(target, "pick")).selection()));
        assertThat(picked.state()).isEqualTo("APPLIED");
        assertThat(picked.options())
                .extracting(
                        SelectionFields.Option::value,
                        SelectionFields.Option::label,
                        SelectionFields.Option::disabled)
                .containsExactly(
                        tuple("A", "甲", false), tuple("B", "乙", false), tuple("C", "丙", true));
        var saved = f.save(app, target, values(id(target, "name"), "选乙", id(target, "pick"), "B"));
        assertThat(saved.values()).containsEntry(id(target, "pick"), "B");
        assertThatThrownBy(
                        () ->
                                f.save(
                                        app,
                                        target,
                                        values(id(target, "name"), "选停用", id(target, "pick"), "C")))
                .hasMessageContaining("挑取值");
        assertThatThrownBy(
                        () ->
                                f.save(
                                        app,
                                        target,
                                        values(
                                                id(target, "name"),
                                                "选不存在",
                                                id(target, "pick"),
                                                "Z")))
                .hasMessageContaining("挑取值");
    }

    /** B27：来源字典项在应用发布后被全部删除时返回 SOURCE_FIELD_HAS_NO_OPTIONS，不给空下拉冒充没有候选，并说明怎么改。 */
    @Test
    void noOptionsState() {
        var r = f.selection(app, target, id(target, "pick_empty"), Map.of(), List.of());
        assertThat(r.ruleState()).isEqualTo("SOURCE_FIELD_HAS_NO_OPTIONS");
        assertThat(r.options()).isEmpty();
        assertThat(r.ruleMessage()).contains("空选项").contains("怎么改");
    }

    /** 实施裁定：来源字段用公共字典时，候选 = 该字典的生效项；同样不读业务行。 */
    @Test
    void dictionarySource() {
        var r =
                countingRows(
                        spy -> {
                            var result =
                                    f.selection(
                                            app,
                                            target,
                                            id(target, "pick_dict"),
                                            Map.of(),
                                            List.of());
                            Mockito.verify(spy, Mockito.never()).rows(Mockito.any());
                            return result;
                        });
        assertThat(r.ruleState()).isNull();
        assertThat(r.options())
                .extracting(SelectionFields.Option::value, SelectionFields.Option::label)
                .containsExactly(tuple("USD", "美元"));
        var catalog =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog.class);
        var all =
                catalog.inApplication(
                        app,
                        null,
                        () ->
                                catalog.objectFieldOptions(
                                        target.fieldOptions()
                                                .get(id(target, "pick_dict"))
                                                .selection()));
        assertThat(all.options())
                .extracting(SelectionFields.Option::value, SelectionFields.Option::disabled)
                .containsExactly(tuple("USD", false), tuple("JPY", true));
    }

    /**
     * 实施裁定：来源对象按应用固定版本读取；来源发布新版本而应用未同步时，候选仍按固定版本。 2026-10
     * 起（关联对象隐式只读）：来源对象没加入应用不再是「来源表缺失」，系统按它的最新发布版自动放行只读。
     */
    @Test
    void pinnedVersionNotLatest() {
        // 新版本只做兼容改动（改标签、加选项）：删掉或停用旧版本里可选的选项、换选项来源都属于平台契约里的不兼容变更，
        // 对象发布会要求暂停仍固定旧版本的应用，那就谈不上「固定版本继续运行」。另两个字段的字典来源是夹具直接写进
        // 已发布快照的，设计稿里没有，这里原样带上，免得新版本把它们改回局部选项。
        f.republish(
                source,
                Map.of(
                        id(source, "status"),
                        withOptions(
                                source.fieldOptions().get(id(source, "status")),
                                List.of(
                                        new DataCenter.Option("A", "新甲", false),
                                        new DataCenter.Option("B", "乙", false),
                                        new DataCenter.Option("D", "丁", false))),
                        id(source, "dict"),
                        source.fieldOptions().get(id(source, "dict")),
                        id(source, "empty"),
                        source.fieldOptions().get(id(source, "empty"))));
        assertThat(published(source.objectId()).fieldOptions().get(id(source, "status")).options())
                .extracting(DataCenter.Option::code, DataCenter.Option::label)
                .containsExactly(tuple("A", "新甲"), tuple("B", "乙"), tuple("D", "丁"));
        assertThat(f.applications.get(app).application().status())
                .as("兼容改动不暂停应用")
                .isEqualTo("ACTIVE");
        var r = f.selection(app, target, id(target, "pick"), Map.of(), List.of());
        assertThat(r.options())
                .extracting(SelectionFields.Option::value, SelectionFields.Option::label)
                .containsExactly(tuple("A", "甲"), tuple("B", "乙"));
        // 来源对象没加入应用：按它的最新发布版读取（不是任何应用固定的旧版本）。发布后改写快照构造。
        var lone =
                f.object(
                        "lone",
                        List.of(field("pick", "挑取值", "SELECT")),
                        Map.of("pick", placeholder()),
                        List.of(),
                        List.of());
        String alone = f.app(lone);
        lone = pick(lone, id(lone, "pick"), "status");
        var implied = f.selection(alone, lone, id(lone, "pick"), Map.of(), List.of());
        assertThat(implied.ruleState()).isNull();
        assertThat(implied.options())
                .extracting(SelectionFields.Option::value, SelectionFields.Option::label)
                .containsExactly(tuple("A", "新甲"), tuple("B", "乙"), tuple("D", "丁"));
        var catalog =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog.class);
        assertThat(
                        catalog.objectFieldOptions(
                                        target.fieldOptions().get(id(target, "pick")).selection())
                                .state())
                .as("没有应用上下文时 fail-closed，不读最新发布版")
                .isEqualTo("SOURCE_TABLE_MISSING");
    }

    /** B59：明细字段的挑取值同样来自来源字段的选项定义，不读业务行。 */
    @Test
    void detailFieldReadsNoRows() {
        String detail = detailId(target, "lines");
        var r =
                countingRows(
                        spy -> {
                            var result =
                                    f.selection(
                                            app,
                                            target,
                                            detail,
                                            detailField(target, "lines", "line_pick"),
                                            Map.of(),
                                            List.of(),
                                            null);
                            Mockito.verify(spy, Mockito.never()).rows(Mockito.any());
                            return result;
                        });
        assertThat(r.options()).extracting(SelectionFields.Option::value).containsExactly("A", "B");
        assertThat(r.ruleState()).isNull();
    }
}
