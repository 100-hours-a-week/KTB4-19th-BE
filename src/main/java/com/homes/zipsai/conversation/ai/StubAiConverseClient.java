package com.homes.zipsai.conversation.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiConverseClient implements AiConverseClient {

    private static final Pattern KNOWLEDGE_PATTERN =
        Pattern.compile("\\?|언제|어떻게|어디서|몇 시|규칙|요일|방법|되나요|있나요|알려");
    private static final Pattern UNANSWERABLE_PATTERN = Pattern.compile("관리인|관리자|담당자|문의");
    private static final int CLARIFY_MAX_TEXT_LENGTH = 3;

    private static final String LOCATION_FIELD = "location";
    private static final String SYMPTOM_FIELD = "symptom";
    private static final Map<String, String> QUESTIONS = Map.of(
        SYMPTOM_FIELD, "어떤 불편이 있으신가요?",
        LOCATION_FIELD, "문제가 생긴 위치가 어디인가요? (예: 안방 천장)");
    private static final List<AiConverseResponse.Citation> STUB_CITATIONS = List.of(
        new AiConverseResponse.Citation("building_document", "building-guide-12", "생활 안내", null, null));

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        String traceId = request.traceId();
        String text = request.message().text() == null ? "" : request.message().text().strip();
        return switch (decideRoute(request, text)) {
            case COMPLAINT -> collectComplaint(request, traceId, text);
            case KNOWLEDGE -> answerKnowledge(traceId, text);
            case CLARIFY -> qaCard(traceId, AiRoute.CLARIFY,
                "말씀하신 내용을 이해하지 못했어요. 관리인이 확인할 수 있도록 질문을 남겨 두었습니다.", text);
        };
    }

    private AiRoute decideRoute(AiConverseRequest request, String text) {
        if (request.currentRoute() == AiRoute.COMPLAINT) {
            return AiRoute.COMPLAINT;
        }
        if (text.length() <= CLARIFY_MAX_TEXT_LENGTH) {
            return AiRoute.CLARIFY;
        }
        return KNOWLEDGE_PATTERN.matcher(text).find() ? AiRoute.KNOWLEDGE : AiRoute.COMPLAINT;
    }

    private AiConverseResponse answerKnowledge(String traceId, String text) {
        if (UNANSWERABLE_PATTERN.matcher(text).find()) {
            return qaCard(traceId, AiRoute.KNOWLEDGE,
                "건물 문서에서 근거를 찾지 못해 답변드리기 어렵습니다. 질문을 관리인에게 전달해 두었습니다.", text);
        }
        return response(traceId, AiRoute.KNOWLEDGE, null,
            "문의하신 내용은 건물 운영규칙을 기준으로 안내해 드려요. 지금은 테스트용 고정 답변이라 자세한 내용은 관리인에게 확인해 주세요.",
            new AiConverseResponse.Result(null, null, List.of(), STUB_CITATIONS));
    }

    private AiConverseResponse collectComplaint(AiConverseRequest request, String traceId, String text) {
        AiComplaintDraft previous = request.complaintDraft() == null
            ? AiComplaintDraft.EMPTY
            : request.complaintDraft().toDraft();
        AiComplaintDraft draft = fillNextMissingField(previous, text);
        List<String> missingFields = missingFields(draft);
        AiConverseResponse.DraftPatch patch =
            new AiConverseResponse.DraftPatch(draft.location(), draft.symptom(),
                draft.occurredAt() == null ? null : draft.occurredAt().toString());
        if (missingFields.isEmpty()) {
            return response(traceId, AiRoute.COMPLAINT, null, "접수 내용을 정리했어요. 아래 내용으로 민원을 접수할까요?",
                new AiConverseResponse.Result(patch, null, missingFields, List.of()));
        }
        String question = QUESTIONS.get(missingFields.getFirst());
        String reply = previous.isEmpty() ? "불편을 드려 죄송해요. " + question : question;
        return response(traceId, AiRoute.COMPLAINT, AiComplaintState.COLLECTING, reply,
            new AiConverseResponse.Result(patch, null, missingFields, List.of()));
    }

    private AiConverseResponse qaCard(String traceId, AiRoute route, String reply, String question) {
        return response(traceId, route, null, reply, new AiConverseResponse.Result(
            null, new AiConverseResponse.QaCardDraft(question), List.of(), List.of()));
    }

    private AiConverseResponse response(String traceId, AiRoute route, AiComplaintState nextComplaintState,
                                        String reply, AiConverseResponse.Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, traceId,
            new AiConverseResponse.Data(route, nextComplaintState, reply, result));
    }

    private AiComplaintDraft fillNextMissingField(AiComplaintDraft draft, String text) {
        if (draft.symptom() == null) {
            return new AiComplaintDraft(draft.location(), text, draft.occurredAt());
        }
        if (draft.location() == null) {
            return new AiComplaintDraft(text, draft.symptom(), draft.occurredAt());
        }
        return draft;
    }

    private List<String> missingFields(AiComplaintDraft draft) {
        List<String> missingFields = new ArrayList<>();
        if (draft.symptom() == null) {
            missingFields.add(SYMPTOM_FIELD);
        }
        if (draft.location() == null) {
            missingFields.add(LOCATION_FIELD);
        }
        return missingFields;
    }
}
