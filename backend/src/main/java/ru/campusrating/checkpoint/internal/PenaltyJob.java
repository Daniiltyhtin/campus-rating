package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.checkpoint.domain.PenaltyPolicy;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.settings.SettingsApi;

@Component
public class PenaltyJob {
    private static final Logger log = LoggerFactory.getLogger(PenaltyJob.class);
    private final PenaltyRepository penalties;
    private final SubmissionRepository assignments;
    private final CheckpointRepository checkpoints;
    private final LedgerApi ledger;
    private final SettingsApi settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PenaltyJob(
            PenaltyRepository penalties,
            SubmissionRepository assignments,
            CheckpointRepository checkpoints,
            LedgerApi ledger,
            SettingsApi settings,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.penalties = penalties;
        this.assignments = assignments;
        this.checkpoints = checkpoints;
        this.ledger = ledger;
        this.settings = settings;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                this::runOnce,
                context -> {
                    var completed = context.lastCompletion();
                    var base = completed == null ? clock.instant() : completed;
                    return base.plus(settings.penaltyPollPeriod());
                });
    }

    public void runOnce() {
        for (long id : penalties.candidates(clock.instant())) {
            try {
                transaction.executeWithoutResult(ignored -> process(id));
            } catch (RuntimeException exception) {
                log.error("Failed to process penalty for assignment {}", id, exception);
            }
        }
    }

    private void process(long id) {
        var found = assignments.find(id, true);
        if (found.isEmpty()) {
            return;
        }
        var assignment = found.get();
        var checkpoint = checkpoints.find(assignment.checkpointId()).orElseThrow();
        var decision =
                PenaltyPolicy.calculate(
                        assignment.state(),
                        checkpoint.deadline(),
                        checkpoint.penaltyIntervalSeconds(),
                        checkpoint.penaltyStep(),
                        checkpoint.cost(),
                        assignment.charged(),
                        assignment.lastPenaltySlot(),
                        clock.instant());
        if (decision instanceof PenaltyPolicy.Charge charge) {
            charge(assignment, checkpoint, charge);
        }
    }

    private void charge(
            AssignmentData assignment, CheckpointData checkpoint, PenaltyPolicy.Charge charge) {
        var result =
                ledger.post(
                                List.of(
                                        new Posting(
                                                assignment.studentId(),
                                                Currency.AR,
                                                EntryKind.PENALTY,
                                                -charge.nominal(),
                                                new Source(
                                                        SourceType.CHECKPOINT,
                                                        checkpoint.id(),
                                                        checkpoint.title()),
                                                null,
                                                "penalty:"
                                                        + assignment.id()
                                                        + ":"
                                                        + charge.slot())))
                        .getFirst();
        int amount =
                switch (result) {
                    case PostingResult.Posted posted -> -posted.amount();
                    case PostingResult.Zero ignored -> 0;
                    case PostingResult.AlreadyPosted ignored -> 0;
                };
        penalties.processed(assignment.id(), assignment.charged() + amount, charge.slot());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "campus.penalty.scheduler.enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfiguration implements SchedulingConfigurer {
        private final PenaltyJob job;

        SchedulingConfiguration(PenaltyJob job) {
            this.job = job;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            job.configureTasks(registrar);
        }
    }
}
