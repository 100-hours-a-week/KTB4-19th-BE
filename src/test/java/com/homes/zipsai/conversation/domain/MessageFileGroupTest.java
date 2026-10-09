package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageFileGroupTest {

    @Test
    @DisplayName("사진 요약과 사진 속 글자는 500자까지 잘라 저장한다")
    void truncatesLongAnalysis() {
        MessageFileGroup fileGroup = MessageFileGroup.builder().fileGroupSeq(1).build();

        fileGroup.recordAnalysis("가".repeat(501), "나".repeat(501));

        assertThat(fileGroup.getSummary()).hasSize(500);
        assertThat(fileGroup.getOcrText()).hasSize(500);
    }

    @Test
    @DisplayName("500자 이하 사진 요약과 사진 속 글자는 자르지 않고 저장한다")
    void keepsAnalysisUpToMaxLength() {
        MessageFileGroup fileGroup = MessageFileGroup.builder().fileGroupSeq(1).build();
        String summary = "가".repeat(500);
        String ocrText = "나".repeat(500);

        fileGroup.recordAnalysis(summary, ocrText);

        assertThat(fileGroup.getSummary()).isEqualTo(summary);
        assertThat(fileGroup.getOcrText()).isEqualTo(ocrText);
    }
}
