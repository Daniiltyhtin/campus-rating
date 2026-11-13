package ru.campusrating.poll;

import java.time.Instant;
import java.util.List;

public record PollCreated(long pollId, String title, Instant endsAt, List<Long> studentIds, Instant occurredAt) {
    public PollCreated {
        studentIds = List.copyOf(studentIds);
    }
}
