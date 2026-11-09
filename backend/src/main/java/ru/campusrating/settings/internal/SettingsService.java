package ru.campusrating.settings.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.campusrating.settings.SettingsApi;

@Service
@EnableConfigurationProperties(SettingsDefaults.class)
public class SettingsService implements SettingsApi {
    private final SettingsStore store;
    private final SettingsDefaults defaults;

    public SettingsService(SettingsStore store, SettingsDefaults defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    @Override
    public int penaltyStep() {
        return store.find("penaltyStep").map(Integer::parseInt).orElse(defaults.penalty().defaultStep());
    }

    @Override
    public Duration penaltyInterval() {
        return store.find("penaltyInterval").map(Duration::parse).orElse(defaults.penalty().defaultInterval());
    }

    @Override
    public Duration penaltyPollPeriod() {
        return store.find("penaltyPollPeriod").map(Duration::parse).orElse(defaults.penalty().pollPeriod());
    }

    @Override
    public Duration minPenaltyInterval() {
        return penaltyPollPeriod().multipliedBy(2);
    }

    @Override
    public int semesterAccrual() {
        return store.find("semesterAccrual").map(Integer::parseInt).orElse(defaults.semester().accrual());
    }

    @Override
    public int monthlyBudgetLimit() {
        return store.find("monthlyBudgetLimit").map(Integer::parseInt).orElse(defaults.budget().monthlyLimit());
    }
}
