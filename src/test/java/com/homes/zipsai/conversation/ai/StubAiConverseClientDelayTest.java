package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubAiConverseClientDelayTest {

    private static final Duration SHORT_DELAY = Duration.ofMillis(200);
    private static final Duration LONG_DELAY = Duration.ofSeconds(5);
    private static final Duration MEDIAN = Duration.ofMillis(12700);
    private static final double SIGMA = 0.32;
    private static final int SAMPLE_COUNT = 10_001;

    @Test
    @DisplayName("민원 경로는 민원 지연만큼 기다린 뒤 응답한다")
    void waitsComplaintDelayOnComplaintRoute() {
        StubAiConverseClient client = new StubAiConverseClient(SHORT_DELAY, 0, LONG_DELAY, 0);

        Duration elapsed = measure(client, "안방 천장에서 물이 떨어지고 있습니다");

        assertThat(elapsed).isGreaterThanOrEqualTo(SHORT_DELAY).isLessThan(LONG_DELAY);
    }

    @Test
    @DisplayName("규정 질의 경로는 규정 질의 지연만큼 기다린 뒤 응답한다")
    void waitsKnowledgeDelayOnKnowledgeRoute() {
        StubAiConverseClient client = new StubAiConverseClient(LONG_DELAY, 0, SHORT_DELAY, 0);

        Duration elapsed = measure(client, "분리수거는 어디에서 하나요?");

        assertThat(elapsed).isGreaterThanOrEqualTo(SHORT_DELAY).isLessThan(LONG_DELAY);
    }

    @Test
    @DisplayName("되묻기 경로는 기다리지 않고 바로 응답한다")
    void answersClarifyRouteWithoutDelay() {
        StubAiConverseClient client = new StubAiConverseClient(LONG_DELAY, 0, LONG_DELAY, 0);

        Duration elapsed = measure(client, "네");

        assertThat(elapsed).isLessThan(SHORT_DELAY);
    }

    @Test
    @DisplayName("분포 폭이 0이면 항상 중앙값만큼 기다린다")
    void returnsMedianWhenSigmaIsZero() {
        StubAiConverseClient.LogNormalDelay delay = new StubAiConverseClient.LogNormalDelay(MEDIAN, 0);

        assertThat(sampleMillis(delay)).containsOnly(MEDIAN.toMillis());
    }

    @Test
    @DisplayName("뽑은 지연의 중앙값은 설정한 중앙값에 가깝다")
    void samplesAroundConfiguredMedian() {
        StubAiConverseClient.LogNormalDelay delay = new StubAiConverseClient.LogNormalDelay(MEDIAN, SIGMA);

        List<Long> samples = sampleMillis(delay);

        assertThat(percentile(samples, 0.5)).isCloseTo(MEDIAN.toMillis(), within(MEDIAN.toMillis() / 20));
    }

    @Test
    @DisplayName("뽑은 지연의 90번째 백분위는 로그정규분포 값에 가깝다")
    void spreadsSamplesBySigma() {
        StubAiConverseClient.LogNormalDelay delay = new StubAiConverseClient.LogNormalDelay(MEDIAN, SIGMA);
        long expectedP90 = Math.round(MEDIAN.toMillis() * Math.exp(1.2816 * SIGMA));

        List<Long> samples = sampleMillis(delay);

        assertThat(percentile(samples, 0.9)).isCloseTo(expectedP90, within(expectedP90 / 10));
    }

    private Duration measure(StubAiConverseClient client, String text) {
        AiConverseRequest request = new AiConverseRequest(1L, "101", "1", "1", "turn-1", "trace-1", null, null,
            new AiConverseRequest.MessagePayload("1", text, List.of()), List.of(), null);
        long started = System.nanoTime();
        client.converse(request);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private List<Long> sampleMillis(StubAiConverseClient.LogNormalDelay delay) {
        return IntStream.range(0, SAMPLE_COUNT)
            .mapToObj(index -> delay.next().toMillis())
            .sorted()
            .toList();
    }

    private long percentile(List<Long> sortedSamples, double ratio) {
        return sortedSamples.get((int) (ratio * (sortedSamples.size() - 1)));
    }
}
