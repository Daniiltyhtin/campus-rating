package ru.campusrating.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.identity.internal.AdminImportService;
import ru.campusrating.identity.internal.ImportFileReader.Kind;

@RestController
public class AdminImportController {
    private final AdminImportService service;
    public AdminImportController(AdminImportService service) {this.service=service;}
    @PostMapping(value="/api/v1/admin/import/{kind}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport importAdminFile(@PathVariable Kind kind,@RequestPart MultipartFile file) {
        try {return service.importFile(kind,file);}
        catch(org.springframework.dao.DataAccessException e) {throw new ru.campusrating.shared.error.ApiException(ru.campusrating.shared.error.ProblemCode.INTERNAL_ERROR,"Импорт не выполнен, попробуйте ещё раз");}
    }
    @GetMapping("/api/v1/admin/teacher-allowlist") public TeacherAllowlistList listTeacherAllowlist() {return new TeacherAllowlistList(service.allowlist());}
    @DeleteMapping("/api/v1/admin/teacher-allowlist") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeacherAllowlistEntry(@RequestParam String email) {service.deleteAllowlist(email);}
    @Schema(requiredProperties={"row","key","reason"}) public record ImportRejection(int row,String key,String reason) {}
    @Schema(requiredProperties={"created","skipped","rejected"}) public record ImportReport(int created,int skipped,List<ImportRejection> rejected) {}
    @Schema(requiredProperties={"email","department","registered"}) public record TeacherAllowlistEntry(String email,DepartmentInfo department,boolean registered) {}
    @Schema(requiredProperties="items") public record TeacherAllowlistList(List<TeacherAllowlistEntry> items) {}
}
