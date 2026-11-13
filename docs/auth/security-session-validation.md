# Access Token 검증

- Access Token은 유효기간 15분의 Stateless JWT이며 HS256으로 서명한다.
- 서명 키는 환경변수로 관리하고, 서명·만료·`uid`·`sid`·`issuer`·`audience`·`token_type`을 검증한다.
- 보호 API는 인증 과정에서 DB·캐시의 `auth_sessions` 상태를 조회하지 않는다.
- 로그아웃·탈퇴·비밀번호 재설정은 Refresh Token 세션을 폐기하지만, 이미 발급된 Access Token은 만료까지 최대 15분간 유효할 수 있다.
- 서명 키와 토큰·비밀번호 원문은 로그에 남기지 않는다.

## 댓글 신고 관리자 권한

`/api/v1/admin/comment-reports`와 하위 경로는 서명 검증을 마친 JWT의 사용자 ID로 매 요청마다 DB 권한을 조회한다. `admin_users`에 등록되어 있고 `users.deleted_at`이 null이어야 한다. 인증 실패는 기존 401, 인증된 일반 사용자는 `403 FORBIDDEN`을 반환한다. 운영에서 권한을 회수하면 기존 Access Token을 사용해도 다음 요청부터 반영된다. 권한 부여·회수는 운영 절차로 수행한다.
