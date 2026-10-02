package com.homes.zipsai.user.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.homes.zipsai.global.exception.ValidationFailedException;

class UserInputNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"홍길동", "Kim", "Ann-Lee", "O'Neil", "김 철수"})
    @DisplayName("한글·영문 이름은 허용한다")
    void acceptsLetterNames(String name) {
        assertThat(UserInput.name(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"😀", "홍길동😀", "❤️", "⭐별", "🇰🇷"})
    @DisplayName("일반 이모지가 들어간 이름은 거절한다")
    void rejectsEmoji(String name) {
        assertThatThrownBy(() -> UserInput.name(name)).isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ℹ", "홍ℹ", "ℹ️"})
    @DisplayName("유니코드상 글자로 분류되는 이모지도 거절한다")
    void rejectsLetterCategoryEmoji(String name) {
        assertThatThrownBy(() -> UserInput.name(name)).isInstanceOf(ValidationFailedException.class);
    }
}
