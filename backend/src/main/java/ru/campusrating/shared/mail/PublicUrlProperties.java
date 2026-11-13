package ru.campusrating.shared.mail;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("campus")
public record PublicUrlProperties(String publicUrl) {
    public PublicUrlProperties {
        if (!valid(publicUrl)) {
            throw new IllegalArgumentException("campus.public-url must be an absolute http or https URL with a host"
                    + " and without query or fragment, for example https://campus.example.ru; got '" + publicUrl + "'");
        }
    }

    private static boolean valid(String value) {
        if (value == null) return false;
        try {
            var uri = new URI(value);
            return uri.getScheme() != null
                    && Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
