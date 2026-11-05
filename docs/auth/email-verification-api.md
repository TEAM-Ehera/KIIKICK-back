# 이메일 회원가입 인증 API

## 인증번호 발송·재발송

`POST /api/v1/auth/email-verifications`는 공개 API다.

```json
{"email":"user@example.com"}
```

성공 시 `202 Accepted`와 `verificationId`, 3분 만료 시각,
60초 재발송 가능 시각을 `{data,meta}`로 반환한다. 같은 이메일의 활성 Flow가 있으면
같은 `verificationId`로 재발송하며 기존 코드는 즉시 무효화하고 실패 횟수는 유지한다.
인증 완료 여부와 관계없이 마지막 발송부터 60초 미만이면 거부하며, 정확히 60초부터 허용한다.
인증 완료·무효화된 Flow는 재사용하지 않고 새 Flow를 생성하며 기존 registrationToken은 보존한다.

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

## 로컬 MySQL 통합 테스트

`./gradlew mysqlIntegrationTest --no-daemon`은 아래 환경변수가 모두 있어야 실행된다.

- `MYSQL_TEST_DB_HOST`: `127.0.0.1` 또는 `localhost`만 허용
- `MYSQL_TEST_DB_PORT`: `3306`만 허용
- `MYSQL_TEST_DB_NAME`: `kiikick_repository_test`만 허용
- `MYSQL_TEST_DB_USERNAME`, `MYSQL_TEST_DB_PASSWORD`: 로컬 테스트 전용 계정

기존 스키마는 미리 준비되어 있어야 하며 테스트가 스키마를 생성·수정하지 않는다.
Context 초기화 검사에서도 같은 제한을 적용하며, 다른 JDBC URL·Hikari 연결 설정·JNDI를 거부한다.
IDE 직접 실행은 위 환경변수와 테스트 클래스의 초기화 검사를 사용한다.
Spring Context를 생성하려면 SMTP·인증 관련 `AUTH_*` 테스트 설정도 필요하지만,
메일 발송기는 Mock이므로 외부 이메일은 발송하지 않는다. 비밀값을 저장소에 포함하지 않는다.

실행 전 `lsof -nP -iTCP:3306 -sTCP:LISTEN` 등으로 포트를 로컬 `mysqld`가 사용하는지 확인한다.
loopback 주소 검사만으로 SSH 터널·프록시의 원격 전달을 판별할 수는 없다.
포트의 실제 프로세스와 로컬 MySQL 데이터 경로를 확인할 수 없으면 실행하지 않는다.
AWS CLI·SSH·DB 생성 명령을 테스트에서 실행하지 않는다.
전용 계정은 해당 DB의 SELECT·INSERT·UPDATE·DELETE만 허용하고 DDL·전역 권한은 부여하지 않는다.

회귀 테스트는 제어 가능한 Clock으로 59초·60초 경계, 기존 토큰 보존,
별도 트랜잭션의 동시 발송을 검증한다. 고유 이메일·IP를 사용하고 해당 테스트의 데이터만 정리한다.
