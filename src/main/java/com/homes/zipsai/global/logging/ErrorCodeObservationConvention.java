package com.homes.zipsai.global.logging;

import org.slf4j.MDC;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.common.KeyValues;

@Component
public class ErrorCodeObservationConvention extends DefaultServerRequestObservationConvention {

    private static final String ERROR_CODE = "error_code";
    private static final String NONE = "none";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        String errorCode = MDC.get(StructuredLogger.ERROR_CODE);
        return super.getLowCardinalityKeyValues(context).and(ERROR_CODE, errorCode == null ? NONE : errorCode);
    }
}
