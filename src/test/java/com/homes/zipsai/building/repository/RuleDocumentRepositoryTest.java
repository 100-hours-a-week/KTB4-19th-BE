package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.user.domain.User;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RuleDocumentRepositoryTest {

    private static final int DOCUMENT_COUNT = 5;

    @Autowired
    RuleDocumentRepository documentRepository;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    private Building building;

    @BeforeEach
    void setUp() {
        User manager = entityManager.persist(new User("manager@example.com", "password", "관리자", null));
        building = entityManager.persist(new Building(manager, "서울시 강남구 테헤란로 1", "집사이 빌딩"));
        for (int i = 0; i < DOCUMENT_COUNT; i++) {
            File file = entityManager.persist(
                new File("documents/rule-" + i + ".pdf", 100, "pdf", "rule-" + i + ".pdf"));
            entityManager.persist(new RuleDocument(building, file, "규칙 " + i, "", 1));
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("문서 목록은 첨부파일을 함께 조회해 문서 수와 관계없이 쿼리 1번으로 끝난다")
    void fetchesAttachmentsInSingleQuery() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<RuleDocument> documents =
            documentRepository.findAllByBuilding_IdAndValidTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(building.getId());
        documents.forEach(document -> document.getAttachment().getFileKey());

        assertThat(documents).hasSize(DOCUMENT_COUNT)
            .allSatisfy(document -> assertThat(Hibernate.isInitialized(document.getAttachment())).isTrue());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("색인용 유효 문서 ID는 문서 ID만 조회한다")
    void findsValidDocumentIds() {
        List<Long> ids = documentRepository.findValidIdsByBuildingId(building.getId());

        assertThat(ids).hasSize(DOCUMENT_COUNT);
    }
}
