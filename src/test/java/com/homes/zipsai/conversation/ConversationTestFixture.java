package com.homes.zipsai.conversation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;

@TestComponent
public class ConversationTestFixture {

    private final UserRepository userRepository;
    private final TermsRepository termsRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final FileRepository fileRepository;

    public ConversationTestFixture(UserRepository userRepository, TermsRepository termsRepository,
                                   BuildingRepository buildingRepository, RoomRepository roomRepository,
                                   FileRepository fileRepository) {
        this.userRepository = userRepository;
        this.termsRepository = termsRepository;
        this.buildingRepository = buildingRepository;
        this.roomRepository = roomRepository;
        this.fileRepository = fileRepository;
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

    @Transactional
    public long uploadedFile(String email, String fileType) {
        File file = pendingFile(email, fileType);
        file.markUploaded(1024, fileType);
        return file.getId();
    }

    public RequestPostProcessor authenticatedAs(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        AuthPrincipal principal = new AuthPrincipal(user.getId(), UUID.randomUUID().toString());
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private File pendingFile(String email, String fileType) {
        File file = new File(UUID.randomUUID() + "." + fileType, 1024, fileType, "photo." + fileType);
        file.assignOwner(userRepository.findByEmail(email).orElseThrow());
        return fileRepository.save(file);
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        for (TermsType type : TermsType.values()) {
            user.agree(termsRepository.getLatest(type), true);
        }
        user.selectRole(role);
        return userRepository.save(user);
    }
}
