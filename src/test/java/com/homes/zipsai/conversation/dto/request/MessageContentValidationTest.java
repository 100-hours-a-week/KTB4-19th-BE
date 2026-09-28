package com.homes.zipsai.conversation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageContentValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    @DisplayName("대화를 시작할 때 메시지는 200자까지 보낼 수 있다")
    void allowsFirstMessageUpTo200Characters() {
        assertThat(VALIDATOR.validate(new ConversationCreateRequest("가".repeat(200), null))).isEmpty();
    }

    @Test
    @DisplayName("대화를 시작할 때 메시지가 200자를 넘으면 검증에 실패한다")
    void rejectsFirstMessageLongerThan200Characters() {
        assertThat(VALIDATOR.validate(new ConversationCreateRequest("가".repeat(201), null)))
            .singleElement()
            .extracting(ConstraintViolation::getMessage)
            .isEqualTo("메시지는 200자 이하여야 합니다.");
    }

    @Test
    @DisplayName("이어서 보내는 메시지는 200자까지 보낼 수 있다")
    void allowsNextMessageUpTo200Characters() {
        assertThat(VALIDATOR.validate(new MessageSendRequest("가".repeat(200), null))).isEmpty();
    }

    @Test
    @DisplayName("이어서 보내는 메시지가 200자를 넘으면 검증에 실패한다")
    void rejectsNextMessageLongerThan200Characters() {
        assertThat(VALIDATOR.validate(new MessageSendRequest("가".repeat(201), null)))
            .singleElement()
            .extracting(ConstraintViolation::getMessage)
            .isEqualTo("메시지는 200자 이하여야 합니다.");
    }
}
