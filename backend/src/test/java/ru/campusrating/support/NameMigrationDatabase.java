package ru.campusrating.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class NameMigrationDatabase implements AutoCloseable {
    private final PostgreSQLContainer database = new PostgreSQLContainer("postgres:18.6-alpine");
    private final Path changelog;
    public final JdbcTemplate jdbc;

    public NameMigrationDatabase(Path changelog) throws Exception {
        this.changelog = changelog;
        database.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        copy(false);
    }

    private void copy(boolean late) throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:db/changelog/**/*")) {
            if (!resource.isReadable()) continue;
            var location = resource.getURL().toString();
            var relative = location.substring(location.lastIndexOf("db/changelog/") + "db/changelog/".length());
            if (relative.startsWith("late/") != late) continue;
            var target = changelog.resolve(relative);
            Files.createDirectories(target.getParent());
            try (var input = resource.getInputStream()) { Files.copy(input, target); }
        }
    }

    public void includeLate() throws Exception {
        copy(true);
    }

    public void update(String contexts) throws Exception {
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                var resources = new DirectoryResourceAccessor(changelog)) {
            var postgres = new PostgresDatabase();
            postgres.setConnection(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db.changelog-master.yaml", resources, postgres)) {
                liquibase.update(new Contexts(contexts), new LabelExpression());
            }
        }
    }

    @Override public void close() { database.close(); }
}
