# Authentication Implementation Plan

**Goal:** v3 회원가입·로그인·역할 권한 처리 및 로컬 MySQL 연결.
**Architecture:** Spring Security Bearer 인증, JWT access token, DB에 해시로 저장하는 rotating refresh session. JPA 트랜잭션으로 회원·동의를 원자 저장하고 역할 선택을 잠금으로 직렬화한다.
**Tech Stack:** Java 25, Spring Boot 4.1.1, Security, JPA, MySQL, Flyway.
**Spec:** 사용자 제공 API 설계서 및 Figma v3. 전화번호는 사용자 확정에 따라 선택/nullable.

## Constraints
- /api/v1 URI와 API 시트의 응답 필드·오류 코드를 유지한다.
- 가입 후 자동 로그인하지 않는다. 초기 역할 NONE, 상태 ACTIVE.
- 이름은 API 시트의 7자 상한, 가입 시 선택. 전화번호는 미입력/null 허용.
- 비밀번호 8~20자 영문·숫자·특수문자, trim 금지. 이메일 trim 및 소문자 정규화.
- 건물·호실·초대·탈퇴 기능은 다른 도메인 담당이므로 구현 범위 밖.
- 기존 feature/auth-spring-security 브랜치에서 실행한다.

## Steps
- [x] src/test/.../AuthApiTests.java: 실제 HTTP/DB 경계 테스트 작성, 미구현 404 실패 확인.
- [x] build.gradle, resources/application*: JPA/Security/Flyway/MySQL과 H2 테스트 설정.
- [x] user/{User,UserAgreement,UserRepository,UserService}: 회원·약관 저장, nullable phone, 중복 방지, 역할 잠금.
- [x] auth/{AuthService,TokenService,RefreshSession,AuthController}: BCrypt 로그인, JWT 발급, refresh 회전/로그아웃 폐기.
- [x] security/SecurityConfig: 최신 DB 역할 기반 인증·인가, CORS, 쿠키 요청 origin 검사, 요청 제한.
- [x] common: 명세 오류 envelope, 입력 누락 400/형식 422 처리.
- [x] ./gradlew test: 가입·중복·필수 동의·로그인·재발급·로그아웃·역할 경합·401/403 검증.
- [x] ./gradlew build 및 로컬 MySQL migration/HTTP smoke 검증.
- [x] README에 환경변수, API 사용법, 실제 확인한 한계 기록.
