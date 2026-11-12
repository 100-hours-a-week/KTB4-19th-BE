package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.outbox.ChatbotReplyClaimService.Claim;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.logging.StructuredLogger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ChatbotReplyWorkerTest {

    private static final long EVENT_ID = 900L;
    private static final Claim CLAIM = new Claim(EVENT_ID, 1, "claim-token");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("structured-events");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Mock
    ChatbotReplyClaimService claimService;

    @Mock
    ConversationService conversationService;

    @Mock
    ConversationReplyService replyService;

    private ChatbotReplyWorker worker;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        worker = new ChatbotReplyWorker(claimService, conversationService, replyService,
            new StructuredLogger(objectMapper));
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLAIM", "PREPARE", "RETRY"})
    @DisplayName("선점·요청 준비·PENDING 복구 실패 로그는 수신한 대화와 턴 식별자를 보존하고 기존 예외를 전달한다")
    void logsRequestIdentifiersForClaimPreparationAndRecoveryFailure(String stage) {
        AiConverseRequest request = request("10");
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("preparation failure");
        DataAccessResourceFailureException retryFailure = new DataAccessResourceFailureException("retry failure");
        if ("CLAIM".equals(stage)) {
            given(claimService.claim(EVENT_ID)).willThrow(failure);
        } else {
            given(claimService.claim(EVENT_ID)).willReturn(Optional.of(CLAIM));
            given(conversationService.prepareReply(EVENT_ID, CLAIM.attempt(), CLAIM.lockToken(), request))
                .willThrow(failure);
            if ("RETRY".equals(stage)) {
                doThrow(retryFailure).when(claimService).retry(CLAIM);
            }
        }

        assertThatThrownBy(() -> worker.claimAndPrepareReply(EVENT_ID, request))
            .isSameAs("RETRY".equals(stage) ? retryFailure : failure);

        assertThat(appender.list).isNotEmpty().allSatisfy(log -> {
            JsonNode event = objectMapper.readTree(log.getFormattedMessage());
            assertThat(event.path("event_id").longValue()).isEqualTo(EVENT_ID);

            assertThat(event.path("conversation_id").isIntegralNumber()).isTrue();

            assertThat(event.path("conversation_id").longValue()).isEqualTo(10L);

            assertThat(event.path("turn_id").asText()).isEqualTo("request-turn");

            assertThat(log.getFormattedMessage()).doesNotContain("do-not-log-content");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid"})
    @DisplayName("대화 식별자를 숫자로 변환할 수 없어도 턴 식별자를 기록하고 원래 실패를 보존한다")
    void preservesOriginalFailureWhenConversationIdentifierCannotBeConverted(String conversationId) {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("claim failure");
        given(claimService.claim(EVENT_ID)).willThrow(failure);

        assertThatThrownBy(() -> worker.claimAndPrepareReply(EVENT_ID, request(conversationId))).isSameAs(failure);

        assertThat(appender.list).hasSize(1);

        JsonNode event = objectMapper.readTree(appender.list.getFirst().getFormattedMessage());
        assertThat(event.path("conversation_id").isNull()).isTrue();

        assertThat(event.path("turn_id").asText()).isEqualTo("request-turn");

        assertThat(event.path("exception_type").asText()).isEqualTo(failure.getClass().getName());
    }

    private static AiConverseRequest request(String conversationId) {
        return new AiConverseRequest(77L, "101", "1", conversationId, "request-turn", "request-trace",
            AiRoute.KNOWLEDGE, null,
            new AiConverseRequest.MessagePayload("20", "do-not-log-content", List.of()), List.of(), null);
    }
}
