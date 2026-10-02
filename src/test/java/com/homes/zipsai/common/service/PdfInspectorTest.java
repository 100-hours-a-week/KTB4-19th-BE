package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PdfInspectorTest {

    @Test
    @DisplayName("일반 PDF는 암호화되지 않은 것으로 본다")
    void plainPdfIsNotEncrypted() {
        assertThat(PdfInspector.isEncrypted(PdfFixtures.plainPdf())).isFalse();
    }

    @Test
    @DisplayName("열람 비밀번호가 걸린 PDF는 암호화된 것으로 본다")
    void userPasswordPdfIsEncrypted() {
        assertThat(PdfInspector.isEncrypted(PdfFixtures.userPasswordPdf())).isTrue();
    }

    @Test
    @DisplayName("복사·인쇄 권한만 제한한 PDF도 암호화된 것으로 본다")
    void ownerPasswordOnlyPdfIsEncrypted() {
        assertThat(PdfInspector.isEncrypted(PdfFixtures.ownerPasswordOnlyPdf())).isTrue();
    }

    @Test
    @DisplayName("PDF로 읽을 수 없는 파일은 암호화되지 않은 것으로 본다")
    void unreadableFileIsNotEncrypted() {
        assertThat(PdfInspector.isEncrypted("not a pdf".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
