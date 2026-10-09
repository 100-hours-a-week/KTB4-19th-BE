package com.homes.zipsai.building.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplaintCreateRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    @DisplayName("대화 ID만 보내도 민원을 접수할 수 있다")
    void allowsRequestWithOnlyConversationId() {
        assertThat(VALIDATOR.validate(ComplaintCreateRequest.builder().conversationId(1L).build())).isEmpty();
    }

    @Test
    @DisplayName("대화 ID가 없으면 검증에 실패한다")
    void rejectsMissingConversationId() {
        assertThat(VALIDATOR.validate(ComplaintCreateRequest.builder().build()))
            .singleElement()
            .extracting(violation -> violation.getPropertyPath().toString())
            .isEqualTo("conversationId");
    }

    @Test
    @DisplayName("발생 위치가 50자를 넘으면 검증에 실패한다")
    void rejectsLocationLongerThan50Characters() {
        ComplaintCreateRequest request =
            ComplaintCreateRequest.builder().conversationId(1L).location("가".repeat(51)).build();

        assertThat(VALIDATOR.validate(request))
            .singleElement()
            .extracting(ConstraintViolation::getMessage)
            .isEqualTo("발생 위치는 50자 이하여야 합니다.");
    }

    @Test
    @DisplayName("증상이 100자를 넘으면 검증에 실패한다")
    void rejectsSymptomLongerThan100Characters() {
        ComplaintCreateRequest request =
            ComplaintCreateRequest.builder().conversationId(1L).symptom("가".repeat(101)).build();

        assertThat(VALIDATOR.validate(request))
            .singleElement()
            .extracting(ConstraintViolation::getMessage)
            .isEqualTo("증상은 100자 이하여야 합니다.");
    }
}
