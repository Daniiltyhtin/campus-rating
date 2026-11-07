package ru.campusrating;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ru.campusrating");

    @Test
    void discoversAllModulesAndVerifiesBoundaries() {
        var modules = ApplicationModules.of("ru.campusrating");
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("identity", "directory", "ledger", "checkpoint",
                        "activity", "profile", "feed", "poll", "admin", "notification",
                        "analytics", "ai", "settings", "shared");
        assertThat(modules.stream().filter(module -> module.isOpen())
                .map(module -> module.getIdentifier().toString())).containsExactly("shared");
        modules.verify();
    }

    @Test
    void applicationDoesNotImportJackson2Databind() {
        noClasses().that().resideInAPackage("ru.campusrating..")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson.databind..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void applicationUsesInjectedClock() {
        var temporalTypes = Set.of("java.time.Instant", "java.time.LocalDate",
                "java.time.LocalDateTime", "java.time.LocalTime", "java.time.ZonedDateTime",
                "java.time.OffsetDateTime", "java.time.YearMonth", "java.time.Year");

        noClasses().that().resideInAPackage("ru.campusrating..")
                .and().doNotHaveFullyQualifiedName("ru.campusrating.shared.ClockConfig")
                .should().callMethodWhere(describe("read system time without an injected Clock", call -> {
                    var target = call.getTarget();
                    var owner = target.getOwner().getName();
                    var name = target.getName();
                    if (temporalTypes.contains(owner) && name.equals("now")) {
                        return target.getRawParameterTypes().stream()
                                .noneMatch(type -> type.getName().equals("java.time.Clock"));
                    }
                    return owner.equals("java.lang.System") && name.equals("currentTimeMillis")
                            || owner.equals("java.time.Clock")
                            && (name.equals("systemUTC") || name.equals("systemDefaultZone"));
                }))
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void domainDoesNotDependOnSpringOrDatabase() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..",
                        "java.sql..", "javax.sql..", "jakarta.persistence..", "jakarta.servlet..")
                .check(PRODUCTION_CLASSES);
    }
}
