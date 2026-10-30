package com.homes.zipsai.conversation.outbox;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.outbox.ChatbotReplyClaimService.Claim;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatbotReplyRelayWorker {

    private final ChatbotReplyClaimService claimService;
    private final ChatbotReplyRelayService relayService;
    private final ConversationReplyService replyService;
    private final StructuredLogger structuredLogger;

    public void processBatch() {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        try {
            for (Claim claim : claimService.findCleanupBatch()) {
                MDC.clear();
                fail(claim);
            }
            MDC.clear();
            relayService.relayBatch();
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

    private void fail(Claim claim) {
        try {
            replyService.failReply(claim.eventId(), claim.attempt(), claim.lockToken());
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(claim.eventId(), null, null, exception);
        }
    }
}
