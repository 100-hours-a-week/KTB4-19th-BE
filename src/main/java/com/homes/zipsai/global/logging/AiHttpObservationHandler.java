package com.homes.zipsai.global.logging;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.exception.ApiException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http", matchIfMissing = true)
public final class AiHttpObservationHandler implements ObservationHandler<ClientRequestObservationContext> {
    private final URI aiBaseUri;
    private final StructuredLogger structuredLogger;

    public AiHttpObservationHandler(@Value("${app.ai.base-url}") String baseUrl,
                                    StructuredLogger structuredLogger) {
        this.aiBaseUri = URI.create(baseUrl);
        this.structuredLogger = structuredLogger;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        if (!(context instanceof ClientRequestObservationContext httpContext) || httpContext.getCarrier() == null) {
            return false;
        }
        URI uri = httpContext.getCarrier().getURI();
        return Objects.equals(aiBaseUri.getScheme(), uri.getScheme())
            && Objects.equals(aiBaseUri.getAuthority(), uri.getAuthority());
    }

    @Override
    public void onStart(ClientRequestObservationContext context) {
        String route = MDC.get("route");
        context.put(Measurement.class, new Measurement(System.nanoTime(), MDC.get("traceId"),
            route != null ? route : context.getCarrier().getURI().getPath()));
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        Measurement measurement = context.get(Measurement.class);
        if (measurement.traceId() == null) {
            return;
        }
        long durationMs = (System.nanoTime() - measurement.startedNanos()) / 1_000_000;
        try {
            MDC.put("aiApiMs", Long.toString(durationMs));
            Throwable error = context.getError();
            boolean failed = error != null || context.getResponse() != null
                && context.getResponse().getStatusCode().isError();
            String errorCode = failed
                ? error instanceof ApiException apiException ? apiException.code : "AI_UNAVAILABLE" : null;
            structuredLogger.stageDone(measurement.traceId(), measurement.route(), "ai_api", durationMs,
                failed ? "fail" : "ok", errorCode);
        } catch (IOException | RuntimeException ignored) {

        }
    }

    private record Measurement(long startedNanos, String traceId, String route) {
    }
}
