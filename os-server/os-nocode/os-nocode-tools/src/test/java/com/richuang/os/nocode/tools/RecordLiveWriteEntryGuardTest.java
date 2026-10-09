package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.DataCenter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 实时推送的写入口守卫（契约 3.7）：正式代码里所有直接写业务表的 Mapper 调用，按「类名 + 被调方法」计数后必须与白名单逐项相同。
 *
 * <p>白名单每一项标注三选一：已挂登记（HOOKED）、由某个挂点覆盖（COVERED_BY）、已登记的缺口（GAP）。出现白名单外的调用方、
 * 计数变了、白名单里有而代码里已没有，都是红：新增或挪动写点的人必须回答「这次写入要不要通知正在看的人」。
 *
 * <p>本守卫只负责发现白名单外的新写点。「登记确实执行到、确实在提交后才交付」由 RecordLiveHooksIntegrationTest 钉，不由它钉： 源码里出现调用字样不等于执行到。
 */
class RecordLiveWriteEntryGuardTest {
    enum Coverage {
        HOOKED,
        COVERED_BY,
        GAP
    }

    record Allowed(String holder, String method, int count, Coverage coverage, String note) {}

    /** 四个会写业务表的 Mapper：方法逐个归类。接口新增方法而这里没归类 ⇒ 红，不让新的写方法从守卫旁边溜过去。 */
    private static final Map<String, Set<String>> WRITES =
            Map.of(
                    "RecordMapper",
                    Set.of("insert", "update", "orderedUpdate", "delete", "attach", "detach"),
                    "ObjectMaintenanceMapper",
                    Set.of("clearColumn"),
                    "FieldConversionMapper",
                    Set.of(
                            "releaseReference",
                            "releaseReferenceUnique",
                            "prepare",
                            "clear",
                            "convert",
                            "convertPreserving"),
                    "SelectionMigrationMapper",
                    Set.of("toText", "mapValues", "toTarget"));

    private static final Map<String, Set<String>> READS =
            Map.of(
                    "RecordMapper",
                    Set.of(
                            "summaries",
                            "relationTargets",
                            "relationBatchTargets",
                            "relationSources",
                            "rows",
                            "orderedRows",
                            "count",
                            "statistics",
                            "runningTotal",
                            "runningTotals",
                            "orderedGroups",
                            "sequenceValues",
                            "sequenceRows"),
                    "ObjectMaintenanceMapper",
                    Set.of(
                            "lockDefinitions",
                            "parentRecordId",
                            "lockColumnTable",
                            "columnStats",
                            "affectedMainIds",
                            "hasProtectedRows"),
                    "FieldConversionMapper",
                    Set.of(
                            "assess",
                            "assessedRows",
                            "statistics",
                            "counts",
                            "preservationFailures",
                            "targetConstraintFailures",
                            "parsedNumericDuplicates",
                            "targetUniqueFailures",
                            "invalidSelectionRows",
                            "emptyMultiRows",
                            "rows",
                            "generatedDependents"),
                    "SelectionMigrationMapper",
                    Set.of("values", "conflicts"));

    /** 基线 2e5cff2 上的全部写点。H1–H6 与 G-R1/G-R2 的含义见契约 3.5、3.6。 */
    static final List<Allowed> WHITELIST =
            List.of(
                    new Allowed("RecordWriteService", "insert", 1, Coverage.HOOKED, "H1：保存的新增分支"),
                    new Allowed(
                            "RecordWriteService",
                            "update",
                            2,
                            Coverage.HOOKED,
                            "H1：保存的修改分支；H5：发起流程后推进记录版本"),
                    new Allowed(
                            "RecordDeletionService",
                            "delete",
                            2,
                            Coverage.HOOKED,
                            "H2：主记录删除；同一次删除里先删的明细行由 H2 覆盖"),
                    new Allowed(
                            "RecordDeletionService",
                            "update",
                            1,
                            Coverage.HOOKED,
                            "H3：解除多对多后给对方记录推版本"),
                    new Allowed(
                            "OrderedRecordCalculations",
                            "orderedUpdate",
                            1,
                            Coverage.HOOKED,
                            "H4：有序计算回写，三个入口都汇到 apply"),
                    new Allowed(
                            "RecordCalculations",
                            "update",
                            1,
                            Coverage.COVERED_BY,
                            "H1 / H3：落库计算回写只在保存内与 H3 之后对同一条记录调用"),
                    new Allowed(
                            "RecordDetailWriter",
                            "insert",
                            1,
                            Coverage.COVERED_BY,
                            "H1：明细行只在保存内写，归到主记录"),
                    new Allowed(
                            "RecordDetailWriter",
                            "update",
                            1,
                            Coverage.COVERED_BY,
                            "H1：明细行只在保存内写，归到主记录"),
                    new Allowed(
                            "RecordDetailWriter",
                            "delete",
                            1,
                            Coverage.COVERED_BY,
                            "H1：明细行只在保存内写，归到主记录"),
                    new Allowed(
                            "RecordRelations",
                            "attach",
                            1,
                            Coverage.COVERED_BY,
                            "H1：保存内本侧记录已登记；对方记录不登记 = 缺口 G-R1"),
                    new Allowed(
                            "RecordRelations",
                            "detach",
                            1,
                            Coverage.COVERED_BY,
                            "H1 / H2 / H3：保存内本侧、删除内两侧已登记；保存场景的对方记录 = 缺口 G-R1"),
                    new Allowed(
                            "ObjectColumnMaintenance",
                            "clearColumn",
                            1,
                            Coverage.HOOKED,
                            "H6：整列清空"),
                    new Allowed(
                            "FieldConversionPlanner",
                            "releaseReference",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "FieldConversionPlanner",
                            "releaseReferenceUnique",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "FieldConversionPlanner",
                            "prepare",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "FieldConversionPlanner",
                            "convertPreserving",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "FieldConversionPlanner", "clear", 1, Coverage.GAP, "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "FieldConversionPlanner",
                            "convert",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的类型转换"),
                    new Allowed(
                            "SelectionMigrationService",
                            "toText",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的选项迁移"),
                    new Allowed(
                            "SelectionMigrationService",
                            "mapValues",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的选项迁移"),
                    new Allowed(
                            "SelectionMigrationService",
                            "toTarget",
                            1,
                            Coverage.GAP,
                            "G-R2：对象发布时的选项迁移"));

    private static final Pattern METHOD =
            Pattern.compile(
                    "^\\s+(?:[A-Za-z_][\\w.]*(?:<[^;{}()]*>)?(?:\\[\\])?)\\s+([a-z]\\w*)\\s*\\(",
                    Pattern.MULTILINE);

    @Test
    void everyDirectWriteIsHookedCoveredOrARecordedGap() throws IOException {
        Path root = moduleRoot();
        assertThat(violations(scan(root), WHITELIST))
                .as("直接写业务表的调用与实时推送白名单不一致；新增或挪动写点时先决定：挂登记、由现有挂点覆盖，还是登记为缺口")
                .isEmpty();
    }

    @Test
    void mapperMethodsAreAllClassifiedAsReadOrWrite() throws IOException {
        Path root = moduleRoot();
        for (String mapper : WRITES.keySet()) {
            Path file;
            try (Stream<Path> files = Files.walk(root)) {
                file =
                        files.filter(p -> p.getFileName().toString().equals(mapper + ".java"))
                                .filter(p -> normalized(p).contains("/src/main/java/"))
                                .filter(p -> !normalized(p).contains("/target/"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("找不到 " + mapper));
            }
            String code = mask(Files.readString(file));
            Set<String> declared = new java.util.TreeSet<>();
            Matcher matcher = METHOD.matcher(code);
            while (matcher.find()) declared.add(matcher.group(1));
            // 接口里的嵌套记录与工具方法不是语句入口。
            declared.removeAll(Set.of("quote", "Identifiers"));
            Set<String> classified = new java.util.TreeSet<>(WRITES.get(mapper));
            classified.addAll(READS.get(mapper));
            assertThat(declared)
                    .as(mapper + " 的方法必须逐个归类为读或写；新增写方法要同时进白名单")
                    .containsExactlyInAnyOrderElementsOf(classified);
        }
    }

    /** 实际计数与白名单逐项比对；任何一项不同都列出来。供变异检验直接传入改过的白名单。 */
    static List<String> violations(Map<String, Integer> actual, List<Allowed> whitelist) {
        Map<String, Integer> expected = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Allowed allowed : whitelist) {
            if (allowed.coverage() == null || allowed.note() == null || allowed.note().isBlank())
                problems.add("白名单项缺少标注：" + allowed.holder() + "." + allowed.method());
            if (expected.put(allowed.holder() + "." + allowed.method(), allowed.count()) != null)
                problems.add("白名单重复：" + allowed.holder() + "." + allowed.method());
        }
        actual.forEach(
                (key, count) -> {
                    Integer allowed = expected.get(key);
                    if (allowed == null) problems.add("白名单外的写点：" + key + " ×" + count);
                    else if (!allowed.equals(count))
                        problems.add("写点计数变了：" + key + " 白名单 ×" + allowed + "，实际 ×" + count);
                });
        expected.forEach(
                (key, count) -> {
                    if (!actual.containsKey(key)) problems.add("白名单里有、代码里已没有：" + key);
                });
        return problems;
    }

    /** 扫描 os-nocode-* 正式源码：对类型为四个 Mapper 之一的变量调用其写方法的位置，按「类名.方法」计数。 */
    static Map<String, Integer> scan(Path root) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources =
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(
                                    p -> {
                                        String path = normalized(p);
                                        return path.matches(".*/os-nocode-[a-z]+/src/main/java/.*")
                                                && !path.contains("/.work/")
                                                && !path.contains("/target/");
                                    })
                            .toList();
        }
        // 扫到 0 个文件时比对恒真：先钉住扫描面。
        assertThat(sources).as("正式源码扫描范围").hasSizeGreaterThan(300);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> holders = new LinkedHashMap<>();
        for (Path file : sources) {
            String name = file.getFileName().toString();
            String holder = name.substring(0, name.length() - ".java".length());
            if (WRITES.containsKey(holder)) continue;
            String code = mask(Files.readString(file));
            for (Map.Entry<String, Set<String>> mapper : WRITES.entrySet()) {
                String type = mapper.getKey();
                if (!Pattern.compile("\\b" + type + "\\b").matcher(code).find()) continue;
                holders.merge(type, 1, Integer::sum);
                Set<String> variables = new java.util.TreeSet<>();
                Matcher declaration =
                        Pattern.compile("\\b" + type + "\\s+([A-Za-z_]\\w*)\\s*[;=,)]")
                                .matcher(code);
                while (declaration.find()) variables.add(declaration.group(1));
                // 类型由推断得出的变量（var x = session.getMapper(X.class)）同样按变量追踪。
                String lookup =
                        "getMapper\\(\\s*(?:[\\w.]+\\.)?" + type + "\\s*\\.\\s*class\\s*\\)";
                Matcher inferred =
                        Pattern.compile("([A-Za-z_]\\w*)\\s*=\\s*[^;=]*" + lookup + "\\s*;")
                                .matcher(code);
                while (inferred.find()) variables.add(inferred.group(1));
                // 取出后直接链式调用（getMapper(X.class).update(...)）没有变量可追踪，在这里直接计数。
                Matcher chained =
                        Pattern.compile(lookup + "\\s*\\.\\s*([A-Za-z_]\\w*)\\s*\\(").matcher(code);
                while (chained.find())
                    if (mapper.getValue().contains(chained.group(1)))
                        counts.merge(holder + "." + chained.group(1), 1, Integer::sum);
                for (String variable : variables) {
                    Matcher call =
                            Pattern.compile(
                                            "(?<![\\w.])"
                                                    + Pattern.quote(variable)
                                                    + "\\s*\\.\\s*([A-Za-z_]\\w*)\\s*\\(")
                                    .matcher(code);
                    while (call.find())
                        if (mapper.getValue().contains(call.group(1)))
                            counts.merge(holder + "." + call.group(1), 1, Integer::sum);
                }
            }
        }
        // 四个 Mapper 各至少有一个持有者被扫到；否则是扫描面或识别方式失效，不是「没有写点」。
        assertThat(holders.keySet())
                .as("被扫到的 Mapper 持有者")
                .containsExactlyInAnyOrderElementsOf(WRITES.keySet());
        return counts;
    }

    /** 把注释、字符串、文本块与字符字面量抹成等长空白（保留换行），避免注释或文案里的调用字样被算作写点。 */
    static String mask(String source) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                end = source.indexOf('\n', i);
                if (end < 0) end = n;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n')
                    j += source.charAt(j) == '\\' ? 2 : 1;
                end = Math.min(n, j + 1);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
            i = end;
        }
        return out.toString();
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path moduleRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        // 测试运行器的工作目录不一定在仓库内：再从编译产物（os-nocode 模块下的 classes）向上查找。
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
        throw new AssertionError("未找到 os-nocode 源码目录，工作目录：" + current + "，类目录：" + classes);
    }
}
