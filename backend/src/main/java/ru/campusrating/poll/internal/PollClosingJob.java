package ru.campusrating.poll.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.poll.PollClosed;

@Component
public class PollClosingJob {
    private final PollRepository repository;
    private final PollViews views;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    PollClosingJob(PollRepository repository, PollViews views, ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.views = views;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void runOnce() {
        var now = clock.instant();
        for (var poll : repository.lockDue(now)) {
            int eligible = views.eligible(views.groupIds(poll.id())).size();
            repository.close(poll.id(), now);
            publisher.publishEvent(new PollClosed(poll.id(), poll.title(), poll.authorId(), repository.participants(poll.id()),
                    eligible, now));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "campus.poll.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler implements SchedulingConfigurer {
        private final PollClosingJob job;

        Scheduler(PollClosingJob job) {
            this.job = job;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(job::runOnce, Duration.ofMinutes(1));
        }
    }
}
