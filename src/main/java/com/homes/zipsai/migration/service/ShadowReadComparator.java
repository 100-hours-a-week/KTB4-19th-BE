package com.homes.zipsai.migration.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnMigrationEnabled
@RequiredArgsConstructor
public class ShadowReadComparator {

    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\"?]+)\\?[^\"]*");

    private final ObjectMapper objectMapper;

    public boolean isSame(Object v1Result, Object v2Result) {
        return removeUrlQuery(v1Result).equals(removeUrlQuery(v2Result));
    }

    private String removeUrlQuery(Object result) {
        return URL_QUERY.matcher(objectMapper.writeValueAsString(result)).replaceAll("$1");
    }
}
