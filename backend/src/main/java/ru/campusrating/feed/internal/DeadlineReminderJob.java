package ru.campusrating.feed.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import ru.campusrating.checkpoint.AssignmentDeadlines;
import ru.campusrating.feed.domain.UserEventKind;
import ru.campusrating.feed.internal.UserEventRepository.NewEvent;

@Component
public class DeadlineReminderJob {
    private static final Duration WINDOW = Duration.ofHours(24);
    private final AssignmentDeadlines deadlines;
    private final UserEventRepository repository;
    private final Clock clock;

    DeadlineReminderJob(AssignmentDeadlines deadlines, UserEventRepository repository, Clock clock) {
        this.deadlines = deadlines;
        this.repository = repository;
        this.clock = clock;
    }

    public void runOnce() {
        var now = clock.instant();
        for (var due : deadlines.inProgressDueBy(now, now.plus(WINDOW))) {
            repository.insert(new NewEvent(due.studentId(), UserEventKind.DEADLINE_REMINDER, "reminder24h:" + due.assignmentId(),
                    now, due.assignmentId(), due.checkpointTitle(), due.deadline(), null, null, null));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "campus.feed.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler implements SchedulingConfigurer {
        private final DeadlineReminderJob job;

        Scheduler(DeadlineReminderJob job) {
            this.job = job;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(job::runOnce, Duration.ofMinutes(1));
        }
    }
}
