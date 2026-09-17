package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiConverseClient implements AiConverseClient {

    private static final Pattern INQUIRY_PATTERN =
        Pattern.compile("\\?|언제|어떻게|어디서|몇 시|규칙|요일|방법|되나요|있나요|알려");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        String text = request.message().text() == null ? "" : request.message().text();
        if (isKnowledgeQuestion(request, text)) {
            return new AiConverseResponse(AiRoute.KNOWLEDGE, null,
                "문의하신 내용은 건물 운영규칙을 기준으로 안내해 드려요. 지금은 테스트용 고정 답변이라 자세한 내용은 관리인에게 확인해 주세요.",
                null);
        }
        return collectComplaint(request, text);
    }

    private boolean isKnowledgeQuestion(AiConverseRequest request, String text) {
        if (request.currentRoute() != null) {
            return request.currentRoute() == AiRoute.KNOWLEDGE;
        }
        return INQUIRY_PATTERN.matcher(text).find();
    }

    private AiConverseResponse collectComplaint(AiConverseRequest request, String text) {
        AiComplaintDraft previous = request.complaintDraft() == null
            ? AiComplaintDraft.EMPTY
            : request.complaintDraft().toDraft();
        AiComplaintDraft draft = fillNextMissingField(previous, text);

        String question = nextQuestion(draft);
        if (question == null) {
            return new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.READY_TO_CONFIRM,
                "접수 내용을 정리했어요. 아래 내용으로 민원을 접수할까요?", draft);
        }
        String reply = previous.isEmpty() ? "불편을 드려 죄송해요. " + question : question;
        return new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.COLLECTING, reply, draft);
    }

    private AiComplaintDraft fillNextMissingField(AiComplaintDraft draft, String text) {
        if (draft.symptom() == null) {
            return new AiComplaintDraft(draft.location(), text, draft.occurredAt());
        }
        if (draft.location() == null) {
            return new AiComplaintDraft(text, draft.symptom(), draft.occurredAt());
        }
        if (draft.occurredAt() == null) {
            return new AiComplaintDraft(draft.location(), draft.symptom(), OffsetDateTime.now(KST));
        }
        return draft;
    }

    private String nextQuestion(AiComplaintDraft draft) {
        if (draft.symptom() == null) {
            return "어떤 불편이 있으신가요?";
        }
        if (draft.location() == null) {
            return "문제가 생긴 위치가 어디인가요? (예: 안방 천장)";
        }
        if (draft.occurredAt() == null) {
            return "언제부터 이런 증상이 있었나요? (예: 어제 저녁부터)";
        }
        return null;
    }
}
