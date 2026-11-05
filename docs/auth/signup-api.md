# 이메일 회원가입 완료 API

공유 API 계약은 [`docs/api/auth.md`의 AUTH-01C](../api/auth.md#auth-01c)를 기준으로 한다.
이 문서는 #72 구현의 전체 성공 응답과 저장 규칙을 보충한다.

`POST /api/v1/auth/signup`은 이메일 인증 성공 시 받은 일회용 `registrationToken`과
비밀번호로 회원가입을 완료하는 공개 API다.

```json
{
  "registrationToken": "opaque-registration-token",
  "password": "user-entered-password"
}
```

성공 시 `201 Created`와 Session을 `{data,meta}` 형식으로 반환한다.

```json
{
  "data": {
    "sessionId": "00000010-0000-4000-8000-000000000010",
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "expiresAt": "2026-11-05T09:15:00+09:00",
    "user": {
      "id": "00000020-0000-4000-8000-000000000020",
      "nickname": null,
      "avatarUrl": null,
      "onboardingStatus": "REQUIRED",
      "comparisonConsent": false,
      "createdAt": "2026-11-05T09:00:00+09:00"
    }
  },
  "meta": {
    "requestId": "req-example",
    "serverTime": "2026-11-05T09:00:00+09:00"
  }
}
```

- registrationToken은 `SIGN_UP` 목적, 10분 만료, 미소비 상태인 경우에만 사용할 수 있다.
- 토큰 소비, User·EmailCredential·AuthSession 생성은 하나의 트랜잭션으로 처리한다.
- Access Token은 15분, Refresh Token은 최초 발급 기준 최대 90일 동안 유효하다.
- Refresh Token 원문 대신 SHA-256 해시만 세션에 저장한다.
- 신규 사용자는 닉네임·동의 온보딩 전이므로 `REQUIRED` 상태다.
- 이메일 중복이나 동시 가입 충돌은 `409 SIGNUP_NOT_AVAILABLE`로 처리해 계정 상태를
  불필요하게 노출하지 않는다.
- 비밀번호 오류는 `422 INVALID_PASSWORD`, 만료·사용 토큰은
  `400 INVALID_REGISTRATION_TOKEN`으로 처리한다.

비밀번호 검증은 [비밀번호 정책](password-policy.md)을 따른다. 비밀번호 원문과 해시는
응답·로그·외부 시스템으로 전달하지 않는다.
