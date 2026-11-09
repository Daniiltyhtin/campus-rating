package ru.campusrating.shared.error;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.*;

class ApiExceptionHandlerTest {
    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.create(org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ErrorsController()).setControllerAdvice(new ApiExceptionHandler()).build());
    }

    @Test
    void domainErrorUsesProblemDetailAndInstance() {
        mvc.get().uri("/errors/not-found").assertThat().hasStatus(404)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"type":"about:blank","title":"Not Found","status":404,
                         "detail":"Ресурс не найден","code":"NOT_FOUND","instance":"/errors/not-found"}
                        """);
    }

    @Test
    void extensionsArePreserved() {
        mvc.get().uri("/errors/budget").assertThat().hasStatus(422).bodyJson()
                .extractingPath("$.budgets[0].shortfall").isEqualTo(20);
        mvc.get().uri("/errors/students").assertThat().hasStatus(422).bodyJson()
                .extractingPath("$.studentIds[0]").isEqualTo(1001);
        mvc.get().uri("/errors/validation").assertThat().hasStatus(400).bodyJson()
                .extractingPath("$.fieldErrors[0].code").isEqualTo("MISMATCH");
    }

    @Test
    void invalidBodyIncludesFieldErrors() {
        mvc.post().uri("/errors/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")
                .assertThat().hasStatus(400).bodyJson().isLenientlyEqualTo("""
                        {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"name","code":"REQUIRED"}]}
                        """);
    }

    @Test
    void invalidMethodParameterIncludesFieldErrors() {
        mvc.get().uri("/errors/parameter").param("count", "0").assertThat().hasStatus(400)
                .bodyJson().isLenientlyEqualTo("""
                        {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"count","code":"INVALID"}]}
                        """);
    }

    @Test
    void malformedParameterIsValidationProblem() {
        mvc.get().uri("/errors/parameter").param("count", "abc").assertThat().hasStatus(400)
                .bodyJson().isLenientlyEqualTo("""
                        {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"count","code":"INVALID"}]}
                        """);
    }

    @ParameterizedTest
    @CsvSource({"ab, TOO_SHORT", "abcdef, TOO_LONG"})
    void bodySizeViolationReportsDirection(String name, String code) {
        mvc.post().uri("/errors/sized-body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")
                .assertThat().hasStatus(400).bodyJson()
                .extractingPath("$.fieldErrors[0].code").isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"[1], TOO_SHORT", "'[1,2,3,4]', TOO_LONG"})
    void bodyCollectionSizeViolationReportsDirection(String items, String code) {
        mvc.post().uri("/errors/sized-collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":" + items + "}")
                .assertThat().hasStatus(400).bodyJson()
                .extractingPath("$.fieldErrors[0].code").isEqualTo(code);
    }

    @Test
    void classLevelViolationIsReportedAgainstObject() {
        mvc.post().uri("/errors/passwords").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"secret1\",\"repeat\":\"secret2\"}")
                .assertThat().hasStatus(400).bodyJson().isLenientlyEqualTo("""
                        {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"passwords","code":"INVALID","message":"Пароли не совпадают"}]}
                        """);
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutDetails() {
        mvc.get().uri("/errors/crash").assertThat().hasStatus(500)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"type":"about:blank","status":500,"code":"INTERNAL_ERROR",
                         "detail":"Внутренняя ошибка сервера","instance":"/errors/crash"}
                        """);
    }

    @Test
    void springMvcErrorsKeepTheirStatus() {
        mvc.post().uri("/errors/not-found").assertThat().hasStatus(405);
        mvc.post().uri("/errors/body").contentType(MediaType.TEXT_PLAIN).content("x").assertThat().hasStatus(415);
    }

    @Test
    void bodyFieldNameSurvivesMethodValidation() {
        mvc.post().uri("/errors/checked-body/0").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")
                .assertThat().hasStatus(400).bodyJson().extractingPath("$.fieldErrors[*].field").asArray()
                .containsExactlyInAnyOrder("id", "name");
    }

    @Test
    void missingParameterPartAndOversizedUploadKeepContractCodes() {
        mvc.get().uri("/errors/parameter").assertThat().hasStatus(400).bodyJson().isLenientlyEqualTo("""
                {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"count","code":"REQUIRED"}]}
                """);
        mvc.post().uri("/errors/upload").multipart().param("note", "x").assertThat().hasStatus(400).bodyJson()
                .isLenientlyEqualTo("""
                {"code":"VALIDATION_FAILED","fieldErrors":[{"field":"file","code":"REQUIRED"}]}
                """);
        mvc.get().uri("/errors/too-large").assertThat().hasStatus(413)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("FILE_TOO_LARGE");
    }

    @RestController
    static class ErrorsController {
        @PostMapping("/errors/checked-body/{id}")
        void checkedBody(@PathVariable @Min(1) long id, @Valid @RequestBody Input input) {}
        @PostMapping("/errors/upload")
        void upload(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) {}
        @GetMapping("/errors/too-large")
        void tooLarge() { throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024); }
        @GetMapping("/errors/crash")
        void crash() { throw new IllegalStateException("секрет в сообщении"); }
        @PostMapping("/errors/passwords")
        void passwords(@Valid @RequestBody Passwords input) {}
        @GetMapping("/errors/not-found")
        void missing() { throw new ApiException(ProblemCode.NOT_FOUND, "Ресурс не найден"); }
        @GetMapping("/errors/budget")
        void budget() { throw new BudgetExceededException("Недостаточно баллов",
                List.of(new BudgetCheck(11, "ИВТ-21", 30, 10, 20))); }
        @GetMapping("/errors/students")
        void students() { throw new StudentsRejectedException(ProblemCode.NOT_ENROLLED,
                "Студент не записан", List.of(1001L)); }
        @GetMapping("/errors/validation")
        void validation() { throw new ValidationFailedException("Поля не совпадают",
                List.of(new FieldError("password", "MISMATCH", "Пароли не совпадают"))); }
        @PostMapping("/errors/body")
        void body(@Valid @RequestBody Input input) {}
        @PostMapping("/errors/sized-body")
        void sizedBody(@Valid @RequestBody SizedInput input) {}
        @PostMapping("/errors/sized-collection")
        void sizedCollection(@Valid @RequestBody SizedCollection input) {}
        @GetMapping("/errors/parameter")
        void parameter(@RequestParam @Min(1) int count) {}
    }

    @Matching
    record Passwords(String password, String repeat) {}

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = MatchingValidator.class)
    @interface Matching {
        String message() default "Пароли не совпадают";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class MatchingValidator implements ConstraintValidator<Matching, Passwords> {
        @Override
        public boolean isValid(Passwords value, ConstraintValidatorContext context) {
            return Objects.equals(value.password(), value.repeat());
        }
    }

    record Input(@NotBlank String name) {}
    record SizedInput(@Size(min = 3, max = 5) String name) {}
    record SizedCollection(@Size(min = 2, max = 3) List<Integer> items) {}
}
