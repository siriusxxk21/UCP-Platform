package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 不连接数据库的命令行边界：以当前迁移工具契约为准，拒绝旧参数，避免静默执行。 */
class ObjectRuleMigrationCommandTest {
    @Test
    void retiredSuspensionAndCleanupOptionsAreRejectedBeforeConnecting() {
        for (String[] args :
                new String[][] {
                    {"apply", "--suspend-applications", "17"},
                    {"rollback", "--suspend-applications", "17"},
                    {"dry-run", "--clear-option-defaults"},
                    {"apply", "--clear-option-defaults", "true", "--actor", "1"}
                }) {
            assertThatThrownBy(
                            () ->
                                    ObjectRuleMigrationTool.Command.parse(
                                            args, ObjectRuleMigrationTool.mapper()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(args[1]);
        }
    }

    @Test
    void previewNormalizesPrefixWithoutAnActorOrReport() {
        ObjectRuleMigrationTool.Command command =
                ObjectRuleMigrationTool.Command.parse(
                        new String[] {"dry-run", "--prefix", "DEMO_"},
                        ObjectRuleMigrationTool.mapper());
        assertThat(command.name()).isEqualTo("dry-run");
        assertThat(command.prefix()).isEqualTo("demo_");
        assertThat(command.report()).isNull();
        assertThat(command.actor()).isZero();
    }

    @Test
    void linkageReadonlyRequiresExplicitObjectCodesAndSupportsPreview() {
        ObjectRuleMigrationTool.Command command =
                ObjectRuleMigrationTool.Command.parse(
                        new String[] {
                            "linkage-readonly",
                            "--object-codes",
                            "Demo_A,demo_b,DEMO_A",
                            "--actor",
                            "1",
                            "--dry-run"
                        },
                        ObjectRuleMigrationTool.mapper());
        assertThat(command.objectCodes()).containsExactly("demo_a", "demo_b");
        assertThat(command.dryRun()).isTrue();
        assertThatThrownBy(
                        () ->
                                ObjectRuleMigrationTool.Command.parse(
                                        new String[] {"linkage-readonly", "--actor", "1"},
                                        ObjectRuleMigrationTool.mapper()))
                .hasMessageContaining("--object-codes");
    }
}
