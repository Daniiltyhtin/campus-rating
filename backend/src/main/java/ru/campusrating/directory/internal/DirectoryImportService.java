package ru.campusrating.directory.internal;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.directory.*;

@Service
class DirectoryImportService implements DirectoryImport {
    private final JdbcClient jdbc;private final DirectoryQueries directory;
    DirectoryImportService(JdbcClient jdbc,DirectoryQueries directory) {this.jdbc=jdbc;this.directory=directory;}
    public Optional<FacultyInfo> facultyByCode(String code) {
        return jdbc.sql("select id from faculty where code=:code").param("code",code).query(Long.class).optional().flatMap(directory::faculty);
    }
    public Optional<DepartmentInfo> departmentByCode(String code) {
        return jdbc.sql("select id from department where code=:code").param("code",code).query(Long.class).optional().flatMap(directory::department);
    }
    public Optional<ImportedGroup> groupByCode(String code) {
        return jdbc.sql("select id,faculty_id,code,name,admission_year from study_group where code=:code").param("code",code)
                .query((rs,n)->new ImportedGroup(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5))).optional();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void createGroup(long facultyId,String code,String name,int admissionYear) {
        jdbc.sql("insert into study_group(faculty_id,code,name,admission_year) values(:faculty,:code,:name,:year)")
                .param("faculty",facultyId).param("code",code).param("name",name).param("year",admissionYear).update();
    }
}
