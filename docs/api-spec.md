# KIIKICK API 명세서

> **개발 참고용 원본 명세** — 이 문서의 전체 API 목록에서 기능별 상세 계약으로 이동합니다. 팀 설명에는 [검토용 공유본](review/api-spec.md)을 사용할 수 있습니다. 병합 자체가 미합의 항목의 승인을 의미하지는 않습니다.

**v0.5 · 2026-11-05 · iOS 협의용 읽기 편집본**
서비스 시간대: **Asia/Seoul** · 기준 경로: **/api/v1** · 전송: HTTPS / JSON

사용자가 자기 경기의 쿼터를 기록하고 결과를 확인하는 API다. **쿼터가 기록 단위이고, 경기는 쿼터를 묶는 날짜·장소 정보**다. 이 문서는 목표 계약이며 현재 iOS·서버가 모두 이대로 구현됐다는 의미는 아니다.

## 바로가기

- [먼저 알아둘 내용](#overview)
- [화면별로 호출하는 순서](#flow)
- [핵심 요청·응답 예시](#examples)
- [운동 결과를 화면에 표시하는 법](#results)
- [공통 통신 규칙](#rules)
- [iOS와 협의할 항목](#agreement)
- [전체 API 목록](#all-apis)

<a id="overview"></a>
## 1. 먼저 알아둘 내용

| 이름 | 뜻 | 예시 |
| --- | --- | --- |
| 경기 `match` | 같은 날·장소에서 진행한 쿼터 묶음 | 월요일 풋살 |
| 쿼터 `quarter` | 개인 기록과 운동 측정을 저장하는 단위 | 1쿼터, 2쿼터 |
| 개인 기록 `stats` | 직접 입력한 골·어시스트 합계 | 2골, 1어시스트 |
| 포지션 `position` | 해당 쿼터의 대표 포지션 하나 | 허용 코드 목록은 협의 필요 |
| 운동 원본 `workout` | 워치가 측정한 거리·칼로리·GPS·심박 전체 | 측정 1회 파일 |
| 운동 요약 `summary` | 서버가 미리 계산해 저장한 지표 | 최고속도·평균심박·Edwards TRIMP |

**확정된 방향:** 개인 기록, 쿼터별 저장, 대표 포지션 하나, GPS·심박 원본 전체 보존, 저장 요약 조회, Edwards TRIMP, 서울 시간대.

**현재 기술 제안:** 쿼터당 운동 파일 하나, Bearer 인증, 중복 방지 키, 경기 버전 검사. iOS 적용 방식은 협의가 필요하다. 상세 미확정 사항은 [협의표](api/ios-integration.md)에 구분했다.

팀 배정·홈/어웨이 점수·참가자 목록·개별 득점 시각은 사용하지 않는다. 사용자는 자기 경기만 수정하고, 친구 전용 경로에서 수락된 친구의 경기·쿼터 결과를 조회하고 경기별 댓글을 주고받는다. 원본 GPS·심박 샘플은 소유자 전용이다.

<a id="flow"></a>
## 2. 화면별로 호출하는 순서

모든 경로 앞에 `/api/v1`을 붙인다. `{matchId}`와 `{quarterId}`는 앞 단계에서 서버가 반환한 실제 UUID로 바꾼다. 서버 ID가 없는 오프라인 시작 방식은 아직 협의 대상이다.

### 2-1. 경기 준비

| 순서 | 사용자가 하는 일 | 호출 | 보내는 값 / 보관할 값 |
| --- | --- | --- | --- |
| 1 | 로그인 | [AUTH-01](api/auth.md#auth-01) | 이메일·비밀번호 검증 / 15분 Access Token·90일 Refresh 세션 (#73 구현) |
| 2 | 저장한 경기장 선택 | [GET /venues](api/setup.md#set-01) | 선택한 venueId |
| 3 | 새 경기 묶음 생성 | [POST /matches](api/setup.md#set-07) | 제목·장소·예정 시각 / matchId와 ETag |
| 4 | 쿼터 시간 설정 | [POST /matches/{matchId}/quarters](api/setup.md#set-17) | durationSeconds / quarterId와 새 ETag |
| 5 | 준비 완료 | [POST /matches/{matchId}/ready](api/setup.md#set-10) | 본문 없음 / 새 ETag |

저장할 새 장소가 있으면 [POST /venues](api/setup.md#set-03)를 사용한다. 장소를 재사용하지 않고 경기 생성 시 직접 입력하는 것도 현재 초안에서 가능하다. 경기장 API는 iOS `Field`의 보정 `cornerA`~`cornerD`와 선택적인 원본 `rawCornerA`~`rawCornerD`를 `{latitude, longitude}`로 저장·조회한다. 보정 코너는 4개를 함께 등록·변경하며 원본은 개별 삭제할 수 있다. 기존 좌표 없는 경기장은 null을 반환한다.

### 2-2. 쿼터 진행과 개인 기록

| 순서 | 사용자가 하는 일 | 호출 | iOS에서 할 일 |
| --- | --- | --- | --- |
| 1 | 경기 시작 | [GAME-01](api/gameplay.md#game-01) | 경기 시작 요청 |
| 2 | 쿼터 시작 | [GAME-02](api/gameplay.md#game-02) | 해당 quarterId와 워치 측정을 연결 |
| 3 | 골·어시스트 입력 | [GAME-14](api/gameplay.md#game-14) | `{goals: 2, assists: 1}`처럼 현재 합계를 전송 |
| 4 | 쿼터 측정 종료 | [GAME-08](api/gameplay.md#game-08) | 서버 쿼터 마감과 워치 파일 전송을 조정 |
| 5 | 포지션 입력 | [GAME-10](api/gameplay.md#game-10) | 해당 쿼터의 대표 코드 하나 저장 |
| 6 | 쿼터 완료 | [GAME-11](api/gameplay.md#game-11) | 다음 쿼터 시작 또는 경기 종료 선택 |
| 7 | 경기 종료 | [GAME-12](api/gameplay.md#game-12) | 마지막 쿼터까지 완료한 뒤 호출. 성공은 200 |

골·어시스트 저장은 **현재 합계를 교체**한다. 같은 `{goals: 2, assists: 1}`을 다시 보내도 4골·2어시스트로 더하지 않는다. 포지션·합계 입력을 생략할 수 있는지는 정책 검토 중이며, 현재 초안은 미입력을 null로 표시한다.

### 2-3. 워치 측정 전송과 결과 확인

| 순서 | 처리 주체 | 하는 일 | API / 결과 |
| --- | --- | --- | --- |
| 1 | Watch → iPhone | 쿼터 측정 파일 전달 | 기존 WCSession.transferFile. 서버 API가 아님 |
| 2 | iPhone → 서버 | 해당 쿼터의 운동 원본 업로드 | [WORK-01](api/workouts.md#work-01), 새 원본 저장 201 |
| 3 | 서버 | 원본 보존 후 운동 요약 계산·저장 | 쿼터/경기 종료와 별개 |
| 4 | iPhone | 저장 요약 조회 | [WORK-03](api/workouts.md#work-03), 200과 계산 상태 |
| 5 | iPhone | 개인 기록·포지션·운동 결과를 함께 표시 | [RESULT-04](api/results.md#result-04) |

**현재 초안은 서버 쿼터 마감 후 업로드**한다. 파일이 먼저 도착했을 때의 대기 방식은 협의 대상이다. 경기 전체가 종료된 뒤 파일이 도착해도 해당 쿼터에 업로드할 수 있다. 파일을 업로드한 201은 계산 완료라는 뜻이 아니다.

<a id="examples"></a>
## 3. 핵심 요청·응답 예시

아래 `{matchId}`, `{quarterId}`, `<accessToken>`은 설명용 자리다. API ID는 문서에서 찾기 위한 이름이며 요청 본문에 보내는 값이 아니다. 각 예시는 독립적이며 실제 변경 요청에는 최신 ETag를 사용한다.

### 3-1. 경기 묶음 만들기

```http
POST /api/v1/matches
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 00000090-0000-4000-8000-000000000090
```

```json
{
  "title": "월요일 풋살",
  "venueSnapshot": {
    "name": "서울 풋살장",
    "address": null
  },
  "scheduledAt": "2026-11-02T19:30:00+09:00"
}
```

성공: **201**. 반환된 `data.id`를 matchId로 보관한다. 저장 경기장으로 선택할 때는 venueSnapshot 대신 venueId를 보낸다. 두 필드를 동시에 보내지 않는다. 전체 응답 필드는 [Match](api/schemas.md#match)를 따른다.

### 3-2. 쿼터 만들기

```http
POST /api/v1/matches/{matchId}/quarters
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 00000091-0000-4000-8000-000000000091
If-Match: "1"
```

```json
{
  "durationSeconds": 600
}
```

600초는 설정한 10분이다. 성공 **201**의 `data.id`가 quarterId다. 응답 ETag를 다음 변경 요청에 사용한다. 실제 측정 시간은 운동 파일의 startDate/endDate로 별도 계산한다.

### 3-3. 쿼터 골·어시스트 합계 저장

```http
PUT /api/v1/matches/{matchId}/quarters/{quarterId}/stats
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 00000092-0000-4000-8000-000000000092
If-Match: "8"
```

```json
{
  "goals": 2,
  "assists": 1
}
```

성공 **200** 응답 예시:

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "goals": 2,
    "assists": 1,
    "updatedAt": "2026-11-02T19:40:00+09:00"
  },
  "meta": {
    "requestId": "req-stats-example",
    "serverTime": "2026-11-02T19:40:00+09:00",
    "matchVersion": 9
  }
}
```

이 예시의 응답 헤더는 `ETag: "9"`다. 골·어시스트는 필수 0 이상 정수이며, 무득점이면 0을 보낸다. 미입력과 0은 다르다.

### 3-4. 운동 원본 업로드

| 내용 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/quarters/{quarterId}/workout-payloads` |
| 본문 | WorkoutPayload의 startDate/endDate, points, heartRateSamples, totalCalories, totalDistance 및 선택 events/field |
| 날짜 예외 | 원본 날짜는 기존 Apple epoch 숫자 유지. 서울 시각 문자열로 바꾸지 않음 |
| 새 원본 저장 | 201과 Workout. 응답은 `{data, meta}` 형식 |
| 같은 원본 재업로드 | 새 키로 보내도 기존 원본이면 200. 동일 멱등 키 재생은 최초 201을 그대로 반환할 수 있음 |
| 다른 원본 재업로드 | 409 WORKOUT_ALREADY_EXISTS. 현재 초안은 쿼터당 원본 한 건 |
| 상세 필드·예시 | [WORK-01](api/workouts.md#work-01) |

현재 초안은 인증·Idempotency-Key·If-Match를 요구한다. **운동 업로드에 경기 버전 검사를 유지할지는 협의 대상**이며, 여기서 변경된 것으로 간주하지 않는다.

### 3-5. 운동 결과 읽기

```http
GET /api/v1/quarters/{quarterId}/summary
Authorization: Bearer <accessToken>
```

성공 **200**에서 `data`가 null이면 운동 원본 미수신이다. 객체가 있으면 calculationStatus와 각 지표를 읽는다. 전체 응답 예시는 [WORK-03](api/workouts.md#work-03), 필드 사전은 [QuarterSummary](api/schemas.md#quartersummary)를 따른다.

<a id="results"></a>
## 4. 운동 결과를 화면에 표시하는 법

### 4-1. 결과 준비 상태

| 서버 값 | 뜻 | 화면 처리 |
| --- | --- | --- |
| `summary = null` | 운동 파일을 아직 받지 못함 | 측정 데이터 대기 표시 |
| `PROCESSING` | 원본은 저장됐고 계산 중 | 처리 중 표시, Retry-After 이후 재조회 |
| `READY` | 제공 지표 계산 완료 | 값 표시 |
| `PARTIAL` | 일부 지표만 계산 가능 | 계산된 값 표시, null 지표는 미측정/기준 필요 안내 |
| `FAILED` | 계산 실패 | 재계산 안내. 원본 업로드 실패와 구분 |

`0`은 계산·입력된 실제 값이고, `null`은 값이 없다는 뜻이다. `metricStatus`는 지표별로 값이 없는 이유를 알려준다. GET 조회가 전체 GPS·심박 재계산을 실행하지는 않는다.

### 4-2. 화면 이름과 응답 필드

| 화면에 표시할 값 | JSON 필드 | 단위 |
| --- | --- | --- |
| 최고속도 | maxSpeedMps | m/s |
| 이동거리 | totalDistanceM | m |
| 소모 칼로리 | totalCaloriesKcal | kcal |
| 평균속력 | averageSpeedMps | m/s |
| 고강도 주행거리 | highIntensityDistanceM | m |
| 가속 횟수 | accelerationCount | 회 |
| 감속 횟수 | decelerationCount | 회 |
| 방향전환 횟수 | directionChangeCount | 회 |
| 평균 심박수 | averageHeartRateBpm | bpm |
| 최고 심박수 | maxHeartRateBpm | bpm |
| 운동 부하 | trimpEdwards | AU |

화면에서 km/h로 표시하려면 m/s에 3.6을 곱한다. Edwards 계산 기준인 hrMaxReferenceBpm은 이번 쿼터의 관측 최고심박수와 다르다. [계산 기준](dbdiagram/quarter-summary.md)의 미확정 임계값은 iOS와 서버가 각자 임의로 정하지 않는다.

<a id="rules"></a>
## 5. 공통 통신 규칙

### 5-1. 시간

| 대상 | 형식·기준 |
| --- | --- |
| 서비스 화면·날짜별 통계 | Asia/Seoul |
| 일반 요청·응답 날짜 | `2026-11-02T19:30:00+09:00`처럼 +09:00이 포함된 문자열 |
| 워치 원본 업로드 날짜 | 2001-01-01 UTC부터 경과한 초(Double). 기존 Apple epoch 유지 |
| 서버 DB 내부 | UTC 저장 유지. 일반 API 출력 시 서울 시각으로 변환 |

서울 시간대로 맞춘다고 Apple epoch 숫자에 iOS가 9시간을 더하면 안 된다. 같은 시각을 다른 표현으로 변환하는 것이다. 날짜 query는 URLComponents/URLQueryItem으로 구성하고 최종 URL의 `+`가 `%2B`인지 확인해 공백으로 해석되지 않게 한다.

### 5-2. 요청 헤더

| 이름 | 쉬운 설명 | 언제 필요한가 |
| --- | --- | --- |
| Authorization | 로그인한 사용자 확인 | 공개 인증 API 외 모든 요청 |
| Content-Type | 본문이 JSON임을 표시 | JSON 본문을 보내는 요청 |
| Idempotency-Key | 같은 저장 요청의 재전송을 식별하는 UUID | 토큰 교환/갱신 외 변경 API |
| If-Match | 내가 읽었던 경기 버전 | 현재 초안의 기존 경기·쿼터 변경. 상세별 예외 확인 |
| ETag | 서버가 알려주는 경기 버전 | 응답에서 읽어 다음 변경 요청의 If-Match로 사용 |

재시도는 같은 Idempotency-Key를 유지한다. 내용을 바꾼 새 요청에는 새 키를 쓴다. 세부 재생·충돌 규칙은 [공통 계약](api/common.md)을 따른다.

### 5-3. 응답과 오류

성공은 `{data, meta}`, 오류는 `{error, meta}`다. 204는 응답 본문이 없다. iOS가 성공 응답을 기존 DTO 최상위에서 바로 읽는다면 공통 응답 래퍼를 추가해야 한다. 목록의 다음 페이지 정보는 meta.pagination에 있다.

| 상태 코드 | 뜻 | iOS 대응 |
| --- | --- | --- |
| 200 / 201 | 조회·변경 성공 / 새 원본 생성 | data 사용. 업로드 성공과 계산 완료는 구별 |
| 202 | 요청은 받았으나 후속 처리 중 | Location/조회 API로 확인 |
| 204 | 본문 없는 성공 | JSON 디코딩을 시도하지 않음 |
| 401 | 인증 실패·만료 | 토큰 갱신 또는 로그인 |
| 409 | 상태·중복 충돌 | error.code별 처리. 무조건 같은 행동 반복 금지 |
| 412 / 428 | 버전 불일치 / 버전 헤더 누락 | 최신 경기 조회 후 처리 |
| 422 | 입력 검증 오류 | 해당 입력 또는 부족 데이터 확인 |
| 응답 유실 | 저장됐는지 알 수 없음 | 같은 키로 복구. 새 기록을 무조건 생성하지 않음 |

<a id="agreement"></a>
## 6. iOS와 협의할 항목

[협의용 체크리스트](api/ios-integration.md)에 **현재 명세 / iOS 변경점 / 결정할 내용**을 모았다. 우선순위는 아래와 같다.

1. 서버 quarterId와 워치 측정 파일을 어떻게 연결할지.
2. 골·어시스트·포지션을 워치에서 iPhone으로 어떻게 함께 전달할지.
3. 오프라인 시작, 전송 실패, 앱 재실행 뒤에도 연결과 중복 식별자를 유지할지.
4. 새 URL·응답 래퍼·인증·날짜 형식을 어느 시점에 함께 적용할지.
5. 쿼터당 재측정 파일 수, 업로드 시 경기 버전 검사, 서버 마감과 파일 도착 순서를 어떻게 처리할지.

서버가 계산하는 지표의 임계값·산식은 [정책 목록](decisions.md)에서 정한다. 아래 전체 목록 중 오프라인 조작 명령 큐는 지원 여부 검토 중인 기술 초안이다.

<a id="all-apis"></a>
홈 화면은 iOS에서 경기 목록과 쿼터 결과를 조합한다. [홈 화면 연동](api/home.md)을 참고한다.

## 7. 전체 API 목록


총 **69개 경로/Method 계약**이다. 구현 예정 계약과 명령 동기화의 지원 범위 검토 초안도 포함한다. 각 행의 구현 상태와 성공 응답은 아래 상세 문서와 함께 읽는다.

### [인증·온보딩·마이페이지](api/auth.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [AUTH-01A](api/auth.md#auth-01a) | 이메일 인증번호 발송·재발송 (구현) | `POST` | `/auth/email-verifications` | 202 |
| [AUTH-01B](api/auth.md#auth-01b) | 이메일 인증번호 검증 (구현) | `POST` | `/auth/email-verifications/{verificationId}/verify` | 200 |
| [AUTH-01C](api/auth.md#auth-01c) | 이메일 회원가입 완료 (구현) | `POST` | `/auth/signup` | 201 |
| [AUTH-01](api/auth.md#auth-01) | 이메일·비밀번호 로그인 (구현 #73) | `POST` | `/auth/login` | 200 |
| [AUTH-02](api/auth.md#auth-02) | 로그인 세션 갱신 (구현 #62) | `POST` | `/auth/refresh` | 200 |
| [AUTH-03](api/auth.md#auth-03) | 로그아웃 (#63 구현) | `POST` | `/auth/logout` | 204 |
| [AUTH-04](api/auth.md#auth-04) | 내 프로필 조회 (#65 구현) | `GET` | `/users/me` | 200 |
| [AUTH-05](api/auth.md#auth-05) | 내 프로필 수정 (#65 구현) | `PATCH` | `/users/me` | 200 |
| [AUTH-06](api/auth.md#auth-06) | 온보딩 완료 (#66 구현·검증 완료) | `PUT` | `/users/me/onboarding` | 200 |
| [AUTH-07](api/auth.md#auth-07) | 현재 동의 목록 조회 (예정 #68) | `GET` | `/users/me/consents` | 200 |
| [AUTH-08](api/auth.md#auth-08) | 동의 부여·철회 (예정 #68) | `PUT` | `/users/me/consents/{purpose}` | 200 |
| [AUTH-09](api/auth.md#auth-09) | 회원 탈퇴 요청 (예정 #69) | `POST` | `/users/me/deletion-requests` | 202 |
| [AUTH-10](api/auth.md#auth-10) | 탈퇴 처리 상태 조회 (예정 #69) | `GET` | `/users/me/deletion-requests/{deletionId}` | 200 |

### [알림·기기](api/notifications.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [NOTI-01](api/notifications.md#noti-01) | 알림 목록 조회 | `GET` | `/notifications` | 200 |
| [NOTI-02](api/notifications.md#noti-02) | 읽지 않은 알림 수 조회 | `GET` | `/notifications/unread-count` | 200 |
| [NOTI-03](api/notifications.md#noti-03) | 알림 읽음 처리 | `PUT` | `/notifications/{notificationId}/read` | 200 |
| [NOTI-04](api/notifications.md#noti-04) | 기기 등록·푸시 설정 갱신 | `PUT` | `/devices/{deviceId}` | 200 |
| [NOTI-05](api/notifications.md#noti-05) | 기기 등록 해제 | `DELETE` | `/devices/{deviceId}` | 204 |

### [경기장·개인 경기·쿼터 세팅](api/setup.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [SET-01](api/setup.md#set-01) | 저장 경기장 목록·검색 | `GET` | `/venues` | 200 |
| [SET-02](api/setup.md#set-02) | 저장 경기장 상세 | `GET` | `/venues/{venueId}` | 200 |
| [SET-03](api/setup.md#set-03) | 경기장 저장 | `POST` | `/venues` | 201 |
| [SET-04](api/setup.md#set-04) | 경기장 수정 | `PATCH` | `/venues/{venueId}` | 200 |
| [SET-05](api/setup.md#set-05) | 경기장 삭제 | `DELETE` | `/venues/{venueId}` | 204 |
| [SET-06](api/setup.md#set-06) | 내 경기 목록 | `GET` | `/matches` | 200 |
| [SET-07](api/setup.md#set-07) | 개인 경기 묶음 생성 | `POST` | `/matches` | 201 |
| [SET-08](api/setup.md#set-08) | 경기 상세·최신 버전 | `GET` | `/matches/{matchId}` | 200 |
| [SET-09](api/setup.md#set-09) | 경기 설정 수정 | `PATCH` | `/matches/{matchId}` | 200 |
| [SET-10](api/setup.md#set-10) | 경기 준비 완료 | `POST` | `/matches/{matchId}/ready` | 200 |
| [SET-11](api/setup.md#set-11) | 시작 전 경기 취소 | `POST` | `/matches/{matchId}/cancel` | 200 |
| [SET-16](api/setup.md#set-16) | 쿼터 목록 | `GET` | `/matches/{matchId}/quarters` | 200 |
| [SET-17](api/setup.md#set-17) | 쿼터 추가 | `POST` | `/matches/{matchId}/quarters` | 201 |
| [SET-18](api/setup.md#set-18) | 미시작 쿼터 시간 수정 | `PATCH` | `/matches/{matchId}/quarters/{quarterId}` | 200 |
| [SET-19](api/setup.md#set-19) | 미사용 마지막 쿼터 삭제 | `DELETE` | `/matches/{matchId}/quarters/{quarterId}` | 204 |

### [개인 쿼터 진행·기록](api/gameplay.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [GAME-01](api/gameplay.md#game-01) | 경기 시작 | `POST` | `/matches/{matchId}/start` | 200 |
| [GAME-02](api/gameplay.md#game-02) | 쿼터 시작 | `POST` | `/matches/{matchId}/quarters/{quarterId}/start` | 200 |
| [GAME-08](api/gameplay.md#game-08) | 쿼터 측정 마감 | `POST` | `/matches/{matchId}/quarters/{quarterId}/end` | 200 |
| [GAME-09](api/gameplay.md#game-09) | 쿼터 대표 포지션 조회 | `GET` | `/matches/{matchId}/quarters/{quarterId}/position` | 200 |
| [GAME-10](api/gameplay.md#game-10) | 쿼터 대표 포지션 저장·수정 | `PUT` | `/matches/{matchId}/quarters/{quarterId}/position` | 200 |
| [GAME-11](api/gameplay.md#game-11) | 쿼터 완료 확정 | `POST` | `/matches/{matchId}/quarters/{quarterId}/complete` | 200 |
| [GAME-12](api/gameplay.md#game-12) | 경기 묶음 종료 | `POST` | `/matches/{matchId}/end` | 200 |
| [GAME-13](api/gameplay.md#game-13) | 쿼터 골·어시스트 조회 | `GET` | `/matches/{matchId}/quarters/{quarterId}/stats` | 200 |
| [GAME-14](api/gameplay.md#game-14) | 쿼터 골·어시스트 합계 저장·수정 | `PUT` | `/matches/{matchId}/quarters/{quarterId}/stats` | 200 |

### [친구](api/friends.md)

친구 프로필의 경기·경기장·상세 스탯·히트맵 연결은 [친구 기록 연동 가이드](api/friend-records.md)를 참고한다.

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [FRIEND-01](api/friends.md#friend-01) | 친구 검색 | `GET` | `/users/search` | 200 |
| [FRIEND-02](api/friends.md#friend-02) | 친구·사용자 공개 프로필 조회 | `GET` | `/users/{userId}/profile` | 200 |
| [FRIEND-03](api/friends.md#friend-03) | 내 친구 목록 조회 | `GET` | `/friends` | 200 |
| [FRIEND-04](api/friends.md#friend-04) | 보낸·받은 친구 요청 목록 | `GET` | `/friend-requests` | 200 |
| [FRIEND-05](api/friends.md#friend-05) | 친구 요청 보내기 | `POST` | `/friend-requests` | 201 |
| [FRIEND-06](api/friends.md#friend-06) | 친구 요청 수락 | `POST` | `/friend-requests/{requestId}/accept` | 200 |
| [FRIEND-07](api/friends.md#friend-07) | 친구 요청 취소·거절 | `DELETE` | `/friend-requests/{requestId}` | 204 |
| [FRIEND-08](api/friends.md#friend-08) | 친구 삭제 | `DELETE` | `/friends/{userId}` | 204 |
| [FRIEND-09](api/friends.md#friend-09) | 사용자 차단 | `PUT` | `/users/{userId}/block` | 204 |
| [FRIEND-10](api/friends.md#friend-10) | 사용자 차단 해제 | `DELETE` | `/users/{userId}/block` | 204 |

### [개인 경기·쿼터 결과](api/results.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [RESULT-01](api/results.md#result-01) | 개인 경기 결과 | `GET` | `/matches/{matchId}/result` | 200 |
| [RESULT-03](api/results.md#result-03) | 경기 내 쿼터별 결과 | `GET` | `/matches/{matchId}/result/quarters` | 200 |
| [RESULT-04](api/results.md#result-04) | 개별 쿼터 결과 | `GET` | `/quarters/{quarterId}/result` | 200 |

### [개인 분석](api/analytics.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [STAT-01](api/analytics.md#stat-01) | 개인 기간 통계 | `GET` | `/analytics/summary` | 200 |
| [STAT-02](api/analytics.md#stat-02) | 지표 추이 | `GET` | `/analytics/trends` | 200 |
| [STAT-03](api/analytics.md#stat-03) | 이전 개인 경기와 비교 | `GET` | `/analytics/match-comparison` | 200 |
| [STAT-04](api/analytics.md#stat-04) | 쿼터별 포지션 분포 | `GET` | `/analytics/positions` | 200 |
| [STAT-05](api/analytics.md#stat-05) | 친구와 개인 기록 비교 | `GET` | `/analytics/friend-comparison` | 200 |

### [운동 원본·요약](api/workouts.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [WORK-01](api/workouts.md#work-01) | 쿼터 운동 원본 업로드 | `POST` | `/quarters/{quarterId}/workout-payloads` | 201 / 200 |
| [WORK-02](api/workouts.md#work-02) | 저장 운동 기본값 조회 | `GET` | `/quarters/{quarterId}/workout` | 200 |
| [WORK-03](api/workouts.md#work-03) | 쿼터 운동 요약 조회 | `GET` | `/quarters/{quarterId}/summary` | 200 |
| [WORK-04](api/workouts.md#work-04) | GPS 원본 페이지 조회 | `GET` | `/quarters/{quarterId}/track-points` | 200 |
| [WORK-05](api/workouts.md#work-05) | 심박 원본 페이지 조회 | `GET` | `/quarters/{quarterId}/heart-rate-samples` | 200 |
| [WORK-06](api/workouts.md#work-06) | Edwards 기준 최대심박수 설정 | `PUT` | `/quarters/{quarterId}/summary/settings` | 202 |
| [WORK-07](api/workouts.md#work-07) | 운동 요약 재계산 요청 | `POST` | `/quarters/{quarterId}/summary/recalculate` | 202 |

### [명령 동기화](api/watch.md)

| API ID | 기능 | Method | 경로 | 성공 |
| --- | --- | --- | --- | --- |
| [WATCH-01](api/watch.md#watch-01) | 오프라인 명령 배치 반영 | `POST` | `/sync/operations` | 200 |
| [WATCH-02](api/watch.md#watch-02) | 명령 처리 결과 조회 | `GET` | `/sync/operations/{operationId}` | 200 |


## 8. 상세 문서와 변경 범위

[기능 명세](functional-spec.md) · [ERD](erd.md) · [응답 필드 사전](api/schemas.md) · [공통 규칙](api/common.md) · [정책 결정](decisions.md).

v0.5는 구현된 이메일 인증번호 발송·검증과 회원가입 완료 API를 반영하고, 아직 구현되지
않은 Auth API를 예정 상태로 구분했다. 이후 #73에서 이메일·비밀번호 로그인과 계정별 실패 제한을 추가했다.
그 밖의 API 경로·Method·필수 헤더와 운동 원본
검증 규칙은 v0.4에서 변경하지 않았다. 운동 업로드 버전 검사와 샘플 경계 처리는 협의표의
개선 제안이며, 합의 전 적용된 것으로 읽지 않는다.

운동 원본은 #115에서 iOS의 `events`(goal/assist 위치 배열)와 `field`(운동 당시 경기장 사본)를 추가 지원한다. 기존 필드만 보내는 요청은 유지하며, events와 field를 보내려면 iOS 전송 DTO에 포함한다.


### 친구 경기·댓글 추가 (#120)

[친구 경기·댓글 계약](api/friends.md#친구-경기-조회--friend-1115): `GET /friends/{userId}/matches`, `GET /friends/{userId}/matches/{matchId}`, `GET /friends/{userId}/matches/{matchId}/quarters`, `GET /friends/{userId}/matches/{matchId}/result`, `GET /friends/{userId}/matches/{matchId}/result/quarters`.

댓글은 `GET/POST /matches/{matchId}/comments`, `DELETE /matches/{matchId}/comments/{commentId}`를 사용한다. 현재 친구 관계를 매번 확인하며 경기 수정 권한은 소유자 전용이다.
