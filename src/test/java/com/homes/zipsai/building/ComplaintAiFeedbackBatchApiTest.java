package com.homes.zipsai.building;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.building.batch.ComplaintAiFeedbackAlreadyRunningException;
import com.homes.zipsai.building.batch.ComplaintAiFeedbackJobRunner;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest(classes = ZipsaiBackendApplication.class,
    properties = "app.complaint.ai-feedback.operator-ids=9001")
@AutoConfigureMockMvc
@DisplayName("민원 답변 AI 피드백 배치 수동 실행 API")
class ComplaintAiFeedbackBatchApiTest {

    private static final String SEND = "/api/v1/managers/complaint-ai-feedback/send";
    private static final String DELETE = "/api/v1/managers/complaint-ai-feedback/delete";
    private static final long OPERATOR_ID = 9001L;

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokenService;

    @MockitoBean
    ComplaintAiFeedbackJobRunner jobRunner;

    @Test
    @DisplayName("허용된 관리자가 전송을 요청하면 202와 실행 ID를 반환한다")
    void startsSendForOperator() throws Exception {
        given(jobRunner.startSend(OPERATOR_ID)).willReturn("run-1");

        mvc.perform(post(SEND).header("Authorization", bearer(OPERATOR_ID, UserRole.MANAGER)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.runId").value("run-1"))
            .andExpect(jsonPath("$.data.job").value("SEND"));
    }

    @Test
    @DisplayName("허용된 관리자가 삭제를 요청하면 삭제 배치를 실행한다")
    void startsDeleteForOperator() throws Exception {
        given(jobRunner.startDelete(OPERATOR_ID)).willReturn("run-2");

        mvc.perform(post(DELETE).header("Authorization", bearer(OPERATOR_ID, UserRole.MANAGER)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.job").value("DELETE"));
    }

    @Test
    @DisplayName("허용 목록에 없는 관리자는 403을 받고 배치를 실행하지 않는다")
    void rejectsManagerNotInOperators() throws Exception {
        mvc.perform(post(SEND).header("Authorization", bearer(9002L, UserRole.MANAGER)))
            .andExpect(status().isForbidden());

        verify(jobRunner, never()).startSend(anyLong());
    }

    @Test
    @DisplayName("입주민은 허용 ID와 같아도 403을 받는다")
    void rejectsResident() throws Exception {
        mvc.perform(post(SEND).header("Authorization", bearer(OPERATOR_ID, UserRole.RESIDENT)))
            .andExpect(status().isForbidden());

        verify(jobRunner, never()).startSend(anyLong());
    }

    @Test
    @DisplayName("토큰 없이 요청하면 401을 받는다")
    void rejectsWithoutToken() throws Exception {
        mvc.perform(post(SEND)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이미 실행 중이면 409를 반환한다")
    void returnsConflictWhenAlreadyRunning() throws Exception {
        given(jobRunner.startSend(OPERATOR_ID)).willThrow(new ComplaintAiFeedbackAlreadyRunningException());

        mvc.perform(post(SEND).header("Authorization", bearer(OPERATOR_ID, UserRole.MANAGER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("AI_FEEDBACK_BATCH_ALREADY_RUNNING"));
    }

    private String bearer(long userId, UserRole role) {
        User user = new User("operator@example.com", "password", "운영자", null);
        user.selectRole(role);
        ReflectionTestUtils.setField(user, "id", userId);
        return "Bearer " + tokenService.access(user, UUID.randomUUID().toString());
    }
}
