package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("초대코드 생성기")
class InvitationCodeGeneratorTests {

    @Test
    @DisplayName("생성한 코드는 여섯 자리이며 혼동되는 기호를 포함하지 않는다")
    void generatesSixUppercaseCharactersWithoutAmbiguousSymbols() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();

        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(generator.generate()).matches("[A-HJ-NP-Z2-9]{6}");
        }
    }
}
