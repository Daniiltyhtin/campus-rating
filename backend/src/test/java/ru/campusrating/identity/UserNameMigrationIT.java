package ru.campusrating.identity;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.support.NameMigrationDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserNameMigrationIT {
    @TempDir Path temporary;

    @ParameterizedTest(name = "Covers {0}: {1}")
    @CsvSource(value = {
        "AE6|Смирнова Анна Олеговна|Смирнова|Анна|Олеговна",
        "AE7|Оглы Мамед Али Оглы|Оглы|Мамед|Али Оглы",
        "AE8|Тест|Тест|Тест|NULL",
        "AE9a|'  Иванов   Иван  Иванович '|Иванов|Иван|Иванович"
    }, delimiter = '|', nullValues = "NULL")
    void splitsExistingNames(String ae, String old, String last, String first, String middle) throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.update("default");
            long id = db.jdbc.queryForObject("insert into app_user(email,role,full_name) values ('migration@example.test','STUDENT',?) returning id", Long.class, old);
            db.includeLate(); db.update("default");
            assertThat(db.jdbc.queryForObject("select last_name from app_user where id=?", String.class, id)).isEqualTo(last);
            assertThat(db.jdbc.queryForObject("select first_name from app_user where id=?", String.class, id)).isEqualTo(first);
            assertThat(db.jdbc.queryForObject("select middle_name from app_user where id=?", String.class, id)).isEqualTo(middle);
            assertThat(db.jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
            assertThat(db.jdbc.queryForList("select column_name from information_schema.columns where table_name='app_user'", String.class)).doesNotContain("full_name");
        }
    }

    @Test @DisplayName("Covers AE9a: tabs and newlines normalize to single spaces")
    void splitsAllWhitespace() throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.update("default");
            db.jdbc.update("insert into app_user(email,role,full_name) values ('spaces@example.test','STUDENT',?)", " \tИванов\nИван\t Иванович\r ");
            db.includeLate(); db.update("default");
            assertThat(db.jdbc.queryForObject("select concat_ws(' ',last_name,first_name,middle_name) from app_user", String.class)).isEqualTo("Иванов Иван Иванович");
        }
    }

    @ParameterizedTest(name = "Covers AE9b: invalid legacy name {0} rolls back DDL and data")
    @ValueSource(strings = {"last", "first", "middle", "empty", "whitespace"})
    void invalidDataRollsBackWholeChangeset(String kind) throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.update("default");
            String old = switch (kind) {
                case "last" -> "Я".repeat(101) + " Иван";
                case "first" -> "Иванов " + "Я".repeat(101);
                case "middle" -> "Иванов Иван " + "Я".repeat(101);
                case "empty" -> "";
                default -> " \t\n ";
            };
            db.jdbc.update("insert into app_user(email,role,full_name) values ('valid@example.test','STUDENT','Смирнова Анна Олеговна'),('invalid@example.test','STUDENT',?)", old);
            var users = db.jdbc.queryForList("select * from app_user order by id");
            var columns = db.jdbc.queryForList("select column_name,data_type,is_nullable from information_schema.columns where table_name='app_user' order by ordinal_position");
            var history = db.jdbc.queryForList("select * from databasechangelog order by orderexecuted");
            db.includeLate();
            assertThatThrownBy(() -> db.update("default")).isInstanceOf(Exception.class);
            assertThat(db.jdbc.queryForList("select * from app_user order by id")).isEqualTo(users);
            assertThat(db.jdbc.queryForList("select column_name,data_type,is_nullable from information_schema.columns where table_name='app_user' order by ordinal_position")).isEqualTo(columns);
            assertThat(db.jdbc.queryForList("select * from databasechangelog order by orderexecuted")).isEqualTo(history);
        }
    }

    @Test @DisplayName("Covers AE9: demo upgrade preserves every name and count, retry is unchanged")
    void demoUpgradeAndRetry() throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.update("default,demo");
            var names = db.jdbc.queryForList("select id,full_name as name from app_user order by id");
            assertThat(names).isNotEmpty();
            var snapshots = db.jdbc.queryForList("select id,student_full_name from assignment order by id");
            db.includeLate(); db.update("default,demo");
            assertThat(db.jdbc.queryForList("select id,concat_ws(' ',last_name,first_name,middle_name) as name from app_user order by id")).isEqualTo(names);
            assertThat(db.jdbc.queryForObject("select count(*) from app_user where last_name is null or first_name is null or btrim(last_name)='' or btrim(first_name)=''", Integer.class)).isZero();
            assertThat(db.jdbc.queryForList("select id,student_full_name from assignment order by id")).isEqualTo(snapshots);
            var users = db.jdbc.queryForList("select * from app_user order by id");
            var history = db.jdbc.queryForList("select * from databasechangelog order by orderexecuted");
            db.update("default,demo");
            assertThat(db.jdbc.queryForList("select * from app_user order by id")).isEqualTo(users);
            assertThat(db.jdbc.queryForList("select * from databasechangelog order by orderexecuted")).isEqualTo(history);
        }
    }

    @Test @DisplayName("Covers AE9: clean default,demo applies seeds before name migration")
    void cleanDemoDatabase() throws Exception {
        try (var baseline = new NameMigrationDatabase(temporary.resolve("before"));
                var db = new NameMigrationDatabase(temporary.resolve("after"))) {
            baseline.update("default,demo");
            var names = baseline.jdbc.queryForList("select id,full_name as name from app_user order by id");
            assertThat(names).isNotEmpty();
            db.includeLate(); db.update("default,demo");
            assertThat(db.jdbc.queryForList("select id,concat_ws(' ',last_name,first_name,middle_name) as name from app_user order by id")).isEqualTo(names);
            assertThat(db.jdbc.queryForObject("select concat_ws(' ',last_name,first_name,middle_name) from app_user where email='admin@campus.test'", String.class)).isEqualTo("Администратор Демо");
            assertThat(db.jdbc.queryForObject("select count(*) from app_user where btrim(last_name)='' or btrim(first_name)=''", Integer.class)).isZero();
        }
    }

    @Test @DisplayName("Covers AE10: historical assignment snapshot stays byte for byte")
    void historicalSnapshot() throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.update("default,demo");
            assertThat(db.jdbc.queryForObject("select count(*) from assignment", Integer.class)).isPositive();
            db.jdbc.update("update assignment set student_full_name=?", "  Смирнова  Анна Олеговна ");
            var before = db.jdbc.queryForList("select id,student_full_name from assignment order by id");
            db.includeLate(); db.update("default,demo");
            assertThat(db.jdbc.queryForList("select id,student_full_name from assignment order by id")).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "Covers AE5, KTD2: database rejects invalid {0}")
    @ValueSource(strings = {"last_name", "first_name", "middle_name"})
    void databaseConstraints(String column) throws Exception {
        try (var db = new NameMigrationDatabase(temporary)) {
            db.includeLate(); db.update("default");
            db.jdbc.update("insert into app_user(email,role,last_name,first_name) values ('valid@example.test','STUDENT','Иванов','Иван')");
            for (String invalid : List.of("", " ", "Я".repeat(101), " Иван ")) {
                assertThatThrownBy(() -> db.jdbc.update("update app_user set " + column + "=?", invalid)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
            if (!column.equals("middle_name")) assertThatThrownBy(() -> db.jdbc.update("update app_user set " + column + "=null")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
