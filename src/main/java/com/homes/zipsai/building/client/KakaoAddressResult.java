package com.homes.zipsai.building.client;

import java.util.Objects;

import org.locationtech.jts.geom.Point;

public sealed interface KakaoAddressResult {

    record Success(Point coordinates) implements KakaoAddressResult {

        public Success {
            Objects.requireNonNull(coordinates, "coordinates");
        }
    }

    record Failure(KakaoAddressErrorType errorType) implements KakaoAddressResult {

        public Failure {
            Objects.requireNonNull(errorType, "errorType");
        }
    }
}
