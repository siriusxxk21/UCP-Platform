package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.tools.DataCenterFixtureCleaner.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 清单校验先于数据源启动，错误前缀、重复身份或任意改名均不能进入数据库清理阶段。 */
class DataCenterFixtureCleanerTest {
    private static final String BATCH = "e2efcmg123456";

    private OwnedObject source(String id, String table) {
        return new OwnedObject(id, BATCH + "_source", "表单验收字段转换 " + BATCH, table);
    }

    @Test
    void acceptsExactFixtureManifestAndPartialCreationWithoutApplication() {
        OwnedObject source = source("41", "biz_" + BATCH + "_source");
        assertThatCode(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(BATCH + "_", List.of(source), List.of())))
                .doesNotThrowAnyException();
        OwnedApplication app = new OwnedApplication("52", BATCH + "_seed", "字段转换写入夹具 " + BATCH);
        assertThatCode(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(BATCH + "_", List.of(source), List.of(app))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOnlyDeclaredModalAndGridFixtureShapes() {
        String modal = "e2efsmmg123456_";
        String grid = "e2eodmg123456_";
        OwnedObject modalSource =
                new OwnedObject("61", modal + "source", "表单验收字段弹窗", "biz_" + modal + "source");
        OwnedObject gridSource =
                new OwnedObject(
                        "62",
                        grid + "orders",
                        "表单验收数据维护订单 e2eodmg123456",
                        "biz_" + grid + "orders");
        assertThatCode(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(modal, List.of(modalSource), List.of())))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(grid, List.of(gridSource), List.of())))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(grid, List.of(modalSource), List.of())))
                .hasMessageContaining("编码");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(
                                                "demo_col_20260925_",
                                                List.of(gridSource),
                                                List.of())))
                .hasMessageContaining("前缀");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(
                                                modal,
                                                List.of(modalSource),
                                                List.of(
                                                        new OwnedApplication(
                                                                "63",
                                                                modal + "seed",
                                                                "字段转换写入夹具 e2efsmmg123456")))))
                .hasMessageContaining("不能含应用");
    }

    @Test
    void refusesGenericPrefixesForeignTablesDuplicatesAndUnexpectedApplicationNames() {
        OwnedObject source = source("41", "biz_" + BATCH + "_source");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest("test_", List.of(source), List.of())))
                .hasMessageContaining("前缀");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(
                                                BATCH + "_",
                                                List.of(source("41", "biz_user_table")),
                                                List.of())))
                .hasMessageContaining("表名");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(
                                                BATCH + "_", List.of(source, source), List.of())))
                .hasMessageContaining("重复");
        OwnedApplication app = new OwnedApplication("52", BATCH + "_seed", "用户应用");
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(BATCH + "_", List.of(source), List.of(app))))
                .hasMessageContaining("应用不属于");
    }

    @Test
    void acceptsExactUpgradePairAndRejectsDuplicatesOrMixedFixtures() {
        OwnedObject source = source("41", "biz_" + BATCH + "_source");
        OwnedApplication a = new OwnedApplication("51", BATCH + "_app_a", "字段升级应用 A " + BATCH);
        OwnedApplication b = new OwnedApplication("52", BATCH + "_app_b", "字段升级应用 B " + BATCH);
        assertThatCode(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(BATCH + "_", List.of(source), List.of(a, b))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(BATCH + "_", List.of(source), List.of(a, a))))
                .hasMessageContaining("重复");
        OwnedApplication seed = new OwnedApplication("53", BATCH + "_seed", "字段转换写入夹具 " + BATCH);
        assertThatThrownBy(
                        () ->
                                DataCenterFixtureCleaner.validateManifest(
                                        new Manifest(
                                                BATCH + "_", List.of(source), List.of(a, seed))))
                .hasMessageContaining("应用不属于");
    }
}
