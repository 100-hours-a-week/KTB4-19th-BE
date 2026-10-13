package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ShadowReadComparatorTest {

    ShadowReadComparator shadowReadComparator = new ShadowReadComparator(JsonMapper.builder().build());

    @Test
    @DisplayName("값이 같으면 같은 결과로 본다")
    void treatsEqualValuesAsSame() {
        Photo v1Photo = new Photo(1L, "a.jpg");
        Photo v2Photo = new Photo(1L, "a.jpg");

        boolean same = shadowReadComparator.isSame(v1Photo, v2Photo);

        assertThat(same).isTrue();
    }

    @Test
    @DisplayName("값이 다르면 다른 결과로 본다")
    void treatsDifferentValuesAsDifferent() {
        Photo v1Photo = new Photo(1L, "a.jpg");
        Photo v2Photo = new Photo(2L, "a.jpg");

        boolean same = shadowReadComparator.isSame(v1Photo, v2Photo);

        assertThat(same).isFalse();
    }

    @Test
    @DisplayName("요청마다 서명이 바뀌는 URL은 쿼리를 빼고 비교한다")
    void ignoresUrlQuery() {
        Photo v1Photo = new Photo(1L, "https://s3.test/a.jpg?X-Amz-Signature=one&X-Amz-Date=20261013");
        Photo v2Photo = new Photo(1L, "https://s3.test/a.jpg?X-Amz-Signature=two&X-Amz-Date=20261014");

        boolean same = shadowReadComparator.isSame(v1Photo, v2Photo);

        assertThat(same).isTrue();
    }

    record Photo(Long id, String url) {
    }
}
