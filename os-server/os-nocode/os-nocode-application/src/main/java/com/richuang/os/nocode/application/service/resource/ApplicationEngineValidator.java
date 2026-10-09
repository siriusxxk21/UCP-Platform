package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.RelationTypeEnum;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 设计引擎区块（ENGINE）的配置校验（laneEG）。
 *
 * <p>区块只能放在有当前记录对象的业务页面；引擎地址只收站内相对路径，保证 iframe 与系统同源、CSP 用 frame-src 'self' 收口；库与写回对象
 * 必须属于本应用发布版本，映射字段必须存在且类型合适。运行时引擎只能经令牌调用两个 engine-link 接口，权限仍按令牌用户实时判定。
 *
 * <p>无状态工具类（不注册为 Spring Bean）：由 {@link ApplicationPageValidator} 传入它已注入的 {@link
 * ApplicationResourceContext} 调用，避免改动按清单装配页面校验器的既有测试上下文。
 */
final class ApplicationEngineValidator {
    private ApplicationEngineValidator() {}

    /** 站内路径：/ 开头，不含 //、..、查询串与片段。 */
    static final Pattern ENGINE_URL = Pattern.compile("^/[A-Za-z0-9._~/-]{0,200}$");

    static final Set<String> LIBRARY_KEYS =
            Set.of(
                    "name",
                    "manufacturer",
                    "model",
                    "specification",
                    "unit",
                    "unitPrice",
                    "methodCode");
    static final Set<String> WRITEBACK_KEYS =
            Set.of(
                    "quantity",
                    "material",
                    "name",
                    "unit",
                    "unitPrice",
                    "methodCode",
                    "basis",
                    "space");
    private static final Set<String> TEXT_TYPES =
            Set.of(FieldTypeEnum.TEXT.getCode(), FieldTypeEnum.TEXTAREA.getCode());
    private static final Set<String> NUMBER_TYPES =
            Set.of(
                    FieldTypeEnum.INTEGER.getCode(),
                    FieldTypeEnum.DECIMAL.getCode(),
                    FieldTypeEnum.MONEY.getCode());
    private static final Set<String> TEXTUAL_KEYS =
            Set.of("name", "unit", "methodCode", "basis", "space");

    /** 只做与页面上下文无关的检查（地址格式、键名）；对象与字段在 {@link #context} 里核对。 */
    static void node(ApplicationUi.Node node) {
        ApplicationUi.EngineBlock engine = node.engine();
        if (engine == null) return;
        String url = engine.engineUrl();
        if (url != null
                && !url.isBlank()
                && (!ENGINE_URL.matcher(url).matches() || url.contains("//") || url.contains("..")))
            throw invalid("设计引擎地址只能是站内路径（如 /engine01/），不能是外部地址");
        keys(engine.materials() == null ? null : engine.materials().fields(), LIBRARY_KEYS, "材料库");
        keys(
                engine.components() == null ? null : engine.components().fields(),
                LIBRARY_KEYS,
                "构件库");
        keys(engine.bom() == null ? null : engine.bom().fields(), WRITEBACK_KEYS, "材料清单写回");
    }

    static void context(
            ApplicationResourceContext resourceContext,
            ApplicationUi.Page page,
            ApplicationUi.Node node,
            Map<String, DataCenter.Definition> definitions) {
        if (page.contextObjectId() == null || page.contextObjectId().isBlank())
            throw invalid("设计引擎区块只能放在有当前记录对象的页面（请在页面设置里选择“当前记录对象”）");
        ApplicationUi.EngineBlock engine = node.engine();
        if (engine == null) return;
        DataCenter.Definition materials =
                library(resourceContext, engine.materials(), definitions, "材料库");
        library(resourceContext, engine.components(), definitions, "构件库");
        ApplicationUi.EngineWriteback bom = engine.bom();
        if (bom == null || bom.objectId() == null || bom.objectId().isBlank()) return;
        DataCenter.Definition target = resourceContext.object(definitions, bom.objectId());
        Map<String, FieldDefinition> fields = resourceContext.fields(target);
        if (!reference(target, bom.recordFieldId(), page.contextObjectId()))
            throw invalid("材料清单写回：「关联当前记录的字段」必须是指向页面当前对象的引用字段");
        FieldDefinition key = fields.get(bom.keyFieldId());
        if (key == null || !TEXT_TYPES.contains(key.type()))
            throw invalid("材料清单写回：「引擎行键」必须是写回对象上的文本字段");
        Map<String, String> mapping = bom.fields() == null ? Map.of() : bom.fields();
        if (mapping.get("quantity") == null) throw invalid("材料清单写回：必须映射「数量」字段");
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            FieldDefinition field = fields.get(entry.getValue());
            if (field == null) throw invalid("材料清单写回：映射的字段不属于写回对象：" + label(entry.getKey()));
            if (List.of(bom.recordFieldId(), bom.keyFieldId()).contains(entry.getValue()))
                throw invalid("材料清单写回：关联字段与行键字段不能再映射为其它内容");
            switch (entry.getKey()) {
                case "quantity", "unitPrice" -> {
                    if (!NUMBER_TYPES.contains(field.type()))
                        throw invalid("材料清单写回：「" + label(entry.getKey()) + "」必须映射到数值字段");
                }
                case "material" -> {
                    if (materials == null
                            || !reference(target, entry.getValue(), materials.objectId()))
                        throw invalid("材料清单写回：「材料」必须是指向材料库对象的引用字段");
                }
                default -> {
                    if (TEXTUAL_KEYS.contains(entry.getKey()) && !TEXT_TYPES.contains(field.type()))
                        throw invalid("材料清单写回：「" + label(entry.getKey()) + "」必须映射到文本字段");
                }
            }
        }
    }

    private static DataCenter.Definition library(
            ApplicationResourceContext resourceContext,
            ApplicationUi.EngineLibrary library,
            Map<String, DataCenter.Definition> definitions,
            String label) {
        if (library == null || library.objectId() == null || library.objectId().isBlank())
            return null;
        DataCenter.Definition definition = resourceContext.object(definitions, library.objectId());
        Map<String, FieldDefinition> fields = resourceContext.fields(definition);
        Map<String, String> mapping = library.fields() == null ? Map.of() : library.fields();
        if (mapping.get("name") == null || mapping.get("name").isBlank())
            throw invalid(label + "：必须映射「名称」字段");
        for (Map.Entry<String, String> entry : mapping.entrySet())
            if (entry.getValue() != null
                    && !entry.getValue().isBlank()
                    && !fields.containsKey(entry.getValue()))
                throw invalid(label + "：映射的字段不属于该对象：" + label(entry.getKey()));
        return definition;
    }

    private static final Map<String, String> LABELS =
            Map.ofEntries(
                    Map.entry("name", "名称"),
                    Map.entry("manufacturer", "厂家"),
                    Map.entry("model", "型号"),
                    Map.entry("specification", "规格"),
                    Map.entry("unit", "单位"),
                    Map.entry("unitPrice", "单价"),
                    Map.entry("methodCode", "工法编码"),
                    Map.entry("quantity", "数量"),
                    Map.entry("material", "材料"),
                    Map.entry("basis", "计算依据"),
                    Map.entry("space", "空间"));

    private static String label(String key) {
        return LABELS.getOrDefault(key, key);
    }

    private static void keys(Map<String, String> mapping, Set<String> allowed, String label) {
        if (mapping == null) return;
        for (String key : mapping.keySet())
            if (!allowed.contains(key)) throw invalid(label + "：不支持的映射项 " + key);
    }

    /** 字段是否为本对象上指向 targetObjectId 的单值引用（REFERENCE / ONE_TO_ONE）。 */
    static boolean reference(DataCenter.Definition owner, String fieldId, String targetObjectId) {
        if (fieldId == null || owner.relations() == null) return false;
        return owner.relations().stream()
                .anyMatch(
                        r ->
                                Objects.equals(r.fieldId(), fieldId)
                                        && Objects.equals(r.targetObjectId(), targetObjectId)
                                        && (RelationTypeEnum.REFERENCE.matches(r.kind())
                                                || RelationTypeEnum.ONE_TO_ONE.matches(r.kind())));
    }
}
