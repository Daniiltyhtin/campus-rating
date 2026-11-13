package ru.campusrating.ledger;

import java.time.LocalDate;

public record ArDayChange(long studentId, LocalDate date, int amount) {}
