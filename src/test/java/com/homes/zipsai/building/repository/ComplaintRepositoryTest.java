package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class ComplaintRepositoryTest {

    @Autowired
    ComplaintRepository complaintRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    FileRepository fileRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TestEntityManager entityManager;

    private User manager;

    private User resident;

    private Building building;

    @BeforeEach
    void setUp() {
        manager = userRepository.save(new User("manager@example.com", "password", "관리자", null));
        resident = userRepository.save(new User("resident@example.com", "password", "입주민", null));
        building = buildingRepository.save(Building.builder()
            .manager(manager)
            .roadAddress("서울시 강남구 테헤란로 1")
            .buildingName("집사이 빌딩")
            .build());
    }

    @Test
    @DisplayName("관리자 민원 목록은 첨부 사진을 함께 조회한다")
    void fetchesAttachmentWithManagerComplaints() {
        saveComplaint(saveFile("leak.jpg"));
        entityManager.clear();

        Slice<Complaint> found = findManagerComplaints();

        assertThat(found.getContent()).singleElement()
            .satisfies(complaint -> assertThat(Hibernate.isInitialized(complaint.getAttachment())).isTrue());
    }

    @Test
    @DisplayName("첨부 사진이 없는 민원도 관리자 민원 목록에 나온다")
    void includesComplaintWithoutAttachment() {
        saveComplaint(null);
        entityManager.clear();

        Slice<Complaint> found = findManagerComplaints();

        assertThat(found.getContent()).singleElement()
            .extracting(Complaint::getAttachment)
            .isNull();
    }

    @Test
    @DisplayName("관리자 민원 목록은 담당 건물의 민원만 조회한다")
    void findsOnlyComplaintsOfManagedBuilding() {
        saveComplaint(null);
        User otherManager = userRepository.save(new User("other@example.com", "password", "다른 관리자", null));
        Building otherBuilding = buildingRepository.save(Building.builder()
            .manager(otherManager)
            .roadAddress("서울시 강남구 테헤란로 2")
            .buildingName("다른 빌딩")
            .build());
        saveComplaint(otherBuilding, null);
        entityManager.clear();

        Slice<Complaint> found = findManagerComplaints();

        assertThat(found.getContent()).singleElement()
            .satisfies(complaint -> assertThat(complaint.getBuilding().getId()).isEqualTo(building.getId()));
    }

    private Slice<Complaint> findManagerComplaints() {
        return complaintRepository.findManagerComplaints(manager.getId(), null, List.of(ComplaintStatus.PENDING),
            false, Complaint.URGENCY_THRESHOLD, PageRequest.of(0, 10));
    }

    private void saveComplaint(File attachment) {
        saveComplaint(building, attachment);
    }

    private void saveComplaint(Building complaintBuilding, File attachment) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("천장에서 물이 새요")
            .build());
        complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(complaintBuilding)
            .attachment(attachment)
            .title("천장 누수")
            .urgency(5)
            .roomNo("101")
            .build());
    }

    private File saveFile(String key) {
        return fileRepository.save(File.builder()
            .fileKey(key)
            .fileSize(1024)
            .fileType("jpg")
            .originalName(key)
            .build());
    }
}
