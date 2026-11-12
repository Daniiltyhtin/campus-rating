package ru.campusrating.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistry;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.TeacherScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AdminTeacherListQueriesTest {
    @Test
    void listLoadsTeachersAndDepartmentsInBatchesKeepingOrder() {
        var repository = mock(AdminTeacherRepository.class);
        var users = mock(IdentityRepository.class);
        var directory = mock(DirectoryApi.class);
        var faculty = new FacultyInfo(1, "ФИТ", "Факультет информационных технологий");
        var first = new DepartmentInfo(101, "ПИ", "Кафедра программной инженерии", faculty);
        var second = new DepartmentInfo(102, "ИС", "Кафедра информационных систем", faculty);
        when(repository.teachers()).thenReturn(List.of(3L, 1L, 2L));
        when(users.users(List.of(3L, 1L, 2L))).thenReturn(List.of(
                new UserAccount(1, "a@campus.test", Role.TEACHER, "Антонов", null, false, 101L, true),
                new UserAccount(2, "b@campus.test", Role.TEACHER, "Борисов", null, false, 102L, false),
                new UserAccount(3, "c@campus.test", Role.TEACHER, "Волков", null, false, 101L, true)));
        when(directory.departments(any())).thenReturn(Map.of(101L, first, 102L, second));
        var service = new AdminTeacherService(repository, users, directory, mock(TeacherScope.class),
                mock(SessionRegistry.class), mock(CurrentUser.class), Clock.systemUTC());

        var teachers = service.list();

        assertThat(teachers).extracting(teacher -> teacher.id()).containsExactly(3L, 1L, 2L);
        assertThat(teachers).extracting(teacher -> teacher.department().id()).containsExactly(101L, 101L, 102L);
        assertThat(teachers).extracting(teacher -> teacher.teacherApproved()).containsExactly(true, true, false);
        verify(users, times(1)).users(any());
        verify(users, never()).byId(anyLong());
        verify(directory, times(1)).departments(any());
        verify(directory, never()).department(anyLong());
    }
}
