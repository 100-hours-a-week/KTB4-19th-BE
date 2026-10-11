package com.homes.zipsai.global.logging;

import java.net.URI;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.external-api")
public record ExternalApiProperties(Map<String, Target> targets) {
    public ExternalApiProperties {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public record Target(URI baseUrl, String stage, String errorCode) {
        public Target {
            if (baseUrl == null || baseUrl.getAuthority() == null
                    || !("http".equalsIgnoreCase(baseUrl.getScheme())
                        || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
                throw new IllegalArgumentException("External API base URL must be an absolute HTTP URL");
            }
            if (stage == null || stage.isBlank() || errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("External API stage and error code must not be blank");
            }
        }
    }
}
