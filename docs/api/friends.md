# 친구 API

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정 목록](../decisions.md)

요구사항: **F06** · 관련 정책: **P07** · 계약 버전: **v0.4 · #41 구현**

친구 요청은 수신자가 직접 승인한다. 공개 조회에는 온보딩을 완료한 사용자의 ID·닉네임·아바타·조회자 기준 관계를 반환한다. 차단 관계의 대상은 양쪽 모두 검색과 공개 프로필에서 숨긴다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [FRIEND-01](#friend-01) | 친구 검색 | `GET` | `/users/search` |
| [FRIEND-02](#friend-02) | 친구·사용자 공개 프로필 조회 | `GET` | `/users/{userId}/profile` |
| [FRIEND-03](#friend-03) | 내 친구 목록 조회 | `GET` | `/friends` |
| [FRIEND-04](#friend-04) | 보낸·받은 친구 요청 목록 | `GET` | `/friend-requests` |
| [FRIEND-05](#friend-05) | 친구 요청 보내기 | `POST` | `/friend-requests` |
| [FRIEND-06](#friend-06) | 친구 요청 수락 | `POST` | `/friend-requests/{requestId}/accept` |
| [FRIEND-07](#friend-07) | 친구 요청 취소·거절 | `DELETE` | `/friend-requests/{requestId}` |
| [FRIEND-08](#friend-08) | 친구 삭제 | `DELETE` | `/friends/{userId}` |
| [FRIEND-09](#friend-09) | 사용자 차단 | `PUT` | `/users/{userId}/block` |
| [FRIEND-10](#friend-10) | 사용자 차단 해제 | `DELETE` | `/users/{userId}/block` |

---

<a id="friend-01"></a>
## FRIEND-01 · 친구 검색

친구로 추가할 사용자의 최소 공개 프로필을 찾는다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/users/search` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · PublicProfile](schemas.md#publicprofile) 배열 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `query` | string | O | 불가 | 민수 | 공백을 제외한 2~255자의 닉네임 부분 검색 |
| `cursor` | string | X | 불가 | — | 직전 응답의 meta.pagination.nextCursor. 최초 조회 시 생략 |
| `limit` | integer | X | 불가 | 기본 20 | 한 번에 조회할 개수. 1~100 |

Request body: 없음.

### 처리 규칙

1. 본인과 양방향 차단 사용자를 제외한다. nickname/id 오름차순. 검색 가능 공개 범위를 서버에서 검사한다.
2. 이메일·비공개 식별자·친구의 경기 목록은 반환하지 않는다.

### 요청 예시

```http
GET /api/v1/users/search?query=민수&limit=20 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data` 배열의 **요소 한 개**의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · PublicProfile](schemas.md#publicprofile)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 공개 범위의 사용자 ID |
| `nickname` | string | O | 불가 | — | 온보딩 완료한 사용자의 표시명 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `relationship` | enum | O | 불가 | — | 조회자 기준 NONE/PENDING_SENT/PENDING_RECEIVED/FRIEND/BLOCKED |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": [
    {
      "id": "00000002-0000-4000-8000-000000000002",
      "nickname": "민",
      "avatarUrl": null,
      "relationship": "NONE"
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
| 422 | `VALIDATION_FAILED` | 검색어가 확정된 검색 규칙 미충족 | 검색어 수정 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "검색어가 확정된 검색 규칙 미충족",
    "retryable": false,
    "persistence": null,
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

<a id="friend-02"></a>
## FRIEND-02 · 친구·사용자 공개 프로필 조회

검색 결과나 친구 목록에서 공개 프로필과 현재 관계를 확인한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/users/{userId}/profile` |
| 권한 | 공개 범위 허용 사용자 |
| 성공 | **200** · [응답 필드 사전 · PublicProfile](schemas.md#publicprofile) |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | X | 사용하지 않음 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

**Path parameter**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `userId` | UUID | O | 불가 | 00000001-0000-4000-8000-000000000001 | 대상 회원 ID. 본인 프로필 API와 구분 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 친구 여부만으로 개인 위치·비공개 경기 전체를 공개하지 않는다.
2. 차단·비공개 대상은 404로 처리한다.

### 요청 예시

```http
GET /api/v1/users/00000001-0000-4000-8000-000000000001/profile HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · PublicProfile](schemas.md#publicprofile)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 공개 범위의 사용자 ID |
| `nickname` | string | O | 불가 | — | 온보딩 완료한 사용자의 표시명 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `relationship` | enum | O | 불가 | — | 조회자 기준 NONE/PENDING_SENT/PENDING_RECEIVED/FRIEND/BLOCKED |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000002-0000-4000-8000-000000000002",
    "nickname": "민",
    "avatarUrl": null,
    "relationship": "FRIEND"
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

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 404**

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "대상이 없거나 조회 권한이 없음",
    "retryable": false,
    "persistence": null,
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

<a id="friend-03"></a>
## FRIEND-03 · 내 친구 목록 조회

친구 탭에서 현재 성립된 친구 관계를 표시한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/friends` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Friend](schemas.md#friend) 배열 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `cursor` | string | X | 불가 | — | 직전 응답의 meta.pagination.nextCursor. 최초 조회 시 생략 |
| `limit` | integer | X | 불가 | 기본 20 | 한 번에 조회할 개수. 1~100 |

Request body: 없음.

### 처리 규칙

1. ACCEPTED 관계이면서 차단이 없는 사용자만 반환한다. friendsSince/관계 ID 내림차순.

### 요청 예시

```http
GET /api/v1/friends?limit=20 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data` 배열의 **요소 한 개**의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Friend](schemas.md#friend)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `profile` | PublicProfile | O | 불가 | — | 친구의 공개 프로필. relationship=FRIEND |
| `friendsSince` | timestamp | O | 불가 | — | 친구 요청 수락 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": [
    {
      "profile": {
        "id": "00000002-0000-4000-8000-000000000002",
        "nickname": "민",
        "avatarUrl": null,
        "relationship": "FRIEND"
      },
      "friendsSince": "2026-11-02T10:00:00+09:00"
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

<a id="friend-04"></a>
## FRIEND-04 · 보낸·받은 친구 요청 목록

수락 전 PENDING 요청을 방향별로 조회한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/friend-requests` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · FriendRequest](schemas.md#friendrequest) 배열 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `direction` | enum | O | 불가 | RECEIVED | SENT 또는 RECEIVED |
| `cursor` | string | X | 불가 | — | 직전 응답의 meta.pagination.nextCursor. 최초 조회 시 생략 |
| `limit` | integer | X | 불가 | 기본 20 | 한 번에 조회할 개수. 1~100 |

Request body: 없음.

### 처리 규칙

1. createdAt/id 내림차순. 수락/취소/거절된 요청은 이 목록에서 제외한다.
2. 응답 requesterId/recipientId는 고정 방향이다. PENDING_SENT/RECEIVED라는 조회자 상태와 구별한다.

### 요청 예시

```http
GET /api/v1/friend-requests?direction=RECEIVED&limit=20 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data` 배열의 **요소 한 개**의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · FriendRequest](schemas.md#friendrequest)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 요청 ID. 취소 후 재요청이면 새로운 ID |
| `requesterId` | UUID | O | 불가 | — | 요청한 사용자 |
| `recipientId` | UUID | O | 불가 | — | 받는 사용자 |
| `status` | enum | O | 불가 | — | PENDING 또는 ACCEPTED |
| `createdAt` | timestamp | O | 불가 | — | 요청 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": [
    {
      "id": "00000013-0000-4000-8000-000000000013",
      "requesterId": "00000001-0000-4000-8000-000000000001",
      "recipientId": "00000002-0000-4000-8000-000000000002",
      "status": "PENDING",
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

<a id="friend-05"></a>
## FRIEND-05 · 친구 요청 보내기

사용자 검색 또는 공개 프로필에서 친구 요청을 보낸다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/friend-requests` |
| 권한 | SELF |
| 성공 | **201** · [응답 필드 사전 · FriendRequest](schemas.md#friendrequest) |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Content-Type | O | `application/json` |
| Idempotency-Key | O | 같은 동작 재전송에는 동일 UUID 유지 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

Query parameter: 없음.

**Request body**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `targetUserId` | UUID | O | 불가 | — | 친구 요청 대상. 본인은 불가 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 수신자가 직접 승인한다. 발신자는 PENDING_SENT, 수신자는 PENDING_RECEIVED로 보인다.
2. 역방향 요청이 이미 있어도 자동 수락하지 않는다. 해당 요청을 사용자가 명시적으로 수락한다.
3. 동시 반대 요청은 같은 사용자 pair 잠금·유일키로 중복을 방지한다.

### 요청 예시

```http
POST /api/v1/friend-requests HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "targetUserId": "00000002-0000-4000-8000-000000000002"
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · FriendRequest](schemas.md#friendrequest)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 요청 ID. 취소 후 재요청이면 새로운 ID |
| `requesterId` | UUID | O | 불가 | — | 요청한 사용자 |
| `recipientId` | UUID | O | 불가 | — | 받는 사용자 |
| `status` | enum | O | 불가 | — | PENDING 또는 ACCEPTED |
| `createdAt` | timestamp | O | 불가 | — | 요청 생성 시각 |

```http
HTTP/1.1 201 Created
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000013-0000-4000-8000-000000000013",
    "requesterId": "00000001-0000-4000-8000-000000000001",
    "recipientId": "00000002-0000-4000-8000-000000000002",
    "status": "PENDING",
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
| 422 | `SELF_RELATION_NOT_ALLOWED` | 자기 자신에게 요청 | 다른 사용자 선택 |
| 409 | `RELATIONSHIP_EXISTS` | 이미 친구 또는 같은 방향 요청 존재 | 현재 관계 표시 |
| 409 | `REQUEST_ALREADY_RECEIVED` | 상대가 먼저 요청함 | 받은 요청 수락 화면으로 이동 |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "SELF_RELATION_NOT_ALLOWED",
    "message": "자기 자신에게 요청",
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

<a id="friend-06"></a>
## FRIEND-06 · 친구 요청 수락

받은 요청을 수락하고 친구 관계를 만든다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/friend-requests/{requestId}/accept` |
| 권한 | 요청 수신자 |
| 성공 | **200** · [응답 필드 사전 · FriendRequest](schemas.md#friendrequest) |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `requestId` | UUID | O | 불가 | 00000013-0000-4000-8000-000000000013 | 해당 친구 요청의 ID. 취소 후 재요청이면 달라짐 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. PENDING 요청의 수신자만 실행한다. 처리 직전 차단·취소·상태를 다시 검사한다.

### 요청 예시

```http
POST /api/v1/friend-requests/00000013-0000-4000-8000-000000000013/accept HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · FriendRequest](schemas.md#friendrequest)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 요청 ID. 취소 후 재요청이면 새로운 ID |
| `requesterId` | UUID | O | 불가 | — | 요청한 사용자 |
| `recipientId` | UUID | O | 불가 | — | 받는 사용자 |
| `status` | enum | O | 불가 | — | PENDING 또는 ACCEPTED |
| `createdAt` | timestamp | O | 불가 | — | 요청 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000013-0000-4000-8000-000000000013",
    "requesterId": "00000001-0000-4000-8000-000000000001",
    "recipientId": "00000002-0000-4000-8000-000000000002",
    "status": "ACCEPTED",
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
| 403 | `FORBIDDEN` | 발신자 또는 제3자가 수락 시도 | 수신 계정으로 확인 |
| 409 | `INVALID_STATE` | 현재 상태에서 이 동작을 실행할 수 없음 | 최신 친구 요청 상태를 조회하고 가능한 동작 표시 |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 403**

```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "발신자 또는 제3자가 수락 시도",
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

<a id="friend-07"></a>
## FRIEND-07 · 친구 요청 취소·거절

발신자는 보낸 요청 취소, 수신자는 받은 요청 거절을 실행한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/friend-requests/{requestId}` |
| 권한 | 요청 당사자 |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `requestId` | UUID | O | 불가 | 00000013-0000-4000-8000-000000000013 | 해당 친구 요청의 ID. 취소 후 재요청이면 달라짐 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. PENDING 관계만 제거한다. ACCEPTED 관계는 친구 삭제 API를 사용한다. 취소된 ID는 다시 수락할 수 없다.

### 요청 예시

```http
DELETE /api/v1/friend-requests/00000013-0000-4000-8000-000000000013 HTTP/1.1
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
| 409 | `INVALID_STATE` | 현재 상태에서 이 동작을 실행할 수 없음 | 최신 친구 요청 상태를 조회하고 가능한 동작 표시 |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 409**

```json
{
  "error": {
    "code": "INVALID_STATE",
    "message": "현재 상태에서 이 동작을 실행할 수 없음",
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

<a id="friend-08"></a>
## FRIEND-08 · 친구 삭제

현재 친구 관계를 해제한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/friends/{userId}` |
| 권한 | SELF |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `userId` | UUID | O | 불가 | 00000001-0000-4000-8000-000000000001 | 대상 회원 ID. 본인 프로필 API와 구분 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 양쪽 친구 목록과 비교 데이터 공개 권한에서 즉시 제거한다.
2. 친구 해제만으로 각자 소유한 개인 경기·쿼터 원본을 삭제하지 않는다. 수락된 관계가 없으면 204를 반환한다.

### 요청 예시

```http
DELETE /api/v1/friends/00000001-0000-4000-8000-000000000001 HTTP/1.1
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
| 422 | `SELF_RELATION_NOT_ALLOWED` | 본인 userId 지정 | 대상 확인 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "SELF_RELATION_NOT_ALLOWED",
    "message": "본인 userId 지정",
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

<a id="friend-09"></a>
## FRIEND-09 · 사용자 차단

특정 사용자와의 관계·검색·비교 접근을 차단한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/users/{userId}/block` |
| 권한 | SELF |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `userId` | UUID | O | 불가 | 00000001-0000-4000-8000-000000000001 | 대상 회원 ID. 본인 프로필 API와 구분 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 방향 있는 차단을 저장하고 기존 친구·요청 관계를 제거한다.
2. 상대 방향의 차단도 독립적으로 유지한다. 이미 본인이 차단했다면 204를 반환한다.

### 요청 예시

```http
PUT /api/v1/users/00000001-0000-4000-8000-000000000001/block HTTP/1.1
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
| 422 | `SELF_RELATION_NOT_ALLOWED` | 본인 차단 | 대상 확인 |
| 404 | `RESOURCE_NOT_FOUND` | 대상이 없거나 조회 권한이 없음 | 이전 화면으로 이동. 숨겨진 대상의 존재를 추정하지 않음 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "SELF_RELATION_NOT_ALLOWED",
    "message": "본인 차단",
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

<a id="friend-10"></a>
## FRIEND-10 · 사용자 차단 해제

내가 설정한 차단만 해제한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `DELETE /api/v1/users/{userId}/block` |
| 권한 | SELF |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P07 ([결정 목록](../decisions.md)) |

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
| `userId` | UUID | O | 불가 | 00000001-0000-4000-8000-000000000001 | 대상 회원 ID. 본인 프로필 API와 구분 |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 친구/요청 관계를 자동 복구하지 않는다. 상대의 차단이 남아 있으면 검색과 공개 프로필에서 계속 숨긴다.
2. 이미 내 차단이 없어도 204. 상대가 나를 차단했는지 별도 응답하지 않는다.

### 요청 예시

```http
DELETE /api/v1/users/00000001-0000-4000-8000-000000000001/block HTTP/1.1
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
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)
