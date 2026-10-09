package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.lingan.ucp.nocode.runtime.service.folder.RecordFolderNamer;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * 子文件夹命名器（纯单元）：任何情况下都给出一个合法的名字，绝不抛出。
 *
 * <p>对象「合同」的字段：编号（自动编号）、名称（文本，标题）、签订日期（日期）、类型（单选，值 A 名称「施工」）、甲方（单值关联，关联列是整数）、 金额（小数，不能用来命名）。
 */
class RecordFolderNamerTest {
    private static final String RECORD = "1234567890123";
    private static final String PARTY_OBJECT = "77";

    private final ObjectMapper json = new ObjectMapper();
    private final SelectionCatalog selections = mock(SelectionCatalog.class);
    private final RecordQueryAccess records = mock(RecordQueryAccess.class);
    private final DataObjectApi objects = mock(DataObjectApi.class);
    private final RecordFolderNamer namer = new RecordFolderNamer();

    private final DataCenter.Definition contract =
            definition(
                    "66",
                    "合同",
                    "name",
                    List.of(
                            field("code", "编号", "AUTO_NUMBER"),
                            field("name", "名称", "TEXT"),
                            field("signed", "签订日期", "DATE"),
                            field("kind", "类型", "SELECT"),
                            field("party", "甲方", "INTEGER"),
                            field("amount", "金额", "DECIMAL")),
                    List.of(
                            new DataCenter.Relation(
                                    "r1",
                                    "party",
                                    "甲方",
                                    "REFERENCE",
                                    PARTY_OBJECT,
                                    "party",
                                    null,
                                    false,
                                    "RESTRICT")));
    private final DataCenter.Definition party =
            definition(
                    PARTY_OBJECT,
                    "往来单位",
                    "title",
                    List.of(field("title", "名称", "TEXT")),
                    List.of());

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(namer, "json", json);
        ReflectionTestUtils.setField(namer, "selectionCatalog", selections);
        ReflectionTestUtils.setField(namer, "records", records);
        ReflectionTestUtils.setField(namer, "objects", objects);
        when(objects.getPublished(PARTY_OBJECT)).thenReturn(party);
        when(selections.selectedOptions(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new SelectionFields.Option(
                                        "A", "施工", "A", null, "施工", false, false),
                                new SelectionFields.Option(
                                        "B", "采购", "B", null, "采购", false, false)));
        when(records.storedRow(eq(PARTY_OBJECT), eq("9"), anyLong()))
                .thenReturn(new Row("9", "1", Map.of("title", "甲方的标题")));
    }

    private static FieldDefinition field(String id, String name, String type) {
        return new FieldDefinition(id, id, id, name, type, null, null, null, false, false, 0);
    }

    private static DataCenter.Definition definition(
            String id,
            String name,
            String title,
            List<FieldDefinition> fields,
            List<DataCenter.Relation> relations) {
        return new DataCenter.Definition(
                id,
                "code" + id,
                name,
                null,
                "public",
                "biz_" + id,
                "GENERATED",
                false,
                title,
                DataCenter.Settings.defaults(),
                fields,
                Map.of(),
                relations,
                List.of(),
                List.of(),
                null);
    }

    private static Row row(Map<String, Object> values) {
        return new Row(RECORD, "1", values);
    }

    /** 命名模板：段写成 "f:字段" 或 "t:固定文字"。 */
    private RecordFolderSourceDO source(String separator, String... parts) {
        RecordFolderSourceDO source = new RecordFolderSourceDO();
        source.setNameTemplate("");
        if (parts.length == 0) return source;
        List<RecordFolders.NamePart> list = new ArrayList<>();
        for (String part : parts)
            list.add(
                    part.startsWith("f:")
                            ? new RecordFolders.NamePart("FIELD", part.substring(2), null)
                            : new RecordFolders.NamePart("TEXT", null, part.substring(2)));
        try {
            source.setNameTemplate(
                    json.writeValueAsString(new RecordFolders.NameTemplate(separator, list)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return source;
    }

    private String name(RecordFolderSourceDO source, Map<String, Object> values) {
        return namer.name(source, contract, row(values), 0L);
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)
            result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    @Test
    void n1NoTemplateUsesTheRecordTitleOrFallsBack() {
        assertThat(name(source("-"), values("name", "某某工程"))).isEqualTo("某某工程");
        assertThat(name(source("-"), values("code", "HT-001"))).isEqualTo("记录 67890123");
        assertThat(name(source("-"), values("name", "   "))).isEqualTo("记录 67890123");
        // 编号不足 8 位取全部
        assertThat(namer.name(source("-"), contract, new Row("42", "1", Map.of()), 0L))
                .isEqualTo("记录 42");
    }

    @Test
    void n2JoinsFieldsWithTheSeparator() {
        assertThat(name(source("-", "f:code", "f:name"), values("code", "HT-001", "name", "某某工程")))
                .isEqualTo("HT-001-某某工程");
        assertThat(name(source("", "f:code", "f:name"), values("code", "HT-001", "name", "某某工程")))
                .isEqualTo("HT-001某某工程");
        assertThat(
                        name(
                                source("-", "f:code", "t:资料", "f:name"),
                                values("code", " HT-001 ", "name", "某某工程")))
                .isEqualTo("HT-001-资料-某某工程");
    }

    @Test
    void n3EmptyPartsLeaveNoDanglingSeparator() {
        assertThat(name(source("-", "f:code", "f:name"), values("code", "HT-001")))
                .isEqualTo("HT-001");
        assertThat(name(source("-", "f:code", "f:name"), values("code", "", "name", "某某工程")))
                .isEqualTo("某某工程");
    }

    @Test
    void n4FixedTextAloneDoesNotMakeAName() {
        assertThat(name(source("-", "t:合同", "f:code", "f:name"), values()))
                .isEqualTo("记录 67890123");
        assertThat(name(source("-", "t:合同", "f:code", "f:name"), values("code", " ", "name", "")))
                .isEqualTo("记录 67890123");
        assertThat(name(source("-", "t:合同", "f:code", "f:name"), values("code", "HT-001")))
                .isEqualTo("合同-HT-001");
    }

    @Test
    void n5DateSelectAndReferenceAreRenderedForReading() {
        RecordFolderSourceDO source = source(" · ", "f:signed", "f:kind", "f:party");

        assertThat(name(source, values("signed", "2026-03-01", "kind", "A", "party", 9)))
                .isEqualTo("2026-03-01 · 施工 · 甲方的标题");
        // 日期时间取前 10 个字符
        assertThat(name(source, values("signed", "2026-03-01T10:20:30", "kind", "B", "party", "9")))
                .isEqualTo("2026-03-01 · 采购 · 甲方的标题");
        // 关联的记录不存在：那一段为空
        assertThat(name(source, values("signed", "2026-03-01", "kind", "A", "party", 10)))
                .isEqualTo("2026-03-01 · 施工");
        // 关联的记录没有可见标题：那一段为空
        when(records.storedRow(eq(PARTY_OBJECT), eq("11"), anyLong()))
                .thenReturn(new Row("11", "1", Map.of()));
        assertThat(name(source, values("signed", "2026-03-01", "kind", "A", "party", 11)))
                .isEqualTo("2026-03-01 · 施工");
        // 选项里没有这个值：用原值
        assertThat(name(source, values("signed", "2026-03-01", "kind", "Z", "party", 9)))
                .isEqualTo("2026-03-01 · Z · 甲方的标题");
        // 选项目录抛异常：用原值
        when(selections.selectedOptions(any(), any(), any()))
                .thenThrow(new IllegalStateException("目录不可用"));
        assertThat(name(source, values("signed", "2026-03-01", "kind", "A", "party", 9)))
                .isEqualTo("2026-03-01 · A · 甲方的标题");
    }

    @Test
    void n6IllegalCharactersBecomeSpaces() {
        assertThat(name(source("-", "f:name"), values("name", "a/b\\c\nd\re\tf\n")))
                .isEqualTo("a b c d e f");
        assertThat(name(source("-"), values("name", "甲/乙\\丙\n"))).isEqualTo("甲 乙 丙");
    }

    @Test
    void n7LongNamesAreCutToOneHundred() {
        assertThat(name(source("-", "f:name"), values("name", "长".repeat(150))))
                .isEqualTo("长".repeat(100));
        // 截断后去掉尾部空白
        assertThat(name(source("-"), values("name", "长".repeat(98) + "  尾巴")))
                .isEqualTo("长".repeat(98));
        assertThat(name(source("-", "f:name"), values("name", "长".repeat(100))))
                .isEqualTo("长".repeat(100));
    }

    @Test
    void n8DotNamesFallBack() {
        assertThat(name(source("-", "f:name"), values("name", ".."))).isEqualTo("记录 67890123");
        assertThat(name(source("-"), values("name", "."))).isEqualTo("记录 67890123");
        assertThat(name(source("-", "f:name"), values("name", "/"))).isEqualTo("记录 67890123");
    }

    @Test
    void n9NeverThrows() {
        // 模板里的字段已不在发布版本里：那一段当作空
        assertThat(name(source("-", "f:gone", "f:name"), values("gone", "旧值", "name", "某某工程")))
                .isEqualTo("某某工程");
        assertThat(name(source("-", "f:gone"), values("gone", "旧值"))).isEqualTo("记录 67890123");
        // 不能用来命名的类型同样当作空
        assertThat(name(source("-", "f:amount", "f:name"), values("amount", 12.5, "name", "某某工程")))
                .isEqualTo("某某工程");
        // 读关联记录时出错：整个名字落到兜底名，不抛出
        when(records.storedRow(eq(PARTY_OBJECT), eq("13"), anyLong()))
                .thenThrow(new IllegalStateException("数据库瞬时错误"));
        assertThat(name(source("-", "f:code", "f:party"), values("code", "HT-001", "party", 13)))
                .isEqualTo("记录 67890123");
        // 模板文本损坏
        RecordFolderSourceDO broken = new RecordFolderSourceDO();
        broken.setNameTemplate("{不是 JSON");
        assertThat(name(broken, values("name", "某某工程"))).isEqualTo("记录 67890123");
        // 记录编号里不能用作名称的字符同样清洗
        assertThat(namer.name(source("-"), contract, new Row("a/b\\c", "1", Map.of()), 0L))
                .isEqualTo("记录 a b c");
    }
}
