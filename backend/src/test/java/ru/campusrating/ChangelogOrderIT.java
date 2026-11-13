package ru.campusrating;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogOrderIT extends PostgresIntegrationTest {
    private static final String EARLY = "202611070900-early.sql";
    private static final String LATE = "202611071000-late.sql";
    private static final String DEMO = "202611071100-demo.sql";

    @Autowired
    private JdbcConnectionDetails connectionDetails;

    @Autowired
    private JdbcTemplate admin;

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appliesBranchesInEitherMergeOrderAndFiltersDemo(boolean lateFirst) throws Exception {
        var databaseName = lateFirst ? "u2_order_late_first" : "u2_order_early_first";
        admin.execute("drop database if exists " + databaseName);
        admin.execute("create database " + databaseName);
        try {
            var baseUrl = connectionDetails.getJdbcUrl();
            var url = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + databaseName;
            var source = new DriverManagerDataSource(url,
                    connectionDetails.getUsername(), connectionDetails.getPassword());
            var jdbc = new JdbcTemplate(source);
            copyRealChangelog();

            var first = lateFirst ? LATE : EARLY;
            var second = lateFirst ? EARLY : LATE;
            addFixture(first);
            addFixture(DEMO);
            update(url, "default");
            assertTable(jdbc, lateFirst ? "u2_order_late" : "u2_order_early", true);
            assertTable(jdbc, lateFirst ? "u2_order_early" : "u2_order_late", false);
            assertTable(jdbc, "u2_order_demo", false);
            assertChangesetCount(jdbc, lateFirst ? "late" : "early", 1);
            assertChangesetCount(jdbc, "demo", 0);

            addFixture(second);
            update(url, "default");
            assertTable(jdbc, "u2_order_early", true);
            assertTable(jdbc, "u2_order_late", true);
            assertChangesetCount(jdbc, "early", 1);
            assertChangesetCount(jdbc, "late", 1);

            var history = jdbc.queryForList("select * from databasechangelog order by orderexecuted");
            update(url, "default");
            assertThat(jdbc.queryForList("select * from databasechangelog order by orderexecuted"))
                    .isEqualTo(history);
            assertTable(jdbc, "u2_order_demo", false);
            assertChangesetCount(jdbc, "demo", 0);
        } finally {
            admin.execute("drop database if exists " + databaseName + " with (force)");
        }
    }

    @org.junit.jupiter.api.Test
    void cleanDemoRunsBothBranchesAndDemoExactlyOnce() throws Exception {
        try (var db = new ru.campusrating.support.NameMigrationDatabase(temporary)) {
            addFixture(EARLY);
            addFixture(LATE);
            addFixture(DEMO);
            db.includeLate();
            db.update("default,demo");
            assertTable(db.jdbc, "u2_order_demo", true);
            assertTable(db.jdbc, "u2_order_early", true);
            assertTable(db.jdbc, "u2_order_late", true);
            assertChangesetCount(db.jdbc, "demo", 1);
            assertChangesetCount(db.jdbc, "early", 1);
            assertChangesetCount(db.jdbc, "late", 1);
        }
    }

    private void copyRealChangelog() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:db/changelog/**/*")) {
            if (!resource.isReadable()) {
                continue;
            }
            var location = resource.getURL().toString();
            var relative = location.substring(location.lastIndexOf("db/changelog/") + "db/changelog/".length());
            var target = temporary.resolve(relative);
            Files.createDirectories(target.getParent());
            try (var input = resource.getInputStream()) {
                Files.copy(input, target);
            }
        }
        assertThat(temporary.resolve("db.changelog-master.yaml")).isRegularFile();
    }

    private void addFixture(String name) throws Exception {
        // A real module directory proves that the production master includes new files.
        var target = temporary.resolve("settings").resolve(name);
        Files.createDirectories(target.getParent());
        try (var input = getClass().getResourceAsStream("/changelog-order/" + name)) {
            Files.copy(input, target);
        }
    }

    private void update(String url, String contexts) throws Exception {
        try (var connection = DriverManager.getConnection(url,
                connectionDetails.getUsername(), connectionDetails.getPassword());
                var resources = new DirectoryResourceAccessor(temporary)) {
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db.changelog-master.yaml", resources, database)) {
                liquibase.update(new Contexts(contexts), new LabelExpression());
            }
        }
    }

    private void assertTable(JdbcTemplate jdbc, String table, boolean exists) {
        assertThat(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table))
                .as(table).isEqualTo(exists);
    }

    private void assertChangesetCount(JdbcTemplate jdbc, String id, int count) {
        assertThat(jdbc.queryForObject(
                "select count(*) from databasechangelog where author = 'u2' and id = ?", Integer.class, id))
                .as("changeset u2:%s", id).isEqualTo(count);
    }
}
