package com.homes.zipsai.conversation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

@TestComponent
public class ConversationTestFixture {

    static final String PASSWORD = "Asdf!12345";

    private final UserRepository userRepository;
    private final TermsRepository termsRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final PasswordEncoder passwordEncoder;

    public ConversationTestFixture(UserRepository userRepository, TermsRepository termsRepository,
                                   BuildingRepository buildingRepository, RoomRepository roomRepository,
                                   PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.termsRepository = termsRepository;
        this.buildingRepository = buildingRepository;
        this.roomRepository = roomRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public String livingResident(String roomNo) {
        User manager = user(UserRole.MANAGER);
        Building building = buildingRepository.save(Building.builder()
            .manager(manager).buildingName("테스트타워").roadAddress("서울 강남구 테스트로 1").build());
        Room room = roomRepository.save(Room.builder().building(building).roomNo(roomNo).build());
        User resident = user(UserRole.RESIDENT);
        room.invite();
        room.moveIn(resident);
        return resident.getEmail();
    }

    @Transactional
    public String unconnectedResident() {
        return user(UserRole.RESIDENT).getEmail();
    }

    public String login(MockMvc mvc, ObjectMapper json, String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("accessToken").asText();
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", passwordEncoder.encode(PASSWORD), "테스트", null);
        for (TermsType type : TermsType.values()) {
            user.agree(termsRepository.getLatest(type), true);
        }
        user.selectRole(role);
        return userRepository.save(user);
    }
}
