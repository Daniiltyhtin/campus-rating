package ru.campusrating;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {
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
                .check(new ClassFileImporter().importPackages("ru.campusrating"));
    }
}
