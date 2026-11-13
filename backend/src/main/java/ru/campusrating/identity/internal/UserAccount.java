package ru.campusrating.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import ru.campusrating.identity.Role;

public record UserAccount(long id, String email, Role role, String fullName, @Nullable String passwordHash,
                    boolean mustChangePassword, @Nullable Long departmentId, boolean approved) {
    static String fullName(String lastName, String firstName, @Nullable String middleName) {
        return lastName + " " + firstName + (middleName == null ? "" : " " + middleName);
    }

    static String fullName(ResultSet rs) throws SQLException {
        return fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"));
    }
}
