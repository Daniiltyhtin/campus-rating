package ru.campusrating.activity.domain;

public record ParticipationLevel(int percent) {
    public ParticipationLevel {
        if (percent != 25 && percent != 50 && percent != 75 && percent != 100) {
            throw new IllegalArgumentException("Уровень участия: 25, 50, 75 или 100");
        }
    }
    public static ParticipationLevel of(int percent) { return new ParticipationLevel(percent); }
}
