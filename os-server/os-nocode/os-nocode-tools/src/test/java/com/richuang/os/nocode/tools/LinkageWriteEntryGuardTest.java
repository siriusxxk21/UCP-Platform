package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.nocode.api.DataCenter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 防漏触发的静态守卫：扫描正式源码里所有直接改写业务记录的类，对照一份白名单。
 *
 * <p>数据联动「来源变化时自动更新」靠写入入口上的钩子触发；以后有人新增一条不经公共保存的写入路径，就会静默漏触发、目标值过期，
 * 没有任何测试会因此变红——所以把「谁在直写业务行」钉成清单：出现清单外的调用方即红，清单里的类不再直写也红（清单过期）。 另钉两条：凡是并排着自动更新规则钩子（lock / before /
 * after）的地方，必须同样并排着数据联动的钩子；打开系统写通道 LinkageWriteScope 的只有 RecordLinkageSync。
 *
 * <p>判据落在去掉注释与字符串之后的调用本身，注释里提到方法名不算数。
 */
class LinkageWriteEntryGuardTest {
    /** 直接写业务记录的 Mapper 及其写方法。 */
    private static final Map<String, List<String>> WRITERS =
            Map.of(
                    "RecordMapper", List.of("insert", "update", "orderedUpdate", "delete"),
                    "ObjectMaintenanceMapper", List.of("clearColumn"),
                    "FieldConversionMapper",
                            List.of(
                                    "releaseReference",
                                    "releaseReferenceUnique",
                                    "prepare",
                                    "clear",
                                    "convert",
                                    "convertPreserving"),
                    "SelectionMigrationMapper", List.of("toText", "mapValues", "toTarget"));

    /** 白名单：类 → 它直写的方法 → 与自动更新的关系。新增条目前先想清楚它要不要触发，并写明缺口编号。 */
    private static final Map<String, Map<String, String>> ALLOWED =
            Map.of(
                    "RecordWriteService",
                    Map.of(
                            "RecordMapper.insert", "已接钩子：主记录新增",
                            "RecordMapper.update", "已接钩子：主记录修改；发起流程后推版本不改字段值，不需要触发"),
                    "RecordDeletionService",
                    Map.of(
                            "RecordMapper.delete", "已接钩子：主记录删除（明细行随主记录）",
                            "RecordMapper.update", "已知不触发 · 缺口 G-4：解除多对多后给对方推版本，不改字段值"),
                    "RecordDetailWriter",
                    Map.of(
                            "RecordMapper.insert", "由主记录保存的钩子覆盖：联动只读主表字段",
                            "RecordMapper.update", "由主记录保存的钩子覆盖：联动只读主表字段",
                            "RecordMapper.delete", "由主记录保存的钩子覆盖：联动只读主表字段"),
                    "RecordCalculations",
                    Map.of("RecordMapper.update", "由主记录保存的钩子覆盖：同一条记录的落库计算，在钩子之前回写"),
                    "OrderedRecordCalculations",
                    Map.of(
                            "RecordMapper.orderedUpdate",
                            "已知不触发：有序计算直写同组其它记录；发布校验禁止把这类字段用作自动更新的取值 / 条件字段，配置上不可达"),
                    "ObjectColumnMaintenance",
                    Map.of("ObjectMaintenanceMapper.clearColumn", "已知不触发 · 缺口 G-2：整列清空"),
                    "FieldConversionPlanner",
                    Map.of(
                            "FieldConversionMapper.releaseReference", "已知不触发 · 缺口 G-3：字段类型转换",
                            "FieldConversionMapper.releaseReferenceUnique", "已知不触发 · 缺口 G-3：字段类型转换",
                            "FieldConversionMapper.prepare", "已知不触发 · 缺口 G-3：字段类型转换",
                            "FieldConversionMapper.clear", "已知不触发 · 缺口 G-3：字段类型转换",
                            "FieldConversionMapper.convert", "已知不触发 · 缺口 G-3：字段类型转换",
                            "FieldConversionMapper.convertPreserving", "已知不触发 · 缺口 G-3：字段类型转换"),
                    "SelectionMigrationService",
                    Map.of(
                            "SelectionMigrationMapper.toText", "已知不触发 · 缺口 G-3：选项迁移",
                            "SelectionMigrationMapper.mapValues", "已知不触发 · 缺口 G-3：选项迁移",
                            "SelectionMigrationMapper.toTarget", "已知不触发 · 缺口 G-3：选项迁移"));

    private static final Pattern NOISE =
            Pattern.compile(
                    "/\\*[\\s\\S]*?\\*/|//[^\\n"
                            + "]*|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n"
                            + "])*\"|'(?:\\\\.|[^'\\\\\\n"
                            + "])*'");

    private static Map<String, String> sources;

    @BeforeAll
    static void scan() throws IOException {
        Path root = moduleRoot();
        sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(
                                    p -> {
                                        String path = p.toString().replace('\\', '/');
                                        return path.contains("/src/main/java/")
                                                && !path.contains("/.work/")
                                                && !path.contains("/target/");
                                    })
                            .toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                // 去掉注释与字面量：判据只看真正的调用。
                sources.merge(
                        name,
                        NOISE.matcher(Files.readString(file)).replaceAll(" "),
                        (a, b) -> a + "\n" + b);
            }
        }
    }

    /** 先钉住扫描面：扫到 0 个文件时下面的断言会恒真。 */
    @Test
    void scanCoversTheProductionSources() {
        assertThat(sources).hasSizeGreaterThan(300);
        assertThat(sources)
                .containsKeys(
                        "RecordWriteService",
                        "RecordDeletionService",
                        "RecordLinkageSync",
                        "ObjectColumnMaintenance",
                        "FieldConversionPlanner");
        assertThat(sources.get("RecordWriteService")).contains("records.insert(");
    }

    private static Map<String, Set<String>> writersFound() {
        Map<String, Set<String>> found = new TreeMap<>();
        for (var entry : sources.entrySet()) {
            String code = entry.getValue();
            for (var writer : WRITERS.entrySet()) {
                // 这个类里类型为该 Mapper 的字段、参数或局部变量名。
                Matcher declared =
                        Pattern.compile(
                                        "\\b"
                                                + writer.getKey()
                                                + "\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[;=,)]")
                                .matcher(code);
                Set<String> names = new HashSet<>();
                while (declared.find()) names.add(declared.group(1));
                for (String name : names)
                    for (String method : writer.getValue())
                        if (Pattern.compile(
                                        "\\b"
                                                + Pattern.quote(name)
                                                + "\\s*\\.\\s*"
                                                + method
                                                + "\\s*\\(")
                                .matcher(code)
                                .find())
                            found.computeIfAbsent(entry.getKey(), k -> new TreeSet<>())
                                    .add(writer.getKey() + "." + method);
            }
        }
        // Mapper 接口自己声明方法不算调用方。
        WRITERS.keySet().forEach(found::remove);
        return found;
    }

    /** 直写业务记录的类与白名单逐项一致：多一个调用方、多一种写法、或白名单里的条目已不存在，都红。 */
    @Test
    void everyDirectWriterIsOnTheAllowList() {
        Map<String, Set<String>> expected = new TreeMap<>();
        ALLOWED.forEach((type, methods) -> expected.put(type, new TreeSet<>(methods.keySet())));
        assertThat(writersFound())
                .as("直接改写业务记录的类必须登记在白名单里并写明它与数据联动自动更新的关系" + "（已接钩子，或已知不触发 + 缺口编号）")
                .isEqualTo(expected);
        ALLOWED.values()
                .forEach(
                        methods ->
                                methods.values()
                                        .forEach(
                                                note ->
                                                        assertThat(note)
                                                                .containsAnyOf(
                                                                        "已接钩子", "钩子覆盖", "已知不触发")));
    }

    private static int count(String code, String call) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(call) + "\\s*\\(").matcher(code);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** 凡是并排着自动更新规则钩子的地方，都并排着数据联动的钩子：lock / before / after 三种逐个数对数。 */
    @Test
    void linkageHooksSitNextToEveryAutomationHook() {
        Map<String, String> problems = new TreeMap<>();
        int files = 0, locks = 0, befores = 0, afters = 0;
        for (var entry : sources.entrySet()) {
            String code = entry.getValue();
            Matcher field =
                    Pattern.compile("\\bRecordAutomations\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
                            .matcher(code);
            if (!field.find() || entry.getKey().equals("RecordAutomations")) continue;
            String automations = field.group(1);
            files++;
            for (String hook : List.of("lock", "before", "after")) {
                int theirs = count(code, automations + "." + hook);
                int ours = count(code, "linkageSync." + hook);
                if (hook.equals("lock")) locks += ours;
                if (hook.equals("before")) befores += ours;
                if (hook.equals("after")) afters += ours;
                if (theirs != ours)
                    problems.put(
                            entry.getKey() + "." + hook,
                            automations
                                    + "."
                                    + hook
                                    + " × "
                                    + theirs
                                    + "，linkageSync."
                                    + hook
                                    + " × "
                                    + ours);
            }
        }
        assertThat(problems).as("数据联动的钩子必须紧跟自动更新规则的同名钩子").isEmpty();
        // 钉住数量：一处都数不到说明扫描失效。
        assertThat(files).isGreaterThanOrEqualTo(5);
        assertThat(locks).as("lock 钩子").isEqualTo(8);
        assertThat(befores).as("before 钩子（保存、删除）").isEqualTo(2);
        assertThat(afters).as("after 钩子（保存、删除）").isEqualTo(2);
        // 删除入口还要登记「本事务正在被删除的记录」。
        assertThat(count(sources.get("RecordDeletionService"), "linkageSync.deleting"))
                .isEqualTo(1);
    }

    /** 系统写通道只能由 RecordLinkageSync 打开。 */
    @Test
    void onlyRecordLinkageSyncOpensTheWriteScope() {
        List<String> openers = new ArrayList<>();
        sources.forEach(
                (name, code) -> {
                    if (!name.equals("LinkageWriteScope")
                            && count(code, "LinkageWriteScope.run") > 0) openers.add(name);
                });
        assertThat(openers).containsExactly("RecordLinkageSync");
    }

    private static Path moduleRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
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
                if (Files.isDirectory(p.resolve("os-nocode-api/src/main/java"))) return p;
                if (Files.isDirectory(p.resolve("os-nocode/os-nocode-api/src/main/java")))
                    return p.resolve("os-nocode");
                if (Files.isDirectory(p.resolve("os-server/os-nocode/os-nocode-api/src/main/java")))
                    return p.resolve("os-server/os-nocode");
            }
        throw new IOException("找不到 os-nocode 模块根目录");
    }
}
