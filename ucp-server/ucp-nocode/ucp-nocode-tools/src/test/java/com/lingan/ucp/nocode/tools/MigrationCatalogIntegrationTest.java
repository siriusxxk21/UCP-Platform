package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;

/** 目录迁移必须保留打包内容和当前库历史；本测试不执行任何迁移。 */
class MigrationCatalogIntegrationTest {
    @Test
    void packagedMigrationsKeepSealedBytesAndLegacyClasspath() throws Exception {
        var lock =
                new ObjectMapper()
                        .readTree(
                                new ClassPathResource("db/migrations.lock.json")
                                        .getContentAsByteArray());
        var packaged =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/nocode/V*__*.sql");
        // 当前发布集合全部封存，重复资源或漏打包都应阻止交付。
        assertThat(packaged).hasSize(lock.path("migrations").size());
        for (var entry : lock.path("migrations")) {
            var bytes =
                    new ClassPathResource("db/nocode/" + entry.path("file").asText())
                            .getContentAsByteArray();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(entry.path("sha256").asText());
        }
    }

    @Test
    void currentHistoryValidatesWithoutReplayingOrChangingRecords() {
        try (var context = NocodeToolContext.open()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var before =
                    jdbc.queryForList(
                            "SELECT * FROM public.nocode_schema_history ORDER BY installed_rank");
            var flyway = context.getBean(NocodeDatabaseTool.class).flyway();
            flyway.validate();
            assertThat(flyway.info().pending()).isEmpty();
            assertThat(
                            jdbc.queryForList(
                                    "SELECT * FROM public.nocode_schema_history ORDER BY"
                                            + " installed_rank"))
                    .isEqualTo(before);
        }
    }
}
