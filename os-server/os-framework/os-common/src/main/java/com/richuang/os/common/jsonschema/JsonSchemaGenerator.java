package com.richuang.os.common.jsonschema;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.richuang.os.common.jsonschema.annotation.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Java Bean → JSON Schema（draft-04）生成器
 *
 * <p>基于 Jackson 反射遍历 Java bean 的字段，生成 draft-04 格式的 JSON Schema。
 * 支持 {@link Schema} 注解补充 title / description / required / example / type / enum 等元信息。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>支持嵌套对象、List / Collection、Map、枚举；</li>
 *   <li>基础类型：String、数值（int/long/double/BigDecimal 等）、boolean、日期时间；</li>
 *   <li>循环引用保护：以 Set 记录正在处理的类型，避免无限递归。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>
 *   JsonNode schema = JsonSchemaGenerator.generate(SomeClass.class);
 *   ObjectNode draft04 = JsonSchemaGenerator.generateAsDraft04(SomeClass.class);
 * </pre>
 *
 * @author os
 */
public class JsonSchemaGenerator {

    /**
     * 默认 ObjectMapper（复用，解析 class 结构）
     */
    private static final ObjectMapper MAPPER = buildMapper();

    private JsonSchemaGenerator() {
    }

    /**
     * 构建用于结构解析的 ObjectMapper
     */
    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    /**
     * 将 JSON 值节点反序列化为指定 Java 类型的对象
     *
     * <p>适用于将 script_content 等 metadata 值（JsonNode）转换为对应的字段定义对象。</p>
     *
     * @param value JSON 值节点
     * @param type  目标 Java 类型
     * @param <T>   目标类型
     * @return 目标类型实例；value 为空或转换失败时返回 null
     */
    public static <T> T fromValue(JsonNode value, Class<T> type) {
        if (value == null || value.isNull() || type == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(value, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 Java bean 生成 JSON Schema（draft-04 完整结构）
     *
     * @param clazz Java 类型
     * @return draft-04 schema（含 $schema 声明），失败返回 null
     */
    public static JsonNode generate(Class<?> clazz) {
        return generateAsDraft04(clazz);
    }

    /**
     * 将 Java bean 生成 JSON Schema（draft-04 完整结构）
     *
     * @param clazz Java 类型
     * @return draft-04 schema（含 $schema 声明），失败返回 null
     */
    public static ObjectNode generateAsDraft04(Class<?> clazz) {
        try {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("$schema", "http://json-schema.org/draft-04/schema#");
            root.put("type", "object");
            buildObject(root, clazz, new HashSet<>());
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建对象类型 schema
     */
    private static void buildObject(ObjectNode node, Class<?> clazz, Set<Class<?>> processing) {
        // 防止循环引用
        if (!processing.add(clazz)) {
            return;
        }
        try {
            BeanDescription desc = MAPPER.getSerializationConfig().introspect(MAPPER.constructType(clazz));
            ObjectNode properties = JsonNodeFactory.instance.objectNode();
            ArrayNode required = JsonNodeFactory.instance.arrayNode();

            for (BeanPropertyDefinition prop : desc.findProperties()) {
                // 字段名
                String name = prop.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // 解析 @Schema 注解（字段或 getter 上）
                Schema schema = findSchemaAnnotation(prop);
                if (schema != null && schema.ignore()) {
                    continue;
                }
                // 推断字段的 Java 类型（含泛型信息）
                JavaType fieldType = resolveFieldType(prop);
                if (fieldType == null) {
                    continue;
                }
                // 生成该字段的 schema
                ObjectNode fieldSchema = JsonNodeFactory.instance.objectNode();
                buildValue(fieldSchema, fieldType, processing);
                applySchemaAnnotation(fieldSchema, schema);
                properties.set(name, fieldSchema);

                if (schema != null && schema.required()) {
                    required.add(name);
                }
            }

            node.set("properties", properties);
            if (required.size() > 0) {
                node.set("required", required);
            }
            // 读取类上的 @Schema 注解
            applyClassAnnotation(node, clazz.getAnnotation(Schema.class));
        } finally {
            processing.remove(clazz);
        }
    }

    /**
     * 构建单个值（基础类型 / 枚举 / 嵌套对象 / 数组 / map）schema
     */
    private static void buildValue(ObjectNode node, JavaType type, Set<Class<?>> processing) {
        Class<?> raw = type.getRawClass();
        // 处理数组/集合：外层为 array，内层为元素类型
        if (type.isArrayType() || Collection.class.isAssignableFrom(raw)) {
            node.put("type", "array");
            ObjectNode items = JsonNodeFactory.instance.objectNode();
            JavaType elementType = type.isArrayType()
                    ? type.getContentType()
                    : (type.containedTypeCount() > 0 ? type.containedType(0) : MAPPER.constructType(Object.class));
            buildValue(items, elementType, processing);
            node.set("items", items);
            return;
        }
        // 处理 Map：外层 object，additionalProperties 为值类型
        if (Map.class.isAssignableFrom(raw)) {
            node.put("type", "object");
            ObjectNode additional = JsonNodeFactory.instance.objectNode();
            JavaType valueType = type.containedTypeCount() > 1
                    ? type.containedType(1)
                    : MAPPER.constructType(Object.class);
            buildValue(additional, valueType, processing);
            node.set("additionalProperties", additional);
            return;
        }
        // 基础类型映射
        String jsonType = mapPrimitiveType(raw);
        if (jsonType != null) {
            node.put("type", jsonType);
            return;
        }
        // 枚举：enum 数组
        if (raw.isEnum()) {
            node.put("type", "string");
            ArrayNode enumNode = JsonNodeFactory.instance.arrayNode();
            for (Object constant : raw.getEnumConstants()) {
                enumNode.add(String.valueOf(constant));
            }
            node.set("enum", enumNode);
            return;
        }
        // 日期时间 → string（draft-04 无 format 强制）
        if (isDateType(raw)) {
            node.put("type", "string");
            return;
        }
        // 其他复杂对象 → 递归
        if (!isBasicType(raw)) {
            node.put("type", "object");
            buildObject(node, raw, processing);
        }
    }

    /**
     * 读取字段上的 @Schema 注解（优先字段，其次 getter）
     */
    private static Schema findSchemaAnnotation(BeanPropertyDefinition prop) {
        AnnotatedField field = prop.getField();
        if (field != null) {
            Schema s = field.getAnnotation(Schema.class);
            if (s != null) {
                return s;
            }
        }
        AnnotatedMember member = prop.getPrimaryMember();
        if (member instanceof AnnotatedMethod method) {
            Schema s = method.getAnnotation(Schema.class);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /**
     * 解析字段的 Java 类型（含泛型信息）
     */
    private static JavaType resolveFieldType(BeanPropertyDefinition prop) {
        AnnotatedField field = prop.getField();
        if (field != null) {
            return field.getType();
        }
        AnnotatedMember member = prop.getPrimaryMember();
        if (member instanceof AnnotatedMethod method) {
            return method.getType();
        }
        return null;
    }

    /**
     * 将 @Schema 注解属性合并到 schema 节点
     */
    private static void applySchemaAnnotation(ObjectNode node, Schema schema) {
        if (schema == null) {
            return;
        }
        if (!schema.title().isEmpty()) {
            node.put("title", schema.title());
        }
        if (!schema.description().isEmpty()) {
            node.put("description", schema.description());
        }
        if (!schema.example().isEmpty()) {
            node.put("default", schema.example());
        }
        if (!schema.type().isEmpty()) {
            node.put("type", schema.type());
        }
        if (schema.enumValues().length > 0) {
            ArrayNode enumNode = JsonNodeFactory.instance.arrayNode();
            for (String v : schema.enumValues()) {
                enumNode.add(v);
            }
            node.set("enum", enumNode);
        }
        if (!schema.extra().isEmpty()) {
            try {
                JsonNode extra = MAPPER.readTree(schema.extra());
                if (extra.isObject()) {
                    extra.fields().forEachRemaining(e -> node.set(e.getKey(), e.getValue()));
                }
            } catch (Exception ignored) {
                // 忽略非法 extra
            }
        }
    }

    /**
     * 将类上的 @Schema 注解合并到对象节点
     */
    private static void applyClassAnnotation(ObjectNode node, Schema schema) {
        if (schema == null) {
            return;
        }
        if (!schema.title().isEmpty()) {
            node.put("title", schema.title());
        }
        if (!schema.description().isEmpty()) {
            node.put("description", schema.description());
        }
    }

    /**
     * 基础类型 → JSON Schema 类型
     */
    private static String mapPrimitiveType(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class) {
            return "string";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class
                || type == Short.class || type == short.class || type == Byte.class || type == byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class || type == Float.class || type == float.class
                || type == BigDecimal.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return null;
    }

    /**
     * 是否为日期时间类型
     */
    private static boolean isDateType(Class<?> type) {
        return type == Date.class
                || type == LocalDate.class
                || type == LocalDateTime.class
                || type == LocalTime.class;
    }

    /**
     * 是否为基础类型（无需递归）
     */
    private static boolean isBasicType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Character.class
                || type == Boolean.class
                || Number.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || type == BigDecimal.class
                || type == BigInteger.class;
    }

}
