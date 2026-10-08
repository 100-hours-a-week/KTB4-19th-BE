package com.homes.zipsai.conversation.domain;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.util.StringUtils;

@Converter
public class AttachmentIdsConverter implements AttributeConverter<List<Long>, String> {

    private static final String DELIMITER = ",";

    @Override
    public String convertToDatabaseColumn(List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return null;
        }
        return attachmentIds.stream()
            .map(attachmentId -> String.valueOf(attachmentId))
            .collect(Collectors.joining(DELIMITER));
    }

    @Override
    public List<Long> convertToEntityAttribute(String column) {
        if (!StringUtils.hasText(column)) {
            return List.of();
        }
        return Arrays.stream(column.split(DELIMITER))
            .map(attachmentId -> Long.valueOf(attachmentId.strip()))
            .toList();
    }
}
