package ru.campusrating.feed.internal;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
public class IncompletePublicationsJob {
    private static final Duration MIN_AGE = Duration.ofMinutes(1);
    private final IncompleteEventPublications publications;

    IncompletePublicationsJob(IncompleteEventPublications publications) {
        this.publications = publications;
    }

    public void runOnce() {
        publications.resubmitIncompletePublicationsOlderThan(MIN_AGE);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "campus.feed.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler implements SchedulingConfigurer {
        private final IncompletePublicationsJob job;

        Scheduler(IncompletePublicationsJob job) {
            this.job = job;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(job::runOnce, Duration.ofMinutes(1));
        }
    }
}
