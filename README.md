# zipsAI Backend

Java 25 / Spring Boot 4.1.1 / Spring Security / JPA / MySQL 8.

## 로컬 실행

MySQL localhost:3306에 `zipsai` 데이터베이스를 생성한다.

```sql
CREATE DATABASE IF NOT EXISTS zipsai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`.env.properties.example`을 `.env.properties`로 복사하고 DB_PASSWORD와 JWT_SECRET을 설정한다. 이 파일은 Git에서 제외한다. 개발 PC에는 요청받은 로컬 설정을 준비했다.

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

Flyway가 테이블을 생성하고 JPA가 스키마를 검증한다. 기본 포트는 8080이다. local 프로필만 HTTP 개발용 Secure=false 쿠키를 사용한다. 배포 환경은 DB_PASSWORD/JWT_SECRET/DB_URL/DB_USERNAME/CORS_ALLOWED_ORIGINS 환경변수를 사용하며 HTTPS Secure 쿠키가 기본값이다.

## 구현 API

| Method | URI | 기능 |
|---|---|---|
| GET | /api/v1/users/email-availability?email=... | 이메일 사용 가능 여부 |
| POST | /api/v1/auth/signup | 가입, 초기 역할 NONE, 자동 로그인 없음 |
| POST | /api/v1/auth/login | JWT access token 및 HttpOnly refresh 쿠키 |
| POST | /api/v1/auth/reissue | refresh 회전 및 access 재발급 |
| POST | /api/v1/auth/logout | 현재 세션 폐기 및 쿠키 삭제 |
| GET | /api/v1/users/me | 본인 정보 및 최신 약관 동의 |
| PATCH | /api/v1/users/me | 최초 역할 선택, 프로필·동의 부분 수정 |

가입 요청:

```json
{"email":"user@example.com","password":"Asdf!12345","passwordConfirm":"Asdf!12345","agreements":[{"termsType":"SERVICE","isAgreed":true},{"termsType":"PRIVACY","isAgreed":true},{"termsType":"MARKETING","isAgreed":false}]}
```

전화번호는 선택/nullable이다. 입력 시 10~11자리 숫자 또는 3-3/4-4 하이픈 형식을 검증한다. 이름도 가입 시 선택이며 API 명세의 7자 상한을 유지한다. 가입 요청에 역할·상태 등 허용되지 않은 필드를 전달하면 422를 반환한다.

보호 API는 `Authorization: Bearer <accessToken>`을 보낸다. 브라우저 로그인·재발급 시 `credentials: include`가 필요하다. refreshToken은 HttpOnly/SameSite=Strict이며 `/api/v1/auth` 경로를 사용한다. 프론트와 API는 같은 site로 배포해야 한다. 기본 허용 origin은 http://localhost:3000이다.

역할 변경은 `PATCH /api/v1/users/me`에 `{"userRole":"MANAGER"}` 또는 `{"userRole":"RESIDENT"}`를 보낸다. 최초 1회만 허용하고 같은 역할은 멱등 처리한다. 응답 accessToken으로 교체해야 한다. 기존 access token은 무효화되며 다른 로그인 세션은 refresh로 갱신한다.

`/api/v1/managers/**`와 `/api/v1/residents/**`에 역할별 접근 제한을 설정했다. 다른 도메인의 공통 URI에는 담당 개발자가 `@PreAuthorize` 및 건물/호실 소유권 검사를 적용해야 한다. 로그인 여부나 MANAGER 역할만으로 타인 건물 접근을 허용하면 안 된다.

## 저장 및 보안

- BCrypt 비밀번호 해시, 이메일 UNIQUE, 회원·동의 원자 저장.
- Refresh_sessions에는 refresh 원문 대신 SHA-256 해시 저장. 재발급 시 이전 refresh는 즉시 사용할 수 없다.
- 매 인증 요청에서 계정 상태·현재 역할·auth_version·세션 폐기 여부를 확인한다. 로그아웃 즉시 현재 세션의 access/refresh가 거부된다.
- auth 및 users/me, 이메일 확인은 IP+HTTP method+path당 30초 5회 제한. Retry-After 제공. 단일 서버 메모리 방식이며 다중 인스턴스 배포 시 공유 저장소가 필요하다. 프록시 헤더를 무조건 신뢰하지 않는다.
- 쿠키 SameSite=Strict와 쓰기 요청의 Origin/Fetch Metadata 검증을 사용한다. Bearer 외 세션 인증은 사용하지 않는다.
- 동의 변경은 이력을 추가하며 GET에는 타입별 최신 동의를 반환한다.

## 검증

```sh
./gradlew test
./gradlew build
```

테스트는 H2 MySQL 모드에서 실제 Security filter·Controller·Service·JPA를 통합 검증한다. MySQL 8.0.46에서 Flyway v1 적용 및 실제 HTTP 가입→로그인→역할 선택→재발급→로그아웃, 이전 토큰 거부를 검증했다.

## 명세 적용 및 확인 필요

- API 설계서의 URI/응답 형태/오류 코드를 우선했다. 전화번호는 사용자 지시에 따라 선택으로 확정했다.
- 이름은 API의 선택 입력과 일치하도록 nullable, VARCHAR(7)을 사용한다. 화면의 2~30자 확대는 적용하지 않았다.
- ERDCloud가 로딩 상태여서 최신 ERD 전체를 재확인하지 못했다. 제공된 ERD 요약을 기준으로 Users/User_agreements를 매핑했다. auth_version과 Refresh_sessions는 인증 구현을 위해 추가했다.
- 약관 본문 조회, 관리자·입주민 소속 조회, 건물·호실·초대·탈퇴 API는 이번 인증 범위에 포함하지 않는다. 확정 약관 본문/버전 정책은 제공되지 않았으므로 임의 본문을 생성하지 않았다.
- 만료 refresh 세션의 정기 삭제 및 분산 rate limit 저장소는 운영 환경에서 별도 구성한다.

설계 출처: [API 설계서](https://docs.google.com/spreadsheets/d/1PI4U3lwL1AtHHlWitjjKRwOFnXqX84ydxuwT-B4AOaY/edit#gid=2105638431), [Figma v3](https://www.figma.com/design/v7WCrCeCIOeLloyzN0n5A4/?node-id=12906-447), [ERD](https://www.erdcloud.com/d/jYJGXLjNdn9o9HKvb).
