package com.homes.zipsai.conversation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentIdsValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    @DisplayName("사진은 3장까지 보낼 수 있다")
    void allowsUpToThreeImages() {
        assertThat(VALIDATOR.validate(new MessageSendRequest("안방이요", List.of(1L, 2L, 3L)))).isEmpty();
        assertThat(VALIDATOR.validate(new ConversationCreateRequest("천장에서 물이 새요", List.of(1L, 2L, 3L))))
            .isEmpty();
    }

    @Test
    @DisplayName("사진이 3장을 넘으면 검증에 실패한다")
    void rejectsMoreThanThreeImages() {
        List<Long> fourImages = List.of(1L, 2L, 3L, 4L);

        assertThat(fields(VALIDATOR.validate(new MessageSendRequest("안방이요", fourImages))))
            .containsExactly("attachmentIds");
        assertThat(fields(VALIDATOR.validate(new ConversationCreateRequest("천장에서 물이 새요", fourImages))))
            .containsExactly("attachmentIds");
    }

    @Test
    @DisplayName("사진 ID에 null이 있으면 검증에 실패한다")
    void rejectsNullImageId() {
        List<Long> withNull = Arrays.asList(1L, null);

        assertThat(VALIDATOR.validate(new MessageSendRequest("안방이요", withNull))).hasSize(1);
        assertThat(VALIDATOR.validate(new ConversationCreateRequest("천장에서 물이 새요", withNull))).hasSize(1);
    }

    @Test
    @DisplayName("사진 없이 보낼 수 있다")
    void allowsMessageWithoutImages() {
        assertThat(VALIDATOR.validate(new MessageSendRequest("안방이요", null))).isEmpty();
    }

    private static <T> List<String> fields(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).toList();
    }
}
