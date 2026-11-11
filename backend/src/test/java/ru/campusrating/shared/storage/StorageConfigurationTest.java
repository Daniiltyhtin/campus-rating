package ru.campusrating.shared.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class StorageConfigurationTest {
    @TempDir Path directory;

    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(StorageConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=" + profile, "campus.storage.dir=" + directory);
    }

    @Test
    void demoClearsVolumeOnStartup() throws Exception {
        Files.writeString(directory.resolve("old-file"), "old");
        runner("demo")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(directory.resolve("old-file")).doesNotExist();
                        });
    }

    @Test
    void prodPreservesVolumeAndMultipartLimits() throws Exception {
        Files.writeString(directory.resolve("old-file"), "old");
        runner("prod")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(directory.resolve("old-file")).hasContent("old");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "spring.servlet.multipart.max-file-size"))
                                    .isEqualTo("10MB");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "spring.servlet.multipart.max-request-size"))
                                    .isEqualTo("11MB");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("server.tomcat.max-swallow-size"))
                                    .isEqualTo("12MB");
                        });
    }
}
