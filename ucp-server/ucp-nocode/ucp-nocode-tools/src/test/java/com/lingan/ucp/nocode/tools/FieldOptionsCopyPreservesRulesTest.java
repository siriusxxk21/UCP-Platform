package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.FieldOptions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * FieldOptions 复制不丢规则（设计稿 3.2 风险点）。以反射对照记录组件：复制器逐组件覆盖、with 方法只改自身组件； 再扫描正式源码，任何 new
 * FieldOptions(...) 都必须显式给出全部组件，避免旧构造器把 rules 静默置空。
 */
class FieldOptionsCopyPreservesRulesTest {
    private static final RecordComponent[] COMPONENTS = FieldOptions.class.getRecordComponents();
    private static final Pattern CONSTRUCTION =
            Pattern.compile("new\\s+(?:DataCenter\\s*\\.\\s*)?FieldOptions\\s*\\(");

    /** 同一组件的两个不同取值；variant 0 为基线，1 为覆盖值。 */
    private static Object sample(RecordComponent c, int variant) {
        var type = c.getType();
        if (type == String.class) return "v" + variant + ":" + c.getName();
        if (type == Boolean.class) return variant == 0;
        if (type == List.class) return List.of(new DataCenter.Option("c" + variant, "选项", false));
        if (type == SelectionFields.Source.class)
            return new SelectionFields.Source(
                    variant == 0 ? "LOCAL_OPTIONS" : "SYSTEM_DICTIONARY",
                    null,
                    variant == 0 ? null : "dict",
                    List.of(),
                    false,
                    List.of(),
                    "NONE");
        if (type == CalculationOptions.class)
            return new CalculationOptions(
                    "LOCAL",
                    "ON_SAVE",
                    null,
                    null,
                    "c" + variant,
                    "SUM",
                    "AND",
                    List.of(),
                    false,
                    List.of(),
                    null);
        if (type == AutoNumberOptions.class)
            return new AutoNumberOptions("P" + variant, "yyyyMMdd", 4, 1L, "NEVER");
        if (type == FieldRules.class)
            return new FieldRules(
                    new FieldRules.Reference("label" + variant, List.of()),
                    null,
                    "c_" + variant,
                    variant == 0 ? "DOWN" : "HALF_UP",
                    null,
                    null);
        throw new AssertionError("新增的 FieldOptions 组件需要补充样例：" + c);
    }

    private static FieldOptions full() throws Exception {
        var types =
                Arrays.stream(COMPONENTS).map(RecordComponent::getType).toArray(Class<?>[]::new);
        var values = Arrays.stream(COMPONENTS).map(c -> sample(c, 0)).toArray();
        return FieldOptions.class.getDeclaredConstructor(types).newInstance(values);
    }

    private static Object value(FieldOptions o, RecordComponent c) throws Exception {
        return c.getAccessor().invoke(o);
    }

    /** 除 changed 外所有组件与基线相同。 */
    private static void onlyChanged(FieldOptions base, FieldOptions next, String changed)
            throws Exception {
        for (var c : COMPONENTS)
            if (!c.getName().equals(changed))
                assertThat(value(next, c))
                        .as(changed + " 复制时保留 " + c.getName())
                        .isEqualTo(value(base, c));
    }

    @Test
    void rulesIsTheLastComponent() {
        assertThat(COMPONENTS[COMPONENTS.length - 1].getName()).isEqualTo("rules");
        assertThat(COMPONENTS[COMPONENTS.length - 1].getType()).isEqualTo(FieldRules.class);
    }

    @Test
    void copierCoversEveryComponent() throws Exception {
        var base = full();
        assertThat(FieldOptions.copyOf(base).build()).isEqualTo(base);
        for (var c : COMPONENTS) {
            var setter = FieldOptions.Builder.class.getMethod(c.getName(), c.getType());
            var builder = FieldOptions.copyOf(base);
            setter.invoke(builder, sample(c, 1));
            var next = builder.build();
            assertThat(value(next, c)).as(c.getName()).isEqualTo(sample(c, 1));
            onlyChanged(base, next, c.getName());
        }
    }

    @Test
    void withMethodsKeepRulesAndOtherComponents() throws Exception {
        var base = full();
        var byName = new HashMap<String, RecordComponent>();
        for (var c : COMPONENTS) byName.put(c.getName(), c);
        onlyChanged(base, base.withDefaultValue("x"), "defaultValue");
        onlyChanged(
                base,
                base.withSelection((SelectionFields.Source) sample(byName.get("selection"), 1)),
                "selection");
        onlyChanged(
                base,
                base.withCalculation((CalculationOptions) sample(byName.get("calculation"), 1)),
                "calculation");
        onlyChanged(
                base,
                base.withAutoNumber((AutoNumberOptions) sample(byName.get("autoNumber"), 1)),
                "autoNumber");
        var replaced = base.withRules((FieldRules) sample(byName.get("rules"), 1));
        onlyChanged(base, replaced, "rules");
        assertThat(replaced.rules()).isEqualTo(sample(byName.get("rules"), 1));
        assertThat(base.withRules(null).rules()).isNull();
    }

    @Test
    void legacyContractsDecodeWithoutRulesAndStayByteCompatible() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        // 既有构造器与旧快照：规则为空且序列化不出现 rules 键，未配置规则的对象版本 JSON 不变。
        assertThat(FieldOptions.defaults().rules()).isNull();
        assertThat(json.writeValueAsString(FieldOptions.defaults())).doesNotContain("\"rules\"");
        var legacy =
                json.writeValueAsString(FieldOptions.defaults()).replace("\"rules\":null,", "");
        assertThat(json.readValue(legacy, FieldOptions.class)).isEqualTo(FieldOptions.defaults());
        var base = full();
        assertThat(json.readValue(json.writeValueAsString(base), FieldOptions.class))
                .isEqualTo(base);
        var source =
                new SelectionFields.Source(
                        "LOCAL_OPTIONS", null, null, List.of(), false, List.of(), "NONE");
        assertThat(json.writeValueAsString(source))
                .doesNotContain("sourceObjectId")
                .doesNotContain("sourceFieldId");
        var field =
                new FieldDefinition(
                        "f", "f", "c_f", "选择", "SELECT", null, null, null, false, false, 0);
        // 既有来源的身份原文不变，发布时不会误触发选择值迁移。
        assertThat(SelectionFields.identity(field, FieldOptions.defaults().withSelection(source)))
                .isEqualTo("LOCAL_OPTIONS:::false");
        var picked =
                new SelectionFields.Source(
                        "OBJECT_FIELD_OPTIONS",
                        null,
                        null,
                        List.of(),
                        false,
                        List.of(),
                        "NONE",
                        null,
                        "100",
                        "107");
        assertThat(SelectionFields.identity(field, FieldOptions.defaults().withSelection(picked)))
                .isEqualTo("OBJECT_FIELD_OPTIONS:::false:100:107");
    }

    @Test
    void productionConstructorsPassEveryComponent() throws IOException {
        Path root = moduleRoot();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources =
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(
                                    p -> {
                                        String path = p.toString().replace('\\', '/');
                                        // 与 format.mjs 相同：不纳入临时副本与构建产物。
                                        return path.contains("/src/main/java/")
                                                && !path.contains("/.work/")
                                                && !path.contains("/target/");
                                    })
                            .toList();
        }
        // 扫到 0 个文件时断言恒真：先钉住扫描面。
        assertThat(sources).as("正式源码扫描范围").hasSizeGreaterThan(300);
        List<String> violations = new ArrayList<>();
        int sites = 0;
        for (Path file : sources) {
            if (file.getFileName().toString().equals("DataCenter.java")) continue;
            String source = Files.readString(file);
            var matcher = CONSTRUCTION.matcher(source);
            while (matcher.find()) {
                sites++;
                int arguments = arguments(source, matcher.end() - 1);
                if (arguments != COMPONENTS.length)
                    violations.add(
                            root.relativize(file)
                                    + ":"
                                    + (source.substring(0, matcher.start()).split("\n", -1).length)
                                    + " 传了 "
                                    + arguments
                                    + " 个参数");
            }
        }
        assertThat(sites).as("仍直接构造 FieldOptions 的正式代码位置").isGreaterThanOrEqualTo(5);
        assertThat(violations)
                .as(
                        "复制 FieldOptions 请用 FieldOptions.copyOf，新建请显式给出全部 "
                                + COMPONENTS.length
                                + " 个组件")
                .isEmpty();
    }

    /** 统计顶层实参个数；跳过字符串、字符字面量及嵌套括号中的逗号。 */
    static int arguments(String source, int open) {
        int depth = 0, count = 0;
        boolean any = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != c)
                    j += source.charAt(j) == '\\' ? 2 : 1;
                i = j;
                any = true;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = source.indexOf('\n', i);
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth++ > 0) any = true;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) return any ? count + 1 : 0;
            } else if (c == ',' && depth == 1) count++;
            else if (!Character.isWhitespace(c)) any = true;
        }
        throw new AssertionError("构造调用括号未闭合");
    }

    private static Path moduleRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        // 测试运行器的工作目录不一定在仓库内：再从编译产物（ucp-nocode 模块下的 classes）向上查找。
        Path classes;
        try {
            classes =
                    Path.of(
                            DataCenter.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
        } catch (java.net.URISyntaxException ex) {
            throw new IOException(ex);
        }
        for (Path start : List.of(current, classes))
            for (Path p = start; p != null; p = p.getParent()) {
                if (Files.isDirectory(p.resolve("ucp-nocode-api/src/main/java"))) return p;
                if (Files.isDirectory(p.resolve("ucp-nocode/ucp-nocode-api/src/main/java")))
                    return p.resolve("ucp-nocode");
                if (Files.isDirectory(
                        p.resolve("ucp-server/ucp-nocode/ucp-nocode-api/src/main/java")))
                    return p.resolve("ucp-server/ucp-nocode");
            }
        throw new AssertionError("未找到 ucp-nocode 源码目录，工作目录：" + current + "，类目录：" + classes);
    }
}
