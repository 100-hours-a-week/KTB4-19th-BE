# syntax=docker/dockerfile:1
# 1. JAR 생성
FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /app

# Gradle Wrapper와 Build 설정을 먼저 복사해 의존성 관련 Layer를 재사용한다.
COPY --chmod=0755 gradlew ./
COPY gradle/ ./gradle/
COPY build.gradle settings.gradle ./

# Source 변경과 무관한 Gradle 의존성을 먼저 내려받는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

COPY src/ ./src/

# CI에서 Test가 통과한 뒤 실행한다. Docker Build에서는 Test를 중복 실행하지 않는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar -x test --no-daemon

# 2. Backend 실행 환경
FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app

# bootJar로 생성한 실행 가능한 JAR만 Runtime Image에 복사한다.
COPY --from=builder --chown=10001:10001 \
    /app/build/libs/zipsai-0.0.1-SNAPSHOT.jar /app/app.jar

# 관리자(root)가 아닌 일반 사용자 권한으로 실행한다.
USER 10001:10001

# 외부 Port 공개는 Docker Compose에서 별도로 설정한다.
EXPOSE 8080

# DB 비밀번호, JWT Secret과 Spring 설정은 Container 실행 시 전달한다.
# Container 시작 시 Java로 Backend JAR를 실행한다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
