package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceCreateTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;

    @Mock
    ComplaintRepository complaintRepository;

    @Mock
    ComplaintDetailRepository complaintDetailRepository;

    @Mock
    BuildingRepository buildingRepository;

    @Mock
    ResidentRoomService residentRoomService;

    @Mock
    ConversationService conversationService;

    @Mock
    S3StorageService s3StorageService;

    ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
    }

    @Test
    @DisplayName("입주민이 카드에서 고친 값만 덮어쓰고 나머지는 AI 초안 값으로 접수한다")
    void mergesResidentEditsIntoAiDraft() {
        givenConversation(conversationWithDraft("안방 천장", "천장에서 물이 샘", List.of()));
        givenComplaintSaved();

        complaintService.createComplaint(RESIDENT_ID, new ComplaintCreateRequest(CONVERSATION_ID, "거실 천장", null, null));

        ComplaintDetail complaintDetail = savedDetail();
        assertThat(complaintDetail.getLocation()).isEqualTo("거실 천장");
        assertThat(complaintDetail.getSymptom()).isEqualTo("천장에서 물이 샘");
    }

    @Test
    @DisplayName("민원을 접수하면 대화를 민원 대화로 바꾸고 닫는다")
    void marksConversationComplaintCreated() {
        Conversation conversation = conversationWithDraft("안방 천장", "천장에서 물이 샘", List.of());
        givenConversation(conversation);
        givenComplaintSaved();

        complaintService.createComplaint(RESIDENT_ID, new ComplaintCreateRequest(CONVERSATION_ID, null, null, null));

        assertThat(conversation.getType()).isEqualTo(ConversationType.COMPLAINT);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.COMPLAINT_CREATED);
        assertThat(conversation.getComplaintState()).isNull();
    }

    @Test
    @DisplayName("요약 카드로 접수한 민원은 일반 민원으로 저장한다")
    void savesComplaintTypeForSummaryCard() {
        givenConversation(conversationWithDraft("안방 천장", "천장에서 물이 샘", List.of()));
        givenComplaintSaved();

        complaintService.createComplaint(RESIDENT_ID, new ComplaintCreateRequest(CONVERSATION_ID, null, null, null));

        assertThat(savedComplaint().getType()).isEqualTo(ComplaintType.COMPLAINT);
    }

    @Test
    @DisplayName("QA 카드로 접수한 민원은 QA로 저장한다")
    void savesQaTypeForQaCard() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(qaCardResponse("분리수거는 어디서 하나요?"));
        givenConversation(conversation);
        givenComplaintSaved();

        complaintService.createComplaint(RESIDENT_ID, new ComplaintCreateRequest(CONVERSATION_ID, null, null, null));

        assertThat(savedComplaint().getType()).isEqualTo(ComplaintType.QA);
    }

    @Test
    @DisplayName("질의로 시작한 대화라도 마지막 카드가 요약 카드면 일반 민원으로 저장한다")
    void savesComplaintTypeWhenLastCardIsSummaryCard() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(answeredQuestionResponse());
        conversation.applyAiResponse(summaryCardResponse("안방 천장", "천장에서 물이 샘", List.of()));
        givenConversation(conversation);
        givenComplaintSaved();

        complaintService.createComplaint(RESIDENT_ID, new ComplaintCreateRequest(CONVERSATION_ID, null, null, null));

        assertThat(savedComplaint().getType()).isEqualTo(ComplaintType.COMPLAINT);
    }

    @Test
    @DisplayName("요약 카드가 뜨기 전에 접수하면 민원을 저장하지 않는다")
    void savesNothingBeforeSummaryCard() {
        given(conversationService.getOwnedConversation(RESIDENT_ID, CONVERSATION_ID))
            .willReturn(conversationWithDraft("안방 천장", null, List.of("symptom")));

        ComplaintCreateRequest request = new ComplaintCreateRequest(CONVERSATION_ID, null, null, null);

        assertThatThrownBy(() -> complaintService.createComplaint(RESIDENT_ID, request))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "COMPLAINT_NOT_READY");
        then(complaintRepository).should(never()).save(any());
        then(complaintDetailRepository).should(never()).save(any());
    }

    private void givenConversation(Conversation conversation) {
        given(conversationService.getOwnedConversation(RESIDENT_ID, CONVERSATION_ID)).willReturn(conversation);
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(user(RESIDENT_ID)));
    }

    private void givenComplaintSaved() {
        given(complaintRepository.save(any(Complaint.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 100L));
        given(complaintDetailRepository.save(any(ComplaintDetail.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    private Complaint savedComplaint() {
        ArgumentCaptor<Complaint> complaintCaptor = ArgumentCaptor.forClass(Complaint.class);
        then(complaintRepository).should().save(complaintCaptor.capture());
        return complaintCaptor.getValue();
    }

    private ComplaintDetail savedDetail() {
        ArgumentCaptor<ComplaintDetail> complaintDetailCaptor = ArgumentCaptor.forClass(ComplaintDetail.class);
        then(complaintDetailRepository).should().save(complaintDetailCaptor.capture());
        return complaintDetailCaptor.getValue();
    }

    private static Conversation conversationWithDraft(String location, String symptom, List<String> missingFields) {
        Conversation conversation = conversation();
        conversation.applyAiResponse(summaryCardResponse(location, symptom, missingFields));
        return conversation;
    }

    private static Conversation conversation() {
        return withId(Conversation.builder()
            .user(user(RESIDENT_ID))
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build(), CONVERSATION_ID);
    }

    private static AiConverseResponse summaryCardResponse(String location, String symptom,
                                                          List<String> missingFields) {
        return aiResponse(AiRoute.COMPLAINT, new AiConverseResponse.Result(
            new AiConverseResponse.DraftPatch(location, symptom, null), null, missingFields, List.of()));
    }

    private static AiConverseResponse qaCardResponse(String question) {
        return aiResponse(AiRoute.KNOWLEDGE, new AiConverseResponse.Result(
            null, new AiConverseResponse.QaCardDraft(question), List.of(), List.of()));
    }

    private static AiConverseResponse answeredQuestionResponse() {
        AiConverseResponse.Citation citation =
            new AiConverseResponse.Citation("building_document", "guide-1", "생활 안내", null, null);
        return aiResponse(AiRoute.KNOWLEDGE, new AiConverseResponse.Result(null, null, List.of(), List.of(citation)));
    }

    private static AiConverseResponse aiResponse(AiRoute route, AiConverseResponse.Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace-1",
            new AiConverseResponse.Data(route, AiComplaintState.COLLECTING, "확인해 주세요", result));
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "입주민", null), id);
    }

    private static Room livingRoom(User resident) {
        Room room = new Room(new Building(user(99L), "서울시 테스트로 1", "테스트빌"), "302");
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
