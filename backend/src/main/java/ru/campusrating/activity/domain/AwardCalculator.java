package ru.campusrating.activity.domain;

public final class AwardCalculator {
    private AwardCalculator() {}

    public static int amount(int maxPoints, ParticipationLevel level) {
        if (maxPoints < 2 || maxPoints > 10000) {
            throw new IllegalArgumentException("Максимум баллов: 2…10000");
        }
        return (maxPoints * level.percent() + 50) / 100;
    }
}
