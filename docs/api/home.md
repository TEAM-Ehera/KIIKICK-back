# 홈

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-02 · 구현 전 설계**

요구사항 F05. 본인 경기 묶음과 최근 쿼터 결과를 한 번에 표시한다. 친구의 원본 기록을 포함하지 않는다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [HOME-01](#home-01) | 홈 요약 조회 | `GET` | `/home` |

<a id="home-01"></a>
## HOME-01 · 홈 요약 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/home` |
| 권한 | SELF |
| 성공 | 200 · Home |

### 입력과 헤더

query: recentLimit(기본 3, 1~10 기술 초안).

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. activeMatches는 IN_PROGRESS 중 startedAt/id 내림차순 최대 5개, readyMatches는 scheduledAt/id 오름차순 최대 5개이며 예정 시각 null은 뒤에 둔다.
2. recentMatches는 COMPLETED 중 endedAt/id 내림차순 recentLimit개다. recentQuarterResults는 COMPLETED 쿼터를 endedAt/id 내림차순 recentLimit개 반환한다.
3. 최근 쿼터의 저장된 기록·요약만 읽는다. 운동이 늦게 도착하면 null 또는 계산 상태를 표시하며 홈 요청이 원본 재계산을 시작하지 않는다.
4. 경기 자체가 없으면 NO_MATCHES, 경기는 있으나 완료 쿼터가 없으면 NO_COMPLETED_QUARTERS, 그 외 emptyReason=null이다. 배열별 cursor는 없고 더 보기는 목록 API를 사용한다.

### 오류와 앱 대응

본인 인증과 공통 조회 오류만 적용한다. [공통 오류](common.md#errors)도 적용한다.
