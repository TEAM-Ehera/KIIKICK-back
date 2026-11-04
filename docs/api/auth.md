# 인증·온보딩·마이페이지 API

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정 목록](../decisions.md)

요구사항: **F01 / F09** · 관련 정책: **P01 / P11** · 계약 버전: **v0.4 검토 초안**

가입/로그인 제공자와 필수 동의는 P01/P11 미확정이다. 아래는 외부 제공자 인증을 채택할 경우의 상세 계약 후보다. 비밀번호 로그인·이미지 업로드·계정 연결은 이번 범위에서 확정하지 않는다.

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [AUTH-01](#auth-01) | 로그인·가입 토큰 교환 | `POST` | `/auth/exchange` |
| [AUTH-02](#auth-02) | 로그인 세션 갱신 | `POST` | `/auth/refresh` |
| [AUTH-03](#auth-03) | 로그아웃 | `POST` | `/auth/logout` |
| [AUTH-04](#auth-04) | 내 프로필 조회 | `GET` | `/users/me` |
| [AUTH-05](#auth-05) | 내 프로필 수정 | `PATCH` | `/users/me` |
| [AUTH-06](#auth-06) | 온보딩 완료 | `PUT` | `/users/me/onboarding` |
| [AUTH-07](#auth-07) | 현재 동의 목록 조회 | `GET` | `/users/me/consents` |
| [AUTH-08](#auth-08) | 동의 부여·철회 | `PUT` | `/users/me/consents/{purpose}` |
| [AUTH-09](#auth-09) | 회원 탈퇴 요청 | `POST` | `/users/me/deletion-requests` |
| [AUTH-10](#auth-10) | 탈퇴 처리 상태 조회 | `GET` | `/users/me/deletion-requests/{deletionId}` |

---

<a id="auth-01"></a>
## AUTH-01 · 로그인·가입 토큰 교환

인증 제공자에서 발급받은 토큰으로 앱 세션을 만든다. 기존 계정은 로그인, 최초 계정은 온보딩 필요 상태로 반환하는 후보 계약이다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/auth/exchange` |
| 권한 | 공개 |
| 성공 | **200** · [응답 필드 사전 · Session](schemas.md#session) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | X | 사용하지 않음 |
| Content-Type | O | `application/json` |
| Idempotency-Key | X | 사용하지 않음 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

Query parameter: 없음.

**Request body**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `provider` | string | O | 불가 | — | 지원 인증 제공자. EXAMPLE_PROVIDER는 예시이며 지원 목록 P01 미정 |
| `identityToken` | string | O | 불가 | — | 제공자가 발급한 신원 증명 토큰. 비밀값 |
| `authorizationCode` | string | X | 불가 | — | 선택한 제공자 흐름에서 요구할 때 필수 |
| `nonce` | string | X | 불가 | — | 제공자별 재전송 검증에 필요할 때 필수 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 서버가 제공자·audience·만료·nonce를 검증한다. 토큰은 로그에 남기지 않는다.
2. (provider,subject)로 계정을 유일하게 찾는다. 첫 가입이면 user.onboardingStatus=REQUIRED, nickname/avatarUrl=null을 반환한다.
3. 이메일/비밀번호 가입과 별도 회원가입 API의 필요 여부는 P01 확정 후 설계한다. 예시 토큰의 만료 간격은 운영 수명이 아니다.

### 요청 예시

```http
POST /api/v1/auth/exchange HTTP/1.1
Accept: application/json
Content-Type: application/json

{
  "provider": "EXAMPLE_PROVIDER",
  "identityToken": "example-provider-token",
  "authorizationCode": "example-code",
  "nonce": "example-nonce"
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Session](schemas.md#session)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `sessionId` | UUID | O | 불가 | — | 로그아웃 대상 세션 ID |
| `accessToken` | string | O | 불가 | — | 서버 인증에 사용하는 비밀값. 로그 금지 |
| `refreshToken` | string | O | 불가 | — | 갱신 시 회전하는 비밀값. 로그 금지 |
| `expiresAt` | timestamp | O | 불가 | — | 발급 시점부터 15분인 access token 만료 시각 |
| `user` | UserSelf | O | 불가 | — | 본인 계정·온보딩 정보 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "sessionId": "00000010-0000-4000-8000-000000000010",
    "accessToken": "example-access-token",
    "refreshToken": "example-refresh-token",
    "expiresAt": "2026-11-02T11:00:00+09:00",
    "user": {
      "id": "00000001-0000-4000-8000-000000000001",
      "nickname": "준",
      "avatarUrl": null,
      "onboardingStatus": "COMPLETED",
      "comparisonConsent": true,
      "createdAt": "2026-11-02T10:00:00+09:00"
    }
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
| 401 | `INVALID_CREDENTIALS` | 토큰 서명·대상·만료 검증 실패 | 제공자 로그인을 다시 진행 |
| 422 | `UNSUPPORTED_PROVIDER` | 지원하지 않는 인증 제공자 | 확정된 제공자 목록 확인 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 401**

```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "토큰 서명·대상·만료 검증 실패",
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

<a id="auth-02"></a>
## AUTH-02 · 로그인 세션 갱신

access token 만료 시 refresh token을 교환한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/auth/refresh` |
| 권한 | refresh token |
| 성공 | **200** · [응답 필드 사전 · Session](schemas.md#session) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | X | 사용하지 않음 |
| Content-Type | O | `application/json` |
| Idempotency-Key | X | 사용하지 않음 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

Query parameter: 없음.

**Request body**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `refreshToken` | string | O | 불가 | — | 가장 최근 발급된 refresh token. 원문 로그 금지 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 성공하면 기존 refresh token을 원자적으로 폐기하고 새 토큰을 안전하게 저장한다.
2. 일반 멱등 응답 캐시를 적용하지 않는다. 회전 응답 유실 시 유예/재로그인 정책과 이전 토큰 재사용 탐지는 P01 미정. 클라이언트는 동시 갱신을 한 건으로 합친다.

### 요청 예시

```http
POST /api/v1/auth/refresh HTTP/1.1
Accept: application/json
Content-Type: application/json

{
  "refreshToken": "example-refresh-token"
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Session](schemas.md#session)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `sessionId` | UUID | O | 불가 | — | 로그아웃 대상 세션 ID |
| `accessToken` | string | O | 불가 | — | 서버 인증에 사용하는 비밀값. 로그 금지 |
| `refreshToken` | string | O | 불가 | — | 갱신 시 회전하는 비밀값. 로그 금지 |
| `expiresAt` | timestamp | O | 불가 | — | 발급 시점부터 15분인 access token 만료 시각 |
| `user` | UserSelf | O | 불가 | — | 본인 계정·온보딩 정보 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "sessionId": "00000010-0000-4000-8000-000000000010",
    "accessToken": "example-access-token",
    "refreshToken": "example-refresh-token",
    "expiresAt": "2026-11-02T11:00:00+09:00",
    "user": {
      "id": "00000001-0000-4000-8000-000000000001",
      "nickname": "준",
      "avatarUrl": null,
      "onboardingStatus": "COMPLETED",
      "comparisonConsent": true,
      "createdAt": "2026-11-02T10:00:00+09:00"
    }
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
| 401 | `INVALID_REFRESH_TOKEN` | 만료·폐기·재사용된 토큰 | 재로그인 처리. 동일 토큰을 무한 재시도하지 않음 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 401**

```json
{
  "error": {
    "code": "INVALID_REFRESH_TOKEN",
    "message": "만료·폐기·재사용된 토큰",
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

<a id="auth-03"></a>
## AUTH-03 · 로그아웃

현재 또는 명시한 본인 세션을 폐기한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/auth/logout` |
| 권한 | SELF |
| 성공 | **204** · 본문 없음 |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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
| `sessionId` | UUID | X | 불가 | — | 생략하면 현재 access token의 세션 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 다른 세션의 로그아웃이 현재 세션 전체 로그아웃을 의미하지 않는다.
2. 같은 본인 세션의 반복 폐기는 204. Refresh Token 세션은 폐기하지만 기존 Access Token은 만료까지 최대 15분간 유효할 수 있다.

### 요청 예시

```http
POST /api/v1/auth/logout HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "sessionId": "00000010-0000-4000-8000-000000000010"
}
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
| 403 | `FORBIDDEN` | 타인 세션 ID를 지정 | 본인 세션만 지정 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 403**

```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "타인 세션 ID를 지정",
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

<a id="auth-04"></a>
## AUTH-04 · 내 프로필 조회

홈 진입·마이페이지·온보딩 분기를 위해 본인 프로필을 확인한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/users/me` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · UserSelf](schemas.md#userself) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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

1. comparisonConsent는 동의 최신 이력에서 파생하며 본인 프로필 PATCH로 변경하지 않는다.

### 요청 예시

```http
GET /api/v1/users/me HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · UserSelf](schemas.md#userself)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인 사용자 ID |
| `nickname` | string | O | 허용 | — | 온보딩 전 null 가능 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `onboardingStatus` | enum | O | 불가 | — | REQUIRED 또는 COMPLETED |
| `comparisonConsent` | boolean | O | 불가 | — | 비교 목적의 현재 동의값에서 파생 |
| `createdAt` | timestamp | O | 불가 | — | 계정 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000001-0000-4000-8000-000000000001",
    "nickname": "준",
    "avatarUrl": null,
    "onboardingStatus": "COMPLETED",
    "comparisonConsent": true,
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
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

[이 문서 위로](#api-바로가기) · [전체 API 목차](../api-spec.md)


---

<a id="auth-05"></a>
## AUTH-05 · 내 프로필 수정

닉네임 또는 프로필 이미지 URL을 변경한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PATCH /api/v1/users/me` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · UserSelf](schemas.md#userself) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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
| `nickname` | string | X | 불가 | — | 공백만 있는 값 거절. 중복/최대 길이 정책 P01/P07 미정 |
| `avatarUrl` | string | X | 허용 | — | 이미 등록된 이미지 URL. null은 이미지 제거 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 수정 필드가 최소 1개 필요하다. 이미지 업로드 API는 원문 확인 전 보류한다.
2. id, comparisonConsent, onboardingStatus 등 읽기 전용 필드는 422.

### 요청 예시

```http
PATCH /api/v1/users/me HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "nickname": "준킥",
  "avatarUrl": null
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · UserSelf](schemas.md#userself)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인 사용자 ID |
| `nickname` | string | O | 허용 | — | 온보딩 전 null 가능 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `onboardingStatus` | enum | O | 불가 | — | REQUIRED 또는 COMPLETED |
| `comparisonConsent` | boolean | O | 불가 | — | 비교 목적의 현재 동의값에서 파생 |
| `createdAt` | timestamp | O | 불가 | — | 계정 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000001-0000-4000-8000-000000000001",
    "nickname": "준킥",
    "avatarUrl": null,
    "onboardingStatus": "COMPLETED",
    "comparisonConsent": true,
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
| 422 | `VALIDATION_FAILED` | 빈 PATCH, 공백 닉네임, 허용되지 않은 필드 | details.fields에 따라 수정 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "빈 PATCH, 공백 닉네임, 허용되지 않은 필드",
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

<a id="auth-06"></a>
## AUTH-06 · 온보딩 완료

닉네임과 동의 내역을 제출하고 온보딩을 완료한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/users/me/onboarding` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · UserSelf](schemas.md#userself) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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
| `nickname` | string | O | 불가 | — | 사용자 표시명 |
| `consents` | array<object> | O | 불가 | — | 현재 정책의 동의 목록. 필수/선택 목적 구분은 P11 미정 |
| `consents[].purpose` | string | O | 불가 | — | 정책에 등록된 동의 목적 |
| `consents[].policyVersion` | string | O | 불가 | — | 해당 목적의 유효 정책 버전 |
| `consents[].granted` | boolean | O | 불가 | — | true 동의, false 거부 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 닉네임 저장·동의 이력 추가·온보딩 완료 처리는 한 트랜잭션이다.
2. 같은 멱등 키의 재전송은 이력을 중복 생성하지 않는다. 이미 완료한 온보딩을 다른 키로 다시 완료하려면 INVALID_STATE를 반환하는 안이다.
3. FRIEND_COMPARISON은 설명용 목적 코드다. 해당 항목만으로 필수 동의를 충족한다고 확정하지 않는다.

### 요청 예시

```http
PUT /api/v1/users/me/onboarding HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "nickname": "준",
  "consents": [
    {
      "purpose": "FRIEND_COMPARISON",
      "policyVersion": "2026-11-01",
      "granted": true
    }
  ]
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · UserSelf](schemas.md#userself)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인 사용자 ID |
| `nickname` | string | O | 허용 | — | 온보딩 전 null 가능 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `onboardingStatus` | enum | O | 불가 | — | REQUIRED 또는 COMPLETED |
| `comparisonConsent` | boolean | O | 불가 | — | 비교 목적의 현재 동의값에서 파생 |
| `createdAt` | timestamp | O | 불가 | — | 계정 생성 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000001-0000-4000-8000-000000000001",
    "nickname": "준",
    "avatarUrl": null,
    "onboardingStatus": "COMPLETED",
    "comparisonConsent": true,
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
| 422 | `REQUIRED_CONSENT_MISSING` | 필수 동의 누락/거절 | 필수 목적·버전을 확인해 재제출 |
| 409 | `INVALID_STATE` | 현재 상태에서 이 동작을 실행할 수 없음 | 최신 계정 상태를 조회하고 가능한 동작 표시 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "REQUIRED_CONSENT_MISSING",
    "message": "필수 동의 누락/거절",
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

<a id="auth-07"></a>
## AUTH-07 · 현재 동의 목록 조회

마이페이지 동의 설정을 표시한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/users/me/consents` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Consent](schemas.md#consent) 배열 |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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

1. 각 purpose의 최신 이력만 반환한다. 과거 동의 원문 전체를 노출하는 감사 API가 아니다.
2. 정책에 정의된 유한한 목적 목록으로 응답하며 cursor 페이지네이션을 사용하지 않는다. purpose 오름차순.

### 요청 예시

```http
GET /api/v1/users/me/consents HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data` 배열의 **요소 한 개**의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Consent](schemas.md#consent)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `purpose` | string | O | 불가 | — | 동의 목적 코드. 목록 P11 미정 |
| `policyVersion` | string | O | 불가 | — | 동의한 약관/정책 버전 |
| `granted` | boolean | O | 불가 | — | true 부여, false 철회 |
| `recordedAt` | timestamp | O | 불가 | — | 서버가 기록한 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": [
    {
      "purpose": "FRIEND_COMPARISON",
      "policyVersion": "2026-11-01",
      "granted": true,
      "recordedAt": "2026-11-02T10:00:00+09:00"
    }
  ],
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

<a id="auth-08"></a>
## AUTH-08 · 동의 부여·철회

특정 목적의 동의를 변경한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `PUT /api/v1/users/me/consents/{purpose}` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · Consent](schemas.md#consent) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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
| `purpose` | string | O | 불가 | FRIEND_COMPARISON | 정책에 등록된 동의 목적 코드. P11 미정 |

Query parameter: 없음.

**Request body**

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `policyVersion` | string | O | 불가 | — | 해당 목적에 대해 허용된 정책 버전 |
| `granted` | boolean | O | 불가 | — | false면 동의 철회 |

중첩 필드의 필수 O는 부모 객체/배열을 제출했을 때 적용한다. PATCH의 누락·null 규칙은 [공통 계약](common.md)을 따른다.

### 처리 규칙

1. 이력은 append-only로 추가한다. 최신 동의가 false이면 공개 조회 권한과 캐시를 즉시 재평가한다.
2. 동일 멱등 요청은 한 이력만 만든다. 필수 동의 철회 시 서비스 이용 처리 정책은 P11 확정 필요.

### 요청 예시

```http
PUT /api/v1/users/me/consents/FRIEND_COMPARISON HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
Content-Type: application/json

{
  "policyVersion": "2026-11-01",
  "granted": false
}
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · Consent](schemas.md#consent)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `purpose` | string | O | 불가 | — | 동의 목적 코드. 목록 P11 미정 |
| `policyVersion` | string | O | 불가 | — | 동의한 약관/정책 버전 |
| `granted` | boolean | O | 불가 | — | true 부여, false 철회 |
| `recordedAt` | timestamp | O | 불가 | — | 서버가 기록한 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "purpose": "FRIEND_COMPARISON",
    "policyVersion": "2026-11-01",
    "granted": false,
    "recordedAt": "2026-11-02T10:00:00+09:00"
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
| 422 | `VALIDATION_FAILED` | 알 수 없는 목적 또는 정책 버전 | 현재 정책의 목적/버전 확인 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |
| 422 | `VALIDATION_FAILED` | 누락·타입·읽기 전용 필드 등 검증 실패 | error.details.fields 확인 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 422**

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "알 수 없는 목적 또는 정책 버전",
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

<a id="auth-09"></a>
## AUTH-09 · 회원 탈퇴 요청

회원 탈퇴 처리를 접수한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/users/me/deletion-requests` |
| 권한 | SELF |
| 성공 | **202** · [응답 필드 사전 · DeletionRequest](schemas.md#deletionrequest) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

### 요청 헤더

| 헤더 | 필수 | 값 / 사용법 |
| --- | :---: | --- |
| Authorization | O | `Bearer <accessToken>` |
| Idempotency-Key | O | 같은 동작 재전송에는 동일 UUID 유지 |
| If-Match | X | 사용하지 않음 |

### 보내는 값: 경로·조회 조건·본문

Path parameter: 없음.

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 접수 성공은 실제 데이터 삭제 완료가 아니다. 반환 ID로 진행 상태를 조회한다.
2. 사용자당 활성 요청은 최대 하나다. 세션 폐기 시점·처리 기한·본인 운동/위치/심박 원본 삭제 범위는 P11 미확정.

### 요청 예시

```http
POST /api/v1/users/me/deletion-requests HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: 00000016-0000-4000-8000-000000000016
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · DeletionRequest](schemas.md#deletionrequest)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 탈퇴 처리 요청 ID |
| `status` | enum | O | 불가 | — | PENDING/PROCESSING/COMPLETED/FAILED |
| `requestedAt` | timestamp | O | 불가 | — | 접수 시각 |
| `completedAt` | timestamp | O | 허용 | — | 완료 시각 |

```http
HTTP/1.1 202 Accepted
X-Request-Id: req-example
Content-Type: application/json
Location: /api/v1/users/me/deletion-requests/00000014-0000-4000-8000-000000000014
Retry-After: 3
```

```json
{
  "data": {
    "id": "00000014-0000-4000-8000-000000000014",
    "status": "PENDING",
    "requestedAt": "2026-11-02T10:00:00+09:00",
    "completedAt": null
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
| 409 | `DELETION_ALREADY_REQUESTED` | 이미 활성 탈퇴 요청이 있음 | 기존 요청 상태 조회 |
| 401 | `UNAUTHENTICATED / TOKEN_EXPIRED` | access token 없음·만료 | 토큰 갱신/로그인 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키로 다른 본문/If-Match 제출 | 기존 요청 결과 확인. 별도 동작은 새 키 |

[공통 오류](common.md)에 정의한 400·403·429·500·503도 적용된다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 ID로 재등록하지 않는다.

**대표 오류 응답 · HTTP 409**

```json
{
  "error": {
    "code": "DELETION_ALREADY_REQUESTED",
    "message": "이미 활성 탈퇴 요청이 있음",
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

<a id="auth-10"></a>
## AUTH-10 · 탈퇴 처리 상태 조회

접수한 탈퇴 요청의 진행 상태를 확인한다.

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/users/me/deletion-requests/{deletionId}` |
| 권한 | SELF |
| 성공 | **200** · [응답 필드 사전 · DeletionRequest](schemas.md#deletionrequest) |
| 정책 검토 | P01 / P11 ([결정 목록](../decisions.md)) |

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
| `deletionId` | UUID | O | 불가 | 00000014-0000-4000-8000-000000000014 | 본인이 접수한 탈퇴 처리 요청 ID |

Query parameter: 없음.

Request body: 없음.

### 처리 규칙

1. 조회용 인증의 유효 기간과 탈퇴 완료 이후 조회 가능 범위는 P11 결정 대상이다. 삭제 완료 뒤 세션을 무기한 유지하지 않는다.

### 요청 예시

```http
GET /api/v1/users/me/deletion-requests/00000014-0000-4000-8000-000000000014 HTTP/1.1
Accept: application/json
Authorization: Bearer <accessToken>
```

### 성공 응답

아래는 `data`의 필드다. 중첩 객체의 전체 필드는 [응답 필드 사전 · DeletionRequest](schemas.md#deletionrequest)에서 확인한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 탈퇴 처리 요청 ID |
| `status` | enum | O | 불가 | — | PENDING/PROCESSING/COMPLETED/FAILED |
| `requestedAt` | timestamp | O | 불가 | — | 접수 시각 |
| `completedAt` | timestamp | O | 허용 | — | 완료 시각 |

```http
HTTP/1.1 200 OK
X-Request-Id: req-example
Content-Type: application/json
```

```json
{
  "data": {
    "id": "00000014-0000-4000-8000-000000000014",
    "status": "PENDING",
    "requestedAt": "2026-11-02T10:00:00+09:00",
    "completedAt": null
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
