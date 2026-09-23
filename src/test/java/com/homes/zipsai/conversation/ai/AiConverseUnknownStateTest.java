package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class AiConverseUnknownStateTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("계약에 없는 상태가 와도 응답을 읽는다")
    void readsResponseWithUnknownState() {
        AiConverseResponse response = read("ready_to_confirm");

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("계약에 없는 상태는 비워 둔다")
    void leavesUnknownStateEmpty() {
        assertThat(read("ready_to_confirm").nextComplaintState()).isNull();
    }

    @Test
    @DisplayName("계약에 있는 상태는 그대로 읽는다")
    void readsStateInContract() {
        assertThat(read("collecting").nextComplaintState()).isEqualTo(AiComplaintState.COLLECTING);
    }

    @Test
    @DisplayName("상태를 읽지 못해도 답변과 민원 초안은 남는다")
    void keepsReplyAndDraftWhenStateIsUnknown() {
        AiConverseResponse response = read("ready_to_confirm");

        assertThat(response.reply()).isEqualTo("민원 정보를 확인했습니다.");
        assertThat(response.draftPatch().location()).isEqualTo("카테부");
        assertThat(response.draftPatch().symptom()).isEqualTo("천장무너짐");
    }

    @Test
    @DisplayName("상태를 읽지 못해도 모아야 할 정보가 없으면 접수로 판단한다")
    void completesByMissingFieldsWhenStateIsUnknown() {
        assertThat(read("ready_to_confirm").isConversationComplete()).isTrue();
    }

    private static AiConverseResponse read(String nextComplaintState) {
        String body = """
            {
              "code": "ai_response_success",
              "trace_id": "2606cdbc-d448-4ab3-8ef3-95d8cdb80f3d",
              "data": {
                "route": "complaint",
                "next_complaint_state": "%s",
                "reply": "민원 정보를 확인했습니다.",
                "result": {
                  "complaint_draft": {
                    "location": "카테부",
                    "symptom": "천장무너짐",
                    "occurred_at": null
                  },
                  "missing_fields": [],
                  "citations": []
                }
              }
            }
            """.formatted(nextComplaintState);
        return MAPPER.readValue(body, AiConverseResponse.class);
    }
}
