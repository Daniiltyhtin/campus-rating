package ru.campusrating.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Comparator;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.GroupInfo;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.api.AuthDtos.*;
import ru.campusrating.identity.internal.GroupHeads;
import ru.campusrating.identity.internal.MembershipApproval;
import ru.campusrating.identity.internal.RegistrationService;
import ru.campusrating.identity.internal.SessionService;
import ru.campusrating.identity.internal.UserAccount;
import ru.campusrating.shared.mail.MailModeProperties;

@RestController
@RequestMapping("/api/v1")
class AuthController {
    private final RegistrationService registration;
    private final SessionService sessions;
    private final CurrentUser current;
    private final GroupMembers members;
    private final TeacherScope scope;
    private final DirectoryApi directory;
    private final Environment environment;
    private final MailModeProperties mail;
    private final MembershipApproval approval;
    private final GroupHeads heads;
    AuthController(RegistrationService registration, SessionService sessions, CurrentUser current, GroupMembers members,
                   TeacherScope scope, DirectoryApi directory, Environment environment, MailModeProperties mail,
                   MembershipApproval approval, GroupHeads heads) {
        this.registration = registration; this.sessions = sessions; this.current = current; this.members = members;
        this.scope = scope; this.directory = directory; this.environment = environment; this.mail = mail;
        this.approval = approval; this.heads = heads;
    }
    @GetMapping("/auth/csrf") @ResponseStatus(HttpStatus.NO_CONTENT)
    void getCsrfToken(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) { token.getToken(); }
    @GetMapping("/auth/config") AuthConfig getAuthConfig() {
        return new AuthConfig(environment.matchesProfiles("demo"), mail.emailVerification());
    }
    @PostMapping("/auth/register/student") @ResponseStatus(HttpStatus.CREATED)
    RegistrationResult registerStudent(@Valid @RequestBody StudentRegistration body) {
        return new RegistrationResult(registration.student(body.email(), body.fullName(), body.password(), body.facultyId(), body.groupId()), nextStep());
    }
    @PostMapping("/auth/register/teacher") @ResponseStatus(HttpStatus.CREATED)
    RegistrationResult registerTeacher(@Valid @RequestBody TeacherRegistration body) {
        return new RegistrationResult(registration.teacher(body.email(), body.fullName(), body.password(), body.departmentId()), nextStep());
    }
    @PostMapping("/auth/login") SessionView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        sessions.login(body.email(), body.password(), request, response);
        return view(sessions.account(current.require().id()));
    }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) { sessions.logout(request, response); }
    @GetMapping("/auth/me") SessionView getCurrentUser() { return view(sessions.account(current.require().id())); }
    @PostMapping("/auth/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody PasswordChange body, HttpServletRequest request, HttpServletResponse response) {
        long id = current.require().id();
        sessions.changePassword(id, body.currentPassword(), body.newPassword());
        sessions.expireOthers(id, request, response);
    }
    @PutMapping("/me/membership") SessionView requestMembership(@Valid @RequestBody MembershipRequest body) {
        approval.reapply(body.facultyId(), body.groupId());
        return view(sessions.account(current.require().id()));
    }
    @GetMapping("/me/accessible-groups") StudyGroupList listAccessibleGroups() {
        var groups = directory.groups(scope.accessibleGroupIds(current.require().id())).values().stream()
                .sorted(Comparator.comparing((GroupInfo group) -> group.faculty().name()).thenComparing(GroupInfo::name).thenComparingLong(GroupInfo::id))
                .map(group -> new StudyGroupRef(group.id(), group.name(), group.faculty())).toList();
        return new StudyGroupList(groups);
    }
    private NextStep nextStep() {return mail.emailVerification()==MailModeProperties.EmailVerification.REQUIRED?NextStep.CONFIRM_EMAIL:NextStep.LOGIN;}
    private SessionView view(UserAccount user) {
        return switch (user.role()) {
            case ADMIN -> new CurrentAdmin(user.id(), user.fullName(), user.email(), user.mustChangePassword());
            case TEACHER -> new CurrentTeacher(user.id(), user.fullName(), user.email(), user.mustChangePassword(),
                    directory.department(user.departmentId()).orElseThrow(), user.approved());
            case STUDENT -> {
                var membership = members.membership(user.id()).orElseThrow();
                var group = directory.group(membership.groupId()).orElseThrow();
                yield new CurrentStudent(user.id(), user.fullName(), user.email(), user.mustChangePassword(),
                        new StudyGroupRef(group.id(), group.name(), group.faculty()), membership.state(), heads.isHead(user.id()));
            }
        };
    }
}
