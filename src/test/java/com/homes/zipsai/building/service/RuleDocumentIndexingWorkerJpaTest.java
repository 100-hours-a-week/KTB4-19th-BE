package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus;
import com.homes.zipsai.building.domain.RuleDocumentIndexingStatus;
import com.homes.zipsai.building.repository.RuleDocumentIndexingOutboxRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.conversation.ai.AiIndexingResponse;
import com.homes.zipsai.conversation.ai.AiIndexingResponse.Result;
import com.homes.zipsai.user.domain.User;

@DataJpaTest
@Import({RuleDocumentIndexingWorker.class, RuleDocumentIndexingWorkerJpaTest.Config.class})
class RuleDocumentIndexingWorkerJpaTest {

    @TestConfiguration
    static class Config {
        @Bean
        StorageProperties storageProperties() {
            return new StorageProperties(null, null, "test-bucket", 0, 0);
        }
    }

    @Autowired
    RuleDocumentIndexingWorker worker;

    @Autowired
    RuleDocumentIndexingOutboxRepository outboxRepository;

    @Autowired
    RuleDocumentRepository documentRepository;

    @Autowired
    TestEntityManager entityManager;

    @MockitoBean
    AiIndexingClient aiIndexingClient;

    private RuleDocument document;
    private Long outboxId;

    @BeforeEach
    void setUp() {
        User manager = entityManager.persist(new User("manager@example.com", "password", "관리자", null));
        Building building = entityManager.persist(new Building(manager, "서울시 강남구 테헤란로 1", "집사이 빌딩"));
        File file = entityManager.persist(new File("documents/rule.pdf", 100, "pdf", "rule.pdf"));
        document = entityManager.persist(new RuleDocument(building, file, "관리규약", "", 1));
        outboxId = entityManager.persist(RuleDocumentIndexingOutbox.index(document, "trace-1",
            LocalDateTime.now().minusSeconds(1))).getId();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("색인 성공 결과를 저장하면 아웃박스도 완료로 남아 같은 문서를 다시 보내지 않는다")
    void marksOutboxDoneWhenIndexSucceeded() {
        givenResult(new Result(AiIndexingResponse.KIND_INDEX, String.valueOf(document.getId()), "succeeded", null));

        worker.processDue();
        entityManager.clear();

        assertThat(outboxRepository.findById(outboxId).orElseThrow().getStatus())
            .isEqualTo(RuleDocumentIndexingOutboxStatus.DONE);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getIndexingStatus())
            .isEqualTo(RuleDocumentIndexingStatus.INDEXED);
        assertThat(worker.processDue()).isZero();
    }

    @Test
    @DisplayName("색인 실패 결과를 저장하면 아웃박스도 실패로 남는다")
    void marksOutboxFailedWhenIndexFailed() {
        givenResult(new Result(AiIndexingResponse.KIND_INDEX, String.valueOf(document.getId()), "failed",
            "PARSE_FAILED", false));

        worker.processDue();
        entityManager.clear();

        RuleDocumentIndexingOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.FAILED);
        assertThat(outbox.getLastError()).isEqualTo("PARSE_FAILED");
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getIndexingStatus())
            .isEqualTo(RuleDocumentIndexingStatus.FAILED);
    }

    private void givenResult(Result result) {
        given(aiIndexingClient.index(any())).willReturn(new AiIndexingResponse("trace-1", List.of(result)));
    }
}
