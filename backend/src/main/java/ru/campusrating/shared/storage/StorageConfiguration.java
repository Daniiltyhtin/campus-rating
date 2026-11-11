package ru.campusrating.shared.storage;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean FileStorage fileStorage(@Value("${campus.storage.dir}") Path directory, Environment environment) throws IOException {
        var storage = new VolumeFileStorage(directory);
        if (environment.acceptsProfiles(Profiles.of("demo"))) storage.clear();
        return storage;
    }
}
