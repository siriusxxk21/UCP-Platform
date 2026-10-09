package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.nocode.api.ObjectImports;
import com.richuang.os.nocode.api.ObjectReconciliation;
import com.richuang.os.nocode.metadata.api.DataTables;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

/** DTO 提出服务内部类后，对照原声明生成的固定 JSON 样本，检查字段、空值和嵌套结构。 */
class ApiContractCompatibilityTest {
    static final Map<String, Class<?>> TYPES =
            Map.of(
                    "reconcile-preview", ObjectReconciliation.Preview.class,
                    "reconcile-apply", ObjectReconciliation.Apply.class,
                    "table-row", DataTables.TableRow.class,
                    "table-detail", DataTables.TableDetail.class,
                    "table-preflight", DataTables.Preflight.class,
                    "table-preview", DataTables.Preview.class,
                    "import-error", ObjectImports.Error.class,
                    "import-preview", ObjectImports.Preview.class);

    static Object sample(Type type, String path, int depth) throws Exception {
        if (depth > 5) return null;
        if (type instanceof ParameterizedType parameter) {
            var args = parameter.getActualTypeArguments();
            if (parameter.getRawType() == List.class) {
                var list = new ArrayList<>();
                list.add(sample(args[0], path + "[0]", depth + 1));
                return list;
            }
            if (parameter.getRawType() == Map.class) {
                var map = new LinkedHashMap<>();
                map.put("字段", sample(args[1], path + ".value", depth + 1));
                return map;
            }
        }
        if (type == String.class || type == Object.class) return path + "：原值";
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 7;
        if (type == long.class || type == Long.class) return 19L;
        if (type == OffsetDateTime.class) return OffsetDateTime.parse("2026-09-13T12:00:00+08:00");
        if (type instanceof Class<?> clazz && clazz.isRecord()) {
            var components = clazz.getRecordComponents();
            var types =
                    Arrays.stream(components)
                            .map(RecordComponent::getType)
                            .toArray(Class<?>[]::new);
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++)
                values[i] =
                        sample(
                                components[i].getGenericType(),
                                path + "." + components[i].getName(),
                                depth + 1);
            var constructor = clazz.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        }
        return null;
    }

    @TestFactory
    Stream<DynamicTest> movedDtosKeepTheirJsonContract() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        JsonNode expected;
        try (var input = getClass().getResourceAsStream("/contract-baseline/moved-dtos.json")) {
            assertThat(input).as("原 DTO 声明的固定序列化基线必须存在").isNotNull();
            expected = json.readTree(input);
        }
        assertThat(expected.size()).isEqualTo(TYPES.size());
        return TYPES.entrySet().stream()
                .map(
                        entry ->
                                DynamicTest.dynamicTest(
                                        entry.getKey(),
                                        () -> {
                                            // 两侧都经过 JSON 文本，避免内存 LongNode/DecimalNode 与读回节点类型不同而误报。
                                            assertThat(
                                                            json.readTree(
                                                                    json.writeValueAsString(
                                                                            sample(
                                                                                    entry
                                                                                            .getValue(),
                                                                                    "root",
                                                                                    0))))
                                                    .isEqualTo(expected.get(entry.getKey()));
                                        }));
    }
}
