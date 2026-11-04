# 경기장·개인 경기·쿼터 세팅

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-02 · 구현 전 설계**

요구사항 F03. 경기는 본인 쿼터의 날짜·장소 묶음이다. 준비/시작 상태는 유지하며 타인 등록이나 팀 배정은 없다. 쿼터가 운동·개인 기록의 기준 단위다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [SET-01](#set-01) | 저장 경기장 목록·검색 | `GET` | `/venues` |
| [SET-02](#set-02) | 저장 경기장 상세 | `GET` | `/venues/{venueId}` |
| [SET-03](#set-03) | 경기장 저장 | `POST` | `/venues` |
| [SET-04](#set-04) | 경기장 수정 | `PATCH` | `/venues/{venueId}` |
| [SET-05](#set-05) | 경기장 삭제 | `DELETE` | `/venues/{venueId}` |
| [SET-06](#set-06) | 내 경기 목록 | `GET` | `/matches` |
| [SET-07](#set-07) | 개인 경기 묶음 생성 | `POST` | `/matches` |
| [SET-08](#set-08) | 경기 상세·최신 버전 | `GET` | `/matches/{matchId}` |
| [SET-09](#set-09) | 경기 설정 수정 | `PATCH` | `/matches/{matchId}` |
| [SET-10](#set-10) | 경기 준비 완료 | `POST` | `/matches/{matchId}/ready` |
| [SET-11](#set-11) | 시작 전 경기 취소 | `POST` | `/matches/{matchId}/cancel` |
| [SET-16](#set-16) | 쿼터 목록 | `GET` | `/matches/{matchId}/quarters` |
| [SET-17](#set-17) | 쿼터 추가 | `POST` | `/matches/{matchId}/quarters` |
| [SET-18](#set-18) | 미시작 쿼터 시간 수정 | `PATCH` | `/matches/{matchId}/quarters/{quarterId}` |
| [SET-19](#set-19) | 미사용 마지막 쿼터 삭제 | `DELETE` | `/matches/{matchId}/quarters/{quarterId}` |

<a id="set-01"></a>
## SET-01 · 저장 경기장 목록·검색

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/venues` |
| 권한 | SELF |
| 성공 | 200 · Venue[] |

### 입력과 헤더

query: q(선택 검색어), cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 소유 경기장 이름에서 q를 일반 문자열로 검색한다. updatedAt/id 내림차순이며 커서는 사용자와 검색어에 귀속된다. 기본 20건, 최대 100건을 반환한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-02"></a>
## SET-02 · 저장 경기장 상세

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/venues/{venueId}` |
| 권한 | SELF |
| 성공 | 200 · Venue |

### 입력과 헤더

path: venueId(UUID).

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 소유 경기장만 조회한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-03"></a>
## SET-03 · 경기장 저장

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/venues` |
| 권한 | SELF |
| 성공 | 201 · Venue |

### 입력과 헤더

본문: `name` 필수 비어 있지 않은 문자열, `address` 선택 nullable 문자열. P12 확정 전 저장 제약에 맞춰 이름 최대 255자와 주소 최대 1,000자를 적용한다.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. If-Match는 사용하지 않는다.

### 처리 규칙

1. ownerId는 본인. Location에 생성한 경기장 경로를 반환한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-04"></a>
## SET-04 · 경기장 수정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PATCH /api/v1/venues/{venueId}` |
| 권한 | SELF |
| 성공 | 200 · Venue |

### 입력과 헤더

path: venueId. name/address는 선택; name=null 불가, address=null 허용.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. If-Match는 사용하지 않는다.

### 처리 규칙

1. 누락은 유지한다. 이미 저장된 경기의 장소 사본은 바꾸지 않는다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-05"></a>
## SET-05 · 경기장 삭제

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/venues/{venueId}` |
| 권한 | SELF |
| 성공 | 204 |

### 입력과 헤더

path: venueId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. If-Match는 사용하지 않는다.

### 처리 규칙

1. 본인 경기장을 삭제한다. 연결된 경기의 venueId를 null로 변경하고 경기 버전을 한 번 증가시킨다. 기존 장소 사본은 보존한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-06"></a>
## SET-06 · 내 경기 목록

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches` |
| 권한 | OWNER |
| 성공 | 200 · Match[] |

### 입력과 헤더

query: status(선택), cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. ownerId=본인만. createdAt/id 내림차순.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-07"></a>
## SET-07 · 개인 경기 묶음 생성

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches` |
| 권한 | OWNER |
| 성공 | 201 · Match |

### 입력과 헤더

본문: title(nullable 선택), venueId(nullable 선택), venueSnapshot(nullable 선택; name/address), scheduledAt(nullable 선택, +09:00 포함 서울 시각).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. If-Match는 사용하지 않는다.

### 처리 규칙

1. DRAFT/version=1로 생성하며 ownerId는 본인이다. Location에는 생성한 경기 상세 경로를 반환한다.
2. venueId와 직접 venueSnapshot을 동시에 지정하면 422. venueId 지정 시 본인 경기장만 선택하고 사본을 저장한다. 둘 다 생략 가능.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-08"></a>
## SET-08 · 경기 상세·최신 버전

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 경기만 조회하고 ETag를 반환한다.

### 성공 응답 예시

```json
{
  "data": {
    "id": "00000003-0000-4000-8000-000000000003",
    "ownerId": "00000001-0000-4000-8000-000000000001",
    "title": "월요일 풋살",
    "venueId": null,
    "venueSnapshot": {
      "name": "서울 풋살장",
      "address": null
    },
    "scheduledAt": "2026-11-02T10:00:00+09:00",
    "status": "IN_PROGRESS",
    "startedAt": "2026-11-02T10:00:00+09:00",
    "endedAt": null,
    "version": 8,
    "createdAt": "2026-11-02T09:50:00+09:00",
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

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-09"></a>
## SET-09 · 경기 설정 수정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PATCH /api/v1/matches/{matchId}` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId. 본문은 생성 필드의 선택형; 누락 유지, nullable만 null 허용.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. DRAFT/READY에서만 변경한다. venueId와 직접 장소 사본은 한 요청에서 함께 지정하지 않는다.
2. 장소 선택·해제는 사본과 참조를 일관되게 변경한다. 장소 사본만 변경하면 기존 venueId를 해제한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-10"></a>
## SET-10 · 경기 준비 완료

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/ready` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. DRAFT에서 READY로 변경한다. 최소 한 개 READY 쿼터가 필요하다. 장소·일시 필수 여부는 P03/P12에서 결정한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-11"></a>
## SET-11 · 시작 전 경기 취소

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/cancel` |
| 권한 | OWNER |
| 성공 | 200 · Match |

### 입력과 헤더

path: matchId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. DRAFT/READY만 CANCELLED로 변경한다. 진행 후 취소·재개는 P06 미정이다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-16"></a>
## SET-16 · 쿼터 목록

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/matches/{matchId}/quarters` |
| 권한 | OWNER |
| 성공 | 200 · Quarter[] |

### 입력과 헤더

path: matchId. query: cursor, limit.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인 경기 쿼터만 number/id 오름차순. meta.matchVersion 및 ETag를 반환한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-17"></a>
## SET-17 · 쿼터 추가

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/matches/{matchId}/quarters` |
| 권한 | OWNER |
| 성공 | 201 · Quarter |

### 입력과 헤더

path: matchId. 본문: durationSeconds(양의 정수, 필수).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. DRAFT/READY/IN_PROGRESS 경기에서 허용한다. 번호는 경기 내 기존 최댓값+1을 부여한다.
2. 측정·통계·포지션 행을 가짜 0으로 미리 만들지 않는다. 쿼터 수·시간 상한은 P03에서 확정한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-18"></a>
## SET-18 · 미시작 쿼터 시간 수정

| 항목 | 내용 |
| --- | --- |
| 요청 | `PATCH /api/v1/matches/{matchId}/quarters/{quarterId}` |
| 권한 | OWNER |
| 성공 | 200 · Quarter |

### 입력과 헤더

path: matchId, quarterId. 본문: durationSeconds(양의 정수, 필수).

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. READY 쿼터만 변경한다. 설정 시간과 실제 측정 시간은 구별한다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

<a id="set-19"></a>
## SET-19 · 미사용 마지막 쿼터 삭제

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/matches/{matchId}/quarters/{quarterId}` |
| 권한 | OWNER |
| 성공 | 204 |

### 입력과 헤더

path: matchId, quarterId. 본문 없음.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. 기존 경기/쿼터를 변경하므로 최신 경기 ETag를 If-Match로 보낸다.

### 처리 규칙

1. DRAFT/READY/IN_PROGRESS 경기의 마지막 번호 READY 쿼터만 삭제한다.
2. 시작 시각·개인 기록·포지션·운동·요약 중 하나라도 존재하면 거절한다. 진행·완료 쿼터는 삭제하지 않는다.

### 오류와 앱 대응

타인 리소스/경기와 불일치하는 쿼터는 404. 상태 불일치는 409 INVALID_STATE, 잘못된 설정은 422. [공통 오류](common.md#errors)도 적용한다.

## v0.4 경로 변경

SET-12~SET-15의 경기 참가자 조회·추가·팀 수정·제거는 제거했다. 서버가 이 초안의 제거 경로를 새로 제공하지 않는다. 이전 클라이언트의 호출은 유지 계약이 아니다.
