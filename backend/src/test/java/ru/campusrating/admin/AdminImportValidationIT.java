package ru.campusrating.admin;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.U13HttpTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class AdminImportValidationIT extends U13HttpTestSupport {
    @Test void semanticErrorInEarlierColumnPrecedesEmptyLaterColumn() {
        var csv="email,full_name,faculty_code,group_code\nbad-email,,missing,missing\n";
        var result=adminWire().upload("/api/v1/admin/import/STUDENTS","students.csv",csv.getBytes(StandardCharsets.UTF_8),200);
        assertThat(result.at("/rejected/0/reason").asText()).isEqualTo("неверный email");
    }
}
