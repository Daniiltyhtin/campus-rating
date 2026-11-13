package ru.campusrating.poll;

import java.time.Instant;

public record PollClosed(long pollId, String title, long authorId, int voted, int eligible, Instant occurredAt) {}
