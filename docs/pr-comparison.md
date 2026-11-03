# API v0.2와 PR #6·#7 비교 기록

확인일: 2026-11-02 (Asia/Seoul)

> 이 문서는 아래 고정 커밋과 당시 API v0.2의 **역사적 비교 기록**이다. 이후 사용자 결정으로 현재 설계는 [v0.3](api-spec.md)으로 바뀌었다. 여기서 “현재 PR/누락/미정”은 비교 당시 시점이며 지금의 GitHub 상태를 재조회한 결과가 아니다.
>
> v0.3에서는 운동 업로드·측정 테이블을 문서에 추가했고, 쿼터당 측정으로 연결했다. 참가자·팀 점수·개별 득점 구조는 제거했으며 Edwards 운동 요약을 저장한다. 아래 옛 명세의 부족 항목을 다시 신규 요구사항으로 적용하지 않는다. 실제 PR 코드가 v0.3으로 변경됐다는 의미는 아니다.

## 1. 비교 결론

현재 [API 명세 v0.2](api-spec.md)는 기능 명세에 기반한 **향후 전체 서비스 계약 제안**이고, 열린 PR은 **사용자 CRUD와 운동 측정 데이터 업로드**를 구현한다. 동일한 기능을 다른 형식으로 구현한 부분과, 아직 구현 대상이 아닌 기능을 구분해야 한다. 기존 명세를 현재 서버에서 바로 호출 가능한 문서로 사용하면 안 된다.

이번 비교에서 확인한 명세의 주요 누락은 `WorkoutPayload` 업로드 API와 `workoutPayloads`, `trackPoints`, `heartRateSamples` 테이블이다. 기존 기능 명세 본문에 상세 수집 항목이 없어 보류했던 GPS·심박수 수집이 실제 PR에는 이미 들어 있다. 반대로 명세의 경기·쿼터·득점·친구·분석·작업 동기화는 현재 열린 PR의 구현 범위에 없다.

## 2. 비교 대상과 확인 방법

| 대상 | 확인 시점 상태 | 기준 커밋 / 범위 |
| --- | --- | --- |
| [PR #6 · 사용자 CRUD](https://github.com/TEAM-Ehera/KIIKICK-back/pull/6) | OPEN, 미병합 | head `8e5e0d31748c02c4cf8df6e2302bc7300bc15f70`, base main `ff9a05357fa3a3a42a008c9f2f000a26c5a0874f` |
| [PR #7 · 운동 기록 업로드](https://github.com/TEAM-Ehera/KIIKICK-back/pull/7) | OPEN, 미병합 | head `e1f9f41859be7f4ae8a980f4d8d1f5447ea52438`, base는 PR #6의 head |
| 로컬 main | GitHub PR의 변경이 아직 없는 초기 코드 | `ff9a05357fa3a3a42a008c9f2f000a26c5a0874f` |
| 로컬 docs | Git에 추적되지 않은 작성 문서 | API v0.2, 기능별 상세 문서, ERD |

- PR 설명만이 아니라 diff와 head 커밋의 Controller·DTO·Service·Repository·예외 처리·DDL·설정을 읽었다.
- #7이 #6 위에 쌓여 있으므로 두 PR의 변경을 독립 기능 두 벌로 합산하지 않았다. 통합 결과는 #7 head를 기준으로 확인했다.
- 닫힌 과거 PR #1~#5의 Pitch·Match 구현은 현재 열린 PR의 구현으로 합산하지 않았다.
- 원격 브랜치로 체크아웃하거나 코드를 변경하지 않았다. 서버 호출·DB 적용·테스트 재실행·배포 상태 확인은 하지 않았다. PR 작성자의 테스트 결과는 이번 비교의 독립 실행 결과가 아니다.

## 3. 현재 PR에 있는 HTTP API

기준 경로를 포함한 실제 Controller 경로다. 아래 API는 인증 세션 대신 경로의 userId를 사용하는 개발 단계 계약이다.

| Method | 실제 경로 | 요청 | 성공 응답 | 기존 명세와 관계 |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/user` | `{nickname}` | 201 UserResponse | 명세는 외부 인증 교환·온보딩으로 계정 생성 제안. 직접 생성 계약 없음 |
| GET | `/api/v1/user/{userId}` | 경로 UUID | 200 UserResponse | 명세의 `/users/me`, `/users/{userId}/profile`과 권한·응답이 다름 |
| GET | `/api/v1/users` | 별도 검색/페이지 쿼리 없음 | 200 UserResponse[] | 명세의 공개 사용자 검색 `/users/search`와 다른 전체 목록 |
| PUT | `/api/v1/user/{userId}` | `{nickname}` | 200 UserResponse | 명세는 `PATCH /users/me`, avatarUrl 편집도 제안 |
| DELETE | `/api/v1/user/{userId}` | 경로 UUID | 204, 즉시 soft delete | 명세는 `/users/me/deletion-requests`에 202 접수 후 비동기 처리 제안 |
| POST | `/api/v1/user/{userId}/workout-payloads` | 운동 요약·GPS·심박수 | 201 `{workoutPayloadId}` | 기존 명세에 누락. `/sync/operations`와 목적·데이터가 다름 |

근거: [UserController](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/user/controller/UserController.java#L66), [WorkoutPayloadController](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/controller/WorkoutPayloadController.java#L31).

**주의**: “67개 중 6개 구현”으로 계산할 수 없다. 현재 6개 경로는 목표 명세의 67개 경로와 그대로 일치하는 부분집합이 아니다.

## 4. 계약 차이와 영향

| 항목 | 기존 명세의 제안 | PR의 실제 구현 | 영향 / 정리 방향 |
| --- | --- | --- | --- |
| 사용자 식별 | Bearer 인증 + `/users/me`, 권한별 공개 프로필 | 인증 없이 URL userId로 사용자 지정 | PR 설명에도 개발 단계 제약으로 명시. 제품 API에서는 인증된 소유자 검증이 필요하며 UUID 존재 확인과 구별해야 함 |
| 응답 형식 | `{data, meta}` 봉투 | UserResponse 또는 배열 직접 반환, 업로드는 `{workoutPayloadId}` | 클라이언트 DTO를 그대로 재사용할 수 없음. 현재 계약과 목표 계약을 문서에서 분리 |
| 오류 형식 | `{error:{code,message,retryable,persistence,details},meta}` | 처리된 도메인/검증 오류에 `{message}` | 모든 프레임워크 오류까지 같은 형식이라는 보장은 없음. 공통 형식 채택 여부를 합의 |
| 필드 검증 상태 | 422 | `@Valid` 실패·운동 값 오류는 400, 사용자 없음은 404 | 클라이언트 오류 분기·Swagger와 Markdown 일치 필요 |
| 닉네임 | 온보딩 전 null 가능, 길이 미정 | 생성·수정 NotBlank, 최대 16자. DB VARCHAR(16) NOT NULL | 목표 온보딩 전 계정 모델과 DB 제약이 충돌. 16자는 현재 구현 사실이지 승인된 기획 정책으로 자동 승격되지 않음 |
| 프로필 필드 | avatarUrl, onboardingStatus, comparisonConsent 포함 | id, nickname, createdAt, updatedAt, deletedAt | 기존 UserSelf를 실제 UserResponse로 사용하면 필수 필드 불일치 |
| 목록 | cursor, limit, 권한 필터 | 전체 활성 사용자 목록, SQL LIMIT 없음 | `/users`를 친구 검색 API로 취급하면 안 됨. 제품 노출 범위·검색·페이지네이션 별도 필요 |
| 시간 입력 | 전반적으로 RFC 3339 UTC 문자열 | 운동 날짜/샘플 시각은 Apple epoch 초의 Double | 업로드를 기존 ISO 시각 DTO로 호출하면 호환되지 않음. 예외 계약으로 명시하거나 변환 시점 합의 |
| 사용자 시간 응답 | `Z` 또는 offset이 있는 UTC 문자열 | DTO는 LocalDateTime, Swagger 예시는 offset 없는 문자열 | UTC라고 설명돼도 응답 자체의 시간대 식별은 별개. 직렬화 형식 합의 필요 |
| 멱등성 | 변경마다 Idempotency-Key, Watch operationId/clientEventId | 운동 업로드마다 서버 UUID 생성, 클라이언트 논리 업로드 ID 없음 | 같은 업로드 재전송 시 별도 부모 운동이 생길 수 있음. 트랜잭션과 멱등성은 별도 요구 |
| 수정 충돌 | 경기 ETag / If-Match | 현재 사용자/운동 API에 해당 계약 없음 | 경기 API는 아직 구현되지 않았으므로 ETag 미구현을 현재 경기 버그로 판단하지 않음 |
| 탈퇴 | 요청 접수·처리 상태·보존/익명화 정책 | users.deletedAt 갱신, 활성 사용자 조회에서 제외 | 삭제 처리 범위가 다름. 공동 경기·측정 데이터 보존은 추가 결정 필요 |
| 영속성 | 로컬 초기 구성 설명에 JPA 언급 | PR #6에서 JdbcTemplate로 전환 | JPA를 전제로 구현 작업을 지시하면 PR 방향과 다름. 논리 ERD는 JDBC에서도 사용 가능 |

근거: [UserCreateRequest](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/user/dto/UserCreateRequest.java#L18), [UserResponse](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/user/dto/UserResponse.java#L16), [사용자 목록 SQL](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/user/repository/UserRepository.java#L85), [예외 처리](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/global/exception/GlobalExceptionHandler.java#L11), [업로드 서비스](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/service/WorkoutPayloadService.java#L43), [JDBC 의존성](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/build.gradle#L21).

## 5. 기존 명세에 보완할 운동 업로드 계약

아래는 정책 제안이 아닌 **비교 시점 PR의 현재 코드 사실**이다. 아직 main에 병합됐거나 배포됐다는 의미는 아니다.

| 필드 | 타입 | 현재 검증 / 의미 |
| --- | --- | --- |
| startDate | Double, 필수 | 2001-01-01 UTC 기준 초. 유한수 검증 |
| endDate | Double, 필수 | 같은 기준 초. 유한수이며 endDate >= startDate |
| totalCalories | Double, 필수 | kcal. 현재 DTO는 NotNull 검증만 있음 |
| totalDistance | Double, 필수 | m. 현재 DTO는 NotNull 검증만 있음 |
| points | 배열, 필수 | 빈 배열 허용. GPS 필드와 시각을 담음 |
| points[].latitude | Double, 필수 | -90~90 검증 |
| points[].longitude | Double, 필수 | -180~180 검증 |
| points[].altitude | Double, 필수 | 고도 m. 음수 가능 |
| points[].horizontalAccuracy | Double, 필수 | 위치 정확도 m |
| points[].speed | Double, 필수 | m/s, -1은 무효 측정값으로 설명됨 |
| points[].course | Double, 필수 | 방향. Swagger는 0~359.9 및 -1을 설명하나 DTO에 그 범위 검증은 없음 |
| points[].timestamp | Double, 필수 | Apple epoch 초 |
| heartRateSamples | 배열, 필수 | 빈 배열 허용 |
| heartRateSamples[].timestamp | Double, 필수 | Apple epoch 초 |
| heartRateSamples[].bpm | Double, 필수 | 현재 DTO는 NotNull 검증만 있음 |

- 운동 요약·GPS·심박수는 `@Transactional` 한 트랜잭션으로 저장한다. 이는 배치 내부 작업을 개별 커밋하는 목표 `/sync/operations`와 다르다.
- 배열 순서는 0부터 시작하는 sampleSeq로 저장한다. 서버가 시각순 정렬을 수행한다는 의미는 아니다.
- 부모 운동 ID는 매번 서버에서 새 UUID로 생성한다. 자식의 `(workoutPayloadId, sampleSeq)` UNIQUE는 부모 업로드 자체의 중복을 방지하지 않는다.
- Apple epoch는 UTC LocalDateTime으로 변환하고 마이크로초 HALF_UP 반올림을 적용한다. DB 연결도 UTC 세션으로 설정돼 있어 UTC 저장 방향은 기존 명세와 일치한다.
- 현재 payload/DDL에는 matchId·quarterId가 없다. 측정 데이터를 경기·쿼터 분석에 연결하려면 연결 단위와 소유·중복 정책을 추가로 결정해야 한다. 한 운동이 여러 쿼터를 포함하는지부터 확인해야 한다.

근거: [운동 요청 DTO](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/dto/WorkoutPayloadCreateRequest.java), [GPS DTO](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/dto/TrackPointRequest.java), [심박수 DTO](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/dto/HeartRateSampleRequest.java), [시간 변환](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/java/com/teamehera/kiikick/workout/service/AppleEpochTimeConverter.java), [UTC 연결 설정](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/src/main/resources/application.yaml#L6).

## 6. ERD 차이

PR의 [MySQL DDL](https://github.com/TEAM-Ehera/KIIKICK-back/blob/e1f9f41859be7f4ae8a980f4d8d1f5447ea52438/docs/db/mysql/create-tables.sql)은 네 테이블을 정의한다.

| PR 테이블 | 핵심 관계·필드 | 기존 ERD와 차이 |
| --- | --- | --- |
| users | BINARY(16) UUID, nickname NOT NULL, createdAt/updatedAt/deletedAt | 기존 ERD는 온보딩 전 nickname nullable이며 추가 프로필·동의 모델 제안 |
| workoutPayloads | userId FK, startDate/endDate, totalCalories/totalDistance | 기존 ERD에 누락. 경기/쿼터 FK 없음 |
| trackPoints | workoutPayloadId FK, sampleSeq, GPS 측정값, timestamp | 기존 ERD에 누락. 자식 PK는 BIGINT AUTO_INCREMENT |
| heartRateSamples | workoutPayloadId FK, sampleSeq, timestamp, bpm | 기존 ERD에 누락. 자식 PK는 BIGINT AUTO_INCREMENT |

현재 실제 관계는 `users → workoutPayloads → trackPoints / heartRateSamples`다. 기존의 경기 이벤트 모델은 이를 대체하지 않는다. 경기 운영 이벤트와 운동 센서 측정 원본은 목적을 구분한 뒤 필요한 관계를 연결해야 한다. 기존 논리 ERD의 snake_case와 실제 DDL의 camelCase 컬럼명도 물리 모델 확정 시 정리해야 한다.

## 7. 아직 열린 PR 범위에 없는 목표 기능

인증·세션·온보딩·동의, 알림·기기, 경기장 세팅, 경기·쿼터 상태 전이, 참가자·득점·어시스트·포지션, 홈, 친구, 경기 결과, 분석, 작업별 동기화·멱등성은 기존 명세의 향후 범위다. 현재 두 PR에 없다는 사실만으로 PR의 범위 위반이나 결함이라고 판단하지 않는다.

## 8. PR #7에서 이미 논의 중인 검증 사항

| 항목 | head 코드에서 확인한 상태 | 기존 리뷰 |
| --- | --- | --- |
| 배열 내부 null | List 자체 NotNull과 요소 Valid만 있고 요소 NotNull이 없음. 서비스는 요소를 바로 역참조하므로 `[null]` 입력의 필드 오류 처리가 보장되지 않음 | [리뷰 의견](https://github.com/TEAM-Ehera/KIIKICK-back/pull/7#discussion_r4438319168) |
| Double 값 검증 | 시작·종료 시각은 finite 검증. 칼로리·거리·bpm 등에는 동일 검증과 제품 범위가 정해져 있지 않음 | [리뷰 의견](https://github.com/TEAM-Ehera/KIIKICK-back/pull/7#discussion_r4438324969) |
| DB 날짜 허용 범위 | 변환기는 유한수·산술/Java 날짜 변환 실패를 처리하지만 MySQL DATETIME 범위를 명시적으로 검사하지 않음 | [리뷰 의견](https://github.com/TEAM-Ehera/KIIKICK-back/pull/7#discussion_r4438329141) |

위 상태는 읽은 head 커밋 기준이다. 모두 해결됐다고 간주하지 않으며 이번 작업에서 수정하거나 PR에 댓글을 게시하지 않았다. 런타임 재현 테스트를 수행한 결과는 아니다.

## 9. 권장 반영 순서

1. **현재 구현 계약 문서화**: 위 6개 API와 실제 DTO·400 오류·인증 미적용 상태를 별도 현재 계약으로 기록한다. 목표 계약과 함께 보여주되 섞지 않는다.
2. **누락 기능 보완**: 운동 업로드 상세 문서와 세 측정 테이블을 추가한다. P10/P11의 수집·동의·보존·삭제 질문을 실제 수집 데이터에 맞춰 보완한다.
3. **공통 계약 합의**: 인증 주체, 사용자 경로, 응답 봉투, 검증 오류 상태, 시간 직렬화, 닉네임 제약을 먼저 결정한다. 기존 iOS 연결에 영향을 줄 수 있어 문서를 맞추는 것만으로 서버 계약을 바꾸지 않는다.
4. **경기 연결·중복 방지 합의**: 운동의 논리 ID, 경기/쿼터 연결 단위, 오프라인 재전송·재업로드 정책을 결정한다.
5. **기능별 구현 진행**: 그다음 경기 세팅 → 경기 운영 → 결과/분석·친구·알림 등의 확정 계약을 구현한다. 모든 공통 기술 제안을 현재 PR의 필수 수정으로 소급하지 않는다.

이번에는 비교 보고서와 문서 안내 링크만 추가했다. 기존 API 경로·정책 제안·ERD를 PR에 맞춰 자동 변경하지 않았다.
