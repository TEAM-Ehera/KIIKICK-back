# 개인 쿼터 분석

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · #43 기간 분석 및 비교**

요구사항 F08. 모든 분석은 직접 입력한 쿼터 기록과 저장된 quarter_summaries를 읽는다. 조회 요청이 GPS·심박 원본 재계산을 유발하지 않는다. 완료 경기의 COMPLETED 쿼터를 사용한다. 기간은 쿼터 endedAt 기준 from 포함/to 제외다.

## 기간과 지표의 공통 계약

query: from/to는 선택 서울 시각(+09:00 포함)이며 둘 다 있으면 from < to다. 생략한 경계는 제한하지 않는다. 기간은 경기 종료 시각이 아닌 **쿼터 종료 시각**에 적용한다. subjectUserId는 STAT-01/02/04에서만 선택이며 기본은 본인, 타인은 FRIEND_VISIBLE을 매번 검사한다. STAT-03은 본인 경기만, STAT-05는 별도 friendUserId를 받는다. 일/월 버킷은 Asia/Seoul 기준이다. timeZone은 생략하거나 Asia/Seoul을 지정한다. 그 외 값은 현재 계약에서 422로 거절한다.

지원 지표와 기간 집계는 다음 규칙을 따른다.

| 지표 | 기간 계산 | 주의 |
| --- | --- | --- |
| goals / assists | 합계 | 직접 입력 행이 없는 쿼터는 누락 |
| totalDistanceM / totalCaloriesKcal / highIntensityDistanceM | 합계 | 모든 대상 쿼터의 지표 READY 필요 |
| accelerationCount / decelerationCount / directionChangeCount | 합계 | 같은 calculationVersion일 때만 비교 |
| maxSpeedMps / maxHeartRateBpm | 최댓값 | 유효 쿼터 결과의 최대 |
| averageSpeedMps | 총거리 합 / 운동 시간 합 | 쿼터별 평균의 단순 평균 금지 |
| averageHeartRateBpm | Σ(쿼터 평균 × 유효 심박 시간) / 유효 심박 시간 합 | 샘플 개수 평균 금지 |
| trimpEdwards | 합계 | 같은 calculationVersion과 기준 HRmax에서만 합산·비교한다. 기준값 원문은 공개하지 않는다. |

대상 쿼터가 없으면 matchCount/quarterCount/goals/assists=0, 운동 지표는 NO_DATA/value=null이다. 하나라도 해당 지표가 부족하면 value=null, status=INCOMPLETE와 포함/전체 쿼터 수를 반환한다. 일부 쿼터만 합산한 수치를 전체값처럼 표시하지 않는다. 한 집계의 가속·감속·방향전환에 계산 버전이 섞이거나 TRIMP의 계산 버전 또는 기준 HRmax가 다르면 해당 지표는 POLICY_REQUIRED다. 개별 집계는 유효해도 두 집계의 기준이 서로 다르면 비교 delta는 null이다. 재계산을 GET에서 실행하지 않는다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [STAT-01](#stat-01) | 개인 기간 통계 | `GET` | `/analytics/summary` |
| [STAT-02](#stat-02) | 지표 추이 | `GET` | `/analytics/trends` |
| [STAT-03](#stat-03) | 이전 개인 경기와 비교 | `GET` | `/analytics/match-comparison` |
| [STAT-04](#stat-04) | 쿼터별 포지션 분포 | `GET` | `/analytics/positions` |
| [STAT-05](#stat-05) | 친구와 개인 기록 비교 | `GET` | `/analytics/friend-comparison` |

<a id="stat-01"></a>
## STAT-01 · 개인 기간 통계

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/analytics/summary` |
| 권한 | SELF / FRIEND_VISIBLE |
| 성공 | 200 · Summary |

### 입력과 헤더

query: from, to, subjectUserId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 완료 쿼터 기준 개인 합계와 metricCoverage를 반환한다. 운동 요약 처리 중·미수신이어도 200으로 누락 범위를 명시한다.

### 오류와 앱 대응

동의 미충족은 403 CONSENT_REQUIRED, 비친구/차단 등 비공개 대상은 404. metric·기간·버킷 오류는 422. 지표 미계산은 200 상태로 표현한다. [공통 오류](common.md#errors)도 적용한다.

<a id="stat-02"></a>
## STAT-02 · 지표 추이

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/analytics/trends` |
| 권한 | SELF / FRIEND_VISIBLE |
| 성공 | 200 · TrendPoint[] |

### 입력과 헤더

query: metric(필수 지원 지표), bucket(DAY/MONTH, 필수), from/to, timeZone, subjectUserId, cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. bucketStart 오름차순. 버킷마다 공통 집계식을 사용한다. 빈 버킷의 운동 지표는 null이다.
2. 버킷당 데이터가 없는 것과 값이 실제 0인 것을 구별한다. 명시한 기간 안의 빈 버킷도 반환한다. 생략한 경계는 조회 가능한 첫·마지막 쿼터를 사용하고, 해당 데이터가 없으면 빈 목록을 반환한다.

### 오류와 앱 대응

동의 미충족은 403 CONSENT_REQUIRED, 비친구/차단 등 비공개 대상은 404. metric·기간·버킷 오류는 422. 지표 미계산은 200 상태로 표현한다. [공통 오류](common.md#errors)도 적용한다.

<a id="stat-03"></a>
## STAT-03 · 이전 개인 경기와 비교

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/analytics/match-comparison` |
| 권한 | OWNER |
| 성공 | 200 · MatchComparison |

### 입력과 헤더

query: matchId(필수), previousMatchId(선택), metric(필수).

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 COMPLETED 경기만. previousMatchId가 없으면 현재 경기보다 endedAt/id가 이전인 가장 최근 완료 경기를 선택한다.
2. 각 경기의 모든 완료 쿼터를 비교하고 같은 경기는 중복 비교하지 않는다. 이전 경기가 없으면 previous/delta=null이다.

### 오류와 앱 대응

동의 미충족은 403 CONSENT_REQUIRED, 비친구/차단 등 비공개 대상은 404. metric·기간·버킷 오류는 422. 지표 미계산은 200 상태로 표현한다. [공통 오류](common.md#errors)도 적용한다.

<a id="stat-04"></a>
## STAT-04 · 쿼터별 포지션 분포

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/analytics/positions` |
| 권한 | SELF / FRIEND_VISIBLE |
| 성공 | 200 · PositionAnalysis[] |

### 입력과 헤더

query: from, to, subjectUserId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 쿼터 수 기준 분포를 제공한다. 미입력 포지션은 positionCode=null 버킷에 포함한다.
2. 분모는 전체 완료 쿼터 수이며 대상이 없으면 빈 배열이다. 시간 가중 분포는 별도 확정 전 제공하지 않는다.

### 오류와 앱 대응

동의 미충족은 403 CONSENT_REQUIRED, 비친구/차단 등 비공개 대상은 404. metric·기간·버킷 오류는 422. 지표 미계산은 200 상태로 표현한다. [공통 오류](common.md#errors)도 적용한다.

<a id="stat-05"></a>
## STAT-05 · 친구와 개인 기록 비교

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/analytics/friend-comparison` |
| 권한 | SELF / FRIEND_VISIBLE |
| 성공 | 200 · FriendComparison |

### 입력과 헤더

query: friendUserId, metric(각 필수), from/to(선택).

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 현재 친구 관계와 양쪽의 최신 FRIEND_COMPARISON 동의를 확인한다. 차단·친구 해제·동의 철회 후에는 반환하지 않는다.
2. 동일 기간·동일 산식의 각자 쿼터 요약을 비교한다. 함께 경기한 것으로 간주하지 않는다.
3. 상대 경기 ID, GPS·심박 원본, 계산 기준의 민감한 상세는 공개하지 않고 허용된 합계만 제공한다.

### 오류와 앱 대응

동의 미충족은 403 CONSENT_REQUIRED, 비친구/차단 등 비공개 대상은 404. metric·기간·버킷 오류는 422. 지표 미계산은 200 상태로 표현한다. [공통 오류](common.md#errors)도 적용한다.
