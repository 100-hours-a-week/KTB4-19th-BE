package com.homes.zipsai.building.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplaintCommentUpdateRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    @DisplayName("코멘트는 200자까지 저장할 수 있다")
    void allowsCommentUpToTwoHundredCharacters() {
        assertThat(VALIDATOR.validate(new ComplaintCommentUpdateRequest("가".repeat(200)))).isEmpty();
    }

    @Test
    @DisplayName("코멘트가 200자를 넘으면 길이 검증에 실패한다")
    void rejectsCommentOverTwoHundredCharacters() {
        assertThat(constraints(VALIDATOR.validate(new ComplaintCommentUpdateRequest("가".repeat(201)))))
            .containsExactly("Size");
    }

    @Test
    @DisplayName("공백만 보낸 코멘트는 필수값 검증에 실패한다")
    void rejectsBlankComment() {
        assertThat(constraints(VALIDATOR.validate(new ComplaintCommentUpdateRequest("   "))))
            .containsExactly("NotBlank");
    }

    @Test
    @DisplayName("코멘트가 없으면 필수값 검증에 실패한다")
    void rejectsMissingComment() {
        assertThat(constraints(VALIDATOR.validate(new ComplaintCommentUpdateRequest(null))))
            .containsExactly("NotBlank");
    }

    @Test
    @DisplayName("코멘트 앞뒤 공백은 지운다")
    void stripsSurroundingWhitespace() {
        assertThat(new ComplaintCommentUpdateRequest("  배관 교체 완료  ").comment()).isEqualTo("배관 교체 완료");
    }

    private static Set<String> constraints(Set<ConstraintViolation<ComplaintCommentUpdateRequest>> violations) {
        Set<String> constraints = new HashSet<>();
        for (ConstraintViolation<ComplaintCommentUpdateRequest> violation : violations) {
            constraints.add(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
        }
        return constraints;
    }
}
