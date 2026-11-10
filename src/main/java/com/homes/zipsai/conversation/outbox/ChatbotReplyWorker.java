package com.homes.zipsai.conversation.outbox;

import java.util.Map;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType.RetryPolicy;
import com.homes.zipsai.conversation.outbox.ChatbotReplyClaimService.Claim;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatbotReplyWorker {

    private final ChatbotReplyClaimService claimService;
    private final ConversationService conversationService;
    private final ConversationReplyService replyService;
    private final StructuredLogger structuredLogger;

    public Optional<PendingAiReply> claimAndPrepareReply(Long eventId, AiConverseRequest request) {
        Long conversationId = null;
        String turnId = request.turnId();
        try {
            conversationId = Long.valueOf(request.conversationId());
        } catch (NumberFormatException ignored) {

        }
        Claim claimed = null;
        try {
            Optional<Claim> claim = claimService.claim(eventId);
            if (claim.isEmpty()) {
                return Optional.empty();
            }
            claimed = claim.orElseThrow();
            return conversationService.prepareReply(claimed.eventId(), claimed.attempt(), claimed.lockToken(), request);
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId, exception);
            if (claimed != null) {
                try {
                    claimService.retry(claimed);
                } catch (RuntimeException retryException) {
                    structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId, retryException);
                    throw retryException;
                }
            }
            throw exception;
        }
    }

    public void processBatch() {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        try {
            for (Claim claim : claimService.findCleanupBatch()) {
                MDC.clear();
                fail(claim);
            }
            MDC.clear();
            for (Claim claim : claimService.claimBatch()) {
                MDC.clear();
                execute(claim);
            }
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(null, null, null, exception);
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private void execute(Claim claim) {
        if (!RetryPolicy.LIMITED.shouldRetry(claim.attempt())) {
            fail(claim);
            return;
        }
        try {
            conversationService.prepareBatchReply(claim.eventId(), claim.attempt(), claim.lockToken())
                .ifPresent(pendingReply -> {
                    MDC.put("traceId", pendingReply.aiRequest().traceId());
                    replyService.reply(pendingReply);
                });
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(claim.eventId(), null, null, exception);
            try {
                claimService.retry(claim);
            } catch (RuntimeException retryException) {
                structuredLogger.chatbotReplyFailed(claim.eventId(), null, null, retryException);
            }
        }
    }

    private void fail(Claim claim) {
        try {
            replyService.failReply(claim.eventId(), claim.attempt(), claim.lockToken());
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(claim.eventId(), null, null, exception);
        }
    }
}
