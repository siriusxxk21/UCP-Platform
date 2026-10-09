package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 启动脚本 deploy/object-rule-migration.sh 的停服检查（设计稿 9.5；2026-09-29 新增
 * linkage-readonly）：写库的子命令在服务端口仍监听时拒绝（退出码 65），linkage-readonly 带 --dry-run 时只读、不做停服检查。java
 * 换成只回显参数的替身，不连库、不启动任何 Spring 上下文。
 */
class ObjectRuleMigrationScriptTest {
    private static final String MARKER = "FAKE-JAVA-INVOKED";

    @TempDir Path work;
    private Path script, java, config, osJar, toolsJar;

    @BeforeEach
    void setup() throws IOException {
        script = locate();
        config = Files.createDirectories(work.resolve("config"));
        osJar = Files.createFile(work.resolve("os.jar"));
        toolsJar = Files.createFile(work.resolve("ucp-nocode-tools.jar"));
        java = work.resolve("java");
        Files.writeString(java, "#!/bin/sh\necho " + MARKER + " \"$@\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 脚本位于仓库的 richuang-os/deploy：从当前目录与测试类所在位置逐级向上找，适配 surefire 与 JUnit Console 两种运行方式。 */
    private static Path locate() {
        var starts = new ArrayList<Path>();
        starts.add(Path.of("").toAbsolutePath());
        try {
            starts.add(
                    Path.of(
                            ObjectRuleMigrationScriptTest.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI()));
        } catch (Exception ignored) {
            // 取不到类位置时只按当前目录查找
        }
        for (var start : starts)
            for (var dir = start; dir != null; dir = dir.getParent()) {
                var candidate = dir.resolve("deploy/object-rule-migration.sh");
                if (Files.isRegularFile(candidate)) return candidate;
                candidate = dir.resolve("richuang-os/deploy/object-rule-migration.sh");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        throw new AssertionError("找不到 deploy/object-rule-migration.sh，起点：" + starts);
    }

    private record Result(int exit, String output) {}

    private Result run(int port, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", script.toString(), config.toString()));
        command.addAll(List.of(args));
        var builder =
                new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        var env = builder.environment();
        env.remove("ALLOW_RUNNING_SERVICE");
        env.put("JAVA", java.toString());
        env.put("OS_JAR", osJar.toString());
        env.put("TOOLS_JAR", toolsJar.toString());
        env.put("APP_PORT", Integer.toString(port));
        var process = builder.start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("脚本 60 秒内结束").isTrue();
        return new Result(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void writingSubcommandsRefusedWhileServiceListens() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = socket.getLocalPort();
            var readonly = run(port, "linkage-readonly", "--object-codes", "a", "--actor", "1");
            assertThat(readonly.exit()).as(readonly.output()).isEqualTo(65);
            assertThat(readonly.output()).doesNotContain(MARKER);
            var apply = run(port, "apply", "--report", "r.json", "--actor", "1");
            assertThat(apply.exit()).as(apply.output()).isEqualTo(65);
            assertThat(apply.output()).doesNotContain(MARKER);
        }
    }

    @Test
    void linkageReadOnlyDryRunSkipsStopCheck() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var result =
                    run(
                            socket.getLocalPort(),
                            "linkage-readonly",
                            "--object-codes",
                            "object_kjpzlr,object_sgfz",
                            "--actor",
                            "1",
                            "--dry-run");
            assertThat(result.exit()).as(result.output()).isZero();
            assertThat(result.output())
                    .contains(MARKER)
                    .contains("-Dloader.main=com.lingan.ucp.nocode.tools.ObjectRuleMigrationTool")
                    .contains("linkage-readonly --object-codes object_kjpzlr,object_sgfz");
        }
    }

    @Test
    void unknownSubcommandIsUsageError() throws Exception {
        var result = run(1, "linkage-readwrite", "--actor", "1");
        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.output()).contains("linkage-readonly").doesNotContain(MARKER);
    }
}
