package com.homes.zipsai.global.config;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.user.domain.Terms;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 로컬 개발용 더미 데이터. 건물 등록, 호실 생성, 초대 API가 구현되기 전까지 채팅/민원 기능을 확인하기 위해 사용한다.
 *
 * <p>모든 계정 비밀번호는 {@value #PASSWORD}이다. 관리자 계정이 이미 있으면 다시 만들지 않는다.
 */
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalDummyDataInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDummyDataInitializer.class);
    private static final String PASSWORD = "Asdf!12345";
    private static final String MANAGER_EMAIL = "manager@zipsai.com";
    private static final List<String> ROOM_NOS = List.of("101", "102", "201", "202", "301", "302");

    private final UserRepository userRepository;
    private final TermsRepository termsRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createTermsIfEmpty();
        if (userRepository.existsByEmail(MANAGER_EMAIL)) {
            return;
        }
        User manager = createUser(MANAGER_EMAIL, "김관리", "010-1234-5678", UserRole.MANAGER);
        Building building = buildingRepository.save(Building.builder()
            .manager(manager)
            .buildingName("A타워")
            .roadAddress("서울 강남구 역삼동 123-4")
            .build());
        Map<String, Room> rooms = ROOM_NOS.stream()
            .map(roomNo -> roomRepository.save(Room.builder().building(building).roomNo(roomNo).build()))
            .collect(Collectors.toMap(Room::getRoomNo, room -> room));

        moveIn(rooms.get("302"), createUser("resident@zipsai.com", "박입주", "010-9876-5432", UserRole.RESIDENT));
        moveIn(rooms.get("301"), createUser("resident2@zipsai.com", "이입주", "010-2222-3333", UserRole.RESIDENT));
        // 역할만 선택하고 호실에 연결되지 않은 입주민 (403 확인용)
        createUser("resident3@zipsai.com", "최대기", "010-4444-5555", UserRole.RESIDENT);

        LOG.info("로컬 더미 데이터 생성 완료: {}, resident@zipsai.com(302호), resident2@zipsai.com(301호), "
            + "resident3@zipsai.com(미연결) / 비밀번호 {}", MANAGER_EMAIL, PASSWORD);
    }

    private void createTermsIfEmpty() {
        if (termsRepository.count() > 0) {
            return;
        }
        LocalDate effectiveAt = LocalDate.of(2026, 1, 1);
        termsRepository.saveAll(List.of(
            terms(TermsType.SERVICE, "서비스 이용약관", effectiveAt),
            terms(TermsType.PRIVACY, "개인정보 수집 및 이용 동의", effectiveAt),
            terms(TermsType.MARKETING, "마케팅 정보 수신 동의", effectiveAt)));
    }

    private Terms terms(TermsType type, String title, LocalDate effectiveAt) {
        return Terms.builder()
            .termsType(type)
            .version(1)
            .title(title)
            .content("로컬 개발용 약관 본문입니다.")
            .effectiveAt(effectiveAt)
            .build();
    }

    private User createUser(String email, String userName, String phone, UserRole role) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), userName, phone);
        for (TermsType type : TermsType.values()) {
            user.agree(termsRepository.getLatest(type), type != TermsType.MARKETING);
        }
        user.selectRole(role);
        return userRepository.save(user);
    }

    private void moveIn(Room room, User resident) {
        room.invite();
        room.moveIn(resident);
    }
}
