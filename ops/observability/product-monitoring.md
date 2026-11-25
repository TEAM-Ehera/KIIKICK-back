# Sentry와 PostHog

Java 21 / Spring Boot 4 운영 API용 수집 설정이다. 관리자용 구 버전 백엔드는 별도 프로세스이며 이 SDK가 적용되는 대상이 아니다.

## 서버 환경 변수

홈서버 `api-production.env`에만 값을 저장한다. Sentry 업로드용 인증 토큰은 빌드 프로세스에서만 전달하고 컨테이너에 넣지 않는다.

```dotenv
OBSERVABILITY_ENVIRONMENT=production
SENTRY_DSN=<Sentry 프로젝트 DSN>
SENTRY_RELEASE=kiikick-backend@<배포 식별자>
POSTHOG_ENABLED=true
POSTHOG_API_KEY=<PostHog 프로젝트 수집 키>
POSTHOG_HOST=https://us.i.posthog.com
```

PostHog의 개인 API 키가 아닌 프로젝트 수집 키를 사용한다. 대시보드 관리에는 별도의 사용자 인증이 필요하다. 키가 없는 환경에서는 기본적으로 PostHog를 비활성화하고 Sentry는 DSN 없이 전송하지 않는다.

## 오류 수집

Sentry `kiikick/kiikick-backend` 프로젝트로 처리되지 않은 웹 예외를 전송한다. 기존 예외 처리기가 5xx로 변환한 예외도 최종 HTTP 상태를 확인한 뒤 수집한다. 처리된 4xx는 이 경로에서 전송하지 않는다.

오류 유형·스택·코드 위치를 남기며 사용자 객체, 실제 URL, 헤더, 쿠키, 요청 본문, 쿼리 문자열, breadcrumb, extras, 스택 로컬 변수 및 예외 메시지를 제거한다. 예외 메시지는 `[redacted]`로 표시한다. Tracing·프로파일링·일반 로그 전송은 이번 오류 수집 범위에 포함하지 않는다. 원본 GPS·심박·이메일·인증 토큰을 수집 필드로 추가하지 않는다.

Sentry JVM Gradle 플러그인은 `SENTRY_AUTH_TOKEN`이 제공된 빌드에서만 소스 컨텍스트 번들을 업로드한다. 운영 빌드에서는 `assemble`을 실행하고 토큰을 로그·저장소·런타임에 남기지 않는다. 일반 테스트와 토큰 없는 로컬 빌드는 소스 업로드 없이 실행된다.

Discord 알림은 KIIKICK 조직의 Discord 통합을 사용한다. 새 오류 또는 회귀 오류에 대해 `production` 환경에서 기존 배포 알림 채널로 전송하며, 같은 이슈의 알림 간격은 30분이다. 웹훅을 Java 코드에 넣지 않는다.

## 제품 이벤트

| 이벤트 | 발생 기준 | 중복 식별자 |
| --- | --- | --- |
| `user_signed_up` | 가입 트랜잭션 커밋 | 사용자 ID |
| `match_created` | 서버 생성 또는 iOS 로컬 경기 최초 가져오기 커밋 | 경기 ID |
| `workout_uploaded` | 신규 쿼터 운동 또는 기존 운동 업로드 API의 저장 커밋 | 운동 ID |

트랜잭션 롤백 시 전송하지 않는다. 기존 업로드 재사용과 Idempotency-Key 재생은 신규 이벤트를 만들지 않는다. PostHog의 `$insert_id`는 `이벤트명:엔티티UUID`이며 `distinct_id`는 사용자 UUID다. UUID 이외의 사용자 프로필 정보는 보내지 않는다. 향후 iOS 분석 SDK에서도 동일한 사용자 UUID를 identify에 사용한다.

분석 속성은 `$insert_id`, `environment`, `source=backend`만 전달한다. 운동 이벤트는 쿼터별 개수이므로 경기 개수와 같지 않다. 기존 데이터는 자동으로 소급 집계하지 않는다.

PostHog SDK의 비동기 큐를 사용하고 전송 실패가 가입·경기·업로드 결과를 실패로 바꾸지 않게 한다. 프로세스 강제 종료나 네트워크 장애 시 이벤트가 유실될 수 있는 best-effort 수집이며 재무·감사 장부처럼 사용하지 않는다. 이후 보장된 전송이 필요하면 별도 outbox가 필요하다.

팀 대시보드는 `environment=production`, `source=backend`로 필터링해 검증 이벤트를 제외한다. 가입·경기 생성·운동 업로드 일별 추세와 동일 사용자 기준 가입 → 경기 생성 → 운동 업로드 퍼널을 구성한다. 조직의 팀원 계정으로 접근하고 공개 공유는 활성화하지 않는다.

## 검증

커밋 후 전송·롤백 제외·전송 장애 시 API 유지·민감 필드 제거·처리된 5xx 분류를 테스트한다. 검증용 앱은 홈서버 loopback에서 별도 DB와 별도 JWT 키를 사용하며 메일·R2 업로드·요약 워커를 끈다. 운영 빌드에는 임시 오류 발생 컨트롤러를 포함하지 않는다. 실제 실행 앱의 오류가 Sentry 이슈에 도착하고 소스 줄이 표시되는지 확인한다. PostHog는 실제 경기 생성 및 같은 요청 재시도를 실행한 뒤 대시보드에서 환경과 중복 여부를 확인한다.

## 운영 대시보드와 적용 결과

- [Sentry 오류 목록](https://kiikick.sentry.io/issues/?project=4512492073975808)
- [PostHog 팀 운영 지표](https://us.posthog.com/project/767029/dashboard/2411686)
- [팀 관리자 화면](https://kiikick-admin.asherlab.dev/admin/)

PostHog 퍼널은 동일 사용자의 7일 이내 전환을 집계한다. 팀원은 각 서비스의 조직/프로젝트 접근 권한이 필요하다. 관리자 화면의 링크 자체가 클라우드 서비스의 권한을 부여하지 않는다.

2026-11-25 운영 이미지 `kiikick-api:observability-0b825d3-86eef4dd`에 적용했다. 전체 365개 테스트 통과, 별도 검증 DB의 실제 앱 오류·소스 컨텍스트 수집, 실제 경기 생성과 재시도의 단일 이벤트를 확인했다. 운영 전환 후 두 API 호스트 상태 점검, Swagger/OpenAPI, 인증된 사용자·경기 조회가 통과했다. 검증 컨테이너는 제거했다. Discord 규칙 실행은 확인했으며 채널의 최종 메시지 수신 여부는 별도로 확인해야 한다. 알림 규칙 이름은 한글이며 Discord 기본 메시지의 고정 문구는 Sentry 통합 형식을 따른다.
