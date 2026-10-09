package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/** V075 在当前开发库的事务临时表验证；不改用户数据或真实迁移历史。 */
class TaskTemplatePrimaryVersionMigrationTest {
    private static ConfigurableApplicationContext context;
    private static String migration;
    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void open() throws Exception {
        migration =
                new ClassPathResource("db/nocode/V075__task_template_primary_version.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.nocode_task_template", "pg_temp.nocode_task_template");
        assertThat(migration).doesNotContain("public.");
        context = NocodeToolContext.open();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void fixture() throws Exception {
        connection = context.getBean(DataSource.class).getConnection();
        connection.setAutoCommit(false);
        statement = connection.createStatement();
        statement.execute(
                "CREATE TEMP TABLE nocode_task_template (id integer PRIMARY KEY,"
                        + " published_version integer, draft_json jsonb) ON COMMIT DROP");
        statement.execute(
                "INSERT INTO pg_temp.nocode_task_template VALUES"
                        + " (1,5,'{\"keep\":true}'), (2,NULL,'{}'), (3,3,'{}')");
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                if (statement != null) statement.close();
                connection.close();
            }
        }
    }

    @Test
    void newInstallationBackfillsOnlyPublishedPointers() throws Exception {
        statement.execute(migration);
        assertThat(values()).containsExactly("1:5", "2:null", "3:3");
        try (ResultSet rows =
                statement.executeQuery(
                        "SELECT draft_json->>'keep' FROM pg_temp.nocode_task_template WHERE"
                                + " id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("true");
        }
    }

    @Test
    void legacyInstallationPreservesHistoricalAndNullSelections() throws Exception {
        statement.execute(migration);
        statement.execute(
                "UPDATE pg_temp.nocode_task_template SET primary_version=CASE WHEN id=1 THEN 2"
                        + " END");
        List<String> before = values();
        statement.execute(migration);
        assertThat(values()).isEqualTo(before).containsExactly("1:2", "2:null", "3:null");
    }

    @Test
    void rejectsUnexpectedColumnType() throws Exception {
        statement.execute(
                "ALTER TABLE pg_temp.nocode_task_template ADD COLUMN primary_version text");
        rejectsDrift();
    }

    @Test
    void rejectsMissingConstraint() throws Exception {
        statement.execute(
                "ALTER TABLE pg_temp.nocode_task_template ADD COLUMN primary_version integer");
        rejectsDrift();
    }

    @Test
    void rejectsWeakerConstraint() throws Exception {
        statement.execute(
                "ALTER TABLE pg_temp.nocode_task_template ADD COLUMN primary_version integer,"
                        + " ADD CONSTRAINT nocode_task_template_primary_version_ck CHECK"
                        + " (primary_version > 0)");
        rejectsDrift();
    }

    @Test
    void rejectsUnexpectedDefault() throws Exception {
        statement.execute(migration);
        statement.execute(
                "ALTER TABLE pg_temp.nocode_task_template ALTER COLUMN primary_version SET DEFAULT"
                        + " 1");
        rejectsDrift();
    }

    @Test
    void rejectsPrimaryVersionBeyondPublishedVersion() throws Exception {
        statement.execute(migration);
        assertThatThrownBy(
                        () ->
                                statement.execute(
                                        "UPDATE pg_temp.nocode_task_template SET primary_version=6"
                                                + " WHERE id=1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("nocode_task_template_primary_version_ck");
    }

    private void rejectsDrift() {
        assertThatThrownBy(() -> statement.execute(migration))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unexpected task primary-version structure");
    }

    private List<String> values() throws Exception {
        List<String> result = new ArrayList<>();
        try (ResultSet rows =
                statement.executeQuery(
                        "SELECT id,primary_version FROM pg_temp.nocode_task_template ORDER BY"
                                + " id")) {
            while (rows.next()) {
                result.add(rows.getInt(1) + ":" + rows.getString(2));
            }
        }
        return result;
    }
}
