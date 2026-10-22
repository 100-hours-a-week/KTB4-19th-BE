package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.NotFoundException;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final String LOCK_TOKEN = "lock-token";

    @Mock
    ConversationService conversationService;

    @Mock
    ConversationReplyService conversationReplyService;

    @Mock
    ConversationReplyLock conversationReplyLock;

    ConversationMessageService conversationMessageService;

    @BeforeEach
    void setUp() {
        conversationMessageService = new ConversationMessageService(conversationService, conversationReplyService,
            conversationReplyLock);
    }

    @Test
    @DisplayName("첫 메시지를 저장하면 AI 답변을 맡기고 대화 ID를 바로 돌려준다")
    void handsOverReplyAndReturnsConversationId() {
        PendingAiReply pendingAiReply = pendingAiReply(true);
        given(conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of()))
            .willReturn(pendingAiReply);

        ConversationCreateResponse response =
            conversationMessageService.createConversation(RESIDENT_ID, "천장에서 물이 새요", List.of());

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        then(conversationReplyService).should().reply(pendingAiReply);
        then(conversationReplyLock).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("메시지를 저장하면 AI 답변을 맡기고 저장한 메시지를 바로 돌려준다")
    void handsOverReplyAndReturnsSavedMessage() {
        PendingAiReply pendingAiReply = pendingAiReply(false);
        given(conversationReplyLock.acquire(CONVERSATION_ID)).willReturn(LOCK_TOKEN);
        given(conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of(), LOCK_TOKEN))
            .willReturn(pendingAiReply);

        MessageSendResponse response =
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of());

        assertThat(response.messageId()).isEqualTo(20L);
        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        InOrder inOrder = inOrder(conversationReplyLock, conversationService, conversationReplyService);
        inOrder.verify(conversationReplyLock).acquire(CONVERSATION_ID);
        inOrder.verify(conversationService).saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of(),
            LOCK_TOKEN);
        inOrder.verify(conversationReplyService).reply(pendingAiReply);
    }

    @Test
    @DisplayName("AI 답을 기다리는 대화에 메시지를 보내면 저장하지 않고 거절한다")
    void rejectsMessageWhileAiIsResponding() {
        willThrow(new ConflictException(ConflictException.Reason.CONVERSATION_BUSY))
            .given(conversationReplyLock).acquire(CONVERSATION_ID);

        assertThatThrownBy(() ->
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "두 번째 메시지", List.of()))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_BUSY");
        then(conversationService).should(never())
            .saveNextMessage(anyLong(), anyLong(), anyString(), anyList(), anyString());
        then(conversationReplyService).should(never()).reply(any());
    }

    @Test
    @DisplayName("메시지를 저장하지 못하면 대화방 답변 잠금을 풀고 AI에 맡기지 않는다")
    void releasesLockWhenMessageCannotBeSaved() {
        given(conversationReplyLock.acquire(CONVERSATION_ID)).willReturn(LOCK_TOKEN);
        given(conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of(), LOCK_TOKEN))
            .willThrow(new NotFoundException(NotFoundException.Resource.CONVERSATION));

        assertThatThrownBy(() ->
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of()))
            .isInstanceOf(NotFoundException.class);
        then(conversationReplyLock).should().release(CONVERSATION_ID, LOCK_TOKEN);
        then(conversationReplyService).should(never()).reply(any());
    }

    private static PendingAiReply pendingAiReply(boolean newConversation) {
        Conversation conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        MessageResponse residentMessage =
            new MessageResponse(20L, SenderType.RESIDENT, MessageType.TEXT, "천장에서 물이 새요", List.of(), null, null);
        AiConverseRequest aiRequest =
            new AiConverseRequest(null, null, null, null, "turn-1", null, null, null, null, List.of(), null);
        return new PendingAiReply(900L, 0, LOCK_TOKEN, RESIDENT_ID, conversation, residentMessage, newConversation,
            null, aiRequest);
    }
}
