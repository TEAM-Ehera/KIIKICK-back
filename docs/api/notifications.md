# 알림·기기 API

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정 목록](../decisions.md)

요구사항: **F02** · 관련 정책: **P09 / P10 / P11** · 계약 버전: **v0.4 검토 초안**

알림 유형·발생 조건·대상·보존 정책은 P09 미확정이다. 읽음 처리와 알림이 가리키는 실제 도메인 변경은 서로 다른 동작이다.

알림 target의 MATCH는 수신자 본인 경기만 가리킬 수 있다. 친구의 결과를 알리는 후보 기능은 공개 분석으로 이동해야 하며, 타인의 경기 상세로 이동하는 계약은 없다. 알림 종류와 트리거는 P09 미확정이다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [NOTI-01](#noti-01) | 알림 목록 조회 | `GET` | `/notifications` |
| [NOTI-02](#noti-02) | 읽지 않은 알림 수 조회 | `GET` | `/notifications/unread-count` |
| [NOTI-03](#noti-03) | 알림 읽음 처리 | `PUT` | `/notifications/{notificationId}/read` |
| [NOTI-04](#noti-04) | 기기 등록·푸시 설정 갱신 | `PUT` | `/devices/{deviceId}` |
| [NOTI-05](#noti-05) | 기기 등록 해제 | `DELETE` | `/devices/{deviceId}` |

---

<a id="noti-01"></a>
## NOTI-01 · 알림 목록 조회

알림 탭에서 내 알림과 이동 대상을 확인한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/notifications` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Notification](schemas.md#notification) 배열 |
| 정책 검토 | P09 / P10 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | X | 사용하지 않음 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

**Query parameter**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `unreadOnly` | boolean | X | 불가 | 기본 false | true이면 readAt=null만 반환 |
| `cursor` | string | X | 불가 | — | 직전 응답의 meta.pagination.nextCursor. 최초 조회 시 생략 |
| `limit` | integer | X | 불가 | 기본 20 | 한 번에 조회할 개수. 1~100 |

Request body: 없음.

### 처리 규칙

1. createdAt/id 내림차순. 빈 결과도 200과 빈 배열을 반환한다.
2. target은 이동 힌트다. 본인 경기와 현재 접근 가능한 받은 대기 친구 요청의 알림만 목록·미읽음 수·읽음 처리에 사용한다. 이동 대상 조회 시에도 권한을 검사한다.

### 요청 예시

```http
GET /api/v1/notifications?limit=20 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data` 배열의 **요소 한 개**의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Notification](schemas.md#notification)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인이 수신한 알림 ID |
| `type` | string | O | 불가 | — | 알림 종류. 예시 코드는 P09 확정 전 후보 |
| `title` | string | O | 불가 | — | 표시 제목 |
| `body` | string | O | 허용 | — | 부가 문구 |
| `target` | object | O | 불가 | — | 이동 대상 힌트. 실제 조회 시 재인가 |
| `readAt` | timestamp | O | 허용 | — | 최초 읽음 시각. 미열람 null |
| `createdAt` | timestamp | O | 불가 | — | 알림 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": [
    {
      "id": "00000012-0000-4000-8000-000000000012",
      "type": "FRIEND_REQUEST_RECEIVED",
      "title": "친구 요청이 도착했어요",
      "body": null,
      "target": {
        "type": "FRIEND_REQUEST",
        "id": "00000013-0000-4000-8000-000000000013"
      },
      "readAt": null,
      "createdAt": "2026-11-02T10:00:00+09:00"
    }
  ],
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:10:00+09:00",
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    }
  }
}
```

**빈 목록 / 다음 페이지**: 빈 목록은 `data: []`로 반환한다. 마지막 페이지는 `meta.pagination.nextCursor=null`, `hasNext=false`. 다음 페이지는 응답 커서를 원래 필터와 함께 전달한다.

### 오류와 앱 대응

| HTTP | code | 발생 조건 | 앱 대응 |
| --- | --- | --- | --- |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)


---

<a id="noti-02"></a>
## NOTI-02 · 읽지 않은 알림 수 조회

홈/탭 배지 수를 가져온다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/notifications/unread-count` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · UnreadCount](schemas.md#unreadcount) |
| 정책 검토 | P09 / P10 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | X | 사용하지 않음 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 본인에게 현재 허용된 알림만 센다. 타인의 수신 여부를 노출하지 않는다.

### 요청 예시

```http
GET /api/v1/notifications/unread-count HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · UnreadCount](schemas.md#unreadcount)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `count` | integer | O | 불가 | — | 현재 사용자의 readAt=null인 알림 수. 0 이상 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "count": 2
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

### 오류와 앱 대응

| HTTP | code | 발생 조건 | 앱 대응 |
| --- | --- | --- | --- |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)


---

<a id="noti-03"></a>
## NOTI-03 · 알림 읽음 처리

사용자가 알림을 열었을 때 최초 읽음 시각을 저장한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/notifications/{notificationId}/read` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Notification](schemas.md#notification) |
| 정책 검토 | P09 / P10 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | O | 같은 동작 재전송에는 동일 UUID 유지 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

**Path parameter**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `notificationId` | UUID | O | 불가 | 00000012-0000-4000-8000-000000000012 | 본인이 수신한 알림 ID |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. readAt은 서버 최초 읽음 시각을 유지한다. 다른 기기에서 다시 읽어도 덮어쓰지 않는다.

### 요청 예시

```http
PUT /api/v1/notifications/00000012-0000-4000-8000-000000000012/read HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Notification](schemas.md#notification)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인이 수신한 알림 ID |
| `type` | string | O | 불가 | — | 알림 종류. 예시 코드는 P09 확정 전 후보 |
| `title` | string | O | 불가 | — | 표시 제목 |
| `body` | string | O | 허용 | — | 부가 문구 |
| `target` | object | O | 불가 | — | 이동 대상 힌트. 실제 조회 시 재인가 |
| `readAt` | timestamp | O | 허용 | — | 최초 읽음 시각. 미열람 null |
| `createdAt` | timestamp | O | 불가 | — | 알림 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000012-0000-4000-8000-000000000012",
    "type": "FRIEND_REQUEST_RECEIVED",
    "title": "친구 요청이 도착했어요",
    "body": null,
    "target": {
      "type": "FRIEND_REQUEST",
      "id": "00000013-0000-4000-8000-000000000013"
    },
    "readAt": "2026-11-02T10:10:00+09:00",
    "createdAt": "2026-11-02T10:00:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

### 오류와 앱 대응

| HTTP | code | 발생 조건 | 앱 대응 |
| --- | --- | --- | --- |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 404**

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "대상이 없거나 조회 권한이 없음",
    "retryable": false,
    "persistence": "NOT_SAVED",
    "details": {}
  },
  "meta": {
    "requestId": "req-error-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)


---

<a id="noti-04"></a>
## NOTI-04 · 기기 등록·푸시 설정 갱신

앱/Watch의 기기를 등록하고 푸시 발송 여부를 갱신한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/devices/{deviceId}` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Device](schemas.md#device) |
| 정책 검토 | P09 / P10 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Content-Type | O | `application/json` |
| Idempotency-Key | O | 같은 동작 재전송에는 동일 UUID 유지 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

**Path parameter**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `deviceId` | UUID | O | 불가 | 00000011-0000-4000-8000-000000000011 | 본인 앱/Watch 설치의 등록 기기 ID |

Query parameter: 없음.

**Request body**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `platform` | enum | O | 불가 | — | IOS 또는 WATCHOS |
| `pushToken` | string | X | 허용 | — | 푸시 토큰. 누락 유지, null은 제거. 원문 응답/로그 금지 |
| `pushEnabled` | boolean | O | 불가 | — | 서버 발송 허용 여부. OS 권한은 클라이언트가 확인 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 클라이언트가 생성한 deviceId로 upsert하며 200을 반환한다. userId는 인증 사용자로 결정한다.
2. 타인 deviceId의 소유권을 덮어쓰지 않는다. pushEnabled=true인데 저장 토큰도 없으면 422.
3. P10 확정 전에는 기기 ID의 platform 변경과 해제된 ID의 재활성화를 거절한다. 새 설치나 다른 platform은 새 기기 ID를 사용한다.
4. 토큰은 최대 1,024 UTF-8 바이트로 제한하고 AES-GCM으로 암호화해 저장한다. 서버의 `KIIKICK_NOTIFICATION_PUSH_TOKEN_KEY`에는 Base64로 인코딩한 32바이트 키를 설정한다. 키 없이 새 토큰을 제출하면 503 TEMPORARILY_UNAVAILABLE을 반환한다.

### 요청 예시

```http
PUT /api/v1/devices/00000011-0000-4000-8000-000000000011 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "platform": "WATCHOS",
  "pushToken": null,
  "pushEnabled": false
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Device](schemas.md#device)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 기기 ID |
| `platform` | enum | O | 불가 | — | IOS 또는 WATCHOS |
| `pushEnabled` | boolean | O | 불가 | — | 서버 발송 허용 여부. OS 권한도 별도 확인 |
| `lastSeenAt` | timestamp | O | 불가 | — | 마지막 서버 접속 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000011-0000-4000-8000-000000000011",
    "platform": "WATCHOS",
    "pushEnabled": false,
    "lastSeenAt": "2026-11-02T10:00:00+09:00"
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

### 오류와 앱 대응

| HTTP | code | 발생 조건 | 앱 대응 |
| --- | --- | --- | --- |
| 409 | `DEVICE_OWNERSHIP_CONFLICT` | 타인 소유 기기 ID | 본인 앱 설치에 해당하는 새 ID 사용 |
| 422 | `VALIDATION_FAILED` | 토큰 없는 pushEnabled=true 또는 잘못된 플랫폼 | 토큰/동의 상태를 확인 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 409**

```json
{
  "error": {
    "code": "DEVICE_OWNERSHIP_CONFLICT",
    "message": "타인 소유 기기 ID",
    "retryable": false,
    "persistence": "NOT_SAVED",
    "details": {}
  },
  "meta": {
    "requestId": "req-error-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)


---

<a id="noti-05"></a>
## NOTI-05 · 기기 등록 해제

해당 기기로 푸시와 직접 동기화가 더 이상 이뤄지지 않도록 해제한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/devices/{deviceId}` |
| 권한 | SELF |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P09 / P10 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | O | 같은 동작 재전송에는 동일 UUID 유지 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

**Path parameter**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `deviceId` | UUID | O | 불가 | 00000011-0000-4000-8000-000000000011 | 본인 앱/Watch 설치의 등록 기기 ID |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 기기 revokedAt을 기록하고 push token을 폐기한다. 감사 이력 참조를 즉시 연쇄 삭제하지 않는다.
2. 해제된 기기의 새 sync 요청은 거절한다. 기존 작업 결과 조회 권한은 사용자 기준으로 검증한다.

### 요청 예시

```http
DELETE /api/v1/devices/00000011-0000-4000-8000-000000000011 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
```

### 성공 응답

```http
HTTP/1.1 204 No Content
X-Request-Id: req-example
```

본문을 반환하지 않는다.

### 오류와 앱 대응

| HTTP | code | 발생 조건 | 앱 대응 |
| --- | --- | --- | --- |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 404**

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "대상이 없거나 조회 권한이 없음",
    "retryable": false,
    "persistence": "NOT_SAVED",
    "details": {}
  },
  "meta": {
    "requestId": "req-error-example",
    "serverTime": "2026-11-02T10:10:00+09:00"
  }
}
```

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)
