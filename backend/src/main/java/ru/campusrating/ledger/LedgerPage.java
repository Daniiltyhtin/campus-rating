package ru.campusrating.ledger;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record LedgerPage<T>(List<T> items, @Nullable String nextCursor) {}
