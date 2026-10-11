package com.homes.zipsai.global.logging;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.exception.ApiException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

@Component
@EnableConfigurationProperties(ExternalApiProperties.class)
public final class ExternalApiHttpObservationHandler implements ObservationHandler<ClientRequestObservationContext> {
    private final List<ExternalApiProperties.Target> targets;
    private final StructuredLogger structuredLogger;

    public ExternalApiHttpObservationHandler(ExternalApiProperties properties,
                                            @Value("${app.ai.client:http}") String aiClient,
                                            StructuredLogger structuredLogger) {
        this.targets = properties.targets().entrySet().stream()
            .filter(entry -> !"ai".equals(entry.getKey()) || "http".equalsIgnoreCase(aiClient))
            .map(Map.Entry::getValue).toList();
        Set<String> origins = new HashSet<>();
        for (ExternalApiProperties.Target target : targets) {
            URI baseUrl = target.baseUrl();
            if (!origins.add(baseUrl.getScheme() + "://" + baseUrl.getAuthority())) {
                throw new IllegalArgumentException("External API targets must use distinct schemes and authorities");
            }
        }
        this.structuredLogger = structuredLogger;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        if (!(context instanceof ClientRequestObservationContext httpContext) || httpContext.getCarrier() == null) {
            return false;
        }
        return target(httpContext.getCarrier().getURI()) != null;
    }

    @Override
    public void onStart(ClientRequestObservationContext context) {
        String route = MDC.get("route");
        URI uri = context.getCarrier().getURI();
        context.put(Measurement.class, new Measurement(System.nanoTime(), MDC.get("traceId"),
            route != null ? route : uri.getPath(), target(uri)));
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        Measurement measurement = context.get(Measurement.class);
        if (measurement.traceId() == null) {
            return;
        }
        long durationMs = (System.nanoTime() - measurement.startedNanos()) / 1_000_000;
        ExternalApiProperties.Target target = measurement.target();
        try {
            if ("ai_api".equals(target.stage())) {
                MDC.put("aiApiMs", Long.toString(durationMs));
            }
            Throwable error = context.getError();
            boolean failed = error != null || context.getResponse() != null
                && context.getResponse().getStatusCode().isError();
            String errorCode = failed
                ? error instanceof ApiException apiException ? apiException.code : target.errorCode() : null;
            structuredLogger.stageDone(measurement.traceId(), measurement.route(), target.stage(), durationMs,
                failed ? "fail" : "ok", errorCode);
        } catch (IOException | RuntimeException ignored) {

        }
    }

    private ExternalApiProperties.Target target(URI uri) {
        return targets.stream().filter(target -> matches(target.baseUrl(), uri)).findFirst().orElse(null);
    }

    private boolean matches(URI baseUri, URI uri) {
        return Objects.equals(baseUri.getScheme(), uri.getScheme())
            && Objects.equals(baseUri.getAuthority(), uri.getAuthority());
    }

    private record Measurement(long startedNanos, String traceId, String route, ExternalApiProperties.Target target) {
    }
}
