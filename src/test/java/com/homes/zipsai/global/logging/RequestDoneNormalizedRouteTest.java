package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class RequestDoneNormalizedRouteTest {

    RequestIdFilter requestIdFilter = new RequestIdFilter(new StructuredLogger(JsonMapper.builder().build()));

    @Test
    @DisplayName("요청 완료 로그에 ID가 빠진 경로 패턴을 남긴다")
    void logsMatchedPattern(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/conversations/7");

        requestIdFilter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/conversations/{conversationId}"));

        assertThat(output.getOut()).contains("\"normalized_route\":\"/api/v1/conversations/{conversationId}\"");
    }

    @Test
    @DisplayName("매핑되지 않은 요청은 UNMATCHED로 남긴다")
    void logsUnmatchedRequest(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unknown/7");

        requestIdFilter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(output.getOut()).contains("\"normalized_route\":\"UNMATCHED\"");
    }
}
