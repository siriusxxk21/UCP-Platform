package com.richuang.os.nocode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 引用字段固定值存量转换的命令行参数：第一个非 -- 参数是子命令，其余是 --键 值。 */
record ReferenceConstantMigrationCommand(
        String name, ReferenceConstantMigrationReport report, long actor, String out) {
    static ReferenceConstantMigrationCommand parse(String[] args, ObjectMapper json) {
        Map<String, String> options = SelectionAllMigrationTool.arguments(args);
        String name = options.getOrDefault("", ReferenceConstantMigrationTool.DRY_RUN);
        Set<String> allowed =
                switch (name) {
                    case ReferenceConstantMigrationTool.DRY_RUN -> Set.of("", "out");
                    case ReferenceConstantMigrationTool.APPLY,
                                    ReferenceConstantMigrationTool.ROLLBACK ->
                            Set.of("", "report", "actor", "out");
                    default -> throw new IllegalArgumentException("子命令只能是 dry-run、apply、rollback");
                };
        for (String key : options.keySet())
            if (!allowed.contains(key))
                throw new IllegalArgumentException(name + " 不接受参数 --" + key);
        String out =
                options.getOrDefault(
                        "out",
                        ReferenceConstantMigrationTool.DRY_RUN.equals(name)
                                ? "report.json"
                                : name + ".json");
        if (ReferenceConstantMigrationTool.DRY_RUN.equals(name))
            return new ReferenceConstantMigrationCommand(name, null, 0, out);
        long actor = actor(options.get("actor"));
        String report = options.get("report");
        if (report == null || report.isBlank()) throw new IllegalArgumentException("缺少 --report");
        try {
            return new ReferenceConstantMigrationCommand(
                    name,
                    json.readValue(
                            Path.of(report).toFile(), ReferenceConstantMigrationReport.class),
                    actor,
                    out);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法读取报告 " + report + "：" + e.getMessage(), e);
        }
    }

    private static long actor(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --actor");
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // 与非正数同样按参数错误处理
        }
        throw new IllegalArgumentException("--actor 必须是正整数，实际为「" + value + "」");
    }
}
