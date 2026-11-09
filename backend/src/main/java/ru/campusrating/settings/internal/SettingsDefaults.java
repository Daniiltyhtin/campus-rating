package ru.campusrating.settings.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("campus")
public record SettingsDefaults(Penalty penalty, Semester semester, Budget budget) {
    public record Penalty(int defaultStep, Duration defaultInterval, Duration pollPeriod) {}
    public record Semester(int accrual) {}
    public record Budget(int monthlyLimit) {}
}
