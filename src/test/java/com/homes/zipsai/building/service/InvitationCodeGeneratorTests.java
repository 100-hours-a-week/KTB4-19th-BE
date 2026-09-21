package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvitationCodeGeneratorTests {

    @Test
    void generatesSixUppercaseCharactersWithoutAmbiguousSymbols() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();

        assertThat(generator.generate()).matches("[A-HJ-NP-Z2-9]{6}");
    }
}
