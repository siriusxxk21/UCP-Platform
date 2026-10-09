package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.metadata.service.formula.OrderedCalculationStateService;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceService;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 来源绑定的失败边界与 Spring 装配；不将结构校验成功视为已获数据权限。 */
class ReportDatasetSourceTest {
    private DataObjectApi objects;
    private OrderedCalculationStateService orderedStates;
    private AnnotationConfigApplicationContext context;
    private ReportDatasetSourceService service;

    @BeforeEach
    void setup() {
        objects = mock(DataObjectApi.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataObjectApi.class, () -> objects);
        orderedStates = mock(OrderedCalculationStateService.class);
        // 注册现成替身，不让 Spring 继续装配替身自身的 Mapper；被测服务仍正常注入。
        context.getBeanFactory().registerSingleton("orderedStates", orderedStates);
        context.register(
                ReportDatasetSourceServiceImpl.class,
                com.richuang.os.nocode.report.service.dataset.ReportDatasetUsage.class);
        context.refresh();
        service = context.getBean(ReportDatasetSourceService.class);
        publish("1", List.of(relation("account", "2", "ref", "REFERENCE", null)));
        publish("2", List.of(relation("company", "3", "ref", "REFERENCE", null)));
        publish("3", List.of());
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void resolvesUnorderedTwoHopAliasesAndDerivesActualTypesWithoutChangingMetadata() {
        ReportDatasets.Relation company =
                new ReportDatasets.Relation("c", List.of("a"), "company", ref("3"));
        ReportDatasets.Source input =
                source(
                        List.of(company, account()),
                        List.of(
                                field("total", List.of(), "amount", "MEASURE"),
                                field("company_name", List.of("a", "c"), "name", "DIMENSION")));
        ReportDatasets.ResolvedSource result = service.resolve(input);
        assertThat(result.objects())
                .extracting(ReportDatasets.ObjectReference::objectId)
                .containsExactly("1", "2", "3");
        assertThat(result.fields().get(0).type()).isEqualTo("MONEY");
        assertThat(result.fields().get(1).relationPath()).containsExactly("account", "company");
        assertThat(result.fields().get(1).objectId()).isEqualTo("3");
        assertThat(result.source()).isEqualTo(input);
        Map<String, java.util.Set<String>> required =
                context.getBean(
                                com.richuang.os.nocode.report.service.dataset.ReportDatasetUsage
                                        .class)
                        .fields(result);
        assertThat(required.get("1")).containsExactlyInAnyOrder("amount", "ref");
        assertThat(required.get("2")).containsExactly("ref");
        assertThat(required.get("3")).containsExactly("name");
        verify(objects, never()).getPublished(anyString());
        verify(objects, never()).registerDependency(any(), anyLong());
        verify(objects, never()).removeDependencies(anyString(), anyString(), anyLong());
    }

    @Test
    void freezesInputCollectionsAndReusesTheSameExactObjectVersion() {
        List<ReportDatasets.Relation> relations =
                new ArrayList<>(
                        List.of(
                                account(),
                                new ReportDatasets.Relation(
                                        "other_account", List.of(), "account", ref("2"))));
        List<String> path = new ArrayList<>(List.of("a"));
        ReportDatasets.Source input =
                source(
                        relations,
                        new ArrayList<>(List.of(field("label", path, "name", "DIMENSION"))));
        ReportDatasets.ResolvedSource result = service.resolve(input);
        relations.clear();
        path.clear();
        input.fields().clear();
        assertThat(result.source().relations()).hasSize(2);
        assertThat(result.source().fields().getFirst().path()).containsExactly("a");
        verify(objects, times(2)).getVersion("2", 1);
        assertThatThrownBy(() -> result.fields().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsTamperedChecksumAndDifferentPinnedVersionsOfTheSameObject() {
        ReportDatasets.Source bad =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference("1", 1, "tampered"),
                        List.of(),
                        baseFields());
        rejected(bad, "校验和");
        rejected(
                source(
                        List.of(
                                account(),
                                new ReportDatasets.Relation(
                                        "b",
                                        List.of(),
                                        "account",
                                        new ReportDatasets.ObjectReference("2", 2, "v2"))),
                        baseFields()),
                "混用");
        verify(objects, never()).getVersion("2", 2);
    }

    @Test
    void rejectsUnexpectedIdentityReturnedByTheObjectProvider() {
        when(objects.getVersion("1", 1))
                .thenReturn(
                        new DataObjectApi.PublishedObject(
                                "2", 1, "sum-1", definition("2", List.of())));
        rejected(source(List.of(), baseFields()), "校验和");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "01", "-1", "x", "9223372036854775808"})
    void rejectsInvalidObjectIdsBeforeReadingMetadata(String id) {
        rejected(
                new ReportDatasets.Source(
                        1, new ReportDatasets.ObjectReference(id, 1, "s"), List.of(), baseFields()),
                "ID");
        verify(objects, never()).getVersion(anyString(), any());
    }

    @Test
    void rejectsMissingVersionUnsupportedProtocolAndEmptySources() {
        rejected(
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference("1", 0, "s"),
                        List.of(),
                        baseFields()),
                "版本");
        rejected(new ReportDatasets.Source(2, ref("1"), List.of(), baseFields()), "协议");
        rejected(source(List.of(), List.of()), "字段应为");
        rejected(null, "协议");
        verify(objects, never()).getVersion(anyString(), any());
    }

    @Test
    void rejectsBrokenDuplicateAndCyclicAliasPaths() {
        rejected(source(List.of(account(), account()), baseFields()), "别名重复");
        rejected(
                source(
                        List.of(
                                new ReportDatasets.Relation(
                                        "a", List.of("a"), "account", ref("2"))),
                        baseFields()),
                "循环");
        rejected(
                source(
                        List.of(
                                new ReportDatasets.Relation(
                                        "a", List.of("missing"), "account", ref("2"))),
                        baseFields()),
                "路径不存在");
        rejected(
                source(
                        List.of(account()),
                        List.of(field("label", List.of("missing"), "name", "DIMENSION"))),
                "路径不存在");
    }

    @Test
    void rejectsThreeHopPathsAndInvalidIdentifiersWithoutMetadataLookup() {
        rejected(
                source(
                        List.of(
                                new ReportDatasets.Relation(
                                        "c", List.of("a", "b"), "company", ref("3"))),
                        baseFields()),
                "两层");
        rejected(
                source(List.of(), List.of(field("x", List.of("a", "b", "c"), "name", "DIMENSION"))),
                "两层");
        rejected(
                source(List.of(), List.of(field("x", List.of(), "name;drop", "DIMENSION"))),
                "编码无效");
        verify(objects, never()).getVersion(anyString(), any());
    }

    @Test
    void checksRelationIdentityAndTargetInsteadOfTrustingClientJoins() {
        rejected(
                source(
                        List.of(new ReportDatasets.Relation("a", List.of(), "unknown", ref("2"))),
                        baseFields()),
                "关联不存在");
        rejected(
                source(
                        List.of(new ReportDatasets.Relation("a", List.of(), "account", ref("3"))),
                        baseFields()),
                "目标");
        publish("1", List.of(relation("account", "2", "name", "REFERENCE", null)));
        rejected(source(List.of(account()), baseFields()), "引用字段");
    }

    @Test
    void rejectsMultiValuedAndDetailOwnedRelationships() {
        publish("1", List.of(relation("account", "2", "ref", "MANY_TO_MANY", null)));
        rejected(source(List.of(account()), baseFields()), "单值");
        publish("1", List.of(relation("account", "2", "ref", "REFERENCE", "detail_1")));
        rejected(source(List.of(account()), baseFields()), "主表");
    }

    @Test
    void rejectsNonPrimaryJoinTargets() {
        DataCenter.Relation relation =
                new DataCenter.Relation(
                        "account",
                        "account",
                        "账户",
                        "REFERENCE",
                        "2",
                        "ref",
                        "name",
                        false,
                        "RESTRICT");
        publish("1", List.of(relation));
        rejected(source(List.of(account()), baseFields()), "目标主键");
    }

    @Test
    void rejectsMissingDuplicateNonScalarAndNonRootMeasureFields() {
        rejected(source(List.of(), List.of(field("x", List.of(), "absent", "DIMENSION"))), "不存在");
        rejected(
                source(List.of(), List.of(baseFields().getFirst(), baseFields().getFirst())),
                "编码重复");
        rejected(source(List.of(), List.of(field("x", List.of(), "files", "DIMENSION"))), "暂不支持");
        rejected(source(List.of(), List.of(field("x", List.of(), "name", "MEASURE"))), "根对象的数值");
        rejected(
                source(List.of(account()), List.of(field("x", List.of("a"), "amount", "MEASURE"))),
                "根对象的数值");
    }

    @Test
    void rejectsOversizedCollectionsBeforeResolvingAnyObject() {
        rejected(source(java.util.Collections.nCopies(51, account()), baseFields()), "最多 50");
        rejected(
                source(List.of(), java.util.Collections.nCopies(201, baseFields().getFirst())),
                "200");
        verify(objects, never()).getVersion(anyString(), any());
    }

    private void rejected(ReportDatasets.Source source, String message) {
        assertThatThrownBy(() -> service.resolve(source))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(message);
    }

    @Test
    void rejectsInactiveAndLiveCalculatedFields() {
        fieldOptions(
                new DataCenter.FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "INACTIVE",
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false),
                "TEXT");
        rejected(source(List.of(), baseFields()), "停用");
        fieldOptions(calculationOptions("LIVE"), "FORMULA");
        rejected(source(List.of(), baseFields()), "尚未落库");
        verifyNoInteractions(orderedStates);
    }

    @Test
    void usesStoredCalculationResultTypeAndRequiresReadiness() {
        fieldOptions(calculationOptions("ON_SAVE"), "FORMULA");
        ReportDatasets.Source source =
                source(List.of(), List.of(field("metric", List.of(), "name", "MEASURE")));
        assertThat(service.resolve(source).fields().getFirst().type()).isEqualTo("DECIMAL");
        verify(orderedStates).requireReady(any(), eq(List.of("name")));
        doThrow(com.richuang.os.nocode.api.NocodeErrorCodes.invalid("计算尚未校准"))
                .when(orderedStates)
                .requireReady(any(), eq(List.of("name")));
        rejected(source, "尚未校准");
    }

    private DataCenter.FieldOptions calculationOptions(String updateMode) {
        return new DataCenter.FieldOptions(
                        null, "NORMAL", null, null, null, null, null, "ACTIVE", List.of(), "amount",
                        "DECIMAL", null, null, false, false)
                .withCalculation(
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                updateMode,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                false,
                                List.of(),
                                null));
    }

    private void fieldOptions(DataCenter.FieldOptions options, String type) {
        DataCenter.Definition base = definition("1", List.of());
        DataCenter.Definition modified =
                new DataCenter.Definition(
                        base.objectId(),
                        base.objectCode(),
                        base.objectName(),
                        base.description(),
                        base.schemaName(),
                        base.tableName(),
                        base.source(),
                        base.readOnly(),
                        base.titleFieldId(),
                        base.settings(),
                        List.of(nativeField("name", type)),
                        Map.of("name", options),
                        List.of(),
                        List.of(),
                        List.of());
        when(objects.getVersion("1", 1))
                .thenReturn(new DataObjectApi.PublishedObject("1", 1, "sum-1", modified));
    }

    @Test
    void rejectsIncompatibleCurrentColumnsEvenWhenPinnedChecksumIsValid() {
        DataCenter.Definition before = objects.getVersion("1", 1).definition();
        DataCenter.Definition changed =
                new DataCenter.Definition(
                        before.objectId(),
                        before.objectCode(),
                        before.objectName(),
                        before.description(),
                        before.schemaName(),
                        before.tableName(),
                        before.source(),
                        before.readOnly(),
                        before.titleFieldId(),
                        before.settings(),
                        before.fields().stream()
                                .map(
                                        f ->
                                                f.id().equals("amount")
                                                        ? nativeField("amount", "TEXT")
                                                        : f)
                                .toList(),
                        before.fieldOptions(),
                        before.relations(),
                        before.indexes(),
                        before.details());
        doReturn(new DataObjectApi.PublishedObject("1", 2, "new", changed))
                .when(objects)
                .getVersion("1", null);
        rejected(source(List.of(), List.of(field("sum", List.of(), "amount", "MEASURE"))), "不兼容");
        // 无关字段变化不应阻止名称维度继续解析。
        assertThat(service.resolve(source(List.of(), baseFields())).fields()).hasSize(1);
    }

    @Test
    void rejectsChangedJoinTargetDespiteUnchangedReferenceColumn() {
        publish("1", List.of(relation("account", "3", "ref", "REFERENCE", null)));
        DataObjectApi.PublishedObject changed = objects.getVersion("1", 1);
        publish("1", List.of(relation("account", "2", "ref", "REFERENCE", null)));
        doReturn(new DataObjectApi.PublishedObject("1", 2, "new", changed.definition()))
                .when(objects)
                .getVersion("1", null);
        rejected(
                source(
                        List.of(account()),
                        List.of(field("label", List.of("a"), "name", "DIMENSION"))),
                "关联已不兼容");
    }

    private void publish(String id, List<DataCenter.Relation> relations) {
        doAnswer(call -> objects.getVersion(id, 1)).when(objects).getVersion(id, null);
        when(objects.getVersion(id, 1))
                .thenReturn(
                        new DataObjectApi.PublishedObject(
                                id, 1, "sum-" + id, definition(id, relations)));
    }

    private DataCenter.Definition definition(String id, List<DataCenter.Relation> relations) {
        return new DataCenter.Definition(
                id,
                "object_" + id,
                "对象" + id,
                null,
                "public",
                "biz_test_" + id,
                "GENERATED",
                false,
                "name",
                DataCenter.Settings.defaults(),
                List.of(
                        nativeField("name", "TEXT"), nativeField("amount", "MONEY"),
                        nativeField("ref", "REFERENCE"), nativeField("files", "ATTACHMENT")),
                Map.of(),
                relations,
                List.of(),
                List.of());
    }

    private FieldDefinition nativeField(String id, String type) {
        return new FieldDefinition(id, id, id, id, type, null, null, null, false, false, 0);
    }

    private DataCenter.Relation relation(
            String id, String target, String field, String kind, String detail) {
        return new DataCenter.Relation(
                id, id, id, kind, target, field, null, false, "RESTRICT", detail);
    }

    private ReportDatasets.ObjectReference ref(String id) {
        return new ReportDatasets.ObjectReference(id, 1, "sum-" + id);
    }

    private ReportDatasets.Relation account() {
        return new ReportDatasets.Relation("a", List.of(), "account", ref("2"));
    }

    private ReportDatasets.Field field(String id, List<String> path, String source, String role) {
        return new ReportDatasets.Field(id, path, source, id, role);
    }

    private List<ReportDatasets.Field> baseFields() {
        return List.of(field("name", List.of(), "name", "DIMENSION"));
    }

    private ReportDatasets.Source source(
            List<ReportDatasets.Relation> relations, List<ReportDatasets.Field> fields) {
        return new ReportDatasets.Source(1, ref("1"), relations, fields);
    }
}
