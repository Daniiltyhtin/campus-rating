package ru.campusrating.ledger;

import org.jspecify.annotations.Nullable;

/** title — снимок на момент проводки. */
public record Source(SourceType type, @Nullable Long id, String title) {}
