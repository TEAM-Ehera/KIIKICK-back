# 개인 경기·쿼터 결과

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-04 · #28 결과 조회**

요구사항 F07. 경기 종료와 운동 요약 계산 완료는 독립이다. 본인 경기만 조회하며, 결과 화면을 열 때 원본 샘플 전체를 다시 계산하지 않는다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [RESULT-01](#result-01) | 개인 경기 결과 | `GET` | `/matches/{matchId}/result` |
| [RESULT-03](#result-03) | 경기 내 쿼터별 결과 | `GET` | `/matches/{matchId}/result/quarters` |
| [RESULT-04](#result-04) | 개별 쿼터 결과 | `GET` | `/quarters/{quarterId}/result` |

<a id="result-01"></a>
## RESULT-01 · 개인 경기 결과

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}/result` |
| 권한 | OWNER |
| 성공 | 200 · MatchResult |

### 입력과 헤더

path: matchId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. COMPLETED 경기만. 쿼터 개인 골·어시스트를 합산하되 미입력이 있으면 해당 합계는 null이다.
2. 운동 요약의 준비·부분·처리 중·실패·미수신 개수를 summaryAvailability로 제공한다. 요약이 없어도 경기 결과는 200이다.

### 오류와 앱 대응

미완료 결과는 409 RESULT_NOT_READY. 타인 개인 기록은 404. [공통 오류](common.md#errors)도 적용한다.

<a id="result-03"></a>
## RESULT-03 · 경기 내 쿼터별 결과

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}/result/quarters` |
| 권한 | OWNER |
| 성공 | 200 · QuarterResult[] |

### 입력과 헤더

path: matchId. query: cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 COMPLETED 경기의 쿼터를 number/id 오름차순으로 반환한다.
2. 각 쿼터의 stats/position/workout/summary를 조합한다. 운동 미수신은 workout/summary=null이다.
3. 한 페이지는 기본 20건이며 1–100건을 요청할 수 있다. 요약 게시나 지연 업로드로 경기 버전이 바뀐 뒤 기존 커서를 보내면 409 CURSOR_SNAPSHOT_CHANGED를 반환한다.

### 오류와 앱 대응

미완료 결과는 409 RESULT_NOT_READY. 타인 개인 기록은 404. [공통 오류](common.md#errors)도 적용한다.

<a id="result-04"></a>
## RESULT-04 · 개별 쿼터 결과

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/quarters/{quarterId}/result` |
| 권한 | OWNER |
| 성공 | 200 · QuarterResult |

### 입력과 헤더

path: quarterId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 쿼터가 COMPLETED이면 경기 전체 종료 전에도 조회할 수 있다.
2. 미수신·미입력과 명시적 0을 구별한다. 위치/심박 원본 배열은 포함하지 않는다.

### 성공 응답 예시

```json
{
  "data": {
    "quarter": {
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
    "stats": {
      "quarterId": "00000005-0000-4000-8000-000000000005",
      "goals": 2,
      "assists": 1,
      "updatedAt": "2026-11-02T10:10:00+09:00"
    },
    "position": {
      "quarterId": "00000005-0000-4000-8000-000000000005",
      "positionCode": "PIVOT",
      "updatedAt": "2026-11-02T10:10:00+09:00"
    },
    "workout": null,
    "summary": null
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:20:00+09:00",
    "matchVersion": 8
  }
}
```

### 오류와 앱 대응

미완료 결과는 409 RESULT_NOT_READY. 타인 개인 기록은 404. [공통 오류](common.md#errors)도 적용한다.

## 응답 버전과 지연 업로드

세 조회 모두 `ETag`와 `meta.matchVersion`을 반환한다. 경기 결과의 `summaryAvailability`는 각 쿼터의 현재 상태를 세며, 예를 들어 두 쿼터 중 하나만 운동 파일을 받았고 계산 중이면 다음과 같다.

```json
{
  "ready": 0,
  "partial": 0,
  "processing": 1,
  "failed": 0,
  "missing": 1
}
```

나중에 파일이 도착하거나 계산 상태가 바뀌면 결과를 다시 조회할 수 있다. 결과 응답의 Workout에는 측정 기본값만 포함한다. 그래프 원본은 [WORK-04/05](workouts.md#work-04)로 별도 조회한다.

## 제거된 계약

RESULT-02의 여러 참여자 결과와 /result/players 경로는 제거했다. 친구 요청은 사용자 검색·친구 프로필에서 보낸다. 친구 비교는 별도의 허용된 분석 요약만 사용한다.
