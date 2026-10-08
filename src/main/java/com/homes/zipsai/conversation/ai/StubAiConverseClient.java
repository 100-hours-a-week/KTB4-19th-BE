package com.homes.zipsai.conversation.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
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
    private static final String STUB_ISSUE_TYPE = "other";
    private static final String STUB_IMAGE_SUMMARY = "테스트용 사진 요약";
    private static final Map<String, String> QUESTIONS = Map.of(
        SYMPTOM_FIELD, "어떤 불편이 있으신가요?",
        LOCATION_FIELD, "문제가 생긴 위치가 어디인가요? (예: 안방 천장)");
    private static final List<AiConverseResponse.Citation> STUB_CITATIONS = List.of(
        new AiConverseResponse.Citation("building_document", "building-guide-12", "생활 안내", null, null));

    private final LogNormalDelay complaintDelay;
    private final LogNormalDelay knowledgeDelay;

    public StubAiConverseClient(@Value("${app.ai.stub.complaint-delay:0s}") Duration complaintMedian,
                                @Value("${app.ai.stub.complaint-delay-sigma:0}") double complaintSigma,
                                @Value("${app.ai.stub.knowledge-delay:0s}") Duration knowledgeMedian,
                                @Value("${app.ai.stub.knowledge-delay-sigma:0}") double knowledgeSigma) {
        this.complaintDelay = new LogNormalDelay(complaintMedian, complaintSigma);
        this.knowledgeDelay = new LogNormalDelay(knowledgeMedian, knowledgeSigma);
    }

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        String turnId = request.turnId();
        String text = request.message().text() == null ? "" : request.message().text().strip();
        AiRoute route = decideRoute(request, text);
        waitLikeAiServer(route);
        return switch (route) {
            case COMPLAINT -> collectComplaint(request, turnId, text);
            case KNOWLEDGE -> answerKnowledge(turnId, text);
            case CLARIFY -> askAgain(turnId);
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

    private void waitLikeAiServer(AiRoute route) {
        Duration delay = switch (route) {
            case COMPLAINT -> complaintDelay.next();
            case KNOWLEDGE -> knowledgeDelay.next();
            case CLARIFY -> Duration.ZERO;
        };
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AiConverseResponse answerKnowledge(String turnId, String text) {
        if (UNANSWERABLE_PATTERN.matcher(text).find()) {
            return qaCard(turnId,
                "건물 문서에서 근거를 찾지 못해 답변드리기 어렵습니다. 질문을 관리인에게 전달해 두었습니다.", text);
        }
        return response(turnId, AiRoute.KNOWLEDGE, null,
            "문의하신 내용은 건물 운영규칙을 기준으로 안내해 드려요. 지금은 테스트용 고정 답변이라 자세한 내용은 관리인에게 확인해 주세요.",
            AiConverseResponse.Result.builder().citations(STUB_CITATIONS).build());
    }

    private AiConverseResponse collectComplaint(AiConverseRequest request, String turnId, String text) {
        AiComplaintDraft previous = request.complaintDraft() == null
            ? AiComplaintDraft.EMPTY
            : request.complaintDraft().toDraft();
        AiComplaintDraft draft = fillNextMissingField(previous, text);
        List<String> missingFields = missingFields(draft);
        String occurredAt = draft.occurredAt() == null ? null : draft.occurredAt().toString();
        List<AiConverseRequest.MessageImage> images = request.message().images();
        AiConverseResponse.DraftPatch patch = AiConverseResponse.DraftPatch.builder()
            .location(draft.location())
            .symptom(draft.symptom())
            .occurredAt(occurredAt)
            .issueType(STUB_ISSUE_TYPE)
            .attachmentIds(draftAttachmentIds(request.complaintDraft(), images))
            .build();
        AiConverseResponse.Result result = AiConverseResponse.Result.builder()
            .complaintDraft(patch)
            .missingFields(missingFields)
            .imageAnalysis(analyze(images))
            .build();
        if (missingFields.isEmpty()) {
            return response(turnId, AiRoute.COMPLAINT, null, "접수 내용을 정리했어요. 아래 내용으로 민원을 접수할까요?", result);
        }
        String question = QUESTIONS.get(missingFields.getFirst());
        String reply = previous.isEmpty() ? "불편을 드려 죄송해요. " + question : question;
        return response(turnId, AiRoute.COMPLAINT, AiComplaintState.COLLECTING, reply, result);
    }

    private List<Long> draftAttachmentIds(AiConverseRequest.ComplaintDraftPayload previousDraft,
                                          List<AiConverseRequest.MessageImage> images) {
        List<Long> attachmentIds = new ArrayList<>();
        if (previousDraft != null) {
            attachmentIds.addAll(previousDraft.attachmentIds());
        }
        for (AiConverseRequest.MessageImage image : images) {
            attachmentIds.add(image.attachmentId());
        }
        return attachmentIds;
    }

    private AiConverseResponse.ImageAnalysis analyze(List<AiConverseRequest.MessageImage> images) {
        if (images.isEmpty()) {
            return null;
        }
        List<AiConverseResponse.ImageObservation> observations = new ArrayList<>();
        for (AiConverseRequest.MessageImage image : images) {
            observations.add(new AiConverseResponse.ImageObservation(image.attachmentId(), STUB_IMAGE_SUMMARY, null));
        }
        return new AiConverseResponse.ImageAnalysis(observations);
    }

    private AiConverseResponse askAgain(String turnId) {
        return response(turnId, AiRoute.CLARIFY, null, "말씀하신 내용을 이해하지 못했어요. 조금 더 자세히 알려 주시겠어요?",
            AiConverseResponse.Result.builder().build());
    }

    private AiConverseResponse qaCard(String turnId, String reply, String question) {
        return response(turnId, AiRoute.KNOWLEDGE, null, reply, AiConverseResponse.Result.builder()
            .qaCardDraft(new AiConverseResponse.QaCardDraft(question))
            .build());
    }

    private AiConverseResponse response(String turnId, AiRoute route, AiComplaintState nextComplaintState,
                                        String reply, AiConverseResponse.Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, turnId,
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

    record LogNormalDelay(Duration median, double sigma) {

        Duration next() {
            double factor = Math.exp(sigma * ThreadLocalRandom.current().nextGaussian());
            return Duration.ofMillis(Math.round(median.toMillis() * factor));
        }
    }
}
