package ru.campusrating.settings;

import java.time.Duration;

public interface SettingsApi {
    int penaltyStep();
    Duration penaltyInterval();
    Duration penaltyPollPeriod();
    Duration minPenaltyInterval();
    int semesterAccrual();
    int monthlyBudgetLimit();
}
