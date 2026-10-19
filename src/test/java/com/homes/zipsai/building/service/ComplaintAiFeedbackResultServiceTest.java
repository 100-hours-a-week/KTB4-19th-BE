package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.conversation.ai.AiProcessingNotification;
import com.homes.zipsai.conversation.ai.AiProcessingNotification.Result;

@ExtendWith(MockitoExtension.class)
class ComplaintAiFeedbackResultServiceTest {

    private static final LocalDateTime VERSION = LocalDateTime.of(2026, 10, 15, 0, 0, 12, 345_678_000);
    private static final OffsetDateTime KST_VERSION = VERSION.atOffset(ZoneOffset.ofHours(9));

    @Mock
    ComplaintDetailRepository complaintDetailRepository;

    @InjectMocks
    ComplaintAiFeedbackResultService service;

    @Test
    @DisplayName("답변 반영 실패 통지는 통지의 버전으로 전송 기록을 되돌린다")
    void revertsSentFeedbackOnUpsertFailure() {
        service.handle(notification(AiProcessingNotification.KIND_COMMENT_UPSERT,
            new Result(9001L, KST_VERSION, "failed", "EMBEDDING_FAILED")));

        then(complaintDetailRepository).should().revertAiFeedbackSent(9001L, VERSION);
    }

    @Test
    @DisplayName("UTC로 온 버전도 한국 시간으로 바꿔 비교한다")
    void convertsUtcVersionToSeoulTime() {
        service.handle(notification(AiProcessingNotification.KIND_COMMENT_UPSERT,
            new Result(9001L, KST_VERSION.withOffsetSameInstant(ZoneOffset.UTC), "failed", "EMBEDDING_FAILED")));

        then(complaintDetailRepository).should().revertAiFeedbackSent(9001L, VERSION);
    }

    @Test
    @DisplayName("삭제 실패 통지는 통지의 버전으로 다시 삭제 대상으로 되돌린다")
    void revertsDeletedFeedbackOnDeleteFailure() {
        service.handle(notification(AiProcessingNotification.KIND_COMMENT_DELETE,
            new Result(9001L, KST_VERSION, "failed", "VECTOR_STORE_FAILED")));

        then(complaintDetailRepository).should().revertAiFeedbackDeleted(9001L, VERSION);
        then(complaintDetailRepository).should(never()).revertAiFeedbackSent(anyLong(), any());
    }

    @Test
    @DisplayName("삭제 실패 통지에 버전이 없으면 되돌리지 않는다")
    void ignoresDeleteFailureWithoutVersion() {
        service.handle(notification(AiProcessingNotification.KIND_COMMENT_DELETE,
            new Result(9001L, null, "failed", "VECTOR_STORE_FAILED")));

        then(complaintDetailRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("성공·건너뜀·알 수 없는 상태는 전송 기록을 바꾸지 않는다")
    void ignoresNonFailureResults() {
        service.handle(notification(AiProcessingNotification.KIND_COMMENT_UPSERT,
            new Result(9001L, KST_VERSION, "succeeded", null),
            new Result(9002L, KST_VERSION, "skipped", "COMMENT_TOO_SHORT"),
            new Result(9003L, KST_VERSION, "unknown", null)));

        then(complaintDetailRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("답변 반영·삭제 통지만 처리 대상으로 인정한다")
    void supportsOnlyCommentKinds() {
        assertThat(service.supports(AiProcessingNotification.KIND_COMMENT_UPSERT)).isTrue();
        assertThat(service.supports(AiProcessingNotification.KIND_COMMENT_DELETE)).isTrue();
        assertThat(service.supports("document_index")).isFalse();
        assertThat(service.supports(null)).isFalse();
    }

    private static AiProcessingNotification notification(String kind, Result... results) {
        return new AiProcessingNotification(kind, "trace-1", List.of(results));
    }
}
