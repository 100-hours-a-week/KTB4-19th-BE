package com.homes.zipsai.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.user.domain.Terms;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.repository.TermsRepository;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
@Transactional
@DisplayName("공개 약관 API")
class TermsApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TermsRepository terms;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("인증 없이 최신 유효 약관의 타입과 제목을 목록으로 조회한다")
    void listsTheThreePublicTermsWithLatestTitlesWithoutAuthentication() throws Exception {
        save(TermsType.SERVICE, 2, "서비스 이용약관 v2", "service", LocalDate.now());
        save(TermsType.PRIVACY, 2, "개인정보 처리방침", "privacy", LocalDate.now());
        save(TermsType.MARKETING, 2, "마케팅 정보 수신 동의", "marketing", LocalDate.now());

        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terms.length()").value(3))
                .andExpect(jsonPath("$.data.terms[0].termsType").value("SERVICE"))
                .andExpect(jsonPath("$.data.terms[0].title").value("서비스 이용약관 v2"))
                .andExpect(jsonPath("$.data.terms[1].termsType").value("PRIVACY"))
                .andExpect(jsonPath("$.data.terms[1].title").value("개인정보 처리방침"))
                .andExpect(jsonPath("$.data.terms[2].termsType").value("MARKETING"))
                .andExpect(jsonPath("$.data.terms[2].title").value("마케팅 정보 수신 동의"));
    }

    @Test
    @DisplayName("인증 없이 미래·삭제 문서를 제외한 최신 약관 본문을 조회한다")
    void returnsLatestEffectiveContentWithoutAuthentication() throws Exception {
        save(TermsType.SERVICE, 2, "현재 약관", "현재 유효한 본문", LocalDate.now());
        save(TermsType.SERVICE, 3, "미래 약관", "미래 본문", LocalDate.now().plusDays(1));
        Terms deleted = save(TermsType.SERVICE, 4, "삭제 약관", "삭제 본문", LocalDate.now());
        jdbc.update("UPDATE Terms SET deleted_at = CURRENT_TIMESTAMP WHERE terms_id = ?", deleted.getId());

        mvc.perform(get("/api/v1/terms/SERVICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("현재 유효한 본문"));
    }

    @Test
    @DisplayName("허용되지 않은 약관 타입은 422 검증 오류를 반환한다")
    void rejectsUnsupportedTermsType() throws Exception {
        mvc.perform(get("/api/v1/terms/UNSUPPORTED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("termsType"))
                .andExpect(jsonPath("$.error.details.violations[0].reason")
                        .value("허용되지 않은 약관 타입입니다."))
                .andExpect(jsonPath("$.data").value((Object) null));
    }

    @Test
    @DisplayName("현재 유효한 약관 문서가 없으면 404를 반환한다")
    void returnsNotFoundWhenNoCurrentTermsExist() throws Exception {
        List<Terms> marketingTerms = terms.findAll().stream()
                .filter(term -> term.getTermsType() == TermsType.MARKETING)
                .toList();
        marketingTerms.forEach(term -> jdbc.update(
                "UPDATE Terms SET deleted_at = CURRENT_TIMESTAMP WHERE terms_id = ?", term.getId()));

        mvc.perform(get("/api/v1/terms/MARKETING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("요청한 약관 문서를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code").value("TERMS_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.field").value("termsType"))
                .andExpect(jsonPath("$.error.details.reason")
                        .value("해당 리소스가 존재하지 않거나 삭제되었습니다."))
                .andExpect(jsonPath("$.data").value((Object) null));
    }

    private Terms save(TermsType type, int version, String title, String content, LocalDate effectiveAt) {
        return terms.saveAndFlush(Terms.builder()
                .termsType(type)
                .version(version)
                .title(title)
                .content(content)
                .effectiveAt(effectiveAt)
                .build());
    }
}
