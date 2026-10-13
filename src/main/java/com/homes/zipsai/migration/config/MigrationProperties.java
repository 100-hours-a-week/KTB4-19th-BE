package com.homes.zipsai.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.migration")
public record MigrationProperties(
    String appConfigUrl,
    V2Datasource v2Datasource
) {
    public boolean isEnabled() {
        return v2Datasource != null && v2Datasource.url() != null && !v2Datasource.url().isBlank();
    }

    public record V2Datasource(
        String url,
        String username,
        String password
    ) {
    }
}
