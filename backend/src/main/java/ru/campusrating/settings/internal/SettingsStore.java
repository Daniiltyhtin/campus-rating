package ru.campusrating.settings.internal;

import java.util.Optional;

@FunctionalInterface
public interface SettingsStore {
    Optional<String> find(String key);
}
