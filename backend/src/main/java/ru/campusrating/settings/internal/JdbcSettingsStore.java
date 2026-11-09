package ru.campusrating.settings.internal;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSettingsStore implements SettingsStore {
    private final JdbcClient jdbc;

    JdbcSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(String key) {
        return jdbc.sql("select value from settings where key = :key")
                .param("key", key).query(String.class).optional();
    }
}
