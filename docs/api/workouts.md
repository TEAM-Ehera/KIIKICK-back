# 쿼터 운동 원본 업로드·요약

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-02 · 구현 전 설계**

요구사항 F10/F08. Watch → iPhone은 기존 WCSession.transferFile로 측정 1회당 JSON 파일 하나를 전달한다. 아래 서버 경로는 신규 설계이며 기존 iOS 파일 전송이나 현재 PR의 HTTP 경로가 이미 바뀌었다는 뜻이 아니다. [iOS DTO](https://github.com/DeveloperAcademy-POSTECH/2026-C6-A03-KIIKICK-iOS/blob/main/docs/dto/workout-payload.md)의 본문 필드를 보존하고 quarterId는 URL로 전달한다.

기존 사용자별 HTTP 경로와 WORK-01의 전환 순서, 대기 파일 처리 및 기존 경로 종료 협의는 [iOS·서버 연동 협의표](ios-integration.md#인증과-공통-응답-적용-순서-12)에 따른다. 전환 검증에는 쿼터 ID 연결, 인증, 응답 디코딩 및 지연 파일 재전송을 포함한다.

## 원본 요청 필드

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| startDate / endDate | number | 필수 / null 불가 | Apple epoch 초, 유한수, endDate > startDate |
| totalCalories | number | 필수 / null 불가 | kcal, 유한수, 0 이상 |
| totalDistance | number | 필수 / null 불가 | m, 유한수, 0 이상 |
| points | TrackPointInput[] | 필수 / null 불가 | 빈 배열 허용. null 요소 불가 |
| events | MatchEvent[] | 선택 / 생략·null은 빈 배열 | 골·어시스트 발생 위치 원본. 수신 순서 보존 |
| field | WorkoutField | 선택 / null 허용 | 운동 당시 경기장 이름·코너 사본 |
| heartRateSamples | HeartRateInput[] | 필수 / null 불가 | 빈 배열 허용. null 요소 불가 |
| points[].latitude / longitude | number | 필수 | degrees; -90~90 / -180~180 |
| points[].altitude / horizontalAccuracy | number | 필수 | m, 유한수; 원본 무효 표식 보존 |
| points[].speed | number | 필수 | m/s; -1 무효 또는 0 이상 |
| points[].course | number | 필수 | -1 무효 또는 0 이상 360 미만 |
| points[].timestamp | number | 필수 | Apple epoch 초 |
| heartRateSamples[].timestamp | number | 필수 | Apple epoch 초 |
| heartRateSamples[].bpm | number | 필수 | 유한수 원본. 품질 미달은 계산에서 제외; 범위 정책 P08 |

### 이벤트와 경기장 (#115 · 2026-11-09)

요청 키는 iOS `WorkoutPayload.swift`와 같은 복수형 `events`다. 각 항목은 `{ "type": "goal", "location": TrackPointInput }`이며 type은 소문자 `goal` 또는 `assist`만 허용한다. `location`은 latitude, longitude, altitude, horizontalAccuracy, speed, course, timestamp의 7개 필수 숫자를 가진다. 이벤트 위치 시각도 Apple epoch 초이며 운동 시작~종료 범위 안이어야 한다. 이벤트 배열은 원본 순서를 유지하며 경로 points에 동일한 포인트가 없어도 된다.

`field`는 `{name, cornerA, cornerB, cornerC, cornerD, rawCornerA?, rawCornerB?, rawCornerC?, rawCornerD?}`다. 각 코너는 `{latitude, longitude}`이며 보정 코너 4개는 필수, 원본 핀은 개별 nullable이다. 위도 -90~90, 경도 -180~180의 유한한 숫자를 받는다. 이 값은 운동 당시의 독립된 사본으로 저장하므로 저장 경기장의 수정·삭제에 영향을 받지 않는다.

기존 사용자별 `POST /api/v1/user/{userId}/workout-payloads`, 쿼터 업로드, 경기 일괄 제출의 `quarters[].workout` 모두 같은 필드를 받는다. 사용자별 기존 경로의 응답은 workoutPayloadId만 반환하는 계약을 유지한다. 쿼터 운동 조회와 업로드 응답, 쿼터 결과·경기 결과의 workout에는 `events`와 `field`를 반환한다. 기존 원본은 `events: []`, `field: null`로 조회한다. 새 필드를 생략하거나 빈 값으로 보내는 요청은 기존 원본 해시와 호환되며, 실제 이벤트·경기장 사본을 변경한 재업로드는 409 충돌이다.

이벤트는 위치 기록이며 별도 입력하는 goals/assists 합계를 자동 변경하지 않는다. iOS의 SwiftData `Field`는 현재 Codable이 아니므로 전송용 Codable DTO에 이름·코너를 매핑해 `field`에 넣는다. iOS 코드 자체는 이번 백엔드 변경에 포함하지 않는다.

### 업로드 본문 예시

```json
{
  "startDate": 815274000.0,
  "endDate": 815274600.0,
  "totalCalories": 100.0,
  "totalDistance": 1500.0,
  "points": [
    {
      "latitude": 37.5665,
      "longitude": 126.978,
      "altitude": 20.1,
      "horizontalAccuracy": 4.8,
      "speed": 2.3,
      "course": 87.5,
      "timestamp": 815274001.2
    }
  ],
  "heartRateSamples": [
    { "timestamp": 815274001.0, "bpm": 142.0 }
  ],
  "events": [
    {
      "type": "goal",
      "location": {
        "latitude": 37.5665, "longitude": 126.978, "altitude": 20.1,
        "horizontalAccuracy": 4.8, "speed": -1, "course": -1, "timestamp": 815274010.0
      }
    }
  ],
  "field": {
    "name": "풋살장",
    "cornerA": { "latitude": 37.5, "longitude": 127.0 },
    "cornerB": { "latitude": 37.5, "longitude": 127.001 },
    "cornerC": { "latitude": 37.501, "longitude": 127.001 },
    "cornerD": { "latitude": 37.501, "longitude": 127.0 }
  }
}
```

모든 샘플은 측정 시작~종료 범위 안이어야 한다. 배열의 시각은 비감소 순서를 검사하되 같은 시각의 샘플을 원본에서 제거하지 않는다. 속도·가속도 계산에서 Δt=0은 제외한다. 요청 크기·배열 상한과 시계 오차 허용 범위는 P10에서 확정한다. 샘플이 적다는 이유로 전체 원본 수신을 거절하지 않으며 해당 지표를 INSUFFICIENT_DATA로 표시한다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [WORK-01](#work-01) | 쿼터 운동 원본 업로드 | `POST` | `/quarters/{quarterId}/workout-payloads` |
| [WORK-02](#work-02) | 저장 운동 기본값 조회 | `GET` | `/quarters/{quarterId}/workout` |
| [WORK-03](#work-03) | 쿼터 운동 요약 조회 | `GET` | `/quarters/{quarterId}/summary` |
| [WORK-04](#work-04) | GPS 원본 페이지 조회 | `GET` | `/quarters/{quarterId}/track-points` |
| [WORK-05](#work-05) | 심박 원본 페이지 조회 | `GET` | `/quarters/{quarterId}/heart-rate-samples` |
| [WORK-06](#work-06) | Edwards 기준 최대심박수 설정 | `PUT` | `/quarters/{quarterId}/summary/settings` |
| [WORK-07](#work-07) | 운동 요약 재계산 요청 | `POST` | `/quarters/{quarterId}/summary/recalculate` |

<a id="work-01"></a>
## WORK-01 · 쿼터 운동 원본 업로드

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/quarters/{quarterId}/workout-payloads` |
| 권한 | OWNER |
| 성공 | 201 · Workout (새 원본) / 200 · Workout (동일 원본 재전송) |

### 입력과 헤더

path: quarterId. 본문은 위 WorkoutPayload 원형이며 userId/matchId/quarterId를 본문에 추가하지 않는다.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 본인 소유 쿼터가 측정 마감되어 endedAt이 있어야 한다. 쿼터/경기가 COMPLETED여도 지연 업로드를 허용한다. 운동 시간과 쿼터 시간의 허용 오차는 P10 검토 대상이다.
2. workout_payloads와 GPS·심박 배열 전체를 같은 트랜잭션에 저장한다. user_id는 경기 소유자, sample_seq는 각 배열의 0부터 시작하는 순서다. 빈 배열도 그대로 반영한다.
3. 쿼터당 한 원본만 허용한다. 같은 정규화 원본이면 기존 데이터를 200으로 반환하며 다른 파일은 409 WORKOUT_ALREADY_EXISTS다. 같은 멱등 키 성공 재생은 최초 201도 그대로 재생한다.
4. 원본 저장과 같은 트랜잭션에 요약 PROCESSING 행(결과는 null, 원본 해시·계산 버전 포함)을 등록한다. 계산은 커밋 후 수행하며 상태 기반 재시도로 중단 복구한다. 별도 outbox는 없다.
5. 201은 원본 저장 성공이다. 계산 실패가 원본 성공을 취소하지 않는다. Location은 WORK-02이며 요약은 WORK-03에서 조회한다.

### 오류와 앱 대응

미마감은 409 QUARTER_NOT_ENDED. 다른 파일은 409 WORKOUT_ALREADY_EXISTS. 유효하지 않은 숫자·날짜·배열은 422. P10 확정 전 서버 요청 본문 제한은 기본 8 MiB이며 `KIIKICK_WORKOUT_MAX_UPLOAD_BYTES` 설정으로 조정할 수 있다. 제한 초과는 413 PAYLOAD_TOO_LARGE. [공통 오류](common.md#errors)도 적용한다.

<a id="work-02"></a>
## WORK-02 · 저장 운동 기본값 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/quarters/{quarterId}/workout` |
| 권한 | OWNER |
| 성공 | 200 · Workout |

### 입력과 헤더

path: quarterId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 원본 전체 배열 대신 부모 측정 정보만 반환한다. 미수신은 404다. 배열은 WORK-04/05로 조회한다.

### 오류와 앱 대응

타인 쿼터·미수신 원본은 404. [공통 오류](common.md#errors)도 적용한다.

<a id="work-03"></a>
## WORK-03 · 쿼터 운동 요약 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/quarters/{quarterId}/summary` |
| 권한 | OWNER |
| 성공 | 200 · QuarterSummary 또는 null |

### 입력과 헤더

path: quarterId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 저장 요약만 읽고 전체 GPS·심박을 재계산하지 않는다. 원본 미수신이면 data=null이다.
2. PROCESSING/PARTIAL/FAILED도 200으로 현재 상태를 반환한다. PROCESSING에는 Retry-After를 제공하고 PARTIAL은 부족 데이터/정책을 표시한다. FAILED는 사용자 재계산 명령 또는 서버 복구 대상으로 안내한다.
3. calculation_parameters와 원본 해시는 내부 관리 정보다. 지표 null을 0으로 바꾸지 않는다.

### 기본 계산 워커

워커는 기본 5초 간격으로 커밋된 PROCESSING 행을 최대 20건 조회한다. 프로세스가 중단돼도 다음 실행이 미완료 행을 다시 처리한다. 결과를 게시할 때 현재 작업 ID와 원본 해시를 검사하고 같은 트랜잭션에서 경기 버전을 올린다. 유효하지 않은 저장 원본은 FAILED로 기록하고, 트랜잭션 실패는 PROCESSING 상태에서 다음 폴링에 재시도한다.

워커는 원본 총거리·칼로리와 계산 가능한 심박·이동 지표를 저장한다. 모든 지표를 계산할 수 있으면 READY, 누락 지표가 있으면 PARTIAL로 게시한다. Edwards TRIMP는 계산 작업에 개인 기준 최대심박수 스냅샷이 있어야 산출한다. 요약 시간은 휴식 포함 경과 시간이며, 평균속력은 원본 총거리와 이 시간으로 계산한다. GPS 기반 이동 지표는 [계산 계약](../dbdiagram/quarter-summary.md)의 품질·이벤트 기준을 따른다. 워커는 `KIIKICK_WORKOUT_SUMMARY_WORKER_ENABLED`로 끄거나 `KIIKICK_WORKOUT_SUMMARY_POLL_MS`로 조회 주기를 조정할 수 있다.

### 성공 응답 예시

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "workoutPayloadId": "00000020-0000-4000-8000-000000000020",
    "calculationStatus": "PARTIAL",
    "calculationVersion": "quarter-metrics-v1",
    "metricStatus": {
      "durationSeconds": {
        "status": "READY",
        "reason": null
      },
      "maxSpeedMps": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "totalDistanceM": {
        "status": "READY",
        "reason": null
      },
      "totalCaloriesKcal": {
        "status": "READY",
        "reason": null
      },
      "averageSpeedMps": {
        "status": "READY",
        "reason": null
      },
      "highIntensityDistanceM": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "accelerationCount": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "decelerationCount": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "directionChangeCount": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "averageHeartRateBpm": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "maxHeartRateBpm": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "trimpEdwards": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "hrMaxReferenceBpm": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "validHrDurationSeconds": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      },
      "validGpsDurationSeconds": {
        "status": "MISSING_INPUT",
        "reason": "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED"
      }
    },
    "durationSeconds": 600.0,
    "maxSpeedMps": null,
    "totalDistanceM": 1500.0,
    "totalCaloriesKcal": 100.0,
    "averageSpeedMps": 2.5,
    "highIntensityDistanceM": null,
    "accelerationCount": null,
    "decelerationCount": null,
    "directionChangeCount": null,
    "averageHeartRateBpm": null,
    "maxHeartRateBpm": null,
    "trimpEdwards": null,
    "hrMaxReferenceBpm": null,
    "validHrDurationSeconds": null,
    "validGpsDurationSeconds": null,
    "calculatedAt": "2026-11-02T10:10:01+09:00",
    "updatedAt": "2026-11-02T10:10:01+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

유효한 본인 쿼터에 요약이 없는 것은 404가 아니라 200 null이다. [공통 오류](common.md#errors)도 적용한다.

<a id="work-04"></a>
## WORK-04 · GPS 원본 페이지 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/quarters/{quarterId}/track-points` |
| 권한 | OWNER |
| 성공 | 200 · TrackPoint[] |

### 입력과 헤더

path: quarterId. query: cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. sampleSeq 오름차순. 원본 미수신은 404, 수신된 빈 배열은 200 [].
2. 현재 workout ID와 원본 해시를 커서에 결합하여 샘플 페이지 혼합을 방지한다.

### 오류와 앱 대응

본인 이외는 404. 잘못된 커서는 400, 버전 변경은 409. [공통 오류](common.md#errors)도 적용한다.

<a id="work-05"></a>
## WORK-05 · 심박 원본 페이지 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/quarters/{quarterId}/heart-rate-samples` |
| 권한 | OWNER |
| 성공 | 200 · HeartRateSample[] |

### 입력과 헤더

path: quarterId. query: cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. sampleSeq 오름차순. 원본 미수신과 수신된 빈 배열을 구별한다.
2. 조회 응답 timestamp는 +09:00 포함 서울 시각 문자열이며 Apple epoch 입력 형식과 구별한다.

### 오류와 앱 대응

본인 이외/원본 미수신은 404. [공통 오류](common.md#errors)도 적용한다.

<a id="work-06"></a>
## WORK-06 · Edwards 기준 최대심박수 설정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/quarters/{quarterId}/summary/settings` |
| 권한 | OWNER |
| 성공 | 202 · QuarterSummary |

### 입력과 헤더

path: quarterId. 본문: hrMaxReferenceBpm(필수, 유한한 양수).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 원본이 존재해야 한다. 계산에 사용할 개인 기준 최대심박수를 스냅샷으로 기록한다. 출처는 초안에서 USER_PROVIDED로 관리한다. 관측 최고심박수를 자동 대입하지 않는다.
2. 계산 중인 새 세대를 만들고 결과를 null로 무효화한 뒤 재계산한다. 계산 설정과 version 변경은 같은 트랜잭션이다. 경기가 완료되어도 허용한다.
3. Location은 WORK-03, Retry-After를 반환한다. 다른 지표 임계값은 서버에서 확정된 계산 정책을 사용하며 임의 숫자로 채우지 않는다.

### 오류와 앱 대응

원본 미수신은 409 WORKOUT_REQUIRED, 진행 중인 다른 요청은 409 CALCULATION_IN_PROGRESS, 기준값 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="work-07"></a>
## WORK-07 · 운동 요약 재계산 요청

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/quarters/{quarterId}/summary/recalculate` |
| 권한 | OWNER |
| 성공 | 202 · QuarterSummary |

### 입력과 헤더

path: quarterId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 원본과 저장된 최대심박 기준을 사용한다. 적용할 계산 정책·버전을 기록하고 원본을 변경하지 않는다.
2. 기존 결과를 무효화하고 PROCESSING으로 전환한다. 동일 키 재시도는 새 계산을 만들지 않는다. 진행 중 다른 키의 요청은 409 CALCULATION_IN_PROGRESS다.
3. 계산 결과 게시 전 원본 해시·계산 설정·계산 버전 세대가 같은지 검사한다. 늦은 이전 계산이 최신 결과를 덮어쓰지 못한다.
4. WORK-03으로 결과를 확인한다. 원본 또는 입력이 부족한 지표는 null과 상태를 반환한다.

### 오류와 앱 대응

원본 미수신은 409 WORKOUT_REQUIRED, 이미 계산 중이면 409 CALCULATION_IN_PROGRESS. [공통 오류](common.md#errors)도 적용한다.

## 계산 기준

[쿼터 요약 계산 계약](../dbdiagram/quarter-summary.md)을 따른다. TRIMP는 Edwards 하나만 제공한다. GPS·심박 원본은 모두 보존하며 Zone별 체류 시간은 중간 계산으로만 사용한다. 최고/평균 속력은 m/s다. GPS·심박 유효 구간, 보간 및 이벤트 임계값은 쿼터 요약 계산 계약을 따른다.

## 경기 단위 운동 기록 제출

`POST /api/v1/matches/{matchId}/workout-submissions`는 완료된 본인 경기의 모든 쿼터 대표 포지션과 운동 원본을 한 요청으로 받는다. 인증과 `Idempotency-Key`가 필요하다. 서버에 등록된 경기는 최신 `If-Match`가 필요하다. 서버에 없는 iOS 생성 `matchId`는 `If-Match` 없이 최초 제출할 수 있으며, 초기 버전 `"1"`도 허용한다.

```json
{
  "quarters": [
    {
      "quarterId": "d2b48276-e3bf-4130-a014-ae8488ddf99b",
      "position": "CM",
      "workout": {
        "startDate": 815274000,
        "endDate": 815274600,
        "totalCalories": 100,
        "totalDistance": 1500,
        "points": [],
        "heartRateSamples": []
      }
    }
  ]
}
```

- 서버에 없는 `matchId`이면 로그인 사용자의 완료 경기와 요청의 `quarterId`를 그대로 저장한 완료 쿼터를 생성한다. 배열 순서가 쿼터 번호가 되고, 운동 원본의 시작·종료 시각으로 쿼터 시간 및 경기 전체 시간을 정한다. 경기장과 제목은 미지정으로 둔다. 생성과 원본·포지션·계산 대기 등록은 하나의 트랜잭션이며 실패하면 모두 롤백한다. 타인 경기 ID 또는 다른 경기에 연결된 쿼터 ID는 `404`다.
- 경기의 모든 쿼터 ID가 정확히 한 번씩 필요하다. `position`은 서버에 설정된 대표 포지션 코드여야 하며, `workout`은 WORK-01과 같은 형식이다.
- 쿼터별 포지션과 운동 원본을 하나의 트랜잭션에서 저장하고, 새 원본마다 요약 계산을 대기 상태로 등록한다. 기존 쿼터 원본과 같은 데이터는 재사용하고 다른 원본과 충돌하면 전체 제출을 롤백한다.
- 요청 본문 전체에 `kiikick.workout.max-upload-bytes` 제한을 적용한다. 기본값은 8 MiB이며 초과하면 `413`을 반환한다.
- 성공하면 `202 Accepted`, 새 경기 `ETag`, `Location: /api/v1/matches/{matchId}/result`, `Retry-After: 5`를 반환한다. 응답의 `data`에는 `matchId`와 `quarterCount`가 들어간다. iOS는 경기 결과의 `summaryAvailability`로 분석 진행 상태를 확인할 수 있다.
- 응답을 받지 못한 경우 같은 본문과 헤더로 재시도한다. 완료된 경기의 대표 포지션을 정정하려면 같은 운동 원본과 수정한 포지션을 새 키 및 최신 경기 ETag로 제출할 수 있다.
