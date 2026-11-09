# 개인 쿼터 진행·기록

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-02 · 구현 전 설계**

요구사항 F04. 골·어시스트는 쿼터별 최종 합계를 PUT한다. 증가분 이벤트를 만들지 않는다. 쿼터 기록 마감과 완료 확정은 별도이며, 운동 업로드는 이 상태 전이와 독립적으로 늦게 도착할 수 있다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [GAME-01](#game-01) | 경기 시작 | `POST` | `/matches/{matchId}/start` |
| [GAME-02](#game-02) | 쿼터 시작 | `POST` | `/matches/{matchId}/quarters/{quarterId}/start` |
| [GAME-08](#game-08) | 쿼터 측정 마감 | `POST` | `/matches/{matchId}/quarters/{quarterId}/end` |
| [GAME-09](#game-09) | 쿼터 대표 포지션 조회 | `GET` | `/matches/{matchId}/quarters/{quarterId}/position` |
| [GAME-10](#game-10) | 쿼터 대표 포지션 저장·수정 | `PUT` | `/matches/{matchId}/quarters/{quarterId}/position` |
| [GAME-11](#game-11) | 쿼터 완료 확정 | `POST` | `/matches/{matchId}/quarters/{quarterId}/complete` |
| [GAME-12](#game-12) | 경기 묶음 종료 | `POST` | `/matches/{matchId}/end` |
| [GAME-13](#game-13) | 쿼터 골·어시스트 조회 | `GET` | `/matches/{matchId}/quarters/{quarterId}/stats` |
| [GAME-14](#game-14) | 쿼터 골·어시스트 합계 저장·수정 | `PUT` | `/matches/{matchId}/quarters/{quarterId}/stats` |

<a id="game-01"></a>
## GAME-01 · 경기 시작

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/start` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. READY → IN_PROGRESS. startedAt을 기록한다. 쿼터 시작은 별도다.

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-02"></a>
## GAME-02 · 쿼터 시작

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/quarters/{quarterId}/start` |
| 권한 | OWNER |
| 성공 | 200 · Quarter |

### 입력과 헤더

path: matchId, quarterId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 부모 경기 IN_PROGRESS, 쿼터 READY에서 시작한다.
2. 같은 경기에서 IN_PROGRESS 쿼터가 하나라도 있으면 ACTIVE_QUARTER_EXISTS. 마감 후 입력 중인 쿼터도 먼저 완료해야 한다.

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-08"></a>
## GAME-08 · 쿼터 측정 마감

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/quarters/{quarterId}/end` |
| 권한 | OWNER |
| 성공 | 200 · Quarter |

### 입력과 헤더

path: matchId, quarterId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. IN_PROGRESS이고 endedAt=null인 쿼터에 endedAt을 기록한다.
2. 쿼터 상태는 입력 단계 동안 IN_PROGRESS로 유지한다. 추가 상태 컬럼 없이 endedAt 유무로 측정 중/입력 중을 구별한다.
3. 서버 마감 시각과 실제 워치 측정 시각은 별개다. 시계 오차 허용 범위는 P10에서 정한다.

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-09"></a>
## GAME-09 · 쿼터 대표 포지션 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}/quarters/{quarterId}/position` |
| 권한 | OWNER |
| 성공 | 200 · PositionEntry 또는 null |

### 입력과 헤더

path: matchId, quarterId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 포지션 한 개만 조회한다. 미입력은 data=null이며 0이나 임의 포지션을 만들지 않는다.

### 성공 응답 예시

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "positionCode": "PIVOT",
    "updatedAt": "2026-11-02T10:10:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-10"></a>
## GAME-10 · 쿼터 대표 포지션 저장·수정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/matches/{matchId}/quarters/{quarterId}/position` |
| 권한 | OWNER |
| 성공 | 200 · PositionEntry |

### 입력과 헤더

path: matchId, quarterId. 본문: positionCode(필수 string).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 쿼터 측정 마감 후, 부모 경기 종료 전 입력한다. 쿼터가 COMPLETED여도 경기 IN_PROGRESS인 동안 정정 가능하다.
2. 쿼터당 한 행을 upsert한다. 허용 코드는 대문자 `GK, LB, CB, RB, LM, CM, RM, LW, ST, RW`다. 대소문자를 구분하며 소문자와 목록 밖의 코드는 422로 거절한다. 서버의 `KIIKICK_MATCH_POSITION_CODES`로 쉼표로 구분한 목록을 설정할 수 있고, 미설정 시 위 기본 목록을 사용한다.

### 성공 응답 예시

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "positionCode": "PIVOT",
    "updatedAt": "2026-11-02T10:10:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-11"></a>
## GAME-11 · 쿼터 완료 확정

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/quarters/{quarterId}/complete` |
| 권한 | OWNER |
| 성공 | 200 · Quarter |

### 입력과 헤더

path: matchId, quarterId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. IN_PROGRESS이고 endedAt이 존재해야 COMPLETED로 변경한다.
2. 운동 파일 수신이나 요약 계산 완료를 기다리지 않는다. 포지션 미입력 상태에서도 완료를 허용한다. 미입력 골·어시스트와 포지션은 조회 시 null로 표시한다.

### 성공 응답 예시

```json
{
  "data": {
    "id": "00000005-0000-4000-8000-000000000005",
    "matchId": "00000003-0000-4000-8000-000000000003",
    "number": 1,
    "durationSeconds": 600,
    "status": "COMPLETED",
    "startedAt": "2026-11-02T10:00:00+09:00",
    "endedAt": "2026-11-02T10:10:00+09:00",
    "matchVersion": 8,
    "createdAt": "2026-11-02T09:55:00+09:00",
    "updatedAt": "2026-11-02T10:10:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-12"></a>
## GAME-12 · 경기 묶음 종료

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/end` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 경기 IN_PROGRESS이며 하나 이상 완료 쿼터가 있어야 한다. 모든 쿼터가 COMPLETED여야 한다. 미사용 READY 쿼터는 SET-19로 먼저 제거한다.
2. COMPLETED와 endedAt을 저장하고 즉시 200을 반환한다. 별도 경기 결과 집계 작업은 없다.
3. 경기 종료 후에도 같은 소유자의 마감된 쿼터에 운동 업로드·요약 계산은 허용한다. 직접 입력 기록의 종료 후 정정은 P06 미정으로 보류한다.

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-13"></a>
## GAME-13 · 쿼터 골·어시스트 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}/quarters/{quarterId}/stats` |
| 권한 | OWNER |
| 성공 | 200 · QuarterPlayerStats 또는 null |

### 입력과 헤더

path: matchId, quarterId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 미입력은 null, 명시적 무득점은 goals=0/assists=0이다.

### 성공 응답 예시

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "goals": 2,
    "assists": 1,
    "updatedAt": "2026-11-02T10:10:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

<a id="game-14"></a>
## GAME-14 · 쿼터 골·어시스트 합계 저장·수정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/matches/{matchId}/quarters/{quarterId}/stats` |
| 권한 | OWNER |
| 성공 | 200 · QuarterPlayerStats |

### 입력과 헤더

path: matchId, quarterId. 본문: goals, assists(필수, 0 이상 정수).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. 시작된 쿼터이며 부모 경기가 IN_PROGRESS일 때 허용한다. 쿼터당 한 행을 upsert한다.
2. 기존 값에 더하지 않고 두 합계를 교체한다. 타 사용자 ID나 팀·득점자·시점은 받지 않는다.
3. 경기 전체 골·어시스트는 쿼터 원본 합계에서 계산한다. 종료 후 직접 기록 정정은 P06에서 결정한다.

### 성공 응답 예시

```json
{
  "data": {
    "quarterId": "00000005-0000-4000-8000-000000000005",
    "goals": 2,
    "assists": 1,
    "updatedAt": "2026-11-02T10:10:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

상태 불일치는 409 INVALID_STATE, 미마감은 409 QUARTER_NOT_ENDED, 합계 타입/음수·포지션 오류는 422. [공통 오류](common.md#errors)도 적용한다.

## 제거된 계약

GAME-03~GAME-07의 개별 득점 조회·생성·수정·삭제는 제거했다. GAME-09/10은 여러 사용자 포지션 배열 대신 단일 /position을 사용한다. GAME-12는 경기 종료 200이며 운동 요약 상태와 독립이다.
