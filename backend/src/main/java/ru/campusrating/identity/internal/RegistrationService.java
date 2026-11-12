package ru.campusrating.identity.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.identity.Role;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class RegistrationService {
    private final IdentityRepository repository;
    private final DirectoryApi directory;
    private final Passwords passwords;
    private final LedgerApi ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PasswordSetupService setup;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    RegistrationService(IdentityRepository repository, DirectoryApi directory, Passwords passwords, LedgerApi ledger,
                        ApplicationEventPublisher events, Clock clock,PasswordSetupService setup,org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.repository = repository; this.directory = directory; this.passwords = passwords;
        this.ledger = ledger; this.events = events; this.clock = clock;
        this.setup=setup;this.jdbc=jdbc;
    }

    @Transactional
    public long student(String email, String fullName, String password, long facultyId, long groupId) {
        return student(email,fullName,password,facultyId,groupId,false);
    }
    @Transactional
    public long importStudent(String email,String fullName,long facultyId,long groupId) {
        long id=student(email,fullName,new TemporaryPassword().generate(),facultyId,groupId,true);
        jdbc.sql("update app_user set password_hash=null where id=:id").param("id",id).update();
        return id;
    }
    private long student(String email,String fullName,String password,long facultyId,long groupId,boolean imported) {
        AdminImportService.lock(jdbc);
        if (repository.allowlisted(email)) throw new ApiException(ProblemCode.EMAIL_RESERVED_FOR_TEACHER, "Email зарезервирован для преподавателя");
        requireFreeEmail(email);
        var group = directory.group(groupId).orElseThrow(() -> Passwords.invalid("groupId", "INVALID", "Группа не найдена"));
        if (group.faculty().id() != facultyId) throw Passwords.invalid("groupId", "MISMATCH", "Группа не принадлежит факультету");
        long id = create(email, fullName, password, Role.STUDENT, null, false);
        var time = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (imported) {
            repository.addMembership(id, groupId, GroupMembershipState.ACTIVE, time, time);
            events.publishEvent(new MembershipActivated(id, groupId, time));
        } else {
            repository.addMembership(id, groupId, GroupMembershipState.PENDING, null, time);
        }
        ledger.post(List.of(initial(id)));
        if(!imported) setup.sendVerification(id,email);
        return id;
    }

    @Transactional
    public long teacher(String email, String fullName, String password, long departmentId) {
        AdminImportService.lock(jdbc);
        requireFreeEmail(email);
        if (directory.department(departmentId).isEmpty()) throw Passwords.invalid("departmentId", "INVALID", "Кафедра не найдена");
        long id=create(email, fullName, password, Role.TEACHER, departmentId, false);
        setup.sendVerification(id,email);return id;
    }

    @Transactional
    public void bootstrap(String email, String password) {
        if (!repository.hasAdmin()) {
            long id=create(email, "Администратор", password, Role.ADMIN, null, true);
            jdbc.sql("update app_user set email_verified=true where id=:id").param("id",id).update();
        }
    }

    private long create(String email, String fullName, String password, Role role, Long department, boolean change) {
        if (fullName.strip().isEmpty()) throw Passwords.invalid("fullName", "REQUIRED", "Укажите ФИО");
        if (email.strip().isEmpty()) throw Passwords.invalid("email", "REQUIRED", "Укажите email");
        requireFreeEmail(email);
        String hash = passwords.hash(password, "password");
        try {
            return repository.insert(email.strip().toLowerCase(Locale.ROOT), fullName.strip(), hash, role, department, change);
        } catch (DuplicateKeyException exception) {
            throw emailTaken();
        }
    }

    static Posting initial(long id) {
        return new Posting(id, Currency.AR, EntryKind.INITIAL, 1000,
                new Source(SourceType.REGISTRATION, null, "Регистрация"), null, "initial:" + id);
    }
    private void requireFreeEmail(String email) {
        if (repository.byEmail(email).isPresent()) throw emailTaken();
    }
    private ApiException emailTaken() { return new ApiException(ProblemCode.EMAIL_TAKEN, "Этот email уже зарегистрирован"); }
}
