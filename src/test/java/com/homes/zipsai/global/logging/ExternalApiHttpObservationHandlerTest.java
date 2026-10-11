package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.observation.ClientRequestObservationContext;

import com.homes.zipsai.global.exception.AiUnavailableException;

import io.micrometer.observation.Observation;

class ExternalApiHttpObservationHandlerTest {
    private static final String TRACE_ID = "trace-123";
    private static final String ROUTE = "/api/v1/managers/me/building";
    private static final String AI_URL = "http://ai.test:8000/converse";
    private static final String KAKAO_URL = "https://dapi.kakao.com/v2/local/search/address.json";
    private final StructuredLogger structuredLogger = mock(StructuredLogger.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ExternalApiHttpObservationHandler.class)
        .withBean(StructuredLogger.class, () -> structuredLogger)
        .withPropertyValues("app.ai.base-url=http://ai.test:8000", "app.ai.client=http",
            "app.kakao.base-url=https://dapi.kakao.com",
            "app.external-api.targets.ai.base-url=${app.ai.base-url}",
            "app.external-api.targets.ai.stage=ai_api",
            "app.external-api.targets.ai.error-code=AI_UNAVAILABLE",
            "app.external-api.targets.kakao.base-url=${app.kakao.base-url}",
            "app.external-api.targets.kakao.stage=kakao_api",
            "app.external-api.targets.kakao.error-code=KAKAO_UNAVAILABLE");

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @ParameterizedTest
    @CsvSource({
        "http://ai.test:8000/converse,true",
        "https://dapi.kakao.com/v2/local/search/address.json,true",
        "https://other.test/address,false",
        "http://dapi.kakao.com/address,false",
        "https://dapi.kakao.com:8443/address,false",
        "https://dapi.kakao.com.other.test/address,false"
    })
    @DisplayName("설정된 AI와 Kakao의 scheme 및 authority가 일치하는 요청만 측정한다")
    void supportsOnlyConfiguredTargets(String url, boolean supported) {
        runner.run(context -> assertThat(context.getBean(ExternalApiHttpObservationHandler.class)
            .supportsContext(httpContext(url))).isEqualTo(supported));
    }

    @Test
    @DisplayName("HTTP 요청이 아니거나 carrier가 없으면 측정하지 않는다")
    void ignoresNonHttpAndMissingCarrier() {
        runner.run(context -> {
            ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
            assertThat(handler.supportsContext(new Observation.Context())).isFalse();
            assertThat(handler.supportsContext(new ClientRequestObservationContext(null))).isFalse();
        });
    }

    @Test
    @DisplayName("AI stub 환경에서도 Handler를 등록하고 Kakao만 측정한다")
    void measuresKakaoWithStubAi() {
        runner.withPropertyValues("app.ai.client=stub").run(context -> {
            assertThat(context).hasSingleBean(ExternalApiHttpObservationHandler.class);
            ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
            assertThat(handler.supportsContext(httpContext(KAKAO_URL))).isTrue();
            assertThat(handler.supportsContext(httpContext(AI_URL))).isFalse();
        });
    }

    @Test
    @DisplayName("AI stub 환경에 AI 서버 주소가 없어도 Kakao Handler를 등록한다")
    void startsWithoutAiUrlWithStubAi() {
        new ApplicationContextRunner().withUserConfiguration(ExternalApiHttpObservationHandler.class)
            .withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
            .withBean(StructuredLogger.class, () -> structuredLogger)
            .withPropertyValues("app.ai.client=stub",
                "app.external-api.targets.kakao.base-url=https://dapi.kakao.com",
                "app.external-api.targets.kakao.stage=kakao_api",
                "app.external-api.targets.kakao.error-code=KAKAO_UNAVAILABLE").run(context -> {
                assertThat(context).hasSingleBean(ExternalApiHttpObservationHandler.class);
                assertThat(context.getBean(ExternalApiHttpObservationHandler.class)
                    .supportsContext(httpContext(KAKAO_URL))).isTrue();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "HTTP"})
    @DisplayName("AI 성공 로그와 기존 aiApiMs 기록을 유지한다")
    void preservesAiSuccessLogging(String aiClient) {
        runner.withPropertyValues("app.ai.client=" + aiClient).run(context -> {
            observe(context.getBean(ExternalApiHttpObservationHandler.class), httpContext(AI_URL));

            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq("ai_api"), anyLong(),
                eq("ok"), isNull());
            assertThat(Long.parseLong(MDC.get("aiApiMs"))).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    @DisplayName("Kakao 성공은 별도 stage로 기록하고 기존 AI 측정값을 덮어쓰지 않는다")
    void logsKakaoWithoutChangingAiDuration() {
        runner.run(context -> {
            MDC.put("aiApiMs", "42");
            observe(context.getBean(ExternalApiHttpObservationHandler.class), httpContext(KAKAO_URL));

            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq("kakao_api"), anyLong(),
                eq("ok"), isNull());
            assertThat(MDC.get("aiApiMs")).isEqualTo("42");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("새 외부 API는 설정 추가만으로 대상별 성공 및 실패 로그를 기록한다")
    void logsAdditionalConfiguredTarget(boolean failed) {
        runner.withPropertyValues(
            "app.external-api.targets.weather.base-url=https://weather.test",
            "app.external-api.targets.weather.stage=weather_api",
            "app.external-api.targets.weather.error-code=WEATHER_UNAVAILABLE"
        ).run(context -> {
            ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
            ClientRequestObservationContext http = httpContext("https://weather.test/forecast");
            assertThat(handler.supportsContext(http)).isTrue();
            if (failed) {
                http.setError(new IOException("connection failed"));
            }
            observe(handler, http);

            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq("weather_api"), anyLong(),
                eq(failed ? "fail" : "ok"), eq(failed ? "WEATHER_UNAVAILABLE" : null));
            assertThat(MDC.get("aiApiMs")).isNull();
        });
    }

    @Test
    @DisplayName("환경변수로 지정한 AI 주소를 참조한 설정을 Map으로 주입한다")
    void bindsTargetFromExistingAiEnvironmentVariable() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("testEnvironment",
                Map.of("AI_API_URL", "https://configured-ai.test"))))
            .withPropertyValues("app.ai.base-url=${AI_API_URL:http://localhost:8000}")
            .run(context -> {
                ExternalApiProperties properties = context.getBean(ExternalApiProperties.class);
                assertThat(properties.targets().get("ai").baseUrl())
                    .isEqualTo(URI.create("https://configured-ai.test"));
                ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
                assertThat(handler.supportsContext(httpContext("https://configured-ai.test/converse"))).isTrue();
                assertThat(handler.supportsContext(httpContext(AI_URL))).isFalse();
            });
    }

    @ParameterizedTest
    @CsvSource({"http://ai.test:8000/converse,ai_api,AI_UNAVAILABLE",
        "https://dapi.kakao.com/address,kakao_api,KAKAO_UNAVAILABLE"})
    @DisplayName("HTTP 오류 응답은 대상별 stage와 오류 코드로 기록한다")
    void logsHttpFailure(String url, String stage, String errorCode) {
        runner.run(context -> {
            ClientRequestObservationContext http = httpContext(url);
            ClientHttpResponse response = mock(ClientHttpResponse.class);
            when(response.getStatusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
            http.setResponse(response);

            observe(context.getBean(ExternalApiHttpObservationHandler.class), http);

            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq(stage), anyLong(),
                eq("fail"), eq(errorCode));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {AI_URL, KAKAO_URL})
    @DisplayName("응답 없이 발생한 연결 오류도 시간과 실패로 기록한다")
    void logsTransportFailure(String url) {
        runner.run(context -> {
            ClientRequestObservationContext http = httpContext(url);
            http.setError(new IOException("connection failed"));

            observe(context.getBean(ExternalApiHttpObservationHandler.class), http);

            boolean ai = AI_URL.equals(url);
            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq(ai ? "ai_api" : "kakao_api"),
                anyLong(), eq("fail"), eq(ai ? "AI_UNAVAILABLE" : "KAKAO_UNAVAILABLE"));
        });
    }

    @Test
    @DisplayName("AI ApiException의 기존 오류 코드를 유지한다")
    void preservesAiExceptionCode() {
        runner.run(context -> {
            ClientRequestObservationContext http = httpContext(AI_URL);
            AiUnavailableException error = new AiUnavailableException();
            http.setError(error);

            observe(context.getBean(ExternalApiHttpObservationHandler.class), http);

            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq("ai_api"), anyLong(),
                eq("fail"), eq(error.code));
        });
    }

    @Test
    @DisplayName("trace가 없는 호출은 요청 로그를 만들지 않는다")
    void skipsLoggingWithoutTrace() {
        runner.run(context -> {
            ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
            ClientRequestObservationContext http = httpContext(KAKAO_URL);
            handler.onStart(http);
            handler.onStop(http);

            verifyNoInteractions(structuredLogger);
        });
    }

    @Test
    @DisplayName("로그 출력 오류가 외부 호출 결과를 바꾸지 않는다")
    void ignoresLoggerFailure() {
        runner.run(context -> {
            doThrow(new IllegalStateException("logger failed")).when(structuredLogger)
                .stageDone(eq(TRACE_ID), eq(ROUTE), eq("kakao_api"), anyLong(), eq("ok"), isNull());

            ExternalApiHttpObservationHandler handler = context.getBean(ExternalApiHttpObservationHandler.class);
            assertThatCode(() -> observe(handler, httpContext(KAKAO_URL)))
                .doesNotThrowAnyException();
            verify(structuredLogger).stageDone(eq(TRACE_ID), eq(ROUTE), eq("kakao_api"), anyLong(),
                eq("ok"), isNull());
        });
    }

    private void observe(ExternalApiHttpObservationHandler handler, ClientRequestObservationContext context) {
        MDC.put("traceId", TRACE_ID);
        MDC.put("route", ROUTE);
        handler.onStart(context);
        handler.onStop(context);
    }

    private ClientRequestObservationContext httpContext(String url) {
        ClientHttpRequest request = mock(ClientHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(url));
        return new ClientRequestObservationContext(request);
    }
}
