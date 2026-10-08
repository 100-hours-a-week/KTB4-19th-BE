package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageFileGroupTest {

    @Test
    @DisplayName("사진 요약은 100자, 사진 속 글자는 200자까지 잘라 저장한다")
    void truncatesLongAnalysis() {
        MessageFileGroup fileGroup = MessageFileGroup.builder().fileGroupSeq(1).build();

        fileGroup.recordAnalysis("가".repeat(150), "나".repeat(250));

        assertThat(fileGroup.getSummary()).hasSize(100);
        assertThat(fileGroup.getOcrText()).hasSize(200);
    }
}
