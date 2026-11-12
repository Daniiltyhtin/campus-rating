package ru.campusrating.ledger.internal;

import java.time.Clock;
import java.time.Duration;
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
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.ledger.internal.SemesterRepository.Semester;
import ru.campusrating.settings.SettingsApi;

@Component
public class SemesterAccrualJob {
    private static final Logger LOG = LoggerFactory.getLogger(SemesterAccrualJob.class);
    private final SemesterRepository repository;
    private final LedgerApi ledger;
    private final SettingsApi settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    SemesterAccrualJob(SemesterRepository repository, LedgerApi ledger, SettingsApi settings,
                       Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.ledger = ledger;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void runOnce() {
        int nominal = settings.semesterAccrual();
        for (var semester : repository.started(clock.instant(), nominal)) {
            accrueSemester(semester, semester.accrual());
        }
    }

    private void accrueSemester(Semester semester, int nominal) {
        long afterStudentId = 0;
        while (true) {
            var students = repository.candidates(semester, afterStudentId);
            if (students.isEmpty()) {
                return;
            }
            for (long studentId : students) {
                try {
                    transaction.executeWithoutResult(status -> {
                        if (!accrueStudent(semester, studentId, nominal)) {
                            status.setRollbackOnly();
                        }
                    });
                } catch (Exception exception) {
                    LOG.error("Semester accrual failed for semester {} and student {}", semester.id(), studentId, exception);
                }
            }
            afterStudentId = students.getLast();
        }
    }

    private boolean accrueStudent(Semester semester, long studentId, int nominal) {
        if (nominal == 0) return repository.recordAccrual(semester.id(), studentId, 0, clock.instant());
        var posting = new Posting(studentId, Currency.AR, EntryKind.SEMESTER_ACCRUAL, nominal,
                new Source(SourceType.SEMESTER, semester.id(), semester.title()), null,
                "semester:" + semester.id() + ":" + studentId);
        var result = ledger.post(List.of(posting)).getFirst();
        int accrued = switch (result) {
            case PostingResult.Posted posted -> posted.amount();
            case PostingResult.AlreadyPosted posted -> posted.amount();
            case PostingResult.Zero ignored -> 0;
        };
        return repository.recordAccrual(semester.id(), studentId, accrued, clock.instant());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "campus.semester.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler implements SchedulingConfigurer {
        private final SemesterAccrualJob job;

        Scheduler(SemesterAccrualJob job) {
            this.job = job;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(job::runOnce, Duration.ofMinutes(1));
        }
    }
}
