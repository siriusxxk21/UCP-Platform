package com.richuang.os.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

/** 设计引擎区块配置校验（laneEG）：只能在有当前对象的记录页、地址只收站内路径、写回字段必须指向当前对象/材料库。 */
class ApplicationEngineValidatorTest {
    ApplicationResourceContext context;
    Map<String, DataCenter.Definition> definitions = new HashMap<>();

    static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, id, type, null, null, null, false, false, 1);
    }

    static DataCenter.Relation reference(String fieldId, String target) {
        return new DataCenter.Relation(
                fieldId + "-rel",
                fieldId,
                fieldId,
                "REFERENCE",
                target,
                fieldId,
                null,
                false,
                "RESTRICT",
                null);
    }

    DataCenter.Definition object(
            String id, List<DataCenter.Relation> relations, FieldDefinition... fields) {
        DataCenter.Definition definition = mock(DataCenter.Definition.class);
        when(definition.objectId()).thenReturn(id);
        when(definition.relations()).thenReturn(relations);
        Map<String, FieldDefinition> map = new LinkedHashMap<>();
        for (FieldDefinition f : fields) map.put(f.id(), f);
        doReturn(definition).when(context).object(definitions, id);
        doReturn(map).when(context).fields(definition);
        definitions.put(id, definition);
        return definition;
    }

    @BeforeEach
    void setUp() {
        context = mock(ApplicationResourceContext.class);
        doThrow(new IllegalArgumentException("资源绑定对象不属于应用")).when(context).object(any(), any());
        object("site", List.of(), field("site-name", "TEXT"));
        object("lib", List.of(), field("lib-name", "TEXT"), field("lib-price", "MONEY"));
        object(
                "bom",
                List.of(
                        reference("bom-site", "site"),
                        reference("bom-material", "lib"),
                        reference("bom-wrong", "lib")),
                field("bom-site", "INTEGER"),
                field("bom-material", "INTEGER"),
                field("bom-wrong", "INTEGER"),
                field("bom-key", "TEXT"),
                field("bom-qty", "DECIMAL"),
                field("bom-name", "TEXT"));
    }

    static ApplicationUi.Node node(ApplicationUi.EngineBlock engine) {
        return new ApplicationUi.Node(
                "n", "ENGINE", null, null, null, null, List.of(), null, null, null, null, null,
                null, null, engine);
    }

    static ApplicationUi.EngineBlock engine(String url, ApplicationUi.EngineWriteback bom) {
        return new ApplicationUi.EngineBlock(
                url,
                new ApplicationUi.EngineLibrary(
                        "lib", Map.of("name", "lib-name", "unitPrice", "lib-price")),
                null,
                bom);
    }

    static ApplicationUi.EngineWriteback bom(
            String recordField, String keyField, Map<String, String> fields) {
        return new ApplicationUi.EngineWriteback("bom", recordField, keyField, fields);
    }

    ApplicationUi.Page page(String contextObjectId, ApplicationUi.Node node) {
        return new ApplicationUi.Page(List.of(node), contextObjectId, 2);
    }

    @Test
    void acceptsAFullConfigurationOnARecordPage() {
        ApplicationUi.Node node =
                node(
                        engine(
                                "/engine01/",
                                bom(
                                        "bom-site",
                                        "bom-key",
                                        Map.of(
                                                "quantity",
                                                "bom-qty",
                                                "material",
                                                "bom-material",
                                                "name",
                                                "bom-name"))));
        ApplicationEngineValidator.node(node);
        ApplicationEngineValidator.context(context, page("site", node), node, definitions);
        ApplicationUi.Node bare = node(null);
        ApplicationEngineValidator.node(bare);
        ApplicationEngineValidator.context(context, page("site", bare), bare, definitions);
    }

    @Test
    void refusesPagesWithoutCurrentRecordObject() {
        ApplicationUi.Node node = node(null);
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context, page(null, node), node, definitions))
                .hasMessageContaining("当前记录对象");
    }

    @Test
    void refusesExternalOrTraversingEngineAddresses() {
        for (String url :
                List.of(
                        "https://evil.example/",
                        "//evil.example/",
                        "/engine01/../api/",
                        "engine01",
                        "/a?b=1"))
            assertThatThrownBy(() -> ApplicationEngineValidator.node(node(engine(url, null))))
                    .as(url)
                    .hasMessageContaining("站内路径");
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.node(
                                        node(
                                                new ApplicationUi.EngineBlock(
                                                        null,
                                                        new ApplicationUi.EngineLibrary(
                                                                "lib", Map.of("script", "x")),
                                                        null,
                                                        null))))
                .hasMessageContaining("不支持的映射项");
    }

    @Test
    void writeBackMustPointAtTheCurrentRecordAndTheMaterialLibrary() {
        ApplicationUi.Node wrongRecordField =
                node(engine(null, bom("bom-material", "bom-key", Map.of("quantity", "bom-qty"))));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context,
                                        page("site", wrongRecordField),
                                        wrongRecordField,
                                        definitions))
                .hasMessageContaining("指向页面当前对象");
        ApplicationUi.Node numericKey =
                node(engine(null, bom("bom-site", "bom-qty", Map.of("quantity", "bom-qty"))));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context, page("site", numericKey), numericKey, definitions))
                .hasMessageContaining("文本字段");
        ApplicationUi.Node textQuantity =
                node(engine(null, bom("bom-site", "bom-key", Map.of("quantity", "bom-name"))));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context,
                                        page("site", textQuantity),
                                        textQuantity,
                                        definitions))
                .hasMessageContaining("数值字段");
        ApplicationUi.Node noQuantity =
                node(engine(null, bom("bom-site", "bom-key", Map.of("name", "bom-name"))));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context, page("site", noQuantity), noQuantity, definitions))
                .hasMessageContaining("数量");
        ApplicationUi.Node siteAsMaterial =
                node(
                        engine(
                                null,
                                bom(
                                        "bom-site",
                                        "bom-key",
                                        Map.of("quantity", "bom-qty", "material", "bom-site"))));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context,
                                        page("site", siteAsMaterial),
                                        siteAsMaterial,
                                        definitions))
                .hasMessageContaining("关联字段与行键字段");
        ApplicationUi.Node foreign =
                node(
                        new ApplicationUi.EngineBlock(
                                null,
                                new ApplicationUi.EngineLibrary("elsewhere", Map.of("name", "x")),
                                null,
                                null));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context, page("site", foreign), foreign, definitions))
                .hasMessageContaining("不属于应用");
        ApplicationUi.Node unnamed =
                node(
                        new ApplicationUi.EngineBlock(
                                null,
                                new ApplicationUi.EngineLibrary(
                                        "lib", Map.of("unitPrice", "lib-price")),
                                null,
                                null));
        assertThatThrownBy(
                        () ->
                                ApplicationEngineValidator.context(
                                        context, page("site", unnamed), unnamed, definitions))
                .hasMessageContaining("名称");
    }
}
