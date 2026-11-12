package ru.campusrating.identity;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import ru.campusrating.support.AdminHttpTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTeacherSessionExpiryIT extends AdminHttpTestSupport {
    @Autowired
    SessionRegistry sessions;

    @Test
    void rejectionExpiresEveryRegisteredSessionOfTheTeacher() {
        long id = teacher("expiring", "Expiring");
        login("expiring");
        login("expiring");
        assertThat(active(email("expiring"))).hasSize(2);

        request(admin, "DELETE", teacherPath(id) + "/application", null, 204);

        assertThat(active(email("expiring"))).isEmpty();
    }

    private List<SessionInformation> active(String email) {
        return sessions.getAllPrincipals().stream()
                .filter(principal -> principal instanceof UserDetails user && user.getUsername().equals(email))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .toList();
    }
}
