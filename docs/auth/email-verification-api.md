# 이메일 회원가입 인증 API

## 인증번호 발송·재발송

`POST /api/v1/auth/email-verifications`는 공개 API다.

```json
{"email":"user@example.com"}
```

성공 시 `202 Accepted`와 `verificationId`, 3분 만료 시각,
60초 재발송 가능 시각을 `{data,meta}`로 반환한다. 같은 이메일의 활성 Flow가 있으면
같은 `verificationId`로 재발송하며 기존 코드는 즉시 무효화하고 실패 횟수는 유지한다.

이메일별 24시간 5회와 IP별 1분 3회를 독립적으로 적용한다. 제한 또는 재발송 대기 중에는
`429 RATE_LIMITED`와 `Retry-After`를 반환한다. IP 원문은 저장하지 않고 HMAC 식별값만 저장한다.

## 인증번호 검증

`POST /api/v1/auth/email-verifications/{verificationId}/verify`에 아래 본문을 보낸다.

```json
{"code":"123456"}
```

성공 시 `200 OK`와 10분 동안 유효한 일회용 `registrationToken`을 반환한다.
인증번호는 최대 5회까지 검증할 수 있고 성공·만료 시 즉시 사용할 수 없게 된다.
코드와 registrationToken 원문은 응답이 필요한 순간을 제외하고 DB나 로그에 저장하지 않는다.

## SMTP 설정

실제 전송은 표준 SMTP를 사용하며 다음 환경변수가 필요하다.

- `AUTH_MAIL_HOST`, `AUTH_MAIL_PORT`
- `AUTH_MAIL_USERNAME`, `AUTH_MAIL_PASSWORD`
- `AUTH_MAIL_FROM`, `AUTH_MAIL_SENDER_NAME`
- `AUTH_MAIL_SMTP_AUTH`
- `AUTH_MAIL_STARTTLS_ENABLE`, `AUTH_MAIL_STARTTLS_REQUIRED`
- `AUTH_MAIL_CONNECTION_TIMEOUT_MS`, `AUTH_MAIL_READ_TIMEOUT_MS`, `AUTH_MAIL_WRITE_TIMEOUT_MS`
- `AUTH_EMAIL_CODE_HMAC_SECRET`, `AUTH_IP_HMAC_SECRET`: 각각 32바이트 이상

SMTP 실패 시 Flow와 Rate Limit 이벤트를 롤백하고 `503 EMAIL_DELIVERY_UNAVAILABLE`을 반환한다.
특정 SMTP 제공자 계정·비밀번호는 저장소에 저장하지 않는다.
