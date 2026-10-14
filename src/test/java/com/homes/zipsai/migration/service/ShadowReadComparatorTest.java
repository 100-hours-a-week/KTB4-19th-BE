package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadQuery;

import tools.jackson.databind.json.JsonMapper;

class ShadowReadComparatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    ShadowReadComparator shadowReadComparator = new ShadowReadComparator(JsonMapper.builder().build());

    @Test
    @DisplayName("값이 같으면 같은 결과로 본다")
    void treatsEqualValuesAsSame() {
        ShadowReadComparison comparison = compare(new Photo(1L, "a.jpg"), new Photo(1L, "a.jpg"));

        assertThat(comparison.isSame()).isTrue();
    }

    @Test
    @DisplayName("값이 다르면 다른 결과로 본다")
    void treatsDifferentValuesAsDifferent() {
        ShadowReadComparison comparison = compare(new Photo(1L, "a.jpg"), new Photo(2L, "a.jpg"));

        assertThat(comparison.isSame()).isFalse();
    }

    @Test
    @DisplayName("요청마다 서명이 바뀌는 URL은 쿼리를 빼고 비교한다")
    void ignoresUrlQuery() {
        ShadowReadComparison comparison = compare(
            new Photo(1L, "https://s3.test/a.jpg?X-Amz-Signature=one&X-Amz-Date=20261013"),
            new Photo(1L, "https://s3.test/a.jpg?X-Amz-Signature=two&X-Amz-Date=20261014"));

        assertThat(comparison.isSame()).isTrue();
    }

    @Test
    @DisplayName("V2 결과를 먼저 받아도 V1과 V2 값을 제자리에 둔다")
    void keepsDatabaseSidesRegardlessOfArgumentOrder() {
        ShadowReadComparison comparison = shadowReadComparator.compare(
            query(MigrationDatabase.V2, page(item(1L, "2026-10-14T12:00:02"))),
            query(MigrationDatabase.V1, page(item(1L, "2026-10-14T12:00:05"))));

        assertThat(comparison.fieldDifferences()).containsExactly(
            new FieldDifference("1", "lastMessageAt", "2026-10-14T12:00:05", "2026-10-14T12:00:02"));
    }

    @Test
    @DisplayName("한쪽 목록에만 있는 ID를 나눠 남긴다")
    void separatesIdsFoundOnlyInOneDatabase() {
        ShadowReadComparison comparison = compare(
            page(item(3L, "2026-10-14T12:00:03"), item(1L, "2026-10-14T12:00:01")),
            page(item(1L, "2026-10-14T12:00:01"), item(2L, "2026-10-14T12:00:00")));

        assertThat(comparison.onlyInV1Ids()).containsExactly("3");
        assertThat(comparison.onlyInV2Ids()).containsExactly("2");
    }

    @Test
    @DisplayName("같은 ID들의 순서만 다르면 순서 차이로 본다")
    void detectsOrderDifference() {
        ShadowReadComparison comparison = compare(
            page(item(2L, "2026-10-14T12:00:00"), item(1L, "2026-10-14T12:00:00")),
            page(item(1L, "2026-10-14T12:00:00"), item(2L, "2026-10-14T12:00:00")));

        assertThat(comparison.orderDiffers()).isTrue();
        assertThat(comparison.fieldDifferences()).isEmpty();
    }

    @Test
    @DisplayName("상태와 시각 필드는 양쪽 값을 그대로 남긴다")
    void keepsPlainValuesOfStatusAndTimeFields() {
        ShadowReadComparison comparison = compare(
            page(new ConversationItem(1L, "보일러 고장", "ACTIVE", "2026-10-14T12:00:05")),
            page(new ConversationItem(1L, "보일러 고장", "CLOSED", "2026-10-14T12:00:02")));

        assertThat(comparison.fieldDifferences()).containsExactly(
            new FieldDifference("1", "statusCode", "ACTIVE", "CLOSED"),
            new FieldDifference("1", "lastMessageAt", "2026-10-14T12:00:05", "2026-10-14T12:00:02"));
    }

    @Test
    @DisplayName("제목 같은 필드는 원문 대신 해시를 남긴다")
    void hashesSensitiveFields() {
        ShadowReadComparison comparison = compare(
            page(new ConversationItem(1L, "보일러 고장", "ACTIVE", "2026-10-14T12:00:00")),
            page(new ConversationItem(1L, "누수 문의", "ACTIVE", "2026-10-14T12:00:00")));

        FieldDifference difference = comparison.fieldDifferences().getFirst();
        assertThat(difference.field()).isEqualTo("conversationTitle");
        assertThat(difference.v1Value()).startsWith("sha256:").doesNotContain("보일러");
        assertThat(difference.v2Value()).startsWith("sha256:").isNotEqualTo(difference.v1Value());
    }

    @Test
    @DisplayName("목록 밖의 필드 차이는 $ 항목으로 남긴다")
    void reportsRootFieldDifference() {
        ShadowReadComparison comparison = compare(new Page(true, List.of()), new Page(false, List.of()));

        assertThat(comparison.fieldDifferences()).containsExactly(new FieldDifference("$", "hasNext", "true", "false"));
    }

    @Test
    @DisplayName("차이는 20개까지만 남기고 전체 개수는 따로 남긴다")
    void limitsLoggedDifferences() {
        List<ConversationItem> v1Items = new ArrayList<>();
        List<ConversationItem> v2Items = new ArrayList<>();
        for (long id = 1; id <= 25; id++) {
            v1Items.add(new ConversationItem(id, "제목", "ACTIVE", "2026-10-14T12:00:05"));
            v2Items.add(new ConversationItem(id, "제목", "ACTIVE", "2026-10-14T12:00:02"));
        }

        ShadowReadComparison comparison = compare(new Page(false, v1Items), new Page(false, v2Items));

        assertThat(comparison.fieldDifferences()).hasSize(20);
        assertThat(comparison.fieldDifferenceCount()).isEqualTo(25);
    }

    private ShadowReadComparison compare(Object v1Result, Object v2Result) {
        return shadowReadComparator.compare(query(MigrationDatabase.V1, v1Result),
            query(MigrationDatabase.V2, v2Result));
    }

    private static ShadowReadQuery query(MigrationDatabase database, Object result) {
        return new ShadowReadQuery(database, NOW, NOW, result);
    }

    private static Page page(ConversationItem... items) {
        return new Page(false, List.of(items));
    }

    private static ConversationItem item(Long id, String lastMessageAt) {
        return new ConversationItem(id, "제목", "ACTIVE", lastMessageAt);
    }

    record Photo(Long id, String url) {
    }

    record Page(boolean hasNext, List<ConversationItem> items) {
    }

    record ConversationItem(Long conversationId, String conversationTitle, String statusCode, String lastMessageAt) {
    }
}
