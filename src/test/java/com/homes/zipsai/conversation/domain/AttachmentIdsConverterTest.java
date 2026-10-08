package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentIdsConverterTest {

    private final AttachmentIdsConverter converter = new AttachmentIdsConverter();

    @Test
    @DisplayName("사진 ID 목록은 쉼표로 이어 저장한다")
    void joinsAttachmentIdsWithComma() {
        assertThat(converter.convertToDatabaseColumn(List.of(31L, 32L))).isEqualTo("31,32");
    }

    @Test
    @DisplayName("사진 ID가 없으면 null로 저장한다")
    void storesNullWithoutAttachmentIds() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("쉼표로 이은 문자열을 사진 ID 목록으로 읽는다")
    void splitsColumnIntoAttachmentIds() {
        assertThat(converter.convertToEntityAttribute("31,32")).containsExactly(31L, 32L);
    }

    @Test
    @DisplayName("저장된 값이 없으면 빈 목록으로 읽는다")
    void readsEmptyListFromEmptyColumn() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
